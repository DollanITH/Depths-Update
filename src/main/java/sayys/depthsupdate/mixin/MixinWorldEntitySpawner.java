package sayys.depthsupdate.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldEntitySpawner;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightManager;

@Mixin(WorldEntitySpawner.class)
public class MixinWorldEntitySpawner {
    @Inject(method = "getRandomChunkPosition", at = @At("HEAD"), cancellable = true)
    private static void depthsupdate$fixGetRandomChunkPosition(World worldIn, int x, int z, CallbackInfoReturnable<BlockPos> cir) {
        if (HeightManager.isExtended(worldIn)) {
            Chunk chunk = worldIn.getChunk(x, z);
            int minY = HeightManager.getMinY(worldIn);
            int topY = chunk.getTopFilledSegment() + 16;

            int range = topY - minY;
            if (range < 1) range = 1;

            int y = minY + worldIn.rand.nextInt(range);
            cir.setReturnValue(new BlockPos(x * 16 + worldIn.rand.nextInt(16), y, z * 16 + worldIn.rand.nextInt(16)));
        }
    }
}
