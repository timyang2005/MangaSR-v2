package eu.kanade.tachiyomi.data.sr

import mihon.core.superresolution.DenoiseLevel
import mihon.core.superresolution.SRModel
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaSrPreferences(
    private val mangaId: Long,
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {
    private fun key(suffix: String) = "sr_manga_${mangaId}_$suffix"

    val useGlobal: Preference<Boolean> = preferenceStore.getBoolean(
        key = key("use_global"),
        defaultValue = true,
    )

    val srEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        key = key("enabled"),
        defaultValue = false,
    )

    val srModel: Preference<String> = preferenceStore.getString(
        key = key("model"),
        defaultValue = SRModel.REALCUGAN_2X_CONSERVATIVE.key,
    )

    val srDenoiseLevel: Preference<String> = preferenceStore.getString(
        key = key("denoise_level"),
        defaultValue = DenoiseLevel.LIGHT.key,
    )
}
