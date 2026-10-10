package sayys.depthsupdate.mixin.client;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 客户端专用的 Chunk 补丁，修正网络区块数据读取时负层 section 的 Y 基准。
 *
 * 原版客户端 Chunk.read(PacketBuffer, int, boolean) 读取 SPacketChunkData 时，按 bitmask 位 N
 * 为每个 section 创建 new ExtendedBlockStorage(N << 4, ...)：
 *   - index 0..15（Y 0..255）与 index 16..24（上层 Y 256..399）：N << 4 恰好正确；
 *   - index 25..28（负层 sectionY -1..-4）：N << 4 = 400..448，真实 Y 应为 -16..-64。
 * 结果客户端把负层地形渲染到 Y 400+，真正的 y<0 变成虚空：服务端 NBT 存档里有方块，
 * 客户端却是"假方块/漏洞"，实体在服务端站立、客户端看起来却在往下掉。
 *
 * 这里把构造函数第一个参数（section 的 Y 基准）从 N << 4 改成 fromStorageIndex(N) << 4，
 * 让负层 section 落到正确的负 Y；非扩展世界时 fromStorageIndex 对 0..15 恒等返回，行为不变。
 *
 * 注意：Chunk.read(PacketBuffer,int,boolean) 是 @SideOnly(Side.CLIENT)，服务端会剥离，
 * 因此必须放在客户端专属配置的 client 段。
 */
@Mixin(Chunk.class)
public abstract class MixinChunkClient {

    @Unique
    private HeightContext depthsupdate$clientCtx() {
        World world = ((Chunk) (Object) this).getWorld();
        return world == null ? HeightContext.VANILLA : HeightManager.get(world);
    }

    @Redirect(method = "read",
            at = @At(value = "NEW", target = "net/minecraft/world/chunk/storage/ExtendedBlockStorage"))
    private ExtendedBlockStorage depthsupdate$fixReadSectionY(int y, boolean storeSkylight) {
        HeightContext ctx = depthsupdate$clientCtx();
        int correctedY = y;

        if (ctx.isExtended()) {
            int sectionIndex = y >> 4;
            int corrected = ctx.fromStorageIndex(sectionIndex);

            if (corrected != Integer.MIN_VALUE) {
                correctedY = corrected << 4;
            }
        }

        return new ExtendedBlockStorage(correctedY, storeSkylight);
    }
}