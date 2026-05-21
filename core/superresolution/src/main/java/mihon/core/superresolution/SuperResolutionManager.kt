package mihon.core.superresolution

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.util.LinkedHashMap

class SuperResolutionManager(
    private val context: Context,
) {
    private val mutex = Mutex()

    private val cacheLock = Any()

    private val maxCacheSize = 32

    private val srCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            return size > maxCacheSize
        }
    }

    private var currentProcessor: SuperResolutionProcessor? = null
    private var currentModel: SRModel? = null
    private var currentScale: Int = 2
    private var currentDenoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT
    private var currentBwConfig: MangaBWPostProcessConfig? = null

    private var processingJobs = mutableListOf<Job>()
    private val jobsMutex = Any()

    @Volatile
    private var modelVersion: Long = 0

    @Volatile
    private var pendingModelKey: String? = null

    @Volatile
    var readerOverride: Boolean = false
        private set

    var onModelSwitching: (() -> Unit)? = null

    val isReady: Boolean
        get() = currentProcessor?.isReady == true

    val activeModel: SRModel?
        get() = currentModel

    val activeScale: Int
        get() = currentScale

    val activeDenoiseLevel: DenoiseLevel
        get() = currentDenoiseLevel

    val isVulkanAvailable: Boolean by lazy {
        VulkanHelper.isVulkanSupported(context) && VulkanHelper.gpuCount > 0
    }

    fun getCacheUsage(): Pair<Int, Long> = synchronized(cacheLock) {
        srCache.size to srCache.values.sumOf { it.allocationByteCount.toLong() }
    }

    fun getCachedResult(cacheKey: String): Bitmap? = synchronized(cacheLock) { srCache[cacheKey] }

    fun putCachedResult(cacheKey: String, bitmap: Bitmap) = synchronized(cacheLock) {
        srCache[cacheKey] = bitmap
    }

    fun removeCachedResult(cacheKey: String) = synchronized(cacheLock) {
        srCache.remove(cacheKey)
    }

    fun clearSrCache() = synchronized(cacheLock) {
        val size = srCache.size
        val bytes = srCache.values.sumOf { it.allocationByteCount.toLong() }
        srCache.clear()
        logcat(LogPriority.INFO) { "SR: Cleared SR result cache ($size entries, ${bytes / 1024}KB)" }
    }

    fun registerProcessingJob(job: Job) {
        synchronized(jobsMutex) {
            processingJobs.add(job)
        }
        job.invokeOnCompletion {
            synchronized(jobsMutex) {
                processingJobs.remove(job)
            }
        }
    }

    private fun cancelAllProcessingJobs() {
        synchronized(jobsMutex) {
            processingJobs.forEach { it.cancel() }
            processingJobs.clear()
        }
    }

    fun switchModel(
        model: SRModel,
        scale: Int = 2,
        denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
        bwConfig: MangaBWPostProcessConfig? = null,
    ) {
        logcat(LogPriority.INFO) { "SR: switchModel called: model=${model.key}, scale=$scale, denoise=$denoiseLevel, readerOverride=$readerOverride" }

        if (currentModel == model && currentScale == scale && currentProcessor?.isReady == true) {
            currentDenoiseLevel = denoiseLevel
            currentBwConfig = bwConfig
            logcat(LogPriority.DEBUG) { "SR: Same model and scale, updating denoise/bw config" }
            return
        }

        pendingModelKey = model.key
        cancelAllProcessingJobs()
        onModelSwitching?.invoke()
        currentProcessor?.release()
        currentProcessor = null
        currentModel = model
        currentDenoiseLevel = denoiseLevel
        currentBwConfig = bwConfig
        currentScale = scale
        clearSrCache()
        modelVersion++
    }

    private val loadMutex = Mutex()

    suspend fun ensureModelLoaded() {
        if (pendingModelKey != null && currentProcessor == null) {
            loadMutex.withLock {
                if (pendingModelKey != null && currentProcessor == null) {
                    loadPendingModel()
                }
            }
        }
    }

    private suspend fun loadPendingModel() {
        val model = SRModel.fromKey(pendingModelKey!!)
        val processor = createProcessor(model)
        val modelPath = getModelPath(model)
        withContext(Dispatchers.IO) {
            try {
                val gpuid = if (isVulkanAvailable && model.requiresVulkan) 0 else -1
                val loadStart = System.currentTimeMillis()
                processor.initialize(modelPath, gpuid)
                val loadElapsed = System.currentTimeMillis() - loadStart
                currentProcessor = processor
                currentModel = model
                logcat(LogPriority.INFO) { "SR: ${model.key} loaded in ${loadElapsed}ms, ready=${processor.isReady}" }
            } catch (e: UnsatisfiedLinkError) {
                logcat(LogPriority.ERROR) { "SR: Native library missing for ${model.key}, using NoOp\n${e.message}" }
                processor.release()
                currentProcessor = NoOpProcessor()
                currentModel = model
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "SR: Failed to initialize ${model.key}\n${e.asLog()}" }
                processor.release()
                currentProcessor = NoOpProcessor()
                currentModel = model
            }
        }
        pendingModelKey = null
    }

    suspend fun process(input: Bitmap, versionAtStart: Long): Bitmap = mutex.withLock {
        ensureModelLoaded()

        val processor = currentProcessor
        if (processor == null || !processor.isReady) {
            logcat(LogPriority.DEBUG) { "SR: No processor ready, returning original" }
            return input
        }

        if (versionAtStart != modelVersion) {
            logcat(LogPriority.DEBUG) { "SR: Model switched during processing, discarding result" }
            return input
        }

        withContext(Dispatchers.IO) {
            try {
                withTimeout(120_000) {
                    val denoiseStrength = when (currentDenoiseLevel) {
                        DenoiseLevel.OFF -> 0f
                        DenoiseLevel.LIGHT -> 0.5f
                        DenoiseLevel.STRONG -> 1.0f
                    }
                    val result = processor.process(
                        input, currentScale,
                        currentDenoiseLevel, denoiseStrength, currentBwConfig,
                    )
                    if (versionAtStart != modelVersion) {
                        logcat(LogPriority.DEBUG) { "SR: Model switched during inference, discarding result" }
                        return@withTimeout input
                    }
                    if (result !== input) {
                        logcat(LogPriority.INFO) {
                            "SR: Processed ${input.width}x${input.height} -> ${result.width}x${result.height}"
                        }
                    }
                    result
                }
            } catch (e: TimeoutCancellationException) {
                logcat(LogPriority.WARN) { "SR: Processing timed out after 120s" }
                input
            } catch (e: CancellationException) {
                logcat(LogPriority.DEBUG) { "SR: Processing cancelled" }
                input
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "SR processing failed\n${e.asLog()}" }
                input
            }
        }
    }

    fun currentModelVersion(): Long = modelVersion

    fun release() {
        cancelAllProcessingJobs()
        currentProcessor?.release()
        currentProcessor = null
        currentModel = null
        modelVersion++
        clearSrCache()
    }

    fun setReaderOverride(enabled: Boolean) {
        readerOverride = enabled
        logcat(LogPriority.INFO) { "SR: readerOverride set to $enabled" }
    }

    private fun createProcessor(model: SRModel): SuperResolutionProcessor {
        return try {
            RealESRGANProcessor(model)
        } catch (e: UnsatisfiedLinkError) {
            logcat(LogPriority.WARN) { "SR: Cannot create processor for ${model.key}: ${e.message}" }
            NoOpProcessor()
        }
    }

    private fun getModelPath(model: SRModel): String {
        return File(context.filesDir, "models/${model.modelDirName}").absolutePath
    }

    fun buildCacheKey(chapterId: Long, pageIndex: Int): String {
        val modelKey = activeModel?.key ?: "unknown"
        return "page_${chapterId}_${pageIndex}_${modelKey}_${activeScale}"
    }

    fun ensureModelsExtracted() {
        ModelManager.getInstance(context).ensureModelsExtracted()
    }
}
