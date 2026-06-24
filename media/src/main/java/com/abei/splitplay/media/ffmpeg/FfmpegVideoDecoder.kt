package com.abei.splitplay.media.ffmpeg

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import com.abei.splitplay.nativelib.FfmpegNative

/**
 * Video 软解 SimpleDecoder。native 端持 frame slot ring,decode 返回 slot id 存到
 * outputBuffer.decoderPrivate;FfmpegVideoRenderer 之后调
 * [FfmpegNative.nativeVideoRender] 把 slot 的 frame 通过 ANativeWindow+sws_scale 直接
 * blit 到 surface。
 *
 * 选 `SURFACE_YUV` 模式 (即 initForPrivateFrame):不让 Media3 持 YUV 平面 ByteBuffer,
 * 全部 frame 数据留在 native side,避免每帧拷贝几 MB 的 YUV。
 */
@UnstableApi
class FfmpegVideoDecoder(
    format: Format,
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(NUM_INPUT_BUFFERS) as Array<DecoderInputBuffer>,
    arrayOfNulls<VideoDecoderOutputBuffer>(NUM_OUTPUT_BUFFERS) as Array<VideoDecoderOutputBuffer>,
) {

    internal val nativeHandle: Long
    private val metaBuffer = IntArray(2)
    val width: Int
    val height: Int

    init {
        val mime = format.sampleMimeType
            ?: throw FfmpegDecoderException("FfmpegVideoDecoder requires sampleMimeType")
        val extra: ByteArray? = format.initializationData.firstOrNull()
        // ExoPlayer Format width/height 可能为 NO_VALUE(-1),给 codec 0 占位让 FFmpeg 自己从
        // SPS/header 推断。
        val initW = format.width.coerceAtLeast(0)
        val initH = format.height.coerceAtLeast(0)
        nativeHandle = FfmpegNative.nativeVideoOpen(mime, extra, initW, initH)
        if (nativeHandle == 0L) {
            throw FfmpegDecoderException("nativeVideoOpen failed for $mime ${initW}x${initH}")
        }
        width = initW
        height = initH
        setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
    }

    override fun getName(): String = "FfmpegVideoDecoder"

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): VideoDecoderOutputBuffer =
        VideoDecoderOutputBuffer { buffer -> onOutputBufferReleased(buffer) }

    private fun onOutputBufferReleased(buffer: VideoDecoderOutputBuffer) {
        // Renderer 用完(或丢弃)输出 buffer 后 native frame 必须释放,否则 16 slot 很快用完。
        // decoderPrivate < 0 表示这个 buffer 没绑定 slot(比如解码失败时),跳过 release。
        val slot = buffer.decoderPrivate
        if (slot >= 0) {
            FfmpegNative.nativeVideoReleaseFrame(nativeHandle, slot)
            buffer.decoderPrivate = -1
        }
        releaseOutputBuffer(buffer)
    }

    override fun createUnexpectedDecodeException(error: Throwable): FfmpegDecoderException =
        FfmpegDecoderException("Unexpected FFmpeg video decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean,
    ): FfmpegDecoderException? {
        if (reset) FfmpegNative.nativeVideoReset(nativeHandle)
        val input = inputBuffer.data ?: return FfmpegDecoderException("input buffer.data is null")
        val inputSize = input.limit()
        val slot = FfmpegNative.nativeVideoDecode(nativeHandle, input, inputSize, metaBuffer)
        if (slot == -1) {
            // 还没解出 frame —— 标 buffer 为 SHOULD_NOT_RENDER(没有数据)。
            // Media3 SimpleDecoder 会跳过这个 output buffer。
            outputBuffer.shouldBeSkipped = true
            return null
        }
        if (slot < 0) return FfmpegDecoderException("video decode failed: code=$slot")

        outputBuffer.timeUs = inputBuffer.timeUs
        outputBuffer.initForPrivateFrame(metaBuffer[0], metaBuffer[1])
        outputBuffer.decoderPrivate = slot
        return null
    }

    override fun release() {
        super.release()
        FfmpegNative.nativeVideoRelease(nativeHandle)
    }

    companion object {
        private const val NUM_INPUT_BUFFERS = 8
        private const val NUM_OUTPUT_BUFFERS = 8
        private const val INITIAL_INPUT_BUFFER_SIZE = 768 * 1024  // 768KB

        /** 跟 native video_mime_to_codec_id 对齐的白名单。 */
        val SUPPORTED_MIMES: Set<String> = setOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_VP8,
            MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_AV1,
            MimeTypes.VIDEO_MP4V,
            MimeTypes.VIDEO_MPEG2,
            MimeTypes.VIDEO_H263,
        )

        fun isSupported(mime: String?): Boolean {
            if (!FfmpegNative.hasFfmpeg) return false
            return mime in SUPPORTED_MIMES
        }
    }
}
