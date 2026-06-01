package com.abei.test.feature.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.camera.core.Preview as CameraPreview

/**
 * 自拍录制页。CameraX 三件套:
 *  - [CameraPreview] 把摄像头帧画到 [PreviewView]
 *  - [VideoCapture] + [Recorder] 编码 H.264 + AAC 到 MP4
 *  - [MediaStoreOutputOptions] 直接 sink 到系统媒体库,录完 VideoGalleryScreen 刷新就能看到
 *
 * 简化:只支持后置 / 前置切换,不暴露分辨率挡位选择 (用 QualitySelector.HIGHEST)。
 */
@Composable
fun CameraRecorderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 先索 CAMERA + RECORD_AUDIO 两个权限,缺一不可。
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
    val previewView = remember { PreviewView(context) }

    var useFrontCamera by remember { mutableStateOf(false) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }

    // 绑定 use case。useFrontCamera 切换时重新绑定;PreviewView 复用,不会闪屏。
    LaunchedEffect(useFrontCamera) {
        val provider = ProcessCameraProvider.getInstance(context).awaitFuture()
        provider.unbindAll()
        val preview = CameraPreview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        videoCapture = capture
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            recording = null
        }
    }

    // 录制中每 100ms 更新一下计时,松手停止时清零。
    LaunchedEffect(recording) {
        val start = recording?.let { System.nanoTime() } ?: return@LaunchedEffect
        while (recording != null) {
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            delay(100)
        }
        elapsedMs = 0L
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        // 录制中:顶部显示计时
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

        // 底部操作区:录制大圆按钮 + 前/后置切换
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth(),
        ) {
            // 切换前后置 —— 录制中不让换,会打断 session。
            IconButton(
                onClick = { if (recording == null) useFrontCamera = !useFrontCamera },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp),
                enabled = recording == null,
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "翻转", tint = Color.White)
            }
            // 录制 toggle:点一下开始,再点一下停止。
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(80.dp)
                    .background(Color.White, CircleShape),
            ) {
                IconButton(
                    onClick = {
                        if (recording != null) {
                            recording?.stop()
                            recording = null
                        } else {
                            recording = startRecording(context, videoCapture) { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    recording = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (recording != null) 32.dp else 64.dp)
                            .background(Color.Red, if (recording != null) CircleShape else CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * 把 ProcessCameraProvider 的 ListenableFuture 等待成可 awaited 的 suspend 调用。
 * 不用 guava-kotlinx-coroutines 减少额外依赖,这里手撸一个 await。
 */
private suspend fun <T> ListenableFuture<T>.awaitFuture(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addListener({
            runCatching { cont.resumeWith(Result.success(get())) }
                .onFailure { cont.resumeWith(Result.failure(it)) }
        }, Runnable::run)
    }

/**
 * 开始录制。输出走 [MediaStoreOutputOptions] 自动落到系统相册的 Movies/AbeiPlayer 下,
 * 录完后通过 VideoGalleryScreen 的 MediaStore query 就能直接被发现。
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
            // 标记 IS_PENDING=0 让媒体库正式收录。
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
