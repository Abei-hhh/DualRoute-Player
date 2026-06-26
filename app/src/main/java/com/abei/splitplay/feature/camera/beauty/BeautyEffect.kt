package com.abei.splitplay.feature.camera.beauty

import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer
import com.abei.splitplay.feature.camera.ar.GeometryState
import java.util.concurrent.Executor

/**
 * CameraX 美颜 effect。targets = PREVIEW or VIDEO_CAPTURE,意味着
 * 摄像头出一路共享流,经过 [BeautySurfaceProcessor] 渲染后同时分发给
 * Preview 显示和 VideoCapture 编码 —— 所见即所录。
 *
 * 除美颜外,[BeautySurfaceProcessor] 内置了一个 3D mesh 第二 pass,
 * 用于 AR 几何体渲染(由手势驱动)。通过 [updateGeometryState] / [setRenderGeometryEnabled]
 * 控制。HUD 模式下应 [setRenderGeometryEnabled] = false,让 Compose Canvas 在屏上叠加;
 * 入录像模式 = true,让几何体进 Preview 与 VideoCapture 共享流。
 *
 * 注意:此 effect 在屏幕生命周期内**只能创建一次**,在 onDispose 时调用 [release]。
 * 不要每次 rebind camera 都新建,否则会泄漏 EGL 上下文。
 */
class BeautyEffect(
    executor: Executor,
    private val processor: BeautySurfaceProcessor = BeautySurfaceProcessor(),
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    executor,
    processor,
    Consumer<Throwable> { Log.w("BeautyEffect", "processor error", it) },
) {
    fun updateParams(params: BeautyParams) = processor.updateParams(params)
    fun updateGeometryState(state: GeometryState) = processor.updateGeometryState(state)
    fun setRenderGeometryEnabled(enabled: Boolean) = processor.setRenderGeometryEnabled(enabled)
    fun release() = processor.release()
}
