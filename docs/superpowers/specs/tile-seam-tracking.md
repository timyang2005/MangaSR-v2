# Tile 接缝追踪

## 概述

Tile 推理时，相邻 tile 在重叠区域因网络边界精度下降产生像素值差异，导致视觉上的"接缝"。

当前未出现可感知接缝，此文档用于收纳未来若用户报告接缝问题时所需的修复方案。

## 位置

`realesrgan_wrapper.cpp`，两处 tile 拷贝循环：

| 模型路径 | 行号 | 逻辑 |
|----------|------|------|
| RealCUGAN tiling | 283-295 | 直接赋值 `dst_row[dst_x] = src_row[x]` |
| RealESRGAN tiling | 417-429 | 直接赋值 `dst_row[dst_x] = src_row[prepad_x + x]` |

## 修复方案

在 tile 重叠区域做线性渐变混合（weighted blending）：

```cpp
// 每个 tile 拷贝时，检查是否处于 overlap 区域
int overlap_pixels = prepadding * scale;

for (int x = 0; x < out_tile.w; x++) {
    int dst_x = out_x0 + x;
    if (dst_x < 0 || dst_x >= out_w) continue;

    float weight = 1.0f;

    // 左侧 tile 的右 overlap（当前 tile 的左边界）
    if (xi > 0 && x < overlap_pixels) {
        weight = (float)x / overlap_pixels;
    }
    // 上方 tile 的下 overlap（当前 tile 的上边界）
    if (yi > 0 && y < overlap_pixels) {
        float vw = (float)y / overlap_pixels;
        weight = min(weight, vw);  // 取最严格的权重
    }

    dst_row[dst_x] = dst_row[dst_x] * (1.0f - weight) + src_row[x] * weight;
}
```

### 权重策略

- **线性权重**：左 overlap 从 0→1，右 overlap 从 1→0
- **四角区域**：取水平/垂直权重的最小值，避免角部突变
- `prepadding * scale` = 实际 overlap 像素数（RealCUGAN 4x: 19*4=76px）

### 何时启用此修复

1. 用户报告高清放大后 tile 交界处有可见线
2. 截图确认接缝存在
3. 在性能测试中 benchmark tile seam diff > 10（像素级）
