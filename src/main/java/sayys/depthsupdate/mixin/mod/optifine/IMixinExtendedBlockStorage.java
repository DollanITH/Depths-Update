package sayys.depthsupdate.mixin.mod.optifine;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ExtendedBlockStorage.class)
public interface IMixinExtendedBlockStorage {
    /**
     * vanilla 字段：private int blockRefCount（记录非空气方块计数）
     * 若启动报字段找不到，改用下方备选名再试
     */
    @Accessor("blockRefCount")
    int depthsupdate$blockRefCount();
}
