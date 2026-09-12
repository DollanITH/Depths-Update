package sayys.depthsupdate.mixin.mod.nothirium;

import meldexun.nothirium.mc.renderer.ChunkRenderManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(value = ChunkRenderManager.class, remap = false)
public class MixinNothiriumChunkRenderManager {

    /**
     * @author __sayys
     * @reason Cap Y render distance to world height; keep Nothirium's OptiFine
     *         routing (ASM-patched createChunkRenderer) intact in the original body.
     */
    @ModifyArg(method = "allChanged",
            at = @At(value = "INVOKE",
                    target = "Lmeldexun/nothirium/api/renderer/chunk/IRenderChunkProvider;init(III)V"),
            index = 1)
    private static int depthsupdate$capRenderDistanceY(int renderDistance) {
        Minecraft mc = Minecraft.getMinecraft();
        HeightContext ctx = mc.world != null ? HeightManager.get(mc.world) : HeightContext.VANILLA;
        return Math.min(renderDistance, (ctx.totalStorageSections() + 1) / 2);
    }
}
