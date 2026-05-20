# SR 动态性能调度 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现一键基准测试 → 自动分级（FAST/MID/SLOW）→ 动态调整 preloadWindow 和 maxAttempts

**架构：** SRBenchmark 先用合成图跑 SR 推理计时，可疑时用 asset 真实图回退；DeviceProfileManager 将结果持久化到 SharedPreferences；SRPreloadDispatcher 和 ReaderPageImageView 在运行时读取配置

**技术栈：** Android Kotlin, Bitmap, SharedPreferences, Compose (settings UI)

**相关文档：** `docs/benchmark-design.md`

---

### 任务 1：数据类 — BenchmarkResult & DeviceConfig

**文件：**
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/benchmark/BenchmarkResult.kt`
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/profile/DeviceConfig.kt`

- [ ] **步骤 1：创建 BenchmarkResult.kt**

```kotlin
package mihon.core.superresolution.benchmark

enum class DeviceTier {
    FAST, MID, SLOW, UNKNOWN
}

data class BenchmarkResult(
    val inferenceMs: Long = -1,
    val deviceTier: DeviceTier = DeviceTier.UNKNOWN,
    val scale: Int = 2,
    val modelKey: String? = null,
)
```

- [ ] **步骤 2：创建 DeviceConfig.kt**

```kotlin
package mihon.core.superresolution.profile

data class DeviceConfig(
    val preloadWindow: Int,
    val maxAttempts: Int,
)
```

- [ ] **步骤 3：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/benchmark/BenchmarkResult.kt core/superresolution/src/main/java/mihon/core/superresolution/profile/DeviceConfig.kt
git commit -m "feat(benchmark): add BenchmarkResult, DeviceTier, DeviceConfig data classes"
```

---

### 任务 2：基准测试引擎 — SRBenchmark

**文件：**
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/benchmark/SRBenchmark.kt`
- 资源：`core/superresolution/src/main/assets/benchmark_sample.png`（已复制）

- [ ] **步骤 1：创建 SRBenchmark.kt**

```kotlin
package mihon.core.superresolution.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import mihon.core.superresolution.SuperResolutionManager

class SRBenchmark(private val manager: SuperResolutionManager) {

    suspend fun run(context: Context): BenchmarkResult {
        val synthetic = createSyntheticImage()
        val (ms, result, srDidRun) = runOnce(synthetic)
        synthetic.recycle()

        if (!srDidRun) {
            result.recycle()
            return BenchmarkResult(deviceTier = DeviceTier.UNKNOWN)
        }

        val finalMs = if (ms < 300) {
            val real = loadAssetImage(context, "benchmark_sample.png")
            val (ms2, result2, ok2) = runOnce(real)
            real.recycle()
            result.recycle()
            if (!ok2) { result2.recycle(); return BenchmarkResult(deviceTier = DeviceTier.UNKNOWN) }
            result2.recycle()
            ms2
        } else {
            result.recycle()
            ms
        }

        return BenchmarkResult(
            inferenceMs = finalMs,
            deviceTier = classifyTier(finalMs),
            scale = manager.activeScale,
            modelKey = manager.activeModel?.key,
        )
    }

    private suspend fun runOnce(input: Bitmap): Triple<Long, Bitmap, Boolean> {
        val version = manager.currentModelVersion()
        val scale = manager.activeScale
        val startTime = System.nanoTime()
        val result = manager.process(input, version)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        val srDidRun = result.width == input.width * scale
                    && result.height == input.height * scale
        return Triple(elapsedMs, result, srDidRun)
    }

    private fun classifyTier(ms: Long): DeviceTier = when {
        ms < 3000  -> DeviceTier.FAST
        ms < 8000  -> DeviceTier.MID
        else       -> DeviceTier.SLOW
    }

    private fun createSyntheticImage(width: Int = 800, height: Int = 1100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.LTGRAY, Color.DKGRAY, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.shader = shader })
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        for (i in 1 until 10) {
            val x = i * width / 10f
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }
        for (i in 1 until 10) {
            val y = i * height / 10f
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }
        return bitmap
    }

    private fun loadAssetImage(context: Context, filename: String): Bitmap {
        return context.assets.open(filename).use { BitmapFactory.decodeStream(it) }
    }
}
```

`process()` 签名参考 `SuperResolutionManager.kt:162`：
```kotlin
suspend fun process(input: Bitmap, versionAtStart: Long): Bitmap
```

- [ ] **步骤 2：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/benchmark/SRBenchmark.kt
git commit -m "feat(benchmark): add SRBenchmark with synthetic+real fallback"
```

---

### 任务 3：配置持久化 — DeviceProfileManager

**文件：**
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/profile/DeviceProfileManager.kt`

- [ ] **步骤 1：创建 DeviceProfileManager.kt**

```kotlin
package mihon.core.superresolution.profile

import android.content.Context
import mihon.core.superresolution.benchmark.BenchmarkResult
import mihon.core.superresolution.benchmark.DeviceTier

class DeviceProfileManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("sr_benchmark", Context.MODE_PRIVATE)

    fun saveResult(result: BenchmarkResult) {
        prefs.edit()
            .putString(KEY_TIER, result.deviceTier.name)
            .putLong(KEY_INFERENCE_MS, result.inferenceMs)
            .putInt(KEY_SCALE, result.scale)
            .putString(KEY_MODEL, result.modelKey)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getConfig(): DeviceConfig? {
        val tierName = prefs.getString(KEY_TIER, null) ?: return null
        val tier = try { DeviceTier.valueOf(tierName) } catch (_: Exception) { return null }
        return when (tier) {
            DeviceTier.FAST   -> DeviceConfig(preloadWindow = 5, maxAttempts = 40)
            DeviceTier.MID    -> DeviceConfig(preloadWindow = 2, maxAttempts = 30)
            DeviceTier.SLOW   -> DeviceConfig(preloadWindow = 0, maxAttempts = 20)
            DeviceTier.UNKNOWN -> null
        }
    }

    fun hasResult(): Boolean = prefs.contains(KEY_TIER)

    fun getResult(): BenchmarkResult? {
        val tierName = prefs.getString(KEY_TIER, null) ?: return null
        val tier = try { DeviceTier.valueOf(tierName) } catch (_: Exception) { return null }
        return BenchmarkResult(
            deviceTier = tier,
            inferenceMs = prefs.getLong(KEY_INFERENCE_MS, -1),
            scale = prefs.getInt(KEY_SCALE, 2),
            modelKey = prefs.getString(KEY_MODEL, null),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TIER = "tier"
        private const val KEY_INFERENCE_MS = "inference_ms"
        private const val KEY_SCALE = "scale"
        private const val KEY_MODEL = "model"
        private const val KEY_TIMESTAMP = "timestamp"
    }
}
```

注意：`DeviceProfileManager` 从 `sr_benchmark`（独立 SharedPreferences 文件，不混入 ReaderPreferences）读取 tier 信息，映射到硬编码的配置参数。

- [ ] **步骤 2：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/profile/DeviceProfileManager.kt
git commit -m "feat(benchmark): add DeviceProfileManager for config persistence"
```

---

### 任务 4：修改 SRPreloadDispatcher — 动态 preloadWindow

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/SRPreloadDispatcher.kt`

- [ ] **步骤 1：修改 SRPreloadDispatcher.kt**

读取 `ReaderPageImageView.kt:177-200`（当前 scheduleSrRefresh）确认 maxAttempts 的读取位置。

改动：
1. 删除 `private val preloadWindow = 5`（第 18 行）
2. 添加 `private val profileManager = DeviceProfileManager(context)` 属性
3. 添加 `getPreloadWindow()` 方法
4. 修改 `onPageChanged()` 使用动态值

```kotlin
package mihon.core.superresolution

// ... 现有 imports ...
import mihon.core.superresolution.profile.DeviceProfileManager

class SRPreloadDispatcher(
    private val manager: SuperResolutionManager,
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profileManager = DeviceProfileManager(context)
    // private val preloadWindow = 5   ← 删除
    private val diskCache = SRDiskCache(File(context.cacheDir, "sr_disk_cache"))
    private val preloadingPages = mutableSetOf<String>()

    var onPreloadRequested: ((chapterId: Long, pageIndex: Int) -> Unit)? = null

    fun getPreloadWindow(): Int =
        profileManager.getConfig()?.preloadWindow ?: 5

    fun onPageChanged(chapterId: Long, currentPageIndex: Int, totalPages: Int) {
        if (!manager.isReady) return

        val window = getPreloadWindow()
        if (window == 0) return

        val pagesToPreload = (currentPageIndex + 1)..minOf(currentPageIndex + window, totalPages - 1)
        // ... 后续代码不变 ...
    }

    // ... 其余代码不变 ...
}
```

- [ ] **步骤 2：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/SRPreloadDispatcher.kt
git commit -m "feat(benchmark): dynamic preloadWindow from DeviceProfileManager, SLOW tier disables preload"
```

---

### 任务 5：修改 ReaderPageImageView — 动态 maxAttempts

**文件：**
- 修改：`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt`

- [ ] **步骤 1：在 `scheduleSrRefresh` 中动态读取 maxAttempts**

关键位置：`ReaderPageImageView.kt:196-197`（`val maxAttempts = 20`）

```kotlin
// 在文件顶部 imports 区域添加：
import mihon.core.superresolution.profile.DeviceProfileManager

// 在 scheduleSrRefresh 方法内：
private fun scheduleSrRefresh(manager: SuperResolutionManager) {
    cancelSrRefresh()
    val page = readerPage ?: return
    val chId = page.chapter.chapter.id ?: -1L

    onSrStatusChanged?.invoke(false)
    srStartTimestamp = System.currentTimeMillis()

    var attempts = 0
    val maxAttempts = DeviceProfileManager(context).getConfig()?.maxAttempts ?: 20
    // ... 后续代码不变 ...
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt
git commit -m "feat(benchmark): dynamic maxAttempts from DeviceProfileManager"
```

---

### 任务 6：设置界面 — 运行基准测试按钮 + 结果弹窗

**文件：**
- 修改：`app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`

- [ ] **步骤 1：在 `getSuperResolutionGroup` 中添加 TextPreference**

位置：`getSuperResolutionGroup()` 方法内部，`Preference.PreferenceGroup` 的 `preferenceItems` 列表末尾（在 `Export SR logs` 之后）。

```kotlin
// 文件顶部添加 imports:
import android.app.AlertDialog
import android.content.DialogInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.superresolution.SuperResolutionManager
import mihon.core.superresolution.benchmark.DeviceTier
import mihon.core.superresolution.benchmark.SRBenchmark
import mihon.core.superresolution.profile.DeviceProfileManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// 在 Preference.PreferenceItem.TextPreference("Export SR logs") 之后添加:
Preference.PreferenceItem.TextPreference(
    title = stringResource(MR.strings.pref_sr_benchmark),
    subtitle = stringResource(MR.strings.pref_sr_benchmark_summary),
    onClick = {
        scope.launch {
            val ctx = context
            val manager = Injekt.get<SuperResolutionManager>()
            if (!manager.isReady) {
                showToast(ctx, "SR 引擎未就绪")
                return@launch
            }
            val benchmark = SRBenchmark(manager)
            val result = withContext(Dispatchers.Default) { benchmark.run(ctx) }
            DeviceProfileManager(ctx).saveResult(result)
            showBenchmarkResultDialog(ctx, result, readerPreferences)
        }
    },
),
```

- [ ] **步骤 2：添加弹窗函数和 Toast 辅助函数**

在 `getSuperResolutionGroup` 函数内部（或作为 private 顶级函数）添加：

```kotlin
private fun showToast(context: Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

private fun showBenchmarkResultDialog(
    context: Context,
    result: mihon.core.superresolution.benchmark.BenchmarkResult,
    readerPreferences: eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences,
) {
    val tierLabel = when (result.deviceTier) {
        DeviceTier.FAST -> "FAST（高性能）"
        DeviceTier.MID -> "MID（中端）"
        DeviceTier.SLOW -> "SLOW（低端）"
        DeviceTier.UNKNOWN -> "无法测试"
    }
    val message = buildString {
        appendLine("设备等级: $tierLabel")
        appendLine("推理耗时: ${result.inferenceMs}ms")
        if (result.deviceTier != DeviceTier.UNKNOWN) {
            val config = mihon.core.superresolution.profile.DeviceProfileManager(context).getConfig()
            if (config != null) {
                appendLine("推荐预加载窗口: ${config.preloadWindow}")
                appendLine("推荐轮询超时: ${config.maxAttempts} 次 (${config.maxAttempts * 500}ms)")
            }
        }
    }

    AlertDialog.Builder(context)
        .setTitle("SR 性能测试完成")
        .setMessage(message)
        .setPositiveButton("应用推荐配置") { _: DialogInterface, _: Int ->
            val config = mihon.core.superresolution.profile.DeviceProfileManager(context).getConfig()
            if (config != null) {
                readerPreferences.srPreloadCount.set(config.preloadWindow)
                showToast(context, "预加载窗口已设置为 ${config.preloadWindow}")
            }
        }
        .setNegativeButton("关闭", null)
        .show()
}
```

- [ ] **步骤 3：在 strings 资源中添加新 key**

在 `i18n/src/commonMain/moko-resources/base/strings.xml` 的 `<!-- Super Resolution -->` 区域末尾（第 428 行后）添加：

```xml
    <string name="pref_sr_benchmark">Run SR Benchmark</string>
    <string name="pref_sr_benchmark_summary">Test device SR speed and auto-tune settings</string>
```

同时在 `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml` 对应位置添加：

```xml
    <string name="pref_sr_benchmark">运行 SR 性能测试</string>
    <string name="pref_sr_benchmark_summary">测试设备 SR 处理速度，自动推荐最优配置</string>
```

- [ ] **步骤 4：在 SettingsReaderScreen 中实现弹窗和 apply 逻辑**

```bash
git add app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt i18n/src/commonMain/moko-resources/base/strings.xml
git commit -m "feat(benchmark): add benchmark button + result dialog in SR settings"
```

---

### 构建验证

- [ ] **最终：编译验证 + 推送**

```bash
./gradlew :app:assembleDev6819Debug
git push
```

### 回退计划

如果某个步骤导致编译失败：
- 检查 import 路径是否正确（尤其是 `mihon.core.superresolution.benchmark` 和 `mihon.core.superresolution.profile` 包名）
- 确认 `context.getSharedPreferences` 在 library module 中可用（`core/superresolution` 是 Android library，有 Android Context）
- 确认 `AssetManager` 路径：assets 文件在 `src/main/assets/benchmark_sample.png`，通过 `context.assets.open("benchmark_sample.png")` 访问
- `strings.xml` 中的 key 需要通过 `stringResource(MR.strings.pref_sr_benchmark)` 引用，确认 moko-resources 生成正确
