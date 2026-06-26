package com.abei.splitplay.feature.camera.ar

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * CameraX [ImageAnalysis.Analyzer] —— 把每帧丢给 MediaPipe `GestureRecognizer`
 * (内含 HandLandmarker),把结果归一化到"屏幕方向"后回调出去。
 *
 * 资产要求:把 `gesture_recognizer.task` 放进 `app/src/main/assets/`。
 * 找不到模型文件不会崩,只会打 warn 并把每帧 close 掉(UI 仍可用,只是没有手势)。
 *
 * 坐标约定:回调里的 [HandPose.landmarks] 已经处理过:
 *  - 按 `imageInfo.rotationDegrees` 把 buffer 旋转到屏幕方向(交给 MediaPipe 的 ImageProcessingOptions)
 *  - 前摄镜头额外做 X 翻转 + LEFT/RIGHT 交换,与 [PreviewView] 显示的镜像保持一致
 *
 * 生命周期:[close] 一定要在 `DisposableEffect.onDispose` 里调,否则 MediaPipe native 资源泄漏。
 */
class HandLandmarkerAnalyzer(
    context: Context,
    private val onResult: (HandFrame) -> Unit,
    private val isFrontCamera: () -> Boolean,
    modelAssetPath: String = "gesture_recognizer.task",
    numHands: Int = 2,
    minDetectionConfidence: Float = 0.5f,
    minTrackingConfidence: Float = 0.5f,
    minPresenceConfidence: Float = 0.5f,
) : ImageAnalysis.Analyzer, AutoCloseable {

    @Volatile private var lastRotation: Int = 0
    @Volatile private var lastIsFront: Boolean = false

    private val recognizer: GestureRecognizer? = runCatching {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            // 先试 GPU delegate;部分老机器 GPU 不支持 task 模型,失败时降级 CPU 在 catch 里处理。
            .setDelegate(Delegate.GPU)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(numHands)
            .setMinHandDetectionConfidence(minDetectionConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)
            .setMinHandPresenceConfidence(minPresenceConfidence)
            .setResultListener(::onMpResult)
            .setErrorListener { e -> Log.w(TAG, "MediaPipe error", e) }
            .build()
        GestureRecognizer.createFromOptions(context, options)
    }.recoverCatching {
        Log.w(TAG, "GPU delegate failed, retry CPU", it)
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .setDelegate(Delegate.CPU)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(numHands)
            .setMinHandDetectionConfidence(minDetectionConfidence)
            .setMinTrackingConfidence(minTrackingConfidence)
            .setMinHandPresenceConfidence(minPresenceConfidence)
            .setResultListener(::onMpResult)
            .setErrorListener { e -> Log.w(TAG, "MediaPipe error", e) }
            .build()
        GestureRecognizer.createFromOptions(context, options)
    }.onFailure {
        Log.w(TAG, "GestureRecognizer init failed — gestures disabled. " +
            "Put '$modelAssetPath' under app/src/main/assets/.", it)
    }.getOrNull()

    @WorkerThread
    override fun analyze(image: ImageProxy) {
        val recognizer = this.recognizer
        if (recognizer == null) {
            image.close()
            return
        }
        try {
            lastRotation = image.imageInfo.rotationDegrees
            lastIsFront = isFrontCamera()
            val bitmap = image.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val options = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
                .setRotationDegrees(lastRotation)
                .build()
            // LIVE_STREAM 模式必须用 async,结果通过 setResultListener 回调
            recognizer.recognizeAsync(mpImage, options, image.imageInfo.timestamp / 1_000_000L)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    /** MediaPipe 内部线程回调。要轻,不要在这里做重计算。 */
    private fun onMpResult(
        result: GestureRecognizerResult,
        @Suppress("UNUSED_PARAMETER") inputImage: com.google.mediapipe.framework.image.MPImage,
    ) {
        val landmarksList = result.landmarks() ?: return
        val handednessList = result.handedness()
        val gesturesList = result.gestures()
        if (landmarksList.isEmpty()) {
            onResult(HandFrame(emptyList(), result.timestampMs()))
            return
        }
        val mirror = lastIsFront
        val hands = ArrayList<HandPose>(landmarksList.size)
        for (i in landmarksList.indices) {
            val lms = landmarksList[i]
            val mapped = ArrayList<HandLandmark>(lms.size)
            for (lm in lms) {
                val x = if (mirror) 1f - lm.x() else lm.x()
                mapped.add(HandLandmark(x, lm.y(), lm.z()))
            }
            // MediaPipe 的 handedness 是从相机视角说的;前摄镜像后要交换
            val rawHand = handednessList?.getOrNull(i)?.firstOrNull()?.categoryName()
            val handedness = when (rawHand) {
                "Left" -> if (mirror) Handedness.RIGHT else Handedness.LEFT
                "Right" -> if (mirror) Handedness.LEFT else Handedness.RIGHT
                else -> Handedness.RIGHT
            }
            val gestureLabel = gesturesList?.getOrNull(i)?.firstOrNull()?.categoryName()
            hands.add(HandPose(handedness, mapped, GestureKind.fromLabel(gestureLabel)))
        }
        onResult(HandFrame(hands, result.timestampMs()))
    }

    override fun close() {
        recognizer?.close()
    }

    companion object {
        private const val TAG = "HandAnalyzer"
    }
}
