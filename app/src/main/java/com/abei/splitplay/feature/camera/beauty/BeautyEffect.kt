package com.abei.splitplay.feature.camera.beauty

import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer
import java.util.concurrent.Executor

/**
 * CameraX 美颜 effect。targets = PREVIEW or VIDEO_CAPTURE,意味着
 * 摄像头出一路共享流,经过 [BeautySurfaceProcessor] 渲染后同时分发给
 * Preview 显示和 VideoCapture 编码 —— 所见即所录。
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
    fun release() = processor.release()
}
