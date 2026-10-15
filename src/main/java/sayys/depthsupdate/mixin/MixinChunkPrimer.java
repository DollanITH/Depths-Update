package sayys.depthsupdate.mixin;

import sayys.depthsupdate.core.ChunkGenContext;
import sayys.depthsupdate.core.BedrockFilter;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.ChunkPrimer;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = ChunkPrimer.class, priority = 2000)
public abstract class MixinChunkPrimer {
    private static final IBlockState DEPTHSUPDATE_DEFAULT_STATE = Blocks.AIR.getDefaultState();

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 65536))
    private int depthsupdate$expandDataArrays(int original) {
        return HeightManager.getMaxContext().primerArraySize();
    }

    /**
     * 上游合并：在 BedrockFilter 激活期间（自定义世界深度填充），
     * 在源头取消 y=0..4 的原版基岩写入，避免事后替换遗漏其他写入路径。
     *
     * <p>★ {@code state} 允许为 {@code null} —— 原版 {@code ChunkPrimer.setBlockState}
     * 对它没有任何判空，直接走 {@code Block.BLOCK_STATE_IDS.get(state)} 写进 {@code char[]}，
     * 即 null 是合法入参（表示"该格无状态"）。许多生成器（例如下界
     * {@code ChunkGeneratorHell} 经 Sponge 的 {@code ChunkPrimerBuffer.setBlock}）
     * 确实会传 null 进来。
     *
     * <p>这里此前直接调用 {@code state.getBlock()}，于是传 null 时抛
     * {@code NullPointerException: Cannot invoke IBlockState.func_177230_c() because "state" is null}，
     * 表现为"进入 DIM-1 即崩溃、玩家掉线"。加入判空后既修掉崩溃，
     * 又不改变原版对 null 的语义（不 cancel，照常写下去）。
     */
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$filterVanillaBedrock(int x, int y, int z, IBlockState state, CallbackInfo ci) {
        if (state == null) {
            return;
        }

        if (y >= 0 && y <= 4 && state.getBlock() == Blocks.BEDROCK && BedrockFilter.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "getBlockIndex", at = @At("HEAD"), cancellable = true)
    private static void depthsupdate$getBlockIndex(int x, int y, int z, @NonNull CallbackInfoReturnable<Integer> cir) {
        HeightContext ctx = ChunkGenContext.currentOr(HeightManager.getMaxContext());
        int yBitShift = ctx.yBitShift();
        cir.setReturnValue((x << (yBitShift + 4)) | (z << yBitShift) | (y - ctx.minY()));
    }

    @Inject(method = "findGroundBlockIdx", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$findGroundBlockIdx(int x, int z, @NonNull CallbackInfoReturnable<Integer> cir) {
        HeightContext ctx = ChunkGenContext.currentOr(HeightManager.getMaxContext());
        int minY = ctx.minY();
        ChunkPrimer self = (ChunkPrimer) (Object) this;

        for (int y = ctx.maxY() - 1; y >= minY; --y) {
            IBlockState state = self.getBlockState(x, y, z);

            if (state != null && state != DEPTHSUPDATE_DEFAULT_STATE) {
                cir.setReturnValue(y);

                return;
            }
        }

        cir.setReturnValue(minY);
    }
}

