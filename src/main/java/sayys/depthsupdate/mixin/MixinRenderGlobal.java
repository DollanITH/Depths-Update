package sayys.depthsupdate.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.RenderChunk;
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

    @ModifyVariable(
            method = "renderSky(FI)V",
            slice = @Slice(from = @At(value = "CONSTANT", args = "doubleValue=65.0D")),
            at = @At(value = "STORE", ordinal = 0),
            name = "f19",
            require = 1)
    public float depthsupdate$adjustVoidBoxHeight(float f19, @Local(argsOnly = true) float partialTicks) {
        HeightContext ctx = HeightManager.get(world);
        if (!ctx.isExtended()) {
            return f19;
        }
        return -((float) (depthsupdate$voidBoxD0(partialTicks) + 65.0 - (float) ctx.minY()));
    }

    @WrapWithCondition(
            method = "renderSky(FI)V",
            slice = @Slice(from = @At(value = "CONSTANT", args = "doubleValue=65.0D")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"),
            require = 1)
    public boolean depthsupdate$skipVoidBoxDraw(Tessellator tessellator,
            @Local(argsOnly = true) float partialTicks) {
        HeightContext ctx = HeightManager.get(world);
        if (!ctx.isExtended()) {
            return true; // 原版世界：照常绘制
        }
        if (depthsupdate$voidBoxD0(partialTicks) > ctx.minY()) {
            tessellator.getBuffer().finishDrawing(); // 丢弃已写入的盒体，复位 buffer
            return false;                            // 跳过原 draw
        }
        return true;
    }

    @Unique
    private double depthsupdate$voidBoxD0(float partialTicks) {
        return mc.player.getPositionEyes(partialTicks).y - world.getHorizon();
    }
}
