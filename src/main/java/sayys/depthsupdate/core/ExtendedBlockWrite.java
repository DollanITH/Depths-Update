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

import sayys.depthsupdate.util.BlockUtils;

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

        // 深层矿石替换：等价于 MixinChunk#depthsupdate$setBlockState 里原来的
        //     if (j < 0 || BlockUtils.isDeepslate(iblockstate)) state = getDeepslateVariant(state);
        // 那句注入挂在 Chunk.setBlockState 上，而 SpongeForge 在 Chunk 上按名覆写了该方法，
        // 注入不会执行；写入改由本类接管后必须把这一步带过来，否则 y<0 的矿石不再变成深层矿石
        // （实测：深层出现普通煤矿/铁矿石，而不是 deepslate 变种）。
        // 条件与原实现一致：位于负层，或原方块本身已是深板岩（此时在其上生成的矿石也要用变种）。
        if (y < 0 || BlockUtils.isDeepslate(oldState)) {
            newState = BlockUtils.getDeepslateVariant(newState);
        }

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
        //
        // ★ 铁轨必须绕开 captureBlockSnapshots 这一层限制
        //
        // 原版条件是：
        //     !isRemote && oldBlock != newBlock
        //         && (!captureBlockSnapshots || hasTileEntity)
        // 它的用意是：在"批量方块捕获"阶段（captureBlockSnapshots == true）跳过
        // onBlockAdded，以免副作用被重复执行。对于"要靠 onBlockAdded 建立自身状态"
        // 的方块，原版本假定它们都有方块实体（hasTileEntity），因而不受影响。
        //
        // 但铁轨（BlockRailBase 及其子类，含 Railcraft 轨道）恰好是反例：
        // 它的连接形状完全由 onBlockAdded -> updateDir -> new Rail().place() 计算，
        // 而 hasTileEntity == false。于是在捕获阶段条件不成立、onBlockAdded 被跳过，
        // 铁轨形状就永远停在 getStateForPlacement 给出的玩家朝向 —— 表现即"不连接"。
        //
        // 实测（深层放置铁轨，服务端侧）：
        //     isRemote=false  changed=true
        //     captureBlockSnapshots=true   hasTileEntity=false
        //     => callOnBlockAdded=false      ← 于是 place() 从未执行
        //
        // 因此这里对铁轨单独放行：它没有方块实体，onBlockAdded 只是<b>计算并写回自己的
        // 形状</b>，不产生可被重复捕获的外部副作用，绕过捕获限制是安全的。
        // 其它方块的行为保持与原版完全一致。
        boolean isRail = newBlock instanceof net.minecraft.block.BlockRailBase;

        boolean callOnBlockAdded = !world.isRemote && oldBlock != newBlock
                && (isRail || !world.captureBlockSnapshots || newBlock.hasTileEntity());

        if (callOnBlockAdded) {
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
        // 客户端同步与邻居通知都交给原版的 markAndNotifyBlock，并沿用它的 flags 语义：
        //   9-13 : (flags & 2) != 0 → notifyBlockUpdate（同步客户端）
        //  58-62 : (flags & 1) != 0 → notifyNeighborsRespectDebug（通知邻居）
        // 这正是原版 World.setBlockState 的收尾动作，行为完全一致。
        world.markAndNotifyBlock(pos, chunk, oldState, newState, flags);
        world.notifyBlockUpdate(pos, oldState, newState, flags);

        // ★ 这里【不能】再补一次邻居通知。
        //
        // markAndNotifyBlock 已经按原版 flags 语义通知过邻居（flags & 1）。
        // 早先又无条件调了一次 depthsupdate$notifyNeighborsAtBlockLevel，
        // 等于每次写入把邻居通知两遍。对液体方块是致命的：
        //
        //   BlockStaticLiquid.neighborChanged -> updateLiquid -> setBlockState
        //     -> (本方法) 再通知两次 -> 邻居液体再次 updateLiquid -> ...
        //
        // 每次写入放大成 2 倍，很快就变成指数级级联。实测后果是看门狗崩服：
        //   ServerHangWatchdog: single server tick took 60.01 seconds
        //     at World.checkLight
        //     at ExtendedBlockWrite.setBlockState:175
        //     at BlockStaticLiquid.updateLiquid
        //     at ExtendedBlockWrite.depthsupdate$notifyNeighborsAtBlockLevel:216
        //     at ExtendedBlockWrite.setBlockState:172
        //
        // 原版语义就是"由 markAndNotifyBlock 一次性按 flags 通知"，照做即可。

        // SKY + BLOCK 两个通道一次覆盖（内部即两次 checkLightFor）。
        world.checkLight(pos);

        return oldState;
    }

    /**
     * 在<b>方块层</b>通知六个邻居，绕过 SpongeForge 的阶段包装。
     *
     * <p>为什么不直接调 {@code world.neighborChanged(...)}：SpongeForge 的
     * {@code PhaseTracker} 会在 {@code WorldServer.func_190524_a}（= {@code World.neighborChanged}）
     * 上包一层 {@code Tick{NeighborNotification}} 阶段并做栈校验。扩展区间的写入发生在
     * 生成期/事件期等嵌套场景里，重入这个包装会让它判定阶段栈被破坏，随后对每个被通知的
     * 方块打印一整段 {@code corrupted phase context providers} + 阶段栈转储
     * （实测岩浆 tick 时每秒数百行 ERROR）。
     *
     * <p>但完全不通知又是错的：实测扩展区间里"打掉支撑后告示牌/铁轨不掉落、浮空"，
     * 以及 Railcraft 轨道"放下后立即消失"（它靠 onBlockAdded/neighborChanged 里的支撑判定
     * 决定去留），都指向同一个缺口 —— 扩展区间缺少邻居更新。
     *
     * <p>折中做法：直接调用 {@code IBlockState.neighborChanged(...)}，它转发到
     * {@code Block.neighborChanged(state, world, pos, block, fromPos)}，
     * <b>不经过 {@code World.neighborChanged}</b>，因此不触碰 Sponge 的阶段栈，
     * 又能真正把"邻居变了"这件事送到方块手里。代价是不触发 Forge 的
     * {@code onNeighborNotify} 事件 —— 对扩展区间来说，这比"完全不通知"要正确得多。
     */
    private static void depthsupdate$notifyNeighborsAtBlockLevel(World world, BlockPos pos, Block oldBlock) {
        for (EnumFacing facing : EnumFacing.values()) {
            BlockPos neighborPos = pos.offset(facing);

            // 只取方块状态，不调用 world.getBlockState 之外的任何 Sponge 钩子。
            IBlockState neighborState = world.getBlockState(neighborPos);
            Block neighborBlock = neighborState.getBlock();

            // 空气不需要邻居通知（原版在此也会跳过大部分空气）。
            if (neighborBlock == Blocks.AIR) {
                continue;
            }

            try {
                // 关键：这是 IBlockState.neighborChanged → Block.neighborChanged，
                // 不是 World.neighborChanged，因此绕过 Sponge 的 PhaseTracker 包装。
                neighborState.neighborChanged(world, neighborPos, oldBlock, pos);
            } catch (Throwable ignored) {
                // 单个邻居抛异常不应影响这次方块写入本身，静默跳过
                // （与已完成的光照/客户端同步相比，这条通知是尽力而为）。
            }
        }
    }
}
