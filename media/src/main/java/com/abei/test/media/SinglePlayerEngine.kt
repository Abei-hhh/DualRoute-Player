package com.abei.test.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * M1 single-player engine: wraps a single ExoPlayer instance.
 * M2 will split into AudioPlayerEngine / VideoPlayerEngine.
 */
class SinglePlayerEngine(
    context: Context,
    scope: CoroutineScope,
) : PlayerEngine {

    private val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val _state = MutableStateFlow(PlaybackState())

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override val player: Player get() = exo

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()
        override fun onPlaybackStateChanged(playbackState: Int) = pushState()
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = pushState()
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
        _state.value = PlaybackState(
            isPlaying = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = exo.duration.takeIf { it > 0 } ?: 0,
            playbackSpeed = exo.playbackParameters.speed,
            isReady = exo.playbackState == Player.STATE_READY,
            error = null,
        )
    }

    override fun setMedia(uri: Uri) {
        exo.setMediaItem(MediaItem.fromUri(uri))
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

    override fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
