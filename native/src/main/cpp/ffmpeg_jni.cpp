// FFmpeg 解码 JNI 占位实现。
//
// 当前只暴露能力枚举接口(见 capability.cpp);真正的 decoder pipeline(open/sendPacket/
// receiveFrame/flush/close)等 M5b 后续 patch 接入。这里留一个 nativeVersion 方便从
// Kotlin 端验证 .so 加载成功。

#include <jni.h>
#include <string>

#ifdef HAVE_FFMPEG
extern "C" {
#include <libavutil/avutil.h>
}
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeVersion(
    JNIEnv* env, jclass /* clazz */) {
#ifdef HAVE_FFMPEG
    std::string v = "FFmpeg ";
    v += av_version_info();
    return env->NewStringUTF(v.c_str());
#else
    return env->NewStringUTF("stub (no FFmpeg compiled)");
#endif
}
