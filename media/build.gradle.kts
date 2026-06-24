plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.abei.splitplay.media"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    // IjkPlayer (FFmpeg) —— PlayerEngineType.IJK 引擎用。
    // 没接上时整个 :media 模块不依赖 ijkplayer-* 包,只留下 IjkPlayerEngineFactory 的 stub。
    // 接入方式见 PlayerEngineFactory.kt 里 IjkPlayerEngineFactory 上方的注释。
    // implementation(libs.ijkplayer.java)
    // implementation(libs.ijkplayer.arm64)
    // implementation(libs.ijkplayer.armv7a)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
