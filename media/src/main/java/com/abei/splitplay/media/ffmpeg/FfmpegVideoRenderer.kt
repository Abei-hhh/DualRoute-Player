package com.abei.splitplay.media.ffmpeg

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.abei.splitplay.nativelib.FfmpegNative

/**
 * `DecoderVideoRenderer` 的 FFmpeg 实现。基类管 buffer queue + 时钟同步,我们负责:
 *  - [supportsFormat] —— 按 mime 白名单挑能不能解
 *  - [createDecoder] —— 实例化 [FfmpegVideoDecoder]
 *  - [renderOutputBufferToSurface] —— 调 native `nativeVideoRender` 用 ANativeWindow + sws_scale
 *    把 frame blit 到 Surface(SURFACE_YUV 模式)
 *  - [setDecoderOutputMode] —— 我们只支持 SURFACE_YUV,YUV 模式 ignore
 *
 * 跟硬解 `MediaCodecVideoRenderer` 共存时谁先由 [FfmpegRenderersFactory] 决定。
 */
@UnstableApi
class FfmpegVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : DecoderVideoRenderer(
    allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify,
) {

    private var currentDecoder: FfmpegVideoDecoder? = null

    override fun getName(): String = "FfmpegVideoRenderer"

    @Throws(FfmpegDecoderException::class)
    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ): androidx.media3.decoder.Decoder<
        androidx.media3.decoder.DecoderInputBuffer,
        out VideoDecoderOutputBuffer,
        out DecoderException,
    > {
        val d = FfmpegVideoDecoder(format)
        currentDecoder = d
        return d
    }

    /**
     * native 端的 sws_scale 把 YUV→RGBA8888 直接写到 ANativeWindow buffer。Media3 让基类
     * 在 frame 到 presentation time 时调过来。
     */
    @Throws(FfmpegDecoderException::class)
    override fun renderOutputBufferToSurface(
        outputBuffer: VideoDecoderOutputBuffer,
        surface: Surface,
    ) {
        val decoder = currentDecoder ?: throw FfmpegDecoderException("renderOutputBufferToSurface: decoder is null")
        val slot = outputBuffer.decoderPrivate
        if (slot < 0) {
            // 没绑定 native slot(比如 EOS / 跳过帧),不渲染。
            return
        }
        val ret = FfmpegNative.nativeVideoRender(decoder.nativeHandle, slot, surface)
        if (ret < 0) {
            throw FfmpegDecoderException("nativeVideoRender failed: code=$ret")
        }
    }

    /**
     * Media3 提供 YUV planes / SURFACE_YUV / NONE 三种输出模式。SURFACE_YUV 表示"decoder
     * 自己拿 surface 渲染",这是我们要的;YUV 表示让 Media3 内部 GL 渲染器接管(需要
     * GLSurfaceView),我们暂不支持。
     */
    override fun setDecoderOutputMode(outputMode: Int) {
        // no-op —— 我们的 decoder 永远走 private frame + native render
    }

    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        val mime = format.sampleMimeType
        if (mime == null || !MimeTypes.isVideo(mime)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (!FfmpegVideoDecoder.isSupported(mime)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        // 不在原生层做 profile/level 检查 —— FFmpeg 通常都能吃,失败时 ExoPlayer 自动 fallback。
        // ADAPTIVE_NOT_SEAMLESS:format 切换(如 resolution change)需要重建 decoder。
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        )
    }
}
