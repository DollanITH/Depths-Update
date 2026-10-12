package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 修复 SpongeForge 下 {@code BlockPos.MutableBlockPos} 的硬编码高度判断。
 *
 * <h2>★ 这是"深层岩浆/火焰伤害全部失效"的真正根因</h2>
 *
 * <p>Sponge 有<b>两套</b> {@code BlockPosBridge} 实现，分别挂在不同的类上：
 * <pre>
 *   Vec3iMixin                            -> @Mixin(Vec3i.class)
 *     bridge$isInvalidYPosition(): field_177962_a/b/c 的 y ... 0..255
 *
 *   BlockPos_MutableBlockPosMixin_Optional -> @Mixin(BlockPos$MutableBlockPos.class)   ★
 *     bridge$isInvalidYPosition():
 *        0: getfield field_177998_c     &lt;- MutableBlockPos 自己的 y 字段
 *        4: iflt 17
 *        7: getfield field_177998_c
 *       11: sipush 256
 *       14: if_icmplt 21
 *       17: iconst_1 (true)   &lt;- y &lt; 0 或 y &gt;= 256 -&gt; "非法"
 *       21: iconst_0 (false)
 * </pre>
 *
 * <p>本项目原先只修了 <b>Vec3i</b> 那一个（{@link MixinSpongeVec3iHeight}）。
 * 但 {@code MutableBlockPos} 继承 {@code BlockPos} 继承 {@code Vec3i}，
 * 它<b>自己重写</b>了这些桥方法 —— 于是对 {@code MutableBlockPos} 实例，
 * 虚分派走的是 Sponge 在子类上的实现，Vec3i 上的修复<b>完全不起作用</b>。
 *
 * <h2>症状为什么是"岩浆伤害无效"而不是"方块看不见"</h2>
 * 原版 {@code World.isMaterialInBB} 遍历 AABB 时用的坐标对象是
 * {@code BlockPos$PooledMutableBlockPos}（继承 {@code MutableBlockPos}）：
 * <pre>
 *   World.isMaterialInBB:
 *     53: PooledMutableBlockPos.retain()
 *     99: pooled.setPos(x, y, z)
 *    102: World.getBlockState(pooled)      &lt;- 走 Sponge 的 getBlockState
 *                                            -&gt; pooled.bridge$isInvalidYPosition()
 *                                            -&gt; MutableBlockPos 版本, y&lt;0 =&gt; true
 *                                            -&gt; 直接返回 AIR
 * </pre>
 * 而 {@code Entity.isInLava()} 就是
 * {@code world.isMaterialInBB(bb.grow(-0.1,-0.4,-0.1), Material.LAVA)}。
 * 于是岩浆判定恒为 false —— 不起火、不受岩浆伤害，且
 * {@code onEntityUpdate} 里紧随其后的 {@code fallDistance *= 0.5f} 也不执行
 * （实测印证：实体在岩浆中"正常下落并受到完整摔落伤害"）。
 *
 * <p>相反，直接传 {@code new BlockPos(...)} 的代码路径（例如光照、碰撞、
 * 玩家自己站在方块上）不受影响，所以"只有 AABB 材料判定相关的功能"坏掉了。
 *
 * <h2>实测对照（同一坐标、同一次调用）</h2>
 * <pre>
 *   COMPARE pos=(-72,-56,19) pooled=minecraft:air | fresh=minecraft:lava[level=8]
 *   &gt;&gt;&gt; DIFFERENT=true
 * </pre>
 * 这正是"两个桥实现不一致"的直接证据。
 *
 * <p>该桥没有 world 上下文，故使用全局最大 {@link HeightContext}；真正的越界位置
 * 由 {@code MixinChunk} / {@code ExtendedBlockWrite} 的读写守卫兜底。
 */
@Mixin(value = BlockPos.MutableBlockPos.class, priority = 1100)
public abstract class MixinSpongeMutableBlockPosHeight {

    @Inject(method = "bridge$isInvalidYPosition", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void depthsupdate$isInvalidYPosition(CallbackInfoReturnable<Boolean> cir) {
        HeightContext ctx = HeightManager.getMaxContext();

        if (!ctx.isExtended()) {
            return;
        }

        int y = ((BlockPos.MutableBlockPos) (Object) this).getY();

        cir.setReturnValue(y < ctx.minY() || y >= ctx.maxY());
    }

    @Inject(method = "bridge$isValidPosition", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void depthsupdate$isValidPosition(CallbackInfoReturnable<Boolean> cir) {
        HeightContext ctx = HeightManager.getMaxContext();

        if (!ctx.isExtended()) {
            return;
        }

        BlockPos.MutableBlockPos self = (BlockPos.MutableBlockPos) (Object) this;
        int y = self.getY();

        if (y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        int x = self.getX();
        int z = self.getZ();

        cir.setReturnValue(x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000);
    }
}
