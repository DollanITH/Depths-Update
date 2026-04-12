package sayys.depthsupdate.mixin.mod.nothirium;

import meldexun.nothirium.api.renderer.chunk.IRenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import sayys.depthsupdate.core.HeightManager;

@Mixin(targets = "meldexun.nothirium.util.Direction$2", remap = false)
public class MixinNothiriumDirectionUp {
    /**
     * @author __sayys
     * @reason Fix face culling for the UP face at extended depth boundaries.
     */
    @Overwrite
    public boolean isFaceCulled(IRenderChunk renderChunk, double cameraX, double cameraY, double cameraZ) {
        if (renderChunk.getSectionY() < HeightManager.getMaxContext().minSection())
            return true;

        return cameraY < renderChunk.getY() + 16;
    }

}
