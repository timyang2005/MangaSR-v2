package eu.kanade.tachiyomi.data.sr

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import mihon.core.superresolution.RealESRGANProcessor
import mihon.core.superresolution.SuperResolutionManager
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.OffsetDateTime
import java.time.ZoneId

class SRLogUtil(
    private val context: Context,
    private val srManager: SuperResolutionManager = Injekt.get(),
) {

    suspend fun dumpSRLogs() = withNonCancellableContext {
        try {
            val file = context.createFileInCacheDir("mangasr_logs.txt")

            file.appendText(getDebugInfo() + "\n\n")
            file.appendText(getSRInfo() + "\n\n")
            file.appendText("=== Logcat (SR-related logs) ===\n")

            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val srKeywords = listOf("SR:", "RealESRGAN", "RealCUGAN", "SuperResolution", "SuperRes", "sr_", "MangaBW")
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (srKeywords.any { keyword -> line.contains(keyword, ignoreCase = false) }) {
                        file.appendText("$line\n")
                    }
                }
            }
            process.waitFor()

            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (e: Throwable) {
            withUIContext { context.toast("Failed to get SR logs") }
        }
    }

    private fun getDebugInfo(): String {
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            Current time: ${OffsetDateTime.now(ZoneId.systemDefault())}
        """.trimIndent()
    }

    private fun getSRInfo(): String {
        return """
            === Super Resolution Status ===
            SR Model: ${srManager.activeModel?.key ?: "None"}
            SR Ready: ${srManager.isReady}
            SR Scale: ${srManager.activeScale}x
            SR Denoise: ${srManager.activeDenoiseLevel.key}
            SR Reader Override: ${srManager.readerOverride}
            Vulkan Available: ${srManager.isVulkanAvailable}
            === Native Library Status ===
            RealESRGAN/CUGAN: ${RealESRGANProcessor.nativeLibraryLoaded}
        """.trimIndent()
    }
}
