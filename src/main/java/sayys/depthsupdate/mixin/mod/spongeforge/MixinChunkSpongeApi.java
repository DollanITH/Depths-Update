package sayys.depthsupdate.mixin.mod.spongeforge;

import com.flowpowered.math.vector.Vector3i;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * SpongeForge 在 {@link Chunk} 里加了 {@code getBlockMin()} / {@code getBlockMax()} /
 * {@code containsBlock()} / {@code checkBlockBounds()} 范围检查，硬编码返回 0..256
 * （{@code SpongeChunkLayout.CHUNK_SIZE} = 16×256×16）。放置/破坏/交互方块时
 * {@code checkBlockBounds} 会拒绝 y&lt;0 或 y&gt;=256，导致：
 * <ul>
 *   <li>无法在 y&lt;0 / y&gt;255 放置方块（{@code setBlock} 前先抛 PositionOutOfBoundsException）；</li>
 *   <li>交互时服务端把该方块当作空气，客户端随即抹掉这个"假方块"。</li>
 * </ul>
 * 这里按该 chunk 所属世界的 {@link HeightContext} 返回真实 Y 边界，并放行扩展高度范围。
 * <p>
 * 注意 SpongeForge 这几个方法内部有缓存字段（api$blockMin / api$blockMax），首次调用时通过
 * SpongeChunkLayout.forceToWorld() 计算并缓存。HEAD 注入直接返回扩展高度范围，绕过缓存计算。
 * <p>
 * 这些方法由 Sponge 的 {@code ChunkMixin_API} 以 srg 名合并进 {@link Chunk}，运行时不被 MC
 * remap，故必须 remap=false；同时 {@code require = 0}，确保在未安装 Sponge 或方法签名不符时
 * 静默跳过而不致启动崩溃（门控仍由 ModMixinConfigPlugin 按类存在性兜底）。
 */
@Mixin(targets = "net.minecraft.world.chunk.Chunk", remap = false)
public abstract class MixinChunkSpongeApi {

    @Unique
    private HeightContext depthsupdate$ctx() {
        World world = ((Chunk) (Object) this).getWorld();
        return world == null ? HeightContext.VANILLA : HeightManager.get(world);
    }

    @Inject(method = "getBlockMin", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$chunkBlockMin(CallbackInfoReturnable<Vector3i> cir) {
        HeightContext ctx = depthsupdate$ctx();
        if (!ctx.isExtended()) {
            return;
        }
        Chunk self = (Chunk) (Object) this;
        cir.setReturnValue(new Vector3i((self.x << 4), ctx.minY(), (self.z << 4)));
    }

    @Inject(method = "getBlockMax", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$chunkBlockMax(CallbackInfoReturnable<Vector3i> cir) {
        HeightContext ctx = depthsupdate$ctx();
        if (!ctx.isExtended()) {
            return;
        }
        Chunk self = (Chunk) (Object) this;
        cir.setReturnValue(new Vector3i((self.x << 4) + 15, ctx.maxY() - 1, (self.z << 4) + 15));
    }

    @Inject(method = "checkBlockBounds", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$chunkCheckBlockBounds(int x, int y, int z, CallbackInfo ci) {
        HeightContext ctx = depthsupdate$ctx();
        if (!ctx.isExtended()) {
            return;
        }
        // 放行扩展 Y 范围；X/Z 仍在本 chunk 内由原版 getBlockState/setBlockState 的 &15 兜底。
        ci.cancel();
    }

    @Inject(method = "containsBlock", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void depthsupdate$chunkContainsBlock(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        HeightContext ctx = depthsupdate$ctx();
        if (!ctx.isExtended()) {
            return;
        }
        cir.setReturnValue(true);
    }
}
