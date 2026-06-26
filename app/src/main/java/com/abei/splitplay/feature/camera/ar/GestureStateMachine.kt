package com.abei.splitplay.feature.camera.ar

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 手势 → [GeometryState] 状态机(单手捏合版)。
 *
 * 状态:
 *  - IDLE:几何体不可见;只有看到 OPEN_PALM 才会转 ARMED
 *  - ARMED:几何体可见,持续跟踪第一只手的拇指 + 食指,做位置/大小/旋转控制
 *
 * 连续映射(都用低通滤波平滑):
 *  - **position** = 拇指尖 (landmark 4) 和 食指尖 (landmark 8) 的**中点**,(0..1, 0..1) → (-1..1, -1..1)
 *  - **scale** = 拇指-食指距离的绝对映射,[PINCH_MIN_DIST .. PINCH_MAX_DIST] 线性映到 [MIN_SCALE .. MAX_SCALE]
 *  - **rotationDegRoll** = 拇指→食指方向的角度(atan2),做角度环绕修正避免 ±180° 跳变
 *
 * 离散触发(都做去抖,避免一帧一次):
 *  - OPEN_PALM:从 IDLE 升到 ARMED(只在 IDLE 时触发)
 *  - CLOSED_FIST 持续 [CLOSED_FIST_FRAMES_TO_HIDE] 帧:回 IDLE
 *  - THUMB_UP:循环换色(冷却 [COLOR_COOLDOWN_MS])
 *  - VICTORY:循环几何体种类(冷却 [KIND_COOLDOWN_MS],当前只有 BOX,留接口)
 *
 * 本类**不是线程安全的**;只在单一调度线程(ViewModel coroutine)里 apply。
 */
class GestureStateMachine(
    private val initialScale: Float = 0.25f,
) {

    private enum class State { IDLE, ARMED }

    private var state: State = State.IDLE
    private var fistFrames: Int = 0
    private var lastColorCycleMs: Long = 0L
    private var lastKindCycleMs: Long = 0L
    private var colorIndex: Int = 0

    /** roll 上一帧值,用来做角度环绕修正和低通滤波。 */
    private var lastRoll: Float = 0f
    private var rollInitialized: Boolean = false

    fun reset() {
        state = State.IDLE
        fistFrames = 0
        colorIndex = 0
        lastRoll = 0f
        rollInitialized = false
    }

    fun apply(frame: HandFrame, current: GeometryState): GeometryState {
        val hands = frame.hands
        if (hands.isEmpty()) {
            // 手离场:保留显示,等回来继续(避免一闪而过)。要真正隐藏请做 CLOSED_FIST。
            return current
        }

        // 触发(IDLE 时只看 OPEN_PALM)
        if (state == State.IDLE) {
            val openPalm = hands.any { it.gesture == GestureKind.OPEN_PALM }
            if (!openPalm) return current
            state = State.ARMED
            fistFrames = 0
            rollInitialized = false
        }

        // 隐藏:任意手 CLOSED_FIST 连续 N 帧
        val fistSeen = hands.any { it.gesture == GestureKind.CLOSED_FIST }
        if (fistSeen) {
            fistFrames++
            if (fistFrames >= CLOSED_FIST_FRAMES_TO_HIDE) {
                state = State.IDLE
                fistFrames = 0
                rollInitialized = false
                return current.copy(visible = false)
            }
        } else {
            fistFrames = 0
        }

        // 取第一只手做捏合控制
        val primary = hands.first()
        val thumb = primary.landmarks[4]
        val index = primary.landmarks[8]

        // position = 捏合中点,屏幕归一化 [0,1] → [-1,1]
        val midX = (thumb.x + index.x) * 0.5f
        val midY = (thumb.y + index.y) * 0.5f
        val px = (midX.coerceIn(0f, 1f) * 2f - 1f)
        val py = (midY.coerceIn(0f, 1f) * 2f - 1f)

        // scale = 捏合距离绝对映射,clamp + 低通
        val dx = index.x - thumb.x
        val dy = index.y - thumb.y
        val pinchDist = hypot(dx, dy)
        val t = ((pinchDist - PINCH_MIN_DIST) / (PINCH_MAX_DIST - PINCH_MIN_DIST))
            .coerceIn(0f, 1f)
        val targetScale = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * t
        val newScale = lerp(current.scale, targetScale, SMOOTHING)

        // roll = 拇指→食指方向角(度)。Y 向下,所以 atan2(dy,dx) 给出的角度
        // 顺时针为正,符合屏幕直观(用户顺时针拧手腕,长方体顺时针转)。
        val targetRoll = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val newRoll = if (!rollInitialized) {
            rollInitialized = true
            targetRoll
        } else {
            lerpAngle(lastRoll, targetRoll, SMOOTHING)
        }
        lastRoll = newRoll

        // 离散:THUMB_UP 换色
        val nowMs = frame.timestampMs
        var newColor = current.colorArgb
        if (hands.any { it.gesture == GestureKind.THUMB_UP } &&
            nowMs - lastColorCycleMs > COLOR_COOLDOWN_MS) {
            colorIndex = (colorIndex + 1) % PALETTE.size
            newColor = PALETTE[colorIndex]
            lastColorCycleMs = nowMs
        }

        // 离散:VICTORY 换几何体种类
        var newKind = current.kind
        if (hands.any { it.gesture == GestureKind.VICTORY } &&
            nowMs - lastKindCycleMs > KIND_COOLDOWN_MS) {
            val all = GeometryKind.entries
            newKind = all[(all.indexOf(current.kind) + 1) % all.size]
            lastKindCycleMs = nowMs
        }

        return current.copy(
            kind = newKind,
            positionNorm = Offset(px, py),
            scale = newScale,
            rotationDegRoll = newRoll,
            colorArgb = newColor,
            visible = true,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 角度低通,处理 ±180° 跳变 —— 总是走最短弧。 */
    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var diff = b - a
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return a + diff * t
    }

    companion object {
        private const val CLOSED_FIST_FRAMES_TO_HIDE = 5
        private const val COLOR_COOLDOWN_MS = 600L
        private const val KIND_COOLDOWN_MS = 800L

        private const val MIN_SCALE = 0.06f
        private const val MAX_SCALE = 0.55f

        /** 捏合距离的归一化区间(在 landmark [0,1] 空间)。 */
        private const val PINCH_MIN_DIST = 0.02f      // 手指几乎相碰 → 最小
        private const val PINCH_MAX_DIST = 0.30f      // 手指尽量张开 → 最大

        /** 低通滤波系数,0 = 完全不更新,1 = 立刻跳到目标。0.35 是手感平衡点。 */
        private const val SMOOTHING = 0.35f

        private val PALETTE = intArrayOf(
            0xFF00E5FF.toInt(), // cyan
            0xFFFFC107.toInt(), // amber
            0xFF8BC34A.toInt(), // green
            0xFFFF5722.toInt(), // deep orange
            0xFFE91E63.toInt(), // pink
            0xFF9C27B0.toInt(), // purple
        )
    }
}
