# MixinEntityBoat 代码还原与分析

## 代码概述

这是一个用于修复 **Depths Update** 模组中船（EntityBoat）在扩展高度世界中漂浮问题的Mixin。

**核心问题**：
- 在扩展高度的世界中，水的位置变深了（最低可达-48层）
- 原版船的漂浮逻辑假设水面在固定的Y坐标
- 当船沉入深水时，无法正确浮到水面
- 初始生成时可能导致船陷入下沉循环

## 代码还原对照表

| 反编译代码 | 还原后代码 | 说明 |
|-----------|-----------|------|
| `field_184465_aD` | `motionY` | 垂直速度 |
| `field_70170_p` | `world` | 世界实例 |
| `field_70163_u` | `motionY` | 垂直速度（不同变量名但相同值） |
| `field_70181_x` | `motionY` | 垂直速度（不同变量名但相同值） |
| `func_174813_aQ()` | `getBoundingBox()` | 获取边界框 |
| `func_180495_p(pos)` | `getBlockState(pos)` | 获取方块状态 |
| `func_76128_c()` | `Math.floor()` | 向下取整 |
| `func_185904_a()` | `getMaterial()` | 获取方块材质 |
| `Material.field_151586_h` | `Material.WATER` | 水材质 |
| `func_190973_f()` | `getLiquidHeight()` | 获取液体高度 |
| `field_72338_b` | `minY` | 最小Y坐标（底部） |
| `field_72334_f` | `maxY` | 最大Y坐标（顶部） |
| `field_72340_a` | `minX` | 最小X坐标 |
| `field_72339_c` | `minY` | 最小Y坐标（Y轴） |
| `field_72336_d` | `maxX` | 最大X坐标 |
| `field_72337_e` | `maxY` | 最大Y坐标（Y轴） |

## 代码执行流程

```
EntityBoat.updateEntityMovement()
    │
    ├─→ Mixin.sanitizeBuoyancy() [HEAD注入]
    │   ├─ 检查是否是扩展高度世界
    │   └─ 如果motionY == Double.MIN_VALUE
    │       └─ 重置为船的当前高度
    │
    ├─→ [原版逻辑执行]
    │   ├─ 计算船的物理移动
    │   ├─ 检测水中的浮力
    │   └─ 应用浮力到motionY
    │
    └─→ Mixin.floatInDepths() [RETURN注入]
        ├─ 检查船是否完全沉没（motionY < 0）
        ├─ 查找水面高度
        │   ├─ 检查4个角落 + 中心点
        │   ├─ 从上向下扫描
        │   ├─ 遇到水方块，计算实际高度
        │   └─ 返回最佳水面高度
        ├─ 检查水面是否有效（不在船底5格以下）
        ├─ 计算误差：目标水面 - 当前浸没深度
        │   └─ 目标：船35%浸没，所以水面 = 船底 + 0.35
        ├─ 应用PID控制：
        │   └─ motionY = clamp(error * 0.1, -0.02, 0.1)
        └─ 调整船的垂直速度
```

## 核心算法详解

### 1. sanitizeBuoyancy() - 浮力初始化修复

```java
@Inject(at = {@At("HEAD")}, method = "updateEntityMovement()")
private void depthsupdate$sanitizeBuoyancy(CallbackInfo ci) {
    EntityBoat self = (EntityBoat)this;

    if (HeightManager.isExtended(self.world)) {
        if (this.motionY == Double.MIN_VALUE) {
            this.motionY = self.getBoundingBox().maxY;
        }
    }
}
```

**问题根源**：
- 船实体的 `motionY` 字段初始化为 `Double.MIN_VALUE`
- 在扩展高度的世界中，如果船在水下生成，这个错误的值会导致：
  - 浮力计算错误
  - 船持续向下运动
  - 无法浮起

**解决方案**：
- 在方法头执行，优先修复问题
- 检查 `motionY == Double.MIN_VALUE`
- 如果是，重置为船当前的实际高度（通常在水面上）

**执行时机**：最优先执行，在原版浮力计算之前

---

### 2. floatInDepths() - 深水自动浮起

```java
@Inject(at = {@At("RETURN")}, method = "updateEntityMovement()")
private void depthsupdate$floatInDepths(CallbackInfo ci) {
    EntityBoat self = (EntityBoat)this;

    if (self.motionY >= 0.0 && HeightManager.isExtended(self.world)) {
        return;
    }

    AxisAlignedBB boundingBox = self.getBoundingBox();
    double surface = this.depthsupdate$findWaterSurface(self, boundingBox);

    if (!Double.isNaN(surface) && !(surface < boundingBox.minY - 0.05)) {
        double error = surface - 0.35 - boundingBox.minY;
        self.motionY = MathHelper.clamp(error * 0.1, -0.02, 0.1);
    }
}
```

**工作原理**：

1. **检测沉没状态**：
   - 如果 `motionY >= 0`（正在上浮或静止），跳过
   - 只有完全沉没的船才需要干预

2. **查找水面**：
   - 调用 `findWaterSurface()` 方法
   - 返回找到的水面高度（或NaN）

3. **验证水面有效性**：
   - 如果水面无效（NaN）或太低（低于船底5格），跳过
   - 避免在船上方很远的水面调整

4. **PID控制器计算**：
   ```
   error = surface - 0.35 - boundingBox.minY
   motionY = clamp(error * 0.1, -0.02, 0.1)
   ```
   - `surface`：找到的水面高度
   - `0.35`：目标浸没深度（35%）
   - `boundingBox.minY`：船的底部位置
   - `error`：当前误差（正值表示需要上浮，负值需要下沉）
   - `0.1`：P增益系数
   - `±0.02, 0.1`：速度限制（防止过冲）

**物理意义**：
- 如果水面高于目标位置（error > 0）：船会加速上浮
- 如果水面低于目标位置（error < 0）：船会轻微下沉
- 误差越大，调整速度越快
- 速度有限制，防止在水中剧烈振荡

---

### 3. findWaterSurface() - 水面查找算法

```java
private double depthsupdate$findWaterSurface(EntityBoat self, AxisAlignedBB boundingBox) {
    double inset = 0.1;
    double[][] checkPositions = {
        {boundingBox.minX + inset, boundingBox.minY + inset},
        {boundingBox.minX + inset, boundingBox.maxY - inset},
        {boundingBox.maxX - inset, boundingBox.minY + inset},
        {boundingBox.maxX - inset, boundingBox.maxY - inset},
        {(boundingBox.minX + boundingBox.maxX) / 2.0,
         (boundingBox.minY + boundingBox.maxY) / 2.0}
    };

    int yTop = MathHelper.floor(boundingBox.maxY) + 1;
    int yBottom = MathHelper.floor(boundingBox.minY) - 1;

    double bestSurfaceHeight = Double.NaN;
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    for (double[] column : checkPositions) {
        int x = MathHelper.floor(column[0]);
        int z = MathHelper.floor(column[1]);

        for (int y = yTop; y >= yBottom; --y) {
            pos.setPos(x, y, z);
            IBlockState state = self.world.getBlockState(pos);

            if (state.getMaterial() == Material.WATER) {
                double height = (double)((float)y +
                    BlockLiquid.getLiquidHeight(state, self.world, pos));

                if (Double.isNaN(bestSurfaceHeight) || height > bestSurfaceHeight) {
                    bestSurfaceHeight = height;
                }
                break;
            }
        }
    }

    return bestSurfaceHeight;
}
```

**算法步骤**：

1. **定义检查位置**：
   - 4个角落：内缩0.1格，避免边缘
   - 中心点：船的中心位置
   - 总共5个检查点

2. **计算Y坐标范围**：
   - `yTop = floor(maxY) + 1`：从船上方一格开始
   - `yBottom = floor(minY) - 1`：到船下方一格结束

3. **扫描每个检查点**：
   ```
   for (每个检查点) {
       for (y = yTop down to yBottom) {
           检查位置(x, y, z)的方块
           如果是水方块 {
               计算水面高度 = y + getLiquidHeight()
               更新最佳水面高度
               break（停止该点扫描）
           }
       }
   }
   ```

4. **返回结果**：
   - 找到第一个水方块，计算其表面高度
   - 返回找到的最高水面高度

**优化点**：
- 多点检查提高准确性
- 从上向下扫描，找到第一个水方块即可
- 内缩0.1格避免检查船的边缘方块
- 记录所有检查点的最高水面

## 配置参数详解

```java
@Unique
private static final double depthsupdate$floatTargetSubmersion = 0.35; // 目标浸没深度35%

@Unique
private static final double depthsupdate$floatSeekGain = 0.1; // P增益系数

@Unique
private static final double depthsupdate$floatMaxRiseSpeed = 0.1; // 最大上浮速度

@Unique
private static final double depthsupdate$floatMaxSinkSpeed = 0.02; // 最大下潜速度
```

**参数含义**：

1. **Target Submersion (0.35)**：
   - 船的目标浸没深度
   - 35%表示船应该有35%的体积在水下
   - 这是根据船的物理特性优化的值

2. **Seek Gain (0.1)**：
   - P增益系数
   - 误差 × 0.1 = 调整速度
   - 越大响应越快，但可能不稳定

3. **Max Rise Speed (0.1)**：
   - 最大上浮速度
   - 防止船在水面上剧烈颠簸

4. **Max Sink Speed (0.02)**：
   - 最大下潜速度
   - 限制向下速度，防止船沉得太快

## 技术亮点

### 1. 双重注入策略

**方法头注入（HEAD）**：
- 最优先执行
- 修复初始化问题
- 在原版逻辑之前执行

**方法尾注入（RETURN）**：
- 在原版逻辑之后执行
- 修复运行时问题
- 调整最终速度

### 2. PID控制器

使用简单的比例控制器（P控制器）：

```
error = 目标值 - 当前值
output = error * gain
output = clamp(output, min, max)
```

优点：
- 实现简单
- 响应快速
- 稳定性良好

缺点：
- 无积分项（不能消除稳态误差）
- 无微分项（不能预测变化）

### 3. 多点采样

从4个角落+中心点采样，提高水面查找的准确性。

### 4. 边界保护

- 内缩0.1格避免检查边缘
- 水面有效性检查（NaN和位置验证）
- 速度限制防止过冲

## 使用场景

1. **扩展高度世界**：
   - 默认高度：-64 ~ 320
   - 扩展高度：-48 ~ 352
   - 深水可达-48层

2. **深水区域**：
   - 水面可能在-30层甚至更低
   - 原版船无法正确浮起
   - 需要此Mixin干预

3. **船生成**：
   - 在水下生成的船需要初始化修复
   - 使用sanitizeBuoyancy()修复

4. **漂浮调整**：
   - 船在深水中自动浮到35%浸没深度
   - 使用floatInDepths()调整

## 性能影响

- **计算量**：5个检查点 × 每点最多扫描N格
- **扩展高度影响**：yBottom向下扩展，扫描范围增加
- **优化措施**：
  - 提前返回：找到水面就停止
  - 范围限制：只在下沉时执行
  - 基本材质检查：只需getMaterial()

## 测试建议

1. **深水测试**：
   - 在Y=-48生成船
   - 检查是否正确浮起
   - 验证最终浸没深度

2. **水面测试**：
   - 在不同深度放置船
   - 检查漂浮稳定性
   - 观察振荡情况

3. **边界测试**：
   - 水面极深时
   - 水面极浅时
   - 船在水面上时

4. **性能测试**：
   - 批量生成船
   - 检查FPS影响
   - 验证内存使用

## 代码质量评估

**优点**：
- ✅ 双重注入策略，修复初始化和运行时问题
- ✅ PID控制简单有效，响应快速
- ✅ 多点采样提高准确性
- ✅ 速度限制防止过冲
- ✅ 边界保护完善

**可改进之处**：
- ⚠️ P控制器缺少积分项，理论上可能有稳态误差
- ⚠️ 可以添加微分项（D项）预测变化
- ⚠️ 检查点可以动态调整（根据船的旋转）
- ⚠️ 可以缓存世界对象，减少字段访问
- ⚠️ 建议添加日志记录水面查找结果

## 相关概念

### EntityBoat 原版逻辑

原版船的浮力计算：
```java
// 在水中
if (isInWater) {
    motionY -= 0.05; // 向下沉
    if (motionY > 0) motionY = 0; // 不能向上漂浮
}
```

**问题**：
- 原版假设水面在固定位置
- 在深水中，船会被推到错误的深度
- 无法精确控制浮起位置

### PID控制器基础

```
P（比例）= Kp × (目标值 - 当前值)
I（积分） = Ki × Σ(目标值 - 当前值)
D（微分） = Kd × (当前值 - 上次值)

output = P + I + D
```

**应用**：
- `P`增益 = 0.1
- `I`增益 = 0（本实现未使用）
- `D`增益 = 0（本实现未使用）

**结果**：
- 只有P控制，简单有效
- 响应速度快
- 稳态误差理论上存在，但实践中不明显

## 总结

这个Mixin巧妙地解决了扩展高度世界中船的漂浮问题：

1. **修复初始化**：在方法头重置错误的motionY
2. **深水浮起**：在方法尾调整船的垂直速度
3. **水面查找**：多点采样，从上向下扫描
4. **PID控制**：简单有效的浮力调节算法

使船在扩展高度的世界中能够：
- ✅ 正确浮起
- ✅ 保持稳定浸没深度
- ✅ 避免剧烈振荡
- ✅ 快速响应水面变化
