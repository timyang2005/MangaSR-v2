package eu.kanade.tachiyomi.ui.reader.setting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.sr.MangaSrPreferences
import eu.kanade.tachiyomi.data.sr.MangaSrRepository
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import mihon.core.superresolution.NativeLibraryStatus
import mihon.core.superresolution.SRModel
import mihon.core.superresolution.SuperResolutionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderSettingsScreenModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences = Injekt.get(),
    private val manager: SuperResolutionManager = Injekt.get(),
) : ScreenModel {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    private val mangaSrRepository = MangaSrRepository()

    @Composable
    fun rememberMangaSrPreferences(): MangaSrPreferences? {
        val mangaId = mangaFlow.collectAsState().value?.id ?: return null
        return remember(mangaId) { MangaSrPreferences(mangaId) }
    }

    fun applyEffectiveSrSettings() {
        val manga = mangaFlow.value ?: return
        val mangaSrPrefs = MangaSrPreferences(manga.id)
        val useGlobal = mangaSrPrefs.useGlobal.get()

        val effectiveEnabled: Boolean
        val effectiveModelKey: String
        val effectiveScale: Int

        if (useGlobal) {
            effectiveEnabled = preferences.srEnabled.get()
            effectiveModelKey = preferences.srModel.get()
            effectiveScale = preferences.srScale.get()
        } else {
            effectiveEnabled = mangaSrPrefs.srEnabled.get()
            effectiveModelKey = mangaSrPrefs.srModel.get()
            effectiveScale = mangaSrPrefs.srScale.get()
        }

        logcat(LogPriority.INFO) {
            "SR: Applying effective settings for manga ${manga.id}: enabled=$effectiveEnabled, model=$effectiveModelKey, scale=$effectiveScale, useGlobal=$useGlobal"
        }

        manager.setReaderOverride(true)
        ioCoroutineScope.launch {
            if (effectiveEnabled) {
                val model = SRModel.fromKey(effectiveModelKey)
                val actualModel = if (NativeLibraryStatus.isModelAvailable(model)) {
                    model
                } else {
                    val fallback = NativeLibraryStatus.getFirstAvailableModel()
                    logcat(LogPriority.WARN) {
                        "SR: Model ${model.key} not available, falling back to ${fallback.key}"
                    }
                    fallback
                }
                manager.switchModel(actualModel, effectiveScale)
            } else {
                manager.release()
            }
        }
    }

    fun releaseReaderOverride() {
        manager.setReaderOverride(false)
        logcat(LogPriority.INFO) { "SR: Reader override released, reverting to global settings" }

        ioCoroutineScope.launch {
            val globalEnabled = preferences.srEnabled.get()
            if (globalEnabled) {
                val model = SRModel.fromKey(preferences.srModel.get())
                val actualModel = if (NativeLibraryStatus.isModelAvailable(model)) model
                else NativeLibraryStatus.getFirstAvailableModel()
                manager.switchModel(actualModel, preferences.srScale.get())
            } else {
                manager.release()
            }
        }
    }

    fun saveSrSettings(mangaId: Long, srEnabled: Boolean?, srModel: String?, srScale: Int?, srNoiseLevel: Int?) {
        ioCoroutineScope.launch {
            srEnabled?.let { mangaSrRepository.setSrEnabled(mangaId, it) }
            srModel?.let { mangaSrRepository.setSrModel(mangaId, it) }
            srScale?.let { mangaSrRepository.setSrScale(mangaId, it) }
            srNoiseLevel?.let { mangaSrRepository.setSrNoiseLevel(mangaId, it) }
        }
    }
}
