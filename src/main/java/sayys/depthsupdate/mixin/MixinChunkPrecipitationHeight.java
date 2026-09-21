package sayys.depthsupdate.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sayys.depthsupdate.core.HeightManager;

@Mixin(value = Chunk.class, priority = 1001)
public abstract class MixinChunkPrecipitationHeight {

    @Final
    @Shadow
    private int[] heightMap;

    @Final
    @Shadow
    private net.minecraft.world.World world;

    @Inject(method = "getPrecipitationHeight", at = @At("HEAD"), cancellable = true, remap = false)
    private void depthsupdate$getPrecipitationHeight(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        if (!HeightManager.isExtended(this.world)) {
            return;
        }
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        int y = this.heightMap[z << 4 | x];
        cir.setReturnValue(new BlockPos(pos.getX(), y, pos.getZ()));
    }
}
