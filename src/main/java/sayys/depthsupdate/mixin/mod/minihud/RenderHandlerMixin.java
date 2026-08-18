package sayys.depthsupdate.mixin.mod.minihud;

import fi.dy.masa.minihud.event.RenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import sayys.depthsupdate.core.HeightManager;

/**
 * Mixin for MiniHUD's RenderHandler to handle extended height dimensions
 * This mixin replaces hardcoded Y bounds (0-256) with HeightManager values
 */
@Mixin(value = RenderHandler.class, remap = false)
public class RenderHandlerMixin {

    @ModifyConstant(
                method = "addLine(Lfi/dy/masa/minihud/config/InfoToggle;)V",
            constant = {
                    @Constant(expandZeroConditions = Constant.Condition.GREATER_THAN_OR_EQUAL_TO_ZERO, ordinal = 0),
                    @Constant(intValue = 256, ordinal = 0)
            },
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=Facing: %s (%s)")), require = 2)
    private int execute_getMinHeight(int original) {
        Minecraft mc = Minecraft.getMinecraft( );
        Entity entity = mc.getRenderViewEntity( );
        World world = entity.getEntityWorld( );
        if (world == null) {
            return original;
        }
        if (!HeightManager.isExtended(world.provider.getDimension( ))) {
            return original;
        }
        return original == 0 ? HeightManager.getMinY(world) : original == 256 ? HeightManager.getMaxY(world) : original;
    }
    @ModifyConstant(
                method = "addLine(Lfi/dy/masa/minihud/config/InfoToggle;)V",
            constant = {
                    @Constant(expandZeroConditions = Constant.Condition.GREATER_THAN_OR_EQUAL_TO_ZERO, ordinal = 0),
                    @Constant(intValue = 256, ordinal = 0)
            },
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=Local Difficulty: %.2f // %.2f (Day %d)")), require = 2)
    private int execute_getMinHeight1(int original) {
        Minecraft mc = Minecraft.getMinecraft( );
        Entity entity = mc.getRenderViewEntity( );
        World world = entity.getEntityWorld( );
        if (world == null) {
            return original;
        }
        if (!HeightManager.isExtended(world.provider.getDimension( ))) {
            return original;
        }
        return original == 0 ? HeightManager.getMinY(world) : original == 256 ? HeightManager.getMaxY(world) : original;
    }

    @ModifyConstant(
                method = "addLine(Lfi/dy/masa/minihud/config/InfoToggle;)V",
            constant = {
                    @Constant(expandZeroConditions = Constant.Condition.GREATER_THAN_OR_EQUAL_TO_ZERO, ordinal = 0),
                    @Constant(intValue = 256, ordinal = 0)
            },
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=Biome: ")), require = 2)
    private int execute_getMinHeight2(int original) {
        Minecraft mc = Minecraft.getMinecraft( );
        Entity entity = mc.getRenderViewEntity( );
        World world = entity.getEntityWorld( );
        if (world == null) {
            return original;
        }
        if (!HeightManager.isExtended(world.provider.getDimension( ))) {
            return original;
        }
        return original == 0 ? HeightManager.getMinY(world) : original == 256 ? HeightManager.getMaxY(world) : original;
    }
}