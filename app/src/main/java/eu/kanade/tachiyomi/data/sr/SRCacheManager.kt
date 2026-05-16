package eu.kanade.tachiyomi.data.sr

import android.app.Application
import coil3.imageLoader
import logcat.LogPriority
import logcat.logcat
import mihon.core.superresolution.SRDiskCache
import mihon.core.superresolution.SuperResolutionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SRCacheManager {

    fun clearAllCaches() {
        clearCoilMemoryCache()
        clearSrResultCache()
        clearDiskCache()
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
            val diskCache = SRDiskCache()
            diskCache.clear()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to clear SR disk cache: ${e.message}" }
        }
    }
}
