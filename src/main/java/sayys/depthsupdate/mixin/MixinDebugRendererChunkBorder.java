package sayys.depthsupdate.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.debug.DebugRendererChunkBorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sayys.depthsupdate.core.HeightManager;

@Mixin(DebugRendererChunkBorder.class)
public class MixinDebugRendererChunkBorder {

    @Shadow
    private Minecraft minecraft;

    @Inject(
        method = "render",
        at = @At("HEAD"),
        cancellable = true
    )
    private void depthsupdate$renderExtended(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        World world = minecraft.world;
        if (world == null) {
            return;
        }

        if (HeightManager.isExtended(world)) {
            ci.cancel();

            int minY = HeightManager.getMinY(world);
            int maxY = HeightManager.getMaxY(world);

            EntityPlayerSP player = minecraft.player;
            if (player == null) {
                return;
            }

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();

            double d0 = player.posX;
            double d1 = player.posZ;
            double d2 = player.posY;

            double renderMinY = minY;
            double renderMaxY = maxY;

            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

            int playerChunkX = player.chunkCoordX;
            int playerChunkZ = player.chunkCoordZ;

            for (int i = -8; i <= 8; ++i) {
                for (int j = -8; j <= 8; ++j) {
                    int chunkX = playerChunkX + i;
                    int chunkZ = playerChunkZ + j;

                    double chunkXPos = (chunkX << 4) + 0.5D - d0;
                    double chunkZPos = (chunkZ << 4) + 0.5D - d1;

                    buffer.begin(3, DefaultVertexFormats.POSITION_COLOR);
                    buffer.pos(chunkXPos - 8.0D, renderMinY - d2, chunkZPos - 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos + 8.0D, renderMinY - d2, chunkZPos - 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos + 8.0D, renderMinY - d2, chunkZPos + 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos - 8.0D, renderMinY - d2, chunkZPos + 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos - 8.0D, renderMinY - d2, chunkZPos - 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();

                    buffer.pos(chunkXPos - 8.0D, renderMaxY - d2, chunkZPos - 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos + 8.0D, renderMaxY - d2, chunkZPos - 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos + 8.0D, renderMaxY - d2, chunkZPos + 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos - 8.0D, renderMaxY - d2, chunkZPos + 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                    buffer.pos(chunkXPos - 8.0D, renderMaxY - d2, chunkZPos - 8.0D).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();

                    for (int k = -8; k <= 8; k += 16) {
                        for (int l = -8; l <= 8; l += 16) {
                            buffer.pos((chunkX << 4) + k + 0.5D - d0, renderMinY - d2, (chunkZ << 4) + l + 0.5D - d1).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                            buffer.pos((chunkX << 4) + k + 0.5D - d0, renderMaxY - d2, (chunkZ << 4) + l + 0.5D - d1).color(1.0F, 0.0F, 0.0F, 1.0F).endVertex();
                            buffer.pos((chunkX << 4) + k + 0.5D - d0, renderMinY - d2, (chunkZ << 4) + l + 0.5D - d1).color(0.5F, 0.0F, 0.0F, 1.0F).endVertex();
                            buffer.pos((chunkX << 4) + k + 0.5D - d0, renderMinY - d2, (chunkZ << 4) + l + 0.5D - d1).color(0.5F, 0.0F, 0.0F, 1.0F).endVertex();
                            buffer.pos((chunkX << 4) + k + 0.5D - d0, renderMaxY - d2, (chunkZ << 4) + l + 0.5D - d1).color(0.5F, 0.0F, 0.0F, 1.0F).endVertex();
                        }
                    }
                    tessellator.draw();
                }
            }

            GlStateManager.disableBlend();
        }
    }
}