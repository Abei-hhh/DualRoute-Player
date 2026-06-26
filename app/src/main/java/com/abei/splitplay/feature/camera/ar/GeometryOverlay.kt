package com.abei.splitplay.feature.camera.ar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.cos
import kotlin.math.sin

/**
 * HUD 模式下用 Compose [Canvas] 把 [GeometryState] 的几何体画成线框。
 *
 * 投影逻辑:把 [Renderable.vertices] 经过 model 矩阵后做简易透视(z 越大越收缩),
 * 再映射到画布像素坐标。线段按段中点 z 倒排,远的先画 —— 对线框看起来已经够立体。
 *
 * **不**走 GL,只在屏上显示;录像里不会出现。要进录像走 [RenderMode.IN_RECORDING] 的 GL pass。
 */
@Composable
fun GeometryOverlay(
    state: GeometryState,
    modifier: Modifier = Modifier,
    strokeWidthPx: Float = 4f,
    farFadeAlpha: Float = 0.45f,
) {
    if (!state.visible) {
        Canvas(modifier = modifier) {}
        return
    }
    val color = Color(state.colorArgb)
    val renderable = state.renderable

    Canvas(modifier = modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        // 视图空间 1 单位 = 屏短边的一半。半边长 scale 直接乘进去。
        val viewUnit = (size.minDimension * 0.5f) * state.scale
        val centerX = cx + state.positionNorm.x * (size.width * 0.5f)
        val centerY = cy + state.positionNorm.y * (size.height * 0.5f)

        val verts = renderable.vertices
        val n = verts.size / 3
        val projected = FloatArray(n * 3) // x, y (像素), z (深度,用于排序)

        val yawRad = state.rotationDegYaw * DEG2RAD
        val pitchRad = state.rotationDegPitch * DEG2RAD
        val rollRad = state.rotationDegRoll * DEG2RAD
        val sy = sin(yawRad); val cyaw = cos(yawRad)
        val sp = sin(pitchRad); val cp = cos(pitchRad)
        val sr = sin(rollRad); val cr = cos(rollRad)

        for (i in 0 until n) {
            val x0 = verts[i * 3]
            val y0 = verts[i * 3 + 1]
            val z0 = verts[i * 3 + 2]
            // 先 yaw(绕 Y),再 pitch(绕 X)
            val x1 = x0 * cyaw + z0 * sy
            val z1 = -x0 * sy + z0 * cyaw
            val y1 = y0
            val y2 = y1 * cp - z1 * sp
            val z2 = y1 * sp + z1 * cp
            val x2 = x1
            // 简易透视:z 越大越收缩(摄像机看向 -Z,z 大 = 远)
            val persp = 1f / (1f + 0.35f * z2)
            // 投影到屏幕空间后再做 roll(屏幕平面旋转)—— 由捏合手势驱动
            val sx0 = x2 * viewUnit * persp
            val sy0 = y2 * viewUnit * persp
            val sx = sx0 * cr - sy0 * sr
            val sy1 = sx0 * sr + sy0 * cr
            projected[i * 3] = centerX + sx
            projected[i * 3 + 1] = centerY + sy1
            projected[i * 3 + 2] = z2
        }

        val indices = renderable.lineIndices
        val segCount = indices.size / 2
        // 按段中点 z 倒序(远→近)
        val order = IntArray(segCount) { it }
        val midZ = FloatArray(segCount)
        for (s in 0 until segCount) {
            val a = indices[s * 2].toInt()
            val b = indices[s * 2 + 1].toInt()
            midZ[s] = (projected[a * 3 + 2] + projected[b * 3 + 2]) * 0.5f
        }
        // 简单插排,segCount 很小(12 条)无所谓
        for (i in 1 until segCount) {
            val key = order[i]; val keyZ = midZ[key]
            var j = i - 1
            while (j >= 0 && midZ[order[j]] < keyZ) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = key
        }

        for (s in order) {
            val a = indices[s * 2].toInt()
            val b = indices[s * 2 + 1].toInt()
            val ax = projected[a * 3]; val ay = projected[a * 3 + 1]
            val bx = projected[b * 3]; val by = projected[b * 3 + 1]
            val avgZ = (projected[a * 3 + 2] + projected[b * 3 + 2]) * 0.5f
            // z 大 = 远,降低 alpha 模拟雾化
            val depthFactor = ((avgZ + 1f) * 0.5f).coerceIn(0f, 1f)
            val alpha = farFadeAlpha + (1f - farFadeAlpha) * (1f - depthFactor)
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(ax, ay),
                end = Offset(bx, by),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val DEG2RAD: Float = (Math.PI / 180.0).toFloat()
