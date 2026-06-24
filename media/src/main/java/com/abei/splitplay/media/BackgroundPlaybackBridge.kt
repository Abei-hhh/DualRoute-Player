package com.abei.splitplay.media

import androidx.media3.common.Player

/**
 * 进程级桥接对象:把当前活动的 Media3 [Player] 引用暴露给后台 Service(`:app` 层)。
 *
 * 为什么不让 :app 直接持 ViewModel 拿 player:
 *  - ViewModel 是 NavBackStackEntry-scope,Activity 不直接持有
 *  - :media 已经依赖 `androidx.media3.common`,在这里挂引用是最浅的桥
 *  - 接口层 [PlayerEngine] 仍然 Media3-free,IjkPlayer / 自实现内核不破
 *
 * 谁来 set:
 *  - [SinglePlayerEngine] 在 init 时把自己内部的 `exo` 注册进来,release 时清掉
 *  - SPLIT 模式的 [DualPlayerEngine] 注册 audio 子引擎(后台播音乐 = 仅音频)
 *
 * 谁来 read:`:app/PlaybackBackgroundService` 在 onCreate 时取,绑成 MediaSession。
 *
 * 多个 engine 同时存在时(SPLIT)同一时刻只有一个 audio 角色,所以单引用足够;
 * Activity 销毁前会被 release 清空。
 */
object BackgroundPlaybackBridge {

    @Volatile
    var audioPlayer: Player? = null
        private set

    /**
     * 标记当前持音频/全频的 Media3 Player。`null` 等于"没有可用后台播放"。
     * `:media` 内部使用,外部模块不直接 set。
     */
    internal fun attach(player: Player?) {
        audioPlayer = player
    }

    /** 跟 [attach] 配对的 release。如果当前不是 owner(player 引用不同),不操作。 */
    internal fun detach(player: Player) {
        if (audioPlayer === player) audioPlayer = null
    }
}
