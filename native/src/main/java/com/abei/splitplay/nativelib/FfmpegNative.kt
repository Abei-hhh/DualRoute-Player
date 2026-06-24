package com.abei.splitplay.nativelib

import android.util.Log

/**
 * FFmpeg JNI 入口。所有方法都是 `external static`,实际签名见 native/src/main/cpp/。
 *
 * 设计:`:media` 不直接 [System.loadLibrary],而是这里 lazy load + 异常吞掉,保证
 * .so 不存在时调用方拿 null/空数组,UI/能力扫描自动退化(M5b 接入位)。
 */
object FfmpegNative {

    private const val TAG = "FfmpegNative"
    private const val LIB_NAME = "ffmpegwrapper"

    /** 是否成功加载了 `libffmpegwrapper.so`(无论是否含 FFmpeg)。 */
    val isLibraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary(LIB_NAME)
            true
        }.getOrElse { e ->
            Log.w(TAG, "loadLibrary($LIB_NAME) failed: ${e.message}")
            false
        }
    }

    /** .so 已加载且其中含完整 FFmpeg 静态库(由 build_ffmpeg.sh 产物决定)。 */
    val hasFfmpeg: Boolean by lazy {
        isLibraryLoaded && runCatching { nativeHasFfmpeg() }.getOrDefault(false)
    }

    /** 形如 "FFmpeg 6.1" 的 banner,用于 UI / 日志。未加载时返回 null。 */
    fun version(): String? = if (isLibraryLoaded) {
        runCatching { nativeVersion() }.getOrNull()
    } else null

    /**
     * 枚举 FFmpeg 注册的所有 decoder。返回每项为 `"codecName|mime|longName"`(mime 可能空,
     * 由调用方按 codec name 自行映射)。.so 未加载或没 FFmpeg 时返回空数组。
     */
    fun listDecoders(): List<String> {
        if (!hasFfmpeg) return emptyList()
        return runCatching { nativeListDecoders().toList() }.getOrElse { emptyList() }
    }

    @JvmStatic
    private external fun nativeHasFfmpeg(): Boolean
    @JvmStatic
    private external fun nativeVersion(): String
    @JvmStatic
    private external fun nativeListDecoders(): Array<String>
}
