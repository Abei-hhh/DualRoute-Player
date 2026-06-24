package com.abei.splitplay.playerui

import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.viewinterop.AndroidView
import com.abei.splitplay.media.PlayerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    initialUri: Uri,
    onBack: () -> Unit = {},
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(initialUri) {
        if (viewModel.uiState.value.mediaUri != initialUri) {
            viewModel.setMedia(initialUri)
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && state.playback.isPlaying) {
                viewModel.togglePlayPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // System bars visibility
    val window = remember(view) { (context as ComponentActivity).window }
    val insetsController = remember(window) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(state.isFullscreen) {
        if (state.isFullscreen) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    // Auto-hide controls in fullscreen
    LaunchedEffect(state.isFullscreen, state.showControls) {
        if (state.isFullscreen && state.showControls) {
            delay(3000)
            viewModel.setControls(false)
        }
    }

    var settingsOpen by remember { mutableStateOf(false) }
    SettingsDialog(
        open = settingsOpen,
        prefs = viewModel.enginePrefs,
        onDismiss = { settingsOpen = false },
        viewModel = viewModel,
    )

    if (state.isFullscreen) {
        FullscreenPlayer(state, viewModel)
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("视频播放") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        ) { padding ->
            // 详情页 = 视频 + 控件 + 速度 + 视频信息,整个可滚动。
            // 引擎/服务器设置改走 TopAppBar 右上的弹窗,详情页内容更干净。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                VideoSurface(
                    viewModel = viewModel,
                    isFullscreen = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.mediaUri != null) {
                    Controls(state, viewModel)
                    if (state.playbackMode == PlaybackMode.SPLIT) {
                        SplitChannelBars(viewModel)
                    }
                    SpeedSection(state.playback.playbackSpeed, viewModel::setSpeed)
                    VideoInfoSection(state)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // Handle back press in fullscreen
    DisposableEffect(state.isFullscreen) {
        val callback = object : androidx.activity.OnBackPressedCallback(state.isFullscreen) {
            override fun handleOnBackPressed() {
                viewModel.setFullscreen(false)
            }
        }
        (context as ComponentActivity).onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/**
 * 把 (fitMode, 视频原始宽高) 折算成 EngineSurface 应该用的 Modifier。
 *  - STRETCH:不约束比例,SurfaceView 撑满,ExoPlayer/Ijk 按 fillMaxSize 渲染,会变形
 *  - 其它模式:加 [Modifier.aspectRatio],SurfaceView 自身就是目标比例,渲染到
 *    自己范围内不会失真;外层容器靠 [Alignment.Center] + 黑色背景天然形成黑边
 *
 * 详情页固定走 FIT_AUTO;全屏才让用户选。
 */
private fun fitModifier(mode: VideoFitMode, videoW: Int, videoH: Int): Modifier {
    val sourceRatio = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 16f / 9f
    return when (mode) {
        VideoFitMode.STRETCH -> Modifier.fillMaxSize()
        VideoFitMode.FIT_AUTO -> Modifier.aspectRatio(sourceRatio)
        VideoFitMode.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
        VideoFitMode.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
        VideoFitMode.RATIO_1_1 -> Modifier.aspectRatio(1f)
    }
}

@Composable
private fun VideoSurface(
    viewModel: PlayerViewModel,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sink by viewModel.videoSink.collectAsStateWithLifecycle()
    // 详情页强制 FIT_AUTO(用户没机会改);全屏走 ViewModel 里的当前选择。
    val effectiveMode = if (isFullscreen) state.fitMode else VideoFitMode.FIT_AUTO
    Box(
        modifier = modifier
            .then(if (isFullscreen) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // 外屏模式下本地 SurfaceView 仍然存在 —— 但它给 engine 喂的 surface 会被
        // VM 按 sink 守卫扣下,本地框是黑底空播,正是预期。
        EngineSurface(
            viewModel = viewModel,
            modifier = fitModifier(effectiveMode, state.playback.videoWidth, state.playback.videoHeight),
        )
        // sink 从 External 切回 Local 时,本地 SurfaceView 可能没经历销毁/重建
        //(Compose 不会重新构建 AndroidView)所以 surfaceCreated 不会再触发。
        // 这里手动在 sink 变化时回调一次 VM 让它走 bindLocalSurface 路径 —— VM 内部
        // 拿不到 Surface 实例,所以这里实际由 EngineSurface 内部的 SurfaceHolder.Callback
        // 处理:见 EngineSurface 上的 sink 监听。但是 SurfaceHolder.Callback 已经早就
        // 注册了,只能用一个外部触发器。简化做法:外屏切回主屏时让 EngineSurface 重组
        // 即可,这里通过给 Modifier 加一个 sink 派生 key 实现(其实就是 fillMaxSize 不变,
        // 改这个 modifier 不会 attach 新 view)。
        // → 真正的方案在 EngineSurface 内部用 DisposableEffect(sink) 触发 rebind。
        if (!isFullscreen) {
            IconButton(
                onClick = viewModel::toggleFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.Fullscreen,
                    contentDescription = "全屏",
                    tint = Color.White,
                )
            }
        }
        // 外屏模式下叠加一个提示,告诉用户画面去了哪里。
        if (sink is PlayerViewModel.VideoSink.External) {
            val selected by viewModel.selectedDisplay.collectAsStateWithLifecycle()
            Text(
                "画面已投到 ${selected?.name ?: "外接屏"}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * 引擎无关的视频输出层。内部包一个 [SurfaceView],surface 创建/销毁时通过
 * [PlayerViewModel.bindLocalSurface] 喂给引擎 —— 该方法内部会按 `videoSink` 守卫:
 * 只有当画面应该留在本地时才真的 `engine.setVideoSurface(...)`,否则不动,
 * 让外接屏 Presentation 那条链独占 surface。
 *
 * 处理 sink 切换:外屏切回主屏时本地 SurfaceView 没经历销毁/重建([SurfaceHolder.Callback]
 * 不会再触发),需要手动重绑。这里用 [LaunchedEffect] 监听 `videoSink`,变化时取当前
 * holder.surface 再调一次 [PlayerViewModel.bindLocalSurface]。
 *
 * 这样 ExoPlayer / IjkPlayer / 自实现内核 都套同一个 Composable,不再依赖 Media3 的
 * `PlayerSurface(player)`——后者要 Media3 Player 实例,IjkPlayer 满足不了。
 */
@Composable
private fun EngineSurface(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val sink by viewModel.videoSink.collectAsStateWithLifecycle()
    val engine by viewModel.engineFlow.collectAsStateWithLifecycle()
    // SurfaceView 引用在 factory 里赋值,LaunchedEffect 里用作主屏重绑入口。
    val surfaceViewRef = remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        viewModel.bindLocalSurface(holder.surface)
                    }
                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        // 尺寸变化由系统通知 native 端,这里不用再 setVideoSurface 一次。
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // 必须传 null 让引擎释放对这个 Surface 的引用,否则
                        // detach 后 native 还往一块已销毁的内存里 blit。
                        // ViewModel 会按 sink 守卫;外屏模式下这个 null 不会传给 engine。
                        viewModel.bindLocalSurface(null)
                    }
                })
                surfaceViewRef.value = this
            }
        },
    )
    // sink 切换 / 模式切换都需要主动重绑:
    //  - 切回主屏:本地 SurfaceView 仍在,holder.surface 也活着,但 engine 端已被 selectDisplay
    //    解绑过(setVideoSurface(null))
    //  - 切换播放模式:engine 实例被换掉,新 engine 默认 Surface = null,需要喂一次
    LaunchedEffect(sink, engine) {
        if (sink is PlayerViewModel.VideoSink.Local) {
            val s = surfaceViewRef.value?.holder?.surface
            if (s != null && s.isValid) {
                viewModel.bindLocalSurface(s)
            }
        }
    }
}

@Composable
private fun FullscreenPlayer(state: PlayerUiState, viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    // 全方向拖动产生的内容偏移(px);progress 是位移向量长度 / dismissThreshold 截断到 [0,1],
    // 用来同步驱动 scale/alpha,产生 TG 那种"按下→跟手缩小淡出→放手收/弹回"的渐进感。
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var menuExpanded by remember { mutableStateOf(false) }

    val openExternalPlayer: () -> Unit = {
        val uri = viewModel.uiState.value.mediaUri
        if (uri != null) {
            launchExternalPlayer(activity, uri)
        }
    }
    val enterPip: () -> Unit = { enterPictureInPicture(activity) }
    val sendFeedback: () -> Unit = { launchFeedback(activity) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                val viewCfg = viewConfiguration
                val tapTimeout = viewCfg.doubleTapTimeoutMillis
                val longPressTimeout = viewCfg.longPressTimeoutMillis
                val slop = viewCfg.touchSlop

                // 跨手势复用的多击计数状态。
                var lastTapMs = 0L
                var lastTapSideLeft: Boolean? = null
                var tapCount = 0
                var pendingTapJob: Job? = null
                // dismiss 释放后的弹回/继续动画 job;新一次按下要先把它取消,
                // 否则手指按住时 dragY 还在被动画线程改写。
                var dismissJob: Job? = null

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dismissJob?.cancel()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val edge = w * 0.1f
                    val isEdge = down.position.x < edge || down.position.x > w - edge

                    // 控件可见时,顶部 ~96dp / 底部 ~96dp 是工具栏区域,
                    // 这两条不响应 dismiss/seek 多击,让里面的 Slider / IconButton 自己接管手势。
                    val showCtrl = viewModel.uiState.value.showControls
                    if (showCtrl) {
                        val chromePx = 96.dp.toPx()
                        val onChrome = down.position.y < chromePx || down.position.y > h - chromePx
                        if (onChrome) return@awaitEachGesture
                    }

                    var isDrag = false
                    var released = false

                    // Phase A: 在长按阈值内等待 → 决定是 tap、drag、还是 long-press。
                    // 任意方向超过 touchSlop 都进入 dismiss 模式(全方向跟手关闭)。
                    withTimeoutOrNull(longPressTimeout) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull
                            if (!ch.pressed) {
                                released = true
                                return@withTimeoutOrNull
                            }
                            val dx = ch.position.x - down.position.x
                            val dy = ch.position.y - down.position.y
                            if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                                isDrag = true
                                return@withTimeoutOrNull
                            }
                        }
                    }

                    when {
                        isDrag -> {
                            // TG 风格:任意方向跟手拖,松手过阈值就关闭全屏,否则弹回原位。
                            pendingTapJob?.cancel()
                            tapCount = 0
                            lastTapSideLeft = null
                            var lastX = down.position.x
                            var lastY = down.position.y
                            var pressed = true
                            while (pressed) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                dragX += ch.position.x - lastX
                                dragY += ch.position.y - lastY
                                lastX = ch.position.x
                                lastY = ch.position.y
                                pressed = ch.pressed
                            }
                            // awaitEachGesture 所在的 AwaitPointerEventScope 是 @RestrictsSuspension,
                            // 不能在里面直接 await Animatable.animateTo —— 必须扔到普通的 CoroutineScope 里跑。
                            val finalOffset = Offset(dragX, dragY)
                            val mag = kotlin.math.sqrt(
                                finalOffset.x * finalOffset.x + finalOffset.y * finalOffset.y
                            )
                            val threshold = kotlin.math.min(w, h) * 0.20f
                            dismissJob = scope.launch {
                                val anim = Animatable(finalOffset, Offset.VectorConverter)
                                if (mag > threshold && mag > 0f) {
                                    // 沿当前位移方向继续飞出屏外,距离取屏幕对角线保证完全离屏。
                                    val flyDistance = kotlin.math.sqrt(w * w + h * h)
                                    val target = finalOffset * (flyDistance / mag)
                                    anim.animateTo(target, tween(200)) {
                                        dragX = value.x
                                        dragY = value.y
                                    }
                                    viewModel.setFullscreen(false)
                                    dragX = 0f
                                    dragY = 0f
                                } else {
                                    anim.animateTo(Offset.Zero, spring()) {
                                        dragX = value.x
                                        dragY = value.y
                                    }
                                }
                            }
                        }
                        released -> {
                            val now = down.uptimeMillis
                            val side = down.position.x < w / 2
                            if (lastTapSideLeft == side && now - lastTapMs < tapTimeout) {
                                tapCount++
                            } else {
                                tapCount = 1
                                lastTapSideLeft = side
                            }
                            lastTapMs = now
                            pendingTapJob?.cancel()
                            if (tapCount == 1) {
                                pendingTapJob = scope.launch {
                                    delay(tapTimeout)
                                    viewModel.toggleControls()
                                    tapCount = 0
                                    lastTapSideLeft = null
                                }
                            } else {
                                val deltaMs = if (side) -10_000L else 10_000L
                                viewModel.seekRelative(deltaMs)
                            }
                        }
                        else -> {
                            if (isEdge) {
                                viewModel.startSpeedBoost()
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    viewModel.endSpeedBoost()
                                }
                            } else {
                                waitForUpOrCancellation()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 一切跟随拖动的视觉层都包裹在 dismissContent 里(画面 + HUD + 控件栏),
        // 这样关闭时是整体一起缩小淡出,而不是只动画面留下控件。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val w = size.width.takeIf { it > 0f } ?: 1f
                    val h = size.height.takeIf { it > 0f } ?: 1f
                    val mag = kotlin.math.sqrt(dragX * dragX + dragY * dragY)
                    val progress = (mag / (kotlin.math.min(w, h) * 0.20f)).coerceIn(0f, 1f)
                    translationX = dragX
                    translationY = dragY
                    val s = 1f - progress * 0.15f
                    scaleX = s
                    scaleY = s
                    alpha = 1f - progress * 0.4f
                },
            contentAlignment = Alignment.Center,
        ) {
            EngineSurface(
                viewModel = viewModel,
                // 全屏:用 state.fitMode + 视频原始宽高决定 surface 实际尺寸。
                // 非 STRETCH 时 surface 比容器小,外面 Box(contentAlignment=Center) 把它居中,
                // 上下/左右自然就成了黑边,不会再被强行拉伸。
                modifier = fitModifier(state.fitMode, state.playback.videoWidth, state.playback.videoHeight),
            )

            state.hud?.let { hud ->
                HudOverlay(hud = hud, modifier = Modifier.align(Alignment.Center))
            }

            AnimatedVisibility(
                visible = state.isSpeedBoosting,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp),
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("2.0× ▶▶", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }

            AnimatedVisibility(
                visible = state.showControls,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 顶部:状态栏(全屏隐藏时为 0)和挖孔/刘海安全区取 union,内容才不会被遮。
                    val topInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(topInsets.asPaddingValues())
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.setFullscreen(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出全屏", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        var fitMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { fitMenuExpanded = true }) {
                                Icon(Icons.Filled.AspectRatio, "画面比例", tint = Color.White)
                            }
                            FitModeMenu(
                                expanded = fitMenuExpanded,
                                current = state.fitMode,
                                onDismissRequest = { fitMenuExpanded = false },
                                onPick = { viewModel.setFitMode(it) },
                            )
                        }
                        IconButton(onClick = viewModel::toggleFullscreen) {
                            Icon(Icons.Filled.FullscreenExit, "退出全屏", tint = Color.White)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, "更多", tint = Color.White)
                            }
                            MoreMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                onEnterPip = enterPip,
                                onOpenExternal = openExternalPlayer,
                                onFeedback = sendFeedback,
                            )
                        }
                    }

                // Center play/pause
                IconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Icon(
                        if (state.playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playback.isPlaying) "暂停" else "播放",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.height(64.dp).width(64.dp),
                    )
                }

                // Bottom controls
                // 横屏挖孔屏:左右刘海+底部手势条/导航条都要避开。
                val bottomInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(bottomInsets.asPaddingValues())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            formatTime(state.playback.positionMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                        CompactSlider(
                            value = if (state.playback.durationMs > 0) {
                                state.playback.positionMs.toFloat() / state.playback.durationMs.toFloat()
                            } else 0f,
                            onValueChange = { fraction ->
                                if (state.playback.durationMs > 0) {
                                    viewModel.seekTo((fraction * state.playback.durationMs).toLong())
                                }
                            },
                            enabled = state.playback.durationMs > 0,
                            modifier = Modifier.weight(1f),
                            onDark = true,
                        )
                        Text(
                            formatTime(state.playback.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, viewModel: PlayerViewModel) {
    val playback = state.playback
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = viewModel::togglePlayPause) {
                Icon(
                    if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.isPlaying) "暂停" else "播放",
                )
            }
            Text(
                formatTime(playback.positionMs) + " / " + formatTime(playback.durationMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        CompactSlider(
            value = if (playback.durationMs > 0) {
                playback.positionMs.toFloat() / playback.durationMs.toFloat()
            } else 0f,
            onValueChange = { fraction ->
                if (playback.durationMs > 0) {
                    viewModel.seekTo((fraction * playback.durationMs).toLong())
                }
            },
            enabled = playback.durationMs > 0,
        )

        playback.error?.let { err ->
            Text(
                "错误: ${err.message ?: err.javaClass.simpleName}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * SPLIT 模式下详情页的额外两条控制条 —— 音频通道、视频通道独立 pause/resume。
 * 主控件 [Controls] 仍然在,但走的是整机操作(广播给两个子引擎),这两条只发命令到
 * 一侧。视频条暂停 → 画面冻结但声音继续(场景 A 的"静音 MV");音频条暂停 → 声音停
 * 但画面继续。
 *
 * 子引擎 state 用 collectAsStateWithLifecycle 单独订阅,UI 只 reflect 自己那一通道。
 */
@Composable
private fun SplitChannelBars(viewModel: PlayerViewModel) {
    val audio by viewModel.audioSubEngine.collectAsStateWithLifecycle()
    val video by viewModel.videoSubEngine.collectAsStateWithLifecycle()
    if (audio == null || video == null) return
    val audioState by audio!!.state.collectAsStateWithLifecycle()
    val videoState by video!!.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChannelControlBar(
            label = "音频",
            isPlaying = audioState.isPlaying,
            onToggle = viewModel::togglePlayPauseAudio,
        )
        ChannelControlBar(
            label = "视频",
            isPlaying = videoState.isPlaying,
            onToggle = viewModel::togglePlayPauseVideo,
        )
    }
}

@Composable
private fun ChannelControlBar(
    label: String,
    isPlaying: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停 $label" else "播放 $label",
            )
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (isPlaying) "播放中" else "已暂停",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 全屏手势触发时的居中浮层提示:目前只有进度跳转(双击 ±10s / 横向 drag-seek)。 */
@Composable
private fun HudOverlay(hud: GestureHud, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        when (hud) {
            is GestureHud.Seek -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${formatTime(hud.targetMs)} / ${formatTime(hud.durationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val sign = if (hud.deltaMs >= 0) "+" else ""
                    Text(
                        "$sign${hud.deltaMs / 1000}s",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * 修复 M3 默认 Slider 的 thumb(高瘦药丸形 4×44dp) 与 track(16dp) 视觉不协调的问题：
 * 改用 14dp 圆形 thumb + 4dp 细 track,thumb 和 track 在视觉上成正比。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDark: Boolean = false,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
) {
    val activeColor = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    val inactiveColor = if (onDark) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant
    val colors = SliderDefaults.colors(
        thumbColor = activeColor,
        activeTrackColor = activeColor,
        inactiveTrackColor = inactiveColor,
        disabledThumbColor = activeColor.copy(alpha = 0.5f),
        disabledActiveTrackColor = activeColor.copy(alpha = 0.3f),
        disabledInactiveTrackColor = inactiveColor.copy(alpha = 0.5f),
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        steps = steps,
        colors = colors,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (enabled) activeColor else activeColor.copy(alpha = 0.5f), CircleShape),
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                colors = colors,
                enabled = enabled,
                modifier = Modifier.height(4.dp),
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
                drawStopIndicator = null,
            )
        },
    )
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
private fun FitModeMenu(
    expanded: Boolean,
    current: VideoFitMode,
    onDismissRequest: () -> Unit,
    onPick: (VideoFitMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        VideoFitMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                leadingIcon = {
                    // 当前选中给个 √,没选中留 16dp 空位让文字纵向对齐。
                    if (mode == current) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    } else {
                        Spacer(Modifier.width(24.dp))
                    }
                },
                onClick = {
                    onDismissRequest()
                    onPick(mode)
                },
            )
        }
    }
}

@Composable
private fun MoreMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenExternal: () -> Unit,
    onFeedback: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DropdownMenuItem(
            text = { Text("小窗播放") },
            leadingIcon = { Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null) },
            onClick = {
                onDismissRequest()
                onEnterPip()
            },
        )
        DropdownMenuItem(
            text = { Text("用其他播放器打开") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = {
                onDismissRequest()
                onOpenExternal()
            },
        )
        DropdownMenuItem(
            text = { Text("问题反馈") },
            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
            onClick = {
                onDismissRequest()
                onFeedback()
            },
        )
    }
}

/** 进入画中画。API 26+ 才支持;低版本提示一下。 */
private fun enterPictureInPicture(activity: ComponentActivity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            activity.enterPictureInPictureMode(params)
        }.onFailure {
            Toast.makeText(activity, "当前设备不支持小窗播放", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(activity, "Android 8.0 以下不支持小窗播放", Toast.LENGTH_SHORT).show()
    }
}

/** 把当前媒体 URI 通过 ACTION_VIEW 转给系统里其他播放器,排除自身避免再回到本应用。 */
private fun launchExternalPlayer(activity: ComponentActivity, uri: Uri) {
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(viewIntent, "选择播放器").apply {
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(activity, activity.javaClass)),
        )
    }
    runCatching { activity.startActivity(chooser) }
        .onFailure {
            Toast.makeText(activity, "没有可用的播放器", Toast.LENGTH_SHORT).show()
        }
}

private fun launchFeedback(activity: ComponentActivity) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf("3239155230@qq.com"))
        putExtra(Intent.EXTRA_SUBJECT, "[本地播放器] 问题反馈")
        putExtra(Intent.EXTRA_TEXT, "请描述您遇到的问题或建议:\n\n\n--- 环境 ---\nAndroid ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}\n")
    }
    try {
        activity.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(activity, "未找到邮件客户端", Toast.LENGTH_SHORT).show()
    }
}
