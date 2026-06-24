package com.abei.splitplay.core

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级单例:跨 Activity / ViewModel 共享的"当前播放队列"。
 *
 * 为什么不走 Navigation 传参:Compose Navigation 不能把 `List<Uri>` 安全地编进 route 字符串
 * (大小上限 + URL 编码爆炸),也不能直接传引用对象。最直接的解法就是进程级 singleton。
 *
 * 不持久化:队列是"用户当下的播放意图",杀进程后回到 Gallery 入口就好。
 */
object PlaybackQueue {

    data class State(
        val items: List<Uri> = emptyList(),
        val currentIndex: Int = -1,
    ) {
        val current: Uri? get() = items.getOrNull(currentIndex)
        val hasPrev: Boolean get() = currentIndex > 0
        val hasNext: Boolean get() = currentIndex >= 0 && currentIndex < items.size - 1
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Gallery 点视频时调:把整个列表 + 选中项一起灌进来。 */
    fun set(items: List<Uri>, currentIndex: Int) {
        if (currentIndex !in items.indices) {
            _state.value = State()
            return
        }
        _state.value = State(items, currentIndex)
    }

    /** Player 内部"下一首"/"上一首"。若超界返回 null,UI 据此 disable 按钮。 */
    fun next(): Uri? {
        val s = _state.value
        if (!s.hasNext) return null
        val nextIndex = s.currentIndex + 1
        _state.value = s.copy(currentIndex = nextIndex)
        return s.items[nextIndex]
    }

    fun prev(): Uri? {
        val s = _state.value
        if (!s.hasPrev) return null
        val prevIndex = s.currentIndex - 1
        _state.value = s.copy(currentIndex = prevIndex)
        return s.items[prevIndex]
    }

    /** 单独打开一个视频(SAF / 外部 chooser 等):把队列降级为单元素队列。 */
    fun singleton(uri: Uri) {
        _state.value = State(listOf(uri), 0)
    }

    fun clear() {
        _state.value = State()
    }
}
