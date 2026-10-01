package sayys.depthsupdate.mixin.mod.spongeforge;

import com.flowpowered.math.vector.Vector3i;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * SpongeForge 生成阶段会把 vanilla 的 {@code ChunkPrimer} 包进自己的
 * {@code ChunkPrimerBuffer}，其构造器写死：
 * <pre>
 *   super(getBlockStart(chunkX, chunkZ), SpongeChunkLayout.CHUNK_SIZE);
 * </pre>
 * 其中 {@code SpongeChunkLayout.CHUNK_SIZE} 是硬编码的 16×256×16，且完全不读世界实际高度、
 * 也不感知 MixinChunkPrimer 扩展出的数据数组。因此生成期任何负 Y 写入都会在
 * {@code AbstractBlockBuffer.checkRange} 越界崩溃（PositionOutOfBoundsException）。
 *
 * 这里把缓冲的 start.Y 改为 minY、size.Y 改为 (maxY - minY)，让生成期缓冲同样覆盖扩展高度，
 * 负 Y 写入即可落到 MixinChunkPrimer 扩展出的数据数组上（和 RTG 走 post-gen 写扩展存储等价）。
 * 仅当 HeightManager 报告扩展（isExtended）时生效；否则完全保持 vanilla 行为。
 */
// Sponge 类与其方法名（getBlockStart / CHUNK_SIZE 等）是固定名，运行时不被 MC remap，
// 必须 remap=false，避免 MOD 阶段 mixin 误 remap 目标类名而解析失败。
@Mixin(targets = "org.spongepowered.common.util.gen.ChunkPrimerBuffer", remap = false)
public abstract class MixinChunkPrimerBuffer {

    @Redirect(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lorg/spongepowered/common/util/gen/ChunkPrimerBuffer;getBlockStart(II)Lcom/flowpowered/math/vector/Vector3i;"))
    private static Vector3i depthsupdate$extendStart(int chunkX, int chunkZ) {
        HeightContext ctx = HeightManager.getMaxContext();
        int minY = ctx.isExtended() ? ctx.minY() : 0;
        return new Vector3i(chunkX * 16, minY, chunkZ * 16);
    }

    @Redirect(method = "<init>",
            at = @At(value = "FIELD",
                    target = "Lorg/spongepowered/common/world/storage/SpongeChunkLayout;CHUNK_SIZE:Lcom/flowpowered/math/vector/Vector3i;"))
    private static Vector3i depthsupdate$extendSize() {
        HeightContext ctx = HeightManager.getMaxContext();
        if (!ctx.isExtended()) {
            return new Vector3i(16, 256, 16);
        }
        return new Vector3i(16, ctx.maxY() - ctx.minY(), 16);
    }

    /**
     * 洞穴生成器（尤其 ClimateControl 找出生点、this.world 为空时）的隧道数学会把
     * Y 走到世界底以下（实测 -103）。缓冲的 checkRange 对此抛 PositionOutOfBoundsException。
     * 在缓冲读写入口把世界 Y 钳到 [minY, maxY-1]：读钳到底界（基岩→视为不可挖）、
     * 写钳到底界以上，让洞穴止于世界底，堵死所有洞穴路径的越界。
     * 此注入挂在 {@code ChunkPrimerBuffer.getBlock/setBlock}（本 mixin 已证实能生效的类），
     * 是每个生成期读写必经的咽喉。
     */
    @ModifyVariable(method = "getBlock", at = @At("HEAD"), index = 2)
    private int depthsupdate$clampGetY(int y) {
        HeightContext ctx = HeightManager.getMaxContext();
        return Math.max(ctx.minY(), Math.min(y, ctx.maxY() - 1));
    }

    @ModifyVariable(method = "setBlock", at = @At("HEAD"), index = 2)
    private int depthsupdate$clampSetY(int y) {
        HeightContext ctx = HeightManager.getMaxContext();
        return Math.max(ctx.minY(), Math.min(y, ctx.maxY() - 1));
    }
}
