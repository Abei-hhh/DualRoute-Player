cmake_minimum_required(VERSION 3.22)

project(ffmpegwrapper LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

# ----------------------------------------------------------------------
# FFmpeg prebuilt static libs
#
# build_ffmpeg.sh 把按 ABI 编出来的 .a 和头文件归到 prebuilt/<abi>/{lib,include}。
# 这里按 ANDROID_ABI 自动选目录。<abi> 没产物时跳过链接 —— 当前 :native 仍能编出
# stub libffmpegwrapper.so,Java 端 loadLibrary 不会崩,只是软解枚举返回空。
# ----------------------------------------------------------------------
set(FFMPEG_DIR ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
set(FFMPEG_LIB_DIR ${FFMPEG_DIR}/lib)
set(FFMPEG_INCLUDE_DIR ${FFMPEG_DIR}/include)

set(FFMPEG_AVAILABLE FALSE)
if(EXISTS ${FFMPEG_LIB_DIR}/libavcodec.a)
    set(FFMPEG_AVAILABLE TRUE)
    message(STATUS "FFmpeg prebuilt found for ${ANDROID_ABI} at ${FFMPEG_LIB_DIR}")
else()
    message(WARNING "FFmpeg prebuilt NOT found for ${ANDROID_ABI}. libffmpegwrapper.so will be a stub.\n"
                    "Run scripts/build_ffmpeg.sh to generate it.")
endif()

# ----------------------------------------------------------------------
# Native sources
# ----------------------------------------------------------------------
add_library(ffmpegwrapper SHARED
    capability.cpp
    ffmpeg_jni.cpp
)

target_include_directories(ffmpegwrapper PRIVATE
    ${FFMPEG_INCLUDE_DIR}
)

if(FFMPEG_AVAILABLE)
    # 静态链接顺序:codec 依赖 util,format 依赖 codec + util,swscale/swresample 独立。
    # 同时定义 HAVE_FFMPEG 让 cpp 走真路径。
    target_compile_definitions(ffmpegwrapper PRIVATE HAVE_FFMPEG=1)

    add_library(avformat STATIC IMPORTED)
    set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.a)
    add_library(avcodec STATIC IMPORTED)
    set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.a)
    add_library(avutil STATIC IMPORTED)
    set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.a)
    add_library(swscale STATIC IMPORTED)
    set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.a)
    add_library(swresample STATIC IMPORTED)
    set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.a)

    target_link_libraries(ffmpegwrapper
        avformat
        avcodec
        avutil
        swscale
        swresample
        log
        z   # libz 是 FFmpeg 依赖,Android NDK 自带
    )
else()
    target_link_libraries(ffmpegwrapper log)
endif()
