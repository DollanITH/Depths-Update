package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.ExtendedBlockWrite;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * SpongeForge 专用：接管 <b>扩展高度区间</b>（{@code y<0 || y>=256}）在 {@link WorldServer} 上的方块写入。
 *
 * <p>为什么必须单独做 {@code WorldServer}：
 * <pre>
 * vanilla          : WorldServer 没有覆盖 setBlockState，调用走 World.func_180501_a
 * Sponge WorldMixin: func_180501_a 只是 abstract 影子（未覆盖）
 * Sponge WorldServerMixin:
 *     public boolean func_180501_a(BlockPos pos, IBlockState state, int flags) {
 *         if (!this.func_175701_a(pos)) return false;          // isValid：只校验 X/Z，能过
 *         if (worldInfo.getTerrainType() == DEBUG) return false;
 *         return PhaseTracker.getInstance().setBlockState(this, pos.toImmutable(), state, flag);
 *     }
 * </pre>
 * Sponge 给 {@code WorldServer} <b>新增</b>了这个覆盖，于是服务端的
 * {@code World.setBlockState} 调用经 Java 虚分派进入的是 Sponge 的版本，
 * {@code MixinWorld} 挂在父类 {@code World.func_180501_a} 上的 HEAD 注入根本不会被执行
 * （实测服务端 {@code WORLD-SET} 日志一条都没有，而 {@code OUTSIDE} 有 —— 因为后者挂在
 * 没有被 {@code WorldServer} 覆盖的 {@code func_189509_E} 上）。
 *
 * <p>而 {@code PhaseTracker.setBlockState} 对 y&lt;0 会直接返回 false（既不写区块、
 * 也不报错），最终 {@code /setblock} 报 {@code commands.setblock.failed}。
 *
 * <p>这里在 {@code WorldServer.setBlockState} 的 HEAD 处、只在原版区间之外接管，
 * {@code 0..255} 原样交回 Sponge，因此不影响 Sponge 的方块变更事件与常规光照逻辑。
 * 由 {@code ModMixinConfigPlugin} 按 Sponge 存在性门控，客户端不会加载。
 */
@Mixin(targets = "net.minecraft.world.WorldServer", remap = false)
public abstract class MixinSpongeWorldServerSetBlock {

    @Inject(method = "func_180501_a(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$worldServerSetBlockState(BlockPos pos, IBlockState state, int flags,
            CallbackInfoReturnable<Boolean> cir) {
        int y = pos.getY();

        if (y >= 0 && y < 256) {
            return;
        }

        WorldServer self = (WorldServer) (Object) this;
        HeightContext ctx = HeightManager.get((World) self);

        if (!ctx.isExtended() || y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        Chunk chunk = self.getChunk(pos);
        // 完整复刻原版 Chunk.setBlockState 的语义（含 onBlockAdded 与方块实体处理），
        // 只看扩展区间的 Y 映射差异。
        IBlockState old = ExtendedBlockWrite.setBlockState(self, chunk, pos, state, flags);

        if (old == null) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        cir.setReturnValue(Boolean.TRUE);
    }
}
