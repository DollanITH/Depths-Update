package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.util.math.Vec3i;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 修复 SpongeForge 下 y&lt;0 / y&gt;=256 的"假方块"根因。
 *
 * <p>Sponge 的 {@code mixins.common.core.json:util.math.Vec3iMixin}
 * （{@code @Mixin(Vec3i.class)}，实现 {@code BlockPosBridge}）给 {@link Vec3i} 加了一个
 * {@code bridge$isInvalidYPosition()}，实现就是原版的 0..255 判断。随后 Sponge 的
 * {@code world.WorldMixin} <b>按方法名覆盖</b>（name-match overwrite）了这两个方法：
 *
 * <pre>
 * World.func_189509_E(BlockPos)   // isOutsideBuildHeight
 *     -> pos.bridge$isInvalidYPosition()
 *
 * World.func_180495_p(BlockPos)   // getBlockState
 *     -> if (pos.bridge$isInvalidYPosition()) return Blocks.AIR.getDefaultState();
 *        return this.func_175726_f(pos).func_177435_g(pos);
 * </pre>
 *
 * <p>按名覆盖会<b>静默丢弃</b>其它 mixin 打在同一个方法上的 {@code @Inject} —— 提升
 * {@code @Mixin(priority)} 也无效。所以 depthsupdate 在 {@code MixinWorld} 里对
 * {@code isOutsideBuildHeight} 的注入在客户端（无 Sponge）生效、在服务端（有 Sponge）失效，
 * 实测日志正好印证：客户端打印 {@code OUTSIDE y=-4 -> false}，服务端一条都没有。
 *
 * <p>后果：
 * <ul>
 *   <li>{@code World.getBlockState(y<0)} 在到达区块之前就直接返回空气 —— 实体踩不到方块而坠入虚空；</li>
 *   <li>{@code World.setBlockState(y<0)} 的第 0 条字节码就是 {@code isOutsideBuildHeight}，
 *       为真立即 return false —— /setblock 报 {@code commands.setblock.failed}，放置一律失败。</li>
 * </ul>
 *
 * <p>因此这里改为拦截 Sponge 真正调用的那个方法：{@code bridge$isInvalidYPosition} 是
 * Sponge 自己<b>新增</b>的方法（与 {@code Chunk.bridge$fill} 同类），对它做 {@code @Inject}
 * 不会被任何按名覆盖顶掉。它没有 world 上下文，故使用全局最大 {@link HeightContext}
 * （本服 minY=-64 / maxY=400）；超出扩展范围的位置由 {@code MixinChunk} 的读写守卫
 * 兜底返回空气 / null，行为安全。
 */
@Mixin(value = Vec3i.class, priority = 1100)
public abstract class MixinSpongeVec3iHeight {

    @Inject(method = "bridge$isInvalidYPosition", at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void depthsupdate$isInvalidYPosition(CallbackInfoReturnable<Boolean> cir) {
        HeightContext ctx = HeightManager.getMaxContext();

        int y = ((Vec3i) (Object) this).getY();

        if (!ctx.isExtended()) {
            return;
        }

        cir.setReturnValue(y < ctx.minY() || y >= ctx.maxY());
    }
}
