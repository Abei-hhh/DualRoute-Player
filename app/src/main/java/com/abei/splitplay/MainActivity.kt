package com.abei.splitplay

import android.os.Bundle
import android.view.Display
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.abei.splitplay.navigation.AppNavHost
import com.abei.splitplay.playerui.LocalVideoOutputController
import com.abei.splitplay.playerui.VideoOutputController
import com.abei.splitplay.ui.theme.AppTheme

class MainActivity : ComponentActivity(), VideoOutputController {

    // 当前活动的 Presentation;选切外接屏时会重建,切回主屏时 dismiss。
    // 只在主线程访问,因此不需要同步。
    private var presentation: PlayerPresentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .crossfade(true)
                .build()
        )
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalVideoOutputController provides this) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Activity 离开前台 → 关掉外接屏的 Presentation,避免外屏黑画面留着。
        // ViewModel 那边随后会被 lifecycle 触发 release,顺序无所谓 —— Presentation 销毁会
        // 通过 onSurface(null) 让 engine 释放对外屏 Surface 的引用。
        dismissExternal()
    }

    override fun showOnExternal(display: Display, onSurface: (Surface?) -> Unit) {
        val current = presentation
        if (current != null && current.display.displayId == display.displayId && current.isShowing) {
            // 同一块屏的重复 show:不重建,但要把回调换成最新的 —— 上层可能换了 engine。
            // 简单起见这里直接 dismiss 再 show,频率很低不影响体验。
            current.setOnDismissListener(null)
            current.dismiss()
        }
        val p = PlayerPresentation(this, display, onSurface)
        p.setOnDismissListener {
            if (presentation === p) presentation = null
            // dismiss 时 Surface 已经销毁,会收到 onSurface(null),引擎自动 detach。
        }
        presentation = p
        p.show()
    }

    override fun dismissExternal() {
        presentation?.dismiss()
        presentation = null
    }
}
