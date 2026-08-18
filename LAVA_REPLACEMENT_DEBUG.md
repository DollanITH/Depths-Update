# 岩浆替换调试指南 - Lava Replacement Debug Guide

## 问题描述
Y=3-Y=11的岩浆没有被正确替换为空气方块。

## 调试步骤

### 1. 检查检测逻辑
```java
// 检测范围：Y=0 到 Y=11
if (by >= 0 && by <= 11) {
    int storageIdx = ctx.toStorageIndex(by);
    if (storageIdx >= 0 && storageIdx < storageArrays.length) {
        // 确保 section 存在
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            section = new ExtendedBlockStorage(by >> 4 << 4, hasSkyLight);
            storageArrays[storageIdx] = section;
        }
        
        // 检测岩浆
        IBlockState existingState = section.get(bx, by & 15, bz);
        if (existingState.getBlock() == Blocks.LAVA || existingState.getBlock() == Blocks.FLOWING_LAVA) {
            // 替换为空气
            IBlockState air = Blocks.AIR.getDefaultState();
            section.set(bx, by & 15, bz, air);
        }
    }
}
```

### 2. 可能的问题及解决方案

#### 问题1：Section不存在
- **现象**：如果section是NULL_BLOCK_STORAGE，岩浆不会被检测到
- **解决**：代码已经修复，会自动创建section

#### 问题2：岩浆类型检测不完整
- **现象**：只检测了静态岩浆，忽略了流动岩浆
- **解决**：已经添加了对`Blocks.FLOWING_LAVA`的检测

#### 问题3：World Provider问题
- **现象**：某些世界可能有不同的岩浆生成方式
- **解决**：检查world.provider设置

### 3. 测试方法

#### 创建测试世界
1. 创建新的超平坦世界
2. 使用世界编辑器在Y=3-11放置岩浆
3. 保存世界

#### 启用调试
```java
// 添加调试日志
LOGGER.info("Checking lava at Y={}, block={}", by, existingState.getBlock());
```

#### 运行测试
1. 启用`convertOldWorlds = true`
2. 加载测试世界
3. 检查Y=3-11的岩浆是否被替换

### 4. 额外检查

#### 检查HeightContext
```java
// 确保HeightContext正确
LOGGER.info("HeightContext: minY={}, maxY={}", ctx.minY(), ctx.maxY());
```

#### 检查存储索引
```java
// 确保存储索引正确
int storageIdx = ctx.toStorageIndex(by);
LOGGER.info("Storage index for Y={}: {}", by, storageIdx);
```

### 5. 调试代码
如果问题仍然存在，可以添加以下调试代码：

```java
// 在岩浆处理部分添加调试
System.out.println("Checking lava at Y=" + by + ", block=" + existingState.getBlock());
if (existingState.getBlock() == Blocks.LAVA || existingState.getBlock() == Blocks.FLOWING_LAVA) {
    System.out.println("Replacing lava with air at Y=" + by);
    section.set(bx, by & 15, bz, Blocks.AIR.getDefaultState());
}
```

### 6. 预期结果
- Y=0-6：基岩被替换为石头/深板岩
- Y=0-11：所有岩浆（静态和流动）被替换为空气
- 洞穴结构保持完整