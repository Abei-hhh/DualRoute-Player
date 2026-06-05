package com.abei.test.feature.camera.beauty

/**
 * 美颜参数。所有字段都是 0..1 的强度,0 表示关。
 *
 * - [smooth] 磨皮强度:在 fragment shader 里控制 5-tap 模糊与原色的混合比例,
 *   并按邻域颜色差做边缘保护,避免把五官也糊掉。
 * - [whiten] 美白强度:按亮度向白色靠拢,仅作用于中高亮区域(肤色),
 *   阴影/头发不会被洗白。
 */
data class BeautyParams(
    val smooth: Float = 0f,
    val whiten: Float = 0f,
)
