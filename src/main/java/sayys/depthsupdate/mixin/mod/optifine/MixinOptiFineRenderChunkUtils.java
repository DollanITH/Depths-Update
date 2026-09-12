package sayys.depthsupdate.mixin.mod.optifine;

import java.lang.reflect.Method;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.mixin.IMixinExtendedBlockStorage;

@Mixin(targets = "net.optifine.util.RenderChunkUtils", remap = false)
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
    @Overwrite
    public static int getCountBlocks(RenderChunk renderChunk) {
        initReflection();

        try {
            Chunk chunk = (Chunk) getChunkMethod.invoke(renderChunk);

            if (chunk == null) return 0;

            ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();

            if (storages == null) return 0;

            int y = renderChunk.getPosition().getY();
            int index = HeightManager.getMaxContext().toStorageIndex(y);

            if (index >= 0 && index < storages.length) {
                ExtendedBlockStorage ebs = storages[index];

                if (ebs != null) {
                    return ((IMixinExtendedBlockStorage) ebs).depthsupdate$blockRefCount();
                }
            }
        } catch (Exception e) {}

        return 0;
    }
}