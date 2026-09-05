package sayys.depthsupdate.mixin.mod.optifine;

import java.lang.reflect.Method;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.optifine.util.RenderChunkUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sayys.depthsupdate.core.HeightManager;

@Mixin(value = RenderChunkUtils.class, remap = false)
public class MixinOptiFineRenderChunkUtils {
    @Unique
    private static Method getChunkMethod;

    @Unique
    private static boolean reflectionInitialized = false;

    @Unique
    private static void initReflection() {
        if (reflectionInitialized) return;

        reflectionInitialized = true;

        try {
            getChunkMethod = RenderChunk.class.getDeclaredMethod("getChunk");
            getChunkMethod.setAccessible(true);
        } catch (Exception e) {}
    }

    /**
     * @author sayys
     * @reason Fix ArrayIndexOutOfBoundsException by correctly mapping Y coords to storage array indices.
     */
    @Inject(method = "getCountBlocks", at = @At(value = "HEAD"), cancellable = true)
    private static void getCountBlocks(RenderChunk renderChunk, CallbackInfoReturnable<Integer> cir) {
        initReflection();

        try {
            Chunk chunk = (Chunk) getChunkMethod.invoke(renderChunk);

            if (chunk == null) {
                cir.setReturnValue(0);
                cir.cancel();
            }

            ExtendedBlockStorage[] storages = null;
            if (chunk != null) {
                storages = chunk.getBlockStorageArray();
            }

            if (storages == null) {
                cir.setReturnValue(0);
                cir.cancel();
            }

            int y = renderChunk.getPosition().getY();
            int index = HeightManager.getMaxContext().toStorageIndex(y);

            if (index >= 0 && index < storages.length) {
                ExtendedBlockStorage ebs = storages[index];

                if (ebs != null) {
                    cir.setReturnValue(((EBSAccessor)ebs).depthsupdate$blockRefCount());
                    cir.cancel();
                }
            }
        } catch (Exception e) {
            return;
        }

        cir.setReturnValue(0);
    }
}