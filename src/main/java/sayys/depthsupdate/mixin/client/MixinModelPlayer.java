package sayys.depthsupdate.mixin.client;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumHandSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.item.ItemSpyglass;

@Mixin(ModelPlayer.class)
public abstract class MixinModelPlayer extends ModelBiped {
    @Inject(method = "setRotationAngles", at = @At("RETURN"))
    private void depthsupdate$spyglassPose(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, Entity entityIn, CallbackInfo ci) {
        if (!(entityIn instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) entityIn;

        if (living.isHandActive() && living.getActiveItemStack().getItem() instanceof ItemSpyglass) {
            boolean isRightHand = living.getPrimaryHand() == EnumHandSide.RIGHT;

            if (living.getActiveHand() == net.minecraft.util.EnumHand.OFF_HAND) isRightHand = !isRightHand;

            if (isRightHand) {
                this.bipedRightArm.rotateAngleX = this.bipedHead.rotateAngleX - 1.9198622F;
                this.bipedRightArm.rotateAngleY = this.bipedHead.rotateAngleY - 0.2617994F;
            } else {
                this.bipedLeftArm.rotateAngleX = this.bipedHead.rotateAngleX - 1.9198622F;
                this.bipedLeftArm.rotateAngleY = this.bipedHead.rotateAngleY + 0.2617994F;
            }
        }
    }
}
