package sayys.depthsupdate.mixin.mod.journeymap;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import journeymap.client.world.JmBlockAccess;
import sayys.depthsupdate.core.HeightManager;

@Mixin(JmBlockAccess.class)
public abstract class MixinJourneyMapJmBlockAccess {

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true, remap = false)
    private void depthsupdate$isValid(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        World world = ((JmBlockAccess) (Object) this).getWorld();
        if (world == null) return;
        if (!HeightManager.isExtended(world)) return;

        int minY = HeightManager.getMinY(world);
        int maxY = HeightManager.getMaxY(world);
        int y = pos.getY();

        boolean valid = pos.getX() >= -30000000 && pos.getZ() >= -30000000
                && pos.getX() < 30000000 && pos.getZ() < 30000000
                && y >= minY && y < maxY;
        cir.setReturnValue(valid);
    }
}