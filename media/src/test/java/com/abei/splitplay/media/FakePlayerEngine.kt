package com.abei.splitplay.media

import android.media.AudioDeviceInfo
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 不依赖 Android/Media3 的纯 Kotlin Fake,JVM 测试用。
 * 记录每个调用,允许测试主动 [emit] 一份 [PlaybackState] 模拟 native 回调。
 */
class FakePlayerEngine : PlayerEngine {
    val invocations = mutableListOf<String>()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    var preferredDevice: AudioDeviceInfo? = null
        private set
    var currentSurface: Surface? = null
        private set

    fun emit(s: PlaybackState) { _state.value = s }

    override fun setMedia(uri: Uri) { invocations += "setMedia($uri)" }
    override fun setMedia(uri: Uri, startPositionMs: Long) {
        invocations += "setMedia($uri, $startPositionMs)"
    }
    override fun play() { invocations += "play"; _state.value = _state.value.copy(isPlaying = true) }
    override fun pause() { invocations += "pause"; _state.value = _state.value.copy(isPlaying = false) }
    override fun seekTo(positionMs: Long) {
        invocations += "seekTo($positionMs)"
        _state.value = _state.value.copy(positionMs = positionMs)
    }
    override fun setPlaybackSpeed(speed: Float) {
        invocations += "setPlaybackSpeed($speed)"
        _state.value = _state.value.copy(playbackSpeed = speed)
    }
    override fun setVideoSurface(surface: Surface?) {
        invocations += "setVideoSurface(${if (surface == null) "null" else "surface"})"
        currentSurface = surface
    }
    override fun setPreferredAudioDevice(info: AudioDeviceInfo?) {
        invocations += "setPreferredAudioDevice(${info?.id ?: "null"})"
        preferredDevice = info
    }
    override fun release() { invocations += "release" }
}
