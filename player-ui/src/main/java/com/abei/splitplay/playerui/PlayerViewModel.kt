package com.abei.splitplay.playerui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abei.splitplay.core.EnginePrefs
import com.abei.splitplay.core.ResumeStore
import com.abei.splitplay.media.ExoPlayerEngineFactory
import com.abei.splitplay.media.PlaybackState
import com.abei.splitplay.media.PlayerEngine
import com.abei.splitplay.media.PlayerEngineRegistry
import com.abei.splitplay.media.PlayerEngineType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 全屏手势触发的即时 HUD 反馈,目前只有 seek 跳转;亮度/音量改走系统默认手势。 */
sealed class GestureHud {
    data class Seek(val targetMs: Long, val deltaMs: Long, val durationMs: Long) : GestureHud()
}

/**
 * 画面比例适配模式。
 *  - [FIT_AUTO]      :按视频本身宽高比自适应,黑边可能在上下或左右(默认)
 *  - [STRETCH]       :拉伸到容器,会变形(以前的行为,保留作为强制项)
 *  - [RATIO_16_9]    :强制 16:9
 *  - [RATIO_4_3]     :强制 4:3
 *  - [RATIO_1_1]     :强制 1:1
 */
enum class VideoFitMode(val label: String) {
    FIT_AUTO("自适应"),
    STRETCH("拉伸填满"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_1_1("1:1"),
}

data class PlayerUiState(
    val mediaUri: Uri? = null,
    val playback: PlaybackState = PlaybackState(),
    val isFullscreen: Boolean = false,
    val showControls: Boolean = true,
    val isSpeedBoosting: Boolean = false,
    val hud: GestureHud? = null,
    /** 全屏下用户可改;详情页始终视为 [VideoFitMode.FIT_AUTO]。 */
    val fitMode: VideoFitMode = VideoFitMode.FIT_AUTO,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val resumeStore = ResumeStore(app)
    val enginePrefs = EnginePrefs(app)

    /**
     * 根据 [EnginePrefs] 选择的内核实例化一个 [PlayerEngine]。读 DataStore 用 runBlocking,
     * 因为 ViewModel 构造必须同步;DataStore 在已写入后命中内存缓存,这次 read 是常数级。
     * 没实现的内核 ([PlayerEngineType.IJK] / [PlayerEngineType.CUSTOM])会抛
     * [NotImplementedError],这里兜底回退到 ExoPlayer,让用户至少能继续看视频。
     */
    val engine: PlayerEngine = createEngine(app)

    private fun createEngine(app: Application): PlayerEngine {
        val saved = runBlocking { enginePrefs.engineType.first() }
        val type = saved
            ?.let { runCatching { PlayerEngineType.valueOf(it) }.getOrNull() }
            ?: PlayerEngineType.EXOPLAYER
        return runCatching {
            PlayerEngineRegistry.factory(type).create(app, viewModelScope)
        }.getOrElse {
            // 未实现 / 初始化失败 → 本次会话兜底用 ExoPlayer。
            // 不写回 pref,保留用户选择,等对应引擎后续真接入了再生效。
            ExoPlayerEngineFactory.create(app, viewModelScope)
        }
    }


    private val _mediaUri = MutableStateFlow<Uri?>(null)
    private val _isFullscreen = MutableStateFlow(false)
    private val _showControls = MutableStateFlow(true)
    private val _isSpeedBoosting = MutableStateFlow(false)
    private val _hud = MutableStateFlow<GestureHud?>(null)
    private val _fitMode = MutableStateFlow(VideoFitMode.FIT_AUTO)

    private var speedBeforeBoost: Float = 1f
    private var lastSavedPosition: Long = -1L
    private var hudClearJob: Job? = null

    private val controlsFlow = combine(_isFullscreen, _showControls, _isSpeedBoosting) { fs, sc, sb ->
        Triple(fs, sc, sb)
    }

    val uiState: StateFlow<PlayerUiState> =
        combine(_mediaUri, engine.state, controlsFlow, _hud, _fitMode) { uri, pb, ctrl, hud, fit ->
            val (fs, sc, sb) = ctrl
            PlayerUiState(uri, pb, fs, sc, sb, hud, fit)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    init {
        // 续播位置持久化:播放中每偏移 >5s 存一次;靠近结尾(<=5s)直接清掉避免下次又从结尾起播。
        viewModelScope.launch {
            engine.state.collect { st ->
                val uri = _mediaUri.value ?: return@collect
                if (st.durationMs <= 0) return@collect
                val pos = st.positionMs
                if (pos > st.durationMs - 5_000L) {
                    if (lastSavedPosition >= 0) {
                        lastSavedPosition = -1
                        resumeStore.clear(uri.toString())
                    }
                    return@collect
                }
                if (pos < 5_000L) return@collect
                val shouldSave = !st.isPlaying ||
                    lastSavedPosition < 0 ||
                    kotlin.math.abs(pos - lastSavedPosition) > 5_000L
                if (shouldSave) {
                    lastSavedPosition = pos
                    resumeStore.save(uri.toString(), pos)
                }
            }
        }
    }

    /**
     * 预留:从服务器加载视频,走 progressive HTTP 流播(ExoPlayer 默认支持边下边播)。
     * 当前调用方还没有 UI 入口,但接口先打通 —— 上层只要把远端地址传进来即可。
     */
    fun setRemoteMedia(url: String) = setMedia(Uri.parse(url))

    fun setMedia(uri: Uri) {
        val app = getApplication<Application>()
        // 仅 SAF (ACTION_OPEN_DOCUMENT) 返回的 URI 才支持持久化授权。
        // MediaStore / PhotoPicker 的 URI 调用此 API 会抛 SecurityException。
        if (DocumentsContract.isDocumentUri(app, uri)) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        _mediaUri.value = uri
        lastSavedPosition = -1L
        viewModelScope.launch {
            val saved = resumeStore.load(uri.toString())
            engine.setMedia(uri, saved)
        }
    }

    fun togglePlayPause() {
        if (engine.state.value.isPlaying) engine.pause() else engine.play()
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun setSpeed(speed: Float) = engine.setPlaybackSpeed(speed)

    fun toggleFullscreen() {
        _isFullscreen.value = !_isFullscreen.value
    }

    fun setFullscreen(fullscreen: Boolean) {
        _isFullscreen.value = fullscreen
    }

    fun toggleControls() {
        _showControls.value = !_showControls.value
    }

    fun setFitMode(mode: VideoFitMode) {
        _fitMode.value = mode
    }

    fun setControls(show: Boolean) {
        _showControls.value = show
    }

    /** Save the current speed and switch to 2× — used by fullscreen edge press-and-hold. */
    fun startSpeedBoost() {
        if (_isSpeedBoosting.value) return
        speedBeforeBoost = engine.state.value.playbackSpeed
        engine.setPlaybackSpeed(2f)
        _isSpeedBoosting.value = true
    }

    /** Restore the speed captured by [startSpeedBoost]. No-op if not boosting. */
    fun endSpeedBoost() {
        if (!_isSpeedBoosting.value) return
        engine.setPlaybackSpeed(speedBeforeBoost)
        _isSpeedBoosting.value = false
    }

    // ---- 双击/多击的 ±10s 累加跳转 ----

    fun seekRelative(deltaMs: Long) {
        val st = engine.state.value
        if (st.durationMs <= 0) return
        val target = (st.positionMs + deltaMs).coerceIn(0L, st.durationMs)
        engine.seekTo(target)
        _hud.value = GestureHud.Seek(target, deltaMs, st.durationMs)
        scheduleClearHud(700)
    }

    private fun scheduleClearHud(delayMs: Long) {
        hudClearJob?.cancel()
        hudClearJob = viewModelScope.launch {
            delay(delayMs)
            _hud.value = null
        }
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
