package eu.kanade.tachiyomi.data.sr

import android.app.Application
import coil3.imageLoader
import logcat.LogPriority
import logcat.logcat
import mihon.core.superresolution.SRDiskCache
import mihon.core.superresolution.SuperResolutionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class CacheUsage(
    val memoryEntries: Int,
    val memoryBytes: Long,
    val diskFiles: Int,
    val diskBytes: Long,
) {
    val totalFiles: Int get() = memoryEntries + diskFiles
    val totalBytes: Long get() = memoryBytes + diskBytes
}

object SRCacheManager {

    fun getCacheUsage(): CacheUsage {
        val manager = Injekt.get<SuperResolutionManager>()
        val (memEntries, memBytes) = manager.getCacheUsage()
        val app = Injekt.get<Application>()
        val cacheDir = java.io.File(app.cacheDir, "sr_disk_cache")
        val diskCache = SRDiskCache(cacheDir)
        val (diskFiles, diskBytes) = diskCache.getUsage()
        return CacheUsage(memEntries, memBytes, diskFiles, diskBytes)
    }

    fun clearAllCaches(): CacheUsage {
        val usage = getCacheUsage()
        clearCoilMemoryCache()
        clearSrResultCache()
        clearDiskCache()
        return usage
    }

    fun clearCoilMemoryCache() {
        try {
            val app = Injekt.get<Application>()
            val memoryCache = app.imageLoader.memoryCache ?: return
            memoryCache.clear()
            logcat(LogPriority.INFO) { "SR: Cleared Coil memory cache" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to clear Coil memory cache: ${e.message}" }
        }
    }

    fun clearSrResultCache() {
        try {
            val manager = Injekt.get<SuperResolutionManager>()
            manager.clearSrCache()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to clear SR result cache: ${e.message}" }
        }
    }

    fun clearDiskCache() {
        try {
            val app = Injekt.get<Application>()
            val cacheDir = java.io.File(app.cacheDir, "sr_disk_cache")
            val diskCache = SRDiskCache(cacheDir)
            diskCache.clear()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to clear SR disk cache: ${e.message}" }
        }
    }
}
