package eu.kanade.tachiyomi.data.sr

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import mihon.core.superresolution.DenoiseLevel
import mihon.core.superresolution.NativeLibraryStatus
import mihon.core.superresolution.SRModel
import mihon.core.superresolution.SuperResolutionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuperResolutionSync(
    private val preferences: ReaderPreferences = Injekt.get(),
    private val manager: SuperResolutionManager = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        logcat(LogPriority.INFO) { "SR: SuperResolutionSync starting" }
        scope.launch {
            combine(
                preferences.srEnabled.changes(),
                preferences.srModel.changes(),
                preferences.srScale.changes(),
                preferences.srDenoiseLevel.changes(),
            ) { enabled, modelKey, scale, denoiseKey ->
                SRConfig(enabled, modelKey, scale, denoiseKey)
            }
                .distinctUntilChanged()
                .collect { config ->
                    try {
                        if (manager.readerOverride) {
                            logcat(LogPriority.DEBUG) { "SR: Reader override active, skipping global sync" }
                            return@collect
                        }

                        if (config.enabled) {
                            val model = SRModel.fromKey(config.modelKey)
                            val denoiseLevel = DenoiseLevel.fromKey(config.denoiseKey)
                            if (NativeLibraryStatus.isModelAvailable(model)) {
                                manager.switchModel(model, config.scale, denoiseLevel)
                            } else {
                                val fallback = NativeLibraryStatus.getFirstAvailableModel()
                                logcat(LogPriority.WARN) {
                                    "SR model ${model.key} native library not available, falling back to ${fallback.key}"
                                }
                                manager.switchModel(fallback, config.scale, denoiseLevel)
                            }
                        } else {
                            manager.release()
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "SR sync error: ${e.message}" }
                    }
                }
        }
    }

    private data class SRConfig(
        val enabled: Boolean,
        val modelKey: String,
        val scale: Int,
        val denoiseKey: String,
    )
}
