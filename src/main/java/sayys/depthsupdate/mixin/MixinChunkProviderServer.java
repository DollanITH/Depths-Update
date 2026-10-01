/**
 * 合并版 ChunkProviderServer Mixin（fork 本地逻辑 + 上游架构）
 * Minecraft版本: 1.12.2
 * 模组: Depths Update
 * <p>
 * 这个Mixin修改了世界生成流程，合并后主要功能：
 * 1. 扩展世界高度（Height Extension）
 * 2. 基岩过滤（Bedrock Filter，上游机制：生成期间在源头取消 y=0..4 的基岩写入）
 * 3. 自定义世界深度填充（DeepFill 分带填充，上游机制）
 * 4. 生成地下河流（Underground Rivers）
 * 5. 生成洞穴噪音（Cave Noise）
 * 6. 老式深穴雕刻（OldStyleDeepCaveCarver，fork 保留）
 * 7. 含水层生成（Aquifers，fork 保留）
 * <p>
 * 与上游的差异（fork 保留的行为）：
 * - 上游只处理自定义世界；fork 同时处理原版主世界（generateChunk 返回后重填深度并雕刻）。
 * - 基岩过滤仅在自定义世界激活；原版主世界仍用事后替换（backstop）把 y=0..4 的基岩换成石头。
 * - 洞穴/含水层/老式深穴均保留 fork 的配置开关。
 */

package sayys.depthsupdate.mixin;

import java.util.Random;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkGeneratorDebug;
import net.minecraft.world.gen.ChunkGeneratorFlat;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.core.DeepFill;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.util.BlockUtils;
import sayys.depthsupdate.world.generation.AquiferGenerator;
import sayys.depthsupdate.world.generation.ChunkPrimerAdapter;
import sayys.depthsupdate.world.generation.OldStyleDeepCaveCarver;
import sayys.depthsupdate.world.generation.noise.CaveNoiseGenerator;
import sayys.depthsupdate.world.generation.river.UndergroundRiverGenerator;

/**
 * Mixin用于修改ChunkProviderServer的行为
 */
@Mixin({ChunkProviderServer.class})
public class MixinChunkProviderServer {

    @Shadow
    @Final
    public IChunkGenerator chunkGenerator;

    @Shadow
    @Final
    public WorldServer world;

    @Unique
    private Random depthsupdate$fillRandom;

    @Unique
    private UndergroundRiverGenerator depthsupdate$riverGenerator;

    @Unique
    private CaveNoiseGenerator depthsupdate$noiseCaveGenerator;

    @Unique
    private AquiferGenerator depthsupdate$aquiferGenerator;

    /**
     * 已做过深度后处理的 chunk 坐标（(x<<32)|z）。provideChunk 对同一 chunk 会被
     * populate 期间反复调用（RTG 地牢等 getBlockState→getChunk 每次都命中缓存），
     * 若每个缓存命中都重跑填充+日志会刷屏并卡死 Server thread。用此集合保证每 chunk
     * 只处理一次。
     */
    @Unique
    private final java.util.Set<Long> depthsupdate$processedChunks = new java.util.HashSet<>();

    public MixinChunkProviderServer() {
        super();
    }

    /**
     * 在 ChunkProviderServer.provideChunk(int,int)Chunk 返回时注入，
     * 对刚生成好的 Chunk 做深度后处理（重填深度、基岩 backstop、河流/洞穴/含水层、天空光）。
     *
     * 说明：原实现用 @Redirect 重定向 generateChunk 调用，但其 @At(INVOKE) 指向的
     * IChunkGenerator.generateChunk（接口调用点）在本 Cleanroom 运行时无法命中，导致
     * "Scanned 0 target(s)" 崩溃。改为 @Inject @At("RETURN") 只依赖方法本身（refmap
     * 已验证能映射 provideChunk→func_186025_d），不依赖任何调用点，更稳健。
     */
    @Inject(
            method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("RETURN")
    )
    private void depthsupdate$onProvideChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        // 检查是否是原版主世界生成器
        IChunkGenerator generator = this.chunkGenerator;
        boolean isVanillaOverworld = generator instanceof ChunkGeneratorOverworld;

        // 检查是否是扁平化或调试世界生成器
        boolean isFlatOrDebug = generator instanceof ChunkGeneratorFlat || generator instanceof ChunkGeneratorDebug;

        // 检查是否是扩展高度的世界
        boolean isDeepWorld = !isFlatOrDebug &&
                HeightManager.isExtended(this.world) &&
                HeightManager.get(this.world).minY() < 0;

        // 如果是扩展高度世界且不是自定义生成器，则填充深度
        boolean shouldFillCustom = isDeepWorld && !isVanillaOverworld &&
                DepthsUpdateConfig.heightExtension.extendCustomWorldTypes;

        // fork 语义：扩展高度下的原版主世界和自定义世界都要重填深度
        boolean processChunk = isDeepWorld && (isVanillaOverworld || shouldFillCustom);

        if (!processChunk) {
            return;
        }

        // 每 chunk 只处理一次：缓存命中（populate 期间反复调用 provideChunk）直接返回，
        // 避免对同一 chunk 重复重填 + 重复刷 WARN 日志把 Server thread 卡在控制台锁上。
        long key = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
        if (!this.depthsupdate$processedChunks.add(key)) {
            return;
        }

        HeightContext ctx = HeightManager.get(this.world);
        int minY = ctx.minY();

        // Initialize fillRandom if not already done
        if (this.depthsupdate$fillRandom == null) {
            this.depthsupdate$fillRandom = new Random();
        }
        this.depthsupdate$fillRandom.setSeed((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);

        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();
        IBlockState bedrock = Blocks.BEDROCK.getDefaultState();

        int deepslateMaxY = DepthsUpdateConfig.deepslateMaxY;
        int fillMaxY = Math.max(4, deepslateMaxY);

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        boolean hasSkyLight = this.world.provider.hasSkyLight();

        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = minY; by <= fillMaxY; by++) {
                    // 上游 backstop：把直接写入 storage（绕过 primer）的 y=0..4 基岩换回石头
                    if (by >= 0 && by <= 4) {
                        int storageIdx = ctx.toStorageIndex(by);

                        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
                            ExtendedBlockStorage section = storageArrays[storageIdx];

                            if (section != Chunk.NULL_BLOCK_STORAGE
                                    && section.get(bx, by & 15, bz).getBlock() == Blocks.BEDROCK) {
                                section.set(bx, by & 15, bz, stone);
                            }
                        }
                    }

                    // 上游：统一分带填充（基岩底、深板岩带、过渡带、y<0 石头）
                    IBlockState state = DeepFill.bandAt(by, minY, this.depthsupdate$fillRandom, bedrock, deepslate, stone);

                    if (state == null) {
                        continue;
                    }

                    int storageIdx = ctx.toStorageIndex(by);

                    if (storageIdx < 0 || storageIdx >= storageArrays.length) {
                        continue;
                    }

                    ExtendedBlockStorage section = storageArrays[storageIdx];

                    if (section == Chunk.NULL_BLOCK_STORAGE) {
                        section = new ExtendedBlockStorage(by >> 4 << 4, hasSkyLight);
                        storageArrays[storageIdx] = section;
                    }

                    // 上游：0 以上只重染石头，不覆盖已有地形与洞穴
                    if (by >= 0 && section.get(bx, by & 15, bz).getBlock() != Blocks.STONE) {
                        continue;
                    }

                    section.set(bx, by & 15, bz, state);
                }
            }
        }

        ChunkPrimerAdapter adapter = new ChunkPrimerAdapter(chunk, ctx);

        if (DepthsUpdateConfig.heightExtension.carveOldStyleDeepCaves) {
            OldStyleDeepCaveCarver.carve(this.world, chunkX, chunkZ, adapter, ctx, 6);
        }

        if (DepthsUpdateConfig.generateUndergroundRivers) {
            if (this.depthsupdate$riverGenerator == null) {
                this.depthsupdate$riverGenerator = new UndergroundRiverGenerator(this.world);
            }
            this.depthsupdate$riverGenerator.generate(chunkX, chunkZ, adapter);
        }

        if (DepthsUpdateConfig.REGISTRY.enable118Caves) {
            if (this.depthsupdate$noiseCaveGenerator == null) {
                this.depthsupdate$noiseCaveGenerator = new CaveNoiseGenerator(this.world);
            }
            this.depthsupdate$noiseCaveGenerator.generate(chunkX, chunkZ, adapter);
        }

        if (DepthsUpdateConfig.aquifers.enableAquifers) {
            if (this.depthsupdate$aquiferGenerator == null) {
                this.depthsupdate$aquiferGenerator = new AquiferGenerator(this.world);
            }
            this.depthsupdate$aquiferGenerator.generate(chunkX, chunkZ, adapter);
        }

        chunk.generateSkylightMap();
    }

}
