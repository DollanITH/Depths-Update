package sayys.depthsupdate.core;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * 扩展高度区间（{@code y<0 || y>=256}）的方块写入，完整复刻原版
 * {@code Chunk.setBlockState} 的语义与副作用。
 *
 * <p>为什么需要它：SpongeForge 在 {@code WorldServer} 上覆盖了 {@code setBlockState}
 * （走 {@code PhaseTracker}，对 y&lt;0 直接返回 false），又在 {@code Chunk} 上按名覆盖了
 * {@code func_177436_a}，导致模组原本挂在区块层的写入注入不可用。于是写入改由
 * {@code WorldServer.setBlockState} 的钩子接管。
 *
 * <p>但"只把方块写进 section"是<b>不够</b>的 —— 反编译原版 {@code Chunk.setBlockState} 可见
 * 它还做了一整套副作用，缺一个都会产生可见故障：
 *
 * <pre>
 * 203: storage.set(x, y&amp;15, z, state)
 * 232: block1.breakBlock(world, pos, old)                       // 旧方块清理
 * 260: te.shouldRefresh(...) → world.removeTileEntity(pos)      // 方块实体移除
 * 357: generateSkylightMap() / relightBlock / propagateSkylightOcclusion   // 光照
 * 507: block.onBlockAdded(world, pos, state)                    // ★ 关键
 * 521: getTileEntity → createTileEntity → setTileEntity → updateContainingBlockInfo()
 * 538: dirty = true
 * </pre>
 *
 * <p>实测缺 {@code onBlockAdded} 与方块实体处理的后果：
 * <ul>
 *   <li>铁轨不连接 —— {@code BlockRailBase.onBlockAdded} 才会构造 {@code Rail} 并调 {@code place()}；</li>
 *   <li>箱子不合并 —— {@code BlockChest.onBlockAdded} 才会调 {@code checkForSurroundingChests}，
 *       且箱子方块实体不会被创建，出现"渲染是小箱子、打开却是大箱子"；</li>
 *   <li>Railcraft 轨道放不下 —— 它同样依赖自己的 {@code onBlockAdded}/支撑判定；</li>
 *   <li>"假告示牌" —— 支撑被打掉后告示牌方块已移除，但它的方块实体没被清掉，
 *       客户端因此继续渲染一个不存在的告示牌（重进才消失）。</li>
 * </ul>
 *
 * <p>这里只在原版区间之外接管；{@code 0..255} 由调用方原样交回原版。
 * 只使用原版类型，双端安全。
 */
public final class ExtendedBlockWrite {

    private ExtendedBlockWrite() {
    }

    /**
     * 在扩展高度区间写入方块，等价于原版 {@code chunk.setBlockState(pos, newState)}。
     *
     * @return 写入前的方块状态；未发生变化或坐标越界时返回 {@code null}（与原版语义一致，
     *         调用方据此让 {@code World.setBlockState} 返回 false）。
     */
    public static IBlockState setBlockState(World world, Chunk chunk, BlockPos pos, IBlockState newState, int flags) {
        if (world == null || chunk == null || pos == null || newState == null) {
            return null;
        }

        int y = pos.getY();
        HeightContext ctx = HeightManager.get(world);

        if (!ctx.isExtended() || y < ctx.minY() || y >= ctx.maxY()) {
            return null;
        }

        // getBlockStorageArray() 会触发 MixinChunk 的扩容，保证扩展 section 可寻址。
        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        int storageIndex = ctx.toStorageIndex(y);

        if (storageIndex < 0 || storageIndex >= storageArrays.length) {
            return null;
        }

        int localX = pos.getX() & 15;
        int localY = y & 15;
        int localZ = pos.getZ() & 15;
        IBlockState oldState = chunk.getBlockState(pos);

        if (oldState == newState) {
            return null;
        }

        Block newBlock = newState.getBlock();
        Block oldBlock = oldState.getBlock();
        ExtendedBlockStorage storage = storageArrays[storageIndex];
        boolean createdSection = false;

        if (storage == null || storage == Chunk.NULL_BLOCK_STORAGE) {
            if (newBlock == Blocks.AIR) {
                return null;
            }

            storage = new ExtendedBlockStorage(y >> 4 << 4, world.provider.hasSkyLight());
            storageArrays[storageIndex] = storage;
            createdSection = true;
        }

        storage.set(localX, localY, localZ, newState);

        // 原版 232-271：旧方块清理 + 方块实体移除（服务端）；客户端只在旧方块带方块实体时处理。
        if (!world.isRemote) {
            if (oldBlock != newBlock) {
                oldBlock.breakBlock(world, pos, oldState);
            }

            TileEntity tileEntity = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

            if (tileEntity != null && tileEntity.shouldRefresh(world, pos, oldState, newState)) {
                world.removeTileEntity(pos);
            }
        } else if (oldBlock.hasTileEntity(oldState)) {
            TileEntity tileEntity = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

            if (tileEntity != null && tileEntity.shouldRefresh(world, pos, oldState, newState)) {
                world.removeTileEntity(pos);
            }
        }

        // 原版 357-460：光照。新建 section 时重算天光图；局部情况交给下面的 checkLight 处理。
        // 注意原版这里还会调私有的 relightBlock / propagateSkylightOcclusion，二者是 private，
        // 非 mixin 类无法直接调用；用公开的 World.checkLight(pos) 等价覆盖——
        // 它内部会对 SKY 与 BLOCK 两个通道各调一次 checkLightFor，正是同一套传播逻辑。
        if (createdSection) {
            chunk.generateSkylightMap();
        }

        // 原版 464-507：★ onBlockAdded —— 铁轨连接、箱子合并、Railcraft 轨道等全靠它。
        if (!world.isRemote && oldBlock != newBlock
                && (!world.captureBlockSnapshots || newBlock.hasTileEntity())) {
            newBlock.onBlockAdded(world, pos, newState);
        }

        // 原版 513-536：★ 方块实体创建/刷新 —— 缺了它箱子等方块实体不存在。
        if (newBlock.hasTileEntity(newState)) {
            TileEntity tileEntity = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

            if (tileEntity == null) {
                tileEntity = newBlock.createTileEntity(world, newState);
                world.setTileEntity(pos, tileEntity);
            }

            if (tileEntity != null) {
                tileEntity.updateContainingBlockInfo();
            }
        }

        chunk.markDirty();
        // markAndNotifyBlock 里通知客户端 / 通知邻居都被 flags 卡着（字节码 9-13 与 58-62），
        // 而扩展区间没有别的东西会补这两步，因此显式补齐。
        world.markAndNotifyBlock(pos, chunk, oldState, newState, flags);
        world.notifyBlockUpdate(pos, oldState, newState, flags);

        for (EnumFacing facing : EnumFacing.values()) {
            world.neighborChanged(pos.offset(facing), oldBlock, pos);
        }

        // SKY + BLOCK 两个通道一次覆盖（内部即两次 checkLightFor）。
        world.checkLight(pos);

        return oldState;
    }
}
