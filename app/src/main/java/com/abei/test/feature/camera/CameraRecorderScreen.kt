package com.abei.test.feature.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abei.test.feature.camera.beauty.BeautyEffect
import com.abei.test.feature.camera.beauty.BeautyParams
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.camera.core.Preview as CameraPreview

/** 录制按钮的交互模式。 */
private enum class RecordMode { TAP, HOLD }

/** 长按模式下的最小录制时长。短点(如 50ms)也至少录到这么长,避免空 mp4。 */
private const val MIN_HOLD_RECORD_MS = 200L

/**
 * 自拍录制页。CameraX 三件套:
 *  - [CameraPreview] 把摄像头帧画到 [PreviewView]
 *  - [VideoCapture] + [Recorder] 编码 H.264 + AAC 到 MP4
 *  - [MediaStoreOutputOptions] 直接 sink 到系统媒体库
 *
 * 画质相关关键配置(过去预览糊主要因为这些都没设):
 *  - [ResolutionSelector] 显式把 Preview 和 VideoCapture 都瞄准 1080p
 *  - [UseCaseGroup] + [ViewPort] 让 Preview 与 VideoCapture 共用裁剪区,所见即所录
 *  - [CameraPreview.Builder.setPreviewStabilizationEnabled] / [VideoCapture.Builder.setVideoStabilizationEnabled]
 *    开 EIS,需先用 [androidx.camera.core.CameraInfo] 探测设备支持
 *  - 点按对焦走 [PreviewView.getMeteringPointFactory] + [FocusMeteringAction]
 *  - 曝光走 [Camera.getCameraControl] 的 setExposureCompensationIndex
 *
 * 美颜走 [BeautyEffect],挂在 [UseCaseGroup] 的 effect 上,Preview 和 VideoCapture
 * 共享同一份处理结果。
 */
@Composable
fun CameraRecorderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> granted = results.values.all { it } }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent(lifecycleOwner = lifecycleOwner, onBack = onBack)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "需要相机和麦克风权限才能录像",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = {
                        launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("授予权限") }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
    }
}

@Composable
private fun CameraContent(lifecycleOwner: LifecycleOwner, onBack: () -> Unit) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            // PERFORMANCE 模式优先走 SurfaceView,比 TextureView 少一次 GPU 拷贝;
            // FILL_CENTER 配合 9:16 ViewPort 让预览铺满竖屏不变形。
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    var useFrontCamera by remember { mutableStateOf(false) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // 美颜参数(0..1)
    var beautyParams by remember { mutableStateOf(BeautyParams()) }
    var showBeautyPanel by remember { mutableStateOf(false) }
    // 曝光归一化值(0..1),0.5 = 中间 = 0EV
    var exposureFraction by remember { mutableStateOf(0.5f) }
    // 点按对焦的视觉指示器位置
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }
    // 录制按钮交互模式:TAP = 点开始/再点停止;HOLD = 按住录、松手停
    var recordMode by remember { mutableStateOf(RecordMode.TAP) }

    // BeautyEffect 整屏只创建一次,跨 rebind 复用,onDispose 释放 EGL
    val beautyEffect = remember { BeautyEffect(mainExecutor) }
    DisposableEffect(beautyEffect) {
        onDispose { beautyEffect.release() }
    }
    LaunchedEffect(beautyParams) {
        beautyEffect.updateParams(beautyParams)
    }

    LaunchedEffect(useFrontCamera) {
        val provider = ProcessCameraProvider.getInstance(context).awaitFuture()
        provider.unbindAll()

        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

        // 探测当前镜头是否支持 EIS;CameraX 1.4 把能力查询挪到 PreviewCapabilities / VideoCapabilities。
        // 前置摄像头大多数机器不支持。
        val candidateInfo = selector.filter(provider.availableCameraInfos).firstOrNull()
        val previewStabSupported = candidateInfo?.let {
            CameraPreview.getPreviewCapabilities(it).isStabilizationSupported
        } == true
        val videoStabSupported = candidateInfo?.let {
            Recorder.getVideoCapabilities(it).isStabilizationSupported
        } == true

        val resSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1080, 1920),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val preview = CameraPreview.Builder()
            .setResolutionSelector(resSelector)
            .apply { if (previewStabSupported) setPreviewStabilizationEnabled(true) }
            .build()
            .apply { surfaceProvider = previewView.surfaceProvider }

        // Recorder 用有序列表:1080p 优先,降级 720p / 480p,再不行接受比 SD 高的档
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
                ),
            )
            .build()

        val capture = VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(30, 30))   // 锁 30fps,避免暗光自动降到 15fps 显糊
            .apply { if (videoStabSupported) setVideoStabilizationEnabled(true) }
            .build()

        val viewPort = ViewPort.Builder(
            Rational(9, 16),
            previewView.display?.rotation ?: android.view.Surface.ROTATION_0,
        ).setScaleType(ViewPort.FILL_CENTER).build()

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(beautyEffect)
            .setViewPort(viewPort)
            .build()

        val cam = provider.bindToLifecycle(lifecycleOwner, selector, group)
        videoCapture = capture
        camera = cam
        applyExposure(cam, exposureFraction)
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            recording = null
        }
    }

    LaunchedEffect(recording) {
        val start = recording?.let { System.nanoTime() } ?: return@LaunchedEffect
        while (recording != null) {
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            delay(100)
        }
        elapsedMs = 0L
    }

    LaunchedEffect(focusIndicator) {
        if (focusIndicator != null) {
            delay(800)
            focusIndicator = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        ).build()
                        runCatching { cam.cameraControl.startFocusAndMetering(action) }
                        focusIndicator = offset
                    }
                },
            factory = { previewView },
        )

        // 对焦指示框 —— 800ms 自动隐藏
        focusIndicator?.let { off ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(off.x.toInt() - 40, off.y.toInt() - 40) }
                    .size(80.dp)
                    .border(2.dp, Color.Yellow, CircleShape),
            )
        }

        if (recording != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(top = 12.dp)
                    .background(Color.Red.copy(alpha = 0.8f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(formatElapsed(elapsedMs), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        // 顶部右侧 —— 录制模式切换 + 曝光调节
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(top = 4.dp, end = 12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            RecordModeToggle(
                mode = recordMode,
                enabled = recording == null,
                onChange = { recordMode = it },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .width(200.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.WbSunny,
                    contentDescription = "曝光",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Slider(
                    value = exposureFraction,
                    onValueChange = {
                        exposureFraction = it
                        camera?.let { c -> applyExposure(c, it) }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // 美颜面板
        if (showBeautyPanel) {
            BeautyPanel(
                params = beautyParams,
                onChange = { beautyParams = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth(),
        ) {
            IconButton(
                onClick = { showBeautyPanel = !showBeautyPanel },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 32.dp),
            ) {
                Icon(
                    Icons.Filled.Face,
                    contentDescription = "美颜",
                    tint = if (beautyParams.smooth > 0f || beautyParams.whiten > 0f)
                        MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(
                onClick = { if (recording == null) useFrontCamera = !useFrontCamera },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp),
                enabled = recording == null,
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "翻转", tint = Color.White)
            }
            val startRec = startRec@{
                if (recording != null) return@startRec
                recording = startRecording(context, videoCapture) { event ->
                    if (event is VideoRecordEvent.Finalize) recording = null
                }
            }
            val stopRec = {
                recording?.stop()
                recording = null
            }
            // pointerInput 用 recordMode 当 key,模式切换时旧手势会被取消重建
            val gestureModifier = when (recordMode) {
                RecordMode.TAP -> Modifier.clickable {
                    if (recording != null) stopRec() else startRec()
                }
                RecordMode.HOLD -> Modifier.pointerInput(recordMode) {
                    detectTapGestures(
                        onPress = {
                            val pressedNs = System.nanoTime()
                            startRec()
                            tryAwaitRelease()
                            // 短点保底 200ms,避免按一下就生成几十毫秒空 mp4
                            val elapsedMs = (System.nanoTime() - pressedNs) / 1_000_000
                            if (elapsedMs < MIN_HOLD_RECORD_MS) {
                                delay(MIN_HOLD_RECORD_MS - elapsedMs)
                            }
                            stopRec()
                        },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(80.dp)
                    .background(Color.White, CircleShape)
                    .then(gestureModifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (recording != null) 32.dp else 64.dp)
                        .background(Color.Red, CircleShape),
                )
            }
        }
    }
}

/** 顶部右侧的点按/长按模式 pill。录制中 [enabled]=false,禁止切换避免歧义。 */
@Composable
private fun RecordModeToggle(
    mode: RecordMode,
    enabled: Boolean,
    onChange: (RecordMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(3.dp),
    ) {
        ModeChip(label = "点按", selected = mode == RecordMode.TAP, enabled = enabled) {
            onChange(RecordMode.TAP)
        }
        ModeChip(label = "长按", selected = mode == RecordMode.HOLD, enabled = enabled) {
            onChange(RecordMode.HOLD)
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = when {
            !enabled -> Color.White.copy(alpha = 0.4f)
            selected -> Color.Black
            else -> Color.White
        },
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(
                if (selected) Color.White else Color.Transparent,
                RoundedCornerShape(13.dp),
            )
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun BeautyPanel(
    params: BeautyParams,
    onChange: (BeautyParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "磨皮  ${(params.smooth * 100).toInt()}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(value = params.smooth, onValueChange = { onChange(params.copy(smooth = it)) })
        Text(
            "美白  ${(params.whiten * 100).toInt()}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(value = params.whiten, onValueChange = { onChange(params.copy(whiten = it)) })
    }
}

/** 把 0..1 的归一化值映射到 [ExposureState] 的整数补偿挡位区间。 */
private fun applyExposure(camera: Camera, fraction: Float) {
    val state = camera.cameraInfo.exposureState
    if (!state.isExposureCompensationSupported) return
    val range = state.exposureCompensationRange
    val span = range.upper - range.lower
    if (span <= 0) return
    val idx = range.lower + (fraction.coerceIn(0f, 1f) * span).toInt()
    runCatching { camera.cameraControl.setExposureCompensationIndex(idx) }
}

/**
 * 把 ProcessCameraProvider 的 ListenableFuture 等待成可 awaited 的 suspend 调用。
 */
private suspend fun <T> ListenableFuture<T>.awaitFuture(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addListener({
            runCatching { cont.resumeWith(Result.success(get())) }
                .onFailure { cont.resumeWith(Result.failure(it)) }
        }, Runnable::run)
    }

/**
 * 开始录制。输出走 [MediaStoreOutputOptions] 自动落到系统相册的 Movies/AbeiPlayer 下。
 */
private fun startRecording(
    context: android.content.Context,
    videoCapture: VideoCapture<Recorder>?,
    onEvent: (VideoRecordEvent) -> Unit,
): Recording? {
    val capture = videoCapture ?: return null

    val name = "AbeiPlayer_${System.currentTimeMillis()}.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AbeiPlayer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val options = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    ).setContentValues(values).build()

    val pendingRecording = capture.output
        .prepareRecording(context, options)
        .withAudioEnabled()
    return pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
        if (event is VideoRecordEvent.Finalize && !event.hasError()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                event.outputResults.outputUri.let { uri ->
                    context.contentResolver.update(uri, update, null, null)
                }
            }
        }
        onEvent(event)
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return String.format(Locale.US, "● %02d:%02d", m, s)
}
