package sayys.depthsupdate.mixin;

import java.util.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenMinable;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(WorldGenMinable.class)
public abstract class MixinWorldGenMinable {
    @ModifyVariable(method = "generate", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private @NonNull BlockPos depthsupdate$offsetModdedOres(@NonNull BlockPos pos, World worldIn, Random rand) {
        int y = pos.getY();

        if (y >= 0 && y <= 64 && rand.nextFloat() < 0.5f) {
            int newY = -64 + rand.nextInt(y + 65);

            return new BlockPos(pos.getX(), newY, pos.getZ());
        }

        return pos;
    }
}
