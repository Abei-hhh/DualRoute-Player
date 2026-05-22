package com.abei.test.media

import android.net.Uri
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

interface PlayerEngine {
    val state: StateFlow<PlaybackState>

    /** Underlying Media3 Player, exposed so Compose `PlayerSurface(player)` can render directly. */
    val player: Player

    fun setMedia(uri: Uri)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val isReady: Boolean = false,
    val error: Throwable? = null,
)
