package com.abei.splitplay.media.ffmpeg

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

/**
 * Media3 `DecoderAudioRenderer` 的 FFmpeg 实现。Renderer 自己管 input/output buffer queue +
 * AudioSink 写入,我们只负责挑 Format 看能不能吃,以及实例化 [FfmpegAudioDecoder]。
 *
 * 跟硬解 `MediaCodecAudioRenderer` 共存时,优先级由 [FfmpegRenderersFactory] 决定 ——
 * 把这个放进 renderer list 哪个位置就先用哪个。
 */
@UnstableApi
class FfmpegAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<FfmpegAudioDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "FfmpegAudioRenderer"

    /**
     * 告诉 ExoPlayer 这个 renderer 是否能播给定 [format]。
     * Return:
     *  - [C.FORMAT_HANDLED] —— mime 在我们的支持表里,采样率/通道数也 OK
     *  - [C.FORMAT_UNSUPPORTED_SUBTYPE] —— mime 不在表里(让 ExoPlayer 试别的 renderer)
     *  - [C.FORMAT_UNSUPPORTED_TYPE] —— 不是 audio
     */
    override fun supportsFormatInternal(format: Format): Int {
        val mime = format.sampleMimeType
        if (mime == null || !MimeTypes.isAudio(mime)) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        if (!FfmpegAudioDecoder.isSupported(mime)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        // 不限制 PCM encoding —— FFmpeg 解出来统一转 S16LE,sink 接受
        return C.FORMAT_HANDLED
    }

    /**
     * 真正解码出来的 PCM 是 S16LE,跟 [format] 原始编码无关。
     */
    override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(decoder.outputSampleRate)
            .setChannelCount(decoder.outputChannelCount)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

    @Throws(FfmpegDecoderException::class)
    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfmpegAudioDecoder {
        return FfmpegAudioDecoder(format)
    }
}
