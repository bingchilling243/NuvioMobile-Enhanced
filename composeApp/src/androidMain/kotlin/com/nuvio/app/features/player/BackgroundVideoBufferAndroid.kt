@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object BackgroundVideoBufferAndroid {
    private const val CACHE_DIRECTORY = "vod_buffer_cache"
    private var cache: SimpleCache? = null
    private val activeUrls = ConcurrentHashMap.newKeySet<String>()
    private val workers = ConcurrentHashMap<String, Thread>()

    @Synchronized
    private fun getCache(context: Context): SimpleCache =
        cache ?: run {
            val directory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(context.applicationContext))
        }.also { cache = it }

    fun startIfRequested(context: Context, url: String, headers: Map<String, String>, streamType: String?) {
        if (!isProgressivePlaybackSource(url, emptyMap(), streamType)) {
            BackgroundVideoBufferRequests.clear(url)
            return
        }
        BackgroundVideoBufferRequests.peek(url)?.let { start(context, url, it.headers.ifEmpty { headers }) }
    }

    fun isBufferedOrBuffering(context: Context, url: String): Boolean {
        if (url in activeUrls) return true
        val cache = getCache(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return length != C.LENGTH_UNSET && length >= 0L && cache.isCached(url, 0L, length)
    }

    fun cache(context: Context): SimpleCache = getCache(context)

    private fun start(context: Context, url: String, headers: Map<String, String>) {
        if (url in activeUrls) return
        val cache = getCache(context)
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        if (contentLength != C.LENGTH_UNSET && contentLength >= 0L && cache.isCached(url, 0L, contentLength)) {
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
                val dataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .createDataSourceForDownloading()
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setKey(url)
                    .build()
                CacheWriter(dataSource, dataSpec, null, null).cache()
                InAppLogger.info("Player/Buffer", "full video buffer completed url=${{InAppLogger.redactUrl(url)}")
            } catch (error: Throwable) {
                activeUrls.remove(url)
                InAppLogger.warn("Player/Buffer", "full video buffer failed url=${{InAppLogger.redactUrl(url)} error=${{error.message}")
            } finally {
                workers.remove(url)
            }
        }, "nuvio-full-video-buffer").apply { isDaemon = true }
        workers[url] = worker
        worker.start()
    }
}
