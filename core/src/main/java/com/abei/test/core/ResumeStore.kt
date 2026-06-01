package com.abei.test.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.resumeDataStore by preferencesDataStore(name = "resume_positions")

/**
 * 续播位置存储：以视频 URI 作为 key,保存上次播放到的毫秒位置。
 * 简单 KV,无版本字段;后续要加最近播放列表/封面快照再换更结构化的存储。
 */
class ResumeStore(private val context: Context) {

    suspend fun load(key: String): Long {
        val prefKey = longPreferencesKey(key)
        return context.resumeDataStore.data.map { it[prefKey] ?: 0L }.first()
    }

    suspend fun save(key: String, positionMs: Long) {
        val prefKey = longPreferencesKey(key)
        context.resumeDataStore.edit { it[prefKey] = positionMs }
    }

    suspend fun clear(key: String) {
        val prefKey = longPreferencesKey(key)
        context.resumeDataStore.edit { it.remove(prefKey) }
    }
}
