package mihon.core.superresolution

enum class SRStatus { IDLE, PROCESSING, DONE }

data class SRStatusInfo(
    val status: SRStatus,
    val pageIndex: Int,
    val chapterId: Long,
    val model: SRModel,
    val startTimeMs: Long? = null,
    val elapsedMs: Long? = null,
)
