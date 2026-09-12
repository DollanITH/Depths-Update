package sayys.depthsupdate.mixin;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ExtendedBlockStorage.class)
public interface IMixinExtendedBlockStorage {

    @Accessor("blockRefCount")
    int depthsupdate$blockRefCount();
}
