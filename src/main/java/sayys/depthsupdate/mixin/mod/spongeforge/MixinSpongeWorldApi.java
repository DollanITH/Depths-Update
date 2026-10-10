package sayys.depthsupdate.mixin.mod.spongeforge;

import com.flowpowered.math.vector.Vector3i;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.common.util.Constants;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * Sponge 1.12.2 的 {@code World#getBlockMin()} / {@code getBlockMax()} 直接返回全局常量
 * {@code Constants.World.BLOCK_MIN}（-30000000, 0, -30000000）与
 * {@code BLOCK_MAX}（60000000, 256, 60000000），Y 范围被硬编码为 [0, 256]，
 * 不感知 depthsupdate 的扩展高度世界（如主世界 minY=-64, maxY=384）。
 * <p>
 * 这里按 {@link HeightManager} 返回该世界真实的 Y 边界：
 * <ul>
 *   <li>非扩展世界（VANILLA）：minY=0 / maxY=256，与原常量完全一致，行为不变；</li>
 *   <li>扩展世界：返回实际 minY / maxY（如 -64 / 384），使依赖 Sponge
 *       {@code World} 边界信息的插件（如 Nucleus 的 /tppos 范围校验）正确工作。</li>
 * </ul>
 * X/Z 分量保持原常量不变。
 * <p>
 * 注意：{@code getBlockMin/getBlockMax} 是由 Sponge 的 {@code WorldMixin_API}
 * 合并进 {@link World} 的方法，因此本 mixin 必须晚于 Sponge 的 mixin 应用。
 * Sponge 的 {@code mixins.common.api.json} 处于 {@code @env(DEFAULT)} 阶段
 * （mixinPriority 900），早于本配置的 {@code @env(MOD)} 阶段，故运行时安全；
 * 编译期 classpath 的 World 不含这两个方法，IDE 静态检查报 "Cannot resolve
 * method" 属正常误报，不影响构建与运行。
 * <p>
 * target 使用字符串形式（与 {@code MixinChunkBufferPrimer} 一致）以避开 IDE
 * 对类引用的静态解析检查。
 */
// priority = 1100：Sponge 的 mixins.common.core.json:world.WorldMixin（默认 1000）也作用于
// World，且实测 cleanmix 顺序为本 mixin(23) → world.WorldMixin(25)，其 @Overwrite func_189509_E
// 会丢弃更早应用的注入。显式提高优先级，保证在 Sponge 全部 World mixin 之后应用。
@Mixin(targets = "net.minecraft.world.World", remap = false, priority = 1100)
public abstract class MixinSpongeWorldApi {

    @Inject(method = "getBlockMin", at = @At("HEAD"), cancellable = true, remap = false)
    private void depthsupdate$worldBlockMin(CallbackInfoReturnable<Vector3i> cir) {
        HeightContext ctx = HeightManager.get((World) (Object) this);
        cir.setReturnValue(new Vector3i(Constants.World.BLOCK_MIN.getX(), ctx.minY(), Constants.World.BLOCK_MIN.getZ()));
    }

    @Inject(method = "getBlockMax", at = @At("HEAD"), cancellable = true, remap = false)
    private void depthsupdate$worldBlockMax(CallbackInfoReturnable<Vector3i> cir) {
        HeightContext ctx = HeightManager.get((World) (Object) this);
        cir.setReturnValue(new Vector3i(Constants.World.BLOCK_MAX.getX(), ctx.maxY(), Constants.World.BLOCK_MAX.getZ()));
    }

    /**
     * Sponge 的 {@code checkBlockBounds(x,y,z)} 直接用硬编码常量 BLOCK_MIN/BLOCK_MAX
     * 校验 Y 范围（不调用 getBlockMin/Max），越界抛 PositionOutOfBoundsException。
     * 扩展世界下 y&lt;0 / y&gt;=maxY 会被拒绝放置。这里直接短路，只对 X/Z 做原生校验。
     */
    @Inject(method = "checkBlockBounds", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$skipWorldCheckBlockBounds(int x, int y, int z, CallbackInfo ci) {
        HeightContext ctx = HeightManager.get((World) (Object) this);
        if (!ctx.isExtended()) {
            return;
        }
        // 只放行扩展 Y 范围；X/Z 越界仍按 Sponge 原逻辑处理（这里直接放行，因为 X/Z 范围极大）
        ci.cancel();
    }

    /**
     * {@code containsBlock(x,y,z)} 同样用硬编码常量。扩展世界下直接返回 true。
     */
    @Inject(method = "containsBlock", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$worldContainsBlock(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        HeightContext ctx = HeightManager.get((World) (Object) this);
        if (!ctx.isExtended()) {
            return;
        }
        cir.setReturnValue(true);
    }
}
