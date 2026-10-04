@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object BackgroundVideoBufferAndroid {
    private const val CACHE_DIRECTORY = "vod_buffer_cache"
    private var cache: SimpleCache? = null
    private var applicationContext: Context? = null
    private val activeUrls = ConcurrentHashMap.newKeySet<String>()
    private val workers = ConcurrentHashMap<String, Thread>()

    @Synchronized
    private fun getCache(context: Context): SimpleCache =
        cache ?: run {
            val directory = File(context.applicationContext.filesDir, CACHE_DIRECTORY).apply { mkdirs() }
            SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(context.applicationContext))
        }.also {
            cache = it
            applicationContext = context.applicationContext
        }

    fun startIfRequested(context: Context, url: String, headers: Map<String, String>, streamType: String?) {
        if (!isProgressivePlaybackSource(url, emptyMap(), streamType)) {
            BackgroundVideoBufferRequests.clear(url)
            return
        }
        BackgroundVideoBufferRequests.peek(url)?.let { request ->
            start(context, url, request.headers.ifEmpty { headers })
            BackgroundVideoBufferRequests.clear(url)
        }
    }

    fun isBufferedOrBuffering(context: Context, url: String): Boolean {
        if (url in activeUrls) return true
        val cache = getCache(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return length != C.LENGTH_UNSET.toLong() && length >= 0L && cache.isCached(url, 0L, length)
    }

    fun cache(context: Context): SimpleCache = getCache(context)

    fun fullBufferProgress(url: String): Float? {
        val context = applicationContext ?: return null
        val cache = getCache(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        if (length == C.LENGTH_UNSET.toLong() || length <= 0L) return null
        if (cache.isCached(url, 0L, length)) return 1f
        val cachedBytes = cache.getCachedSpans(url).sumOf { it.length }.coerceAtMost(length)
        return (cachedBytes.toDouble() / length.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun start(context: Context, url: String, headers: Map<String, String>) {
        if (url in activeUrls) return
        val cache = getCache(context)
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        if (contentLength != C.LENGTH_UNSET.toLong() && contentLength >= 0L && cache.isCached(url, 0L, contentLength)) {
            activeUrls += url
            return
        }
        if (workers.containsKey(url)) return
        activeUrls += url
        val worker = Thread({
            try {
                val upstreamFactory = PlayerPlaybackNetworking.createDataSourceFactory(
                    context.applicationContext, headers, useLongReadTimeout = true
                )
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                ProgressiveDownloader(
                    MediaItem.fromUri(url),
                    cacheDataSourceFactory,
                ).download(null)
                Log.i("Player/Buffer", "full video buffer completed url=$url")
            } catch (error: Throwable) {
                activeUrls.remove(url)
                Log.w("Player/Buffer", "full video buffer failed url=$url error=${error.message}")
            } finally {
                workers.remove(url)
            }
        }, "nuvio-full-video-buffer").apply { isDaemon = true }
        workers[url] = worker
        worker.start()
    }
}
