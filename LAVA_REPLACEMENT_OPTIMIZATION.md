# 岩浆替换优化 - LAVA Replacement Optimization

## 优化内容

已成功优化旧世界转换代码，消除Y0-Y11的岩浆。

### 问题描述
在旧世界转换过程中，Y=0到Y=11之间的岩浆没有被处理，导致地下空间仍然存在岩浆池。

### 解决方案
在填充地形之前，先检测并替换指定范围内的岩浆为空气：

```java
// 检测Y=0到Y=11的岩浆并替换为空气
if (by >= 0 && by <= 11) {
    int storageIdxCheck = ctx.toStorageIndex(by);
    if (storageIdxCheck >= 0 && storageIdxCheck < storageArrays.length) {
        ExtendedBlockStorage sectionCheck = storageArrays[storageIdxCheck];
        if (sectionCheck != Chunk.NULL_BLOCK_STORAGE) {
            IBlockState existingState = sectionCheck.get(bx, by & 15, bz);
            if (existingState.getBlock() == net.minecraft.init.Blocks.LAVA) {
                // 将岩浆替换为空气，保留洞穴结构
                IBlockState air = net.minecraft.init.Blocks.AIR.getDefaultState();
                section.set(bx, by & 15, bz, air);
            }
        }
    }
}
```

### 技术细节
1. **检测范围**: Y=0 到 Y=11
2. **替换目标**: 仅岩浆方块 (`Blocks.LAVA`)
3. **替换为**: 空气 (`Blocks.AIR`)
4. **执行时机**: 在地形填充之前进行

### 优势
- **保留洞穴**: 岩浆被移除后，原有的洞穴结构得以保留
- **自然体验**: 地下空间更加自然，不会出现石头填充的空洞
- **性能优化**: 只替换必要的岩浆方块

## 测试步骤

1. 创建一个包含岩浆池的旧世界
2. 确保岩浆池位于Y=0-Y11范围内
3. 启用`convertOldWorlds = true`
4. 加载世界
5. 检查岩浆池是否已被石头替换

## 注意事项
- 此优化不会影响Y<0或Y>11的岩浆
- 保持了原有的地形生成逻辑
- 确保了地下空间的完整性和一致性