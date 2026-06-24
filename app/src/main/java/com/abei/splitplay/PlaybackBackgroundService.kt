package com.abei.splitplay

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.abei.splitplay.media.BackgroundPlaybackBridge

/**
 * 后台音频播放服务。
 *
 * 设计:Player 实例由 Activity 持有(不是这里 own),Service 只是给系统挂一个 MediaSession +
 * 前台通知,让锁屏/通知栏出现媒体控制条。Activity 离开前台时 startForegroundService,
 * Activity 回前台时 stopService,音频持续播放、画面停在那里。
 *
 *  - 通知栏控件:Media3 [MediaSessionService] 自带,系统按 MediaSession 元数据自动渲染。
 *  - 用户从最近任务划走应用 → [onTaskRemoved] 主动 stopSelf,Activity 进程也会被回收。
 *  - 没有 player 引用(Activity 还没进过播放页)→ onCreate 直接 stopSelf,避免空通知。
 *
 * Manifest 已声明该 Service + `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`(API 34+)。
 */
@UnstableApi
class PlaybackBackgroundService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = BackgroundPlaybackBridge.audioPlayer
        if (player == null) {
            // Activity 都没起过播放页 / engine 已 release → 没必要起 Service
            stopSelf()
            return
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户在最近任务里划走 app —— 一并停止后台播放,符合大多数音视频 app 的行为预期。
        // 不主动 release player(Activity 的 ViewModel 会在 onCleared 时 release)。
        stopSelf()
    }

    override fun onDestroy() {
        // 只 release MediaSession,不 release player —— player 所有权属于 Activity 的 ViewModel,
        // 它 onCleared 时会 release。重复 release 会 NPE。
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

