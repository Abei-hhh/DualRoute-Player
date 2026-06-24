package com.abei.splitplay.media.ffmpeg

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import com.abei.splitplay.nativelib.FfmpegNative

/**
 * SimpleDecoder 子类:把 ExoPlayer 喂来的压缩 audio packet 交给 FFmpeg 软解,
 * 输出 PCM S16LE,DefaultAudioSink 直接可用。
 *
 *  - input buffer 用 DIRECT 模式 —— FFmpeg JNI 直读 DirectByteBuffer,零 copy
 *  - output 容量 [OUTPUT_BUFFER_SIZE_BYTES] = 256KB,对 48k stereo 单包 ~4KB 绰绰有余;
 *    如果 native 返回 -5(buffer 不够)会动态 grow
 *  - flush 走 [nativeAudioReset] 清 native side 缓存,seek 时必经
 *
 * 不可重入 —— 单 decoder 实例 + 单 native handle,Media3 自己保证 dequeue/queue 串行。
 */
@UnstableApi
class FfmpegAudioDecoder(
    format: Format,
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(NUM_INPUT_BUFFERS) as Array<DecoderInputBuffer>,
    arrayOfNulls<SimpleDecoderOutputBuffer>(NUM_OUTPUT_BUFFERS) as Array<SimpleDecoderOutputBuffer>,
) {

    val outputSampleRate: Int = format.sampleRate.coerceAtLeast(8_000)
    val outputChannelCount: Int = format.channelCount.coerceAtLeast(1)

    private val nativeHandle: Long

    init {
        val mime = format.sampleMimeType
            ?: throw FfmpegDecoderException("FfmpegAudioDecoder requires sampleMimeType")
        // Android Format.initializationData 是 codec-specific data 列表,大多数 codec 一份(AAC、Vorbis 等)
        val extra: ByteArray? = format.initializationData.firstOrNull()
        nativeHandle = FfmpegNative.nativeAudioOpen(mime, extra, outputSampleRate, outputChannelCount)
        if (nativeHandle == 0L) {
            throw FfmpegDecoderException("nativeAudioOpen failed for $mime")
        }
        setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
    }

    override fun getName(): String = "FfmpegAudioDecoder"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer =
        SimpleDecoderOutputBuffer { buffer -> releaseOutputBuffer(buffer) }

    override fun createUnexpectedDecodeException(error: Throwable): FfmpegDecoderException =
        FfmpegDecoderException("Unexpected FFmpeg decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean,
    ): FfmpegDecoderException? {
        if (reset) FfmpegNative.nativeAudioReset(nativeHandle)
        val input = inputBuffer.data ?: return FfmpegDecoderException("input buffer.data is null")
        val inputSize = input.limit()

        // 先按默认 size 初始化 output;native 返回 -5 不够时 grow 一次再试。
        var outBuf = outputBuffer.init(inputBuffer.timeUs, OUTPUT_BUFFER_SIZE_BYTES)
        var written = FfmpegNative.nativeAudioDecode(
            nativeHandle, input, inputSize, outBuf, outBuf.capacity(),
        )
        if (written == -5) {
            outBuf = outputBuffer.grow(OUTPUT_BUFFER_SIZE_BYTES * 4)
            written = FfmpegNative.nativeAudioDecode(
                nativeHandle, input, inputSize, outBuf, outBuf.capacity(),
            )
        }
        if (written < 0) {
            return FfmpegDecoderException("FFmpeg decode failed: code=$written")
        }
        // ExoPlayer 期望 outputBuffer.data position=0, limit=written
        outBuf.position(0)
        outBuf.limit(written)
        return null
    }

    override fun release() {
        super.release()
        FfmpegNative.nativeAudioRelease(nativeHandle)
    }

    companion object {
        private const val NUM_INPUT_BUFFERS = 16
        private const val NUM_OUTPUT_BUFFERS = 16
        private const val INITIAL_INPUT_BUFFER_SIZE = 16 * 1024
        private const val OUTPUT_BUFFER_SIZE_BYTES = 256 * 1024

        /**
         * 跟 [com.abei.splitplay.media.ffmpeg.FfmpegAudioRenderer] 共享的 codec 白名单。
         * 跟 native 的 mime_to_codec_id 映射对齐,任何超出范围的 mime UI 不会触发 FFmpeg 路径。
         */
        val SUPPORTED_MIMES: Set<String> = setOf(
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAC,
            MimeTypes.AUDIO_AMR_NB,
            MimeTypes.AUDIO_AMR_WB,
        )

        fun isSupported(mime: String?): Boolean {
            if (!FfmpegNative.hasFfmpeg) return false
            return mime in SUPPORTED_MIMES
        }
    }
}

/** [SimpleDecoder] 的错误回路,Media3 要求每个 decoder 自带一个 [DecoderException] 子类。 */
@UnstableApi
class FfmpegDecoderException(msg: String, cause: Throwable? = null) : DecoderException(msg, cause)
