// FFmpeg video decode pipeline 暴露给 JNI。
//
// 设计:Media3 `DecoderVideoRenderer` 要 decode/render 分离,decode 阶段把 frame 暂存,
// render 阶段把暂存帧 blit 到 Surface。这里用 SURFACE_YUV (private frame) 模式:
//  - decode → 解出 AVFrame 放到内部 ring buffer 的一个 slot,返回 slot index 给 Java
//  - Java 用 outputBuffer.initForPrivateFrame(w,h) + outputBuffer.decoderPrivate=slot
//  - render(slot, Surface) → ANativeWindow lock + sws_scale 把 YUV→RGBA_8888 写到 buffer + unlockAndPost
//  - releaseFrame(slot) → av_frame_unref 释放,slot 可复用
//
// ring 大小 [MAX_FRAME_SLOTS] 跟 Media3 NUM_OUTPUT_BUFFERS 对齐;Media3 一次最多 dequeue 这么多。

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string>
#include <cstring>

#ifdef HAVE_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

namespace {

constexpr int MAX_FRAME_SLOTS = 16;

struct VideoCtx {
    AVCodecContext* codec_ctx = nullptr;
    AVPacket* packet = nullptr;

    // Frame ring:每个 slot 一个 AVFrame,decode 喂数据进去,render 时读出。
    AVFrame* frames[MAX_FRAME_SLOTS] = {};
    bool slot_in_use[MAX_FRAME_SLOTS] = {};

    // SwsContext lazy 在 render 时建,因为 source pix_fmt 要等第一个 frame 才知道。
    SwsContext* sws = nullptr;
    int sws_src_w = 0;
    int sws_src_h = 0;
    AVPixelFormat sws_src_fmt = AV_PIX_FMT_NONE;

    int hint_w = 0;
    int hint_h = 0;
};

AVCodecID video_mime_to_codec_id(const std::string& mime) {
    if (mime == "video/avc")               return AV_CODEC_ID_H264;
    if (mime == "video/hevc")              return AV_CODEC_ID_HEVC;
    if (mime == "video/x-vnd.on2.vp8")     return AV_CODEC_ID_VP8;
    if (mime == "video/x-vnd.on2.vp9")     return AV_CODEC_ID_VP9;
    if (mime == "video/av01")              return AV_CODEC_ID_AV1;
    if (mime == "video/mp4v-es")           return AV_CODEC_ID_MPEG4;
    if (mime == "video/mpeg2")             return AV_CODEC_ID_MPEG2VIDEO;
    if (mime == "video/3gpp")              return AV_CODEC_ID_H263;
    return AV_CODEC_ID_NONE;
}

void close_video_ctx(VideoCtx* c) {
    if (!c) return;
    for (int i = 0; i < MAX_FRAME_SLOTS; i++) {
        if (c->frames[i]) av_frame_free(&c->frames[i]);
    }
    if (c->sws)       sws_freeContext(c->sws);
    if (c->packet)    av_packet_free(&c->packet);
    if (c->codec_ctx) avcodec_free_context(&c->codec_ctx);
    delete c;
}

int find_free_slot(VideoCtx* c) {
    for (int i = 0; i < MAX_FRAME_SLOTS; i++) {
        if (!c->slot_in_use[i]) return i;
    }
    return -1;
}

}  // namespace
#endif  // HAVE_FFMPEG

extern "C" JNIEXPORT jlong JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVideoOpen(
    JNIEnv* env, jclass /*clazz*/,
    jstring jmime, jbyteArray jextra, jint width, jint height) {
#ifndef HAVE_FFMPEG
    return 0;
#else
    const char* mime_cstr = env->GetStringUTFChars(jmime, nullptr);
    std::string mime(mime_cstr ? mime_cstr : "");
    env->ReleaseStringUTFChars(jmime, mime_cstr);

    AVCodecID codec_id = video_mime_to_codec_id(mime);
    if (codec_id == AV_CODEC_ID_NONE) return 0;

    const AVCodec* codec = avcodec_find_decoder(codec_id);
    if (!codec) return 0;

    VideoCtx* c = new VideoCtx();
    c->codec_ctx = avcodec_alloc_context3(codec);
    if (!c->codec_ctx) { close_video_ctx(c); return 0; }

    c->codec_ctx->width = width;
    c->codec_ctx->height = height;
    c->hint_w = width;
    c->hint_h = height;

    // 容器侧 CSD / SPS+PPS (H.264) 等 → AVCodecContext.extradata
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

    // 多线程解码 —— ARM 移动设备 4 个线程合适
    c->codec_ctx->thread_count = 4;
    c->codec_ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;

    if (avcodec_open2(c->codec_ctx, codec, nullptr) < 0) {
        close_video_ctx(c);
        return 0;
    }

    c->packet = av_packet_alloc();
    if (!c->packet) { close_video_ctx(c); return 0; }

    for (int i = 0; i < MAX_FRAME_SLOTS; i++) {
        c->frames[i] = av_frame_alloc();
        if (!c->frames[i]) { close_video_ctx(c); return 0; }
    }

    return reinterpret_cast<jlong>(c);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVideoReset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
#ifdef HAVE_FFMPEG
    VideoCtx* c = reinterpret_cast<VideoCtx*>(handle);
    if (!c) return;
    avcodec_flush_buffers(c->codec_ctx);
    // 全部 slot 释放
    for (int i = 0; i < MAX_FRAME_SLOTS; i++) {
        if (c->slot_in_use[i]) {
            av_frame_unref(c->frames[i]);
            c->slot_in_use[i] = false;
        }
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVideoRelease(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
#ifdef HAVE_FFMPEG
    close_video_ctx(reinterpret_cast<VideoCtx*>(handle));
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVideoReleaseFrame(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint slot) {
#ifdef HAVE_FFMPEG
    VideoCtx* c = reinterpret_cast<VideoCtx*>(handle);
    if (!c || slot < 0 || slot >= MAX_FRAME_SLOTS) return;
    if (c->slot_in_use[slot]) {
        av_frame_unref(c->frames[slot]);
        c->slot_in_use[slot] = false;
    }
#endif
}

/**
 * 解一个 packet。返回值:
 *   >= 0 : 分配到的 frame slot index;meta[0]=width meta[1]=height
 *   == -1 : packet 已吃下但还没解出 frame(需要更多 packet)
 *   < -1  : 解码错误
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVideoDecode(
    JNIEnv* env, jclass /*clazz*/, jlong handle,
    jobject input_direct, jint input_size, jintArray meta_out) {
#ifndef HAVE_FFMPEG
    return -2;
#else
    VideoCtx* c = reinterpret_cast<VideoCtx*>(handle);
    if (!c) return -2;

    uint8_t* in_buf = (uint8_t*)env->GetDirectBufferAddress(input_direct);
    if (!in_buf && input_size > 0) return -2;

    c->packet->data = in_buf;
    c->packet->size = input_size;
    int ret = avcodec_send_packet(c->codec_ctx, c->packet);
    if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) return -3;

    int slot = find_free_slot(c);
    if (slot < 0) return -4;  // 上层来不及 release frame,backpressure

    ret = avcodec_receive_frame(c->codec_ctx, c->frames[slot]);
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return -1;
    if (ret < 0) return -5;

    c->slot_in_use[slot] = true;

    // 把 width/height 回填给 Java 侧(initForPrivateFrame 用)
    if (meta_out) {
        jint meta[2];
        meta[0] = c->frames[slot]->width;
        meta[1] = c->frames[slot]->height;
        env->SetIntArrayRegion(meta_out, 0, 2, meta);
    }
    return slot;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVideoRender(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jint slot, jobject surface) {
#ifndef HAVE_FFMPEG
    return -1;
#else
    VideoCtx* c = reinterpret_cast<VideoCtx*>(handle);
    if (!c || slot < 0 || slot >= MAX_FRAME_SLOTS || !c->slot_in_use[slot]) return -1;
    if (!surface) return -2;

    AVFrame* frame = c->frames[slot];
    int src_w = frame->width;
    int src_h = frame->height;
    AVPixelFormat src_fmt = (AVPixelFormat)frame->format;
    if (src_w <= 0 || src_h <= 0 || src_fmt == AV_PIX_FMT_NONE) return -3;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return -4;

    // 让 Surface buffer 跟 frame 等分辨率;Android 自动 scale 到屏幕。
    // WINDOW_FORMAT_RGBA_8888 是 sws_scale 最常见输出 — sws 自带 BT.601 / BT.709 转换。
    if (ANativeWindow_setBuffersGeometry(window, src_w, src_h, WINDOW_FORMAT_RGBA_8888) != 0) {
        ANativeWindow_release(window);
        return -5;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, nullptr) != 0) {
        ANativeWindow_release(window);
        return -6;
    }

    // SwsContext lazy 建立或在 source format 改变时重建。
    if (!c->sws || c->sws_src_w != src_w || c->sws_src_h != src_h || c->sws_src_fmt != src_fmt) {
        if (c->sws) sws_freeContext(c->sws);
        c->sws = sws_getContext(
            src_w, src_h, src_fmt,
            src_w, src_h, AV_PIX_FMT_RGBA,
            SWS_BILINEAR, nullptr, nullptr, nullptr);
        c->sws_src_w = src_w;
        c->sws_src_h = src_h;
        c->sws_src_fmt = src_fmt;
    }
    if (!c->sws) {
        ANativeWindow_unlockAndPost(window);
        ANativeWindow_release(window);
        return -7;
    }

    uint8_t* dst_data[4] = { (uint8_t*)buf.bits, nullptr, nullptr, nullptr };
    int dst_stride[4] = { buf.stride * 4, 0, 0, 0 };  // RGBA = 4 bytes / pixel

    sws_scale(c->sws,
              frame->data, frame->linesize, 0, src_h,
              dst_data, dst_stride);

    ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
    return 0;
#endif
}
