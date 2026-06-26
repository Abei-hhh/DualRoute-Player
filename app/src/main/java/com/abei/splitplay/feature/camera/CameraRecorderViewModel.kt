package com.abei.splitplay.feature.camera

import androidx.lifecycle.ViewModel
import com.abei.splitplay.feature.camera.ar.GeometryState
import com.abei.splitplay.feature.camera.ar.GestureStateMachine
import com.abei.splitplay.feature.camera.ar.HandFrame
import com.abei.splitplay.feature.camera.ar.RenderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 录像页的 AR 状态容器。
 *
 * 这一层把"手势识别 → 几何体姿态"的更新收敛成单一数据源(SSOT),
 * Compose Canvas(HUD 模式)和 GL 第二 pass(入录像模式)都读同一份 [geometryState]。
 *
 * 注意:之前 [CameraRecorderScreen] 完全没有 ViewModel,所有相机/录制状态仍留在 Composable
 * 里(因为它们和 CameraX 绑定/Recorder 强耦合,迁出来收益小)。本 ViewModel 只管 AR 相关状态。
 */
class CameraRecorderViewModel : ViewModel() {

    private val machine = GestureStateMachine()

    private val _geometryState = MutableStateFlow(GeometryState())
    val geometryState: StateFlow<GeometryState> = _geometryState.asStateFlow()

    private val _renderMode = MutableStateFlow(RenderMode.HUD_OVERLAY)
    val renderMode: StateFlow<RenderMode> = _renderMode.asStateFlow()

    /** 最近一帧的手部检测结果,供调试骨架叠加层读取。 */
    private val _handFrame = MutableStateFlow<HandFrame?>(null)
    val handFrame: StateFlow<HandFrame?> = _handFrame.asStateFlow()

    /** 是否在屏上叠加 21 点骨架(默认开,便于看跟踪效果)。 */
    private val _showSkeleton = MutableStateFlow(true)
    val showSkeleton: StateFlow<Boolean> = _showSkeleton.asStateFlow()

    /** 分析线程(MediaPipe 回调线程)调。 */
    fun onHandFrame(frame: HandFrame) {
        _handFrame.value = frame
        _geometryState.update { current -> machine.apply(frame, current) }
    }

    fun setRenderMode(mode: RenderMode) {
        _renderMode.value = mode
    }

    fun setShowSkeleton(show: Boolean) {
        _showSkeleton.value = show
    }

    /** 手动重置(切换前/后摄时调,避免坐标残留)。 */
    fun resetGesture() {
        machine.reset()
        _geometryState.update { it.copy(visible = false) }
        _handFrame.value = null
    }
}
