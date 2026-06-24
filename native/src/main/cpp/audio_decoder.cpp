// FFmpeg audio decode pipeline 暴露给 JNI。
//
// 接口:
//   nativeAudioOpen(mime, extraData, sampleRate, channels) → handle (long)
//   nativeAudioDecode(handle, inputDirect, inputSize, outputDirect, outputCapacity) → bytesWritten/error
//   nativeAudioReset(handle)
//   nativeAudioRelease(handle)
//
// 设计点:
//  - PCM 统一重采样到 S16LE,跟 Media3 DefaultAudioSink 默认期望对齐
//  - 一个 input packet 可能产生 0~N 帧 PCM —— 全部 swr_convert 后拼到 output 直 ByteBuffer
//  - 不维护内部 ring buffer;调用方(FfmpegAudioDecoder)给的 256KB output 缓冲对 48k stereo
//    足够一个 1024-frame AAC packet 用(~4KB)
//  - 没编 FFmpeg(HAVE_FFMPEG 未定义)时全部 stub 返回 -1

#include <jni.h>
#include <string>
#include <cstring>

#ifdef HAVE_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

namespace {

struct AudioCtx {
    AVCodecContext* codec_ctx = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    SwrContext* swr = nullptr;
    int out_sample_rate = 0;
    int out_channels = 0;
};

// Android MIME → AVCodecID,覆盖 PRD §4.3 重点。
AVCodecID mime_to_codec_id(const std::string& mime) {
    if (mime == "audio/mp4a-latm") return AV_CODEC_ID_AAC;
    if (mime == "audio/mpeg")      return AV_CODEC_ID_MP3;
    if (mime == "audio/ac3")       return AV_CODEC_ID_AC3;
    if (mime == "audio/eac3")      return AV_CODEC_ID_EAC3;
    if (mime == "audio/opus")      return AV_CODEC_ID_OPUS;
    if (mime == "audio/vorbis")    return AV_CODEC_ID_VORBIS;
    if (mime == "audio/flac")      return AV_CODEC_ID_FLAC;
    if (mime == "audio/alac")      return AV_CODEC_ID_ALAC;
    if (mime == "audio/3gpp")      return AV_CODEC_ID_AMR_NB;
    if (mime == "audio/amr-wb")    return AV_CODEC_ID_AMR_WB;
    return AV_CODEC_ID_NONE;
}

void close_audio_ctx(AudioCtx* c) {
    if (!c) return;
    if (c->swr)       swr_free(&c->swr);
    if (c->frame)     av_frame_free(&c->frame);
    if (c->packet)    av_packet_free(&c->packet);
    if (c->codec_ctx) avcodec_free_context(&c->codec_ctx);
    delete c;
}

}  // namespace
#endif  // HAVE_FFMPEG

extern "C" JNIEXPORT jlong JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeAudioOpen(
    JNIEnv* env, jclass /*clazz*/,
    jstring jmime, jbyteArray jextra, jint sample_rate, jint channels) {
#ifndef HAVE_FFMPEG
    return 0;
#else
    const char* mime_cstr = env->GetStringUTFChars(jmime, nullptr);
    std::string mime(mime_cstr ? mime_cstr : "");
    env->ReleaseStringUTFChars(jmime, mime_cstr);

    AVCodecID codec_id = mime_to_codec_id(mime);
    if (codec_id == AV_CODEC_ID_NONE) return 0;

    const AVCodec* codec = avcodec_find_decoder(codec_id);
    if (!codec) return 0;

    AudioCtx* c = new AudioCtx();
    c->codec_ctx = avcodec_alloc_context3(codec);
    if (!c->codec_ctx) { close_audio_ctx(c); return 0; }

    // 入参 sample_rate / channels 是容器解析出来的提示;FFmpeg 自己也会从 packet header
    // 重新确定,这里给个先验避免某些容器没暴露的情况。
    c->codec_ctx->sample_rate = sample_rate;
    av_channel_layout_default(&c->codec_ctx->ch_layout, channels);

    // CSD (codec-specific data,Android 把 AAC AudioSpecificConfig 等放这里) → AVCodecContext.extradata
    if (jextra) {
        jsize extra_len = env->GetArrayLength(jextra);
        if (extra_len > 0) {
            c->codec_ctx->extradata = (uint8_t*)av_malloc(extra_len + AV_INPUT_BUFFER_PADDING_SIZE);
            if (c->codec_ctx->extradata) {
                env->GetByteArrayRegion(jextra, 0, extra_len, (jbyte*)c->codec_ctx->extradata);
                memset(c->codec_ctx->extradata + extra_len, 0, AV_INPUT_BUFFER_PADDING_SIZE);
                c->codec_ctx->extradata_size = extra_len;
            }
        }
    }

    if (avcodec_open2(c->codec_ctx, codec, nullptr) < 0) {
        close_audio_ctx(c);
        return 0;
    }

    c->packet = av_packet_alloc();
    c->frame = av_frame_alloc();
    if (!c->packet || !c->frame) { close_audio_ctx(c); return 0; }

    c->out_sample_rate = sample_rate;
    c->out_channels = channels;

    // 重采样到 S16LE planar→interleaved。Source format 在第一帧 receive 后才知道,
    // swr_init 推到第一次 decode 时做。
    return reinterpret_cast<jlong>(c);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeAudioReset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
#ifdef HAVE_FFMPEG
    AudioCtx* c = reinterpret_cast<AudioCtx*>(handle);
    if (c && c->codec_ctx) {
        avcodec_flush_buffers(c->codec_ctx);
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeAudioRelease(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
#ifdef HAVE_FFMPEG
    close_audio_ctx(reinterpret_cast<AudioCtx*>(handle));
#endif
}

#ifdef HAVE_FFMPEG
// 内部辅助:swr lazy 初始化,第一次 receive_frame 拿到具体 source format 后建。
static int ensure_swr(AudioCtx* c) {
    if (c->swr) return 0;
    AVChannelLayout out_layout;
    av_channel_layout_default(&out_layout, c->out_channels);
    int ret = swr_alloc_set_opts2(
        &c->swr,
        &out_layout, AV_SAMPLE_FMT_S16, c->out_sample_rate,
        &c->frame->ch_layout, (AVSampleFormat)c->frame->format, c->frame->sample_rate,
        0, nullptr);
    av_channel_layout_uninit(&out_layout);
    if (ret < 0 || !c->swr) return -1;
    return swr_init(c->swr);
}
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeAudioDecode(
    JNIEnv* env, jclass /*clazz*/, jlong handle,
    jobject input_direct, jint input_size,
    jobject output_direct, jint output_capacity) {
#ifndef HAVE_FFMPEG
    return -1;
#else
    AudioCtx* c = reinterpret_cast<AudioCtx*>(handle);
    if (!c) return -1;
    uint8_t* in_buf  = (uint8_t*)env->GetDirectBufferAddress(input_direct);
    uint8_t* out_buf = (uint8_t*)env->GetDirectBufferAddress(output_direct);
    if (!in_buf || !out_buf) return -1;

    // send packet
    c->packet->data = in_buf;
    c->packet->size = input_size;
    int ret = avcodec_send_packet(c->codec_ctx, c->packet);
    if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
        return -2;
    }

    int total_written = 0;
    while (true) {
        ret = avcodec_receive_frame(c->codec_ctx, c->frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) return -3;

        if (ensure_swr(c) < 0) return -4;

        // 估算输出 frame 数:每输入 frame nb_samples → 输出按比例 swr_get_out_samples。
        int max_out_samples = swr_get_out_samples(c->swr, c->frame->nb_samples);
        int max_out_bytes = max_out_samples * c->out_channels * 2;  // S16LE = 2 bytes / sample
        if (total_written + max_out_bytes > output_capacity) {
            // 输出 buffer 不够装,丢弃该 frame 但报错让上层加大 buffer。
            return -5;
        }

        uint8_t* dst = out_buf + total_written;
        int converted = swr_convert(
            c->swr,
            &dst, max_out_samples,
            (const uint8_t**)c->frame->extended_data, c->frame->nb_samples);
        if (converted < 0) return -6;

        total_written += converted * c->out_channels * 2;
        av_frame_unref(c->frame);
    }
    return total_written;
#endif
}
