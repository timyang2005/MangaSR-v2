# 批量超分队列实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 subagent-driven-development（推荐）或 executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 允许用户选择已下载章节加入后台超分队列，处理完成后缓存到磁盘，阅读时 0 延迟显示 SR 结果。

**架构：** SRDiskCache 新增元数据管理方法 → SRQueueProcessor 逐章串行处理，通过 StateFlow 暴露进度 → MangaDetailPage 通过 `_ch_${chapterId}.json` 文件存在性判断 SR 状态 → 底部操作栏新增超分按钮 → 设置页弹窗管理队列。

**技术栈：** Kotlin Coroutines + SharedPreferences JSON + Coil + Compose

---

### 任务 1: SRDiskCache 扩展 — 元数据 + 按章节删除

**文件：**
- 修改：`core/superresolution/src/main/java/mihon/core/superresolution/SRDiskCache.kt`
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/ChapterMetadata.kt`

- [ ] **步骤 1: 创建 ChapterMetadata 数据类**

```kotlin
package mihon.core.superresolution

import kotlinx.serialization.Serializable

@Serializable
data class ChapterMetadata(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val pageCount: Int,
)
```

- [ ] **步骤 2: 在 SRDiskCache 新增 3 个方法**

```kotlin
fun putChapterMetadata(chapterId: Long, meta: ChapterMetadata) {
    val file = File(cacheDir, "_ch_${chapterId}.json")
    try {
        file.writeText(json.encodeToString(meta))
    } catch (e: Exception) {
        logcat(LogPriority.ERROR) { "SR: Failed to write chapter metadata for $chapterId\n${e.asLog()}" }
    }
}

fun getCompletedChapters(): List<Pair<Long, ChapterMetadata>> {
    return cacheDir.listFiles()
        ?.filter { it.name.startsWith("_ch_") && it.extension == "json" }
        ?.mapNotNull { file ->
            val chapterId = file.nameWithoutExtension.removePrefix("_ch_").toLongOrNull()
            if (chapterId == null) {
                logcat(LogPriority.WARN) { "SR: Invalid metadata filename ${file.name}" }
                null
            } else {
                try {
                    val meta = json.decodeFromString<ChapterMetadata>(file.readText())
                    chapterId to meta
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "SR: Failed to parse metadata ${file.name}\n${e.asLog()}" }
                    null
                }
            }
        } ?: emptyList()
}

fun removeChapter(chapterId: Long) {
    cacheDir.listFiles { _, name ->
        name.startsWith("page_${chapterId}_") || name == "_ch_${chapterId}.json"
    }?.forEach { it.delete() }
}
```

注意：`json` 变量需要注入，检查 SRDiskCache 已有 Json 实例或添加 `private val json: Json = Json { ignoreUnknownKeys = true }`。

同时 `evictIfNeeded` 应跳过 `_ch_*` 文件（极小，可忽略）：

```kotlin
private fun evictIfNeeded() {
    evictScope.launch {
        val files = cacheDir.listFiles()?.filter { !it.name.startsWith("_ch_") } ?: return@launch
        // ... rest unchanged
    }
}
```

并且在 `SRDiskCache` 添加 `contains(key)` 方法供快捷检查：

```kotlin
fun contains(key: String): Boolean = getFile(key).exists()
```

`getFile` 改为 `internal`：

```kotlin
internal fun getFile(key: String): File {
```

- [ ] **步骤 3: 编译验证**

运行：`gradlew :core:superresolution:compileDebugKotlin`
预期：BUILD SUCCESSFUL

---

### 任务 2: SRQueueStore — 进行中队列持久化

**文件：**
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/SRQueueStore.kt`

- [ ] **步骤 1: 创建 SRQueueStore**

```kotlin
package mihon.core.superresolution

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SRQueueItem(
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val sourceKey: Long,
    val totalPages: Int,
    val processedPages: Int = 0,
)

class SRQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("sr_queue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<SRQueueItem> {
        return prefs.all.mapNotNull { (_, v) ->
            try {
                json.decodeFromString<SRQueueItem>(v as String)
            } catch (_: Exception) { null }
        }
    }

    fun save(items: List<SRQueueItem>) {
        prefs.edit {
            clear()
            items.forEachIndexed { i, item ->
                putString(item.chapterId.toString(), json.encodeToString(item))
            }
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}
```

- [ ] **步骤 2: `build.gradle.kts` 添加 kotlinx-serialization 依赖**

检查 `core/superresolution/build.gradle.kts` 是否已有 `kotlinx-serialization` 插件和依赖。ChapterMetadata 需要用 `@Serializable`，所以确保：

```kotlin
plugins { kotlin("plugin.serialization") }
dependencies { implementation(libs.kotlinx.serialization.json) }
```

如果已有，跳过此步。

- [ ] **步骤 3: 编译验证**

运行：`gradlew :core:superresolution:compileDebugKotlin`
预期：BUILD SUCCESSFUL

---

### 任务 3: SRQueueProcessor — 队列处理器

**文件：**
- 创建：`core/superresolution/src/main/java/mihon/core/superresolution/SRQueueProcessor.kt`

依赖：
- `SuperResolutionManager`
- `SRDiskCache`
- `DownloadProvider`（来自 app 模块）
- `SourceManager`（用来通过 sourceKey 获取 Source）

因为 `DownloadProvider` 在 `app` 模块，而 `SRQueueProcessor` 在 `core/superresolution` 模块，需要调整位置。两种方案：
A. 把 `SRQueueProcessor` 放在 `app` 模块中
B. 把队列处理器拆成接口（core）和实现（app）

选 A 更简单。

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/sr/SRQueueProcessor.kt`

- [ ] **步骤 1: 创建 SRQueueProcessor**

```kotlin
package eu.kanade.tachiyomi.data.sr

import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.core.superresolution.SRQueueItem
import mihon.core.superresolution.SRQueueStore
import mihon.core.superresolution.ChapterMetadata
import mihon.core.superresolution.SRDiskCache
import mihon.core.superresolution.SuperResolutionManager
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

data class SRQueueState(
    val inProgress: List<SRQueueItem> = emptyList(),
    val completedCount: Int = 0,
)

class SRQueueProcessor(
    private val manager: SuperResolutionManager,
    private val diskCache: SRDiskCache,
    private val queueStore: SRQueueStore,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(SRQueueState())
    val state: StateFlow<SRQueueState> = _state.asStateFlow()

    private val queue = mutableListOf<SRQueueItem>()
    private var running = false

    init {
        restoreQueue()
    }

    fun enqueue(chapters: List<tachiyomi.domain.chapter.model.Chapter>, mangaTitle: String, sourceKey: Long) {
        val newItems = chapters
            .filter { c -> queue.none { it.chapterId == c.id } && !diskCache.contains(manager.buildCacheKey(c.id, 0)) }
            .map { c -> SRQueueItem(c.id, c.mangaId, mangaTitle, c.name, sourceKey, 0, 0) }
        if (newItems.isEmpty()) return
        queue.addAll(newItems)
        persist()
        _state.value = _state.value.copy(inProgress = queue.toList())
        ensureRunning()
    }

    fun cancel(chapterId: Long) {
        queue.removeAll { it.chapterId == chapterId }
        persist()
        _state.value = _state.value.copy(inProgress = queue.toList())
    }

    fun cancelAll() {
        queue.clear()
        persist()
        _state.value = _state.value.copy(inProgress = emptyList())
    }

    private fun restoreQueue() {
        queue.addAll(queueStore.load())
        _state.value = _state.value.copy(inProgress = queue.toList())
        if (queue.isNotEmpty()) ensureRunning()
    }

    private fun persist() {
        queueStore.save(queue.toList())
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (queue.isNotEmpty()) {
            val item = queue.first()
            _state.value = _state.value.copy(inProgress = queue.toList())

            val chapter = runCatching { getChapter.await(item.chapterId) }.getOrNull()
            val manga = runCatching { getManga.await(item.mangaId) }.getOrNull()
            val source = manga?.let { sourceManager.get(it.source) }
            if (chapter == null || manga == null || source == null) {
                logcat(LogPriority.ERROR) { "SR: Queue item missing data, skipping ch${item.chapterId}" }
                queue.removeFirst()
                persist()
                continue
            }

            val chapterDir = downloadProvider.findChapterDir(
                chapter.name, chapter.scanlator, chapter.url,
                manga.title, source,
            )
            if (chapterDir == null || chapterDir.isFile) {
                logcat(LogPriority.WARN) { "SR: Chapter dir not found or is archive, skipping ch${item.chapterId}" }
                queue.removeFirst()
                persist()
                continue
            }

            val imageFiles = chapterDir.listFiles()
                ?.filter { it.name.endsWith(".jpg") || it.name.endsWith(".png") || it.name.endsWith(".webp") }
                ?.sortedBy { it.name }
                ?: emptyList()

            if (imageFiles.isEmpty()) {
                logcat(LogPriority.WARN) { "SR: No image files found in ch${item.chapterId}" }
                queue.removeFirst()
                persist()
                continue
            }

            val updatedItem = item.copy(totalPages = imageFiles.size)
            var processed = 0
            val version = manager.currentModelVersion()

            for (file in imageFiles) {
                val pageIndex = processed
                val cacheKey = manager.buildCacheKey(item.chapterId, pageIndex)
                if (diskCache.get(cacheKey) != null) {
                    processed++
                    updateProgress(item.copy(processedPages = processed))
                    continue
                }

                try {
                    val input = BitmapFactory.decodeFile(file.absolutePath)
                    if (input != null) {
                        val result = manager.process(input, version)
                        if (result !== input) {
                            diskCache.put(cacheKey, result)
                        }
                        input.recycle()
                        if (result !== input) result.recycle()
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "SR: Failed to process page $pageIndex ch${item.chapterId}\n${e.asLog()}" }
                }
                processed++
                updateProgress(item.copy(processedPages = processed))
            }

            diskCache.putChapterMetadata(item.chapterId, ChapterMetadata(
                mangaId = item.mangaId,
                mangaTitle = item.mangaTitle,
                chapterName = item.chapterName,
                pageCount = imageFiles.size,
            ))

            queue.removeFirst()
            persist()
            _state.value = _state.value.copy(
                inProgress = queue.toList(),
                completedCount = _state.value.completedCount + 1,
            )
        }
        running = false
    }

    private fun updateProgress(item: SRQueueItem) {
        val idx = queue.indexOfFirst { it.chapterId == item.chapterId }
        if (idx >= 0) {
            queue[idx] = item
            persist()
            _state.value = _state.value.copy(inProgress = queue.toList())
        }
    }
}
```

注意：`SRDiskCache.getFile` 当前是 `private`，需要改为 `internal` 或 `public`：

```kotlin
// SRDiskCache.kt
internal fun getFile(key: String): File {
    val safeKey = key.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
    return File(cacheDir, "$safeKey.$ext")
}
```

改为 `internal` 或在 `SRQueueProcessor` 中直接用 `cacheDir`。

- [ ] **步骤 2: 编译验证**

运行：`gradlew :app:compileDebugKotlin`
预期：BUILD SUCCESSFUL

---

### 任务 4: 接入 SuperResolutionSync

**文件：**
- 修改：`app/src/main/java/eu/kanade/tachiyomi/data/sr/SuperResolutionSync.kt`

在 `start()` 中初始化 `SRQueueProcessor` 并暴露它：

```kotlin
class SuperResolutionSync(
    private val preferences: ReaderPreferences,
    private val manager: SuperResolutionManager,
    private val context: Context,
) {
    lateinit var queueProcessor: SRQueueProcessor
        private set

    fun start() {
        val diskCache = SRDiskCache(File(context.cacheDir, "sr_disk_cache"))
        val queueStore = SRQueueStore(context)
        queueProcessor = SRQueueProcessor(manager, diskCache, queueStore, 
            Injekt.get<DownloadProvider>())
        // ... existing combine logic unchanged
    }
}
```

`SuperResolutionSync` 的实例化位置需要检查是否已经传了 `Context`。

- [ ] **步骤 1: 修改 SuperResolutionSync**

检查现有构造参数，添加 `context: Context` 并初始化 `queueProcessor`。

- [ ] **步骤 2: 在 ReaderActivity.kt 或 Application 初始化处获取 queueProcessor**

`SuperResolutionSync.queueProcessor` 通过 Injekt 或逐层传递暴露给 ScreenModel。

- [ ] **步骤 3: 编译验证**

---

### 任务 5: 漫画详情页 — SR 状态图标

**修改文件：**
- `app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt`

在 Row 中，下载图标左边新增 sparkle 图标。需要一个新的参数 `hasSrCache: Boolean`。

- [ ] **步骤 1: 给 MangaChapterListItem 添加参数**

```kotlin
@Composable
fun MangaChapterListItem(
    // ... existing params ...
    hasSrCache: Boolean = false,
    // ...
)
```

- [ ] **步骤 2: 在 Row 中下载图标前插入 sparkle**

找到下载图标的渲染位置（后面的行），在前面加：

```kotlin
if (hasSrCache) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.ic_sr_sparkle_24dp),
        contentDescription = "SR",
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}
```

- [ ] **步骤 3: 在 MangaScreenModel 加载 chapter list 时获取已完成的 chapterId 集合**

```kotlin
// MangaScreenModel
private val completedSrChapters = mutableSetOf<Long>()

fun loadSrCacheStatus() {
    viewModelScope.launch {
        val diskCache = SRDiskCache(File(context.cacheDir, "sr_disk_cache"))
        val chapters = diskCache.getCompletedChapters()
        completedSrChapters.clear()
        completedSrChapters.addAll(chapters.map { it.first })
        // 通知 UI 刷新
    }
}
```

但 `MangaScreenModel` 需要访问 `SRDiskCache`，或者更简单的方式：通过 `SuperResolutionSync.queueProcessor` 获取。

- [ ] **步骤 4: 把 hasSrCache 传入 MangaChapterListItem**

在 `sharedChapterItems` 中：

```kotlin
hasSrCache = item.chapter.id in completedSrChapters,
```

- [ ] **步骤 5: 编译验证**

---

### 任务 6: 漫画详情页 — 底部操作栏超分按钮

**修改文件：**
- `app/src/main/java/eu/kanade/presentation/manga/components/MangaBottomActionMenu.kt`
- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`

- [ ] **步骤 1: MangaBottomActionMenu 新增 onBatchSrClicked 参数**

```kotlin
@Composable
fun MangaBottomActionMenu(
    // ... existing params ...
    onBatchSrClicked: (() -> Unit)? = null,
)
```

在 Row 中添加按钮（放 Download 和 Delete 之间）：

```kotlin
if (onBatchSrClicked != null) {
    Button(
        title = stringResource(MR.strings.action_sr_batch),
        icon = ImageVector.vectorResource(R.drawable.ic_sr_sparkle_24dp),
        toConfirm = confirm[7], // 注意 confirm 数组大小调整为 8
        onLongClick = { onLongClickItem(7) },
        onClick = onBatchSrClicked,
    )
}
```

需要新增 string resource `action_sr_batch` = "超分"。

- [ ] **步骤 2: SharedMangaBottomActionMenu 传递回调**

```kotlin
onBatchSrClicked = {
    screenModel.batchSrChapters(selected.fastMap { it.chapter })
}.takeIf { selected.fastAll { it.downloadState == Download.State.DOWNLOADED } },
```

只在所有选中章节都已下载时显示按钮。

- [ ] **步骤 3: MangaScreenModel 添加 batchSrChapters 方法**

```kotlin
fun batchSrChapters(chapters: List<Chapter>) {
    launchIO {
        val manga = requireNotNull(manga) // manga 已在 ScreenModel 中
        val srSync = Injekt.get<SuperResolutionSync>()
        val source = requireNotNull(manga.value?.source)
        srSync.queueProcessor.enqueue(chapters, manga.value!!.title, source)
        // toast via snackbar
    }
}
```

- [ ] **步骤 4: 编译验证**

---

### 任务 7: 设置页队列管理入口

**修改文件：**
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`

- [ ] **步骤 1: 在 SR 分组新增 TextPreference**

```kotlin
Preference.PreferenceItem.TextPreference(
    title = stringResource(MR.strings.pref_sr_batch_queue),
    subtitle = buildQueueSubtitle(queueState),
    onClick = { showQueueDialog = true },
)
```

- [ ] **步骤 2: 实现队列管理 Dialog**

```kotlin
@Composable
private fun SRQueueDialog(
    state: SRQueueState,
    completedChapters: List<Pair<Long, ChapterMetadata>>,
    onCancel: (Long) -> Unit,
    onDeleteCompleted: (List<Long>) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    // AlertDialog with LazyColumn
    // Section 1: in-progress items with progress bars
    // Section 2: completed items grouped by mangaTitle, with checkboxes
    // Bottom: [删除选中] [清空全部]
}
```

- [ ] **步骤 3: 读取队列状态和已完成列表**

```kotlin
val srSync = remember { Injekt.get<SuperResolutionSync>() }
val queueState by srSync.queueProcessor.state.collectAsState()
val completedChapters = remember { mutableStateOf<List<Pair<Long, ChapterMetadata>>>(emptyList()) }

// 在 Dialog 打开时刷新已完成列表
LaunchedEffect(showQueueDialog) {
    if (showQueueDialog) {
        val diskCache = SRDiskCache(File(context.cacheDir, "sr_disk_cache"))
        completedChapters.value = diskCache.getCompletedChapters()
    }
}
```

- [ ] **步骤 4: 编译验证**

---

### 任务 8: i18n 字符串

**文件：**
- 修改：`i18n/src/commonMain/moko-resources/base/strings.xml`
- 修改：`i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`

新增字符串：

```xml
<!-- 批量超分 -->
<string name="action_sr_batch">超分</string>
<string name="pref_sr_batch_queue">批量超分队列</string>
<string name="pref_sr_batch_queue_summary">%d 个处理中 · %d 个已完成</string>
<string name="sr_queue_delete_selected">删除选中</string>
<string name="sr_queue_clear_all">清空全部</string>
```

---

### 任务 9: 集成测试 & 验证

- [ ] **步骤 1: 编译整个项目**

```bash
gradlew :app:assembleDebug
```

- [ ] **步骤 2: 安装到设备/模拟器并测试**
   - 打开一本已下载的漫画 → 章节多选 → 验证「超分」按钮在有未下载章节时置灰
   - 选中全部已下载 → 点击超分 → 加入队列
   - 在设置页查看队列进度
   - 等待完成后回到漫画详情页 → 验证 sparkle 图标出现
   - 在设置页队列管理 → 删除选中 → 重新进入漫画页 → sparkle 消失
