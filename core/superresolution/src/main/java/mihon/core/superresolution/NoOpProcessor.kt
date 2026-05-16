package mihon.core.superresolution

import android.graphics.Bitmap
import logcat.LogPriority
import logcat.logcat

class NoOpProcessor : SuperResolutionProcessor {
    override val model: SRModel = SRModel.REALCUGAN_2X_CONSERVATIVE
    override val isReady: Boolean = true
    override suspend fun initialize(modelPath: String, gpuid: Int) {
        logcat(LogPriority.WARN) { "SR: NoOpProcessor initialized - super resolution will not be applied" }
    }
    override fun process(
        input: Bitmap,
        scale: Int,
        denoiseLevel: DenoiseLevel,
        denoiseStrength: Float,
        bwConfig: MangaBWPostProcessConfig?,
    ): Bitmap = input
    override fun release() {}
}
