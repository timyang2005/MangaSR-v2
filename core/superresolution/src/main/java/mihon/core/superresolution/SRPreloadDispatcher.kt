package mihon.core.superresolution

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.core.superresolution.profile.DeviceProfileManager
import java.io.File

class SRPreloadDispatcher(
    private val manager: SuperResolutionManager,
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profileManager = DeviceProfileManager(context)
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
        pagesToPreload.forEach { pageIndex ->
            val cacheKey = buildCacheKey(chapterId, pageIndex)
            synchronized(preloadingPages) {
                if (cacheKey in preloadingPages) return@forEach
                preloadingPages.add(cacheKey)
            }

            if (diskCache.get(cacheKey) != null || manager.getCachedResult(cacheKey) != null) {
                logcat(LogPriority.DEBUG) { "SR: Preload skipped - already cached ch$chapterId p$pageIndex" }
                synchronized(preloadingPages) { preloadingPages.remove(cacheKey) }
                return@forEach
            }

            scope.launch {
                try {
                    onPreloadRequested?.invoke(chapterId, pageIndex)
                    logcat(LogPriority.DEBUG) { "SR: Preloading ch$chapterId p$pageIndex" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "SR: Preload failed ch$chapterId p$pageIndex\n${e.asLog()}" }
                } finally {
                    synchronized(preloadingPages) { preloadingPages.remove(cacheKey) }
                }
            }
        }
    }

    fun getSrBitmap(chapterId: Long, pageIndex: Int): Bitmap? {
        val cacheKey = buildCacheKey(chapterId, pageIndex)
        return manager.getCachedResult(cacheKey) ?: diskCache.get(cacheKey)
    }

    fun putSrResult(chapterId: Long, pageIndex: Int, bitmap: Bitmap) {
        val cacheKey = buildCacheKey(chapterId, pageIndex)
        manager.putCachedResult(cacheKey, bitmap)
        scope.launch(Dispatchers.IO) {
            diskCache.put(cacheKey, bitmap)
        }
    }

    fun clearCache() {
        diskCache.clear()
        manager.clearSrCache()
    }

    private fun buildCacheKey(chapterId: Long, pageIndex: Int): String =
        manager.buildCacheKey(chapterId, pageIndex)
}
