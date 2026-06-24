package com.abei.splitplay.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 简版双 Player 时钟同步器。
 *
 * 把音频通道当主时钟,视频通道每 [TICK_MS] 校对一次:
 *  - |Δ| > [HARD_SEEK_MS]:`video.seekTo(audio.position)`,且 [HARD_SEEK_THROTTLE_MS] 节流
 *    一次 —— 不然慢存储会被疯狂 seek 抖出来
 *  - [SOFT_NUDGE_MS] < |Δ| ≤ [HARD_SEEK_MS]:微调 video 倍速 ±[NUDGE_FACTOR],把它慢慢拖回来
 *  - |Δ| ≤ [NUDGE_OFF_MS]:倍速复位为 audio 倍速,避免长期 ±1% 偏移
 *
 * 仅当两侧都 isPlaying 且时长非 0 才启用;一旦任一暂停就停同步,避免拉错的 seek。
 *
 * 调用方:[DualPlayerEngine] 在 init 里 `start()`,release 时 `stop()`。
 */
class PlaybackSyncer(
    private val audio: PlayerEngine,
    private val video: PlayerEngine,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var lastHardSeekAtMs: Long = 0L

    fun start() {
        if (job != null) return
        job = scope.launch {
            var virtualClock = 0L
            while (true) {
                delay(TICK_MS)
                virtualClock += TICK_MS
                tick(virtualClock)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun tick(virtualClock: Long) {
        val a = audio.state.value
        val v = video.state.value
        if (!a.isPlaying || !v.isPlaying) return
        if (a.durationMs <= 0 || v.durationMs <= 0) return
        val delta = v.positionMs - a.positionMs
        val absDelta = abs(delta)
        when {
            absDelta > HARD_SEEK_MS -> {
                if (virtualClock - lastHardSeekAtMs >= HARD_SEEK_THROTTLE_MS) {
                    video.seekTo(a.positionMs)
                    lastHardSeekAtMs = virtualClock
                }
            }
            absDelta > SOFT_NUDGE_MS -> {
                // video 跑得快(delta > 0)→ 减速;反之加速。
                val target = a.playbackSpeed * (1f + if (delta > 0) -NUDGE_FACTOR else NUDGE_FACTOR)
                if (abs(v.playbackSpeed - target) > NUDGE_EPSILON) {
                    video.setPlaybackSpeed(target)
                }
            }
            absDelta <= NUDGE_OFF_MS -> {
                if (abs(v.playbackSpeed - a.playbackSpeed) > NUDGE_EPSILON) {
                    video.setPlaybackSpeed(a.playbackSpeed)
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val HARD_SEEK_MS = 80L
        const val SOFT_NUDGE_MS = 25L
        const val NUDGE_OFF_MS = 10L
        const val HARD_SEEK_THROTTLE_MS = 1_000L
        const val NUDGE_FACTOR = 0.02f
        const val NUDGE_EPSILON = 0.005f
    }
}
