package com.abei.splitplay.media.ffmpeg

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.abei.splitplay.nativelib.FfmpegNative
import java.util.ArrayList

/**
 * [DefaultRenderersFactory] 子类:在 audio + video renderer 列表的**最前面**塞 FFmpeg
 * 软解。ExoPlayer 按列表顺序问每个 renderer,有 [FORMAT_HANDLED] 就用它 —— 所以
 * FFmpeg 软解比硬解优先。mime 不在 FFmpeg 白名单(或 .so 没编)自动 fallback 到
 * super.buildXxxRenderers 添加的 MediaCodec*Renderer。
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
        if (FfmpegNative.hasFfmpeg) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        if (FfmpegNative.hasFfmpeg) {
            out.add(
                FfmpegVideoRenderer(
                    allowedJoiningTimeMs = allowedVideoJoiningTimeMs,
                    eventHandler = eventHandler,
                    eventListener = eventListener,
                    maxDroppedFramesToNotify = MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                )
            )
        }
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out,
        )
    }

    private companion object {
        // 跟 DefaultRenderersFactory 内默认值对齐
        const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }
}
