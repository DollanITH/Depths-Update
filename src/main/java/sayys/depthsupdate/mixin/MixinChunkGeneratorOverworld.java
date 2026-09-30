/**
 * 合并版 MixinChunkGeneratorOverworld（fork 本地逻辑 + 上游架构）
 * Minecraft版本: 1.12.2
 * 模组: Depths Update
 * <p>
 * 合并后主要功能（setBlocksInChunk 返回后）：
 * 1. 扩展高度守卫：仅扩展世界（minY &lt; 0）且为本类（非子类）时生效（上游机制）
 * 2. DeepFill 统一分带填充原版主世界深度（上游机制）
 * 3. 地下河流 / 洞穴噪音 / 含水层生成（fork 保留，配置开关）
 * <p>
 * 与上游的差异（fork 保留的行为）：
 * - 上游在此用 replaceBiomeBlocks 注入携带真实生物群系雕刻洞穴噪音；
 *   fork 的 CaveNoiseGenerator 接口不接收 biomes，且新块深部雕刻由
 *   MixinChunkProviderServer 完成，因此这里沿用 fork 的 setBlocksInChunk 注入。
 * - 洞穴噪音 / 含水层保留 fork 的配置开关（REGISTRY.enable118Caves / aquifers.enableAquifers）。
 */

package sayys.depthsupdate.mixin;

import java.util.Random;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.core.DeepFill;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.util.BlockUtils;
import sayys.depthsupdate.world.generation.AquiferGenerator;
import sayys.depthsupdate.world.generation.noise.CaveNoiseGenerator;
import sayys.depthsupdate.world.generation.river.UndergroundRiverGenerator;

@Mixin(ChunkGeneratorOverworld.class)
public abstract class MixinChunkGeneratorOverworld {
    @Final
    @Shadow
    private World world;

    @Shadow
    @Final
    private Random rand;

    @Unique
    private UndergroundRiverGenerator depthsupdate$riverGenerator;

    @Unique
    private sayys.depthsupdate.world.generation.noise.CaveNoiseGenerator depthsupdate$noiseCaveGenerator;

    @Unique
    private AquiferGenerator depthsupdate$aquiferGenerator;

    @Inject(method = "setBlocksInChunk", at = @At("RETURN"))
    private void depthsupdate$fillDeepUnderground(int x, int z, ChunkPrimer primer, CallbackInfo ci) {
        // 上游守卫：跳过子类生成器，避免重复处理
        if (((Object) this).getClass() != ChunkGeneratorOverworld.class) {
            return;
        }

        // 上游守卫：仅在扩展高度世界生效
        if (!HeightManager.isExtended(this.world) || HeightManager.get(this.world).minY() >= 0) {
            return;
        }

        HeightContext ctx = HeightManager.get(this.world);
        int minY = ctx.minY();
        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();
        IBlockState bedrock = Blocks.BEDROCK.getDefaultState();

        int fillMaxY = Math.max(0, DepthsUpdateConfig.deepslateMaxY);

        // 深部(by<0)填充由 MixinChunkProviderServer.onProvideChunk 在 generateChunk
        // 返回后直接写入扩展存储完成；此处是生成阶段的 Sponge ChunkPrimerBuffer，
        // Y 范围固定 0..255，不能写负数，故下限夹到 0（否则 PositionOutOfBoundsException）。
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = Math.max(0, minY); by <= fillMaxY; by++) {
                    IBlockState banded = DeepFill.bandAt(by, minY, this.rand, bedrock, deepslate, stone);

                    if (banded != null) {
                        primer.setBlockState(bx, by, bz, banded);
                    }
                }
            }
        }

        // NOTE: 新扩展主世界块的深部雕刻实际由
        // MixinChunkProviderServer.depthsupdate$onGenerateChunk 完成（generateChunk
        // 返回后重填深度再雕刻）；此处仅保留浅层/过渡部分的生成器侧处理。
        if (DepthsUpdateConfig.generateUndergroundRivers) {
            if (this.depthsupdate$riverGenerator == null) {
                this.depthsupdate$riverGenerator = new UndergroundRiverGenerator(this.world);
            }

            this.depthsupdate$riverGenerator.generate(x, z, primer);
        }

        if (DepthsUpdateConfig.REGISTRY.enable118Caves) {
            if (this.depthsupdate$noiseCaveGenerator == null) {
                this.depthsupdate$noiseCaveGenerator =
                        new sayys.depthsupdate.world.generation.noise.CaveNoiseGenerator(this.world);
            }

            this.depthsupdate$noiseCaveGenerator.generate(x, z, primer);
        }

        if (DepthsUpdateConfig.aquifers.enableAquifers) {
            if (this.depthsupdate$aquiferGenerator == null) {
                this.depthsupdate$aquiferGenerator = new AquiferGenerator(this.world);
            }

            this.depthsupdate$aquiferGenerator.generate(x, z, primer);
        }
    }
}
