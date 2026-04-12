package sayys.depthsupdate.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {
    @Shadow
    private int renderDistanceChunks;

    @Shadow
    private ViewFrustum viewFrustum;

    /**
     * Fixes entity rendering in extended-height worlds.
     *
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
}
