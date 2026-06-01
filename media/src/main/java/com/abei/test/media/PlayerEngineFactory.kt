package com.abei.test.media

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * 支持的播放引擎类型。
 *
 * 设计上把"用哪个播放器内核"做成可切换:目前只有 [EXOPLAYER] 是真的实现,
 * IjkPlayer / 自定义引擎都留好接入口,后续在 :media (或独立模块) 里补具体实现即可,
 * 上层 [com.abei.test.playerui.PlayerViewModel] 不需要再改。
 */
enum class PlayerEngineType(val displayName: String, val description: String) {
    EXOPLAYER(
        displayName = "ExoPlayer (Media3)",
        description = "Google 官方实现,硬解优先,HLS/DASH/SmoothStreaming 一应俱全。",
    ),
    IJK(
        displayName = "IjkPlayer (FFmpeg)",
        description = "B 站基于 FFmpeg 的播放器,RTMP/直播流支持好;需要手动接入 aar 后启用。",
    ),
    CUSTOM(
        displayName = "自定义引擎",
        description = "完全自实现的解封装+解码+渲染管线;尚未集成。",
    ),
}

/**
 * 每个 [PlayerEngineType] 提供一个工厂,用统一的 (Context, CoroutineScope) 创建出
 * 实现了 [PlayerEngine] 的实例。未实现的工厂在 [create] 时抛 [NotImplementedError],
 * 上层兜底回退到 ExoPlayer。
 */
interface PlayerEngineFactory {
    val type: PlayerEngineType
    fun create(context: Context, scope: CoroutineScope): PlayerEngine
}

object ExoPlayerEngineFactory : PlayerEngineFactory {
    override val type = PlayerEngineType.EXOPLAYER
    override fun create(context: Context, scope: CoroutineScope): PlayerEngine =
        SinglePlayerEngine(context, scope)
}

object IjkPlayerEngineFactory : PlayerEngineFactory {
    override val type = PlayerEngineType.IJK

    /**
     * IjkPlayer 引擎入口。当前抛 [NotImplementedError],上层会兜底用 ExoPlayer。
     *
     * 启用步骤(maven.bilibili.com 已基本不可用,推荐"本地 aar"路线):
     *  1) 从 github.com/bilibili/ijkplayer 的 release(或镜像)下载这两个 aar:
     *       - ijkplayer-java-0.8.8.aar
     *       - ijkplayer-arm64-0.8.8.aar  (要支持 32 位再加 ijkplayer-armv7a)
     *  2) 放到 `:media/libs/` 下,并在 `:media/build.gradle.kts` 加入:
     *       implementation(files("libs/ijkplayer-java-0.8.8.aar"))
     *       implementation(files("libs/ijkplayer-arm64-0.8.8.aar"))
     *  3) 新建 `media/.../IjkPlayerEngine.kt` 包一份 [IjkMediaPlayer]:
     *       - listener 把 onPrepared / onCompletion / onVideoSizeChanged / onError 映射到 PlaybackState
     *       - 250ms 协程轮询 currentPosition(IjkMediaPlayer 不主动推 position)
     *       - 在 setVideoSurface 里调 `ijk.setSurface(surface)`
     *       - 在 setPlaybackSpeed 里调 `ijk.setSpeed(speed)`(要 native 编 soundtouch)
     *  4) 把这里的 throw 换成 `return IjkPlayerEngine(context.applicationContext, scope)`
     *
     * 完整 IjkPlayerEngine 实现历史版本在 git 里查 commit "Integrate IjkPlayer"。
     */
    override fun create(context: Context, scope: CoroutineScope): PlayerEngine {
        throw NotImplementedError(
            "IjkPlayer 引擎尚未集成。把 ijkplayer 的 aar 放到 :media/libs/ 后," +
                "按 PlayerEngineFactory.kt 注释里的步骤启用。"
        )
    }
}

object CustomPlayerEngineFactory : PlayerEngineFactory {
    override val type = PlayerEngineType.CUSTOM
    override fun create(context: Context, scope: CoroutineScope): PlayerEngine {
        // 接入步骤:
        //  1. 选定 demux/decode 实现(FFmpeg JNI 或纯 Kotlin 解析 box)
        //  2. 渲染输出 Surface (MediaCodec hw / OpenGL ES sw)
        //  3. 音频走 AudioTrack;时钟同步取 audio PTS
        //  4. 把内部状态打到 PlaybackState 推进 state flow
        throw NotImplementedError("自定义引擎尚未集成,详见 PlayerEngineFactory.kt 注释。")
    }
}

object PlayerEngineRegistry {
    fun factory(type: PlayerEngineType): PlayerEngineFactory = when (type) {
        PlayerEngineType.EXOPLAYER -> ExoPlayerEngineFactory
        PlayerEngineType.IJK -> IjkPlayerEngineFactory
        PlayerEngineType.CUSTOM -> CustomPlayerEngineFactory
    }

    fun all(): List<PlayerEngineType> = PlayerEngineType.entries
}
