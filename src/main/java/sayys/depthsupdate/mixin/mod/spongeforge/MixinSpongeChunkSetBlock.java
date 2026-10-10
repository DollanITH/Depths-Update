package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.ExtendedBlockWrite;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * SpongeForge 专用：接管 <b>扩展高度区间</b>（{@code y<0 || y>=256}）的方块写入。
 *
 * <p>Sponge 的 {@code mixins.common.core.json:world.chunk.ChunkMixin} 用<b>同名方法覆盖</b>
 * 掉了 {@code Chunk.func_177436_a}（setBlockState），并把真正的实现搬到它自己新增的
 * {@code bridge$setBlockState(BlockPos, IBlockState, IBlockState, BlockChangeFlag)}。
 * 同名覆盖会丢弃 {@code MixinChunk#depthsupdate$setBlockState} 的注入 —— 实测服务端
 * 即使执行 {@code /setblock} 也从不打印该注入的 {@code SET-ENTER} 日志，而
 * {@code bridge$setBlockState} 内部用的是原始下标：
 *
 * <pre>
 * 82: getfield field_76652_q   // storageArrays
 * 86: iload 6                  // y
 * 88: ishr 4                   // y &gt;&gt; 4
 * 90: aaload                   // y&lt;0 时是负下标
 * </pre>
 *
 * <p>于是 {@code World.setBlockState} → {@code Chunk.setBlockState} → 该负下标要么抛
 * 下标越界、要么返回 null，最终 {@code World.setBlockState} 返回 false，
 * {@code /setblock} 报 {@code commands.setblock.failed}，y&lt;0 / y&gt;=256 完全无法放置。
 *
 * <p>{@code bridge$setBlockState} 是 Sponge 自己新增的方法（与已生效的
 * {@code Chunk.bridge$fill}、{@code Vec3i.bridge$isInvalidYPosition} 同类），对它做
 * {@code @Inject} 不会被任何同名覆盖顶掉。这里只在原版区间之外接管，{@code 0..255}
 * 一律原样交回 Sponge，因此不影响 Sponge 的方块变更事件与常规光照逻辑。
 */
@Mixin(targets = "net.minecraft.world.chunk.Chunk", remap = false)
public abstract class MixinSpongeChunkSetBlock {

    @Inject(method = "bridge$setBlockState", at = @At("HEAD"), cancellable = true, remap = false)
    private void depthsupdate$spongeSetBlockState(BlockPos pos, IBlockState state, IBlockState currentState,
            org.spongepowered.api.world.BlockChangeFlag flag, CallbackInfoReturnable<IBlockState> cir) {
        int y = pos.getY();

        // 原版区间交回 Sponge 自己处理。
        if (y >= 0 && y < 256) {
            return;
        }

        Chunk self = (Chunk) (Object) this;
        World world = self.getWorld();

        if (world == null) {
            return;
        }

        HeightContext ctx = HeightManager.get(world);

        if (!ctx.isExtended() || y < ctx.minY() || y >= ctx.maxY()) {
            // 越界：等价于"无变化"，绝不能落到 Sponge 的负下标访问。
            cir.setReturnValue(null);

            return;
        }

        // 与 MixinSpongeWorldServerSetBlock / MixinWorld 共用同一套实现：
        // 完整复刻原版 Chunk.setBlockState 的语义（onBlockAdded、方块实体创建/移除、
        // 光照、邻居与客户端同步），只把 Y→section 的映射换成扩展版。
        // 正常情况下服务端会先经 WorldServer.setBlockState 的钩子接管，这里是兜底：
        // 若有代码绕过 World 直接调 Chunk.setBlockState，也能得到同样的正确结果。
        // flags 用 3（通知邻居 + 同步客户端），与原版 World.setBlockState 的常规取值一致。
        IBlockState old = ExtendedBlockWrite.setBlockState(world, self, pos, state, 3);

        cir.setReturnValue(old);
    }
}
