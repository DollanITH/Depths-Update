# 基岩和岩浆替换优化 - Bedrock and Lava Replacement Optimization

## 优化内容

已成功优化旧世界转换代码，处理Y0-Y11的基岩和岩浆问题。

### 问题描述
1. **基岩层问题**：Y=0到Y=6之间的基岩需要替换为石头或深板岩
2. **岩浆问题**：Y=0到Y=11之间的岩浆需要替换为空气以保留洞穴

### 解决方案

#### 1. 基岩替换 (Y=0 到 Y=6)
```java
// 检测Y=0到Y=6的基岩并替换
if (by >= 0 && by <= 6) {
    if (existingState.getBlock() == net.minecraft.init.Blocks.BEDROCK) {
        // 根据深度决定使用石头还是深板岩
        if (by <= 3) {
            // Y=0-3 使用石头
            replacementBlock = net.minecraft.init.Blocks.STONE.getDefaultState();
        } else {
            // Y=4-6 使用深板岩
            replacementBlock = BlockUtils.getDeepslateBlockState();
        }
    }
}
```

#### 2. 岩浆替换 (Y=7 到 Y=11)
```java
// 检测Y=7到Y=11的岩浆并替换为空气
if (by >= 7 && by <= 11) {
    if (existingState.getBlock() == net.minecraft.init.Blocks.LAVA) {
        // 替换为空气，保留洞穴结构
        IBlockState air = net.minecraft.init.Blocks.AIR.getDefaultState();
    }
}
```

### 技术细节

#### 基岩替换策略
- **Y=0 到 Y=3**：替换为石头 (`Blocks.STONE`)
- **Y=4 到 Y=6**：替换为深板岩 (`BlockUtils.getDeepslateBlockState()`)
- **理由**：底部使用石头更符合原版基岩层，上部使用深板岩提供更好的过渡

#### 岩浆替换策略
- **Y=0 到 Y=11**：替换为空气 (`Blocks.AIR`)
- **理由**：保留原有的洞穴结构，使地下空间更加自然

### 执行顺序
1. **先处理基岩** (Y=0-6)：在填充地形之前替换基岩
2. **再处理岩浆** (Y=0-11)：保留洞穴结构

### 优势
1. **更自然的地形**：
   - 基岩层被合理替换，不会出现突兀的基岩
   - 保留了原有的洞穴系统

2. **层次分明的结构**：
   - 底部使用石头，上部使用深板岩
   - 提供了更好的视觉过渡

3. **保持游戏平衡**：
   - 移除了危险的岩浆
   - 保留了探索价值

### 测试步骤
1. 创建一个包含基岩层和岩浆池的旧世界
2. 确保基岩在Y=0-6范围内
3. 确保岩浆在Y=0-11范围内（包括流动岩浆）
4. 启用`convertOldWorlds = true`
5. 加载世界后：
   - 检查基岩是否已被正确替换
   - 验证所有岩浆（包括流动岩浆）已被移除但洞穴保留
   - 观察石头到深板岩的过渡效果