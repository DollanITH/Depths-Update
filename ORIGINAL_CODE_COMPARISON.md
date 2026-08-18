# Depths Update - 代码对比分析

## 原始版本反编译结果 (I:\depthsupdate-1.12.2-1.0.0-a12)

### DeepFill.class 反编译

```java
// 来自反编译的 DeepFill.class
public static IBlockState bandAt(int y, int minY, Random rand,
                                   IBlockState bedrock, IBlockState deepslate, IBlockState stone) {
    int deepslateMaxY = DepthsUpdateConfig.deepslateMaxY;
    int transitionRange = DepthsUpdateConfig.deepslateTransitionRange;

    // 第1层：最深部 - 基岩层
    if (y <= minY + rand.nextInt(5)) {
        return bedrock;
    }
    // 第2层：中深部 - 深板岩层
    else if (y <= deepslateMaxY - transitionRange) {
        return deepslate;
    }
    // 第3层：过渡层 - 随机过渡
    else if (y < deepslateMaxY) {
        if (rand.nextDouble() < (double)(deepslateMaxY - y) / (double)transitionRange) {
            return deepslate;
        } else {
            return y < 0 ? stone : null;  // <--- 关键点
        }
    }
    // 第4层：浅层（y >= deepslateMaxY）
    else {
        return y < 0 ? stone : null;  // <--- 关键点
    }
}
```

**关键发现：**
- `y < 0` 时返回 `stone`
- `y >= 0` 时在过渡层返回 `null`

---

## 当前版本代码

### MixinChunkProviderServer.java 问题分析

```java
// 第 150 行：循环范围
for (int blockY = minY; blockY <= fillMaxY; ++blockY) {

    // 第 157-161 行：0-4层基岩过滤
    if (blockY >= 0 && blockY <= 4) {
        int storageIdx = context.toStorageIndex(blockY);
        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
            ExtendedBlockStorage section = storageArrays[storageIdx];
            if (section != null && section.get(blockX, blockY & 15, blockZ).getBlock() == Blocks.BEDROCK) {
                section.set(blockX, blockY & 15, blockZ, stone);
            }
        }
    }

    // 第 164 行：使用 DeepFill 填充
    IBlockState state = DeepFill.bandAt(blockY, minY, this.depthsupdate$fillRandom,
                                         bedrock, deepslate, stone);

    // 第 166-181 行：条件填充
    if (state != null) {
        int storageIdx = context.toStorageIndex(blockY);
        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
            ExtendedBlockStorage section = storageArrays[storageIdx];

            if (section == null) {
                section = new ExtendedBlockStorage(blockY >> 4 << 4, hasSkyLight);
                storageArrays[storageIdx] = section;
            }

            // <--- 问题在这里！
            if (section.get(blockX, blockY & 15, blockZ).getBlock() == Blocks.STONE) {
                section.set(blockX, blockY & 15, blockZ, state);
            }
        }
    }
}
```

### 问题 1：填充范围太小

**第 139 行：**
```java
int fillMaxY = Math.max(4, DepthsUpdateConfig.deepslateMaxY);
```

- `DepthsUpdateConfig.deepslateMaxY = 0` (默认值)
- 所以 `fillMaxY = Math.max(4, 0) = 4`
- **循环只填充到 Y=4**，而 `minY = -48`

**结果：** Y=-48 到 Y=4 的范围几乎全部为空！

---

### 问题 2：填充条件过于严格

**第 178 行：**
```java
if (section.get(blockX, blockY & 15, blockZ).getBlock() == Blocks.STONE) {
    section.set(blockX, blockY & 15, blockZ, state);
}
```

对于 y < 0 的位置：
1. `DeepFill.bandAt(y, minY, ...)` 返回 `stone`
2. **如果原版生成器在 y<0 处生成的是空气、水或其他方块**，条件为 false
3. **方块不会被设置，y<0 处保持为空**

---

## 对比分析

| 项目 | 原始版本 (推测) | 当前版本 | 差异 |
|------|---------------|---------|------|
| DeepFill 逻辑 | 相同 | 相同 | ✓ 一致 |
| 填充范围 | `minY` 到 `maxY` (完整) | `minY` 到 `4` (截断) | ✗ 严重 |
| 填充条件 | 可能更宽松 | 仅 `STONE` | ✗ 过于严格 |
| y<0 处理 | 强制填充 | 依赖原版生成 | ✗ 可能失败 |

---

## 修复建议

### 修复 1：调整填充范围

```java
// 修改前
int fillMaxY = Math.max(4, DepthsUpdateConfig.deepslateMaxY);

// 修改后
int fillMaxY = context.maxY() - 1;  // 填充到 maxY 之前一层
```

### 修复 2：放宽填充条件

```java
// 修改前
if (section.get(blockX, blockY & 15, blockZ).getBlock() == Blocks.STONE) {
    section.set(blockX, blockY & 15, blockZ, state);
}

// 修改后：y<0 强制填充，y>=0 保持原条件
if (blockY < 0 ||
    section.get(blockX, blockY & 15, blockZ).getBlock() == Blocks.STONE) {
    section.set(blockX, blockY & 15, blockZ, state);
}
```

---

## 结论

**y<0 以下地形被砍掉的根本原因：**

1. **主要原因：** 填充范围被限制到 Y=4，而 minY = -48，导致 Y=-48 到 Y=4 的范围被严重截断
2. **次要原因：** 填充条件过于严格，原版生成器在 y<0 处可能生成空气/水，导致填充失败

原始版本（推测）应该完整填充从 minY 到 maxY 的所有位置，而不是只填充到 Y=4。
