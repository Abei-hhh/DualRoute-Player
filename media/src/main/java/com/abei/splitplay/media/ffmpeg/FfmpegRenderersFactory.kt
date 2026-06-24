package com.abei.splitplay.media.ffmpeg

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.abei.splitplay.nativelib.FfmpegNative
import java.util.ArrayList

/**
 * [DefaultRenderersFactory] 子类:在 audio renderer 列表的**最前面**塞一个
 * [FfmpegAudioRenderer]。ExoPlayer 按列表顺序询问每个 renderer,有 [FORMAT_HANDLED]
 * 就用它 —— 所以 FFmpeg 软解比硬解优先。如果 mime 不在 FFmpeg 白名单(或 .so 没编)
 * 自动 fallback 到 super.buildAudioRenderers 添加的 MediaCodecAudioRenderer。
 *
 * Video 端不动 —— 真正软解视频要写一个 `DecoderVideoRenderer` 子类 + GL YUV→RGB 渲染,
 * 工作量大,Phase 14 暂不做。FORCE_SW 视频仍然走默认硬解(等同 AUTO)。
 *
 * 用法:`SinglePlayerEngine` 当 `decoderPolicy == FORCE_SW` 时
 * `ExoPlayer.Builder(...).setRenderersFactory(FfmpegRenderersFactory(ctx))`。
 */
@UnstableApi
class FfmpegRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // 把 FFmpeg renderer 加到 list 第一位 —— 有 FORMAT_HANDLED 就用它
        if (FfmpegNative.hasFfmpeg) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }
        // 后面跟默认的 MediaCodec audio renderers 作 fallback —— FFmpeg renderer
        // 拒绝(FORMAT_UNSUPPORTED_SUBTYPE)的格式自动落到这里
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
    }
}
