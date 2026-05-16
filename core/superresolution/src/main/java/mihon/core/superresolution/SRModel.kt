package mihon.core.superresolution

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class SRModel(
    val key: String,
    val displayNameRes: StringResource,
    val scale: Int,
    val requiresVulkan: Boolean,
    val modelDirName: String,
    val modelType: String,
    val isBuiltIn: Boolean,
) {
    REALCUGAN_2X_CONSERVATIVE(
        key = "realcugan_2x_conservative",
        displayNameRes = MR.strings.sr_model_realcugan_2x_conservative,
        scale = 2,
        requiresVulkan = true,
        modelDirName = "realcugan-2x-conservative",
        modelType = "realcugan",
        isBuiltIn = true,
    ),
    REALCUGAN_4X_CONSERVATIVE(
        key = "realcugan_4x_conservative",
        displayNameRes = MR.strings.sr_model_realcugan_4x_conservative,
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realcugan-4x-conservative",
        modelType = "realcugan",
        isBuiltIn = true,
    ),
    REALESRGAN_ANIME_FAST(
        key = "realesrgan_anime_fast",
        displayNameRes = MR.strings.sr_model_realesrgan_anime_fast,
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realesrgan-anime-fast",
        modelType = "realesrgan",
        isBuiltIn = true,
    ),
    REALESRGAN_ANIME_PLUS(
        key = "realesrgan_anime_plus",
        displayNameRes = MR.strings.sr_model_realesrgan_anime_plus,
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realesrgan-anime-plus",
        modelType = "realesrgan",
        isBuiltIn = true,
    ),
    REALESRGAN_GENERAL_FAST(
        key = "realesrgan_general_fast",
        displayNameRes = MR.strings.sr_model_realesrgan_general_fast,
        scale = 4,
        requiresVulkan = true,
        modelDirName = "realesrgan-general-fast",
        modelType = "realesrgan",
        isBuiltIn = true,
    );

    companion object {
        fun fromKey(key: String): SRModel =
            entries.firstOrNull { it.key == key } ?: REALCUGAN_2X_CONSERVATIVE

        fun selectModel(scale: Int, denoiseLevel: DenoiseLevel, quality: Quality = Quality.BALANCED): SRModel {
            return when {
                scale == 2 -> REALCUGAN_2X_CONSERVATIVE
                denoiseLevel == DenoiseLevel.STRONG -> REALESRGAN_GENERAL_FAST
                quality == Quality.HIGH -> REALESRGAN_ANIME_PLUS
                else -> REALESRGAN_ANIME_FAST
            }
        }
    }
}
