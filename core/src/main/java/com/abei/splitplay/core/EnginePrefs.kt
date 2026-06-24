package com.abei.splitplay.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.engineDataStore by preferencesDataStore(name = "engine_prefs")

/**
 * 引擎/远端配置 + 设备路由偏好:
 *  - [engineType] —— 当前选用的播放器内核名称(字符串 = PlayerEngineType.name)
 *  - [serverBaseUrl] —— 服务器流播地址前缀,后续做 url 列表/远端目录浏览时拼接用
 *  - [audioDeviceId] —— 用户最近选中的音频输出 [android.media.AudioDeviceInfo.id]。
 *    `null` 表示"系统默认"。AudioDeviceInfo.id 跨进程不稳定,所以读出来后必须
 *    跟当前 [com.abei.splitplay.media.AudioDeviceRepository.outputs] 做 reconcile。
 *
 * 故意做成"读到啥就当啥",反序列化失败时由上层 fallback 到默认值。
 * 避免在 :core 直接依赖 :media 的 PlayerEngineType,保持 core 不引入 media3 这堆 jar。
 */
class EnginePrefs(context: Context) {

    private val ds = context.applicationContext.engineDataStore

    val engineType: Flow<String?> = ds.data.map { it[ENGINE_TYPE_KEY] }
    val serverBaseUrl: Flow<String> = ds.data.map { it[SERVER_BASE_URL_KEY].orEmpty() }

    /**
     * 播放模式:`"SINGLE"`(单引擎,默认)/ `"SPLIT"`(音视频双 Player 分离)。
     * 字符串而非 enum 避免 :core 依赖 :media —— 解析由上层做,坏值兜底回 SINGLE。
     */
    val playbackMode: Flow<String?> = ds.data.map { it[PLAYBACK_MODE_KEY] }

    /**
     * 读出的 id 若为 [NO_DEVICE_SENTINEL] 或缺失,统一映射为 `null`(系统默认)。
     */
    val audioDeviceId: Flow<Int?> = ds.data.map { prefs ->
        prefs[AUDIO_DEVICE_ID_KEY]?.takeIf { it != NO_DEVICE_SENTINEL }
    }

    /**
     * 视频输出显示设备 id。`null` 表示走主屏(等价于"不投屏")。
     * 跟 [audioDeviceId] 一样跨进程不稳定,reconcile 由上层做。
     */
    val displayId: Flow<Int?> = ds.data.map { prefs ->
        prefs[DISPLAY_ID_KEY]?.takeIf { it != NO_DEVICE_SENTINEL }
    }

    suspend fun setEngineType(name: String) {
        ds.edit { it[ENGINE_TYPE_KEY] = name }
    }

    suspend fun setServerBaseUrl(url: String) {
        ds.edit { it[SERVER_BASE_URL_KEY] = url.trim() }
    }

    suspend fun setAudioDeviceId(id: Int?) {
        ds.edit { it[AUDIO_DEVICE_ID_KEY] = id ?: NO_DEVICE_SENTINEL }
    }

    suspend fun setDisplayId(id: Int?) {
        ds.edit { it[DISPLAY_ID_KEY] = id ?: NO_DEVICE_SENTINEL }
    }

    suspend fun setPlaybackMode(mode: String) {
        ds.edit { it[PLAYBACK_MODE_KEY] = mode }
    }

    private companion object {
        val ENGINE_TYPE_KEY = stringPreferencesKey("engine_type")
        val SERVER_BASE_URL_KEY = stringPreferencesKey("server_base_url")
        val AUDIO_DEVICE_ID_KEY = intPreferencesKey("audio_device_id")
        val DISPLAY_ID_KEY = intPreferencesKey("display_id")
        val PLAYBACK_MODE_KEY = stringPreferencesKey("playback_mode")

        /** `-1` 一直被各 OEM 当作"未指定/无效"使用,这里也用它当 null 哨兵。 */
        const val NO_DEVICE_SENTINEL = -1
    }
}
