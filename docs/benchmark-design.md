# MangaSR-v2 动态性能调度系统 — 设计文档

## 概述

基于设备实际 SR 推理性能，自动调整预加载窗口和轮询超时等参数，在不增加复杂度的情况下让不同设备获得最优翻页体验。

### 设计原则

1. **可选的** — 基准测试由用户主动触发，非强制
2. **轻量级** — 只用实际 SR 推理延迟做分级，不引入合成基准测试
3. **不覆盖用户偏好** — 模型选择、降噪级别等画质选项始终由用户决定
4. **渐进式** — Phase 1 解决最痛点（自动配置 + 低端机关预加载），Phase 2 锦上添花

---

## Phase 1 — 核心功能（估计 3-4h）

### 架构

```
┌──────────────────────────────────────────────────────────┐
│  SettingsReaderScreen 新增「运行 SR 性能测试」按钮         │
│  (TextPreference, 同 Export SR logs 模式)                │
└────────────────────────┬─────────────────────────────────┘
                         │ 点击
                         ▼
┌──────────────────────────────────────────────────────────┐
│  SRBenchmark.run(context, manager)                       │
│  1. 合成 300×400 测试图                                    │
│  2. 用当前活跃模型跑一次 process() 计时                     │
│  3. 返回 BenchmarkResult                                  │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│  DeviceProfileManager.saveResult(result)                 │
│  → 写入 SharedPreferences                                │
│  → 根据耗时分为 FAST / MID / SLOW 三档                    │
└────────────────────────┬─────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────┐
│  SRPreloadDispatcher / ReaderPageImageView 读取配置      │
│  → 动态设置 preloadWindow                                │
│  → 动态设置 maxAttempts                                  │
│  → SLOW tier 强制 preloadWindow=0 (禁用预加载)            │
└──────────────────────────────────────────────────────────┘
```

### 1.1 基准测试 — SRBenchmark

```kotlin
// core/superresolution/src/main/java/mihon/core/superresolution/benchmark/SRBenchmark.kt

class SRBenchmark(private val manager: SuperResolutionManager) {

    suspend fun run(progress: (String) -> Unit): BenchmarkResult {
        progress("正在准备测试图像…")
        val testBitmap = createTestImage()  // 800×1100 ARGB_8888

        progress("正在执行 SR 推理测试…")
        val startTime = System.nanoTime()
        val version = manager.currentModelVersion()
        val result = manager.process(testBitmap, version)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        testBitmap.recycle()
        if (result !== testBitmap) result.recycle()

        return BenchmarkResult(
            inferenceMs = elapsedMs,
            deviceTier = classifyTier(elapsedMs),
            scale = manager.activeScale,
            modelKey = manager.activeModel?.key,
        )
    }

    private fun classifyTier(ms: Long): DeviceTier = when {
        ms < 3000  -> DeviceTier.FAST
        ms < 8000  -> DeviceTier.MID
        else       -> DeviceTier.SLOW
    }
}

**为什么只测一次？** SR 性能主要取决于 GPU/NPU，波动小。测试图 800×1100 接近实际页面尺寸，触发约 6 个 tile（800/400×1100/400），~2-8s 完成，能反映真实 tiling 行为。

### 1.2 设备分级 — DeviceProfileManager

```kotlin
// core/superresolution/src/main/java/mihon/core/superresolution/profile/DeviceProfileManager.kt

enum class DeviceTier { FAST, MID, SLOW }

data class DeviceConfig(
    val preloadWindow: Int,       // 预加载页数
    val maxAttempts: Int,         // scheduleSrRefresh 最大轮询次数
)

// 映射规则 (写死在代码中, 不强制写入 preference)
FAST  (≤3s):   preloadWindow=5, maxAttempts=40   // 30s 内能完成约 5 页
MID   (3-8s):  preloadWindow=2, maxAttempts=30   // 24s 内完成约 3 页
SLOW  (>8s):   preloadWindow=0, maxAttempts=20   // 禁用预加载, 翻页不卡

**为什么 SLOW 阈值设在 8s？** 按实际页面 910×1290 推算 ≈ 800×1100 的 1.3 倍像素，SLOW 设备跑一页约 10s+，预加载队列只会让等待时间失控。
```

**为什么 SLOW 必须关预加载？**
- 每页 >6s → 预加载堆满 Mutex 队列 → 用户翻页后当前页排在第 N 个 → 等 N×6s
- 关闭预加载 → 翻页后只处理当前页 → 立即显示原图 → 后台慢慢出 SR 结果

### 1.3 SRPreloadDispatcher 改造

**关键发现**：`onPreloadRequested` 回调从未被赋值，`onPageChanged()` 启动的协程是空转（只打日志）。实际的"预加载"来自 ViewPager 创建 offscreen 页面时触发的 Coil 请求。

所以改造不需要改 preload 触发逻辑，只需要让 `preloadWindow` 可动态读取：

```kotlin
class SRPreloadDispatcher(...) {
    // private val preloadWindow = 5  →  删除硬编码常量
    private val profileManager = DeviceProfileManager(context)

    fun getPreloadWindow(): Int =
        profileManager.getConfig()?.preloadWindow ?: 5  // 无基准结果时默认 5

    fun onPageChanged(chapterId: Long, currentPageIndex: Int, totalPages: Int) {
        if (!manager.isReady) return
        val window = getPreloadWindow()
        if (window == 0) return  // SLOW tier: 不预加载
        val pagesToPreload = (currentPageIndex + 1)..minOf(currentPageIndex + window, totalPages - 1)
        // ... 后续逻辑不变
    }
}
```

### 1.4 ReaderPageImageView 改造

`scheduleSrRefresh` 中的 `maxAttempts` 从硬编码改为动态读取：

```kotlin
private fun scheduleSrRefresh(manager: SuperResolutionManager) {
    // ...
    val maxAttempts = DeviceProfileManager(context).getConfig()?.maxAttempts ?: 20
    // ...
}
```

### 1.5 UI

在 `SettingsReaderScreen.getSuperResolutionGroup()` 中新增 TextPreference：

```kotlin
Preference.PreferenceItem.TextPreference(
    title = "运行 SR 性能测试",
    subtitle = "测试设备 SR 速度，自动推荐最优预加载配置",
    onClick = {
        scope.launch {
            val context = LocalContext.current
            val manager = Injekt.get<SuperResolutionManager>()
            val benchmark = SRBenchmark(manager)
            val result = withContext(Dispatchers.Default) { benchmark.run() }
            DeviceProfileManager(context).saveResult(result)
            // 显示结果弹窗
            showBenchmarkResultDialog(context, result)
        }
    },
)
```

结果显示弹窗：
```
┌─────────────────────────────────────┐
│  SR 性能测试完成                      │
│                                      │
│  设备等级: FAST / MID / SLOW         │
│  推理耗时: 1234ms                     │
│  推荐预加载窗口: 2                    │
│                                      │
│  [应用推荐配置]  [关闭]               │
└─────────────────────────────────────┘
```

### 1.6 文件变更清单

| 文件 | 操作 | 行数 |
|------|------|------|
| `core/.../benchmark/SRBenchmark.kt` | 新建 | ~70 |
| `core/.../benchmark/BenchmarkResult.kt` | 新建 | ~25 |
| `core/.../profile/DeviceProfileManager.kt` | 新建 | ~50 |
| `core/.../profile/DeviceConfig.kt` | 新建 | ~15 |
| `core/.../SRPreloadDispatcher.kt` | 修改（动态 preloadWindow） | ~+10 |
| `app/.../reader/viewer/ReaderPageImageView.kt` | 修改（动态 maxAttempts） | ~+5 |
| `app/.../settings/screen/SettingsReaderScreen.kt` | 修改（加 benchmark TextPreference） | ~+40 |
| **合计** | | **~215** |

---

## Phase 2 — 增强功能（估计 2-3h）

### 2.1 自适应 Tile 尺寸

当前 JNI 层已根据可用 RAM 自动计算 tile_size。Phase 2 可选根据 tier 做缩放：

```
FAST  → 使用自动计算值（默认）
MID   → 使用自动计算值 × 0.75
SLOW  → 使用自动计算值 × 0.5
```

### 2.2 低电量/过热降级

```kotlin
电池 < 15% → 强制切换 SLOW 配置（preloadWindow=0）
恢复充电 → 恢复基准测试配置
```

通过 `BatteryManager.ACTION_BATTERY_CHANGED` 广播监听，不做主动轮询。

### 2.3 优先级队列（可选）

当前 `onPreloadRequested` 未接线，ViewPager 直接触发 Coil 为所有可见+邻近页面加载。若后续需要显式区分显示/预加载，需要：

1. 在 `SuperResolutionInterceptor` 中识别请求来源（通过 Coil request extra 参数标记优先级）
2. 在 `SuperResolutionManager` 中用双队列或 `Job.cancel` 机制让 DISPLAY 任务插队

Phase 1 暂不实现，因为：
- ViewPager 只会创建少量 offscreen 页面（通常 1-2 页）
- 配合禁用预加载后 SLOW/MID 的 Mutex 队列已经很短
- 需要额外机制标记 Coil 请求，引入复杂度

---

## 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 基准测试时机 | 用户手动触发 | 不影响首次启动体验 |
| 测试图 | 代码合成 800×1100 | 接近真实页面，触发 tiling |
| 分档数 | 3 档 | 再多收益递减 |
| 配置写入 | ReaderPreferences | 与现有系统一致，用户可覆盖 |
| preloadWindow | 运行时动态读取 | 不修改现有 ViewPager 行为 |
| 优先级队列 | Phase 2 | 现有架构无需即可获得大部分收益 |

## 不做的事情

- ❌ CPU/GPU/内存合成基准测试
- ❌ 运行时实时监控（cpuUsage/gpuUsage/thermalLevel）
- ❌ 自动切换模型/降噪（尊重用户选择）
- ❌ 最大并发数（Mutex 已串行化）
- ❌ ML 预测 / 云同步 / 历史分析
- ❌ 独立 benchmark 设置页（TextPreference 内联即可）
