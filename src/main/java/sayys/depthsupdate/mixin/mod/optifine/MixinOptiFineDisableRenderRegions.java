package sayys.depthsupdate.mixin.mod.optifine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 永久禁用 OptiFine 的「区域渲染」。
 *
 * 原因：区域渲染按 (regionX, regionZ) 分组、不含 Y 分量，顶点平移量却按 Y 计算。
 * 原版世界 y 恒在 0..255 时自洽；扩展到 y=-64..400 后，同一 (regionX, regionZ)
 * 下并存多个 Y 段却共用一次平移，导致 y&lt;0 地形被截断／上浮。
 *
 * Config.isRenderRegions() 是 OptiFine 内部读取该状态的唯一入口
 * （RenderChunk 等 6 个类均经此方法），恒返回 false 即可全部覆盖，
 * 玩家在「视频设置 → 性能」中开启也无法绕过。
 *
 * require = 0：本 mixin 配置声明了 "required": true，任一注入失败会使整个配置
 * 报错（本项目曾因此导致服务器无法启动）。故用 require = 0 保证不拖垮启动，
 * 并在首次拦截时打印一条日志 —— 日志中【出现】即代表禁用已生效，
 * 未出现则说明该 OptiFine 版本改名了，需改用其它方式禁用。
 */
@Mixin(targets = "Config", remap = false)
public class MixinOptiFineDisableRenderRegions {

    @Unique
    private static final Logger DEPTHSUPDATE_LOGGER =
            LogManager.getLogger("DepthsUpdate/OptiFineCompat");

    @Unique
    private static boolean depthsupdate$loggedOnce = false;

    @Inject(method = "isRenderRegions", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void depthsupdate$disableRenderRegions(CallbackInfoReturnable<Boolean> cir) {
        if (!depthsupdate$loggedOnce) {
            depthsupdate$loggedOnce = true;
            DEPTHSUPDATE_LOGGER.info("OptiFine Render Regions force-disabled (incompatible with extended height)");
        }

        cir.setReturnValue(Boolean.FALSE);
    }
}
