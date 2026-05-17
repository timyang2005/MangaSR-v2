package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import mihon.core.superresolution.SRPreloadDispatcher
import mihon.core.superresolution.SRStatus
import mihon.core.superresolution.SRStatusInfo
import mihon.core.superresolution.SRStatusViewModel
import mihon.core.superresolution.SuperResolutionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class SRResult(
    val chapterId: Long,
    val pageIndex: Int,
    val bitmap: Bitmap,
)

class SuperResolutionInterceptor(
    private val manager: SuperResolutionManager = Injekt.get(),
    private val preloadDispatcher: SRPreloadDispatcher = Injekt.get(),
) : Interceptor {

    private val srScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingKeys = mutableSetOf<String>()

    val srResultFlow = MutableSharedFlow<SRResult>(extraBufferCapacity = 64)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()

        if (!chain.request.superResolution) return result
        if (!manager.isReady) return result
        if (result !is SuccessResult) return result

        val bitmap = (result.image as? coil3.BitmapImage)?.bitmap
        if (bitmap == null) return result

        if (bitmap.width < MIN_SR_INPUT_WIDTH || bitmap.height < MIN_SR_INPUT_HEIGHT) return result
        if (bitmap.width > MAX_SR_INPUT_SIZE || bitmap.height > MAX_SR_INPUT_SIZE) return result

        val pageIndex = chain.request.pageIndex
        val chapterId = chain.request.chapterId

        val srBitmap = preloadDispatcher.getSrBitmap(chapterId, pageIndex)
        if (srBitmap != null) {
            logcat(LogPriority.INFO) { "SR: Preload cache hit for ch$chapterId p$pageIndex ${srBitmap.width}x${srBitmap.height}" }
            return result.copy(image = srBitmap.asImage(shareable = false))
        }

        val cacheKey = if (pageIndex >= 0 && chapterId >= 0) {
            "page_${chapterId}_${pageIndex}_${manager.activeModel?.key}_${manager.activeScale}"
        } else if (pageIndex >= 0) {
            "page_${pageIndex}_${manager.activeModel?.key}_${manager.activeScale}"
        } else {
            "${bitmap.width}x${bitmap.height}_${System.identityHashCode(bitmap)}_${manager.activeModel?.key}_${manager.activeScale}"
        }

        manager.getCachedResult(cacheKey)?.let { cachedBitmap ->
            logcat(LogPriority.INFO) { "SR: Memory cache hit for ch$chapterId p$pageIndex" }
            return result.copy(image = cachedBitmap.asImage(shareable = false))
        }

        synchronized(processingKeys) {
            if (cacheKey in processingKeys) return result
            processingKeys.add(cacheKey)
        }

        val inputBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            try {
                withContext(Dispatchers.Default) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (_: Exception) {
                synchronized(processingKeys) { processingKeys.remove(cacheKey) }
                return result
            } ?: run {
                synchronized(processingKeys) { processingKeys.remove(cacheKey) }
                return result
            }
        } else if (bitmap.config != Bitmap.Config.ARGB_8888) {
            synchronized(processingKeys) { processingKeys.remove(cacheKey) }
            return result
        } else {
            bitmap
        }

        val key = cacheKey
        val versionAtStart = manager.currentModelVersion()
        val job = srScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                logcat(LogPriority.INFO) { "SR: Background processing ch$chapterId p$pageIndex ${inputBitmap.width}x${inputBitmap.height}" }

                val srBitmap = manager.process(inputBitmap, versionAtStart)
                val elapsed = System.currentTimeMillis() - startTime

                if (srBitmap !== inputBitmap) {
                    manager.putCachedResult(key, srBitmap)
                    if (pageIndex >= 0 && chapterId >= 0) {
                        preloadDispatcher.putSrResult(chapterId, pageIndex, srBitmap)
                    }
                    srResultFlow.emit(SRResult(chapterId, pageIndex, srBitmap))
                    logcat(LogPriority.INFO) {
                        "SR: Cached ch$chapterId p$pageIndex ${inputBitmap.width}x${inputBitmap.height} -> ${srBitmap.width}x${srBitmap.height} in ${elapsed}ms"
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "SR: Background processing failed: ${e.message}" }
            } finally {
                if (inputBitmap !== bitmap) inputBitmap.recycle()
                synchronized(processingKeys) { processingKeys.remove(key) }
            }
        }
        manager.registerProcessingJob(job)

        return result
    }

    companion object {
        private const val MIN_SR_INPUT_WIDTH = 300
        private const val MIN_SR_INPUT_HEIGHT = 400
        private const val MAX_SR_INPUT_SIZE = 2048
    }
}
