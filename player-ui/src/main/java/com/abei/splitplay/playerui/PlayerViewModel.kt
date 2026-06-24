package com.abei.splitplay.playerui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abei.splitplay.core.EnginePrefs
import com.abei.splitplay.core.ResumeStore
import com.abei.splitplay.media.AudioDeviceRepository
import com.abei.splitplay.media.AudioOutput
import com.abei.splitplay.media.CapabilityScanner
import com.abei.splitplay.media.CodecCapability
import com.abei.splitplay.media.DisplayInfo
import com.abei.splitplay.media.DisplayRepository
import com.abei.splitplay.media.DualPlayerEngine
import com.abei.splitplay.media.ExoPlayerEngineFactory
import com.abei.splitplay.media.PlaybackState
import com.abei.splitplay.media.PlayerChannel
import com.abei.splitplay.media.PlayerEngine
import com.abei.splitplay.media.PlayerEngineFactory
import com.abei.splitplay.media.PlayerEngineRegistry
import com.abei.splitplay.media.PlayerEngineType
import com.abei.splitplay.media.TrackOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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

/**
 * 播放模式。
 *  - [SINGLE]:单 ExoPlayer 实例,音视频都在里面(M1 默认行为)。
 *  - [SPLIT]:音视频拆分到两个独立 ExoPlayer,两条控制条互不影响,
 *    场景 A(HiFi 音频 + 静音 MV / 不同步独立控制)用。
 */
enum class PlaybackMode { SINGLE, SPLIT }

data class PlayerUiState(
    val mediaUri: Uri? = null,
    val playback: PlaybackState = PlaybackState(),
    val isFullscreen: Boolean = false,
    val showControls: Boolean = true,
    val isSpeedBoosting: Boolean = false,
    val hud: GestureHud? = null,
    /** 全屏下用户可改;详情页始终视为 [VideoFitMode.FIT_AUTO]。 */
    val fitMode: VideoFitMode = VideoFitMode.FIT_AUTO,
    /** SINGLE/SPLIT,UI 用它决定要不要画第二条控制条。 */
    val playbackMode: PlaybackMode = PlaybackMode.SINGLE,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val resumeStore = ResumeStore(app)
    val enginePrefs = EnginePrefs(app)
    private val audioDeviceRepo = AudioDeviceRepository(app, viewModelScope)
    private val displayRepo = DisplayRepository(app, viewModelScope)

    /** 当前可选音频输出快照(物理 sink 子集)。UI 直接拿去 radio 渲染。 */
    val audioOutputs: StateFlow<List<AudioOutput>> = audioDeviceRepo.outputs

    /**
     * 当前选中的音频输出。`null` = 走系统默认路由。
     * 用户手动选 → 持久化到 [EnginePrefs.audioDeviceId];设备热拔后会被 reconcile 清空。
     */
    private val _selectedAudioOutput = MutableStateFlow<AudioOutput?>(null)
    val selectedAudioOutput: StateFlow<AudioOutput?> = _selectedAudioOutput

    /** 当前可选显示设备(主屏 + 所有外接 Presentation 屏)。 */
    val displays: StateFlow<List<DisplayInfo>> = displayRepo.displays

    /**
     * 设备能力矩阵。lazy 扫描:首次有人 collect 时才跑(几十毫秒级,在 IO scheduler 上)。
     * 系统编解码列表不会跑着变,扫一次就够,缓存在 StateFlow 里。
     */
    private val _capabilities = MutableStateFlow<List<CodecCapability>>(emptyList())
    val capabilities: StateFlow<List<CodecCapability>> = _capabilities

    /** 选中的显示设备。`null` 或 isMain 都代表"画面留在主屏"。 */
    private val _selectedDisplay = MutableStateFlow<DisplayInfo?>(null)
    val selectedDisplay: StateFlow<DisplayInfo?> = _selectedDisplay

    /**
     * 视频画面去向。`Local` = 本地 SurfaceView,`External(id)` = 外接 Presentation。
     * EngineSurface 用这个值决定是否要 `engine.setVideoSurface(holder.surface)` —— 切到
     * External 时必须传 `null` 让 engine 释放本地 Surface 引用,避免双重 attach。
     */
    sealed class VideoSink {
        object Local : VideoSink()
        data class External(val displayId: Int) : VideoSink()
    }

    private val _videoSink = MutableStateFlow<VideoSink>(VideoSink.Local)
    val videoSink: StateFlow<VideoSink> = _videoSink

    // 播放模式 + 内核类型一起决定 engine 实例。两者都是同步 runBlocking 从 DataStore 读出来 ——
    // ViewModel 构造必须同步;DataStore 在已写入后命中内存缓存,read 是常数级。
    private val initialMode: PlaybackMode = runCatching {
        runBlocking { enginePrefs.playbackMode.first() }
            ?.let { PlaybackMode.valueOf(it) }
    }.getOrNull() ?: PlaybackMode.SINGLE

    private val _playbackMode = MutableStateFlow(initialMode)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode

    /**
     * 当前 engine 实例。**包内 `var`,模式切换时会换新实例。**
     * UI 通过 [bindLocalSurface] / [selectAudioOutput] 等方法间接调引擎,不直接读这个属性,
     * 避免在 swap 瞬间拿到 stale 引用。
     */
    private val _engine: MutableStateFlow<PlayerEngine> =
        MutableStateFlow(createEngine(app, initialMode))

    private fun engineFactory(): PlayerEngineFactory {
        val saved = runBlocking { enginePrefs.engineType.first() }
        val type = saved
            ?.let { runCatching { PlayerEngineType.valueOf(it) }.getOrNull() }
            ?: PlayerEngineType.EXOPLAYER
        return PlayerEngineRegistry.factory(type)
    }

    /**
     * 根据 [EnginePrefs] 的引擎内核选择 + 当前 [PlaybackMode] 实例化一个 [PlayerEngine]。
     * 没实现的内核 ([PlayerEngineType.IJK] / [PlayerEngineType.CUSTOM])会抛
     * [NotImplementedError],这里兜底回退到 ExoPlayer,让用户至少能继续看视频。
     */
    private fun createEngine(app: Application, mode: PlaybackMode): PlayerEngine {
        val factory = engineFactory()
        return runCatching {
            when (mode) {
                PlaybackMode.SINGLE ->
                    factory.create(app, viewModelScope, PlayerChannel.BOTH)
                PlaybackMode.SPLIT ->
                    DualPlayerEngine(
                        audio = factory.create(app, viewModelScope, PlayerChannel.AUDIO_ONLY),
                        video = factory.create(app, viewModelScope, PlayerChannel.VIDEO_ONLY),
                        scope = viewModelScope,
                    )
            }
        }.getOrElse {
            // 未实现的内核 / 初始化失败 → 本次会话兜底用 ExoPlayer。
            // 不写回 pref,保留用户选择,等对应引擎后续真接入了再生效。
            when (mode) {
                PlaybackMode.SINGLE ->
                    ExoPlayerEngineFactory.create(app, viewModelScope, PlayerChannel.BOTH)
                PlaybackMode.SPLIT ->
                    DualPlayerEngine(
                        audio = ExoPlayerEngineFactory.create(app, viewModelScope, PlayerChannel.AUDIO_ONLY),
                        video = ExoPlayerEngineFactory.create(app, viewModelScope, PlayerChannel.VIDEO_ONLY),
                        scope = viewModelScope,
                    )
            }
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

    /**
     * Combined playback state。engine 实例可能在运行中被换掉(模式切换),所以这里走
     * `_engine.flatMapLatest { it.state }` —— 每换一次 engine 自动重新订阅其 state flow,
     * 不会拿到旧 engine 的孤儿值。
     */
    private val combinedPlayback: StateFlow<PlaybackState> =
        _engine
            .flatMapLatest { it.state }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackState())

    /** 当前可选音轨/字幕轨,跟随 engine 实例切换自动重订阅。 */
    val audioTracks: StateFlow<List<TrackOption>> =
        _engine.flatMapLatest { it.audioTracks }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subtitleTracks: StateFlow<List<TrackOption>> =
        _engine.flatMapLatest { it.subtitleTracks }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectTrack(option: TrackOption) = _engine.value.selectTrack(option)

    val uiState: StateFlow<PlayerUiState> =
        combine(
            _mediaUri,
            combinedPlayback,
            controlsFlow,
            _hud,
            _fitMode,
            _playbackMode,
        ) { values ->
            val uri = values[0] as Uri?
            val pb = values[1] as PlaybackState
            @Suppress("UNCHECKED_CAST")
            val ctrl = values[2] as Triple<Boolean, Boolean, Boolean>
            val hud = values[3] as GestureHud?
            val fit = values[4] as VideoFitMode
            val mode = values[5] as PlaybackMode
            PlayerUiState(uri, pb, ctrl.first, ctrl.second, ctrl.third, hud, fit, mode)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState(playbackMode = initialMode))

    /** 暴露音频/视频子引擎的便利访问,SPLIT 模式才非空。UI 双控制条用。 */
    val audioSubEngine: StateFlow<PlayerEngine?> = _engine
        .let { flow ->
            val mutable = MutableStateFlow<PlayerEngine?>((_engine.value as? DualPlayerEngine)?.audio)
            viewModelScope.launch {
                flow.collect { e -> mutable.value = (e as? DualPlayerEngine)?.audio }
            }
            mutable
        }
    val videoSubEngine: StateFlow<PlayerEngine?> = _engine
        .let { flow ->
            val mutable = MutableStateFlow<PlayerEngine?>((_engine.value as? DualPlayerEngine)?.video)
            viewModelScope.launch {
                flow.collect { e -> mutable.value = (e as? DualPlayerEngine)?.video }
            }
            mutable
        }

    init {
        // 续播位置持久化:播放中每偏移 >5s 存一次;靠近结尾(<=5s)直接清掉避免下次又从结尾起播。
        // engine 在切模式时会被替换,所以监听 combinedPlayback 而不是 engine.state 直访问。
        viewModelScope.launch {
            combinedPlayback.collect { st ->
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

        // 音频路由:启动设备监听 + 跟随设备列表 reconcile 持久化偏好。
        //  - 列表里有持久化 id → 选中 + 调 setPreferredAudioDevice
        //  - 列表里没有(被拔/没插) → 清掉选择并回退系统默认
        // 单一来源:跟 outputs 一起 combine 持久化 id,避免两条 collect 互相打架。
        audioDeviceRepo.start()
        viewModelScope.launch {
            combine(audioDeviceRepo.outputs, enginePrefs.audioDeviceId) { list, savedId ->
                list to savedId
            }.collect { (list, savedId) ->
                val match = savedId?.let { id -> list.firstOrNull { it.id == id } }
                if (match != _selectedAudioOutput.value) {
                    _selectedAudioOutput.value = match
                    _engine.value.setPreferredAudioDevice(match?.info)
                }
            }
        }

        // 编解码能力扫描:跑在 IO 调度器上避免阻塞主线程构造路径,扫一次落到 StateFlow。
        // MediaCodecList 在系统启动后不会变,不需要 listener / 二次扫描。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _capabilities.value = runCatching { CapabilityScanner.scan() }.getOrElse { emptyList() }
        }

        // 显示设备:监听 hotplug + reconcile 持久化的 displayId。注意 selectDisplay 真正去
        // 调 Presentation 的动作需要 Activity context(VideoOutputController),所以这里
        // 只更新 _selectedDisplay 状态;_videoSink 由调用 selectDisplay(...) 时切换。
        // reconcile 时如果持久化的外接屏不在了 → 自动回主屏(_videoSink = Local)。
        displayRepo.start()
        viewModelScope.launch {
            combine(displayRepo.displays, enginePrefs.displayId) { list, savedId ->
                list to savedId
            }.collect { (list, savedId) ->
                val match = savedId?.let { id -> list.firstOrNull { it.id == id && !it.isMain } }
                if (match != _selectedDisplay.value) {
                    _selectedDisplay.value = match
                    // 外接屏列表里没有持久化 id → 必须把 sink 拨回 Local,
                    // 否则 EngineSurface 会一直认为"应该投外屏"画面就消失了。
                    if (match == null && _videoSink.value !is VideoSink.Local) {
                        _videoSink.value = VideoSink.Local
                    }
                }
            }
        }
    }

    /**
     * 切换播放模式(SINGLE ↔ SPLIT)。代价:重建 engine 实例;期间会:
     *  1) 抓当前 (uri, positionMs, speed) 快照
     *  2) release 旧 engine
     *  3) build 新 engine,reapply 音频路由
     *  4) 如有 media,setMedia(uri, savedPosition) + restore speed + 立刻 play
     * SPLIT 模式同步器会自动启动(在 DualPlayerEngine.init 里 start)。
     *
     * UI 在调这个方法之后会通过 combinedPlayback 的 flatMapLatest 自动重订阅新 engine.state。
     */
    fun setPlaybackMode(mode: PlaybackMode) {
        if (_playbackMode.value == mode) return
        val app = getApplication<Application>()
        val current = _engine.value
        val snap = current.state.value
        val savedUri = _mediaUri.value
        val savedPos = snap.positionMs
        val savedSpeed = snap.playbackSpeed.takeIf { it > 0f } ?: 1f
        val wasPlaying = snap.isPlaying
        // 释放本地 / 外屏 Surface 引用,避免新 engine 起来前还有旧 engine 在 blit。
        current.setVideoSurface(null)
        current.release()
        val next = createEngine(app, mode)
        _engine.value = next
        _playbackMode.value = mode
        // 重新 apply 一次音频路由 —— 新 engine 默认走系统路由。
        next.setPreferredAudioDevice(_selectedAudioOutput.value?.info)
        if (savedUri != null) {
            next.setMedia(savedUri, savedPos)
            next.setPlaybackSpeed(savedSpeed)
            if (!wasPlaying) next.pause()
        }
        viewModelScope.launch { enginePrefs.setPlaybackMode(mode.name) }
        // 注意:Surface 重绑由 PlayerScreen 的 LaunchedEffect(sink) 触发(sink 没变,
        // 但 engine 变了)。这里直接通过 bindLocalSurface 的 sink 守卫顺手处理:
        // - sink == Local → 等 PlayerScreen 的 LaunchedEffect(engineFlow) 重绑
        //   (它监听 _engine 的变化,见 EngineSurface)。
        // - sink == External → 选择重置:外屏 Surface 已经被旧 engine 释放,需要 UI 重新调
        //   selectDisplay 才能继续投屏。这里简化为切回 Local,让用户重新选。
        if (_videoSink.value is VideoSink.External) {
            _videoSink.value = VideoSink.Local
            _selectedDisplay.value = null
            viewModelScope.launch { enginePrefs.setDisplayId(null) }
        }
    }

    /**
     * 用户在设置弹窗里选了一块屏。`null` / 主屏:dismiss 外接 Presentation,sink 回 Local。
     * 外接屏:通过注入的 [VideoOutputController] 开 Presentation,Presentation 内部
     * 的 SurfaceHolder.Callback 把 Surface 传回来,我们再 `engine.setVideoSurface(it)`。
     *
     * controller 由 :app 通过 [LocalVideoOutputController] 注入;为了避免 ViewModel
     * 直接持 Activity 引用,这里在每次调用时由 UI 层传入。
     */
    fun selectDisplay(target: DisplayInfo?, controller: VideoOutputController?) {
        val effective = target?.takeUnless { it.isMain }
        _selectedDisplay.value = effective
        viewModelScope.launch { enginePrefs.setDisplayId(effective?.id) }

        if (effective == null) {
            controller?.dismissExternal()
            _videoSink.value = VideoSink.Local
            return
        }
        // 切到外屏:先解绑本地 Surface,再让 Activity 起 Presentation;
        // Presentation 的 SurfaceHolder 回调里再调 engine.setVideoSurface(surface)。
        _videoSink.value = VideoSink.External(effective.id)
        _engine.value.setVideoSurface(null)
        controller?.showOnExternal(effective.raw) { surface ->
            // surface 为 null 时(Presentation 销毁)engine 自动解绑;非 null 时绑定到外屏。
            _engine.value.setVideoSurface(surface)
        }
    }

    /**
     * EngineSurface 在本地 SurfaceView attach 时调用 —— 只有 sink == Local 才真的绑定。
     * 集中走这一道闸门:UI 层不直接调 engine.setVideoSurface,避免和外屏路径竞争。
     *
     * 模式切换后 engine 实例换了,UI 也会通过 [PlayerScreen.LaunchedEffect(engineFlow)]
     * 触发再调一次,把新 engine 绑到本地 Surface。
     */
    fun bindLocalSurface(surface: android.view.Surface?) {
        if (_videoSink.value is VideoSink.Local) {
            _engine.value.setVideoSurface(surface)
        }
        // 外屏模式下本地 Surface 创建/销毁都不影响渲染目标。
    }

    /** 提供给 PlayerScreen 的 LaunchedEffect 监听 engine 变化用。 */
    val engineFlow: StateFlow<PlayerEngine> = _engine

    /**
     * 用户在设置弹窗里手动选择音频输出。`null` = 系统默认。
     * 持久化由 [EnginePrefs.audioDeviceId] flow 驱动的 reconcile 协程统一 apply,
     * 这里只负责把选择写进 DataStore + 立刻乐观更新 UI。
     */
    fun selectAudioOutput(out: AudioOutput?) {
        _selectedAudioOutput.value = out
        _engine.value.setPreferredAudioDevice(out?.info)
        viewModelScope.launch { enginePrefs.setAudioDeviceId(out?.id) }
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
            _engine.value.setMedia(uri, saved)
        }
    }

    fun togglePlayPause() {
        val e = _engine.value
        if (e.state.value.isPlaying) e.pause() else e.play()
    }

    fun seekTo(positionMs: Long) = _engine.value.seekTo(positionMs)

    fun setSpeed(speed: Float) = _engine.value.setPlaybackSpeed(speed)

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
        speedBeforeBoost = _engine.value.state.value.playbackSpeed
        _engine.value.setPlaybackSpeed(2f)
        _isSpeedBoosting.value = true
    }

    /** Restore the speed captured by [startSpeedBoost]. No-op if not boosting. */
    fun endSpeedBoost() {
        if (!_isSpeedBoosting.value) return
        _engine.value.setPlaybackSpeed(speedBeforeBoost)
        _isSpeedBoosting.value = false
    }

    // ---- 双击/多击的 ±10s 累加跳转 ----

    fun seekRelative(deltaMs: Long) {
        val e = _engine.value
        val st = e.state.value
        if (st.durationMs <= 0) return
        val target = (st.positionMs + deltaMs).coerceIn(0L, st.durationMs)
        e.seekTo(target)
        _hud.value = GestureHud.Seek(target, deltaMs, st.durationMs)
        scheduleClearHud(700)
    }

    // ---- Split 模式专用:对单个通道发命令(SINGLE 模式下退化为整机操作)----

    fun togglePlayPauseAudio() = togglePlayPauseChannel(audioSubEngine.value)
    fun togglePlayPauseVideo() = togglePlayPauseChannel(videoSubEngine.value)

    private fun togglePlayPauseChannel(sub: PlayerEngine?) {
        val target = sub ?: _engine.value
        if (target.state.value.isPlaying) target.pause() else target.play()
    }

    private fun scheduleClearHud(delayMs: Long) {
        hudClearJob?.cancel()
        hudClearJob = viewModelScope.launch {
            delay(delayMs)
            _hud.value = null
        }
    }

    override fun onCleared() {
        audioDeviceRepo.stop()
        displayRepo.stop()
        _engine.value.release()
        super.onCleared()
    }
}
