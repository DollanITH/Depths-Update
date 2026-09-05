package sayys.depthsupdate.mixin.mod.optifine;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.optifine.render.AabbFrame;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sayys.depthsupdate.core.HeightManager;

import java.lang.reflect.Field;

@Mixin(value = RenderChunk.class, remap = false)
public class MixinOptiFineRenderChunk {
    @Unique
    private static Field neighboursUpdatedField;

    @Unique
    private static Field offset16UpdatedField;

    @Unique
    private static boolean reflectionInitialized = false;

    @Unique
    private static boolean reflectionFailed = false;

    @Unique
    private static void initReflection() {
        if (reflectionInitialized) return;

        reflectionInitialized = true;

        try {
            neighboursUpdatedField = RenderChunk.class.getDeclaredField("renderChunkNeighboursUpated");
            neighboursUpdatedField.setAccessible(true);

            offset16UpdatedField = RenderChunk.class.getDeclaredField("renderChunksOffset16Updated");
            offset16UpdatedField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            reflectionFailed = true;
        }
    }

    @Inject(method = "setPosition", at = @At("HEAD"))
    private void depthsupdate$resetNeighbourFlags(int x, int y, int z, CallbackInfo ci) {
        if (reflectionFailed) return;

        initReflection();

        if (reflectionFailed) return;

        try {
            neighboursUpdatedField.setBoolean(this, false);
            offset16UpdatedField.setBoolean(this, false);
        } catch (Exception e) {}
    }

    @Dynamic
    @Shadow(remap = false)
    private RenderChunk[] renderChunkNeighboursValid;

    @Dynamic
    @Shadow(remap = false)
    private RenderChunk[] renderChunkNeighbours;

    @Shadow
    private World world;

    @Shadow
    public BlockPos getPosition() {
        return this.position;
    }

    @Shadow
    @Final
    private BlockPos.MutableBlockPos position;

    @Dynamic @Inject(method = "updateRenderChunkNeighboursValid()V", at = @At("HEAD"), remap = false)
    private void onUpdateNeighbors(CallbackInfo cbi) {
        if (!HeightManager.isExtended(this.world)) {
            return;
        }
        int y = this.getPosition().getY();
        int up = EnumFacing.UP.ordinal();
        int down = EnumFacing.DOWN.ordinal();
        this.renderChunkNeighboursValid[up] = this.renderChunkNeighbours[up].getPosition().getY() == y + 16 ?
                this.renderChunkNeighbours[up] : null;
        this.renderChunkNeighboursValid[down] = this.renderChunkNeighbours[down].getPosition().getY() == y - 16 ?
                this.renderChunkNeighbours[down] : null;
    }

}
