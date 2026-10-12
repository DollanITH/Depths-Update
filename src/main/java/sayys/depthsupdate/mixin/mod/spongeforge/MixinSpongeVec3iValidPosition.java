package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 修复 SpongeForge 下 {@code bridge$isValidPosition()} 的硬编码高度判断。
 *
 * <h2>为什么需要它（与 {@link MixinSpongeVec3iHeight} 同一类，但覆盖的是另一个桥）</h2>
 * Sponge 在 {@code Vec3i} 上加了两个桥方法，反汇编实测：
 * <pre>
 * bridge$isInvalidYPosition():  return y &lt; 0 || y &gt;= 256;                       // sipush 256
 * bridge$isValidPosition():     return X/Z in ±30000000 &amp;&amp; y &gt;= 0 &amp;&amp; y &lt; 256;    // sipush 256
 * </pre>
 * {@link MixinSpongeVec3iHeight} 只修了前者。而 Sponge 的 {@code WorldMixin} /
 * {@code WorldServerMixin} 里有 <b>6 处</b>调用点（逐条字节码核对）：
 * <pre>
 * WorldMixin.func_180495_p  (getBlockState)  → isInvalidYPosition
 * WorldMixin.func_189509_E  (isOutsideBuildHeight) → isInvalidYPosition
 * WorldMixin.func_175701_a  (isValid)        → isValidPosition   ★
 * WorldMixin.func_175642_b  (getLightFor)    → isValidPosition   ★ 光照读
 * WorldMixin.func_175653_a  (setLightFor)    → isValidPosition   ★ 光照写
 * WorldServerMixin 侧同款
 * </pre>
 *
 * <h2>这解释了三个实测现象</h2>
 * <ul>
 *   <li><b>isValid 恒为 false</b>：Sponge 用具体方法覆盖了 {@code func_175701_a}，虚分派走
 *       Sponge 版本 → 调 {@code bridge$isValidPosition()} → y≥256 返回 false。
 *       于是原版 {@code World.isSideSolid(pos, side, _default)} 第一条字节码就
 *       {@code return _default(false)}，Railcraft 的
 *       {@code TrackSupportTools.isSupportedDirectly} 得到"无支撑" →
 *       {@code BlockTrack.isRailValid} 返回 false → {@code breakRail()} →
 *       <b>轨道放下后立即消失</b>；告示牌/火把同理永远认为支撑还在 → <b>浮空不掉落</b>。</li>
 *   <li><b>光照重进后消失</b>：读光与写光都先过 {@code isValidPosition}，扩展区间被短路
 *       （读返回默认值、写直接丢弃）。</li>
 *   <li>凡走 {@code isValid} 的交互在扩展区间一律被否掉。</li>
 * </ul>
 *
 * <h2>为什么改这个桥而不是改 {@code isValid}</h2>
 * 该桥是 Sponge <b>自己新增</b>的方法（与 {@code Chunk.bridge$fill} 同类），
 * 对它做 {@code @Inject} 不会被任何"按名覆盖"顶掉；而直接注入
 * {@code World.func_175701_a} 会因为 Sponge 覆盖了同名方法而被静默丢弃
 * （已在 {@code MixinWorld} 的 {@code isOutsideBuildHeight} 上踩过这个坑）。
 *
 * <p>该桥没有 world 上下文，故使用全局最大 {@link HeightContext}；真正越界的位置由
 * {@code MixinChunk} / {@code ExtendedBlockWrite} 的读写守卫兜底。
 */
@Mixin(value = Vec3i.class, priority = 1100)
public abstract class MixinSpongeVec3iValidPosition {

    @Inject(method = "bridge$isValidPosition", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void depthsupdate$isValidPosition(CallbackInfoReturnable<Boolean> cir) {
        HeightContext ctx = HeightManager.getMaxContext();

        if (!ctx.isExtended()) {
            return;
        }

        Vec3i self = (Vec3i) (Object) this;
        int y = self.getY();

        // 与原版一致：X/Z 必须在 ±30000000 内，Y 必须落在扩展高度内。
        // 原版对 Y 的要求是 0 <= y < 256；这里放宽为 [minY, maxY)。
        if (y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        int x = self.getX();
        int z = self.getZ();

        cir.setReturnValue(x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000);
    }
}
