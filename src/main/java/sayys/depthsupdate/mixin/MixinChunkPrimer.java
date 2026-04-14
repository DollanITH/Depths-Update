package sayys.depthsupdate.mixin;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.ChunkPrimer;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = ChunkPrimer.class, priority = 2000)
public abstract class MixinChunkPrimer {
    private static final IBlockState DEPTHSUPDATE_DEFAULT_STATE = Blocks.AIR.getDefaultState();

    @Shadow
    @Final
    private char[] data;

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

    @Inject(method = "findGroundBlockIdx", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$findGroundBlockIdx(int x, int z, @NonNull CallbackInfoReturnable<Integer> cir) {
        HeightContext ctx = HeightManager.getMaxContext();
        int yBitShift = ctx.yBitShift();
        int minY = ctx.minY();

        for (int y = ctx.maxY() - 1; y >= minY; --y) {
            int idx = (x << (yBitShift + 4)) | (z << yBitShift) | (y - minY);
            IBlockState state = Block.BLOCK_STATE_IDS.getByValue(this.data[idx]);

            if (state != null && state != DEPTHSUPDATE_DEFAULT_STATE) {
                cir.setReturnValue(y);
                return;
            }
        }

        cir.setReturnValue(minY);
    }
}
