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

    // ---- Audio decode ----

    /**
     * 打开一个 audio decoder。
     *  - [mime] 跟 Android `Format.sampleMimeType` 一致("audio/mp4a-latm" 等)
     *  - [extraData] = Android Format.initializationData[0](AAC CSD-0、Vorbis ID header 等)
     *  - 返回的 handle 给后续 decode/release;`0` 表示初始化失败,调用方需要 fallback。
     */
    @JvmStatic
    external fun nativeAudioOpen(
        mime: String,
        extraData: ByteArray?,
        sampleRate: Int,
        channels: Int,
    ): Long

    /**
     * 喂一个 packet 进解码器,把所有产生的 PCM frames 写到 [outputDirect],返回 byte 数。
     * 负数表示错误(`-5` 输出 buffer 不够,调用方应加大;其它见 audio_decoder.cpp)。
     * [outputDirect] 必须是 DirectByteBuffer。
     */
    @JvmStatic
    external fun nativeAudioDecode(
        handle: Long,
        inputDirect: java.nio.ByteBuffer,
        inputSize: Int,
        outputDirect: java.nio.ByteBuffer,
        outputCapacity: Int,
    ): Int

    /** Seek 等场景调用,清空 decoder 内部 buffer。 */
    @JvmStatic
    external fun nativeAudioReset(handle: Long)

    /** 释放 decoder context。release 后 handle 不能再用。 */
    @JvmStatic
    external fun nativeAudioRelease(handle: Long)

    // ---- Video decode ----
    //
    // 模型:native 维护 ring buffer<AVFrame>(MAX_FRAME_SLOTS=16),decode 返回 slot id,
    // render(slot, surface) 把该 slot 的 frame 用 ANativeWindow + sws_scale 直接 blit 到 surface,
    // releaseFrame(slot) 标 slot 空闲。Java 端把 slot id 存到 VideoDecoderOutputBuffer.decoderPrivate。

    /**
     * @return native handle(0 = 不支持/失败)
     */
    @JvmStatic
    external fun nativeVideoOpen(
        mime: String,
        extraData: ByteArray?,
        width: Int,
        height: Int,
    ): Long

    /**
     * 喂一个 packet。返回值:
     *   `>= 0`  分配到的 frame slot id;[metaOut][0]=width [1]=height 回填
     *   `-1`   packet 吃下但还没解出 frame(需更多 input,Media3 SimpleDecoder 自然处理)
     *   `<-1`  错误
     */
    @JvmStatic
    external fun nativeVideoDecode(
        handle: Long,
        inputDirect: java.nio.ByteBuffer,
        inputSize: Int,
        metaOut: IntArray,
    ): Int

    /** 把指定 slot 的 frame blit 到 surface。返回 0 / 负数错误码。 */
    @JvmStatic
    external fun nativeVideoRender(handle: Long, slot: Int, surface: android.view.Surface): Int

    /** 用户层处理完 frame 后 release slot。**必须** 跟 [nativeVideoDecode] 配对。 */
    @JvmStatic
    external fun nativeVideoReleaseFrame(handle: Long, slot: Int)

    @JvmStatic
    external fun nativeVideoReset(handle: Long)

    @JvmStatic
    external fun nativeVideoRelease(handle: Long)

    // ---- meta / introspection ----

    @JvmStatic
    private external fun nativeHasFfmpeg(): Boolean
    @JvmStatic
    private external fun nativeVersion(): String
    @JvmStatic
    private external fun nativeListDecoders(): Array<String>
}
