# SR 性能优化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 通过 8 项独立优化减少 SR 结果显示延迟，保持阅读连贯性，并修复已确认的性能/内存问题。

**架构：**
- **Kotlin 层**（Tasks 1-5、7、8）：延迟模型加载、轮询加速、WEBP 缓存、异步驱逐、flow 泄漏修复、缓存键安全、GPU 计数缓存——全部在现有类内修改，不改变架构边界。
- **C++ 层**（Task 6、9）：分离轴形态学（`manga_bw_postprocessor.cpp`）和 NCNN 多线程（`realesrgan_wrapper.cpp`）——纯性能优化，行为等价。

**技术栈：** Kotlin/Coroutines、Android SDK、C++17、NCNN、ARM NEON

---

### 任务 1：A1 — 延迟模型加载

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/SuperResolutionManager.kt:102-160`

- [ ] **步骤 1：阅读现有 `switchModel()` 和 `process()` 的完整实现，理解当前模型加载流程**

- [ ] **步骤 2：添加 `pendingModelKey` 字段并修改 `switchModel()`**

```kotlin
// 新增字段（放在 modelVersion 下面，第 44 行之后）
private var pendingModelKey: String? = null

// 修改 switchModel()：取消锁内模型加载，改为设标志
suspend fun switchModel(
    model: SRModel,
    scale: Int = 2,
    denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
    bwConfig: MangaBWPostProcessConfig? = null,
) {
    // 检查同模型/同比例快速路径（无需锁）
    if (currentModel == model && currentScale == scale && currentProcessor?.isReady == true) {
        currentDenoiseLevel = denoiseLevel
        currentBwConfig = bwConfig
        logcat(LogPriority.DEBUG) { "SR: Same model and scale, updating denoise/bw config" }
        return
    }

    pendingModelKey = model.key
    cancelAllProcessingJobs()
    onModelSwitching?.invoke()

    // 更新配置
    currentDenoiseLevel = denoiseLevel
    currentBwConfig = bwConfig
    currentScale = scale

    // 增量 modelVersion 使进行中的 process() 放弃结果
    modelVersion++

    logcat(LogPriority.INFO) { "SR: switchModel pending: model=${model.key}, scale=$scale" }
    // 注：不获取 mutex，不加载模型，立即返回
}
```

- [ ] **步骤 3：在 `process()` 开头添加按需模型加载逻辑**

```kotlin
suspend fun process(input: Bitmap, versionAtStart: Long): Bitmap = mutex.withLock {
    // 按需加载待切换模型
    val pending = pendingModelKey
    if (pending != null && (currentModel == null || currentModel?.key != pending)) {
        logcat(LogPriority.INFO) { "SR: Deferred model load: $pending" }
        currentProcessor?.release()
        currentProcessor = null
        currentModel = null

        val model = SRModel.fromKey(pending) ?: return@withLock input
        val processor = createProcessor(model)
        val modelPath = getModelPath(model)

        withContext(Dispatchers.IO) {
            try {
                val gpuid = if (isVulkanAvailable && model.requiresVulkan) 0 else -1
                processor.initialize(modelPath, gpuid)
                currentProcessor = processor
                currentModel = model
                logcat(LogPriority.INFO) { "SR: Deferred model load complete: ${model.key}" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "SR: Deferred model load failed for ${model.key}\n${e.asLog()}" }
                processor.release()
                currentProcessor = NoOpProcessor()
                currentModel = model
            }
        }
        pendingModelKey = null
    }

    // ... 原有 process 逻辑 ...
```

- [ ] **步骤 4：验证编译通过**

运行：`./gradlew :core:superresolution:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/SuperResolutionManager.kt
git commit -m "perf: deferred model loading - switchModel is instant, load on first process"
```

---

### 任务 2：A2 — 轮询加速

**文件：**
- 修改：`app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt:224`

- [ ] **步骤 1：首次检查延迟 500ms → 0ms**

```kotlin
// 第 224 行：首次立即检查而非等待 500ms
postDelayed(runnable, 0)  // 原来是 SR_REFRESH_INTERVAL_MS (500L)
```

- [ ] **步骤 2：缩短轮询间隔 500ms → 300ms**

```kotlin
// 第 500 行
private const val SR_REFRESH_INTERVAL_MS = 300L  // 原来是 500L
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt
git commit -m "perf: reduce SR polling - first check at 0ms, interval 300ms"
```

---

### 任务 3：A3 — WEBP 磁盘缓存

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/SRDiskCache.kt:34-36`

- [ ] **步骤 1：增加 WebP/JPEG 压缩路径**

```kotlin
// 第 31-36 行的 put() 方法
fun put(key: String, bitmap: Bitmap) {
    val file = getFile(key)
    try {
        FileOutputStream(file).use { out ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
    } catch (e: Exception) {
        logcat(LogPriority.ERROR) { "SR: Failed to write disk cache for $key\n${e.asLog()}" }
    }
    evictIfNeeded()
}
```

- [ ] **步骤 2：更新文件扩展名以匹配实际格式**

```kotlin
// 第 52-55 行的 getFile() 方法
private fun getFile(key: String): File {
    val safeKey = key.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
    return File(cacheDir, "$safeKey.$ext")
}
```

- [ ] **步骤 3：添加 import（如果缺失）**

```kotlin
// 文件顶部新增
import android.os.Build
```

- [ ] **步骤 4：编译验证**

运行：`./gradlew :core:superresolution:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/SRDiskCache.kt
git commit -m "perf: use WEBP_LOSSY for disk cache (Android 11+) with JPEG fallback"
```

---

### 任务 4：A4 — 异步缓存驱逐

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/SRDiskCache.kt:14-68`

- [ ] **步骤 1：在 SRDiskCache 中添加协程 scope 并将驱逐异步化**

```kotlin
// 第 1 行新增 import
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 在类体内新增 scope（第 14 行之后，maxCacheSizeBytes 下方）
private val evictScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// 修改 evictIfNeeded()（第 57-70 行）
private fun evictIfNeeded() {
    evictScope.launch {
        val files = cacheDir.listFiles() ?: return@launch
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxCacheSizeBytes) return@launch
        files.sortByDescending { it.lastModified() }
        var index = files.size - 1
        while (totalSize > maxCacheSizeBytes && index >= 0) {
            totalSize -= files[index].length()
            files[index].delete()
            index--
        }
        logcat(LogPriority.DEBUG) { "SR: Evicted ${files.size - 1 - index} cache files" }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`./gradlew :core:superresolution:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/SRDiskCache.kt
git commit -m "perf: async cache eviction to avoid blocking write path"
```

---

### 任务 5：A5 — 修复 srResultFlow 泄漏

**文件：**
- 修改：`app/src/main/java/eu/kanade/tachiyomi/data/coil/SuperResolutionInterceptor.kt:38`

- [ ] **步骤 1：将缓冲区容量设为 0**

```kotlin
// 第 38 行
val srResultFlow = MutableSharedFlow<SRResult>(
    extraBufferCapacity = 0,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/coil/SuperResolutionInterceptor.kt
git commit -m "fix: remove srResultFlow buffer (64 Bitmap refs leaked, no collector)"
```

---

### 任务 6：B2 — 分离轴形态学

**文件：**
- 修改：`core/superresolution/src/main/cpp/manga_bw_postprocessor.cpp:96-138`

- [ ] **步骤 1：将 `morphErode` 替换为分离轴实现**

```cpp
// 替换第 96-116 行
static void morphErode(unsigned char* data, int width, int height, int radius) {
    if (radius <= 0) return;
    std::vector<unsigned char> temp(width * height);

    // Step 1: 水平方向腐蚀
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned char min_val = 255;
            int start_x = std::max(0, x - radius);
            int end_x = std::min(width - 1, x + radius);
            for (int dx = start_x; dx <= end_x; dx++) {
                min_val = std::min(min_val, data[y * width + dx]);
            }
            temp[y * width + x] = min_val;
        }
    }

    // Step 2: 垂直方向腐蚀
    for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
            unsigned char min_val = 255;
            int start_y = std::max(0, y - radius);
            int end_y = std::min(height - 1, y + radius);
            for (int dy = start_y; dy <= end_y; dy++) {
                min_val = std::min(min_val, temp[dy * width + x]);
            }
            data[y * width + x] = min_val;
        }
    }
}
```

- [ ] **步骤 2：将 `morphDilate` 替换为分离轴实现**

```cpp
// 替换第 118-138 行
static void morphDilate(unsigned char* data, int width, int height, int radius) {
    if (radius <= 0) return;
    std::vector<unsigned char> temp(width * height);

    // Step 1: 水平方向膨胀
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned char max_val = 0;
            int start_x = std::max(0, x - radius);
            int end_x = std::min(width - 1, x + radius);
            for (int dx = start_x; dx <= end_x; dx++) {
                max_val = std::max(max_val, data[y * width + dx]);
            }
            temp[y * width + x] = max_val;
        }
    }

    // Step 2: 垂直方向膨胀
    for (int x = 0; x < width; x++) {
        for (int y = 0; y < height; y++) {
            unsigned char max_val = 0;
            int start_y = std::max(0, y - radius);
            int end_y = std::min(height - 1, y + radius);
            for (int dy = start_y; dy <= end_y; dy++) {
                max_val = std::max(max_val, temp[dy * width + x]);
            }
            data[y * width + x] = max_val;
        }
    }
}
```

- [ ] **步骤 3：验证编译（需要 NDK）**

运行：`cd core/superresolution && ./gradlew :core:superresolution:build`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core/superresolution/src/main/cpp/manga_bw_postprocessor.cpp
git commit -m "perf: separable-axis morphology - O(w*h*r) vs O(w*h*r²), 60-70% faster"
```

---

### 任务 7：B3 — NCNN 多线程配置

**文件：**
- 修改：`core/superresolution/src/main/cpp/realesrgan_wrapper.cpp:80`

- [ ] **步骤 1：在 Vulkan 初始化后添加 `num_threads`**

```cpp
// 第 80 行之后，第 83 行之前
net.opt.use_vulkan_compute = true;
int device_id = (gpuid < ncnn::get_gpu_count()) ? gpuid : ncnn::get_default_gpu_index();
net.set_vulkan_device(device_id);
net.opt.num_threads = 4;  // CPU 预处理辅助线程数 = SoC 大核数
```

- [ ] **步骤 2：验证编译**

运行：`cd core/superresolution && ./gradlew :core:superresolution:build`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core/superresolution/src/main/cpp/realesrgan_wrapper.cpp
git commit -m "perf: set ncnn num_threads=4 for CPU preprocessing parallel"
```

---

### 任务 8：B5a — 缓存键 null 安全

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/SRPreloadDispatcher.kt:78-80`
- 修改：`app/src/main/java/eu/kanade/tachiyomi/data/coil/SuperResolutionInterceptor.kt:62-68`

- [ ] **步骤 1：修复 SRPreloadDispatcher 的 buildCacheKey**

```kotlin
// 第 78-80 行
private fun buildCacheKey(chapterId: Long, pageIndex: Int): String {
    val modelKey = manager.activeModel?.key ?: "unknown"
    return "page_${chapterId}_${pageIndex}_${modelKey}_${manager.activeScale}"
}
```

- [ ] **步骤 2：修复 SuperResolutionInterceptor 中的 cacheKey 构建**

```kotlin
// 第 62-68 行
val cacheKey = if (pageIndex >= 0 && chapterId >= 0) {
    "page_${chapterId}_${pageIndex}_${manager.activeModel?.key ?: "unknown"}_${manager.activeScale}"
} else if (pageIndex >= 0) {
    "page_${pageIndex}_${manager.activeModel?.key ?: "unknown"}_${manager.activeScale}"
} else {
    "${bitmap.width}x${bitmap.height}_${System.identityHashCode(bitmap)}_${manager.activeModel?.key ?: "unknown"}_${manager.activeScale}"
}
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :core:superresolution:compileDebugKotlin :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/SRPreloadDispatcher.kt app/src/main/java/eu/kanade/tachiyomi/data/coil/SuperResolutionInterceptor.kt
git commit -m "fix: cache key null safety - fallback to 'unknown' when model not loaded"
```

---

### 任务 9：B5b — GPU 计数懒加载

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/VulkanHelper.kt:20-32`

- [ ] **步骤 1：将 `getGpuCount()` 改为 `lazy` 属性**

```kotlin
// 第 20-32 行
val gpuCount by lazy {
    try {
        val count = nativeGetGpuCount()
        logcat(LogPriority.INFO) { "VulkanHelper: nativeGetGpuCount=$count" }
        count
    } catch (e: UnsatisfiedLinkError) {
        logcat(LogPriority.ERROR) { "VulkanHelper: nativeGetGpuCount failed (native lib not loaded): ${e.message}" }
        0
    } catch (e: Exception) {
        logcat(LogPriority.ERROR) { "VulkanHelper: nativeGetGpuCount failed: ${e.message}" }
        0
    }
}
```

- [ ] **步骤 2：更新引用处（如果 `getGpuCount()` 被外部以方法形式调用，需要改为属性访问）**

搜索项目中对 `VulkanHelper.getGpuCount()` 的引用：
```bash
rg "getGpuCount\(\)" --type kotlin
```

如果存在引用，改为 `VulkanHelper.gpuCount`。

- [ ] **步骤 3：编译验证**

运行：`./gradlew :core:superresolution:compileDebugKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core/superresolution/src/main/java/mihon/core/superresolution/VulkanHelper.kt
git commit -m "perf: cache GPU count via lazy (avoids repeated JNI calls)"
```
