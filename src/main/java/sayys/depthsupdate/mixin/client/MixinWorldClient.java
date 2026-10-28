package sayys.depthsupdate.mixin.client;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(WorldClient.class)
public class MixinWorldClient {
    @ModifyArg(
            method="doPreChunk",
            at=@At(
                    value="INVOKE",
                    target="Lnet/minecraft/client/multiplayer/WorldClient;markBlockRangeForRenderUpdate(IIIIII)V"
            ),
            index=1
    )
    private int depthsupdate$modifyPreChunkMinY(int y1) {
        return HeightManager.getMinY((World) (Object) this);
    }

    @ModifyArg(
            method="doPreChunk",
            at=@At(
                    value="INVOKE",
                    target="Lnet/minecraft/client/multiplayer/WorldClient;markBlockRangeForRenderUpdate(IIIIII)V"
            ),
            index=4
    )
    private int depthsupdate$modifyPreChunkMaxY(int y2) {
        return HeightManager.getMaxY((World) (Object) this);
    }

    @ModifyArg(
            method="playMoodSoundAndCheckLight(IILnet/minecraft/world/chunk/Chunk;)V",
            at=@At(
                    value="INVOKE",
                    target="Lnet/minecraft/util/math/BlockPos;<init>(III)V"
            ),
            index=1,
            require=0
    )
    private int depthsupdate$scaleMoodSoundY(int originalY) {
        World self = (World) (Object) this;

        if (!HeightManager.isExtended(self)) {
            return originalY;
        }

        HeightContext heightContext = HeightManager.get(self);

        return heightContext.minY() + (int) ((long) originalY * heightContext.totalHeight() / 256);
    }
}