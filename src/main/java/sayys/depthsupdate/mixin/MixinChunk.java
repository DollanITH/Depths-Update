package sayys.depthsupdate.mixin;

import com.google.common.base.Predicate;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkGeneratorDebug;
import net.minecraft.entity.Entity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.ChunkPrimer;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.core.ChunkCarveMarker;

// priority = 1100：SpongeForge 的 core ChunkMixin（mixinPriority 1000）会 @Overwrite
// Chunk#setBlockState / bridge$fill 等，且注册顺序晚于本模组；若本 mixin 以默认优先级先应用，
// 随后的 @Overwrite 会把这里的 @Inject 顶掉，导致 y<0 / y>=256 的放置与存储退回 Sponge 的原始
// 逻辑（storageArrays[y >> 4]）。提高优先级让本 mixin 在 Sponge 之后应用，注入得以保留。
@Mixin(value = Chunk.class, priority = 1100)
public abstract class MixinChunk implements ChunkCarveMarker, sayys.depthsupdate.core.ExtendedLightSeeder {
    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    @org.spongepowered.asm.mixin.Mutable
    private ExtendedBlockStorage[] storageArrays;

    @Shadow
    @Final
    private int[] precipitationHeightMap;

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Shadow
    @Final
    private int[] heightMap;

    @Shadow
    private boolean dirty;

    @Shadow
    private int heightMapMinimum;

    @Shadow
    private boolean hasEntities;

    @Shadow
    @Final
    private ClassInheritanceMultiMap<Entity>[] entityLists;

    @Shadow
    public abstract int getTopFilledSegment();

    @Shadow
    protected abstract int getBlockLightOpacity(int x, int y, int z);

    @Shadow
    @Nullable
    public abstract TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType creationMode);

    @Shadow
    private void propagateSkylightOcclusion(int x, int z) {}

    @Shadow
    private void relightBlock(int x, int y, int z) {}

    @Shadow
    public abstract void generateSkylightMap();

    @Shadow
    private void updateSkylightNeighborHeight(int p_76609_1_, int p_76609_2_, int p_76609_3_, int p_76609_4_) {}

    @Shadow
    public abstract IBlockState getBlockState(int x, int y, int z);

    @Shadow
    public abstract int getLightFor(EnumSkyBlock type, @NonNull BlockPos pos);

    @Unique
    private static final ExtendedBlockStorage NULL_BLOCK_STORAGE = null;

    /**
     * ThreadLocal to pass HeightContext into @ModifyConstant during construction.
     * Required because @Inject at HEAD of &lt;init&gt; must be static (pre-super).
     */
    @Unique
    private static final ThreadLocal<HeightContext> depthsupdate$initContext = ThreadLocal.withInitial(() -> HeightContext.VANILLA);

    /**
     * Reentrant guard for setBlockState to prevent StackOverflowError.
     * When placing liquid blocks at negative Y, onBlockAdded() triggers
     * checkForMixedStateLiquids() which recursively calls setBlockState,
     * causing infinite recursion. This guard skips onBlockAdded during
     * recursive calls.
     */
    @Unique
    private static final ThreadLocal<Integer> depthsupdate$setBlockDepth = ThreadLocal.withInitial(() -> 0);

    @Unique
    private HeightContext depthsupdate$ctxCache;

    /**
     * Whether the deep (below Y=0) fill + cave carve has already been applied to this
     * chunk, for the current height/topY configuration.
     *
     * <p>Without this marker the old-world conversion in {@code MixinAnvilChunkLoader}
     * re-ran {@code OldStyleDeepCaveCarver.carve()} on <em>every</em> chunk load. The
     * carver is deterministic and idempotent for untouched terrain (it only removes
     * stone/deepslate), but any block the player has placed at a position the carver
     * wants to dig is stone to the carver and gets silently deleted. That is what made
     * deep terrain appear to "partially regenerate itself at random" between visits.
     *
     * <p>The marker is written to and read back from chunk NBT, so an already-carved
     * chunk is never re-carved again, whether it stays loaded or is reloaded in a later
     * session. It backs the {@link ChunkCarveMarker} methods; the field itself is not
     * accessed from other mixins because Mixin cannot resolve a {@code @Unique} field
     * across mixins.
     */
    @Unique
    private boolean depthsupdate$carved;

    @Override
    public boolean depthsupdate$isCarved() {
        return this.depthsupdate$carved;
    }

    @Override
    public void depthsupdate$setCarved(boolean carved) {
        this.depthsupdate$carved = carved;
    }

    /**
     * Per-chunk HeightContext cache.
     *
     * <p>HeightManager.get(World) reads a volatile field, runs an eligibility
     * check and performs a boxed HashMap lookup. The chunk accessors below call
     * it for every single block read/write/light query, i.e. hundreds of
     * thousands of times per chunk rebuild. The context for a given chunk is
     * immutable for the lifetime of the world (changed only by a config-driven
     * HeightManager.initialize(), which requires a restart because the affected
     * options are @RequiresMcRestart), so memoising it per Chunk instance is
     * safe. The dimension is re-checked so a stale cache can never leak across
     * worlds.
     */
    @Unique
    private HeightContext depthsupdate$ctx() {
        HeightContext cached = this.depthsupdate$ctxCache;

        if (cached != null && HeightManager.isExtended(this.world)) {
            return cached;
        }

        HeightContext ctx = HeightManager.get(this.world);
        this.depthsupdate$ctxCache = ctx;

        return ctx;
    }

    @Unique
    private boolean depthsupdate$isExtended() {
        return HeightManager.isExtended(this.world);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At(value = "HEAD", unsafe = true))
    private static void depthsupdate$captureWorldForInit(World worldIn, int x, int z, CallbackInfo ci) {
        depthsupdate$initContext.set(HeightManager.get(worldIn));
    }

    @ModifyConstant(method = "<init>(Lnet/minecraft/world/World;II)V", constant = @Constant(intValue = 16))
    private int depthsupdate$modifyStorageArraysSize(int original) {
        HeightContext ctx = depthsupdate$initContext.get();
        return ctx.isExtended() ? ctx.totalStorageSections() : original;
    }

    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"))
    private void depthsupdate$onChunkInitDefault(World worldIn, int x, int z, CallbackInfo ci) {
        depthsupdate$initContext.remove();
        HeightContext ctx = HeightManager.get(worldIn);
        if (ctx.isExtended()) {
            depthsupdate$ensureStorageArrayCapacity(ctx);
            for (int i = 0; i < this.heightMap.length; ++i) {
                this.heightMap[i] = ctx.minY();
            }
        }
    }

    @Unique
    private void depthsupdate$ensureStorageArrayCapacity(HeightContext ctx) {
        int want = ctx.totalStorageSections();
        if (this.storageArrays.length < want) {
            ExtendedBlockStorage[] grown = new ExtendedBlockStorage[want];
            System.arraycopy(this.storageArrays, 0, grown, 0, this.storageArrays.length);
            this.storageArrays = grown;
        }
    }

    @Inject(method = "getBlockStorageArray", at = @At("RETURN"))
    private void depthsupdate$onGetStorageArray(CallbackInfoReturnable<ExtendedBlockStorage[]> cir) {
        HeightContext ctx = depthsupdate$ctx();
        if (ctx.isExtended()) {
            depthsupdate$ensureStorageArrayCapacity(ctx);
        }
    }

    @Inject(method = "isEmptyBetween", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$isEmptyBetween(int startY, int endY, CallbackInfoReturnable<Boolean> cir) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();

        if (startY < ctx.minY()) {
            startY = ctx.minY();
        }

        if (endY >= ctx.maxY()) {
            endY = ctx.maxY() - 1;
        }

        for (int i = startY; i <= endY; i += 16) {
            int chunkY = ctx.toStorageIndex(i);

            if (chunkY >= 0 && chunkY < this.storageArrays.length) {
                ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

                if (extendedblockstorage != NULL_BLOCK_STORAGE && !extendedblockstorage.isEmpty()) {
                    cir.setReturnValue(false);

                    return;
                }
            }
        }

        cir.setReturnValue(true);
    }

    @Inject(method = "checkLight(II)Z", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$checkLight(int p_150811_1_, int p_150811_2_, CallbackInfoReturnable<Boolean> cir) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        int i = this.getTopFilledSegment();
        boolean flag = false;
        boolean flag1 = false;
        int minY = depthsupdate$ctx().minY();
        int seaLevel = this.world.getSeaLevel();
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos(
                (this.x << 4) + p_150811_1_, 0,
                (this.z << 4) + p_150811_2_);

        // ★ 循环范围与原版【逐位一致】，只是把下界从硬编码的 0 换成 minY。
        //
        // 原版条件：j > seaLevel || (j > 0 && !flag1)
        //   - 海平面以上：一直往下扫；
        //   - 海平面以下：遇到 flag1（撞上 opacity==255 的基岩层）就停。
        //
        // 这里把它推广为 j > seaLevel || (j > minY && !flag1)。
        //
        // 为什么这样是对的：原版之所以在 flag1 处收工，是因为原版世界底部
        // 就是基岩、下面什么也没有。扩展世界只是把"底部"从 0 挪到了 minY，
        // 而 y<0 的深层与 y>=256 的上层另有专门处理（见下面的第二趟与
        // depthsupdate$scanExtendedColumn）。因此本循环在 0..255 范围内的
        // 行为、包括提前退出的时机，都与原版完全相同 —— 开销也是 1.0x。
        //
        // 早先的写法 `j > minY` 丢掉了 flag1 的提前退出，导致每次都从顶部
        // 一路扫到 -64（约 1.57x 开销），这是 TPS 下降的直接原因。
        for (int j = i + 16 - 1; j > seaLevel || j > minY && !flag1; --j) {
            blockpos$mutableblockpos.setPos(blockpos$mutableblockpos.getX(), j, blockpos$mutableblockpos.getZ());
            int k = this.getBlockLightOpacity(blockpos$mutableblockpos.getX(), blockpos$mutableblockpos.getY(),
                    blockpos$mutableblockpos.getZ());

            if (k == 255 && blockpos$mutableblockpos.getY() < seaLevel) {
                flag1 = true;
            }

            if (!flag && k > 0) {
                flag = true;
            } else if (flag && k == 0 && !this.world.checkLight(blockpos$mutableblockpos)) {
                // ★ 原版在这里直接 return，但我们【不能】照做。
                //
                // 这个提前返回会跳过下面第二趟的扩展区间扫描，后果是：
                // 新生成区块里的深层发光方块（岩浆等）永远得不到方块光 ——
                // 实测症状正是"加载新区块后深层光源 light=0"。
                //
                // 新区块生成时 0..255 有大量待点亮位置，world.checkLight 很容易
                // 返回 false 而走到这里，于是深层扫描几乎总是被跳过；
                // 而 extendedLightBuilt 又会在 checkLight()V 的 RETURN 处被置位，
                // 导致这些列再也不会补算。
                //
                // 因此这里改为：先把扩展区间的光照补上，再按原版语义返回 false。
                depthsupdate$ensureExtendedColumn(p_150811_1_, p_150811_2_, i, minY, blockpos$mutableblockpos);
                cir.setReturnValue(false);

                return;
            }
        }

        // 扩展区间（y<0 与 y>=256）单独扫描一遍。
        //
        // ★ 必须**只做一次**：本方法会被 Chunk.checkLight() 对 256 列各调一次，
        //   另外 checkLightSide() 再调 4x16 次（合计约 320 次），而
        //   Chunk.onTick() **每个 tick 都会调用 checkLight()**。
        //   若每次都跑 scanExtendedColumn（对每个发光方块做一次完整 BFS），
        //   一个区块每 tick 就会有几十万次光照写入 —— 实测 MSPT 稳定在
        //   237~260ms，TPS 掉到 4。
        //
        //   所以用一个每区块的标志位把它限制为"首次完整 checkLight 时执行一次"，
        //   并在方块变化时重新置位（见 setBlockState），保证光照仍然会被修正。
        if (!depthsupdate$extendedLightBuilt) {
            depthsupdate$scanExtendedColumn(p_150811_1_, p_150811_2_, i, minY, blockpos$mutableblockpos);
        }

        cir.setReturnValue(true);
    }

    /**
     * 扩展区间的光照是否已经建立过。
     *
     * <p>由 {@code checkLight()V} 的 RETURN 注入置为 {@code true}（一次完整扫描
     * 覆盖了全部 256 列），由 {@code setBlockState} 在方块变化时置回 {@code false}。
     */
    @org.spongepowered.asm.mixin.Unique
    private boolean depthsupdate$extendedLightBuilt;

    /**
     * 补算"扩展区间的方块光"。
     *
     * <h2>为什么需要这个包装</h2>
     * {@code checkLight(x,z)} 的第一趟在 0..255 范围内可能因为
     * {@code !world.checkLight(pos)} 而<b>提前 return</b> —— 原版语义如此。
     * 但那段提前返回会跳过第二趟的扩展区间扫描，导致新生成区块里的
     * 深层发光方块（岩浆等）永远拿不到方块光，实测症状为"加载新区块后深层 light=0"。
     *
     * <p>因此把扩展区间的扫描抽成这个方法，在提前返回【之前】也调用一次，
     * 保证无论第一趟是否提前结束，深层光照都会被建立。
     *
     * <p>仍然受 {@link #depthsupdate$extendedLightBuilt} 限制：同一个区块只做一次
     * 完整扫描，避免每 tick 重复 BFS（那曾导致 MSPT 237~260ms、TPS 掉到 4）。
     */
    @org.spongepowered.asm.mixin.Unique
    private void depthsupdate$ensureExtendedColumn(int x, int z, int topFilled, int minY,
            BlockPos.MutableBlockPos cursor) {
        if (depthsupdate$extendedLightBuilt) {
            return;
        }

        depthsupdate$scanExtendedColumn(x, z, topFilled, minY, cursor);
    }

    /**
     * 每次完整 {@code checkLight()V} 结束时，标记"扩展区间已建立"。
     *
     * <h2>★ 为什么必须检查 {@link #depthsupdate$extendedEmitterSections()} 是否非空</h2>
     * 这个标志的作用是"别每 tick 重做一遍代价高昂的播种 BFS"，
     * 因此只有在<b>确实存在可播种的发光段、并且这一轮真的扫过</b>时才该置位。
     *
     * <p>此前是<b>无条件</b>置 {@code true}，于是出现这样的顺序：
     * <pre>
     *   1. 区块刚加载 / 深层段尚未填充 -> extendedEmitterSections() 返回【空】
     *      -> scanExtendedColumn 立即 return，一列都没播种
     *   2. checkLight()V 返回 -> 无条件置 extendedLightBuilt = true
     *   3. 之后深层段才被填入岩浆 -> setBlockState 只在【扩展区间变化】时重置该标志，
     *      而"段被填充"往往发生在生成阶段、并不经过该路径
     *   4. 结果：这一片的深层光照【永远】是 0 —— 正是实测的
     *      "岩浆旁边一片漆黑、dark air 散落在多个区块"
     * </pre>
     *
     * <p>加入了空判定后：只要深层段还没准备好，就不置位，
     * 后续的 {@code checkLight()} 会继续尝试播种，直到真正扫过为止。
     * 这不会重新引入每 tick 重算 —— 因为一旦段存在且扫过一次，标志就会置位。
     */
    @Inject(method = "checkLight()V", at = @At("RETURN"))
    private void depthsupdate$markExtendedLightBuilt(CallbackInfo ci) {
        // 没有任何可播种的发光段时不置位：留待段填充后的下一次 checkLight 再试。
        if (depthsupdate$extendedEmitterSections().length == 0) {
            return;
        }

        depthsupdate$extendedLightBuilt = true;
    }

    /**
     * 扫描本列的深层发光方块并播种。
     *
     * @return 本列实际播种的发光方块数量。调用方用它判断"这次是否真的做了事" ——
     *         深层填充之前段里还没有岩浆，返回 0；填充之后才会返回 &gt; 0。
     *         这一点对 {@link #depthsupdate$seedExtendedLight()} 的幂等标志至关重要：
     *         若只看"段是否存在"就置位，会在深层填充前误判为"已播种"。
     */
    private int depthsupdate$scanExtendedColumn(int localX, int localZ, int topFilled, int minY,
            BlockPos.MutableBlockPos cursor) {
        ExtendedBlockStorage[] candidates = depthsupdate$extendedEmitterSections();

        if (candidates.length == 0) {
            return 0;
        }

        int seeded = 0;

        HeightContext ctx = depthsupdate$ctx();
        int maxY = ctx.maxY();

        for (ExtendedBlockStorage sec : candidates) {
            int secBase = sec.getYLocation();

            for (int ly = 0; ly < 16; ++ly) {
                int y = secBase + ly;

                if (y < minY || y >= maxY) {
                    continue;
                }

                // 只处理扩展区间；0..255 已由上面那段原版逻辑负责。
                if (y >= 0 && y < 256) {
                    continue;
                }

                cursor.setPos(cursor.getX(), y, cursor.getZ());

                if (sec.get(localX, ly, localZ).getLightValue(this.world, cursor) <= 0) {
                    continue;
                }

                // ★ 不调用 this.world.checkLight(cursor)。
                //
                // 原版的 checkLight → checkLightFor → BFS 内部会调用
                // World.getLightFromNeighborsFor，而该方法把 y<0 的坐标
                // **强行替换成 (x, 0, z)**（原版为"负 Y 不存在"写的兼容逻辑）：
                //     pos.getY(); ifge; new BlockPos(x, 0, z)
                // 于是 BFS 在深层每一步都去查 y=0 那格（实心石头，光=0），
                // 算出的目标光恒为 0，**光永远传不出去**（实测：岩浆 15、空气全 0）。
                //
                // 该方法的 SRG 名无法可靠注入（实测 func_175705_a 在运行期
                // 匹配失败会让服务器无法启动），所以这里改为**自己传播**，
                // 完全不依赖原版那套带高度假设的 BFS。
                int emission = sec.get(localX, ly, localZ).getLightValue(this.world, cursor);

                depthsupdate$propagateBlockLight(cursor.getX(), y, cursor.getZ(), emission);

                seeded++;

                // 让客户端重算这一小片的光照。
                //
                // 注意粒度：这里对**每个发光方块**通知一次（几十~几百次/区块），
                // 而不是对 BFS 写过的每一格通知（那会是几万次，导致单 tick 超时崩服）。
                // 这是"批量直接写数组 + 少量粗粒度通知"的折中，
                // 与原版 generateSkylightMap 最后统一同步的思路一致。
                this.world.notifyLightSet(cursor);
            }
        }

        return seeded;
    }




    private void depthsupdate$propagateBlockLight(int startX, int startY, int startZ, int emission) {
        HeightContext ctx = depthsupdate$ctx();
        int minY = ctx.minY();
        int maxY = ctx.maxY();

        if (emission <= 0 || startY < minY || startY >= maxY) {
            return;
        }

        // 起始格先置为自身发光值（原版语义：光源格就是它的发光值）。
        // 直接写自身光照数组，不走 World.setLightFor —— 后者会触发
        // notifyLightSet -> markBlockRangeForRenderUpdate，批量写入会造成
        // 看门狗判定单 tick 超时而崩服。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.setPos(startX, startY, startZ);
        depthsupdate$writeLightRaw(startX, startY, startZ, emission);


        // 简单队列：交替存 x,y,z,light 四个 int。
        // 注意 capacity 必须与数组长度一致，且按"每项 4 个 int"计算，
        // 否则扩容判断会用错误的单位（早先写成 QUEUE_INITIAL * 3 配 capacity=QUEUE_INITIAL，
        // 两者不一致会导致扩容时机错乱）。
        int capacity = QUEUE_INITIAL * 4;
        int[] queue = new int[capacity];
        int head = 0;
        int tail = 0;

        queue[tail++] = startX;
        queue[tail++] = startY;
        queue[tail++] = startZ;
        queue[tail++] = emission;

        final int[][] OFFSETS = {
                { 1, 0, 0 }, { -1, 0, 0 },
                { 0, 1, 0 }, { 0, -1, 0 },
                { 0, 0, 1 }, { 0, 0, -1 }
        };

        while (head < tail) {
            int cx = queue[head++];
            int cy = queue[head++];
            int cz = queue[head++];
            int light = queue[head++];

            // ★ 光值从队列取，【不要】回读 world.getLightFor。
            //
            // SpongeForge 的 WorldServerMixin 用具体方法体覆盖了
            // World.getLightFor，使本模组在该方法上的 @Inject 被丢弃，
            // 且其内部会把坐标改写成 0/255。结果是 y<0 时回读得到 0，
            // 而旧实现正好用它决定 BFS 是否继续：
            //     int light = world.getLightFor(BLOCK, pos);
            //     if (light <= 1) continue;      // 深层永远命中 -> BFS 起点即退出
            // 于是"一格都没写"。改为随队列携带光值后，推进不再依赖该读取。
            if (light <= 1) {
                continue;
            }

            for (int[] off : OFFSETS) {
                int nx = cx + off[0];
                int ny = cy + off[1];
                int nz = cz + off[2];

                // 严格限制在配置的高度范围内。
                if (ny < minY || ny >= maxY) {
                    continue;
                }

                pos.setPos(nx, ny, nz);
                IBlockState neighbor = this.world.getBlockState(pos);
                int opacity = neighbor.getLightOpacity(this.world, pos);

                // ★ 恢复为只用 world.getBlockState，不再拿 Chunk 通路去"纠正"它。
                //
                // 这里曾经是这样写的：
                //     int chunkOpacity = this.getBlockLightOpacity(nx, ny, nz);
                //     if (chunkOpacity != opacity) opacity = chunkOpacity;
                // 依据是一次诊断：同一格 (935,-54,-912) 上
                // "world 通路报 deepslate/opacity=255、chunk 通路报空气/opacity=0"。
                //
                // 但那个对比【本身是无效的】：Chunk.getBlockLightOpacity(x,y,z) 是
                // Chunk 自己的方法，内部用 (x & 15, y & 15, z & 15) 索引【本区块】的
                // 存储段。对跨区块坐标 nx（例如 BFS 在区块 14 上跑到 nx=223，那属于
                // 区块 13），223 & 15 == 15 会把读取落到【本区块的第 15 列】——
                // 读的根本不是同一格，两者不同是必然的，不能作为"world 通路错误"的证据。
                //
                // 而 world.getBlockState 会正确地定位到目标区块，是本 BFS 唯一正确的
                // 跨区块读取方式。实测症状也支持这一点：把读取换成 Chunk 通路之后，
                // 出现了成片的"区块最西一列（localX == 0）恒为 0"——
                // 例如 x=224（14*16）那一列长期是 dark air，因为它的邻居 x=223
                // 属于上一个区块，正好命中上面那个越界式的错误读取。
                if (opacity >= 255) {
                    continue;
                }

                int target = light - 1 - opacity;

                if (target <= 0) {
                    continue;
                }

                // ★ 直接写光照数组，不用 World.setLightFor。
                //
                // World.setLightFor 会触发 notifyLightSet ->
                // markBlockRangeForRenderUpdate（每次 3x3x3 格）。本 BFS 会写
                // 成千上万格，几万次渲染标记会让单 tick 超过看门狗阈值而崩服
                // （实测：ServerHangWatchdog "single server tick took 60.01 seconds"）。
                // 原版批量光照（generateSkylightMap）同样直接写数组、最后统一同步。
                //
                // 写入前先比较，避免重复入队导致队列膨胀。
                int existing = depthsupdate$readLightRaw(nx, ny, nz);

                if (existing >= target) {
                    continue;
                }

                depthsupdate$writeLightRaw(nx, ny, nz, target);

                // 扩容：容量不足时翻倍。
                if (tail + 4 > capacity) {
                    int newCapacity = capacity * 2;

                    if (newCapacity > QUEUE_MAX) {
                        // 防御性上限：极端情况下放弃继续扩展，避免卡死主线程。
                        continue;
                    }

                    int[] bigger = new int[newCapacity];
                    System.arraycopy(queue, 0, bigger, 0, tail);
                    queue = bigger;
                    capacity = newCapacity;
                }

                queue[tail++] = nx;
                queue[tail++] = ny;
                queue[tail++] = nz;
                queue[tail++] = target;
            }
        }
    }

    /** 自有 BFS 的队列初始容量（以"格"为单位）。 */
    @org.spongepowered.asm.mixin.Unique
    private static final int QUEUE_INITIAL = 4096;


    /**
     * 按世界坐标写方块光，直接落到所属区块的 {@link ExtendedBlockStorage}。
     *
     * <p>刻意绕过 {@code World.setLightFor}：它会触发
     * {@code notifyLightSet -> markBlockRangeForRenderUpdate}，批量写入会造成
     * 单 tick 超时（看门狗崩服）。原版批量光照也是直接写数组。
     *
     * <h2>★ 目标段不存在时必须【补建】，不能静默丢弃</h2>
     * 本方法会被 BFS 用来写<b>跨区块</b>的邻居格。若那个区块的深层段此时还不存在
     * （尚未填充/尚未创建），旧实现是 {@code if (section != null)} —— 光被直接丢掉，
     * 而调用方毫不知情。
     *
     * <p>后果（实测：{@code /depthsupdate scan} 报出 193 处 {@code dark air}，
     * 且分布散落在多个区块）：
     * <pre>
     *   区块 A 的岩浆 BFS 扩散到区块 B
     *     -> 写 B 的深层格时 B 的段还是 null -> 光被丢弃
     *   之后 B 的段被建好，但 A 的 BFS 不会再跑（A 的 extendedLightBuilt 已置位）
     *   而 B 自己没有岩浆 -> B 的播种也不会写这些格
     *   => 这些格【永远】是 0，形成"岩浆旁边一片漆黑"
     * </pre>
     *
     * <p>因此这里在目标段缺失时就地创建一个（与原版 {@code Chunk.setLightFor}
     * 在段为 null 时新建段的做法一致），保证传播过来的光一定会被记录。
     * 只有当坐标越出该维度的合法范围时才真正放弃。
     */
    @org.spongepowered.asm.mixin.Unique
    private void depthsupdate$writeLightRaw(int x, int y, int z, int value) {
        HeightContext ctx = depthsupdate$ctx();
        int sectionIndex = ctx.toStorageIndex(y);

        if (sectionIndex < 0) {
            return;
        }

        // 定位真正要写入的那个区块：本区块直接用自己的数组，
        // 跨区块必须取【邻居区块】并往它的数组里写（否则会写错地方）。
        ExtendedBlockStorage[] target;

        if ((x >> 4) == this.x && (z >> 4) == this.z) {
            target = this.storageArrays;
        } else {
            // 用 getLoadedChunk：本方法会在区块读取/转换过程中被调用，
            // getChunk 可能触发递归加载。
            Chunk other = this.world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);

            if (other == null) {
                // 邻居区块尚未加载，此刻确实无处可写；等它加载后会自行播种。
                return;
            }

            target = other.getBlockStorageArray();
        }

        if (sectionIndex >= target.length) {
            return;
        }

        ExtendedBlockStorage section = target[sectionIndex];

        if (section == null || section == NULL_BLOCK_STORAGE) {
            // ★ 目标段不存在时就地补建，而不是把光丢掉。
            section = new ExtendedBlockStorage(y >> 4 << 4, this.world.provider.hasSkyLight());
            target[sectionIndex] = section;
        }

        section.setBlockLight(x & 15, y & 15, z & 15, value);
    }

    /** 按世界坐标读方块光；没有对应段时返回 0。 */
    @org.spongepowered.asm.mixin.Unique
    private int depthsupdate$readLightRaw(int x, int y, int z) {
        ExtendedBlockStorage section = depthsupdate$sectionAt(x, y, z);

        return section == null ? 0 : section.getBlockLight(x & 15, y & 15, z & 15);
    }

    /**
     * 找到世界坐标 (x,y,z) 所属的段；跨区块时通过 {@code World.getChunk} 取邻居段。
     *
     * <p>用逐段比较 {@code getYLocation()} 定位，不依赖自定的高度映射，
     * 因此对"哪一段装哪个 Y"的格式变化是稳健的。
     */
    @org.spongepowered.asm.mixin.Unique
    private ExtendedBlockStorage depthsupdate$sectionAt(int x, int y, int z) {
        // 本区块内（x/z 的区块坐标与本区块一致）直接查自己的数组，避免一次 getChunk。
        boolean sameChunk = (x >> 4) == this.x && (z >> 4) == this.z;

        if (sameChunk) {
            return depthsupdate$sectionIn(this.storageArrays, y);
        }

        // 用 getLoadedChunk 而非 getChunk：后者在缺失时会触发区块加载，
        // 而本方法会在区块读取/转换过程中被调用，直接 getChunk 有递归加载风险。
        Chunk other = this.world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);

        return other == null ? null : depthsupdate$sectionIn(other.getBlockStorageArray(), y);
    }

    /** 在一组段里找包含世界 Y 的段。 */
    @org.spongepowered.asm.mixin.Unique
    private static ExtendedBlockStorage depthsupdate$sectionIn(ExtendedBlockStorage[] arrays, int y) {
        if (arrays == null) {
            return null;
        }

        for (ExtendedBlockStorage section : arrays) {
            if (section == null || section == NULL_BLOCK_STORAGE) {
                continue;
            }

            int base = section.getYLocation();

            if (y >= base && y < base + 16) {
                return section;
            }
        }

        return null;
    }

    /** 自有 BFS 的队列容量上限（以"格"为单位），防止病态膨胀。 */
    @org.spongepowered.asm.mixin.Unique
    private static final int QUEUE_MAX = 1 << 20;

    /** 扩展区间内"非空且可能含发光方块"的 segment 缓存。 */
    @org.spongepowered.asm.mixin.Unique
    private ExtendedBlockStorage[] depthsupdate$emitterSectionCache;

    /** 缓存对应的 storageArrays 版本号，用于判断是否需要重算。 */
    @org.spongepowered.asm.mixin.Unique
    private int depthsupdate$emitterSectionStamp = -1;

    /**
     * 取出"落在扩展区间、且非空"的 segment 列表（带缓存）。
     *
     * <p>缓存失效判据：{@link #storageArrays} 的引用变了，或任一段的引用变了。
     * 用一个单调递增的 stamp 表达：每次调用时逐段比较引用身份，
     * 一旦发现变化就重算并记录。逐段比较本身是 29 次引用比较，
     * 比 29 次 {@code isEmpty()}（含数组扫描）便宜得多。
     */
    @org.spongepowered.asm.mixin.Unique
    private ExtendedBlockStorage[] depthsupdate$extendedEmitterSections() {
        int stamp = 1;

        // ★ 版本号必须同时反映「段的身份」和「段是否为空」。
        //
        // 只取 identityHashCode 是不够的：深层填充（以及其它直接往
        // ExtendedBlockStorage 写方块的路径）不会替换段对象，只是往里面填内容。
        // 那样身份不变 -> stamp 不变 -> 缓存照旧命中 —— 于是：
        //   1. 段最初是空的 -> 算出【空候选列表】并缓存；
        //   2. 深层填充把岩浆写进【同一个】段对象；
        //   3. 缓存仍然返回空 -> scanExtendedColumn 直接 return
        //      -> 单格岩浆这类孤立光源【永远不被播种】，连自身都不亮。
        // 实测症状正是"孤立的单格岩浆不发光，而成片的岩浆有光晕"
        // （成片时总有别的列先被扫到，靠 BFS 把光带过去）。
        //
        // 把 isEmpty() 纳入版本号后，段从"空"变成"非空"会立即改变 stamp，
        // 缓存随之失效并重算 —— 不再依赖调用方记得手动清缓存。
        for (int si = 0; si < this.storageArrays.length; ++si) {
            ExtendedBlockStorage section = this.storageArrays[si];
            stamp = stamp * 31 + System.identityHashCode(section);

            if (section != null && section != NULL_BLOCK_STORAGE) {
                stamp = stamp * 31 + (section.isEmpty() ? 1 : 2);
            }
        }

        if (stamp == depthsupdate$emitterSectionStamp && depthsupdate$emitterSectionCache != null) {
            return depthsupdate$emitterSectionCache;
        }

        HeightContext ctx = depthsupdate$ctx();
        int minY = ctx.minY();
        int maxY = ctx.maxY();
        java.util.List<ExtendedBlockStorage> out = new java.util.ArrayList<ExtendedBlockStorage>(4);

        for (int si = 0; si < this.storageArrays.length; ++si) {
            ExtendedBlockStorage sec = this.storageArrays[si];

            if (sec == null || sec == NULL_BLOCK_STORAGE || sec.isEmpty()) {
                continue;
            }

            int secBase = sec.getYLocation();

            // 只要有一段落在扩展区间就纳入候选。
            if (secBase >= maxY || secBase + 15 < minY || (secBase >= 0 && secBase + 15 < 256)) {
                continue;
            }

            out.add(sec);
        }

        ExtendedBlockStorage[] result = out.toArray(new ExtendedBlockStorage[0]);
        depthsupdate$emitterSectionCache = result;
        depthsupdate$emitterSectionStamp = stamp;

        return result;
    }

    /**
     * Chunk.getBlockState(int,int,int) 的扩展高度接管。
     */
    /**
     * 标记"正在执行区块加载回调"阶段 —— 供 {@code Entity.setDead} 守卫使用。
     *
     * <h2>为什么是 {@code onLoad}（反汇编 + 调用栈确认）</h2>
     * 实体在重启后被删除的调用栈：
     * <pre>
     *   Entity.setDead()
     *     &lt;- WorldServer.handler$bbi000$sponge$spongeLoadEntities()
     *     &lt;- WorldServer.func_175650_b()          // loadEntities
     *     &lt;- Chunk.func_76631_c()                 &lt;- ★ onLoad，就在这里
     *     &lt;- ChunkIOProvider.syncCallback()
     *     &lt;- ChunkProviderServer.loadChunk()
     * </pre>
     *
     * <p>注意栈里<b>没有</b> {@code readChunkFromNBT} —— {@code syncCallback} 是在区块
     * 已经反序列化完成之后才执行的，而 {@code loadEntities} 由 {@code onLoad} 触发。
     * 因此把标志挂在 {@code readChunkFromNBT} 上无效（实测 loading 恒为 false），
     * 必须挂在 {@code onLoad} 这个真正触发实体加载回调的方法上。
     */
    @Inject(method = "onLoad", at = @At("HEAD"))
    public void depthsupdate$beginOnLoad(CallbackInfo ci) {
        sayys.depthsupdate.core.LoadEntityGuard.beginLoad();
    }

    @Inject(method = "onLoad", at = @At("RETURN"))
    public void depthsupdate$endOnLoad(CallbackInfo ci) {
        // 先激活"已取消删除但未加入世界"的实体，再清加载标志。
        //
        // 顺序很重要：Sponge 的实体过滤在 onLoad 内部跑完，此时被我们保住、
        // 却因 Sponge 的 `goto 30` 而未进入 world.loadedEntityList 的实体
        // 正需要在这里补上注册，否则它们会成为"幽灵实体"
        // （可见性问题：有存档、有占位，但看不见、/kill 打不到）。
        sayys.depthsupdate.core.LoadEntityGuard.activatePending();

        sayys.depthsupdate.core.LoadEntityGuard.endLoad();

        // ★ 区块加载完成 -> 对【全部 256 列】补做深层光照播种。
        depthsupdate$seedExtendedLight();
    }

    /**
     * 对<b>整个区块的全部 256 列</b>补做深层（y&lt;0 / y&gt;=256）光照播种。
     *
     * <h2>★ 为什么必须在这里做，而不能只依赖 checkLight(II)</h2>
     * 此前深层播种只挂在 {@code checkLight(II)} 里（见
     * {@link #depthsupdate$ensureExtendedColumn}）。当时的假设是
     * "该方法会被 {@code Chunk.checkLight()} 对 256 列各调一次"——
     * 这个假设是<b>错的</b>。反汇编原版 {@code Chunk.checkLight()V} 后可见：
     * <pre>
     *   44-73:   if (world.isAreaLoaded(...))
     *   135-193:   for (EnumFacing facing : HORIZONTALS)
     *                chunk = world.getChunk(pos.offset(facing, 16|1))
     *                chunk.checkLightSide(facing.getOpposite())   // ★ 调【邻居区块】的
     *   196:       setSkylightUpdated()
     * </pre>
     * 即 {@code checkLight()V} <b>根本不调用自己的 {@code checkLight(II)}</b>；
     * 它只让四个邻居区块各自执行 {@code checkLightSide()}，而后者只处理
     * <b>朝向那一侧的 16 个边界列</b>。
     *
     * <p>结论：一个区块里只有<b>边界列</b>会走到 {@code checkLight(II)}，
     * 内部 240 列的深层发光方块<b>从来不会被播种</b>；区块内部只能依赖 BFS
     * 从边界扩散，而光每格衰减 1 级（岩浆最多 15 级），远超不了十几格。
     *
     * <p>实测症状与此完全吻合：{@code /depthsupdate scan} 报出 920 处
     * {@code dark air}（紧邻岩浆却是 0），且<b>散布在多个区块</b>；
     * 而玩家附近少数格子是 15 —— 那些正是边界 BFS 恰好扩散到的格。
     *
     * <h2>为什么放在 onLoad</h2>
     * <ul>
     *   <li>每个区块加载时<b>只跑一次</b>，不引入每 tick 开销；</li>
     *   <li>此时区块数据（含深层段）已经反序列化完毕，可以安全读取；</li>
     *   <li>覆盖全部 256 列，不依赖任何"边界列"假设。</li>
     * </ul>
     *
     * <p>代价与 {@code scanExtendedColumn} 一致：只对"含发光方块的深层段"
     * 做一次 BFS，段为空时立即返回。因此对绝大多数区块是零成本的。
     */
    /**
     * 让"深层发光段"候选缓存失效（见 {@link ExtendedLightSeeder}）。
     *
     * <p>深层填充直接写 {@code ExtendedBlockStorage}，不经过 {@code setBlockState}，
     * 因此必须由填充方显式调用本方法，否则填充前缓存的"空候选列表"会被一直复用 ——
     * 单格岩浆这类孤立光源就永远不会被播种（连自身都不亮）。
     */
    @Override
    public void depthsupdate$invalidateEmitterCache() {
        depthsupdate$emitterSectionStamp = -1;
        depthsupdate$emitterSectionCache = null;
    }

    @Override
    public void depthsupdate$seedExtendedLight() {
        if (!depthsupdate$isExtended()) {
            return;
        }

        // 幂等：本次加载已经播过种就不要再做一遍。
        if (depthsupdate$extendedLightSeeded) {
            return;
        }

        // 段为空则无事可做（绝大多数区块走这条路径）——
        // 注意此时【不要】置 seeded，留待深层填充完成后的再次调用。
        if (depthsupdate$extendedEmitterSections().length == 0) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(this.x << 4, 0, this.z << 4);

        int totalSeeded = 0;

        for (int localX = 0; localX < 16; ++localX) {
            for (int localZ = 0; localZ < 16; ++localZ) {
                cursor.setPos((this.x << 4) + localX, 0, (this.z << 4) + localZ);

                totalSeeded += depthsupdate$scanExtendedColumn(localX, localZ,
                        this.getTopFilledSegment(), ctx.minY(), cursor);
            }
        }

        // ★ 只有【真的播种到光源】才算完成。
        //
        // 若仅凭"段存在"就置位，会在新生成区块上误判：那时深层段可能已由 primer
        // 建好（装着石头/空气），但我们的深层填充（岩浆等）还没跑 —— 于是本次
        // 扫描一无所获却把 seeded 置为 true，深层填充完成后的那次调用就被跳过，
        // 岩浆的光永远不会被播种。这正是本次要修的问题本身。
        if (totalSeeded > 0) {
            depthsupdate$extendedLightBuilt = true;
            depthsupdate$extendedLightSeeded = true;
        }
    }

    /**
     * 本区块的深层光照是否已经完成过一次"整区块播种"。
     *
     * <p>与 {@link #depthsupdate$extendedLightBuilt} 的区别：后者表示
     * "边界列的 checkLight 流程已建立"，会在方块变化时被重置；
     * 本标志只由 {@link #depthsupdate$seedExtendedLight()} 设置，
     * 用于让 onLoad 与深层填充后的两次调用互不重复。
     */
    @org.spongepowered.asm.mixin.Unique
    private boolean depthsupdate$extendedLightSeeded;

    @Inject(method = "getBlockState(III)Lnet/minecraft/block/state/IBlockState;", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$getBlockState(final int x, final int y, final int z, CallbackInfoReturnable<IBlockState> cir) {

        // Sponge 的 bridge$isInvalidYPosition 现在被放行到扩展范围，那么"该维度并未扩展"的世界
        // 也必须在此兜底：越界直接返回空气，否则会落到原版 Chunk.getBlockState 的
        // storageArrays[y >> 4]，y<0 时是负下标。
        if (y < depthsupdate$ctx().minY() || y >= depthsupdate$ctx().maxY()) {
            cir.setReturnValue(Blocks.AIR.getDefaultState());

            return;
        }

        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        // 磁盘加载（readChunkFromNBT）会用一个新建的定长数组替换掉构造器里扩容过的 storageArrays，
        // 而 ensureStorageArrayCapacity 原本只挂在构造器与 getBlockStorageArray() 上，
        // 于是 get/setBlockState 直接读字段时数组仍是 16 长：y<0 的 toStorageIndex 得到 25 越界，
        // get 返回空气（实体坠落）、set 返回 null（/setblock 报 commands.setblock.failed）。
        // 在任何读写之前就地补齐容量，保证扩展 section 可寻址。
        depthsupdate$ensureStorageArrayCapacity(ctx);

        if (this.world.getWorldType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            IBlockState iblockstate = null;

            if (y == 60) {
                iblockstate = Blocks.BARRIER.getDefaultState();
            }

            if (y == 70) {
                iblockstate = ChunkGeneratorDebug.getBlockStateFor(x, z);
            }

            cir.setReturnValue(iblockstate == null ? Blocks.AIR.getDefaultState() : iblockstate);
        } else {
            int chunkY = ctx.toStorageIndex(y);

            if (y >= ctx.minY() && chunkY >= 0 && chunkY < this.storageArrays.length) {
                ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

                if (extendedblockstorage != NULL_BLOCK_STORAGE) {
                    cir.setReturnValue(extendedblockstorage.get(x & 15, y & 15, z & 15));

                    return;
                }
            }

            cir.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$setBlockState(@NonNull BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        if (!depthsupdate$isExtended()) {
            // 同上：非扩展维度若收到越界坐标，返回 null（等价于"无变化"）而不是让原版
            // Chunk.setBlockState 去取 storageArrays[y >> 4] 的负下标。
            int outY = pos.getY();
            if (outY < depthsupdate$ctx().minY() || outY >= depthsupdate$ctx().maxY()) {
                cir.setReturnValue(null);
            }

            return;
        }

        // ★ 只接管扩展区间（y<0 或 y>=256）。
        //
        // 早先这里【没有】这个守卫：只要维度属于"扩展世界"，Chunk.setBlockState
        // 就会被我们自己的复刻实现**整体替换**，连 0..255 也一样走我们的代码。
        //
        // 这是极其侵入的接管 —— 原版所有方块写入语义（铁轨形状判定、红石更新、
        // 流体、方块实体、掉落、邻居通知…）都被换成复刻版，任何细微差异都会在
        // 原生平台上表现为"奇葩效果"。
        //
        // 实测症状：客户端（内置服务端）里继承 BlockRailBase 的轨道方块不再自动连接，
        // 而 SpongeForge 服务端正常 —— 正是因为两边进入 Chunk.setBlockState 的路径不同，
        // 而我们在这条路径上对 0..255 做了不该做的接管。
        //
        // 现在 0..255 原样交回原版 Chunk.setBlockState，只有扩展区间才由我们处理。
        if (pos.getY() >= 0 && pos.getY() < 256) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        // 同上：磁盘加载后的数组可能只有 16 长，写入前先补齐，否则 chunkY=25 越界会直接返回 null。
        depthsupdate$ensureStorageArrayCapacity(ctx);

        int i = pos.getX() & 15;
        int j = pos.getY();
        int k = pos.getZ() & 15;

        // ★ 只有【扩展区间】的方块变化才需要重扫扩展光照。
        //
        // 早先这里是无条件置位，导致 TPS 灾难（实测 overworld MSPT 125~155ms，
        // 而其它维度 0.02~0.06ms）：
        //   Chunk.onTick 每个 tick 都会做随机刻（草蔓延、作物生长、树叶腐烂…），
        //   这些都会调用 setBlockState -> 把标志置 false ->
        //   同一 tick 稍后的 checkLight() 就认为"需要重扫" ->
        //   对整区块每个发光方块跑一次完整 BFS（每区块每 tick 数十万次写入）。
        //
        // 地表随机刻发生在 y=0..255，与扩展区间无关，因此不应该触发重扫。
        if (j < 0 || j >= 256) {
            depthsupdate$extendedLightBuilt = false;

            // ★ 同时让"扩展区间发光段"的缓存失效。
            //
            // 那个缓存的失效判据原本只看 storageArrays 的【对象身份】
            // （stamp = stamp * 31 + identityHashCode(storageArrays[si])），
            // 而段内【内容】变化不会改变对象身份 —— 于是它一旦算出结果就永不失效。
            //
            // 后果（实测症状："光照割裂"，相邻两格一格 15、一格 0）：
            //   区块刚加载时深层段可能还是 NULL_BLOCK_STORAGE 或 isEmpty()，
            //   此时算出的候选列表是【空的】并被缓存；之后岩浆/方块填入该段，
            //   身份未变 -> 缓存照样命中 -> scanExtendedColumn 直接 return
            //   -> propagateBlockLight 一次都不执行 -> 这一片的光照永远是 0。
            //   而首次调用时恰巧段已填充的区块则完全正常。
            //   这正是"概率性"与"割裂"的来源。
            //
            // 缓存本是为了避免 checkLight(II) 每次（每区块约 320 次）都遍历
            // 全部 29 个段。这里在方块真正变化时置 -1，语义与 extendedLightBuilt
            // 一致：只在扩展区间变化时重算，地表随机刻不受影响。
            depthsupdate$emitterSectionStamp = -1;
        }

        int l = k << 4 | i;

        if (j >= this.precipitationHeightMap[l] - 1) {
            this.precipitationHeightMap[l] = -999;
        }

        int i1 = this.heightMap[l];
        IBlockState iblockstate = this.getBlockState(pos.getX(), pos.getY(), pos.getZ());

        if (j < 0 || sayys.depthsupdate.util.BlockUtils.isDeepslate(iblockstate)) {
            state = sayys.depthsupdate.util.BlockUtils.getDeepslateVariant(state);
        }

        if (iblockstate == state) {
            cir.setReturnValue(null);
        } else {
            Block block = state.getBlock();
            Block block1 = iblockstate.getBlock();
            int k1 = iblockstate.getLightOpacity(this.world, pos);
            int chunkY = ctx.toStorageIndex(j);

            if (chunkY < 0 || chunkY >= this.storageArrays.length) {
                cir.setReturnValue(null);

                return;
            }

            ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];
            boolean flag = false;

            if (extendedblockstorage == NULL_BLOCK_STORAGE) {
                if (block == Blocks.AIR) {
                    cir.setReturnValue(null);

                    return;
                }
                extendedblockstorage = new ExtendedBlockStorage(j >> 4 << 4, this.world.provider.hasSkyLight());
                this.storageArrays[chunkY] = extendedblockstorage;
                flag = j >= i1;
            }

            extendedblockstorage.set(i, j & 15, k, state);

            if (!this.world.isRemote) {
                if (block1 != block)
                    block1.breakBlock(this.world, pos, iblockstate);

                TileEntity te = this.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

                if (te != null && te.shouldRefresh(this.world, pos, iblockstate, state))
                    this.world.removeTileEntity(pos);
            } else if (block1.hasTileEntity(iblockstate)) {
                TileEntity te = this.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

                if (te != null && te.shouldRefresh(this.world, pos, iblockstate, state))
                    this.world.removeTileEntity(pos);
            }

            if (extendedblockstorage.get(i, j & 15, k).getBlock() != block) {
                cir.setReturnValue(null);
            } else {
                if (flag) {
                    this.generateSkylightMap();
                } else {
                    int j1 = state.getLightOpacity(this.world, pos);

                    if (j1 > 0) {
                        if (j >= i1) {
                            this.relightBlock(i, j + 1, k);
                        }
                    } else if (j == i1 - 1) {
                        this.relightBlock(i, j, k);
                    }

                    if (j1 != k1 && (j1 < k1 || this.getLightFor(EnumSkyBlock.SKY, pos) > 0
                            || this.getLightFor(EnumSkyBlock.BLOCK, pos) > 0)) {
                        this.propagateSkylightOcclusion(i, k);
                    }
                }

                if (!this.world.isRemote && block1 != block
                        && (!this.world.captureBlockSnapshots || block.hasTileEntity(state))) {
                    int depth = depthsupdate$setBlockDepth.get();
                    if (depth < 16) {
                        depthsupdate$setBlockDepth.set(depth + 1);
                        try {
                            block.onBlockAdded(this.world, pos, state);
                        } finally {
                            depthsupdate$setBlockDepth.set(depth);
                        }
                    }
                }

                if (block.hasTileEntity(state)) {
                    TileEntity tileentity1 = this.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);

                    if (tileentity1 == null) {
                        tileentity1 = block.createTileEntity(this.world, state);
                        this.world.setTileEntity(pos, tileentity1);
                    }

                    if (tileentity1 != null) {
                        tileentity1.updateContainingBlockInfo();
                    }
                }

                this.dirty = true;

                cir.setReturnValue(iblockstate);
            }
        }
    }

    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$getLightFor(EnumSkyBlock type, @NonNull BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        int i = pos.getX() & 15;
        int j = pos.getY();
        int k = pos.getZ() & 15;
        int chunkY = ctx.toStorageIndex(j);

        if (chunkY < 0 || chunkY >= this.storageArrays.length) {
            cir.setReturnValue(type.defaultLightValue);

            return;
        }

        ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

        if (extendedblockstorage == NULL_BLOCK_STORAGE) {
            int height = this.heightMap[k << 4 | i];

            cir.setReturnValue((j >= height) ? type.defaultLightValue : 0);
        } else if (type == EnumSkyBlock.SKY) {
            cir.setReturnValue(!this.world.provider.hasSkyLight() ? 0 : extendedblockstorage.getSkyLight(i, j & 15, k));
        } else {
            cir.setReturnValue(type == EnumSkyBlock.BLOCK ? extendedblockstorage.getBlockLight(i, j & 15, k)
                    : type.defaultLightValue);
        }
    }

    @Inject(method = "setLightFor", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$setLightFor(EnumSkyBlock type, @NonNull BlockPos pos, int lightValue, CallbackInfo ci) {
        if (!depthsupdate$isExtended()) {
            // ★ 非扩展维度（本维度高度仍是 0..255）也必须做越界保护。
            //
            // 原版 Chunk.setLightFor 的字节码（offset 24-32）是：
            //     storageArrays[pos.getY() >> 4]        // 无任何边界检查
            // 当 getY() 为负时，(负数 >> 4) 得到 -1，于是 storageArrays[-1]，
            // 抛 ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 16。
            //
            // 谁会在负 Y 调用它？原版 WorldServer.playerCheckLight：
            //     y = MathHelper.floor(player.posY) + rand.nextInt(11) - 5;
            //     this.checkLight(new BlockPos(x, y, z));
            // 即以"玩家 Y ± 5"随机取点。只要玩家位于 y &lt; 5，就会取到负 Y，
            // 调用链 World.checkLight -> checkLightFor -> setLightFor 一路传下去。
            // 实测：玩家在 DIM-1 的 y=-4.30（floor = -5，范围 -10..0），
            // 服务端随即在 WorldServer.tick -> updateBlocks -> playerCheckLight 崩服。
            //
            // 越界坐标本就不存在光照可言，直接取消即可（等价于原版"世界外无光照"的语义），
            // 且不改动任何合法坐标的行为。
            int outY = pos.getY();

            if (outY < 0 || outY >= 256) {
                ci.cancel();
            }

            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        int i = pos.getX() & 15;
        int j = pos.getY();
        int k = pos.getZ() & 15;
        int chunkY = ctx.toStorageIndex(j);

        if (chunkY < 0 || chunkY >= this.storageArrays.length) {
            ci.cancel();

            return;
        }

        ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

        if (extendedblockstorage == NULL_BLOCK_STORAGE) {
            extendedblockstorage = new ExtendedBlockStorage(j >> 4 << 4, this.world.provider.hasSkyLight());
            this.storageArrays[chunkY] = extendedblockstorage;
            this.generateSkylightMap();
        }

        this.dirty = true;

        if (type == EnumSkyBlock.SKY) {
            if (this.world.provider.hasSkyLight()) {
                extendedblockstorage.setSkyLight(i, j & 15, k, lightValue);
            }
        } else if (type == EnumSkyBlock.BLOCK) {
            extendedblockstorage.setBlockLight(i, j & 15, k, lightValue);
        }

        ci.cancel();
    }

    @Inject(method = "getLightSubtracted", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$getLightSubtracted(@NonNull BlockPos pos, int amount, CallbackInfoReturnable<Integer> cir) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        int i = pos.getX() & 15;
        int j = pos.getY();
        int k = pos.getZ() & 15;
        int chunkY = ctx.toStorageIndex(j);

        if (chunkY < 0 || chunkY >= this.storageArrays.length) {
            cir.setReturnValue(this.world.provider.hasSkyLight() && amount < EnumSkyBlock.SKY.defaultLightValue
                    ? EnumSkyBlock.SKY.defaultLightValue - amount
                    : 0);

            return;
        }

        ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

        if (extendedblockstorage == NULL_BLOCK_STORAGE) {
            cir.setReturnValue(this.world.provider.hasSkyLight() && amount < EnumSkyBlock.SKY.defaultLightValue
                    ? EnumSkyBlock.SKY.defaultLightValue - amount
                    : 0);
        } else {
            int l = !this.world.provider.hasSkyLight() ? 0 : extendedblockstorage.getSkyLight(i, j & 15, k);
            l = l - amount;
            int i1 = extendedblockstorage.getBlockLight(i, j & 15, k);

            if (i1 > l) {
                l = i1;
            }

            cir.setReturnValue(l);
        }
    }

    /**
     * Rebuild chunk from primer for extended dimensions, handling the full configured Y range.
     */
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/ChunkPrimer;II)V", at = @At("RETURN"))
    private void depthsupdate$onChunkPrimerInit(@NonNull World worldIn, ChunkPrimer primer, int x, int z, CallbackInfo ci) {
        if (!HeightManager.isExtended(worldIn)) {
            return;
        }

        HeightContext ctx = HeightManager.get(worldIn);
        boolean flag = worldIn.provider.hasSkyLight();

        for (int i = 0; i < this.storageArrays.length; ++i) {
            this.storageArrays[i] = null;
        }

        for (int i = 0; i < this.heightMap.length; ++i) {
            this.heightMap[i] = ctx.minY();
        }

        for (int j = 0; j < 16; ++j) {
            for (int k = 0; k < 16; ++k) {
                for (int l = ctx.minY(); l < ctx.maxY(); ++l) {
                    IBlockState iblockstate = primer.getBlockState(j, l, k);

                    if (iblockstate.getMaterial() != net.minecraft.block.material.Material.AIR) {
                        int chunkY = ctx.toStorageIndex(l);

                        if (this.storageArrays[chunkY] == null) {
                            this.storageArrays[chunkY] = new ExtendedBlockStorage(l >> 4 << 4, flag);
                        }

                        this.storageArrays[chunkY].set(j, l & 15, k, iblockstate);
                    }
                }
            }
        }

        // Recompute heightmap and skylight after re-filling blocks across full Y range
        this.generateSkylightMap();
    }

    
    /**
     * Find the highest filled section, checking upper extension first, then vanilla, then negative.
     */
    @Inject(method = "getTopFilledSegment", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getTopFilledSegment(CallbackInfoReturnable<Integer> cir) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();

        // Check upper extension sections first (highest Y, stored at indices 16..16+upperSections-1)
        for (int i = 16 + ctx.upperSections() - 1; i >= 16; --i) {
            if (i < this.storageArrays.length && this.storageArrays[i] != NULL_BLOCK_STORAGE) {
                cir.setReturnValue(this.storageArrays[i].getYLocation());
                return;
            }
        }

        // Then vanilla sections (Y 240 down to Y 0)
        for (int i = 15; i >= 0; --i) {
            if (this.storageArrays[i] != NULL_BLOCK_STORAGE) {
                cir.setReturnValue(this.storageArrays[i].getYLocation());
                return;
            }
        }

        // Finally negative sections, from the one CLOSEST TO THE SURFACE downwards.
        //
        // ★ 顺序很关键：本方法必须返回【最高的】非空段。
        // 负层段在 storageArrays 里的下标顺序是"越深下标越大"
        // （index 25 = Y -16..-1，index 28 = Y -64..-49），
        // 所以要从【小的下标】往【大的下标】走，第一个命中的就是最浅（最高）的负层段。
        // 曾经这里是 negStart -> negEnd，即自上而下却从最深处开始，
        // 结果只有深层方块的区块会返回 -64 这个"最深的段"，
        // 而调用方（如 WorldEntitySpawner.getRandomChunkPosition）拿它当"顶部"用，
        // 于是 nextInt(-64 + 16 - 1) = nextInt(-49) 抛
        // IllegalArgumentException: bound must be positive，服务端直接崩。
        int negStart = 16 + ctx.upperSections();
        int negEnd = negStart + ctx.negativeSections();
        for (int i = negEnd - 1; i >= negStart; --i) {
            if (i < this.storageArrays.length && this.storageArrays[i] != NULL_BLOCK_STORAGE) {
                cir.setReturnValue(this.storageArrays[i].getYLocation());
                return;
            }
        }

        // ★ 全空区块返回 0，与原版一致。
        //
        // 原版 getTopFilledSegment -> getLastExtendedBlockStorage 返回 null 时走 iconst_0，
        // 即"空区块的顶部是 0"。我们以前返回 ctx.minY()（-64），
        // 使虚空世界（例如 xu_wall 长城世界的初始状态）的调用方算出负数区间而崩溃。
        cir.setReturnValue(0);
    }

    /**
     * Height-map scan for one column, semantically identical to the vanilla
     * {@code for (l = top; l > minY; --l) if (getBlockLightOpacity(j, l - 1, k) != 0)}
     * loop but skipping storage sections that hold no blocks at all.
     *
     * <p>Vanilla only ever scans the 16 layers of the single section that the
     * highest block lives in, so it performs at most 16 opacity lookups per
     * column. Because the height extension gives a chunk up to 29 sections
     * spanning e.g. y=-48..351, the previous port kept vanilla's loop shape and
     * therefore walked the entire world height downwards - up to ~416 opacity
     * lookups per column, 256 columns, on every generateHeightMap() /
     * generateSkylightMap() call (chunk load, relight, every block change).
     *
     * <p>A section that is null or {@code isEmpty()} cannot contain a non-zero
     * light opacity, so its 16 layers are skipped with a single check. The
     * recorded height and the minimum tracking are unchanged.
     */
    @Unique
    private void depthsupdate$scanColumnHeight(int x, int z, int fromY, int minY) {
        int y = fromY;

        while (y > minY) {
            int storageIdx = this.depthsupdate$ctx().toStorageIndex(y - 1);

            if (storageIdx < 0 || storageIdx >= this.storageArrays.length) {
                y -= 16;
                continue;
            }

            ExtendedBlockStorage section = this.storageArrays[storageIdx];

            if (section == NULL_BLOCK_STORAGE || section.isEmpty()) {
                // Nothing opaque can exist in this section: skip straight to its bottom.
                y = section == NULL_BLOCK_STORAGE ? y - 1 - ((y - 1) & 15) : section.getYLocation();
                continue;
            }

            if (this.getBlockLightOpacity(x, y - 1, z) != 0) {
                this.heightMap[z << 4 | x] = y;

                if (y < this.heightMapMinimum) {
                    this.heightMapMinimum = y;
                }

                return;
            }

            --y;
        }
    }

    @Inject(method = "generateHeightMap", at = @At("HEAD"), cancellable = true, require = 0)
    private void depthsupdate$generateHeightMap(@NonNull CallbackInfo ci) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        ci.cancel();
        HeightContext ctx = depthsupdate$ctx();
        int i = this.getTopFilledSegment();
        this.heightMapMinimum = Integer.MAX_VALUE;
        int minY = ctx.minY();

        for (int j = 0; j < 16; ++j) {
            for (int k = 0; k < 16; ++k) {
                this.precipitationHeightMap[j + (k << 4)] = -999;

                this.heightMap[k << 4 | j] = minY;

                depthsupdate$scanColumnHeight(j, k, i + 16, minY);
            }
        }

        this.dirty = true;
    }

    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$generateSkylightMap(@NonNull CallbackInfo ci) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        ci.cancel();
        HeightContext ctx = depthsupdate$ctx();
        int i = this.getTopFilledSegment();
        this.heightMapMinimum = Integer.MAX_VALUE;
        int minY = ctx.minY();

        for (int j = 0; j < 16; ++j) {
            for (int k = 0; k < 16; ++k) {
                this.precipitationHeightMap[j + (k << 4)] = -999;

                this.heightMap[k << 4 | j] = minY;

                depthsupdate$scanColumnHeight(j, k, i + 16, minY);

                if (this.world.provider.hasSkyLight()) {
                    int k1 = 15;
                    int i1 = i + 16 - 1;

                    while (true) {
                        int j1 = this.getBlockLightOpacity(j, i1, k);

                        if (j1 == 0 && k1 != 15) {
                            j1 = 1;
                        }

                        k1 -= j1;

                        if (k1 > 0) {
                            int chunkY = ctx.toStorageIndex(i1);

                            if (chunkY >= 0 && chunkY < this.storageArrays.length) {
                                ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

                                if (extendedblockstorage != NULL_BLOCK_STORAGE) {
                                    extendedblockstorage.setSkyLight(j, i1 & 15, k, k1);
                                    this.world.notifyLightSet(new BlockPos((this.x << 4) + j, i1, (this.z << 4) + k));
                                }
                            }
                        } else {
                            break;
                        }

                        --i1;

                        if (i1 < minY || k1 <= 0) {
                            break;
                        }
                    }
                }
            }
        }

        this.dirty = true;
    }

    @Inject(method = "relightBlock", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$relightBlock(int x, int y, int z, @NonNull CallbackInfo ci) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        ci.cancel();
        HeightContext ctx = depthsupdate$ctx();
        int i = this.heightMap[z << 4 | x];
        int j = i;
        int minY = ctx.minY();

        if (y > i) {
            j = y;
            // New block above old surface. Don't scan down through air below it -
            // in extended-Y worlds (minY=-64) this would collapse heightMap back
            // to minY for manually-placed blocks. The block at y is the new surface.
        } else {
            while (j > minY && this.getBlockLightOpacity(x, j - 1, z) == 0) {
                --j;
            }
        }

        if (j != i) {
            this.world.markBlocksDirtyVertical(x + this.x * 16, z + this.z * 16, j, i);
            this.heightMap[z << 4 | x] = j;
            int k = this.x * 16 + x;
            int l = this.z * 16 + z;

            if (this.world.provider.hasSkyLight()) {
                if (j < i) {
                    for (int j1 = j; j1 < i; ++j1) {
                        int chunkY = ctx.toStorageIndex(j1);

                        if (chunkY >= 0 && chunkY < this.storageArrays.length) {
                            ExtendedBlockStorage extendedblockstorage2 = this.storageArrays[chunkY];

                            if (extendedblockstorage2 != NULL_BLOCK_STORAGE) {
                                extendedblockstorage2.setSkyLight(x, j1 & 15, z, 15);
                                this.world.notifyLightSet(new BlockPos((this.x << 4) + x, j1, (this.z << 4) + z));
                            }
                        }
                    }
                } else {
                    for (int i1 = i; i1 < j; ++i1) {
                        int chunkY = ctx.toStorageIndex(i1);

                        if (chunkY >= 0 && chunkY < this.storageArrays.length) {
                            ExtendedBlockStorage extendedblockstorage = this.storageArrays[chunkY];

                            if (extendedblockstorage != NULL_BLOCK_STORAGE) {
                                extendedblockstorage.setSkyLight(x, i1 & 15, z, 0);
                                this.world.notifyLightSet(new BlockPos((this.x << 4) + x, i1, (this.z << 4) + z));
                            }
                        }
                    }
                }

                int k1 = 15;

                while (j > minY && k1 > 0) {
                    --j;
                    int i2 = this.getBlockLightOpacity(x, j, z);

                    if (i2 == 0) {
                        i2 = 1;
                    }

                    k1 -= i2;

                    if (k1 < 0) {
                        k1 = 0;
                    }

                    int chunkY = ctx.toStorageIndex(j);

                    if (chunkY >= 0 && chunkY < this.storageArrays.length) {
                        ExtendedBlockStorage extendedblockstorage1 = this.storageArrays[chunkY];

                        if (extendedblockstorage1 != NULL_BLOCK_STORAGE) {
                            extendedblockstorage1.setSkyLight(x, j & 15, z, k1);
                        }
                    }
                }
            }

            int l1 = this.heightMap[z << 4 | x];
            int j2 = i;
            int k2 = l1;

            if (l1 < i) {
                j2 = l1;
                k2 = i;
            }

            if (l1 < this.heightMapMinimum) {
                this.heightMapMinimum = l1;
            }

            if (this.world.provider.hasSkyLight()) {
                for (net.minecraft.util.EnumFacing enumfacing : net.minecraft.util.EnumFacing.Plane.HORIZONTAL) {
                    this.updateSkylightNeighborHeight(k + enumfacing.getXOffset(), l + enumfacing.getZOffset(), j2, k2);
                }

                this.updateSkylightNeighborHeight(k, l, j2, k2);
            }

            this.dirty = true;
        }
    }

    /**
     * Redirects the section-index computation inside vanilla/Forge addEntity.
     *
     * <p>Vanilla computes {@code k = MathHelper.floor(posY / 16.0D)} (the 3rd floor(D)I call in
     * addEntity, ordinal = 2 — verified against the Cleanroom 1.12.2 Chunk bytecode) and clamps it
     * to {@code [0, entityLists.length-1]}. In extended worlds we redirect that call to return the
     * extended storage index instead (via HeightContext.toStorageIndex), so entities at negative Y
     * land in the negative storage sections (indices 16+upperSections..) instead of being clamped
     * into section 0. Non-extended worlds keep vanilla behaviour.</p>
     *
     * <p>Unlike the previous {@code @Inject(HEAD, cancellable) + ci.cancel()} full replacement, the
     * rest of the vanilla/Forge body still executes: the Forge EnteringChunk event, the
     * {@code entity.chunkCoordY = k} write (which Sponge's cross-chunk move logic relies on for
     * {@code removeEntityAtIndex(entity, entity.chunkCoordY)}), the list add, and — critically —
     * Sponge's {@code @Inject} at RETURN of addEntity that registers this chunk as the entity's
     * active chunk. Without that registration Sponge can never remove the entity from the old
     * chunk's entity list when it crosses a chunk boundary, so the entity ends up registered into
     * multiple chunks' lists, and the save-time validation (Sponge's
     * AnvilChunkLoaderMixin_FilterInvalidEntities) spams "is not in chunk ... skipping save".</p>
     */
    @Redirect(method = "addEntity",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/math/MathHelper;floor(D)I",
                    ordinal = 2))
    private int depthsupdate$entitySectionIndex(double value) {
        HeightContext ctx = depthsupdate$ctx();

        if (!ctx.isExtended()) {
            return MathHelper.floor(value);
        }

        int sectionY = MathHelper.floor(value);
        int storageIdx = ctx.toStorageIndex(sectionY << 4);
        int listsLen = this.entityLists == null ? -1 : this.entityLists.length;


        if (storageIdx < 0) {
            // Out of world height: mirror vanilla's clamp semantics
            // (below floor -> section 0, above ceiling -> last section)
            return sectionY < ctx.minSection() ? 0 : this.entityLists.length - 1;
        }

        return Math.min(storageIdx, this.entityLists.length - 1);
    }

    @Inject(method = "getEntitiesWithinAABBForEntity", at = @At("HEAD"), cancellable = true)
    public void depthsupdate$getEntitiesWithinAABBForEntity(@Nullable Entity entityIn, @NonNull AxisAlignedBB aabb, List<Entity> listToFill, Predicate<? super Entity> filter, CallbackInfo ci) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        int startY = MathHelper.floor((aabb.minY - World.MAX_ENTITY_RADIUS) / 16.0D);
        int endY = MathHelper.floor((aabb.maxY + World.MAX_ENTITY_RADIUS) / 16.0D);
        startY = MathHelper.clamp(startY, ctx.minSection(), ctx.maxSection());
        endY = MathHelper.clamp(endY, ctx.minSection(), ctx.maxSection());

        for (int y = startY; y <= endY; ++y) {
            int k = ctx.toStorageIndex(y << 4);
            if (k >= 0 && k < this.entityLists.length && !this.entityLists[k].isEmpty()) {
                for (Entity entity : this.entityLists[k]) {
                    if (entity.getEntityBoundingBox().intersects(aabb) && entity != entityIn) {
                        if (filter == null || filter.apply(entity)) {
                            listToFill.add(entity);
                        }

                        Entity[] aentity = entity.getParts();

                        if (aentity != null) {
                            for (Entity entity1 : aentity) {
                                if (entity1 != entityIn && entity1.getEntityBoundingBox().intersects(aabb)
                                        && (filter == null || filter.apply(entity1))) {
                                    listToFill.add(entity1);
                                }
                            }
                        }
                    }
                }
            }
        }

        ci.cancel();
    }

    @Inject(method = "getEntitiesOfTypeWithinAABB", at = @At("HEAD"), cancellable = true)
    public <T extends Entity> void depthsupdate$getEntitiesOfTypeWithinAABB(Class<? extends T> entityClass, @NonNull AxisAlignedBB aabb, List<T> listToFill, Predicate<? super T> filter, CallbackInfo ci) {
        if (!depthsupdate$isExtended()) {
            return;
        }

        HeightContext ctx = depthsupdate$ctx();
        int startY = MathHelper.floor((aabb.minY - World.MAX_ENTITY_RADIUS) / 16.0D);
        int endY = MathHelper.floor((aabb.maxY + World.MAX_ENTITY_RADIUS) / 16.0D);
        startY = MathHelper.clamp(startY, ctx.minSection(), ctx.maxSection());
        endY = MathHelper.clamp(endY, ctx.minSection(), ctx.maxSection());

        for (int y = startY; y <= endY; ++y) {
            int k = ctx.toStorageIndex(y << 4);
            if (k >= 0 && k < this.entityLists.length) {
                for (T t : this.entityLists[k].getByClass(entityClass)) {
                    if (t.getEntityBoundingBox().intersects(aabb) && (filter == null || filter.apply(t))) {
                        listToFill.add(t);
                    }
                }
            }
        }

        ci.cancel();
    }
}
