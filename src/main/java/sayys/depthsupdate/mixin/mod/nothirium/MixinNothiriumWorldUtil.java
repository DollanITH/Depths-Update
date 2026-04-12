package sayys.depthsupdate.mixin.mod.nothirium;

import meldexun.nothirium.mc.util.WorldUtil;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(value = WorldUtil.class, remap = false)
public class MixinNothiriumWorldUtil {
    /**
     * @author __sayys
     * @reason Support negative and upper section coordinates.
     */
    @Overwrite
    public static ExtendedBlockStorage getSection(World world, int sectionX, int sectionY, int sectionZ) {
        HeightContext ctx = HeightManager.get(world);
        if (sectionY < ctx.minSection() || sectionY > ctx.maxSection()) {
            return null;
        }

        Chunk chunk = world.getChunk(sectionX, sectionZ);

        if (chunk == null) {
            return null;
        }

        int storageIndex = ctx.toStorageIndex(sectionY << 4);

        ExtendedBlockStorage[] storageArray = chunk.getBlockStorageArray();

        if (storageIndex < 0 || storageIndex >= storageArray.length) {
            return null;
        }

        return storageArray[storageIndex];
    }
}
