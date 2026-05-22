package com.abei.test.feature.player

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abei.test.media.PlaybackState
import com.abei.test.media.SinglePlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

data class PlayerUiState(
    val mediaUri: Uri? = null,
    val playback: PlaybackState = PlaybackState(),
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    val engine = SinglePlayerEngine(app, viewModelScope)

    private val _mediaUri = MutableStateFlow<Uri?>(null)

    val uiState: StateFlow<PlayerUiState> =
        combine(_mediaUri, engine.state) { uri, playback ->
            PlayerUiState(mediaUri = uri, playback = playback)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUiState())

    fun onMediaPicked(uri: Uri) {
        // Persist SAF permission so the Uri survives process death.
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        _mediaUri.value = uri
        engine.setMedia(uri)
    }

    fun togglePlayPause() {
        if (engine.state.value.isPlaying) engine.pause() else engine.play()
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun setSpeed(speed: Float) = engine.setPlaybackSpeed(speed)

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}
