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
import androidx.media3.common.VideoSize
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

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

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

    override fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
