package mihon.core.superresolution

import android.graphics.Bitmap
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

class RealESRGANProcessor(
    override val model: SRModel,
) : SuperResolutionProcessor {

    private var handle: Long = 0L

    override val isReady: Boolean
        get() = handle != 0L && nativeLibraryLoaded

    override suspend fun initialize(modelPath: String, gpuid: Int) {
        if (!nativeLibraryLoaded) {
            logcat(LogPriority.ERROR) { "RealESRGAN native library not available for ${model.key}" }
            return
        }
        if (handle != 0L) release()
        val paramPath = "$modelPath/${model.modelDirName}.param"
        val binPath = "$modelPath/${model.modelDirName}.bin"
        handle = nativeInit(paramPath, binPath, gpuid, model.modelType, true)
        logcat(LogPriority.INFO) { "RealESRGAN initialized: model=${model.key}, handle=$handle" }
    }

    override fun process(
        input: Bitmap,
        scale: Int,
        denoiseLevel: DenoiseLevel,
        denoiseStrength: Float,
        bwConfig: MangaBWPostProcessConfig?,
    ): Bitmap {
        if (!isReady) {
            logcat(LogPriority.WARN) { "RealESRGAN not ready, returning original" }
            return input
        }
        val output = nativeProcess(
            handle, input, scale,
            denoiseStrength,
            bwConfig?.grayLevels ?: 0,
            bwConfig?.densityCorrection ?: false,
        )
        return output ?: input
    }

    override fun release() {
        if (handle != 0L && nativeLibraryLoaded) {
            nativeRelease(handle)
        }
        handle = 0L
    }

    protected fun finalize() {
        release()
    }

    private external fun nativeInit(paramPath: String, binPath: String, gpuid: Int, modelType: String, useFp16: Boolean): Long
    private external fun nativeProcess(
        handle: Long, input: Bitmap, scale: Int,
        denoiseStrength: Float, grayLevels: Int, densityCorrection: Boolean,
    ): Bitmap?
    private external fun nativeRelease(handle: Long)

    companion object {
        val nativeLibraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary("realesrgan-ncnn-vulkan")
                logcat(LogPriority.INFO) { "librealesrgan-ncnn-vulkan.so loaded successfully" }
                true
            } catch (e: UnsatisfiedLinkError) {
                logcat(LogPriority.ERROR) { "Failed to load librealesrgan-ncnn-vulkan.so: ${e.message}\n${e.asLog()}" }
                false
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Unexpected error loading librealesrgan-ncnn-vulkan.so: ${e.message}\n${e.asLog()}" }
                false
            }
        }
    }
}
