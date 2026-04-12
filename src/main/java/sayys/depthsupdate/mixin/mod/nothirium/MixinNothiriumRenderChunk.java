package sayys.depthsupdate.mixin.mod.nothirium;

import meldexun.nothirium.mc.renderer.chunk.RenderChunk;
import meldexun.nothirium.renderer.chunk.AbstractRenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(value = RenderChunk.class, remap = false)
public abstract class MixinNothiriumRenderChunk extends AbstractRenderChunk {
    protected MixinNothiriumRenderChunk(int sectionX, int sectionY, int sectionZ) {
        super(sectionX, sectionY, sectionZ);
    }

    /**
     * @author __sayys
     * @reason Remove the hardcoded 0-15 dirty marking culling, use configurable bounds.
     */
    @Overwrite
    public void markDirty() {
        HeightContext ctx = HeightManager.getMaxContext();
        if (this.getSectionY() < ctx.minSection() || this.getSectionY() > ctx.maxSection()) {
            this.getVisibility().setAllVisible();

            return;
        }

        super.markDirty();
    }
}
