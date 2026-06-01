package com.abei.test.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.engineDataStore by preferencesDataStore(name = "engine_prefs")

/**
 * 引擎/远端配置:
 *  - [engineType] —— 当前选用的播放器内核名称(字符串 = PlayerEngineType.name)
 *  - [serverBaseUrl] —— 服务器流播地址前缀,后续做 url 列表/远端目录浏览时拼接用
 *
 * 故意做成"读到啥就当啥",反序列化失败时由上层 fallback 到默认值。
 * 避免在 :core 直接依赖 :media 的 PlayerEngineType,保持 core 不引入 media3 这堆 jar。
 */
class EnginePrefs(context: Context) {

    private val ds = context.applicationContext.engineDataStore

    val engineType: Flow<String?> = ds.data.map { it[ENGINE_TYPE_KEY] }
    val serverBaseUrl: Flow<String> = ds.data.map { it[SERVER_BASE_URL_KEY].orEmpty() }

    suspend fun setEngineType(name: String) {
        ds.edit { it[ENGINE_TYPE_KEY] = name }
    }

    suspend fun setServerBaseUrl(url: String) {
        ds.edit { it[SERVER_BASE_URL_KEY] = url.trim() }
    }

    private companion object {
        val ENGINE_TYPE_KEY = stringPreferencesKey("engine_type")
        val SERVER_BASE_URL_KEY = stringPreferencesKey("server_base_url")
    }
}
