package mihon.core.superresolution.profile

import android.content.Context
import mihon.core.superresolution.benchmark.BenchmarkResult
import mihon.core.superresolution.benchmark.DeviceTier

class DeviceProfileManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("sr_benchmark", Context.MODE_PRIVATE)

    fun saveResult(result: BenchmarkResult) {
        prefs.edit()
            .putString(KEY_TIER, result.deviceTier.name)
            .putLong(KEY_INFERENCE_MS, result.inferenceMs)
            .putInt(KEY_SCALE, result.scale)
            .putString(KEY_MODEL, result.modelKey)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getConfig(): DeviceConfig? {
        val tierName = prefs.getString(KEY_TIER, null) ?: return null
        val tier = try { DeviceTier.valueOf(tierName) } catch (_: Exception) { return null }
        return when (tier) {
            DeviceTier.FAST   -> DeviceConfig(preloadWindow = 5, maxAttempts = 40)
            DeviceTier.MID    -> DeviceConfig(preloadWindow = 2, maxAttempts = 30)
            DeviceTier.SLOW   -> DeviceConfig(preloadWindow = 0, maxAttempts = 20)
            DeviceTier.UNKNOWN -> null
        }
    }

    fun hasResult(): Boolean = prefs.contains(KEY_TIER)

    fun getResult(): BenchmarkResult? {
        val tierName = prefs.getString(KEY_TIER, null) ?: return null
        val tier = try { DeviceTier.valueOf(tierName) } catch (_: Exception) { return null }
        return BenchmarkResult(
            deviceTier = tier,
            inferenceMs = prefs.getLong(KEY_INFERENCE_MS, -1),
            scale = prefs.getInt(KEY_SCALE, 2),
            modelKey = prefs.getString(KEY_MODEL, null),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TIER = "tier"
        private const val KEY_INFERENCE_MS = "inference_ms"
        private const val KEY_SCALE = "scale"
        private const val KEY_MODEL = "model"
        private const val KEY_TIMESTAMP = "timestamp"
    }
}
