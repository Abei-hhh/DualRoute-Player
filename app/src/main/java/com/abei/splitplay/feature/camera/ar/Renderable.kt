package com.abei.splitplay.feature.camera.ar

/**
 * 几何体抽象 —— Compose Canvas 与 GL 第二 pass 共用同一份顶点/索引数据。
 *
 * 顶点采用单位坐标(半边长 = 1),实际尺寸由 [GeometryState.scale] 在渲染时缩放。
 */
interface Renderable {
    /** (x, y, z) triplets,单位长度。 */
    val vertices: FloatArray

    /** 线框索引(对 [vertices] 取索引,每两个为一条线段)。 */
    val lineIndices: ShortArray

    /** 实心三角索引(每三个为一个三角形)。当前主要给 GL 第二 pass 用。 */
    val triangleIndices: ShortArray
}

/** 单位立方体(8 顶点 + 12 条边 + 12 个三角形面)。 */
object BoxRenderable : Renderable {
    override val vertices = floatArrayOf(
        -1f, -1f, -1f, // 0
        +1f, -1f, -1f, // 1
        +1f, +1f, -1f, // 2
        -1f, +1f, -1f, // 3
        -1f, -1f, +1f, // 4
        +1f, -1f, +1f, // 5
        +1f, +1f, +1f, // 6
        -1f, +1f, +1f, // 7
    )

    override val lineIndices = shortArrayOf(
        // 后面(z = -1)
        0, 1, 1, 2, 2, 3, 3, 0,
        // 前面(z = +1)
        4, 5, 5, 6, 6, 7, 7, 4,
        // 四条连接边
        0, 4, 1, 5, 2, 6, 3, 7,
    )

    override val triangleIndices = shortArrayOf(
        // 后(z=-1): 0,1,2 / 0,2,3
        0, 1, 2, 0, 2, 3,
        // 前(z=+1): 4,6,5 / 4,7,6 (逆时针)
        4, 6, 5, 4, 7, 6,
        // 左(x=-1)
        0, 3, 7, 0, 7, 4,
        // 右(x=+1)
        1, 5, 6, 1, 6, 2,
        // 下(y=-1)
        0, 4, 5, 0, 5, 1,
        // 上(y=+1)
        3, 2, 6, 3, 6, 7,
    )
}

fun renderableOf(kind: GeometryKind): Renderable = when (kind) {
    GeometryKind.BOX -> BoxRenderable
}
