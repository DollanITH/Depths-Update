package sayys.depthsupdate.mixin.mod.journeymap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.World;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * Fixes JourneyMap not mapping blocks below Y=0.
 *
 * <p>Root cause (decompiled {@code journeymap.common.chunk.WorldHeightHelper}):
 *
 * <pre>
 *   static int probeMinY(World world) {
 *       int y = 0;
 *       while (y > -2048) {
 *           pos.set(0, y - 16, 0);
 *           if (world.isValidBlockPos(pos)) return y;   // &lt;-- first valid pos wins
 *           y -= 16;
 *       }
 *   }
 * </pre>
 *
 * <p>On vanilla 1.12.2 every {@code y-16 < 0} position is invalid, so the loop walks all the
 * way down and reports a very negative bottom. But this mod extends the overworld down to
 * {@code minY} (e.g. -64), so {@code isValidBlockPos(-16)} is already true and the very first
 * probe returns {@code y = 0}. JourneyMap then caches {@code bounds[0] = 0} and only renders
 * vertical slices from chunkY = 0 upward - everything below Y=0 is never drawn.
 *
 * <p>Fix: short-circuit {@code probeMinY} to return the dimension's real {@code minY}. The
 * cached bounds become {@code [minY, getHeight()]} and the full extended column renders.
 *
 * <p>{@code require = 0}: silent no-op if a future JourneyMap build renames this method.
 */
@Mixin(targets = "journeymap.common.chunk.WorldHeightHelper", remap = false)
public class MixinJourneyMapWorldHeightHelper {

    @Inject(
        method = "probeMinY",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void depthsupdate$useRealMinY(World world, CallbackInfoReturnable<Integer> cir) {
        HeightContext ctx = HeightManager.get(world);
        if (ctx.isExtended()) {
            cir.setReturnValue(ctx.minY());
        }
    }
}
