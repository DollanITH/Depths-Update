package sayys.depthsupdate.mixin;

import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.util.DimensionHelper;

@Mixin(SPacketChunkData.class)
public class MixinSPacketChunkData {
    @Unique
    private static final ThreadLocal<Boolean> depthsupdate$isExtended = ThreadLocal.withInitial(() -> false);

    /**
     * Static inject at HEAD of constructor — required by Mixin for injections before super().
     * Captures whether the chunk belongs to an extended dimension via ThreadLocal.
     */
    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At("HEAD"))
    private static void depthsupdate$captureChunk(Chunk chunkIn, int changedSectionFilter, CallbackInfo ci) {
        depthsupdate$isExtended.set(DimensionHelper.isExtendedDimension(chunkIn.getWorld()));
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", constant = @Constant(intValue = 65535))
    private int depthsupdate$modifyFullChunkCheck(int original) {
        return depthsupdate$isExtended.get() ? 1048575 : original; // 20 sections mask for extended
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", constant = @Constant(intValue = 16))
    private static int depthsupdate$modifyLoopLimit(int original) {
        return depthsupdate$isExtended.get() ? 20 : original;
    }

    @ModifyConstant(method = "calculateDataSize", constant = @Constant(intValue = 16), remap = true)
    private int depthsupdate$modifyCalculateDataSizeLoop(int original) {
        return depthsupdate$isExtended.get() ? 20 : original;
    }

    @ModifyConstant(method = "extractChunkData", constant = @Constant(intValue = 16), remap = true)
    private int depthsupdate$modifyExtractChunkDataLoop(int original) {
        return depthsupdate$isExtended.get() ? 20 : original;
    }

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At("RETURN"))
    private void depthsupdate$cleanup(Chunk chunkIn, int changedSectionFilter, CallbackInfo ci) {
        depthsupdate$isExtended.remove();
    }
}
