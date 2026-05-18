package mihon.core.superresolution

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SRStatusViewModel : ViewModel() {
    private val _srStatus = MutableStateFlow(SRStatusInfo(SRStatus.IDLE, -1, -1, SRModel.REALCUGAN_2X_CONSERVATIVE))
    val srStatus: StateFlow<SRStatusInfo> = _srStatus.asStateFlow()

    fun onSRStart(chapterId: Long, pageIndex: Int, model: SRModel) {
        _srStatus.value = SRStatusInfo(
            status = SRStatus.PROCESSING,
            pageIndex = pageIndex,
            chapterId = chapterId,
            model = model,
            startTimeMs = System.currentTimeMillis()
        )
    }

    fun onSRStartWithStartTime(chapterId: Long, pageIndex: Int, model: SRModel, startTimeMs: Long) {
        _srStatus.value = SRStatusInfo(
            status = SRStatus.PROCESSING,
            pageIndex = pageIndex,
            chapterId = chapterId,
            model = model,
            startTimeMs = startTimeMs
        )
    }

    fun onSRDone(chapterId: Long, pageIndex: Int, model: SRModel, elapsedMs: Long) {
        _srStatus.value = SRStatusInfo(
            status = SRStatus.DONE,
            pageIndex = pageIndex,
            chapterId = chapterId,
            model = model,
            elapsedMs = elapsedMs
        )
    }

    fun onSRIdle() {
        _srStatus.value = SRStatusInfo(SRStatus.IDLE, -1, -1, SRModel.REALCUGAN_2X_CONSERVATIVE)
    }

    /**
     * 更新当前处理中的已用时间（用于实时更新计时器）
     */
    fun updateProcessingElapsedTime() {
        val current = _srStatus.value
        if (current.status == SRStatus.PROCESSING && current.startTimeMs != null) {
            _srStatus.value = current.copy(
                elapsedMs = System.currentTimeMillis() - current.startTimeMs
            )
        }
    }
}
