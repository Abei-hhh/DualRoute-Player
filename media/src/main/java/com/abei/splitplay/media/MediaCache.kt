package com.abei.splitplay.media

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File

/**
 * 流播磁盘缓存(边下边播)。
 *
 * 为什么不需要本地代理(localhost HTTP server)：
 * - 老一辈方案(MediaPlayer + AndroidVideoCache 之类)起一个 127.0.0.1 的 HTTP 服务,
 *   原因是 MediaPlayer 只能吃 URL,塞不进自定义的 IO 层。
 * - ExoPlayer 从设计上就是 pluggable DataSource —— 播放器读字节走 DataSource 接口,
 *   而 [CacheDataSource] 是这个接口的一个 wrapper：命中缓存就读本地,没命中就走
 *   [upstream] 拉网络,顺手把字节写到 [SimpleCache] 里。整条管线都在 in-process,
 *   没有额外的 socket、端口、CORS、Range 重写,带宽和延迟都比 loopback 更省。
 *
 * 用法:在 [SinglePlayerEngine] 的 ExoPlayer.Builder 里
 * `setMediaSourceFactory(MediaCache.mediaSourceFactory(context))` 即可。
 * 本地 content:// 和 file:// URI 不会进缓存,只在 http(s):// 时落盘。
 */
object MediaCache {

    private const val CACHE_DIR_NAME = "media"

    /** 500 MB。后续可改成读 DataStore 让用户在设置里调。 */
    private const val MAX_BYTES = 500L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    fun mediaSourceFactory(context: Context): MediaSource.Factory {
        val app = context.applicationContext
        val simpleCache = getOrCreateCache(app)

        // upstream 关键点:不能只用 DefaultHttpDataSource —— 它只认 http(s),
        // 拿到 content:// / file:// / asset:/// 会直接 fail,本地视频就播不了。
        // DefaultDataSource 会按 URI scheme 路由到正确的子 DataSource
        // (ContentDataSource / FileDataSource / AssetDataSource / ...),
        // http(s) 才落到我们传入的 HTTP factory 上。
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("AbeiPlayer/1.0")
        val upstream = DefaultDataSource.Factory(app, http)

        val cacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            // 缓存写出错(磁盘满、IO 异常)不影响播放,继续从 upstream 读。
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultMediaSourceFactory(app).setDataSourceFactory(cacheFactory)
    }

    /**
     * SimpleCache 对同一目录只能存在一个实例(内部有进程内文件锁),所以走单例。
     * 多次构造同目录会抛 [IllegalStateException]。
     */
    private fun getOrCreateCache(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val dir = File(context.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_BYTES)
            val db = StandaloneDatabaseProvider(context)
            return SimpleCache(dir, evictor, db).also { cache = it }
        }
    }

    /** 进程退出/低内存压力下可调用释放。当前 MainActivity 是单实例,长期持有即可。 */
    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
