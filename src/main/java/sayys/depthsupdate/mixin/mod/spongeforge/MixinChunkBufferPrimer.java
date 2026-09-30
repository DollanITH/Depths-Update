package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.block.state.IBlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Sponge 把 vanilla ChunkPrimer 包成 {@code ChunkBufferPrimer}，其
 * {@code func_177855_a}（setBlockState）字节码为：
 * <pre>
 *   buffer.setBlock( min.getX() + x,  min.getY() + y,  min.getZ() + z,  state );
 * </pre>
 * {@code min} 即扩展缓冲的世界角 start（y = minY = -64）。depthsupdate 的生成期 mixin
 * （如 fillDeepUnderground）写的是**世界 Y**（by = ctx.minY()），若仍把 min.y 加上，
 * 世界 Y 会被再次平移（-64 + (-64) = -128）越界崩溃。
 * <p>
 * 修复：x/z 仍需加 min（局部→世界），但 **y 直接透传**——vanilla 生成写的局部 Y(0..255)
 * 在扩展世界下本就等于世界 Y，mod 生成写的世界 Y 也保持原值。非扩展世界时 min.y=0，
 * 本 patch 行为与原版完全一致。
 */
@Mixin(targets = "org.spongepowered.common.util.gen.ChunkBufferPrimer")
public abstract class MixinChunkBufferPrimer {

    @ModifyArgs(method = "func_177855_a",
            at = @At(value = "INVOKE", target = "setBlock"))
    private void depthsupdate$yThroughOnSet(Args args, int x, int y, int z, IBlockState state) {
        // buffer.setBlock(min.x+x, y, min.z+z, state)：y 保持世界坐标，不加 min.y
        args.set(1, y);
    }

    @ModifyArgs(method = "func_177856_a",
            at = @At(value = "INVOKE", target = "getBlock"))
    private void depthsupdate$yThroughOnGet(Args args, int x, int y, int z) {
        // buffer.getBlock(min.x+x, y, min.z+z)：y 保持世界坐标，不加 min.y
        args.set(1, y);
    }
}
