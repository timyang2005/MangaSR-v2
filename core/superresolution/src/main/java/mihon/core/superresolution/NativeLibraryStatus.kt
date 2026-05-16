package mihon.core.superresolution

import logcat.LogPriority
import logcat.logcat

object NativeLibraryStatus {

    val isRealESRGANAvailable: Boolean by lazy {
        RealESRGANProcessor.nativeLibraryLoaded
    }

    fun isModelAvailable(model: SRModel): Boolean {
        return when (model.requiresVulkan) {
            true -> isRealESRGANAvailable
            false -> true
        }
    }

    fun getAvailableModels(): List<SRModel> {
        return SRModel.entries.filter { isModelAvailable(it) }
    }

    fun getFirstAvailableModel(): SRModel {
        return getAvailableModels().firstOrNull() ?: SRModel.REALCUGAN_2X_CONSERVATIVE
    }
}
