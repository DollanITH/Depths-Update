package sayys.depthsupdate.mixin.mod.journeymap;

import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import journeymap.client.data.DataCache;
import journeymap.client.event.handlers.ChunkMonitorHandler;
import journeymap.client.model.chunk.ChunkMD;
import journeymap.client.task.multi.MapPlayerTask;

@Mixin(ChunkMonitorHandler.class)
public abstract class MixinJourneyMapChunkMonitorHandler {

    @Inject(method = "notifyBlockUpdate(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/block/state/IBlockState;I)V", at = @At("HEAD"), remap = false)
    private void depthsupdate$notifyBlockUpdate(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos, net.minecraft.block.state.IBlockState oldState, net.minecraft.block.state.IBlockState newState, int flags, CallbackInfo ci) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        long chunkPosLong = ChunkPos.asLong(chunkX, chunkZ);

        // 从缓存里移除这个 chunk，让它重新加载
        DataCache.INSTANCE.invalidateChunkMD(chunkPos);

        // 获取 ChunkMD（从实时 chunk 加载）
        ChunkMD chunkMD = DataCache.INSTANCE.getChunkMD(chunkPosLong);
        if (chunkMD != null) {
            // 清空方块数据缓存
            chunkMD.getBlockData().clearAll();

            // 重置渲染时间，让它重新渲染
            chunkMD.resetRenderTimes();
        }

        // 从 MapPlayerTask 的"最近已经渲染过"缓存里移除这个 chunk
        MapPlayerTask.resetRecentRenderTimes(chunkPosLong);
    }
}
