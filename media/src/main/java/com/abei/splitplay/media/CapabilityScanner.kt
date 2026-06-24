package com.abei.splitplay.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * 编解码能力扫描。硬解侧扫 [MediaCodecList],软解侧暂留占位 ——
 * 等 M5 的 `:native` 模块上线后通过 `nativeListFfmpegDecoders()` 填进来。
 *
 * 扫描是 O(N) 遍历 MediaCodecInfo,N 通常几十,**一次几十毫秒**;暂不上 DataStore 缓存。
 * 若启动期占用 UI 线程,把它扔到 viewModelScope 里就好(本类提供同步 API,调用方自己包协程)。
 */
object CapabilityScanner {

    /**
     * 扫一遍 MediaCodecList,返回按编码格式聚合后的能力清单。
     * 同一编码可能有多个 codec(c2.qti.avc.decoder / c2.android.avc.decoder / OMX.qcom.avc.decoder),
     * 这里**按 mime 聚合**:任一硬解 codec 存在 → hwSupport = true,记录该 codec 名字。
     */
    fun scan(): List<CodecCapability> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val grouped: MutableMap<String, MutableCapability> = LinkedHashMap()
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val key = mime.lowercase()
                val type = mediaType(key) ?: continue
                val entry = grouped.getOrPut(key) {
                    MutableCapability(
                        mime = key,
                        displayName = displayNameFor(key),
                        type = type,
                    )
                }
                val hw = isHardwareAccelerated(info)
                if (hw) {
                    entry.hwSupport = true
                    if (entry.hwCodecName == null) entry.hwCodecName = info.name
                } else {
                    entry.softwareSupport = true
                }
            }
        }
        // 把 video 放前、audio 放后,组内按显示名字典序;便于 UI 渲染时直接遍历。
        return grouped.values
            .sortedWith(compareBy({ it.type.ordinal }, { it.displayName }))
            .map {
                CodecCapability(
                    mime = it.mime,
                    displayName = it.displayName,
                    type = it.type,
                    hwSupport = it.hwSupport,
                    hwCodecName = it.hwCodecName,
                    softwareSupportFromSystem = it.softwareSupport,
                    // 软解(FFmpeg)目前没接 :native,留 false;M5 完成后从 JNI 拉到这里。
                    softwareSupportFromFfmpeg = false,
                )
            }
    }

    /**
     * `MediaCodecInfo.isHardwareAccelerated()` 是 API 29+;老机型用名字前缀启发式兜底。
     * `c2.android.*` 和 `OMX.google.*` 是 AOSP 软实现的统一前缀,其余视为硬件加速。
     */
    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !(name.startsWith("omx.google.") || name.startsWith("c2.android."))
        }
    }

    private fun mediaType(mime: String): CodecType? = when {
        mime.startsWith("video/") -> CodecType.VIDEO
        mime.startsWith("audio/") -> CodecType.AUDIO
        else -> null  // image/* / text/* 等忽略
    }

    /**
     * MIME → 人类可读名。覆盖 PRD §4.2 / §4.3 重点格式;未列的回退到 mime 子字段大写。
     */
    private fun displayNameFor(mime: String): String = when (mime) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4 ASP"
        "video/mpeg2" -> "MPEG-2"
        "video/3gpp" -> "H.263"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/flac" -> "FLAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/raw" -> "PCM"
        "audio/g711-alaw" -> "G.711 A-law"
        "audio/g711-mlaw" -> "G.711 μ-law"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        else -> mime.substringAfter('/').uppercase()
    }

    private class MutableCapability(
        val mime: String,
        val displayName: String,
        val type: CodecType,
        var hwSupport: Boolean = false,
        var hwCodecName: String? = null,
        var softwareSupport: Boolean = false,
    )
}

enum class CodecType { VIDEO, AUDIO }

/**
 * 某编码的能力快照。
 *  - [hwSupport]:系统 MediaCodec 有硬解实现
 *  - [hwCodecName]:举一个具体 codec 名字方便调试,如 `c2.qti.avc.decoder`
 *  - [softwareSupportFromSystem]:系统自带 software MediaCodec(`c2.android.*` / `OMX.google.*`)
 *  - [softwareSupportFromFfmpeg]:FFmpeg JNI 枚举出的软解。M5 上线前永远为 false
 */
data class CodecCapability(
    val mime: String,
    val displayName: String,
    val type: CodecType,
    val hwSupport: Boolean,
    val hwCodecName: String?,
    val softwareSupportFromSystem: Boolean,
    val softwareSupportFromFfmpeg: Boolean,
) {
    /** UI 的"软解 ✓"取系统软解 ∪ FFmpeg 软解。 */
    val swSupport: Boolean get() = softwareSupportFromSystem || softwareSupportFromFfmpeg
}
