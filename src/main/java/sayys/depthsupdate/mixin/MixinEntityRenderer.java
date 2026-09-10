package sayys.depthsupdate.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.world.World;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 这个 Mixin 只在需要时覆盖 void fog 逻辑
 * 这样不会影响 Optifine 的其他设置
 */
@Mixin(value = EntityRenderer.class, priority = 999)
public abstract class MixinEntityRenderer {

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private float fogColorRed;

    @Shadow
    private float fogColorGreen;

    @Shadow
    private float fogColorBlue;

    @Shadow
    private float fogColor2;

    @Shadow
    private float fogColor1;

    @Shadow
    private float getNightVisionBrightness(EntityLivingBase entitylivingbaseIn, float partialTicks) {
        return 0.0F;
    }

    @Shadow
    private float bossColorModifier;

    @Shadow
    private float bossColorModifierPrev;

    @ModifyVariable(method = "updateFogColor(F)V",
                    at = @At(value = "STORE", target = "d1"),
                    ordinal = 0)
    private double depths$modifyVoidFogVariable(double d1) {
        World world = this.mc.world;
        Entity entity = this.mc.getRenderViewEntity();
        HeightContext ctx = HeightManager.get(world);

        // 只有在我们的自定义条件满足时才修改
        if (entity != null && ctx.isExtended() && ctx.minY() < 0) {
            // 计算插值后的 Y 位置
            double voidFogY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * this.mc.getRenderPartialTicks();

            // 应用我们的自定义计算
            return (voidFogY - ctx.minY()) * world.provider.getVoidFogYFactor() + 1.0D;
        }
        return d1;
    }
}