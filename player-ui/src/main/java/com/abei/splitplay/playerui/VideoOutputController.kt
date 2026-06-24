package com.abei.splitplay.playerui

import android.view.Display
import android.view.Surface
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 控制视频画面投到哪块屏。
 *
 * 为什么需要这层抽象:[android.app.Presentation] 必须用 Activity context,而
 * [PlayerViewModel] 在 :player-ui 模块里只能拿到 Application context。所以投屏的实际
 * 创建/销毁动作下沉到 :app 的 MainActivity,通过这个接口反向调度。
 *
 * 约定:
 *  - [showOnExternal] 在指定 [Display] 上开 Presentation。Presentation 内部建一个 SurfaceView,
 *    `surfaceCreated/Destroyed` 各回调一次 [onSurface]。Surface 销毁时回调 `null`。
 *  - [dismissExternal] 关掉当前 Presentation。如果当前没有,no-op。
 *  - 实现需要保证幂等:重复调 `showOnExternal` 同一 Display 不应重建 Presentation。
 */
interface VideoOutputController {
    fun showOnExternal(display: Display, onSurface: (Surface?) -> Unit)
    fun dismissExternal()
}

/**
 * CompositionLocal:Activity 在 `setContent` 外层 provide 一份 [VideoOutputController];
 * [PlayerScreen] / [PlayerViewModel] 通过 `LocalVideoOutputController.current` 拿来用。
 *
 * 默认 `null` —— 单元测试 / 预览环境下没有 Activity,UI 仍然能跑(选外接屏的按钮无效)。
 */
val LocalVideoOutputController = staticCompositionLocalOf<VideoOutputController?> { null }
