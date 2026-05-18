package mihon.core.superresolution

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.util.LinkedHashMap

class SuperResolutionManager(
    private val context: Context,
) {
    private val mutex = Mutex()

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
        VulkanHelper.isVulkanSupported(context) && VulkanHelper.getGpuCount() > 0
    }

    @Synchronized
    fun getCachedResult(cacheKey: String): Bitmap? = srCache[cacheKey]

    @Synchronized
    fun putCachedResult(cacheKey: String, bitmap: Bitmap) {
        srCache[cacheKey] = bitmap
    }

    @Synchronized
    fun removeCachedResult(cacheKey: String) {
        srCache.remove(cacheKey)
    }

    @Synchronized
    fun clearSrCache() {
        srCache.clear()
        logcat(LogPriority.INFO) { "SR: Cleared SR result cache" }
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
            logcat(LogPriority.INFO) { "SR: Cancelling ${processingJobs.size} processing jobs" }
            processingJobs.forEach { it.cancel() }
            processingJobs.clear()
        }
    }

    suspend fun switchModel(
        model: SRModel,
        scale: Int = 2,
        denoiseLevel: DenoiseLevel = DenoiseLevel.LIGHT,
        bwConfig: MangaBWPostProcessConfig? = null,
    ) {
        mutex.withLock {
            logcat(LogPriority.INFO) { "SR: switchModel called: model=${model.key}, scale=$scale, denoise=$denoiseLevel, readerOverride=$readerOverride" }

            if (currentModel == model && currentScale == scale && currentProcessor?.isReady == true) {
                currentDenoiseLevel = denoiseLevel
                currentBwConfig = bwConfig
                logcat(LogPriority.DEBUG) { "SR: Same model and scale, updating denoise/bw config" }
                return@withLock
            }

            cancelAllProcessingJobs()

            onModelSwitching?.invoke()

            currentProcessor?.release()
            currentProcessor = null
            currentModel = null
            modelVersion++
            clearSrCache()

            val processor = createProcessor(model)
            val modelPath = getModelPath(model)

            withContext(Dispatchers.IO) {
                try {
                    val gpuid = if (isVulkanAvailable && model.requiresVulkan) 0 else -1
                    processor.initialize(modelPath, gpuid)
                    currentProcessor = processor
                    currentModel = model
                    currentScale = scale
                    currentDenoiseLevel = denoiseLevel
                    currentBwConfig = bwConfig
                    logcat(LogPriority.INFO) { "SR engine switched to ${model.key}, scale=${scale}x, ready=${processor.isReady}" }
                } catch (e: UnsatisfiedLinkError) {
                    logcat(LogPriority.ERROR) { "SR: Native library missing for ${model.key}, using NoOp\n${e.message}" }
                    processor.release()
                    currentProcessor = NoOpProcessor()
                    currentModel = model
                    currentScale = scale
                    currentDenoiseLevel = denoiseLevel
                    currentBwConfig = bwConfig
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "SR: Failed to initialize ${model.key}\n${e.asLog()}" }
                    processor.release()
                    currentProcessor = NoOpProcessor()
                    currentModel = model
                    currentScale = scale
                    currentDenoiseLevel = denoiseLevel
                    currentBwConfig = bwConfig
                }
            }
        }
    }

    suspend fun process(input: Bitmap, versionAtStart: Long): Bitmap = mutex.withLock {
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
                    return@withContext input
                }
                if (result !== input) {
                    logcat(LogPriority.INFO) {
                        "SR: Processed ${input.width}x${input.height} -> ${result.width}x${result.height}"
                    }
                }
                result
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

    fun ensureModelsExtracted() {
        val modelManager = ModelManager(context)
        modelManager.ensureModelsExtracted()
    }
}
