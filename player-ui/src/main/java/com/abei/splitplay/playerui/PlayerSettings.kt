package com.abei.splitplay.playerui

import android.content.ContentResolver
import android.media.AudioDeviceInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abei.splitplay.core.EnginePrefs
import com.abei.splitplay.media.AudioOutput
import com.abei.splitplay.media.CodecCapability
import com.abei.splitplay.media.CodecType
import com.abei.splitplay.media.DisplayInfo
import com.abei.splitplay.media.PlayerEngineRegistry
import com.abei.splitplay.media.PlayerEngineType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 播放速度精细调整(0.25x..4x,步进 0.25)+ 视频信息卡片。
 * 内嵌在详情页 [PlayerScreen] 里,不再是单独的导航目的地 —— 避免 SurfaceView
 * 在 push/pop 过渡时反复 attach/detach 造成的画面闪/黑。
 */

@Composable
internal fun SpeedSection(speed: Float, onSpeedChange: (Float) -> Unit) {
    // sliderValue 本地驱动,松手才 commit 给 engine,避免拖动时狂改 PlaybackParameters。
    var sliderValue by remember { mutableStateOf(speed) }
    LaunchedEffect(speed) { sliderValue = speed }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 标题 + 当前值合在一行,节省竖向空间;
            // CompactSlider 跟详情页进度条同款 14dp 圆 thumb + 4dp 细 track,看起来一致。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放速度", style = MaterialTheme.typography.titleSmall)
                Text(
                    String.format(Locale.US, "%.2fx", sliderValue),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // 0.25..4.0,步进 0.25。Slider.steps 是端点间的内部刻度,(4-0.25)/0.25 = 15 段。
            CompactSlider(
                value = (sliderValue - 0.25f) / (4f - 0.25f),
                onValueChange = { fraction -> sliderValue = 0.25f + fraction * (4f - 0.25f) },
                onValueChangeFinished = { onSpeedChange(sliderValue) },
                steps = 14,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("0.25x", style = MaterialTheme.typography.labelSmall)
                Text("1x", style = MaterialTheme.typography.labelSmall)
                Text("2x", style = MaterialTheme.typography.labelSmall)
                Text("4x", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun VideoInfoSection(state: PlayerUiState) {
    val context = LocalContext.current
    val uri = state.mediaUri
    val fileInfo = remember(uri) {
        if (uri == null) FileInfo(null, null) else queryFileInfo(context.contentResolver, uri)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("视频信息", style = MaterialTheme.typography.titleMedium)
            InfoRow("文件名", fileInfo.displayName ?: uri?.lastPathSegment ?: "—")
            InfoRow("协议", uri?.scheme ?: "—")
            InfoRow("时长", formatTime(state.playback.durationMs))
            InfoRow("当前位置", formatTime(state.playback.positionMs))
            val res = if (state.playback.videoWidth > 0 && state.playback.videoHeight > 0) {
                "${state.playback.videoWidth} × ${state.playback.videoHeight}"
            } else "—"
            InfoRow("分辨率", res)
            InfoRow(
                "文件大小",
                fileInfo.sizeBytes?.let { formatBytes(it) } ?: "—",
            )
            InfoRow("播放速度", String.format(Locale.US, "%.2fx", state.playback.playbackSpeed))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "URI",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(uri?.toString() ?: "—", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class FileInfo(val displayName: String?, val sizeBytes: Long?)

/** SAF / MediaStore 的 content:// URI 用 OpenableColumns 拿到原始文件名和大小。 */
private fun queryFileInfo(resolver: ContentResolver, uri: Uri): FileInfo {
    if (uri.scheme != "content") return FileInfo(null, null)
    return runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use FileInfo(null, null)
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            FileInfo(
                displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null,
                sizeBytes = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null,
            )
        } ?: FileInfo(null, null)
    }.getOrElse { FileInfo(null, null) }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format(Locale.US, "%.2f %s", value, units[i])
}

/**
 * 播放器引擎选择 + 服务器地址(流播预留)。
 * 引擎切换需要重建 [com.abei.splitplay.media.PlayerEngine] 实例,改 pref 即生效,
 * 不会立刻在当前会话里换内核 —— 这里直接 Toast 提示"下次进入生效"。
 */
@Composable
internal fun EngineSection(prefs: EnginePrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初次进入时从 DataStore 拉一次当前值;后续用户操作直接更新本地状态 + 持久化。
    var selected by remember { mutableStateOf(PlayerEngineType.EXOPLAYER) }
    var serverUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        prefs.engineType.first()
            ?.let { runCatching { PlayerEngineType.valueOf(it) }.getOrNull() }
            ?.let { selected = it }
        serverUrl = prefs.serverBaseUrl.first()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("播放器引擎", style = MaterialTheme.typography.titleSmall)
            PlayerEngineRegistry.all().forEach { type ->
                EngineOption(
                    type = type,
                    selected = selected == type,
                    onClick = {
                        if (selected == type) return@EngineOption
                        selected = type
                        scope.launch { prefs.setEngineType(type.name) }
                        val msg = if (type == PlayerEngineType.CUSTOM) {
                            "${type.displayName} 尚未实现,下次进入播放页时会兜底用 ExoPlayer"
                        } else {
                            "已切换为 ${type.displayName},下次播放生效"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("服务器地址", style = MaterialTheme.typography.titleSmall)
            Text(
                "流播预留:http(s) 视频前缀,后续做远端目录浏览/历史时拼接用。本地视频不影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    scope.launch { prefs.setServerBaseUrl(it) }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://example.com/videos/") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
    }
}

@Composable
private fun EngineOption(
    type: PlayerEngineType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Box(modifier = Modifier.padding(start = 8.dp)) {
            Column {
                Text(type.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 音频输出设备 radio 列表。`outputs` 来自 [com.abei.splitplay.media.AudioDeviceRepository],
 * 设备热插拔会自动反应在这里。`selected == null` 表示"系统默认",作为列表第一项。
 *
 * 仅在详情页/全屏的 [SettingsDialog] 内启用 —— 首页弹窗不挂这个 section,因为
 * 首页没有 PlayerViewModel 实例,设备路由只在播放时才有意义。
 */
@Composable
internal fun AudioOutputSection(
    outputs: List<AudioOutput>,
    selected: AudioOutput?,
    onSelect: (AudioOutput?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("音频输出", style = MaterialTheme.typography.titleSmall)
            Text(
                "选中某个设备后,播放的声音会优先送往它;拔出该设备会自动回到系统默认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            // 第一项固定是"系统默认",对应 selected == null;后续按系统给的顺序展开。
            AudioOutputRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "系统默认",
                subtitle = "由系统按当前路由策略选择(等价于不勾任何设备)",
                selected = selected == null,
                onClick = { onSelect(null) },
            )
            outputs.forEach { out ->
                AudioOutputRow(
                    icon = iconForType(out.type),
                    title = out.name,
                    subtitle = out.typeLabel,
                    selected = selected?.id == out.id,
                    onClick = { onSelect(out) },
                )
            }
            if (outputs.isEmpty()) {
                Text(
                    "未检测到额外的音频输出设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AudioOutputRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Box(modifier = Modifier.padding(start = 12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 播放模式 radio:SINGLE / SPLIT。切到 SPLIT 后引擎会被重建为
 * [com.abei.splitplay.media.DualPlayerEngine](一个 audio-only 引擎 + 一个 video-only 引擎),
 * 详情页 UI 同时挂出两条独立控制条。当前播放会以快照的 (uri, position, speed) 续上。
 */
@Composable
internal fun PlaybackModeSection(
    mode: PlaybackMode,
    onSelect: (PlaybackMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("播放模式", style = MaterialTheme.typography.titleSmall)
            Text(
                "SPLIT 模式下音视频走两个独立引擎,可以单独暂停/播放;场景 A(HiFi 听歌 + 静音 MV)用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            PlaybackModeRow(
                title = "SINGLE — 单引擎",
                subtitle = "音视频走同一个 ExoPlayer 实例(默认,资源占用低)",
                selected = mode == PlaybackMode.SINGLE,
                onClick = { onSelect(PlaybackMode.SINGLE) },
            )
            PlaybackModeRow(
                title = "SPLIT — 音视频分离",
                subtitle = "两个独立引擎 + 时钟同步,详情页出现双控制条",
                selected = mode == PlaybackMode.SPLIT,
                onClick = { onSelect(PlaybackMode.SPLIT) },
            )
        }
    }
}

@Composable
private fun PlaybackModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Box(modifier = Modifier.padding(start = 8.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 视频输出显示设备的 radio 列表。`displays` 列表里 [DisplayInfo.isMain] 项作为
 * "本机屏幕"渲染,选中等价于关闭外接 Presentation。
 *
 * 仅在详情页/全屏的 [SettingsDialog] 内启用,首页弹窗不挂这个 section。
 */
@Composable
internal fun VideoOutputSection(
    displays: List<DisplayInfo>,
    selected: DisplayInfo?,
    onSelect: (DisplayInfo?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("视频输出", style = MaterialTheme.typography.titleSmall)
            Text(
                "选中某块外接屏,画面会投到那块屏上,本机仅保留控制面板;拔掉外接屏会自动回主屏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
            // 第一项总是主屏(取 displays 里 isMain == true 的那条;没有就显示占位)。
            val main = displays.firstOrNull { it.isMain }
            VideoOutputRow(
                icon = Icons.Filled.PhoneAndroid,
                title = main?.name ?: "本机屏幕",
                subtitle = "默认(不投屏)",
                selected = selected == null,
                onClick = { onSelect(main) },
            )
            displays.filter { !it.isMain }.forEach { d ->
                VideoOutputRow(
                    icon = Icons.Filled.Tv,
                    title = d.name,
                    subtitle = "外接屏",
                    selected = selected?.id == d.id,
                    onClick = { onSelect(d) },
                )
            }
            if (displays.none { !it.isMain }) {
                Text(
                    "未检测到外接屏。连接 HDMI / DisplayPort / 无线投屏后会自动出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoOutputRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Box(modifier = Modifier.padding(start = 12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 编解码能力矩阵。每行 = 一个编码格式,展示硬解 / 软解两列勾选。
 * 软解一栏目前只显示"系统自带 software MediaCodec",FFmpeg 软解还没接(M5 完成后补)。
 * UI 故意按 video / audio 分两段渲染,让用户一眼看清"哪些视频走得动、哪些音频走得动"。
 */
@Composable
internal fun CapabilityMatrixSection(capabilities: List<CodecCapability>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("编解码能力", style = MaterialTheme.typography.titleSmall)
            Text(
                "本机系统对各编码的硬解/软解支持情况。软解栏=系统软 MediaCodec;FFmpeg 软解未接入,暂为 ✗。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            if (capabilities.isEmpty()) {
                Text(
                    "扫描中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            // 表头
            CapabilityHeader()
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val videos = capabilities.filter { it.type == CodecType.VIDEO }
            val audios = capabilities.filter { it.type == CodecType.AUDIO }
            if (videos.isNotEmpty()) {
                Text("视频", style = MaterialTheme.typography.labelMedium)
                videos.forEach { CapabilityRow(it) }
            }
            if (audios.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text("音频", style = MaterialTheme.typography.labelMedium)
                audios.forEach { CapabilityRow(it) }
            }
        }
    }
}

@Composable
private fun CapabilityHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "编码",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "硬解",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "软解",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityRow(cap: CodecCapability) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(cap.displayName, style = MaterialTheme.typography.bodyMedium)
            // codec 名字 (c2.qti.avc.decoder 之类) 给开发者调试用,小字灰色不抢主视线
            val sub = cap.hwCodecName ?: cap.mime
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (cap.hwSupport) "✓" else "✗",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (cap.hwSupport) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (cap.swSupport) "✓" else "✗",
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (cap.swSupport) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconForType(type: Int): ImageVector = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    -> Icons.Filled.Bluetooth
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> Icons.Filled.Usb
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> Icons.Filled.Headset
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Filled.Headphones
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    -> Icons.Filled.SettingsInputHdmi
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Filled.Speaker
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

/**
 * 复用的播放器/服务器设置弹窗。首页和详情页都通过它打开,内容就是 [EngineSection]。
 * 走 AlertDialog 是因为这玩意需求是"随时可调"——比 push 到独立 screen 体验顺。
 *
 * 详情页传 `viewModel` 进来 → 弹窗里加挂"音频输出"section;首页没有 VM,
 * 仅显示引擎选择 + 服务器地址。
 */
@Composable
fun SettingsDialog(
    open: Boolean,
    prefs: EnginePrefs,
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel? = null,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        title = { Text("播放器设置") },
        text = {
            // 引擎 + 服务器地址内嵌在 EngineSection 里;弹窗本身按内容自适应高度,
            // 屏幕小的时候 dialog 自带 scroll 还不够稳,这里再外包一层 verticalScroll。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EngineSection(prefs = prefs)
                if (viewModel != null) {
                    val mode by viewModel.playbackMode.collectAsStateWithLifecycle()
                    PlaybackModeSection(
                        mode = mode,
                        onSelect = viewModel::setPlaybackMode,
                    )

                    val outputs by viewModel.audioOutputs.collectAsStateWithLifecycle()
                    val selectedAudio by viewModel.selectedAudioOutput.collectAsStateWithLifecycle()
                    AudioOutputSection(
                        outputs = outputs,
                        selected = selectedAudio,
                        onSelect = viewModel::selectAudioOutput,
                    )

                    val displays by viewModel.displays.collectAsStateWithLifecycle()
                    val selectedDisplay by viewModel.selectedDisplay.collectAsStateWithLifecycle()
                    val controller = LocalVideoOutputController.current
                    VideoOutputSection(
                        displays = displays,
                        selected = selectedDisplay,
                        onSelect = { d -> viewModel.selectDisplay(d, controller) },
                    )

                    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
                    CapabilityMatrixSection(capabilities = capabilities)
                }
            }
        },
    )
}

/**
 * 给 :app 调用的便利入口:不需要 ViewModel 也能弹设置(首页用),
 * 自己从 LocalContext 构造 [EnginePrefs] 实例。
 */
@Composable
fun rememberEnginePrefs(): EnginePrefs {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(ctx) { EnginePrefs(ctx) }
}
