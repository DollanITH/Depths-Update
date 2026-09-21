package sayys.depthsupdate.mixin.mod.journeymap;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import journeymap.client.data.PlayerData;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(PlayerData.class)
public abstract class MixinJourneyMapPlayerData {

    @ModifyConstant(method = "playerIsUnderground", constant = @Constant(expandZeroConditions = Constant.Condition.LESS_THAN_ZERO), remap = false)
    private static int depthsupdate$playerIsUnderground(int original, @Local(argsOnly = true) Minecraft mc) {
        World world = mc.world;
        HeightContext ctx = HeightManager.get(world);
        if (ctx.isExtended()) {
            return ctx.minY();
        }
        return original;
    }
}
