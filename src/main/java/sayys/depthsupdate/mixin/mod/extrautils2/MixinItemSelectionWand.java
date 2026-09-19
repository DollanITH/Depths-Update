package sayys.depthsupdate.mixin.mod.extrautils2;

import com.llamalad7.mixinextras.sugar.Local;
import com.rwtema.extrautils2.items.ItemSelectionWand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import sayys.depthsupdate.core.HeightManager;

@Mixin(ItemSelectionWand.class)
public class MixinItemSelectionWand {

    @Redirect(method = "getPotentialBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/BlockPos;getY()I", ordinal = 0))
    public int maxPosY(BlockPos instance, @Local(argsOnly = true) World world) {
        int y = instance.getY();
        if (HeightManager.isExtended(world)) {
            int minY = HeightManager.getMinY(world);
            int maxY = HeightManager.getMaxY(world);
            if (y >= minY && y < maxY) {
                return 64;
            }
        }
        return y;
    }

    @Redirect(method = "getPotentialBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/BlockPos;getY()I", ordinal = 1))
    public int minPosY(BlockPos instance, @Local(argsOnly = true) World world) {
        int y = instance.getY();
        if (HeightManager.isExtended(world)) {
            int minY = HeightManager.getMinY(world);
            int maxY = HeightManager.getMaxY(world);
            if (y >= minY && y < maxY) {
                return 64;
            }
        }
        return y;
    }
}
