package com.abei.splitplay.feature.camera.ar

/** 21 个手部关键点之一的 3D 归一化坐标(MediaPipe 输出格式)。 */
data class HandLandmark(val x: Float, val y: Float, val z: Float)

enum class Handedness { LEFT, RIGHT }

/**
 * MediaPipe `GestureRecognizer` 默认 7 + None 个标签。
 * 字符串映射放在 [fromLabel],避免硬编码散落各处。
 */
enum class GestureKind {
    NONE,
    OPEN_PALM,
    CLOSED_FIST,
    POINTING_UP,
    THUMB_UP,
    THUMB_DOWN,
    VICTORY,
    I_LOVE_YOU;

    companion object {
        fun fromLabel(label: String?): GestureKind = when (label) {
            "Open_Palm" -> OPEN_PALM
            "Closed_Fist" -> CLOSED_FIST
            "Pointing_Up" -> POINTING_UP
            "Thumb_Up" -> THUMB_UP
            "Thumb_Down" -> THUMB_DOWN
            "Victory" -> VICTORY
            "ILoveYou" -> I_LOVE_YOU
            else -> NONE
        }
    }
}

/**
 * 一只手的检测结果。坐标系约定:
 *
 * - [landmarks] 已经过坐标映射(旋转 + 前摄镜像),x/y ∈ [0,1] 对应**显示在屏幕上**的归一化坐标,
 *   X 向右,Y 向下。z 是相对深度(0 = 手腕,正 = 远离镜头),保留原始尺度但不做绝对单位转换。
 * - [handedness] 是从用户视角看的左/右手(已根据前/后摄做了校正)。
 */
data class HandPose(
    val handedness: Handedness,
    val landmarks: List<HandLandmark>,
    val gesture: GestureKind,
) {
    /** 手掌中心(landmark 0 手腕 + 9 中指掌指关节平均),取屏幕归一化坐标。 */
    val palmCenter: HandLandmark
        get() {
            val wrist = landmarks[0]
            val mcp = landmarks[9]
            return HandLandmark(
                (wrist.x + mcp.x) * 0.5f,
                (wrist.y + mcp.y) * 0.5f,
                (wrist.z + mcp.z) * 0.5f,
            )
        }

    /** 食指尖。 */
    val indexTip: HandLandmark get() = landmarks[8]
}

/** 一帧的所有手部检测结果。 */
data class HandFrame(
    val hands: List<HandPose>,
    val timestampMs: Long,
)

/**
 * MediaPipe HandLandmarker 的标准 21 点连接拓扑。每对 (a, b) 表示一条连线。
 *
 * 21 点定义(MediaPipe):
 *   0  WRIST
 *   1-4 THUMB (CMC, MCP, IP, TIP)
 *   5-8 INDEX (MCP, PIP, DIP, TIP)
 *   9-12 MIDDLE
 *   13-16 RING
 *   17-20 PINKY
 */
object HandSkeleton {
    /** 21 条连线,按手指 + 手掌分组。 */
    val CONNECTIONS: IntArray = intArrayOf(
        // 拇指
        0, 1, 1, 2, 2, 3, 3, 4,
        // 食指
        5, 6, 6, 7, 7, 8,
        // 中指
        9, 10, 10, 11, 11, 12,
        // 无名指
        13, 14, 14, 15, 15, 16,
        // 小指
        17, 18, 18, 19, 19, 20,
        // 手掌(手腕到各掌指关节 + 掌指关节之间)
        0, 5, 5, 9, 9, 13, 13, 17, 0, 17,
    )

    /** 给每个 landmark 索引分组(用于上色)。 */
    enum class Group { PALM, THUMB, INDEX, MIDDLE, RING, PINKY }

    fun groupOf(index: Int): Group = when (index) {
        0 -> Group.PALM
        in 1..4 -> Group.THUMB
        in 5..8 -> Group.INDEX
        in 9..12 -> Group.MIDDLE
        in 13..16 -> Group.RING
        in 17..20 -> Group.PINKY
        else -> Group.PALM
    }
}
