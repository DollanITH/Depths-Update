package sayys.depthsupdate.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * Overrides the server build limit to match the maximum configured world height.
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {
    @Inject(method = "getBuildLimit", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getBuildLimit(CallbackInfoReturnable<Integer> cir) {
        HeightContext ctx = HeightManager.getMaxContext();

        if (ctx.isExtended() && ctx.maxY() > 256) {
            cir.setReturnValue(ctx.maxY());
        }
    }
}
