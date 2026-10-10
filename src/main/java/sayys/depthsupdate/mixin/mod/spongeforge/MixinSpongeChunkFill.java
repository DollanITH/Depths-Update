package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * SpongeForge 的区块生成走的是 {@code SpongeChunkGenerator.generateChunk}，它并不使用
 * vanilla 的 {@code Chunk(World, ChunkPrimer, int, int)} 构造器（depthsupdate 的
 * {@code MixinChunk#onChunkPrimerInit} 正挂在那里），而是走
 * {@code ChunkBridge.bridge$fill(ChunkPrimer)}。
 *
 * <p>问题在于 Sponge 的 {@code ChunkMixin#bridge$fill} 里写死了：
 * <pre>
 *   for (int y = 0; y &lt; 256; y++) {          // Y 只有 0..255
 *       if (storageArrays[y &gt;&gt; 4] == null) storageArrays[y &gt;&gt; 4] = new ExtendedBlockStorage((y &gt;&gt; 4) &lt;&lt; 4, sky);
 *       storageArrays[y &gt;&gt; 4].set(x, y &amp; 15, z, state);
 *   }
 * </pre>
 * 于是生成期写入 primer 的 y&lt;0 / y&gt;=256 区块被整体丢弃，
 * 服务端在扩展高度上"没有方块"（假方块：客户端渲染了、服务端取到空气），
 * 同时因为越界 {@code storageArrays[y &gt;&gt; 4]} 对 y&lt;0 会取到负下标而无法放置。
 *
 * <p>这里在 {@code bridge$fill} 返回后补一遍扩展高度（minY..-1 与 256..maxY-1），
 * 使用 {@link HeightContext#toStorageIndex(int)} 把世界 Y 映射到扩展存储下标，
 * 与 {@code MixinChunk#onChunkPrimerInit} 的行为保持一致；非扩展世界直接跳过，
 * 完全不影响原生行为。
 * <p>
 * 仅当 Sponge 存在时由 ModMixinConfigPlugin 启用（MOD 阶段，晚于 Sponge 的 DEFAULT 阶段
 * mixin，因此 {@code bridge$fill} 一定已存在），故 remap=false。
 */
@Mixin(targets = "net.minecraft.world.chunk.Chunk", remap = false)
public abstract class MixinSpongeChunkFill {

    @Inject(method = "bridge$fill", at = @At("RETURN"), remap = false, require = 0)
    private void depthsupdate$fillExtendedHeight(ChunkPrimer primer, CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        World world = self.getWorld();

        if (world == null) {
            return;
        }

        HeightContext ctx = HeightManager.get(world);

        if (!ctx.isExtended()) {
            return;
        }

        boolean hasSkyLight = world.provider.hasSkyLight();
        ExtendedBlockStorage[] storageArrays = self.getBlockStorageArray();
        int minY = ctx.minY();
        int maxY = ctx.maxY();

        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                // 0..255 已由 Sponge 的 bridge$fill 处理，这里只补扩展高度（负层 / 上层）。
                for (int y = minY; y < maxY; ++y) {
                    if (y >= 0 && y < 256) {
                        continue;
                    }

                    IBlockState state = primer.getBlockState(x, y, z);

                    if (state.getMaterial() == Material.AIR) {
                        continue;
                    }

                    int storageIndex = ctx.toStorageIndex(y);

                    if (storageIndex < 0 || storageIndex >= storageArrays.length) {
                        continue;
                    }

                    ExtendedBlockStorage storage = storageArrays[storageIndex];

                    if (storage == null) {
                        storage = new ExtendedBlockStorage(y >> 4 << 4, hasSkyLight);
                        storageArrays[storageIndex] = storage;
                    }

                    storage.set(x, y & 15, z, state);
                }
            }
        }
    }
}
