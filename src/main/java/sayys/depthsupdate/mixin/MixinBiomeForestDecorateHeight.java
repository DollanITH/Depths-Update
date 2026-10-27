package sayys.depthsupdate.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeForest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Same deep-hole decoration guard as {@link MixinBiomeDecorateHeight}, for
 * BiomeForest whose getHeight+nextInt sites live in addMushrooms and
 * addDoublePlants rather than decorate itself.
 */
@Mixin(BiomeForest.class)
public abstract class MixinBiomeForestDecorateHeight {
    @Redirect(
        method = {"addMushrooms", "addDoublePlants"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getHeight(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;"
        )
    )
    private BlockPos depthsupdate$clampDecorateHeight(World world, BlockPos pos) {
        BlockPos top = world.getHeight(pos);
        return top.getY() < 0 ? new BlockPos(top.getX(), 0, top.getZ()) : top;
    }
}