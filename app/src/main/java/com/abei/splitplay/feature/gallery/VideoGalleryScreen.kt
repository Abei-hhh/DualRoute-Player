package com.abei.splitplay.feature.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGalleryScreen(
    onVideoClick: (android.net.Uri) -> Unit,
    onPickFromFile: (android.net.Uri) -> Unit,
    onOpenCamera: () -> Unit = {},
    viewModel: VideoGalleryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // 按 SDK 版本组装需要请求的权限。
    // Android 14 (SDK 34) 增加 READ_MEDIA_VISUAL_USER_SELECTED — 用户选「仅允许部分」时实际授予的是它。
    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var attemptedRequest by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        attemptedRequest = true
        // 任一权限被授予即视为可用(含 Android 14 部分访问)。
        val anyGranted = results.values.any { it }
        viewModel.onPermissionResult(anyGranted)
        if (!anyGranted && activity != null) {
            // 拒绝后再次请求若 shouldShowRationale 仍为 false → 用户勾了「不再询问」。
            permanentlyDenied = permissions.none {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // SAF 单文件:队列降级为单元素,Player 自然没有上下一首入口。
            com.abei.splitplay.core.PlaybackQueue.singleton(it)
            onPickFromFile(it)
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissions)
    }

    // 从录像页返回时,系统媒体库已经有新视频,这里 ON_RESUME 重新拉一次 MediaStore。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && state.hasPermission) {
                viewModel.loadVideos()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val enginePrefs = com.abei.splitplay.playerui.rememberEnginePrefs()
    var settingsOpen by remember { mutableStateOf(false) }
    com.abei.splitplay.playerui.SettingsDialog(
        open = settingsOpen,
        prefs = enginePrefs,
        onDismiss = { settingsOpen = false },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频播放器") },
                actions = {
                    IconButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "文件管理器")
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenCamera) {
                Icon(Icons.Filled.Videocam, contentDescription = "录像")
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.hasPermission -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Text(
                                if (permanentlyDenied) "存储权限已被永久拒绝，请到系统设置中开启"
                                else "需要存储权限才能浏览本机视频\n(也可直接用上方的相册/文件选择)",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (permanentlyDenied) {
                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        )
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.padding(top = 12.dp),
                                ) { Text("打开设置") }
                            } else {
                                Button(
                                    onClick = { permissionLauncher.launch(permissions) },
                                    modifier = Modifier.padding(top = 12.dp),
                                ) { Text("授予权限") }
                            }
                        }
                    }
                }
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载失败: ${state.error}", color = MaterialTheme.colorScheme.error)
                    }
                }
                state.videos.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到视频文件", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    VideoGrid(
                        videos = state.videos,
                        onVideoClick = { uri ->
                            // 把整列视频塞到播放队列里,选中项做 currentIndex。
                            // PlayerViewModel 启动时读这个队列,naturally 支持上下一首。
                            val all = state.videos.map { it.uri }
                            val idx = all.indexOf(uri).coerceAtLeast(0)
                            com.abei.splitplay.core.PlaybackQueue.set(all, idx)
                            onVideoClick(uri)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoGrid(
    videos: List<VideoItem>,
    onVideoClick: (android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(videos, key = { it.id }) { video ->
            VideoCard(video = video, onClick = { onVideoClick(video.uri) })
        }
    }
}

@Composable
private fun VideoCard(video: VideoItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            AsyncImage(
                model = video.thumbnailUri,
                contentDescription = video.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop,
            )
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(0.7f),
                tint = Color.White,
            )
            if (video.durationMs > 0) {
                Text(
                    text = formatDuration(video.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                )
            }
        }
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
