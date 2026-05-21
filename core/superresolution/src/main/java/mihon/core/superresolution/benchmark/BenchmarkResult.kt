package mihon.core.superresolution.benchmark

enum class DeviceTier {
    FAST, MID, SLOW, UNKNOWN
}

data class BenchmarkResult(
    val inferenceMs: Long = -1,
    val deviceTier: DeviceTier = DeviceTier.UNKNOWN,
    val scale: Int = 2,
    val modelKey: String? = null,
)
