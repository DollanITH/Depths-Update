package sayys.depthsupdate.mixin;

import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 让区块封包带上<b>扩展区间</b>的数据与光照。
 *
 * <h2>★ 问题：深层段的光照从未被写入封包</h2>
 * 反汇编 {@code SPacketChunkData.extractChunkData}（offset 9-118）：
 * <pre>
 *   for (int idx = 0; idx &lt; storageArrays.length; ++idx) {   // 遍历全部段（含深层）
 *       if (storage == NULL_BLOCK_STORAGE) continue;
 *       if (isFullChunk() &amp;&amp; storage.isEmpty()) continue;
 *       if ((changedSectionFilter &amp; (1 &lt;&lt; idx)) == 0) continue;   // ★ 必须通过筛选
 *       ... 写入 BlockStateContainer、BlockLight、SkyLight ...
 *   }
 * </pre>
 *
 * <p>循环本身覆盖全部段，但每一段都要通过 {@code (filter & (1 << idx)) != 0}。
 * 而调用方 {@code PlayerChunkMapEntry.update()} 传的是 <b>65535</b>（{@code 0xFFFF}，
 * 只有低 16 位）—— 于是 {@code (1 << 16) & 65535 == 0}，<b>第 16..28 段全部被跳过</b>，
 * 深层方块与光照都不会进入封包。
 *
 * <p>实测印证：服务端光照数据完全正确（探针显示 {@code selfLight=15}、
 * 邻居 {@code light=14}），而客户端深层恒为 0 并渲染成黑色。
 *
 * <h2>修法（两件事必须同时做）</h2>
 * <ol>
 *   <li><b>放宽传入的段筛选参数</b>（{@link #depthsupdate$widenSectionFilter}）：
 *       扩展世界下把"完整区块请求"（调用方传 65535）提升为全段掩码，
 *       使深层段的方块与光照都被打包。</li>
 *   <li><b>保持 {@code fullChunk} 的判定语义不变</b>
 *       （{@link #depthsupdate$modifyFullChunkCheck}）。</li>
 * </ol>
 *
 * <p>★ 上一次修复<b>只做了第 1 件事而删掉了第 2 件</b>：把那个用于判定
 * {@code fullChunk} 的 65535 常量一并换成了全段掩码，于是
 * {@code fullChunk = (65535 == 全段掩码)} = <b>false</b> ——
 * 完整区块被当成"部分更新"处理，客户端出现贴图图集全空、卡在
 * "Waiting for chunk..."。因此这两处必须分别对待：一个改<b>参数</b>，一个改<b>常量</b>。
 *
 * <p>{@code priority = 1100}：SpongeForge 的 SPacketChunkDataMixin 会包装区块封包构建，
 * 需在其之后应用。
 */
@Mixin(value = SPacketChunkData.class, priority = 1100)
public class MixinSPacketChunkData {
    @Unique
    private static final ThreadLocal<HeightContext> depthsupdate$ctx =
            ThreadLocal.withInitial(() -> HeightContext.VANILLA);

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At(value = "HEAD", unsafe = true))
    private static void depthsupdate$captureChunk(Chunk chunkIn, int changedSectionFilter, CallbackInfo ci) {
        depthsupdate$ctx.set(HeightManager.get(chunkIn.getWorld()));
    }

    /**
     * 保持原版的 {@code fullChunk} 判定：{@code changedSectionFilter == 65535}。
     *
     * <p>注意这里把常量换成 {@code ctx.fullChunkSectionMask()} —— 调用方传的仍是 65535，
     * 因此判定结果不变（都是 false）。这样写是为了与 {@code extractChunkData} 收到的
     * 放宽后的 filter 保持"同一套段掩码"语义，避免两处不一致。
     */
    @ModifyConstant(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", constant = @Constant(intValue = 65535))
    private int depthsupdate$modifyFullChunkCheck(int original) {
        HeightContext ctx = depthsupdate$ctx.get();

        return ctx.isExtended() ? ctx.fullChunkSectionMask() : original;
    }

    /**
     * 放宽传给 {@code extractChunkData} 的段筛选参数。
     *
     * <p>只在"调用方本来就是完整区块请求"（{@code original == 65535}）时放宽；
     * 真正的部分更新保持调用方语义不变。
     */
    @ModifyArg(
            method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/play/server/SPacketChunkData;extractChunkData(Lnet/minecraft/network/PacketBuffer;Lnet/minecraft/world/chunk/Chunk;ZI)I"),
            index = 3)
    private int depthsupdate$widenSectionFilter(int original) {
        HeightContext ctx = depthsupdate$ctx.get();

        if (!ctx.isExtended() || original != 65535) {
            return original;
        }

        return ctx.fullChunkSectionMask();
    }

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V", at = @At("RETURN"))
    private void depthsupdate$cleanup(Chunk chunkIn, int changedSectionFilter, CallbackInfo ci) {
        depthsupdate$ctx.remove();
    }
}