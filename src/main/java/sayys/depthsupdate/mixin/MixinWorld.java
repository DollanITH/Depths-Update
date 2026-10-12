package sayys.depthsupdate.mixin;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import sayys.depthsupdate.core.ExtendedBlockWrite;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * 必须使用 priority = 1100（晚于 Sponge 的 mixins.common.core.json:world.WorldMixin，默认 1000）。
 *
 * Sponge 的 WorldMixin 直接 @Overwrite 了 func_189509_E（= isOutsideBuildHeight），
 * 实现退化成 BlockPosBridge.bridge$isInvalidYPosition() 的原版 0..255 判断。
 * 如果本 mixin 先于 Sponge 应用，下面 depthsupdate$isOutsideBuildHeight 的注入会被这次
 * @Overwrite 整个丢弃，于是：
 *   - World.setBlockState 在第 0 条字节码就因 isOutsideBuildHeight(pos)==true 返回 false
 *     → /setblock 报 commands.setblock.failed，任何 y<0 / y>=256 的放置都失败；
 *   - World.getBlockState 同样先判 isOutsideBuildHeight → 直接返回空气
 *     → 实体在 y<0 踩不到方块而坠入虚空、右键交互得到空气。
 * 提高优先级后本 mixin 在 Sponge 之后应用，注入得以保留并最先执行。
 */
@Mixin(value = World.class, priority = 1100)
public abstract class MixinWorld {
    @Shadow
    private int skylightSubtracted;

    @Shadow
    public abstract Chunk getChunk(BlockPos pos);

    @Shadow
    public abstract IBlockState getBlockState(BlockPos pos);

    @Shadow
    public abstract int getLight(BlockPos pos, boolean checkNeighbors);

    @Shadow
    public abstract boolean isValid(BlockPos pos);

    @Shadow
    public abstract boolean isBlockLoaded(BlockPos pos);

    @Shadow
    protected abstract boolean isChunkLoaded(int x, int z, boolean allowEmpty);

    @Inject(method = "isAreaLoaded(IIIIIIZ)Z", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$isAreaLoaded(int startX, int startY, int startZ, int endX, int endY, int endZ,
                                           boolean allowEmpty, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (!HeightManager.isExtended(self)) {
            return;
        }

        HeightContext ctx = HeightManager.get(self);
        if (endY >= ctx.minY() && startY < ctx.maxY()) {
            int chunkStartX = startX >> 4;
            int chunkStartZ = startZ >> 4;
            int chunkEndX = endX >> 4;
            int chunkEndZ = endZ >> 4;

            for (int i = chunkStartX; i <= chunkEndX; ++i) {
                for (int j = chunkStartZ; j <= chunkEndZ; ++j) {
                    if (!this.isChunkLoaded(i, j, allowEmpty)) {
                        cir.setReturnValue(false);
                        return;
                    }
                }
            }
            cir.setReturnValue(true);
        } else {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isOutsideBuildHeight", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$isOutsideBuildHeight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (!HeightManager.isExtended(self)) {
            return;
        }
        HeightContext ctx = HeightManager.get(self);
        cir.setReturnValue(pos.getY() < ctx.minY() || pos.getY() >= ctx.maxY());
    }

    /**
     * ★ 接管 {@code World.getLightFromNeighborsFor} —— 这是深层光照**不扩散**的根因。
     *
     * <p>反汇编原版该方法开头：
     * <pre>
     *   19: pos.getY()
     *   23: ifge 43                                     if (y &gt;= 0) 跳过
     *   26-42: new BlockPos(pos.getX(), 0, pos.getZ())  ★ 负 Y 被强行改成 Y=0
     *   43: isValid(改过的 pos)
     *   ...
     *   69: getBlockState(改过的 pos)
     * </pre>
     *
     * <p>原版认为"负 Y 不存在"，于是把坐标钉到 y=0 再查。在扩展世界里这个假设
     * 不成立：{@code getLightFromNeighborsFor} 是光照 BFS 计算"邻居能收到多少光"
     * 的核心函数，它被改成查 y=0 那格（通常是实心石头，光=0），
     * 于是 BFS 每一步算出的目标光都是 0，**光永远传不出去**。
     *
     * <p>实测症状与此完全吻合（岩浆自己 15、周围空气全 0）：
     * <pre>
     *   chunk(-3,-2): state=11(岩浆) 82 格 avgLight=15.00
     *                 state= 0(空气) 5547 格 avgLight= 0.00   &lt;- 一步都没扩散
     * </pre>
     *
     * <p>修法：对扩展世界的 y&lt;0 与 y&gt;=256 坐标，不走那段"钉到 y=0"的逻辑，
     * 直接按真实坐标计算。其余行为（useNeighborBrightness 分支、
     * isValid/isBlockLoaded 守卫）与原版保持一致。
     *
     * <h2>⚠️ 本注入当前【已停用】</h2>
     *
     * 该修法在原理上是正确的（原版确实把 y&lt;0 钉成 y=0），但**无法安全注入**：
     * <ul>
     *   <li>写 MCP 名 {@code "getLightFromNeighborsFor"} → 构建期 refmap 产出
     *       {@code func_175705_a}，运行期报
     *       "could not find any targets matching 'World;func_175705_a(...)'"；</li>
     *   <li>该注入是**关键失败**，会让整个 MixinWorld 的 APPLY 中止，最终
     *       {@code NoClassDefFoundError: net/minecraft/world/World}，
     *       <b>服务器完全无法启动</b>（实测 00:57:10 FATAL: Unable to launch）；</li>
     *   <li>改用 SRG 名 + {@code remap = false} 后 refmap 里仍残留旧条目，
     *       且无法在离线环境验证运行期名，风险不划算。</li>
     * </ul>
     *
     * <p>因此这里**不注入**，服务器恢复可启动。深层光照的"不扩散"问题需要
     * 换一条可验证的路线解决（见 EXTENDED-HEIGHT-STATUS.md 的遗留项）。
     *
     * <p>保留下面这段实现作为注释，便于后续以其它方式接入（例如在
     * {@code checkLightFor} 的调用方做坐标修正，或改为对 BFS 起点做补偿）。
     */
    /*
    @Inject(
            method = "getLightFromNeighborsFor",
            at = @At("HEAD"),
            cancellable = true
    )
    private void depthsupdate$getLightFromNeighborsFor(EnumSkyBlock type, BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;

        if (!HeightManager.isExtended(self)) {
            return;
        }

        HeightContext ctx = HeightManager.get(self);
        int y = pos.getY();

        // 只有落在扩展区间（y<0 或 y>=256）时才接管；0..255 完全交回原版。
        if (y >= 0 && y < 256) {
            return;
        }

        // 与原版一致：无天光的维度查 SKY 直接返回 0。
        if (type == EnumSkyBlock.SKY && !self.provider.hasSkyLight()) {
            cir.setReturnValue(0);

            return;
        }

        // 越界或区块未加载时原版返回 defaultLightValue，这里保持一致。
        // 注意：这里必须用**原始** pos，不能像原版那样替换成 y=0。
        if (y < ctx.minY() || y >= ctx.maxY() || !self.isValid(pos) || !self.isBlockLoaded(pos)) {
            cir.setReturnValue(type.defaultLightValue);

            return;
        }

        IBlockState state = self.getBlockState(pos);

        // useNeighborBrightness 分支：取六个邻居的最大光（原版语义）。
        if (state.useNeighborBrightness()) {
            int up = self.getLightFor(type, pos.up());
            int east = self.getLightFor(type, pos.east());
            int west = self.getLightFor(type, pos.west());
            int south = self.getLightFor(type, pos.south());
            int north = self.getLightFor(type, pos.north());
            int max = up;

            if (east > max) {
                max = east;
            }
            if (west > max) {
                max = west;
            }
            if (south > max) {
                max = south;
            }
            if (north > max) {
                max = north;
            }

            cir.setReturnValue(max);

            return;
        }

        // 普通情况：本格光照，再取六个邻居中的最大值（原版语义）。
        int light = self.getLightFor(type, pos);

        if (light < 15) {
            int up = self.getLightFor(type, pos.up());
            int down = self.getLightFor(type, pos.down());
            int east = self.getLightFor(type, pos.east());
            int west = self.getLightFor(type, pos.west());
            int south = self.getLightFor(type, pos.south());
            int north = self.getLightFor(type, pos.north());

            if (up > light) {
                light = up;
            }
            if (down > light) {
                light = down;
            }
            if (east > light) {
                light = east;
            }
            if (west > light) {
                light = west;
            }
            if (south > light) {
                light = south;
            }
            if (north > light) {
                light = north;
            }
        }

        cir.setReturnValue(light);
    }
    */

    /**
     * 在扩展区间接管 {@code World.isValid(BlockPos)}。
     *
     * <p>为什么必须单独接管 {@code isValid}：实测（一次性探针，y=260 的草方块）
     * <pre>
     *   [DEPTHSUPDATE-SIDESOLID] pos=BlockPos{x=0, y=260, z=0} side=up default=false
     *     ctx[minY=-64, maxY=400] isValid=false chunk=present isEmpty=false
     *     state=minecraft:grass[...] stateIsSideSolid=true
     * </pre>
     * 方块真实存在且完全实心、区块也在，但 {@code isValid} 返回了 <b>false</b>。
     *
     * <p>根因是 <b>StellarCore</b> 的
     * {@code github.kasuminova.stellarcore.mixin.minecraft.world_pos_judgement.MixinWorld}
     * 在 {@code World} 上用<b>具体方法体</b>（不是 @Inject）重定义了：
     * <pre>
     *   func_189509_E(pos):  return y &lt; 0 || y &gt;= 256;      // isOutsideBuildHeight，硬编码
     *   func_175701_a(pos):  if (func_189509_E(pos)) return false; ...  // isValid
     * </pre>
     * 于是 y=260 被判为越界 → {@code isValid=false}。这与之前
     * {@code WorldServer.setBlockState} / {@code Chunk.setBlockState} /
     * {@code ChunkBufferPrimer} 是<b>同一类失败模式</b>：
     * 别的 mod 用具体方法覆盖了父类方法，Java 虚分派绕过了本 mod 挂在
     * {@code isOutsideBuildHeight} 上的 HEAD 注入，那个注入根本没执行。
     *
     * <p>连锁后果（这就是用户报的三个现象）：
     * <ul>
     *   <li>{@code World.isSideSolid(pos, side, _default)} 第一条字节码就是
     *       {@code if (!isValid(pos)) return _default;} —— 对扩展区间一律返回 false。
     *       Railcraft 的 {@code TrackSupportTools.isSupportedDirectly} 正是调它，
     *       于是 {@code BlockTrack.isRailValid} 得到"无支撑" → {@code breakRail()}
     *       → <b>轨道放下后立即消失</b>；</li>
     *   <li>告示牌/火把/铁轨等依赖支撑判定的方块，同样永远认为"支撑还在"
     *       → <b>失去支撑后浮空、不掉落</b>；</li>
     *   <li>凡是走 {@code isValid} 的放置/交互在扩展区间都被否掉。</li>
     * </ul>
     *
     * <p>这里<b>不与 StellarCore 争优先级</b>，而是直接接管 {@code isValid} 本身，
     * 在扩展区间自行按 {@link HeightContext} 判定；原版区间（0..255）原样交回，
     * 因此不改变任何原版行为。
     */
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$isValidExtended(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;

        if (!HeightManager.isExtended(self)) {
            return;
        }

        int y = pos.getY();
        HeightContext ctx = HeightManager.get(self);

        // 扩展高度之内的 Y，再加上与原版一致的水平范围检查。
        if (y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        if (y >= 0 && y < 256) {
            // 原版区间：X/Z 判定与原版一致，交回原版实现更稳妥。
            return;
        }

        int x = pos.getX();
        int z = pos.getZ();

        cir.setReturnValue(x >= -30000000 && z >= -30000000 && x < 30000000 && z < 30000000);
    }

    /**
     * 在扩展区间接管 {@code World.isSideSolid(BlockPos, EnumFacing, boolean)}。
     *
     * <p>即使 {@code isValid} 已经修好，这里仍然显式接管：原版实现对
     * {@code getChunk(pos) == null || chunk.isEmpty()} 也会返回 {@code _default}，
     * 而扩展区间刚放置方块时区块可能正好处于"存在但被判定为空"的状态。
     * 接管后直接读取方块状态并询问方块自身，语义与原版一致但不受上述短路影响。
     *
     * <p>只处理扩展区间；0..255 交回原版。
     */
    @Inject(method = "isSideSolid(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private void depthsupdate$isSideSolidExtended(BlockPos pos, net.minecraft.util.EnumFacing side, boolean _default,
            CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;

        if (!HeightManager.isExtended(self)) {
            return;
        }

        int y = pos.getY();
        HeightContext ctx = HeightManager.get(self);

        if (y >= 0 && y < 256) {
            return;
        }

        if (y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(_default);

            return;
        }

        Chunk chunk = self.getChunk(pos);

        if (chunk == null || chunk.isEmpty()) {
            cir.setReturnValue(_default);

            return;
        }

        IBlockState state = self.getBlockState(pos);

        cir.setReturnValue(state != null && state.isSideSolid(self, pos, side));
    }

    /**
     * 扩展高度区间（{@code y<0 || y>=256}）的方块写入。
     *
     * <p>为什么放在 {@code World} 层：SpongeForge 用<b>同名方法覆盖</b>了
     * {@code Chunk.func_177436_a}，并把实现搬进 {@code Chunk.bridge$setBlockState}。
     * 实测（服务端 8 次 {@code /setblock} 全部失败、{@code bridge$setBlockState} 上的
     * 注入一次都没触发）说明区块层的写入在被覆盖后走不通；而
     * {@code World.func_180501_a}（setBlockState）在 Sponge 的 {@code WorldMixin} 里只是
     * {@code abstract} 影子、<b>没有被覆盖</b>，因此这里注入稳定生效。
     *
     * <p>只接管原版区间之外；{@code 0..255} 原样返回，交回原版逻辑处理。
     */
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void depthsupdate$setBlockStateExtended(BlockPos pos, IBlockState state, int flags,
            CallbackInfoReturnable<Boolean> cir) {
        int y = pos.getY();

        if (y >= 0 && y < 256) {
            return;
        }

        World self = (World) (Object) this;
        HeightContext ctx = HeightManager.get(self);

        if (!ctx.isExtended() || y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        Chunk chunk = this.getChunk(pos);
        // 与 MixinSpongeWorldServerSetBlock 共用同一套实现：完整复刻原版
        // Chunk.setBlockState 的语义（onBlockAdded、方块实体创建/移除、光照、邻居与客户端同步），
        // 只把 Y→section 的映射换成扩展版。避免两处实现漂移。
        IBlockState old = ExtendedBlockWrite.setBlockState(self, chunk, pos, state, flags);

        if (old == null) {
            cir.setReturnValue(Boolean.FALSE);

            return;
        }

        cir.setReturnValue(Boolean.TRUE);
    }

    /**
     * 扩展高度区间（AABB 触及 y&lt;0 或 y&gt;=256）的碰撞收集，由模组自己完成。
     *
     * <p>实测：私有的 {@code getCollisionBoxes(Entity, AxisAlignedBB, boolean, List)} 在原版
     * 循环里对 y&lt;0 的实心深板岩（{@code getCollisionBoundingBox} 返回完整方块盒）
     * 收集到 <b>0</b> 个碰撞箱（{@code columnLoaded=true / valid=true}，方块读取也正确），
     * 于是实体一路坠到 y&lt;-64。这里直接接管：AABB 一旦触及原版区间之外，就按同样的语义
     * 自行遍历收集，不再依赖 Sponge 优化过的原版循环。
     *
     * <p>{@code checkBorder=true}（Forge 的 gatherCollisionBoxes 路径）时不接管，交回原版。
     */
    @Inject(method = "getCollisionBoxes(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;ZLjava/util/List;)Z",
            at = @At("HEAD"), cancellable = true)
    private void depthsupdate$collisionExtended(Entity entity, AxisAlignedBB aabb, boolean checkBorder,
            List<AxisAlignedBB> boxes, CallbackInfoReturnable<Boolean> cir) {
        if (checkBorder) {
            return;
        }

        // 只有 AABB 完全落在原版 0..255 区间内才交回原版。
        // 注意这里必须同时覆盖 <b>y&lt;0 与 y&gt;=256 两侧</b>：最初写成
        // `aabb.minY >= 0 && aabb.maxY >= 0`，只挡住了下半部分，于是实体在 y&gt;256
        // 仍然穿过方块坠落（上层 section 同样是 Sponge 优化过的原版循环收不到箱子）。
        if (aabb.minY >= 0 && aabb.maxY < 256) {
            return;
        }

        World self = (World) (Object) this;

        if (!HeightManager.isExtended(self)) {
            return;
        }

        int minX = net.minecraft.util.math.MathHelper.floor(aabb.minX) - 1;
        int maxX = net.minecraft.util.math.MathHelper.ceil(aabb.maxX) + 1;
        int minY = net.minecraft.util.math.MathHelper.floor(aabb.minY) - 1;
        int maxY = net.minecraft.util.math.MathHelper.ceil(aabb.maxY) + 1;
        int minZ = net.minecraft.util.math.MathHelper.floor(aabb.minZ) - 1;
        int maxZ = net.minecraft.util.math.MathHelper.ceil(aabb.maxZ) + 1;

        for (int x = minX; x < maxX; ++x) {
            for (int z = minZ; z < maxZ; ++z) {
                for (int y = minY; y < maxY; ++y) {
                    BlockPos p = new BlockPos(x, y, z);
                    self.getBlockState(p).addCollisionBoxToList(self, p, aabb, boxes, entity, false);
                }
            }
        }

        cir.setReturnValue(!boxes.isEmpty());
    }


    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getLightSimple(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        if (!HeightManager.isExtended(self)) {
            return;
        }
        int y = pos.getY();

        if ((y >= HeightManager.getMinY(self) && y < 0) || (y >= 256 && y < HeightManager.getMaxY(self))) {
            cir.setReturnValue(this.getChunk(pos).getLightSubtracted(pos, 0));
        }
    }

    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;Z)I", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getLight(BlockPos pos, boolean checkNeighbors, CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        if (!HeightManager.isExtended(self)) {
            return;
        }
        int y = pos.getY();

        if ((y >= HeightManager.getMinY(self) && y < 0) || (y >= 256 && y < HeightManager.getMaxY(self))) {
            if (pos.getX() >= -30000000 && pos.getZ() >= -30000000 && pos.getX() < 30000000
                    && pos.getZ() < 30000000) {
                if (checkNeighbors && this.getBlockState(pos).useNeighborBrightness()) {
                    int i1 = this.getLight(pos.up(), false);
                    int i = this.getLight(pos.east(), false);
                    int j = this.getLight(pos.west(), false);
                    int k = this.getLight(pos.south(), false);
                    int l = this.getLight(pos.north(), false);

                    if (i > i1)
                        i1 = i;
                    if (j > i1)
                        i1 = j;
                    if (k > i1)
                        i1 = k;
                    if (l > i1)
                        i1 = l;

                    cir.setReturnValue(i1);
                } else {
                    Chunk chunk = this.getChunk(pos);
                    cir.setReturnValue(chunk.getLightSubtracted(pos, this.skylightSubtracted));
                }
            } else {
                cir.setReturnValue(15);
            }
        }
    }

    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getLightFor(EnumSkyBlock type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        if (!HeightManager.isExtended(self)) {
            return;
        }
        int y = pos.getY();

        if ((y >= HeightManager.getMinY(self) && y < 0) || (y >= 256 && y < HeightManager.getMaxY(self))) {
            if (!this.isValid(pos)) {
                cir.setReturnValue(type.defaultLightValue);
            } else if (!this.isBlockLoaded(pos)) {
                cir.setReturnValue(type.defaultLightValue);
            } else {
                Chunk chunk = this.getChunk(pos);
                cir.setReturnValue(chunk.getLightFor(type, pos));
            }
        }
    }

    /**
     * Vanilla's heightmap never goes below 0 and its callers rely on that:
     * BiomePlains passes this straight to Random.nextInt with a +32 bias. A
     * column carved clear through reports the world floor instead, so keep the
     * vanilla range for anyone asking the world rather than the chunk.
     */
    @Inject(method = "getHeight(II)I", at = @At("RETURN"), cancellable = true)
    private void depthsupdate$getHeight(int x, int z, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() < 0) {
            cir.setReturnValue(0);
        }
    }

    /**
     * Vanilla loops down to Y >= 0 which misses solid ground below Y=0 in extended worlds.
     */
    @Inject(method = "getTopSolidOrLiquidBlock", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getTopSolidOrLiquidBlock(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        World self = (World) (Object) this;

        if (!HeightManager.isExtended(self)) {
            return;
        }

        HeightContext ctx = HeightManager.get(self);
        Chunk chunk = this.getChunk(pos);
        BlockPos blockpos = new BlockPos(pos.getX(), chunk.getTopFilledSegment() + 16, pos.getZ());
        BlockPos blockpos1;

        for (; blockpos.getY() >= ctx.minY(); blockpos = blockpos1) {
            blockpos1 = blockpos.down();
            Material material = chunk.getBlockState(blockpos1).getMaterial();

            if (material.blocksMovement() && material != Material.LEAVES) {
                break;
            }
        }

        cir.setReturnValue(blockpos);
    }
}
