// FFmpeg 软解能力枚举 JNI 实现。
//
// `HAVE_FFMPEG` 由 CMakeLists 在 prebuilt .a 存在时定义;否则编出空实现,JVM 端拿到
// 空数组,UI 自然显示"软解 ✗",跟未接入 :native 的行为一致。

#include <jni.h>
#include <string>
#include <vector>

#ifdef HAVE_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
}
#endif

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeListDecoders(
    JNIEnv* env, jclass /* clazz */) {

    std::vector<std::string> result;

#ifdef HAVE_FFMPEG
    // avcodec_iterate 遍历所有注册的 codec;过滤 decoder。
    void* iter = nullptr;
    const AVCodec* codec;
    while ((codec = av_codec_iterate(&iter)) != nullptr) {
        if (av_codec_is_decoder(codec)) {
            // 用 "codec.name|mime_type|long_name" 拼一行,上层 split 解析。
            // mime 这里留空(FFmpeg API 没暴露 mime),交由 Kotlin 端按 codec.id 映射。
            std::string entry = codec->name;
            entry += "|";
            entry += "";  // mime placeholder
            entry += "|";
            entry += codec->long_name ? codec->long_name : "";
            result.push_back(entry);
        }
    }
#endif

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(
        static_cast<jsize>(result.size()), stringClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(result.size()); ++i) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(result[i].c_str()));
    }
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_abei_splitplay_nativelib_FfmpegNative_nativeHasFfmpeg(
    JNIEnv* /* env */, jclass /* clazz */) {
#ifdef HAVE_FFMPEG
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
