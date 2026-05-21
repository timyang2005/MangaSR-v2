package eu.kanade.tachiyomi.data.sr

import android.content.Context
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
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
    private val context: Context,
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(SRQueueState())
    val state: StateFlow<SRQueueState> = _state.asStateFlow()

    private val queueMutex = Mutex()
    private val queue = mutableListOf<SRQueueItem>()
    private var running = false

    private val cancelledIds = mutableSetOf<Long>()

    init {
        scope.launch { restoreQueue() }
    }

    fun enqueue(chapters: List<tachiyomi.domain.chapter.model.Chapter>, mangaTitle: String, sourceKey: Long) {
        scope.launch {
            queueMutex.withLock {
                val newItems = chapters
                    .filter { c -> queue.none { it.chapterId == c.id } && !diskCache.contains(manager.buildCacheKey(c.id, 0)) }
                    .map { c -> SRQueueItem(c.id, c.mangaId, mangaTitle, c.name, sourceKey, 0, 0) }
                if (newItems.isEmpty()) return@withLock
                queue.addAll(newItems)
                persistLocked()
                _state.value = _state.value.copy(inProgress = queue.toList())
            }
            ensureRunning()
        }
    }

    fun cancel(chapterId: Long) {
        scope.launch {
            queueMutex.withLock {
                cancelledIds.add(chapterId)
                queue.removeAll { it.chapterId == chapterId }
                persistLocked()
                _state.value = _state.value.copy(inProgress = queue.toList())
            }
        }
    }

    fun cancelAll() {
        scope.launch {
            queueMutex.withLock {
                cancelledIds.addAll(queue.map { it.chapterId })
                queue.clear()
                persistLocked()
                _state.value = _state.value.copy(inProgress = emptyList())
            }
        }
    }

    private suspend fun restoreQueue() {
        queueMutex.withLock {
            queue.addAll(queueStore.load())
            _state.value = _state.value.copy(inProgress = queue.toList())
            if (queue.isNotEmpty()) ensureRunning()
        }
    }

    private fun persistLocked() {
        queueStore.save(queue.toList())
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        scope.launch { runLoop() }
    }

    private data class ImageSource(
        val name: String,
        val openStream: () -> java.io.InputStream,
    )

    private suspend fun runLoop() {
        while (true) {
            val item: SRQueueItem
            queueMutex.withLock {
                if (queue.isEmpty()) {
                    running = false
                    return
                }
                item = queue.first()
                _state.value = _state.value.copy(inProgress = queue.toList())
            }

            val chapter = runCatching { getChapter.await(item.chapterId) }.getOrNull()
            val manga = runCatching { getManga.await(item.mangaId) }.getOrNull()
            val source = manga?.let { sourceManager.get(it.source) }
            if (chapter == null || manga == null || source == null) {
                logcat(LogPriority.ERROR) { "SR: Queue item missing data, skipping ch${item.chapterId}" }
                queueMutex.withLock { queue.removeFirst(); persistLocked() }
                continue
            }

            val chapterDir = downloadProvider.findChapterDir(
                chapter.name, chapter.scanlator, chapter.url,
                manga.title, source,
            )

            if (chapterDir == null) {
                logcat(LogPriority.WARN) { "SR: Chapter dir not found, skipping ch${item.chapterId}" }
                queueMutex.withLock { queue.removeFirst(); persistLocked() }
                continue
            }

            val images: List<ImageSource>
            var archiveReader: ArchiveReader? = null
            try {
                if (chapterDir.isFile) {
                    archiveReader = chapterDir.archiveReader(context)
                    val entryNames = archiveReader.useEntries { entries ->
                        entries
                            .filter { it.isFile && ImageUtil.isImage(it.name) }
                            .sortedBy { it.name }
                            .map { it.name }
                            .toList()
                    }
                    images = entryNames.map { name ->
                        ImageSource(name) { archiveReader.getInputStream(name)!! }
                    }
                } else {
                    val files = chapterDir.listFiles().orEmpty()
                        .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
                        .sortedBy { it.name }
                    images = files.map { file ->
                        ImageSource(file.name!!) { file.openInputStream() }
                    }
                }

                if (images.isEmpty()) {
                    logcat(LogPriority.WARN) { "SR: No image files found in ch${item.chapterId}" }
                    queueMutex.withLock { queue.removeFirst(); persistLocked() }
                    continue
                }

                queueMutex.withLock {
                    val idx = queue.indexOfFirst { it.chapterId == item.chapterId }
                    if (idx >= 0) {
                        queue[idx] = queue[idx].copy(totalPages = images.size)
                        persistLocked()
                        _state.value = _state.value.copy(inProgress = queue.toList())
                    }
                }

                val version = manager.currentModelVersion()
                var processed = 0

                for (image in images) {
                    currentCoroutineContext().ensureActive()
                    if (queueMutex.withLock { item.chapterId in cancelledIds }) {
                        logcat(LogPriority.DEBUG) { "SR: Ch${item.chapterId} cancelled mid-processing at page $processed/${images.size}" }
                        break
                    }
                    val pageIndex = processed
                    val cacheKey = manager.buildCacheKey(item.chapterId, pageIndex)
                    if (diskCache.get(cacheKey) != null) {
                        processed++
                        updateProgress(item.copy(processedPages = processed))
                        continue
                    }

                    var input: Bitmap? = null
                    var result: Bitmap? = null
                    try {
                        input = image.openStream().use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                        if (input != null) {
                            result = manager.process(input, version)
                            if (result !== input) {
                                diskCache.put(cacheKey, result)
                            }
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "SR: Failed to process page $pageIndex ch${item.chapterId}\n${e.asLog()}" }
                    } finally {
                        input?.recycle()
                        if (result != null && result !== input) result.recycle()
                    }
                    processed++
                    updateProgress(item.copy(processedPages = processed))
                }

                queueMutex.withLock {
                    if (item.chapterId in cancelledIds) {
                        cancelledIds.remove(item.chapterId)
                        logcat(LogPriority.DEBUG) { "SR: Ch${item.chapterId} was cancelled, skipping metadata" }
                        return@withLock
                    }
                    queue.removeFirst()
                    persistLocked()
                    _state.value = _state.value.copy(
                        inProgress = queue.toList(),
                        completedCount = _state.value.completedCount + 1,
                    )
                }
                diskCache.putChapterMetadata(item.chapterId, ChapterMetadata(
                    mangaId = item.mangaId,
                    mangaTitle = item.mangaTitle,
                    chapterName = item.chapterName,
                    pageCount = images.size,
                ))
            } finally {
                archiveReader?.close()
            }
        }
    }

    private suspend fun updateProgress(item: SRQueueItem) {
        queueMutex.withLock {
            val idx = queue.indexOfFirst { it.chapterId == item.chapterId }
            if (idx >= 0) {
                queue[idx] = item
                persistLocked()
                _state.value = _state.value.copy(inProgress = queue.toList())
            }
        }
    }
}
