package eu.kanade.tachiyomi.data.sr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.PermissionManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val _running = AtomicBoolean(false)

    val isRunning: Boolean
        get() = _running.get()

    private val cancelledIds = mutableSetOf<Long>()

    init {
        scope.launch { restoreQueue() }
    }

    fun enqueue(chapters: List<tachiyomi.domain.chapter.model.Chapter>, mangaTitle: String, sourceKey: Long, sourceName: String = "") {
        scope.launch {
            queueMutex.withLock {
                val newItems = chapters
                    .filter { c -> queue.none { it.chapterId == c.id } }
                    .map { c -> SRQueueItem(c.id, c.mangaId, mangaTitle, c.name, sourceKey, sourceName, 0, 0) }
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
        if (_running.get()) return
        SRJob.start(context)
    }

    fun start() {
        if (!_running.compareAndSet(false, true)) return
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
                    _running.set(false)
                    showCompletionNotification()
                    return
                }
                item = queue.first()
                _state.value = _state.value.copy(inProgress = queue.toList())
            }

            showProgressNotification(item)

            val chapter = runCatching { getChapter.await(item.chapterId) }.getOrNull()
            val manga = runCatching { getManga.await(item.mangaId) }.getOrNull()
            val source = manga?.let { sourceManager.get(it.source) }
            if (chapter == null || manga == null || source == null) {
                logcat(LogPriority.ERROR) { "SR: Queue item missing data, skipping ch${item.chapterId}" }
                queueMutex.withLock { queue.removeFirst(); persistLocked() }
                continue
            }

            val sourceName = downloadProvider.getSourceDirName(source)

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
                        queue[idx] = queue[idx].copy(totalPages = images.size, sourceName = sourceName)
                        persistLocked()
                        _state.value = _state.value.copy(inProgress = queue.toList())
                    }
                }

                val version = manager.currentModelVersion()
                var processed = 0
                var cancelledMidProcessing = false

                for (image in images) {
                    currentCoroutineContext().ensureActive()
                    if (queueMutex.withLock { item.chapterId in cancelledIds }) {
                        cancelledMidProcessing = true
                        logcat(LogPriority.DEBUG) { "SR: Ch${item.chapterId} cancelled mid-processing at page $processed/${images.size}" }
                        break
                    }
                    val pageIndex = processed
                    if (diskCache.getBatchPage(sourceName, item.mangaTitle, item.chapterId, pageIndex) != null) {
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
                                diskCache.putBatchPage(sourceName, item.mangaTitle, item.chapterId, pageIndex, result)
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
                    }
                    if (queue.isNotEmpty() && queue.first().chapterId == item.chapterId) {
                        queue.removeFirst()
                    }
                    persistLocked()
                    if (!cancelledMidProcessing) {
                        _state.value = _state.value.copy(
                            inProgress = queue.toList(),
                            completedCount = _state.value.completedCount + 1,
                        )
                    } else {
                        _state.value = _state.value.copy(inProgress = queue.toList())
                    }
                }
                if (cancelledMidProcessing) {
                    logcat(LogPriority.DEBUG) { "SR: Ch${item.chapterId} removing partial batch cache" }
                    diskCache.removeBatchChapter(sourceName, item.mangaTitle, item.chapterId)
                } else {
                    diskCache.putBatchMetadata(
                        sourceName, item.mangaTitle, item.chapterId,
                        ChapterMetadata(
                            mangaId = item.mangaId,
                            mangaTitle = item.mangaTitle,
                            chapterName = item.chapterName,
                            pageCount = images.size,
                        ),
                    )
                }
            } finally {
                archiveReader?.close()
            }
        }
    }

    private suspend fun updateProgress(item: SRQueueItem) {
        queueMutex.withLock {
            val idx = queue.indexOfFirst { it.chapterId == item.chapterId }
            if (idx >= 0) {
                queue[idx] = queue[idx].copy(processedPages = item.processedPages)
                persistLocked()
                _state.value = _state.value.copy(inProgress = queue.toList())
            }
        }
    }

    private fun showProgressNotification(item: SRQueueItem) {
        if (!PermissionManager.hasNotificationPermission(context)) return
        try {
            val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SR_PROGRESS)
                .setContentTitle(context.stringResource(MR.strings.sr_notification_group))
                .setContentText("${item.mangaTitle} - ${item.chapterName}")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setSilent(true)
                .build()
            NotificationManagerCompat.from(context).notify(Notifications.ID_SR_PROGRESS, notification)
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "SR: Failed to show notification: ${e.message}" }
        }
    }

    private fun showCompletionNotification() {
        if (!PermissionManager.hasNotificationPermission(context)) return
        try {
            NotificationManagerCompat.from(context).cancel(Notifications.ID_SR_PROGRESS)
            val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_SR_COMPLETE)
                .setContentTitle(context.stringResource(MR.strings.sr_notification_complete_title))
                .setContentText(context.stringResource(MR.strings.sr_notification_complete_text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(Notifications.ID_SR_COMPLETE, notification)
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "SR: Failed to show completion notification: ${e.message}" }
        }
    }
}
