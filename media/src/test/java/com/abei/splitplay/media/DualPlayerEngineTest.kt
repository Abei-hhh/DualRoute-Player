package com.abei.splitplay.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DualPlayerEngine 委派 + state 合并的基础行为验证。
 * 不需要 Android / Media3,纯 JVM。
 */
class DualPlayerEngineTest {

    private fun newDual(scope: CoroutineScope): Triple<DualPlayerEngine, FakePlayerEngine, FakePlayerEngine> {
        val a = FakePlayerEngine()
        val v = FakePlayerEngine()
        return Triple(DualPlayerEngine(a, v, scope), a, v)
    }

    @Test
    fun play_broadcasts_to_both_channels() = runTest(UnconfinedTestDispatcher()) {
        val (dual, a, v) = newDual(this)
        dual.play()
        assertTrue("audio.play missing", "play" in a.invocations)
        assertTrue("video.play missing", "play" in v.invocations)
    }

    @Test
    fun setVideoSurface_only_hits_video_channel() = runTest(UnconfinedTestDispatcher()) {
        val (dual, a, v) = newDual(this)
        dual.setVideoSurface(null)
        assertEquals(0, a.invocations.count { it.startsWith("setVideoSurface") })
        assertEquals(1, v.invocations.count { it.startsWith("setVideoSurface") })
    }

    @Test
    fun setPreferredAudioDevice_only_hits_audio_channel() = runTest(UnconfinedTestDispatcher()) {
        val (dual, a, v) = newDual(this)
        dual.setPreferredAudioDevice(null)
        assertEquals(1, a.invocations.count { it.startsWith("setPreferredAudioDevice") })
        assertEquals(0, v.invocations.count { it.startsWith("setPreferredAudioDevice") })
    }

    @Test
    fun mergedState_isPlaying_is_OR_of_channels() = runTest(UnconfinedTestDispatcher()) {
        val (dual, a, v) = newDual(this)
        // 等 init 协程把初始状态合并好
        assertNotNull(dual.state.value)
        a.emit(PlaybackState(isPlaying = true, positionMs = 5_000, durationMs = 60_000))
        v.emit(PlaybackState(isPlaying = false, positionMs = 5_000, durationMs = 60_000))
        // audio 在播 → 合并视图 isPlaying=true
        assertTrue("OR isPlaying=true expected", dual.state.value.isPlaying)
        a.emit(PlaybackState(isPlaying = false, positionMs = 5_000, durationMs = 60_000))
        v.emit(PlaybackState(isPlaying = false, positionMs = 5_000, durationMs = 60_000))
        assertEquals(false, dual.state.value.isPlaying)
    }

    // setMedia(Uri, ...) 需要真实 Uri 实例,而 Android unit test 的 mock android.jar 里
    // Uri.parse 是 stub。要么走 Robolectric,要么用 instrumented 测试。当前先省略这一项,
    // 真要测可以 @RunWith(RobolectricTestRunner::class) 加上去。

    @Test
    fun release_releases_both_and_stops_syncer() = runTest(UnconfinedTestDispatcher()) {
        val (dual, a, v) = newDual(this)
        dual.release()
        assertTrue("audio.release missing", "release" in a.invocations)
        assertTrue("video.release missing", "release" in v.invocations)
    }
}
