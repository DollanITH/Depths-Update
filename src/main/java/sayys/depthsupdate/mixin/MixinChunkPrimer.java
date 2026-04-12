package sayys.depthsupdate.mixin;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import net.minecraft.world.chunk.ChunkPrimer;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = ChunkPrimer.class, priority = 2000)
public abstract class MixinChunkPrimer {
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 65536))
    private int depthsupdate$expandDataArrays(int original) {
        return HeightManager.getMaxContext().primerArraySize();
    }

    @Inject(method = "getBlockIndex", at = @At("HEAD"), cancellable = true)
    private static void depthsupdate$getBlockIndex(int x, int y, int z, @NonNull CallbackInfoReturnable<Integer> cir) {
        HeightContext ctx = HeightManager.getMaxContext();
        int yBitShift = ctx.yBitShift();
        cir.setReturnValue((x << (yBitShift + 4)) | (z << yBitShift) | (y - ctx.minY()));
    }

    @ModifyConstant(method = "findGroundBlockIdx", constant = @Constant(intValue = 255))
    private int depthsupdate$modifyFindGroundMaxY(int original) {
        return HeightManager.getMaxContext().maxY() - 1;
    }

    @ModifyConstant(method = "findGroundBlockIdx", constant = @Constant(intValue = 0))
    private int depthsupdate$modifyFindGroundMinY(int original) {
        return HeightManager.getMaxContext().minY();
    }
}
