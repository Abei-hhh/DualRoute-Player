package com.abei.splitplay.feature.camera.ar

import androidx.compose.ui.geometry.Offset

/**
 * AR 叠加几何体的种类。当前只实现 BOX,留出枚举供后续扩展(球体/圆锥/自定义 mesh)。
 *
 * 扩展方式:在 [Renderable] 里加新实现,然后在 [renderableOf] 里登记。
 */
enum class GeometryKind { BOX }

/**
 * 几何体当前状态 —— 作为 Compose 端和 GL 端共享的 SSOT。
 *
 * - [positionNorm] 在视图空间(显示完后的最终方向),(-1..1, -1..1),X 向右、Y 向下,
 *   与 Compose Canvas 的屏幕坐标系一致(GL 端会做 Y 翻转适配 NDC)。
 * - [scale] 半边长,以视图归一化坐标计;0.3 大约占屏幕半宽。
 * - [rotationDegYaw] / [rotationDegPitch] 绕 Y / X 轴的固定旋转,让长方体看起来"立体"(默认值)。
 * - [rotationDegRoll] 绕 Z 轴(屏幕平面)旋转,由用户手势驱动 —— 单手捏合的角度。
 * - [colorArgb] 0xAARRGGBB。
 */
data class GeometryState(
    val kind: GeometryKind = GeometryKind.BOX,
    val positionNorm: Offset = Offset.Zero,
    val scale: Float = 0.25f,
    val rotationDegYaw: Float = 25f,
    val rotationDegPitch: Float = -15f,
    val rotationDegRoll: Float = 0f,
    val colorArgb: Int = 0xFF00E5FF.toInt(),
    val visible: Boolean = false,
) {
    val renderable: Renderable get() = renderableOf(kind)
}

/** 渲染模式 —— 屏上叠加(HUD,只在 Compose 层)或入录像(GL 第二 pass)。 */
enum class RenderMode {
    /** 只在屏上画,录像里不出现。Compose Canvas 负责。 */
    HUD_OVERLAY,

    /** 进美颜 GL 管线第二 pass,Preview 与 VideoCapture 共用 —— 所见即所录。 */
    IN_RECORDING,
}
