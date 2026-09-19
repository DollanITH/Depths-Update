package sayys.depthsupdate.mixin.mod.journeymap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.client.Minecraft;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * Fixes JourneyMap minimap snapping to cave mode at negative Y.
 *
 * <p>Root cause (decompiled {@code journeymap.client.data.PlayerData#playerIsUnderground}):
 *
 * <pre>
 *   int playerY = MathHelper.floor(player.posY + player.getEyeHeight());
 *   if (playerY < 0) return true;          // &lt;-- hardcoded world bottom at Y=0
 *   // ...otherwise scan the 3x3 columns around the player via ChunkMD.ceiling(...)
 * </pre>
 *
 * <p>When this mod extends the overworld down to {@code minY} (e.g. -64), the player legitimately
 * stands at negative Y, so the hardcoded {@code playerY < 0} check immediately returns
 * {@code true} ("underground") and the real "is there a solid roof above me?" scan never runs.
 * The minimap therefore flips to cave mode even in an open, sky-lit cave.
 *
 * <p>Fix: rewrite the implicit zero in the {@code if (playerY < 0)} comparison to the dimension's
 * real {@code minY}. {@code expandZeroConditions = LESS_THAN_ZERO} matches the compiled
 * {@code iload playerY; ifge} pattern (source: {@code if (playerY < 0) ...}). After the rewrite
 * the branch becomes {@code if (playerY < minY) return true}, so the player is only flagged
 * underground once they have actually dropped below the extended world floor; otherwise the
 * normal ceiling scan proceeds.
 *
 * <p>{@code require = 0}: if a future JourneyMap build changes this method shape, the mixin
 * no-ops rather than crashing.
 */
@Mixin(targets = "journeymap.client.data.PlayerData", remap = false)
public class MixinJourneyMapPlayerData {

    @ModifyConstant(
        method = "playerIsUnderground",
        constant = @Constant(expandZeroConditions = Constant.Condition.LESS_THAN_ZERO),
        require = 0
    )
    private static int depthsupdate$adjustWorldBottom(int original) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world != null) {
            HeightContext ctx = HeightManager.get(mc.world);
            if (ctx.isExtended()) {
                return ctx.minY();
            }
        }
        return original;
    }
}
