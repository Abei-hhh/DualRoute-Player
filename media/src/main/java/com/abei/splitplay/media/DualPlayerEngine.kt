package com.abei.splitplay.media

import android.media.AudioDeviceInfo
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 把两个独立 [PlayerEngine] 组合成一个,音频走 [audio] 通道、视频走 [video] 通道。
 *
 *  - **同源播同一文件**(默认):两个引擎都拿同一个 [Uri],[PlaybackSyncer] 保持
 *    视频时钟跟随音频。这就是 PRD 的"同时播放"基本形态。
 *  - **独立播放**(PRD 场景 A 雏形):上层直接拿 [audio] / [video] 公开属性,分别调
 *    `setMedia(differentUri)`。Syncer 自动停 —— Δ 来源不一样,同步没意义。
 *
 * combined state:
 *  - `isPlaying = audio.isPlaying || video.isPlaying`(独立暂停时也要诚实反映)
 *  - 时间 / 时长 / 速度取 audio 主时钟
 *  - 视频宽高取 video 通道
 *  - error 任一非空都上报(优先 audio)
 *
 * [setMedia] / [play] / [pause] / [seekTo] / [setPlaybackSpeed] 同步广播到两侧;
 * [setVideoSurface] 只发给 video 通道;[setPreferredAudioDevice] 只发给 audio 通道。
 */
class DualPlayerEngine(
    val audio: PlayerEngine,
    val video: PlayerEngine,
    scope: CoroutineScope,
) : PlayerEngine {

    private val syncer = PlaybackSyncer(audio, video, scope)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        // 把两侧 state 合并成统一视图,UI 拿到的是单一 PlaybackState。
        scope.launch {
            combine(audio.state, video.state) { a, v -> mergeStates(a, v) }
                .collect { merged -> _state.value = merged }
        }
        syncer.start()
    }

    private fun mergeStates(a: PlaybackState, v: PlaybackState): PlaybackState = PlaybackState(
        isPlaying = a.isPlaying || v.isPlaying,
        positionMs = a.positionMs.takeIf { it > 0 } ?: v.positionMs,
        durationMs = if (a.durationMs > 0) a.durationMs else v.durationMs,
        playbackSpeed = a.playbackSpeed,
        // ready 取"双方都 ready 或独自一个 ready"(另一个还没开始也算就绪)
        isReady = a.isReady || v.isReady,
        error = a.error ?: v.error,
        videoWidth = v.videoWidth,
        videoHeight = v.videoHeight,
        // 主时钟(音频)结束才算整体结束 —— 视频比音频先到 EOF 时不触发自动下一首,
        // 让最后一段音频自然放完。
        isEnded = a.isEnded,
    )

    override fun setMedia(uri: Uri) {
        audio.setMedia(uri)
        video.setMedia(uri)
    }

    override fun setMedia(uri: Uri, startPositionMs: Long) {
        audio.setMedia(uri, startPositionMs)
        video.setMedia(uri, startPositionMs)
    }

    override fun play() {
        audio.play()
        video.play()
    }

    override fun pause() {
        audio.pause()
        video.pause()
    }

    override fun seekTo(positionMs: Long) {
        audio.seekTo(positionMs)
        video.seekTo(positionMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        audio.setPlaybackSpeed(speed)
        video.setPlaybackSpeed(speed)
    }

    override fun setVideoSurface(surface: Surface?) {
        video.setVideoSurface(surface)
    }

    override fun setPreferredAudioDevice(info: AudioDeviceInfo?) {
        audio.setPreferredAudioDevice(info)
    }

    // 音轨跟 audio 子引擎走,字幕跟 video 子引擎走 —— 字幕渲染依赖视频时钟。
    override val audioTracks: StateFlow<List<TrackOption>> get() = audio.audioTracks
    override val subtitleTracks: StateFlow<List<TrackOption>> get() = video.subtitleTracks
    override val cues: StateFlow<List<SubtitleCue>> get() = video.cues

    override fun selectTrack(option: TrackOption) {
        when (option.type) {
            TrackType.AUDIO -> audio.selectTrack(option)
            TrackType.SUBTITLE -> video.selectTrack(option)
        }
    }

    override fun release() {
        syncer.stop()
        audio.release()
        video.release()
    }
}
