plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.abei.splitplay.nativelib"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    // 显式锁定用户机器装的 NDK 版本,避免 AGP 自动选到不存在的 28.x。
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 24

        // 当前只编 arm64-v8a 验证管线;armv7a / x86_64 等 FFmpeg 源码到位后再扩。
        // build_ffmpeg.sh 也是按这里的 ABI 列表对齐输出目录的。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            // 用户机器装的是 4.1.2,显式锁版本避免 AGP 默认拿不到 3.22.x。
            // CMake 4 跟 AGP 9 之间的兼容性已验证。
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 纯 native 模块,不依赖任何 Android 业务库 —— 保持上层 :media 调用面薄。
}
