package com.abei.splitplay.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 显示设备(屏幕/外接屏)的枚举/热插拔监听层。
 *
 * 用法跟 [AudioDeviceRepository] 一致:`start()` 在 init 调,`stop()` 在 onCleared。
 *
 * 用 [DisplayManager.DISPLAY_CATEGORY_PRESENTATION] 列举可以承载 [android.app.Presentation]
 * 的次屏,**外加** [Display.DEFAULT_DISPLAY] 作为"主屏"项 —— 这样 UI 总能列出 ≥1 个选项,
 * 没有外接屏时也有"主屏"radio 占位。
 *
 * 名称兜底:某些 ROM 的外接屏只暴露索引(`Display 1`),为了好看会把分辨率附在 [DisplayInfo.name]
 * 后缀里方便用户区分。
 */
class DisplayRepository(
    context: Context,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val dm: DisplayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private val _displays = MutableStateFlow<List<DisplayInfo>>(emptyList())
    val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    fun start() {
        refresh()
        dm.registerDisplayListener(listener, handler)
    }

    fun stop() {
        dm.unregisterDisplayListener(listener)
    }

    private fun refresh() {
        // DEFAULT_DISPLAY 永远存在,但 getDisplays(PRESENTATION) 默认不会返回它 ——
        // 主屏作为"不投屏"的默认选项手动加进去。
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val list = buildList(presentations.size + 1) {
            if (main != null) {
                add(DisplayInfo(main.displayId, displayName(main), isMain = true, raw = main))
            }
            presentations.forEach { d ->
                add(DisplayInfo(d.displayId, displayName(d), isMain = false, raw = d))
            }
        }
        _displays.value = list
    }

    private fun displayName(d: Display): String {
        val base = d.name?.trim().orEmpty().ifEmpty { "Display ${d.displayId}" }
        // 主屏不附加分辨率,避免冗余;外接屏附上分辨率方便用户区分多块同名屏。
        return if (d.displayId == Display.DEFAULT_DISPLAY) base else {
            val w = d.mode.physicalWidth
            val h = d.mode.physicalHeight
            if (w > 0 && h > 0) "$base · ${w}×${h}" else base
        }
    }
}

/**
 * 一个可选显示设备的快照。
 *  - [isMain] = true 时对应 [Display.DEFAULT_DISPLAY],UI 该项渲染成"主屏",选中等价于"不投屏"
 *  - [raw] 给 :app 层 [android.app.Presentation] 用 —— 跨进程不可序列化,但 :app 层
 *    直接持引用就好
 */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val isMain: Boolean,
    val raw: Display,
)
