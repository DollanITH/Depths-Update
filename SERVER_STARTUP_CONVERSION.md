# 服务器启动时自动转换基岩层和岩浆层

## 功能概述

在服务器启动时自动转换旧世界的基岩层和岩浆层，根据指定的条件筛选需要转换的区块。

## 实现方式

### 1. 新增 Mixin: MixinMinecraftServerStartup.java

监听服务器启动后的第一次tick，执行世界扫描和转换。

#### 转换时机

- **第一次tick时执行**：确保所有世界和区块都已加载
- **每个世界只转换一次**：使用 `worldLoadedStates` 映射跟踪转换状态
- **避免重复转换**：如果世界已经正确转换，会跳过处理

#### 转换条件

需要满足以下**全部**条件：

1. **世界有高度限制变化**
   - `minY < 0` (有地下深度) 或 `maxY > 256` (有高空扩展)
   - 通过 `HeightManager.isExtended(world)` 检查

2. **区块没有y<0的方块**
   - 只有Y=0及以上的区块才转换
   - 跳过已经包含y<0方块（已扩展）的区块
   - 避免重复转换

3. **区块不是空的**
   - 区块包含实际方块数据
   - 通过采样检查提高性能

#### 转换内容

在指定区块执行以下操作：

**基岩层替换 (Y=0 到 Y=6)**
- Y=0-3: 替换为石头 (`Blocks.STONE`)
- Y=4-6: 替换为深板岩 (`BlockUtils.getDeepslateBlockState()`)

**岩浆替换 (Y=0 到 Y=11)**
- 替换为空气 (`Blocks.AIR`)
- 保留原有的洞穴结构
- Y=12-13: 更新液体流动状态

**深度石头填充 (Y < 0)**
- Y <= bedrockThreshold: 基岩
- Y <= fullDeepslateY: 深板岩
- Y < deepslateMaxY: 过渡区（随机石头/深板岩）
- Y >= deepslateMaxY: 石头

#### 执行流程

```
服务器启动 → 第一次tick触发
    ↓
检查是否已执行过（hasExecutedFirstTick）
    ↓
遍历所有世界
    ↓
对每个世界检查：
    ├─ 检查是否为扩展高度
    ├─ 检查世界是否已转换过（worldLoadedStates）
    ├─ 检查世界是否有区块没有y<0的方块
    ├─ 检查区块是否非空
    └─ 如果都满足 → 执行转换
        ├─ 遍历周围区块（32x32区域）
        ├─ 对每个区块检查条件
        └─ 如果满足条件 → 执行转换
            ├─ 替换基岩层 (Y=0-6)
            ├─ 替换岩浆 (Y=0-11)
            ├─ 填充深度石头 (Y<0)
            └─ 重新计算高度图和光照
        └─ 标记世界为已转换
    ↓
完成转换
```

### 2. 更新 Mixin: MixinAnvilChunkLoader.java

修改了 `readChunkFromNBT` 方法的转换逻辑，使其遵循相同的筛选条件。

#### 更新的条件检查

```java
// 旧逻辑：检查区块是否需要转换
boolean needsConversion = depthsupdate$needsOldWorldConversion(chunk, ctx);

// 新逻辑：只转换没有y<0方块的区块
if (depthsupdate$hasBlocksInChunkBelowY0(chunk, ctx)) {
    return; // 跳过已有y<0方块的区块
}

if (!depthsupdate$hasNonEmptyChunk(chunk)) {
    return; // 跳过空区块
}
```

## 配置选项

通过 `DepthsUpdateConfig` 配置：

```java
@Config.Name("Convert Old Worlds")
@Config.Comment("When loading chunks from a non-extended world, fill below Y=0 with stone.")
public boolean convertOldWorlds = true;

@Config.Name("Deepslate Max Y")
public static int deepslateMaxY = 0;

@Config.Name("Deepslate Transition Range")
public static int deepslateTransitionRange = 8;
```

## 性能优化

1. **采样检查**：区块section采样间隔4格，减少检查次数
2. **范围限制**：只扫描中心32x32区块，避免处理整个世界
3. **一次性执行**：只在服务器第一次tick执行，不会重复处理
4. **条件过滤**：提前过滤不满足条件的区块，避免不必要的计算

## 日志输出

```
DepthsUpdate/ServerStartup 开始扫描世界以进行基岩层和岩浆层转换...
DepthsUpdate/ServerStartup 检测到世界 <世界名> (0) 有高度扩展，minY=-64, maxY=320
DepthsUpdate/ServerStartup 世界 <世界名> 的区块需要转换基岩层和岩浆层
DepthsUpdate/ServerStartup 开始转换世界 <世界名> 的基岩层和岩浆层...
DepthsUpdate/ServerStartup 转换区块 [0, 0] 的基岩层和岩浆层
DepthsUpdate/ServerStartup 转换完成！共处理了 12 个区块
DepthsUpdate/ServerStartup 基岩层和岩浆层转换完成！共处理了 1 个世界
```

## 为什么使用第一次tick而不是启动时执行？

### 选择tick的原因

在 Minecraft Fabric 1.20.1 中，没有像 Forge 那样的 `ServerLifecycleEvent`，需要使用 Mixin 来监听事件。

使用第一次tick而不是世界加载事件的原因：

1. **世界完整性保证**：在第一次tick时，所有世界都已经加载完成，区块数据完整
2. **避免过早执行**：如果在世界加载过程中执行，某些区块可能还未生成
3. **简化实现**：使用 tick 事件是 Fabric 中常用的模式，代码更简洁

### 避免重复转换

使用 `worldLoadedStates` 映射确保每个世界只转换一次：

```java
@Unique
private static final java.util.Map<Integer, Boolean> worldLoadedStates = new java.util.HashMap<>();
```

- 每个世界有唯一的 dimension ID
- 转换完成后标记为 true
- 下次启动时如果发现已转换，直接跳过

## 与旧版本的区别

### 旧版本（MixinAnvilChunkLoader）
- 在加载区块时转换
- 任何有数据的区块都会被转换
- 如果区块已经有y<0的方块，仍会被转换（可能导致重复工作）
- 没有统一的转换时机控制

### 新版本（MixinMinecraftServerStartup）
- 在服务器启动后的第一次tick批量转换
- 只转换Y=0及以上的区块（没有y<0方块的区块）
- 使用 `worldLoadedStates` 映射避免重复转换
- 更高效的批量处理，一次性完成所有世界的转换
- 更好的性能和更明确的行为

## 注意事项

1. **只在服务器第一次tick执行**：确保转换只执行一次
2. **范围限制**：只扫描中心32x32区块，其他区块在加载时会转换
3. **configOldWorlds开关**：通过配置禁用转换功能
4. **维度过滤**：只处理扩展高度的维度

## 测试建议

1. 创建一个有高度扩展的世界（minY=-64）
2. 检查Y=0-6是否有基岩
3. 检查Y=0-11是否有岩浆
4. 启动服务器，观察日志输出
5. 验证转换是否正确完成
6. 尝试禁用转换功能，确认逻辑正确
