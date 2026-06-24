package com.abei.splitplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single-player engine: wraps a single ExoPlayer instance.
 *
 * 通过 [channel] 决定承载哪些轨道:
 *  - [PlayerChannel.BOTH](默认)= M1 老行为,音视频齐全
 *  - [PlayerChannel.AUDIO_ONLY] = 用 [AudioOnlyRenderersFactory] 物理阻断 video renderer,
 *    再叠加 `TrackSelector.setTrackTypeDisabled(VIDEO, true)` 防 HLS 中途冒视频轨
 *  - [PlayerChannel.VIDEO_ONLY] = 对称做法,无声播视频
 *
 * M2b 的 [DualPlayerEngine] 会创建一对 AUDIO_ONLY + VIDEO_ONLY 实例组合使用。
 */
@OptIn(UnstableApi::class)
class SinglePlayerEngine(
    context: Context,
    scope: CoroutineScope,
    private val channel: PlayerChannel = PlayerChannel.BOTH,
    private val decoderPolicy: DecoderPolicy = DecoderPolicy.AUTO,
) : PlayerEngine {

    // 走带缓存的 MediaSource.Factory:本地 URI 直读不入缓存,http(s) 会走 CacheDataSource
    // → 命中即从磁盘读、未命中边下边播并落盘。无需本地代理(详见 MediaCache 注释)。
    private val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(MediaCache.mediaSourceFactory(context.applicationContext))
        .apply {
            when (channel) {
                PlayerChannel.AUDIO_ONLY ->
                    setRenderersFactory(AudioOnlyRenderersFactory(context.applicationContext))
                PlayerChannel.VIDEO_ONLY ->
                    setRenderersFactory(VideoOnlyRenderersFactory(context.applicationContext))
                PlayerChannel.BOTH -> Unit
            }
            // DecoderPolicy 接入位:
            //  AUTO     —— 默认 RenderersFactory 已经硬解优先,什么都不动
            //  FORCE_HW —— 当前等同 AUTO(默认 factory 没有装额外软解 extension);
            //              :native 接入 FFmpeg 后才需要这里"只硬不软"过滤
            //  FORCE_SW —— TODO M5b :native 上线后:把 RenderersFactory 换成 FfmpegRenderersFactory,
            //              video 端用 FfmpegVideoRenderer,audio 端用 FfmpegAudioRenderer。
            //              当前先吞下参数,行为等同 AUTO,UI 给的提示已经说"软解未启用"。
        }
        .build()
        .also { player ->
            // 双保险:RenderersFactory 阻断后,TrackSelector 再禁一次。HLS 等容器可能
            // 在 prepare 之后动态增加 track,只靠 RenderersFactory 就漏了。
            val disableVideo = channel == PlayerChannel.AUDIO_ONLY
            val disableAudio = channel == PlayerChannel.VIDEO_ONLY
            if (disableVideo || disableAudio) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disableAudio)
                    .build()
            }
        }
    private val _state = MutableStateFlow(PlaybackState())
    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    private val _cues = MutableStateFlow<List<SubtitleCue>>(emptyList())

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()
    override val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()
    override val cues: StateFlow<List<SubtitleCue>> = _cues.asStateFlow()

    /** Compose 端如果需要直接拿 Media3 Player(比如挂 PlayerSurface)走这里。 */
    val player: Player get() = exo

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()
        override fun onPlaybackStateChanged(playbackState: Int) = pushState()
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = pushState()
        override fun onVideoSizeChanged(videoSize: VideoSize) = pushState()
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error) }
        }
        override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)
        override fun onCues(cueGroup: CueGroup) {
            // 把 Media3 Cue 转成中立 SubtitleCue:text 给非渲染消费者(测试/日志)用,
            // renderable 透传原 Cue 给 :player-ui 的 SubtitleView 渲染。
            _cues.value = cueGroup.cues.map { cue ->
                SubtitleCue(
                    text = cue.text?.toString().orEmpty(),
                    renderable = cue,
                )
            }
        }
    }

    init {
        exo.addListener(listener)
        // Position polling — ExoPlayer doesn't push position updates.
        scope.launch {
            while (true) {
                if (exo.isPlaying) pushState()
                delay(POLL_INTERVAL_MS)
            }
        }
        // 暴露 player 引用给后台 Service,只有 BOTH 和 AUDIO_ONLY 通道才注册 —— VIDEO_ONLY
        // 不含音频,挂上去后台只是看着 player 在 idle,没必要。
        if (channel != PlayerChannel.VIDEO_ONLY) {
            BackgroundPlaybackBridge.attach(exo)
        }
    }

    private fun pushState() {
        val size = exo.videoSize
        _state.value = PlaybackState(
            isPlaying = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = exo.duration.takeIf { it > 0 } ?: 0,
            playbackSpeed = exo.playbackParameters.speed,
            isReady = exo.playbackState == Player.STATE_READY,
            error = null,
            videoWidth = size.width,
            videoHeight = size.height,
            isEnded = exo.playbackState == Player.STATE_ENDED,
        )
    }

    override fun setMedia(uri: Uri) = setMedia(uri, 0L)

    /**
     * 带起始位置的 setMedia,用于续播。startPositionMs<=0 等同于普通 setMedia。
     * 直接走 ExoPlayer.setMediaItem(item, startPositionMs),首帧就定位到目标时间,
     * 避免先从 0 开始播放再 seek 出现的画面闪烁。
     *
     * 流播/边下边播由 [MediaCache] 提供的 MediaSource.Factory 在 ExoPlayer.Builder
     * 阶段注入,这里不感知 URI 是本地还是远端。
     */
    override fun setMedia(uri: Uri, startPositionMs: Long) {
        exo.setMediaItem(MediaItem.fromUri(uri), startPositionMs.coerceAtLeast(0L))
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun play() {
        exo.play()
    }

    override fun pause() {
        exo.pause()
    }

    override fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
        pushState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        exo.setPlaybackSpeed(speed)
    }

    override fun setVideoSurface(surface: Surface?) {
        exo.setVideoSurface(surface)
    }

    override fun setPreferredAudioDevice(info: AudioDeviceInfo?) {
        // ExoPlayer 内部转给 DefaultAudioSink → AudioTrack.setPreferredDevice。
        // 传 null 把路由交还系统(等价于"自动"),用于设备被拔掉时 fallback。
        exo.setPreferredAudioDevice(info)
    }

    override fun selectTrack(option: TrackOption) {
        // option.id 格式见 [makeTrackId];这里反解出 (groupIndex, trackIndex) 再走
        // setTrackSelectionParameters → TrackSelectionOverride。groupIndex=-1 表示
        // 这是"关闭字幕"虚拟项。
        val trackType = when (option.type) {
            TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            TrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
        }
        val params = exo.trackSelectionParameters.buildUpon()
        if (option.isDisable) {
            // 关闭对应轨道类型(常用于字幕)。
            params.setTrackTypeDisabled(trackType, true)
                .clearOverridesOfType(trackType)
        } else {
            params.setTrackTypeDisabled(trackType, false)
            val parsed = parseTrackId(option.id) ?: return
            val tracks = exo.currentTracks.groups
            val group = tracks.getOrNull(parsed.groupIndex) ?: return
            val rawGroup: TrackGroup = group.mediaTrackGroup
            params.setOverrideForType(TrackSelectionOverride(rawGroup, parsed.trackIndex))
        }
        exo.trackSelectionParameters = params.build()
    }

    /**
     * Tracks 监听回调:把 currentTracks 映射成扁平的 [TrackOption] 列表,推到 StateFlow 上。
     * 同一个 TrackGroup 里通常一个轨道,但 HLS / DASH 可能多个;扁平展开方便 UI radio。
     */
    private fun refreshTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val subs = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.SUBTITLE
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.mediaTrackGroup.getFormat(trackIndex)
                val label = buildTrackLabel(format.language, format.label, type)
                val option = TrackOption(
                    id = makeTrackId(groupIndex, trackIndex),
                    label = label,
                    type = type,
                    isSelected = group.isTrackSelected(trackIndex),
                )
                if (type == TrackType.AUDIO) audio += option else subs += option
            }
        }
        // 字幕额外加一个"关闭"项,选中即关字幕;当容器无字幕时这个项也就没必要出现。
        if (subs.isNotEmpty()) {
            val isDisabledNow = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                subs.none { it.isSelected }
            subs.add(
                0,
                TrackOption(
                    id = DISABLE_SUBTITLE_ID,
                    label = "关闭字幕",
                    type = TrackType.SUBTITLE,
                    isSelected = isDisabledNow,
                    isDisable = true,
                ),
            )
        }
        _audioTracks.value = audio
        _subtitleTracks.value = subs
    }

    private fun buildTrackLabel(language: String?, label: String?, type: TrackType): String {
        val lang = language?.takeUnless { it.isBlank() || it == "und" }
        val human = label?.takeUnless { it.isBlank() }
        return when {
            human != null && lang != null -> "$human · $lang"
            human != null -> human
            lang != null -> lang
            else -> if (type == TrackType.AUDIO) "默认音轨" else "默认字幕"
        }
    }

    private fun makeTrackId(groupIndex: Int, trackIndex: Int): String =
        "$groupIndex:$trackIndex"

    private fun parseTrackId(id: String): TrackId? {
        val (g, t) = id.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
        return TrackId(g.toIntOrNull() ?: return null, t.toIntOrNull() ?: return null)
    }

    private data class TrackId(val groupIndex: Int, val trackIndex: Int)

    override fun release() {
        BackgroundPlaybackBridge.detach(exo)
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val DISABLE_SUBTITLE_ID = "_disabled"
    }
}
