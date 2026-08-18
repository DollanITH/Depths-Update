package sayys.depthsupdate.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import sayys.depthsupdate.core.HeightManager;

@Mixin({GuiOverlayDebug.class})
public class MixinGuiOverlayDebug {
    @Redirect(
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/BlockPos;getY()I"
            ),
            slice = @Slice(
                    from = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/World;isBlockLoaded(Lnet/minecraft/util/math/BlockPos;)Z"
                    ),
                    to = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/chunk/Chunk;isEmpty()Z"
                    )
            ),
            method = {"Lnet/minecraft/client/gui/GuiOverlayDebug;call()Ljava/util/List;"}
    )
    private int depthsupdate$normalizeYForDebug(BlockPos pos) {
        int y = pos.getY();
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return y;
        } else {
            if (HeightManager.isExtended(world)) {
                int minY = HeightManager.getMinY(world);
                int maxY = HeightManager.getMaxY(world);
                if (y >= minY && y < maxY) {
                    return 0;
                }
            }

            return y;
        }
    }
}
