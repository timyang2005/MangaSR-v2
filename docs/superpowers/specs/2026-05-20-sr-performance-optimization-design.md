# SR 性能优化设计文档

## 目标

在保证翻页不卡顿的前提下，尽可能减少 SR 结果显示延迟，保持阅读连贯性。

## 总览

四项独立优化，按优先级排列：

| # | 优化项 | 文件 | 收益 |
|---|--------|------|------|
| 1 | 延迟模型加载 | `SuperResolutionManager.kt` | 模型切换即时响应，快速切换不累积阻塞 |
| 2 | 轮询加速 | `ReaderPageImageView.kt` | 预加载页 0ms 显示，同页延迟 avg 250ms→150ms |
| 3 | WEBP 磁盘缓存 | `SRDiskCache.kt` | 编码 3-5x 加速，磁盘占用减半 |
| 4 | 修复 srResultFlow 泄漏 | `SuperResolutionInterceptor.kt` | 消除最多 64 条 Bitmap 引用泄漏 |

## 1. 延迟模型加载 (Deferred Model Loading)

### 现状

```kotlin
suspend fun switchModel(key: String) {
    if (activeModel?.key == key) return
    cancelAllProcessingJobs()
    mutex.withLock {
        nativeRelease()       // 释放旧模型
        nativeInit(key)       // 读取文件 + GPU 加载 (1-3s)
        activeModel = ...
    }
}
```

`switchModel()` 在锁内执行模型加载，阻塞所有 SR 处理。快速切换模型 N 次 = N × 加载时间 的停顿。

### 改动

`switchModel()` 只设标志，不加载模型。模型加载推迟到下次 `process()`。

```kotlin
// SuperResolutionManager

private var pendingModelKey: String? = null

suspend fun switchModel(key: String) {
    if (activeModel?.key == key) {
        pendingModelKey = null
        return
    }
    pendingModelKey = key
    cancelAllProcessingJobs()
    // 不获取锁，不加载模型，立即返回
}

suspend fun process(...) = mutex.withLock {
    // 按需加载待切换模型
    if (pendingModelKey != null && pendingModelKey != activeModel?.key) {
        nativeRelease()
        nativeInit(pendingModelKey!!)
        activeModel = SRModel.fromKey(pendingModelKey!!)
        pendingModelKey = null
    }
    // ... 原有处理逻辑
}
```

### 行为对比

| 场景 | 当前 | 优化后 |
|------|------|--------|
| 切换模型 | 阻塞 1-3s | **0ms 返回** |
| 快速切换 5 次 | 5 × 加载时间阻塞 | **1 次加载**（最后一次生效） |
| 首张 SR 处理 | 模型已加载 | 包含模型加载（总时间不变） |
| 正在 SR 时切换 | 等待 SR 完成 + 加载模型 | 当前 SR 不受影响 |

## 2. 轮询加速

### 现状

```kotlin
postDelayed(runnable, SR_REFRESH_INTERVAL_MS)  // 500ms
```

首次检查延迟 500ms。若 SR 在翻页前已完成（预加载场景），用户等待 500ms 才能看到结果。

### 改动

```kotlin
// 首次立即检查：预加载完成的页 0ms 显示
postDelayed(runnable, 0)

// 缩短轮询间隔：同页延迟 avg 250ms → 150ms
const val SR_REFRESH_INTERVAL_MS = 300L
```

### 效果

| 指标 | 当前 | 优化后 |
|------|------|--------|
| 预加载页首次检查 | 500ms | **0ms** |
| 同页平均通知延迟 | 250ms | **150ms** |
| CPU 开销 | 低 | 略增（500ms→300ms） |

## 3. WEBP 磁盘缓存

### 现状

```kotlin
// SRDiskCache.kt:35
stream.writeTo(file)  // 默认 PNG 格式
```

PNG 编码慢，文件大。

### 改动

```kotlin
// SRDiskCache.kt
// Android 11+ 使用 WEBP_LOSSY，低版本用 JPEG
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, stream)
} else {
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
}
```

解码端无需改动（BitmapFactory 自动识别格式）。

### 效果

| 格式 | 编码速度 | 文件大小 |
|------|---------|---------|
| PNG (当前) | 基准 | 基准 |
| WEBP 90 (优化) | **3-5x 快** | **~50%** |
| JPEG 90 (回退) | 2-3x 快 | ~30% |

### 风险

SR 结果是 AI 放大图像，WEBP 质量 90 在视觉上不可感知有损。

## 4. 修复 srResultFlow 泄漏

### 现状

```kotlin
// SuperResolutionInterceptor.kt
private val srResultFlow = MutableSharedFlow<SRResult>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

`srResultFlow` 在 `putSrResult()` 中被 `tryEmit`，但全项目无任何地方 collect。64 条缓冲导致最多 64 个 Bitmap 引用无法被 GC 回收。

### 改动

```kotlin
// 方式：裁掉缓冲区，设置为无缓冲
private val srResultFlow = MutableSharedFlow<SRResult>(
    extraBufferCapacity = 0,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

若将来需要 flow 通道，可随时恢复缓冲区。当前无 collector 则不应缓冲。

## 文件变更清单

| 文件 | 行数 | 变更类型 |
|------|------|----------|
| `SuperResolutionManager.kt` | ~10 行新增 | 延迟模型加载 |
| `ReaderPageImageView.kt` | 2 行修改 | 轮询加速 |
| `SRDiskCache.kt` | ~5 行修改 | WEBP 缓存 |
| `SuperResolutionInterceptor.kt` | 1 行修改 | 修复 flow 泄漏 |
