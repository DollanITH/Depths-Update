package sayys.depthsupdate.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldEntitySpawner;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightManager;

/**
 * 修正扩展高度下"随机生成点"的 Y 取值。
 *
 * <h2>原版逻辑与它为什么在虚空世界崩溃</h2>
 * 原版 {@code getRandomChunkPosition} 用高度图算出一个上界：
 * <pre>
 *   int bound = MathHelper.roundUp(chunk.getHeight(pos) + 1, 16);
 *   ...
 *   world.rand.nextInt(bound);
 * </pre>
 * 在普通世界里 {@code getHeight()} 是地表高度，{@code bound} 为正。
 * 但在<b>虚空/模板世界</b>（例如本模组服务器上 ID 06 的 {@code xu_wall} 生成器）
 * 整列没有任何方块，而本模组把高度图的初值设成了 {@code minY}（-64），于是：
 * <pre>
 *   getHeight() = minY + 1 = -63
 *   roundUp(-63 + 1, 16) = roundUp(-62, 16)
 *     MathHelper.roundUp 对负数按整数除法向 0 截断 -> (-62 + 15) / 16 * 16 = -32
 *   bound = -32  <= 0   ->  nextInt(-32) 抛 IllegalArgumentException: bound must be positive
 * </pre>
 * 实测崩溃栈：
 * <pre>
 *   java.lang.IllegalArgumentException: bound must be positive
 *     at java.util.Random.nextInt
 *     at WorldEntitySpawner.getRandomChunkPosition
 *     at WorldEntitySpawner.findChunksForSpawning
 * </pre>
 *
 * <h2>修法</h2>
 * 接管整个方法，用<b>实际已填充的段</b>而不是高度图来界定 Y 范围，
 * 并保证 {@code range} 恒为正、最终取值为合法（&gt;= minY）的坐标。
 * 这样虚空世界也能安全地"生成点落在 minY 附近"而不是抛异常。
 */
@Mixin(WorldEntitySpawner.class)
public class MixinWorldEntitySpawner {
    @Inject(method = "getRandomChunkPosition", at = @At("HEAD"), cancellable = true)
    private static void depthsupdate$fixGetRandomChunkPosition(World worldIn, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
        if (!HeightManager.isExtended(worldIn)) {
            return;
        }

        Chunk chunk = worldIn.getChunk(x, z);
        int minY = HeightManager.getMinY(worldIn);
        int maxY = HeightManager.getMaxY(worldIn);

        // ★ 用"已填充段的顶端"界定范围，并且【永远保证 range >= 1】。
        //
        // 虚空世界里 getTopFilledSegment() 会落在很低的段（甚至返回 0），
        // 单靠它算出的区间可能是负的；因此这里再与 minY/maxY 夹紧一次，
        // 使 range 始终 >= 1，nextInt 不会再收到非正数。
        int topY = chunk.getTopFilledSegment() + 16;

        if (topY <= minY) {
            topY = minY + 16;
        }

        if (topY > maxY) {
            topY = maxY;
        }

        int range = topY - minY;

        if (range < 1) {
            range = 1;
        }

        int y = minY + worldIn.rand.nextInt(range);

        // 双保险：即使上面全部退化，y 也一定是 minY..maxY-1 内的合法值。
        if (y < minY) {
            y = minY;
        } else if (y >= maxY) {
            y = maxY - 1;
        }

        cir.setReturnValue(new BlockPos(x * 16 + worldIn.rand.nextInt(16), y, z * 16 + worldIn.rand.nextInt(16)));
    }
}
