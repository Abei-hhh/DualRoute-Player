package com.abei.test.feature.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import java.util.Locale

@Composable
fun PlayerScreen(viewModel: PlayerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onMediaPicked(it) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && state.playback.isPlaying) {
                viewModel.togglePlayPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VideoSurface(viewModel)
            PickerRow(state.mediaUri?.toString()) {
                picker.launch(arrayOf("video/*", "audio/*"))
            }
            if (state.mediaUri != null) {
                Controls(state, viewModel)
            }
        }
    }
}

@Composable
private fun VideoSurface(viewModel: PlayerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        PlayerSurface(
            player = viewModel.engine.player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PickerRow(currentUri: String?, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = onPick) {
            Text(if (currentUri == null) "选择视频" else "重新选择")
        }
        if (currentUri != null) {
            Text(
                currentUri,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, viewModel: PlayerViewModel) {
    val playback = state.playback
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = viewModel::togglePlayPause) {
                Text(if (playback.isPlaying) "暂停" else "播放")
            }
            Text(
                formatTime(playback.positionMs) + " / " + formatTime(playback.durationMs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Slider(
            value = if (playback.durationMs > 0) {
                playback.positionMs.toFloat() / playback.durationMs.toFloat()
            } else 0f,
            onValueChange = { fraction ->
                if (playback.durationMs > 0) {
                    viewModel.seekTo((fraction * playback.durationMs).toLong())
                }
            },
            enabled = playback.durationMs > 0,
        )

        SpeedRow(playback.playbackSpeed, viewModel::setSpeed)

        playback.error?.let { err ->
            Text(
                "错误:${err.message ?: err.javaClass.simpleName}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SpeedRow(current: Float, onSpeed: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
            val selected = kotlin.math.abs(current - speed) < 0.01f
            FilterChip(
                selected = selected,
                onClick = { onSpeed(speed) },
                label = { Text("${speed}x") },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
