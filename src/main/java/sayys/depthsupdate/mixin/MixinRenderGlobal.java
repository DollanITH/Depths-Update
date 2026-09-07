package sayys.depthsupdate.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {
    @Shadow
    private int renderDistanceChunks;

    @Shadow
    private ViewFrustum viewFrustum;

    @Shadow
    private WorldClient world;

    @Shadow
    private int glSkyList2;

    @Shadow
    private VertexBuffer sky2VBO;

    @Shadow
    private boolean vboEnabled;

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private int starGLCallList;

    @Shadow
    private VertexBuffer starVBO;

    @Shadow
    @Final
    private TextureManager renderEngine;

    @Shadow
    @Final
    private static ResourceLocation MOON_PHASES_TEXTURES;

    @Shadow
    @Final
    private static ResourceLocation SUN_TEXTURES;

    @Shadow
    private int glSkyList;

    @Shadow
    private VertexBuffer skyVBO;

    @Shadow
    protected abstract void renderSkyEnd();

    @Shadow
    @Final
    private static ResourceLocation CLOUDS_TEXTURES;

    @Shadow
    private int cloudTickCounter;

    @Shadow
    protected abstract void renderCloudsFancy(float partialTicks, int pass, double x, double y, double z);

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

    @Inject(method = "renderSky(FI)V", at = @At(value = "HEAD"), cancellable = true)
    private void getVoidHeight(float partialTicks, int pass, CallbackInfo ci) {
        net.minecraftforge.client.IRenderHandler renderer = this.world.provider.getSkyRenderer();
        if (renderer != null)
        {
            renderer.render(partialTicks, world, mc);
            return;
        }

        if (this.mc.world.provider.getDimensionType().getId() == 1)
        {
            this.renderSkyEnd();
        }
        else if (this.mc.world.provider.isSurfaceWorld())
        {
            GlStateManager.disableTexture2D();
            Vec3d vec3d = this.world.getSkyColor(this.mc.getRenderViewEntity(), partialTicks);
            float f = (float)vec3d.x;
            float f1 = (float)vec3d.y;
            float f2 = (float)vec3d.z;

            if (pass != 2)
            {
                float f3 = (f * 30.0F + f1 * 59.0F + f2 * 11.0F) / 100.0F;
                float f4 = (f * 30.0F + f1 * 70.0F) / 100.0F;
                float f5 = (f * 30.0F + f2 * 70.0F) / 100.0F;
                f = f3;
                f1 = f4;
                f2 = f5;
            }

            GlStateManager.color(f, f1, f2);
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder bufferbuilder = tessellator.getBuffer();
            GlStateManager.depthMask(false);
            GlStateManager.enableFog();
            GlStateManager.color(f, f1, f2);

            if (this.vboEnabled)
            {
                this.skyVBO.bindBuffer();
                GlStateManager.glEnableClientState(32884);
                GlStateManager.glVertexPointer(3, 5126, 12, 0);
                this.skyVBO.drawArrays(7);
                this.skyVBO.unbindBuffer();
                GlStateManager.glDisableClientState(32884);
            }
            else
            {
                GlStateManager.callList(this.glSkyList);
            }

            GlStateManager.disableFog();
            GlStateManager.disableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO
            );
            RenderHelper.disableStandardItemLighting();
            float[] afloat = this.world.provider.calcSunriseSunsetColors(this.world.getCelestialAngle(partialTicks), partialTicks);

            if (afloat != null)
            {
                GlStateManager.disableTexture2D();
                GlStateManager.shadeModel(7425);
                GlStateManager.pushMatrix();
                GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
                GlStateManager.rotate(MathHelper.sin(this.world.getCelestialAngleRadians(partialTicks)) < 0.0F ? 180.0F : 0.0F, 0.0F, 0.0F, 1.0F);
                GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
                float f6 = afloat[0];
                float f7 = afloat[1];
                float f8 = afloat[2];

                if (pass != 2)
                {
                    float f9 = (f6 * 30.0F + f7 * 59.0F + f8 * 11.0F) / 100.0F;
                    float f10 = (f6 * 30.0F + f7 * 70.0F) / 100.0F;
                    float f11 = (f6 * 30.0F + f8 * 70.0F) / 100.0F;
                    f6 = f9;
                    f7 = f10;
                    f8 = f11;
                }

                bufferbuilder.begin(6, DefaultVertexFormats.POSITION_COLOR);
                bufferbuilder.pos(0.0, 100.0, 0.0).color(f6, f7, f8, afloat[3]).endVertex();
                int j = 16;

                for (int l = 0; l <= 16; l++)
                {
                    float f21 = l * (float)(Math.PI * 2) / 16.0F;
                    float f12 = MathHelper.sin(f21);
                    float f13 = MathHelper.cos(f21);
                    bufferbuilder.pos(f12 * 120.0F, f13 * 120.0F, -f13 * 40.0F * afloat[3])
                            .color(afloat[0], afloat[1], afloat[2], 0.0F)
                            .endVertex();
                }

                tessellator.draw();
                GlStateManager.popMatrix();
                GlStateManager.shadeModel(7424);
            }

            GlStateManager.enableTexture2D();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO
            );
            GlStateManager.pushMatrix();
            float f16 = 1.0F - this.world.getRainStrength(partialTicks);
            GlStateManager.color(1.0F, 1.0F, 1.0F, f16);
            GlStateManager.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(this.world.getCelestialAngle(partialTicks) * 360.0F, 1.0F, 0.0F, 0.0F);
            float f17 = 30.0F;
            this.renderEngine.bindTexture(SUN_TEXTURES);
            bufferbuilder.begin(7, DefaultVertexFormats.POSITION_TEX);
            bufferbuilder.pos(-f17, 100.0, -f17).tex(0.0, 0.0).endVertex();
            bufferbuilder.pos(f17, 100.0, -f17).tex(1.0, 0.0).endVertex();
            bufferbuilder.pos(f17, 100.0, f17).tex(1.0, 1.0).endVertex();
            bufferbuilder.pos(-f17, 100.0, f17).tex(0.0, 1.0).endVertex();
            tessellator.draw();
            f17 = 20.0F;
            this.renderEngine.bindTexture(MOON_PHASES_TEXTURES);
            int i = this.world.getMoonPhase();
            int k = i % 4;
            int i1 = i / 4 % 2;
            float f22 = (k + 0) / 4.0F;
            float f23 = (i1 + 0) / 2.0F;
            float f24 = (k + 1) / 4.0F;
            float f14 = (i1 + 1) / 2.0F;
            bufferbuilder.begin(7, DefaultVertexFormats.POSITION_TEX);
            bufferbuilder.pos(-f17, -100.0, f17).tex(f24, f14).endVertex();
            bufferbuilder.pos(f17, -100.0, f17).tex(f22, f14).endVertex();
            bufferbuilder.pos(f17, -100.0, -f17).tex(f22, f23).endVertex();
            bufferbuilder.pos(-f17, -100.0, -f17).tex(f24, f23).endVertex();
            tessellator.draw();
            GlStateManager.disableTexture2D();
            float f15 = this.world.getStarBrightness(partialTicks) * f16;

            if (f15 > 0.0F)
            {
                GlStateManager.color(f15, f15, f15, f15);

                if (this.vboEnabled)
                {
                    this.starVBO.bindBuffer();
                    GlStateManager.glEnableClientState(32884);
                    GlStateManager.glVertexPointer(3, 5126, 12, 0);
                    this.starVBO.drawArrays(7);
                    this.starVBO.unbindBuffer();
                    GlStateManager.glDisableClientState(32884);
                }
                else
                {
                    GlStateManager.callList(this.starGLCallList);
                }
            }

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.enableFog();
            GlStateManager.popMatrix();
            GlStateManager.disableTexture2D();
            GlStateManager.color(0.0F, 0.0F, 0.0F);
            double d0 = this.mc.player.getPositionEyes(partialTicks).y - this.world.getHorizon();

            if (d0 < 0.0D)
            {
                GlStateManager.pushMatrix();
                GlStateManager.translate(0.0F, 12.0F, 0.0F);

                if (this.vboEnabled)
                {
                    this.sky2VBO.bindBuffer();
                    GlStateManager.glEnableClientState(32884);
                    GlStateManager.glVertexPointer(3, 5126, 12, 0);
                    this.sky2VBO.drawArrays(7);
                    this.sky2VBO.unbindBuffer();
                    GlStateManager.glDisableClientState(32884);
                }
                else
                {
                    GlStateManager.callList(this.glSkyList2);
                }

                GlStateManager.popMatrix();

                if (d0 < HeightManager.getMinY(world)) {
                    float f_height = -((float) (d0 + 58.0 - HeightManager.getMinY(world)));
                    bufferbuilder.begin(7, DefaultVertexFormats.POSITION_COLOR);
                    bufferbuilder.pos(-1.0, f_height, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, f_height, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, -1.0, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, -1.0, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, -1.0, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, -1.0, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, f_height, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, f_height, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, -1.0, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, -1.0, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, f_height, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, f_height, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, f_height, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, f_height, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, -1.0, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, -1.0, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, -1.0, -1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(-1.0, -1.0, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, -1.0, 1.0).color(0, 0, 0, 255).endVertex();
                    bufferbuilder.pos(1.0, -1.0, -1.0).color(0, 0, 0, 255).endVertex();
                    tessellator.draw();
                }
            }

            if (this.world.provider.isSkyColored())
            {
                GlStateManager.color(f * 0.2F + 0.04F, f1 * 0.2F + 0.04F, f2 * 0.6F + 0.1F);
            }
            else
            {
                GlStateManager.color(f, f1, f2);
            }

            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, -((float)(d0 - this.world.getHorizon() - 16.0)), 0.0F);
            GlStateManager.callList(this.glSkyList2);
            GlStateManager.popMatrix();
            GlStateManager.enableTexture2D();
            GlStateManager.depthMask(true);
        }
        ci.cancel();
    }

}
