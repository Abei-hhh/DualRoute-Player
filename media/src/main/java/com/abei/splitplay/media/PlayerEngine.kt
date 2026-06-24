package com.abei.splitplay.media

import android.media.AudioDeviceInfo
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放引擎抽象。不暴露 Media3 类型,这样 IjkPlayer / 自实现内核 / 未来其他引擎
 * 都能套同一份 UI:Compose 端创建 SurfaceView,把它的 [Surface] 喂给 [setVideoSurface]。
 */
interface PlayerEngine {
    val state: StateFlow<PlaybackState>

    fun setMedia(uri: Uri)

    /** 带起始位置的 setMedia,用于续播;默认实现退化为先 [setMedia] 再 [seekTo]。 */
    fun setMedia(uri: Uri, startPositionMs: Long) {
        setMedia(uri)
        if (startPositionMs > 0) seekTo(startPositionMs)
    }

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)

    /** 绑定视频输出。Surface 销毁时传 `null`,引擎需要释放对它的引用避免 native 端继续写入。 */
    fun setVideoSurface(surface: Surface?)

    /**
     * 指定首选音频输出设备。`null` 表示交还给系统默认路由。
     *
     * 默认 no-op —— 不支持设备路由的内核(IjkPlayer / 自实现等)直接忽略,
     * 这样调用方不需要做类型分支,Phase 1 只是没效果而已,不会崩。
     */
    fun setPreferredAudioDevice(info: AudioDeviceInfo?) = Unit

    /**
     * 容器当前暴露的音轨/字幕轨。空列表表示该引擎暂不支持(或正在 prepare)。
     * 默认空 StateFlow —— IjkPlayer / 自实现引擎可以不实现这部分,UI 会自然隐藏入口。
     *
     * 字幕轨列表中含一个 [TrackOption.isDisable] 为 true 的"关闭字幕"项,选中后字幕渲染关闭。
     */
    val audioTracks: StateFlow<List<TrackOption>> get() = EMPTY_TRACKS
    val subtitleTracks: StateFlow<List<TrackOption>> get() = EMPTY_TRACKS

    fun selectTrack(option: TrackOption) = Unit

    /**
     * 当前帧的字幕 cue 列表(按显示时间已过滤好,直接渲染即可)。
     * 默认空 —— 不支持字幕的引擎(IjkPlayer 等)UI 不会显示字幕层。
     */
    val cues: StateFlow<List<SubtitleCue>> get() = EMPTY_CUES

    fun release()

    private companion object {
        val EMPTY_TRACKS: StateFlow<List<TrackOption>> =
            MutableStateFlow<List<TrackOption>>(emptyList()).asStateFlow()
        val EMPTY_CUES: StateFlow<List<SubtitleCue>> =
            MutableStateFlow<List<SubtitleCue>>(emptyList()).asStateFlow()
    }
}

/**
 * 引擎无关的字幕 cue 抽象。Media3 内部有 `androidx.media3.common.text.Cue`,但我们不在
 * [PlayerEngine] 接口暴露 Media3 类型,避免 IjkPlayer / 自实现引擎被绑死。
 *
 * [renderable] 是 Media3 的 `Cue` 对象(实现侧透出),给 :player-ui 的渲染层直接喂给
 * `SubtitleView.setCues(...)` 用;非 Media3 引擎可以传 null,UI 用纯文本兜底渲染 [text]。
 */
data class SubtitleCue(
    val text: String,
    val renderable: Any? = null,
)

/**
 * 一个可选的音轨或字幕轨。`id` 是引擎内部稳定标识(不同引擎实现自由组装),
 * UI 拿来做 selected 判断 + 回传 [PlayerEngine.selectTrack] 时不要修改。
 */
data class TrackOption(
    val id: String,
    val label: String,
    val type: TrackType,
    val isSelected: Boolean,
    /** "关闭字幕"这种虚拟项;只对 [TrackType.SUBTITLE] 有意义。 */
    val isDisable: Boolean = false,
)

enum class TrackType { AUDIO, SUBTITLE }

data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val isReady: Boolean = false,
    val error: Throwable? = null,
    /** 当前轨道的视频宽高(px);未知/纯音频时为 0。 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** 播放到末尾。上层用它触发"自动下一首";engine 自身不会做任何 seek。 */
    val isEnded: Boolean = false,
)
