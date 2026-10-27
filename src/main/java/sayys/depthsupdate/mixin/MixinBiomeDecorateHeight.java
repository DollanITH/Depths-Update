package sayys.depthsupdate.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomePlains;
import net.minecraft.world.biome.BiomeSavanna;
import net.minecraft.world.biome.BiomeTaiga;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Deep-hole decoration guard (plains / savanna / taiga).
 *
 * These biomes pick double-plant / pumpkin Y coordinates with
 * {@code rand.nextInt(world.getHeight(pos).getY() + 32)}. Vanilla columns
 * always report a height above -32, but in an extended world a surface-opening
 * feature (the great rift) leaves columns whose top solid block sits at the
 * feature floor (e.g. Y=-160), making the bound non-positive and crashing
 * populate with "bound must be positive".
 *
 * Clamping the reported height to 0 keeps the bound positive; the generator
 * then simply fails its ground checks at depth and places nothing, which is
 * the correct outcome for a rift-cut column.
 */
@Mixin({BiomePlains.class, BiomeSavanna.class, BiomeTaiga.class})
public abstract class MixinBiomeDecorateHeight {
    @Redirect(
        method = "decorate",
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