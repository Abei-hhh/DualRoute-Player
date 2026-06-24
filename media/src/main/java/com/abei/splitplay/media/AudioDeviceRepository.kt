package com.abei.splitplay.media

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音频输出设备的枚举/热插拔监听层。
 *
 * 用法:在 [com.abei.splitplay.playerui.PlayerViewModel.init] 里 [start],
 * [androidx.lifecycle.ViewModel.onCleared] 里 [stop]。
 *
 * 列表只保留**物理 sink**(扬声器/耳机/蓝牙/USB/HDMI/Dock),过滤掉电话听筒、遥控、
 * 虚拟回环等用户不会主动选的类型 —— 这样 UI 单选弹窗的项不会太杂乱。
 *
 * 名称兜底:有些 ROM 不暴露 USB DAC 的 `productName`,这里降级到 [typeLabel]
 * (比如直接显示"USB 音频"),避免出现"Unknown"。
 */
class AudioDeviceRepository(
    context: Context,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val am: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // AudioDeviceCallback 内部 post 回调需要一个有 Looper 的线程;主线程 Handler 最稳。
    private val handler = Handler(Looper.getMainLooper())

    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }
    }

    fun start() {
        // 注册 callback 时 SDK 会立刻回调一次 onAudioDevicesAdded(全量),所以这里不用先 refresh。
        // 但为了 start 后立刻有数据,显式拉一次更稳。
        refresh()
        am.registerAudioDeviceCallback(callback, handler)
    }

    fun stop() {
        am.unregisterAudioDeviceCallback(callback)
    }

    private fun refresh() {
        val raw = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val list = raw
            .asSequence()
            .filter { it.isSink }
            .filter { it.type in PHYSICAL_OUTPUT_TYPES }
            .map { dev ->
                AudioOutput(
                    id = dev.id,
                    type = dev.type,
                    typeLabel = typeLabel(dev.type),
                    name = displayName(dev),
                    info = dev,
                )
            }
            .toList()
        _outputs.value = list
    }

    private fun displayName(dev: AudioDeviceInfo): String {
        val raw = dev.productName?.toString()?.trim().orEmpty()
        return raw.ifEmpty { typeLabel(dev.type) }
    }

    private companion object {
        // 用户会主动选的物理输出。其它(听筒/电话线路/虚拟回环/遥控等)默默忽略。
        val PHYSICAL_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
        )

        fun typeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 外设"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_DOCK -> "底座"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
            else -> "其它"
        }
    }
}

/**
 * 一个可选音频输出的快照。
 * - [id] 对应 [AudioDeviceInfo.getId],跨进程不稳定,只在当前进程会话内有效 ——
 *   持久化时仍按 id 存,但每次启动列表刷新后要 reconcile,id 不在了就清空选择。
 */
data class AudioOutput(
    val id: Int,
    val type: Int,
    val typeLabel: String,
    val name: String,
    val info: AudioDeviceInfo,
)
