package sayys.depthsupdate.mixin.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient {
    @ModifyArg(method = "handleChunkData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;invalidateBlockReceiveRegion(IIIIII)V"), index = 1)
    private int depthsupdate$modifyInvalidateRegionMinY(int y1) {
        World world = Minecraft.getMinecraft().world;
        return HeightManager.isExtended(world) ? HeightManager.getMinY(world) : y1;
    }

    @ModifyArg(method = "handleChunkData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;invalidateBlockReceiveRegion(IIIIII)V"), index = 4)
    private int depthsupdate$modifyInvalidateRegionMaxY(int y2) {
        World world = Minecraft.getMinecraft().world;
        return HeightManager.isExtended(world) ? HeightManager.getMaxY(world) : y2;
    }

    @ModifyArg(method = "handleChunkData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;markBlockRangeForRenderUpdate(IIIIII)V"), index = 1)
    private int depthsupdate$modifyMarkRenderMinY(int y1) {
        World world = Minecraft.getMinecraft().world;
        return HeightManager.isExtended(world) ? HeightManager.getMinY(world) : y1;
    }

    @ModifyArg(method = "handleChunkData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;markBlockRangeForRenderUpdate(IIIIII)V"), index = 4)
    private int depthsupdate$modifyMarkRenderMaxY(int y2) {
        World world = Minecraft.getMinecraft().world;
        return HeightManager.isExtended(world) ? HeightManager.getMaxY(world) : y2;
    }

    /**
     * 客户端按 bitmask 位 N 创建 ExtendedBlockStorage(N << 4)，把 N=24..27 当成
     * y=384+ 高空 section。handleChunkData 结束后遍历 chunk storage 数组，
     * 把 negative sections 的 baseY 反射修正为真实起始 Y。
     */
    @Inject(method = "handleChunkData", at = @At("RETURN"))
    private void depthsupdate$fixNegativeSectionY(SPacketChunkData packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (!HeightManager.isExtended(world)) {
            return;
        }
        int chunkX;
        int chunkZ;
        try {
            chunkX = packet.getChunkX();
            chunkZ = packet.getChunkZ();
        } catch (Throwable t) {
            return;
        }
        Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        HeightContext ctx = HeightManager.get(world);
        ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        int negStart = ctx.totalStorageSections() - ctx.negativeSections();
        for (int i = negStart; i < storage.length; i++) {
            ExtendedBlockStorage s = storage[i];
            if (s == null) continue;
            int expectedY = i << 4;
            int correctY = ctx.fromStorageIndex(i) << 4;
            if (s.getYLocation() == expectedY) {
                try {
                    for (Field f : ExtendedBlockStorage.class.getDeclaredFields()) {
                        if (f.getType() == int.class && Modifier.isFinal(f.getModifiers())) {
                            f.setAccessible(true);
                            if (f.getInt(s) == expectedY) {
                                f.setInt(s, correctY);
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
