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

        if (aabb.minY >= 0 && aabb.maxY >= 0) {
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