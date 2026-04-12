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

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(SPacketChunkData.class)
public class MixinSPacketChunkData {
    @Unique
    private static final ThreadLocal<HeightContext> depthsupdate$ctx = ThreadLocal.withInitial(() -> HeightContext.VANILLA);

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At("HEAD"))
    private static void depthsupdate$captureChunk(Chunk chunkIn, int changedSectionFilter, CallbackInfo ci) {
        depthsupdate$ctx.set(HeightManager.get(chunkIn.getWorld()));
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", constant = @Constant(intValue = 65535))
    private int depthsupdate$modifyFullChunkCheck(int original) {
        HeightContext ctx = depthsupdate$ctx.get();
        return ctx.isExtended() ? ctx.fullChunkSectionMask() : original;
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", constant = @Constant(intValue = 16))
    private static int depthsupdate$modifyLoopLimit(int original) {
        HeightContext ctx = depthsupdate$ctx.get();
        return ctx.isExtended() ? ctx.totalStorageSections() : original;
    }

    @ModifyConstant(method = "calculateDataSize", constant = @Constant(intValue = 16), remap = true)
    private int depthsupdate$modifyCalculateDataSizeLoop(int original) {
        HeightContext ctx = depthsupdate$ctx.get();
        return ctx.isExtended() ? ctx.totalStorageSections() : original;
    }

    @ModifyConstant(method = "extractChunkData", constant = @Constant(intValue = 16), remap = true)
    private int depthsupdate$modifyExtractChunkDataLoop(int original) {
        HeightContext ctx = depthsupdate$ctx.get();
        return ctx.isExtended() ? ctx.totalStorageSections() : original;
    }

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At("RETURN"))
    private void depthsupdate$cleanup(Chunk chunkIn, int changedSectionFilter, CallbackInfo ci) {
        depthsupdate$ctx.remove();
    }
}
