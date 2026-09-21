package sayys.depthsupdate.mixin.mod.journeymap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import journeymap.client.properties.CoreProperties;
import journeymap.common.properties.config.IntegerField;

@Mixin(CoreProperties.class)
public abstract class MixinJourneyMapCoreProperties {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void depthsupdate$setRenderDelay(CallbackInfo ci) {
        CoreProperties self = (CoreProperties) (Object) this;
        IntegerField renderDelay = self.renderDelay;
        if (renderDelay != null) {
            renderDelay.put("min", 5);
            renderDelay.set(5);
        }
    }
}