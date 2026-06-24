package com.abei.splitplay

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * 把视频画面投到外接屏的 [Presentation]。
 *
 * 设计点:
 *  - 必须用 Activity context([Presentation] 不接受 Application context),所以放在 :app 层。
 *  - 内部只布置一个全屏 [SurfaceView] + 黑底,Surface 生命周期通过 [onSurface] 回调
 *    传出去 —— ViewModel 在拿到 Surface 后调 `engine.setVideoSurface(...)`,Surface 销毁时
 *    传 `null`,避免 native 端继续往已销毁的内存写。
 *  - 不在这里持任何 engine/player 引用,完全无状态地把 Surface 当事件流暴露出去。
 *    Presentation 也不渲染任何控件 —— 控制面板始终留在主屏。
 */
class PlayerPresentation(
    activity: Activity,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        val surfaceView = SurfaceView(context)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        root.addView(surfaceView, lp)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // 尺寸变化由系统通知 native 端,这里无需再调一次。
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurface(null)
            }
        })
        setContentView(root)
    }

    override fun onStop() {
        super.onStop()
        // 防御性:即使 surfaceDestroyed 没及时回调,这里也保证 engine 不再持旧 Surface。
        onSurface(null)
    }
}
