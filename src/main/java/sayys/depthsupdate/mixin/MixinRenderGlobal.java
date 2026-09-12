package sayys.depthsupdate.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {
    @Shadow
    private int renderDistanceChunks;

    @Shadow
    private ViewFrustum viewFrustum;

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private WorldClient world;

    /**
     * Fixes entity rendering in extended-height worlds.
     * <p>
     * Vanilla's renderEntities() does: chunk.getEntityLists()[pos.getY() / 16]
     * In extended worlds, render chunks can have negative Y (e.g. -64), which gives
     * negative array indices and a crash. We transform Y so that dividing by 16 gives
     * the correct storage index from HeightContext.toStorageIndex().
     */
    @Redirect(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/RenderChunk;getPosition()Lnet/minecraft/util/math/BlockPos;"))
    private BlockPos depthsupdate$redirectRenderChunkPosForEntityArray(RenderChunk renderChunk) {
        BlockPos pos = renderChunk.getPosition();
        World world = Minecraft.getMinecraft().world;

        if (HeightManager.isExtended(world)) {
            HeightContext ctx = HeightManager.get(world);
            int storageIndex = ctx.toStorageIndex(pos.getY());
            return new BlockPos(pos.getX(), storageIndex * 16, pos.getZ());
        }

        return pos;
    }

    @Inject(method = "getRenderChunkOffset", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getRenderChunkOffset(BlockPos playerPos, RenderChunk renderChunkBase, EnumFacing facing,
            CallbackInfoReturnable<RenderChunk> cir) {
        World world = Minecraft.getMinecraft().world;

        if (!HeightManager.isExtended(world)) {
            return;
        }

        HeightContext ctx = HeightManager.get(world);
        BlockPos blockpos = renderChunkBase.getBlockPosOffset16(facing);

        if (MathHelper.abs(playerPos.getX() - blockpos.getX()) > this.renderDistanceChunks * 16) {
            cir.setReturnValue(null);
        } else if (blockpos.getY() < ctx.minY()
                || blockpos.getY() >= ctx.maxY()) {
            cir.setReturnValue(null);
        } else {
            cir.setReturnValue(MathHelper
                    .abs(playerPos.getZ() - blockpos.getZ()) > this.renderDistanceChunks * 16 ? null
                            : ((IMixinViewFrustum) this.viewFrustum).invokeGetRenderChunk(blockpos));
        }
    }

    @Unique
    private boolean depthsupdate$shouldSkipVoidBox(float partialTicks) {
        HeightContext ctx = HeightManager.get(world);
        if (!ctx.isExtended()) return false;
        return this.mc.player.getPositionEyes(partialTicks).y >= ctx.minY();
    }

    // 根据 f19 反推玩家是否已在 minY 之上，决定是否隐藏大黑盒
    @ModifyVariable(method = "renderSky(FI)V", at = @At("STORE"), name = "f19", ordinal = 12)
    public float depthsupdate$adjustVoidBoxHeight(float f19) {
        HeightContext ctx = HeightManager.get(world);
        if (!ctx.isExtended()) return f19;   // 普通世界：原版行为

        int minY = ctx.minY();
        // f19 = -(d0+65)，d0 = eyeY - getHorizon()(=63)
        // 所以 eyeY >= minY  ⟺  f19 <= -(minY+2)
        if (f19 <= -(minY + 2)) {
            // 玩家在 minY 之上：把大黑盒退化为零高度 → 不可见；
            // begin/draw 仍正常配对，共享缓冲区状态不受干扰，区块线程不会崩
            return -1.0F;
        }
        // 玩家在 minY 之下：正常渲染 + 原来的位置调整
        return f19 - (float) minY;
    }

    @WrapWithCondition(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BufferBuilder;begin(ILnet/minecraft/client/renderer/vertex/VertexFormat;)V",
                    ordinal = 3))
    public boolean depthsupdate$wrapVoidBoxBegin(
            BufferBuilder buffer, int glMode, VertexFormat format,
            @Local(argsOnly = true) float partialTicks) {
        if (depthsupdate$shouldSkipVoidBox(partialTicks)) {
            buffer.begin(glMode, format);  // 手动 begin，让后面的 .pos().color() 正常写入
            return false;               // 跳过原 begin
        }
        return true;                    // 原版：原 begin 正常执行
    }

    @WrapWithCondition(method = "renderSky(FI)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Tessellator;draw()V",
                    ordinal = 3))
    public boolean depthsupdate$wrapVoidBoxDraw(
            Tessellator tessellator,
            @Local(argsOnly = true) float partialTicks) {
        if (depthsupdate$shouldSkipVoidBox(partialTicks)) {
            tessellator.getBuffer().finishDrawing();  // 释放构建态，不画
            return false;
        }
        return true;
    }
}
