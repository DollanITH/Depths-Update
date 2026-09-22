package sayys.depthsupdate.mixin.mod.journeymap;

import journeymap.client.cartography.render.SurfaceRenderer;
import journeymap.client.model.block.BlockMD;
import journeymap.client.model.chunk.ChunkMD;
import net.minecraft.init.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复地表地图的空列刷新 bug：
 *
 * 现象：当某一列（XZ 坐标）下方没有任何方块时，打掉这一列唯一的方块后，
 * 地图不刷新，仍然绘制已打掉的方块。
 *
 * 根因：JourneyMap 的 {@link SurfaceRenderer#renderSurface} 判断"该列是虚空/空列"时，
 * 用的是 upperY（= max(minY, getPrecipitationHeight)）是否等于 minY。
 * 而 getPrecipitationHeight 来自 Chunk 的高度图，方块被破坏后不会更新（在扩展负 Y
 * 高度的世界里尤其如此——它只反映 0~255 的旧生成值），所以空列的 upperY 仍远大于
 * minY，空列判断永远不成立；随后 buildStrata 找不到任何可绘制方块、paintStrata 走
 * paintBadBlock 空操作、renderSurface 返回 false。ChunkRenderController 因此不会
 * 调用 setChunkImage，旧图（已打掉的方块）就一直残留在 region 贴图里。
 *
 * 修复：把 renderSurface 里对 getPrecipitationHeight 的调用重定向——当该列实际
 * 已经没有可绘制方块（getBlockHeight 已回落到 minY，且 minY 处是空气）时返回
 * minY，让原逻辑进入 paintVoidBlock 分支，正确绘制虚空并把该 chunk 标记为已渲染。
 * 对正常列不做任何改动。
 */
@Mixin(SurfaceRenderer.class)
public abstract class MixinJourneyMapSurfaceRenderer {

    @Shadow
    public abstract Integer getBlockHeight(ChunkMD chunkMd, int localX, Integer vSlice, int localZ, Integer sliceMinY, Integer sliceMaxY);

    @Redirect(
            method = "renderSurface(Ljourneymap/client/texture/ComparableNativeImage;Ljourneymap/client/texture/ComparableNativeImage;Ljourneymap/client/model/chunk/ChunkMD;Ljava/lang/Integer;Z)Z",
            at = @At(value = "INVOKE", target = "Ljourneymap/client/model/chunk/ChunkMD;getPrecipitationHeight(II)I", remap = false),
            remap = false,
            require = 1
    )
    private int depthsupdate$fixEmptyColumn(ChunkMD chunkMd, int x, int z) {
        int original = chunkMd.getPrecipitationHeight(x, z);
        int minY = chunkMd.getMinY();
        // 原逻辑已经能正确处理的情况（例如普通世界里高度图已回落到世界底部）
        if (original <= minY) {
            return original;
        }
        // 该列最顶部的可绘制方块高度；这里 getBlockHeight 已被 renderSurface 先调用过，
        // 命中的是本回合的高度缓存，不会重复扫描整列。
        Integer blockHeight = this.getBlockHeight(chunkMd, x, null, z, null, null);
        if (blockHeight == null || blockHeight > minY) {
            // 列里还有方块，保持原样
            return original;
        }
        // blockHeight 已回落到 minY：整列（minY 及以上）已经没有任何可绘制方块。
        // 只有 minY 处确实是空气时才判定为空列，否则应绘制 minY 处的方块。
        BlockMD blockMD = BlockMD.getBlockMDFromChunkLocal(chunkMd, x, minY, z);
        if (blockMD != null && blockMD.getBlockState().getBlock() == Blocks.AIR) {
            return minY;
        }
        return original;
    }
}
