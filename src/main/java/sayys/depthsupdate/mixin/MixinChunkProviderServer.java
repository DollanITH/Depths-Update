/**
 * 还原后的 ChunkProviderServer Mixin
 * 源文件: MixinChunkProviderServer.java
 * Minecraft版本: 1.12.2
 * 模组: Depths Update
 * <p>
 * 这个Mixin修改了世界生成流程，主要功能：
 * 1. 扩展世界高度（Height Extension）
 * 2. 过滤基岩层（Bedrock Filter）
 * 3. 填充自定义世界的深度
 * 4. 生成地下河流（Underground Rivers）
 * 5. 生成洞穴噪音（Cave Noise）
 */

package sayys.depthsupdate.mixin;

import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.util.BlockUtils;
import sayys.depthsupdate.world.generation.AquiferGenerator;
import sayys.depthsupdate.world.generation.ChunkPrimerAdapter;
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

    public MixinChunkProviderServer() {
        super();
    }

    /**
     * 重定向IChunkGenerator.generateChunks()方法
     *
     * 原始方法签名:
     * Chunk generateChunks(int x, int z)
     *
     * 被注入到的方法:
     * ChunkProviderServer.loadChunk(int x, int z)
     */
    @Redirect(
            method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/IChunkGenerator;generateChunk(II)Lnet/minecraft/world/chunk/Chunk;"
            )
    )
    private Chunk depthsupdate$onGenerateChunk(IChunkGenerator generator, int chunkX, int chunkZ) {
        Chunk chunk = generator.generateChunk(chunkX, chunkZ);
        int x = chunk.x;
        int z = chunk.z;

        // 检查是否是原版主世界生成器
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

        // 如果需要过滤基岩（原版主世界或扩展高度世界）
        if (!((isDeepWorld && (isVanillaOverworld || shouldFillCustom)))) {
            return chunk;
        }

        HeightContext ctx = HeightManager.get(this.world);
        int minY = ctx.minY();

        // Initialize fillRandom if not already done
        if (this.depthsupdate$fillRandom == null) {
            this.depthsupdate$fillRandom = new Random();
        }
        this.depthsupdate$fillRandom.setSeed((long) x * 341873128712L + (long) z * 132897987541L);

        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();
        IBlockState bedrock = Blocks.BEDROCK.getDefaultState();

        int deepslateMaxY = DepthsUpdateConfig.deepslateMaxY;
        int transitionRange = DepthsUpdateConfig.deepslateTransitionRange;
        int fullDeepslateY = deepslateMaxY - transitionRange;
        int fillMaxY = Math.max(4, deepslateMaxY);

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        boolean hasSkyLight = this.world.provider.hasSkyLight();

        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = minY; by <= fillMaxY; by++) {
                    IBlockState state;

                    if (by <= minY + this.depthsupdate$fillRandom.nextInt(5)) {
                        state = bedrock;
                    } else if (by <= fullDeepslateY) {
                        state = deepslate;
                    } else if (by < deepslateMaxY) {
                        double chance = (double) (deepslateMaxY - by) / (double) transitionRange;
                        if (this.depthsupdate$fillRandom.nextDouble() < chance) {
                            state = deepslate;
                        } else if (by < 0) {
                            state = stone;
                        } else {
                            continue;
                        }
                    } else if (by < 0) {
                        state = stone;
                    } else {
                        // check for vanilla bedrock replacement
                        if (by <= 4) {
                            int storageIdx = ctx.toStorageIndex(by);

                            if (storageIdx >= 0 && storageIdx < storageArrays.length) {
                                ExtendedBlockStorage section = storageArrays[storageIdx];
                                if (section != Chunk.NULL_BLOCK_STORAGE
                                        && section.get(bx, by & 15, bz).getBlock() == Blocks.BEDROCK) {
                                    section.set(bx, by & 15, bz, stone);
                                }
                            }
                        }

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

                    section.set(bx, by & 15, bz, state);
                }
            }
        }

        ChunkPrimerAdapter adapter = new ChunkPrimerAdapter(chunk, ctx);

        if (DepthsUpdateConfig.generateUndergroundRivers) {
            if (this.depthsupdate$riverGenerator == null) {
                this.depthsupdate$riverGenerator = new UndergroundRiverGenerator(this.world);
            }

            this.depthsupdate$riverGenerator.generate(x, z, adapter);
        }

        if (this.depthsupdate$noiseCaveGenerator == null) {
            this.depthsupdate$noiseCaveGenerator = new CaveNoiseGenerator(this.world);
        }

        this.depthsupdate$noiseCaveGenerator.generate(x, z, adapter);

        if (DepthsUpdateConfig.aquifers.enableAquifers) {
            if (this.depthsupdate$aquiferGenerator == null) {
                this.depthsupdate$aquiferGenerator = new AquiferGenerator(this.world);
            }

            this.depthsupdate$aquiferGenerator.generate(x, z, adapter);
        }

        chunk.generateSkylightMap();

        return chunk;
    }

}
