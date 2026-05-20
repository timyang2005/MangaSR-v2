package eu.kanade.tachiyomi.data.sr

import android.graphics.Bitmap
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
import mihon.core.superresolution.ChapterMetadata
import mihon.core.superresolution.SRDiskCache
import mihon.core.superresolution.SRQueueItem
import mihon.core.superresolution.SRQueueStore
import mihon.core.superresolution.SuperResolutionManager
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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

            val files = chapterDir.listFiles().orEmpty()
                .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedBy { it.name }

            if (files.isEmpty()) {
                logcat(LogPriority.WARN) { "SR: No image files found in ch${item.chapterId}" }
                queue.removeFirst()
                persist()
                continue
            }

            val updatedItem = item.copy(totalPages = files.size)
            var processed = 0
            val version = manager.currentModelVersion()

            for (uniFile in files) {
                val pageIndex = processed
                val cacheKey = manager.buildCacheKey(item.chapterId, pageIndex)
                if (diskCache.get(cacheKey) != null) {
                    processed++
                    updateProgress(item.copy(processedPages = processed))
                    continue
                }

                try {
                    val input = uniFile.openInputStream().use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
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
                pageCount = files.size,
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
