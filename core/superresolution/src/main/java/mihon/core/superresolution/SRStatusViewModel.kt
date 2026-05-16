package mihon.core.superresolution

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SRStatusViewModel : ViewModel() {
    private val _srStatus = MutableStateFlow(SRStatusInfo(SRStatus.IDLE, -1, -1, SRModel.REALCUGAN_2X_CONSERVATIVE))
    val srStatus: StateFlow<SRStatusInfo> = _srStatus.asStateFlow()

    fun onSRStart(chapterId: Long, pageIndex: Int, model: SRModel) {
        _srStatus.value = SRStatusInfo(SRStatus.PROCESSING, pageIndex, chapterId, model)
    }

    fun onSRDone(chapterId: Long, pageIndex: Int, model: SRModel, elapsedMs: Long) {
        _srStatus.value = SRStatusInfo(SRStatus.DONE, pageIndex, chapterId, model, elapsedMs)
    }

    fun onSRIdle() {
        _srStatus.value = SRStatusInfo(SRStatus.IDLE, -1, -1, SRModel.REALCUGAN_2X_CONSERVATIVE)
    }
}
