package sayys.depthsupdate.mixin;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sayys.depthsupdate.core.HeightManager;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;

@Mixin(net.minecraft.command.CommandFill.class)
public class MixinCommandFill {

    @Unique
    @Nullable private WeakReference<World> depths_Update_main$commandWorld;

    //get command sender, can't fail (inject at HEAD
    @Inject(method = "execute", at = @At(value = "HEAD"), require = 1)
    private void getWorldFromExecute(MinecraftServer server, ICommandSender sender, String[] args, CallbackInfo cbi) {
        depths_Update_main$commandWorld = new WeakReference<>(sender.getEntityWorld());
    }

    @ModifyConstant(
            method = "execute",
            constant = {
                    @Constant(expandZeroConditions = Constant.Condition.GREATER_THAN_OR_EQUAL_TO_ZERO, ordinal = 0),
                    @Constant(intValue = 256, ordinal = 0)
            },
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=commands.fill.tooManyBlocks")), require = 2)
    private int execute_getMinHeight(int original) {
        if (depths_Update_main$commandWorld == null) {
            return original;
        }
        World world = depths_Update_main$commandWorld.get();
        if (world == null) {
            return original;
        }
        if (!HeightManager.isExtended(world.provider.getDimension())) {
            return original;
        }
        return original == 0 ? HeightManager.getMinY(world) : HeightManager.getMaxY(world);
    }


}
