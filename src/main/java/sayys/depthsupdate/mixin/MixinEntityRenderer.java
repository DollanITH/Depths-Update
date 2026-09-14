package sayys.depthsupdate.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    @Shadow
    @Final
    private Minecraft mc;


    @ModifyVariable(method = "updateFogColor(F)V", at = @At(value = "STORE", ordinal = 0), name = "d1")
    private double updateFogColor(double d1, @Local(argsOnly = true) float partialTicks) {
        World world = this.mc.world;
        Entity entity = this.mc.getRenderViewEntity();
        HeightContext ctx = HeightManager.get(world);
        if (entity == null || ctx == null || !ctx.isExtended() || ctx.minY() >= 0) {
            return d1;
        }
        double voidFogY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        return (voidFogY - ctx.minY()) * world.provider.getVoidFogYFactor() + 1.0D;
    }

}
