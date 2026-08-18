package sayys.depthsupdate.mixin;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sayys.depthsupdate.core.HeightManager;

/**
 * Mixin用于修改船（EntityBoat）的行为，使其在扩展高度的世界中正确漂浮
 */
@Mixin({EntityBoat.class})
public abstract class MixinEntityBoat extends MixinEntity {

    @Unique
    private static final double depthsupdate$floatTargetSubmersion = 0.35; // 目标浸没深度（35%）

    @Unique
    private static final double depthsupdate$floatSeekGain = 0.1; // 浮起速度系数

    @Unique
    private static final double depthsupdate$floatMaxRiseSpeed = 0.1; // 最大浮起速度

    @Unique
    private static final double depthsupdate$floatMaxSinkSpeed = 0.02; // 最大下潜速度

    public MixinEntityBoat() {
        super();
    }

    /**
     * 在方法头注入 - 修复浮力初始化问题
     *
     * 问题场景：当船首次生成时，如果位置在水中，motionY可能被错误设置为Double.MIN_VALUE
     * 这会导致船无法正确浮起，陷入无限下沉循环
     */
    @Inject(
        at = {@At("HEAD")},
        method = {"onUpdate"}
    )
    private void depthsupdate$sanitizeBuoyancy(CallbackInfo ci) {
        EntityBoat self = (EntityBoat) (Object) this;

        // 检查是否是扩展高度的世界
        if (HeightManager.isExtended(self.world)) {
            // 如果motionY被设置为Double.MIN_VALUE（初始值），重置为当前水面的高度
            if (this.motionY == Double.MIN_VALUE) {
                this.motionY = self.getEntityBoundingBox().maxY;
            }
        }
    }

    /**
     * 在方法尾注入 - 在深水中自动浮起
     *
     * 功能：当船完全沉入水中（motionY < 0）时，使用 PID 控制器让船浮到目标浸没深度（35%）
     */
    @Inject(
        at = {@At("RETURN")},
        method = {"onUpdate"}
    )
    private void depthsupdate$floatInDepths(CallbackInfo ci) {
        EntityBoat self = (EntityBoat) (Object) this;

        // 检查船是否完全沉没（向下运动）且在扩展高度的世界中
        if (self.motionY >= 0.0 && HeightManager.isExtended(self.world)) {
            return; // 如果正在上浮或静止，不需要干预
        }

        AxisAlignedBB boundingBox = self.getEntityBoundingBox();

        // 查找当前位置的水面高度
        double surface = this.depthsupdate$findWaterSurface(self, boundingBox);

        // 检查是否找到了水面，且水面高度高于船底至少5格
        if (!Double.isNaN(surface) && !(surface < boundingBox.minY - 0.05)) {
            // 计算误差：目标水面 - 当前船底 - 浸没深度
            double error = surface - 0.35 - boundingBox.minY;

            // 使用比例控制调整垂直速度：error * 0.1
            // 限制最大浮起速度为0.1，最大下潜速度为0.02
            this.motionY = MathHelper.clamp(error * 0.1, -0.02, 0.1);
        }
    }

    /**
     * 查找指定边界框内的水面高度
     *
     * 算法：检查边界框的四个角落和中心点，找到第一个遇到水方块的位置
     * 从上向下扫描，返回该位置的水面高度
     *
     * @param self 船实体
     * @param boundingBox 边界框
     * @return 水面高度，如果找不到水则返回NaN
     */
    @Unique
    private double depthsupdate$findWaterSurface(EntityBoat self, AxisAlignedBB boundingBox) {
        // 边界框内缩0.1格，避免检查边缘方块
        double inset = 0.1;

        // 定义检查位置：四个角落 + 中心点
        double[][] checkPositions = {
            {boundingBox.minX + inset, boundingBox.minY + inset},  // 左下角
            {boundingBox.minX + inset, boundingBox.maxY - inset},  // 左上角
            {boundingBox.maxX - inset, boundingBox.minY + inset},  // 右下角
            {boundingBox.maxX - inset, boundingBox.maxY - inset},  // 右上角
            {(boundingBox.minX + boundingBox.maxX) / 2.0,       // 中心点
             (boundingBox.minY + boundingBox.maxY) / 2.0}
        };

        // 计算要扫描的Y坐标范围
        int yTop = MathHelper.floor(boundingBox.maxY) + 1;
        int yBottom = MathHelper.floor(boundingBox.minY) - 1;

        double bestSurfaceHeight = Double.NaN;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 对每个检查位置，从上向下扫描
        for (double[] column : checkPositions) {
            int x = MathHelper.floor(column[0]);
            int z = MathHelper.floor(column[1]);

            // 从上往下遍历
            for (int y = yTop; y >= yBottom; --y) {
                pos.setPos(x, y, z);

                // 获取当前位置的方块状态
                IBlockState state = self.world.getBlockState(pos);

                // 检查是否是水方块
                if (state.getMaterial() == Material.WATER) {
                    // 计算水的实际高度（考虑方块类型的影响）
                    double height = (double)((float)y + BlockLiquid.getLiquidHeight(state, self.world, pos));

                    // 更新找到的最佳水面高度
                    if (Double.isNaN(bestSurfaceHeight) || height > bestSurfaceHeight) {
                        bestSurfaceHeight = height;
                    }
                    break; // 找到第一个水方块，停止扫描该位置
                }
            }
        }

        return bestSurfaceHeight;
    }

}
