package com.abei.splitplay.media

/**
 * 解码策略。决定 [SinglePlayerEngine] 构造时把哪种 RenderersFactory 接进去。
 *
 *  - [AUTO]      :硬解优先,失败时 Media3 自带 fallback 链(默认行为)
 *  - [FORCE_HW]  :只装系统 MediaCodec,硬解失败直接报错给用户,便于排查
 *  - [FORCE_SW]  :优先 FFmpeg 软解 renderer(等 :native 模块上线后真正生效);
 *                 在 :native 还没接入的阶段,fallback 回 AUTO 行为并 log 一行提示。
 *
 * 持久化按字符串 name 走,见 [com.abei.splitplay.core.EnginePrefs.decoderPolicy]。
 */
enum class DecoderPolicy {
    AUTO,
    FORCE_HW,
    FORCE_SW;

    companion object {
        /** 字符串容错解析:坏值/null → [AUTO]。 */
        fun fromName(name: String?): DecoderPolicy =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: AUTO
    }
}
