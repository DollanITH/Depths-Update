package sayys.depthsupdate.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sayys.depthsupdate.core.HeightManager;

/**
 * 扩展高度下 {@code Chunk.getPrecipitationHeight} 的修正。
 *
 * <h2>★ 根因：原版内部把 -1 写进了降雨高度缓存</h2>
 * 原版逻辑（已逐条核对字节码）：
 * <pre>
 *   86: j = -1
 *   89: while (pos.getY() &gt; 0 &amp;&amp; j == -1) {          // ★ 这个 0 是硬编码的世界底
 *           if (material.blocksMovement() || material.isLiquid()) j = pos.getY() + 1;
 *           else pos = pos.down();
 *       }
 *  158: precipitationHeightMap[i] = j;                  // ★ 若循环因 Y&lt;=0 退出，这里写入 -1
 *  167: return new BlockPos(x, precipitationHeightMap[i], z)
 * </pre>
 *
 * <p>扩展世界（本模组的世界底是 {@code minY}，例如 -64）里，某一列在
 * {@code 0..topFilledSegment} 之间可能<b>全是空气</b>（下界 RTG 地形、被挖空的区域等）。
 * 此时循环因 {@code pos.getY() > 0} 不成立而退出，{@code j} 仍是 {@code -1}，
 * 于是：
 * <pre>
 *   precipitationHeightMap[i] = -1      // 污染缓存
 *   return BlockPos(x, -1, z)           // 把 -1 当"高度"抛给调用方
 * </pre>
 * 调用方会把该 Y 当作数组下标使用，实测崩溃：
 * <pre>
 *   java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 16
 *     at net.minecraft.world.chunk.Chunk.getPrecipitationHeight
 *     at net.minecraft.world.World.notifyLightSet
 *     at net.minecraft.world.World.notifyNeighborsRespectDebug
 *     at net.minecraft.world.WorldServer.tick
 * </pre>
 * 表现为"进入 DIM-1 就崩服"。
 *
 * <h2>★ 曾经的错误修法</h2>
 * 此前这里在 HEAD 直接取消原方法，返回
 * {@code new BlockPos(x, this.heightMap[z << 4 | x], z)}。两处都不对：
 * <ul>
 *   <li>读错了数组 —— 原版用的是 {@code precipitationHeightMap}，
 *       而 {@code heightMap} 被本模组在 {@code generateSkylightMap} 里初始化成
 *       {@code minY}，语义完全不同；</li>
 *   <li>它把 {@code minY}（-64）当"降雨高度"返回，<b>同样是一个非法 Y</b>，
 *       下游照样会越界 —— 只是换了个触发路径。</li>
 * </ul>
 *
 * <h2>现做法</h2>
 * 保留原版方法的全部逻辑（{@code precipitationHeightMap} 缓存、材质判定、写回），
 * 只在其返回后把<b>非法的 Y</b>（低于本维度 {@code minY}，含原版写入的 -1）
 * 修正为该维度的 {@code minY}。
 *
 * <p>这样：
 * <ul>
 *   <li>正常情况（返回值合法）完全不干预，与原版一致；</li>
 *   <li>原版会写出 -1 的退化情形被抬到 {@code minY}，既不再越界，
 *       语义上也正确 —— "这列没有可降水的实体表面，取世界底"。</li>
 * </ul>
 */
@Mixin(value = Chunk.class, priority = 2500)
public abstract class MixinChunkPrecipitationHeight {

    @Final
    @Shadow
    private net.minecraft.world.World world;

    @Inject(method = "getPrecipitationHeight(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;",
            at = @At("RETURN"), cancellable = true)
    private void depthsupdate$clampPrecipitationHeight(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos result = cir.getReturnValue();

        if (result == null) {
            return;
        }

        int minY = HeightManager.getMinY(this.world);

        // 原版在"整列无实体表面"时会写入 -1；扩展世界里也可能低于 minY。
        // 两者都不是合法高度，一律抬到本维度的 minY。
        if (result.getY() >= minY) {
            return;
        }

        cir.setReturnValue(new BlockPos(result.getX(), minY, result.getZ()));
    }
}
