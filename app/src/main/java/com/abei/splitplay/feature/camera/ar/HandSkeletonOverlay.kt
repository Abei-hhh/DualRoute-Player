package com.abei.splitplay.feature.camera.ar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * 调试用骨架叠加 —— 把 MediaPipe 的 21 个 landmark 和标准连线画到屏上。
 *
 * 只在 Compose 层渲染,不会进录像。坐标已经由 [HandLandmarkerAnalyzer] 做过镜像/旋转,
 * 可以直接乘画布尺寸映射到屏幕。
 *
 * 不显示时把 [frame] 传 null,组件画一个空 Canvas(避免大小变化引起的重布局)。
 */
@Composable
fun HandSkeletonOverlay(
    frame: HandFrame?,
    modifier: Modifier = Modifier,
    pointRadiusPx: Float = 6f,
    lineWidthPx: Float = 3f,
) {
    Canvas(modifier = modifier) {
        val hands = frame?.hands ?: return@Canvas
        for (hand in hands) {
            val color = colorFor(hand.handedness)
            val lms = hand.landmarks
            // 连线
            val conn = HandSkeleton.CONNECTIONS
            var i = 0
            while (i < conn.size) {
                val a = conn[i]; val b = conn[i + 1]
                if (a < lms.size && b < lms.size) {
                    val pa = lms[a]; val pb = lms[b]
                    drawLine(
                        color = color.copy(alpha = 0.85f),
                        start = Offset(pa.x * size.width, pa.y * size.height),
                        end = Offset(pb.x * size.width, pb.y * size.height),
                        strokeWidth = lineWidthPx,
                        cap = StrokeCap.Round,
                    )
                }
                i += 2
            }
            // 21 个点 —— 拇指尖 / 食指尖 加粗,方便看清捏合控制点
            for ((idx, lm) in lms.withIndex()) {
                val r = if (idx == 4 || idx == 8) pointRadiusPx * 1.8f else pointRadiusPx
                val dotColor = when (idx) {
                    4 -> Color(0xFFFF5252)   // 拇指尖:红
                    8 -> Color(0xFFFFEB3B)   // 食指尖:黄
                    0 -> Color.White         // 手腕:白
                    else -> color
                }
                drawCircle(
                    color = dotColor,
                    radius = r,
                    center = Offset(lm.x * size.width, lm.y * size.height),
                )
            }
        }
    }
}

private fun colorFor(handedness: Handedness): Color = when (handedness) {
    Handedness.LEFT -> Color(0xFF4FC3F7)   // 浅蓝
    Handedness.RIGHT -> Color(0xFF81C784)  // 浅绿
}
