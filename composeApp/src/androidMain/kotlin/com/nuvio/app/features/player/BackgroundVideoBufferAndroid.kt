@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class BackgroundBufferProgress(
    val state: State,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val fraction: Float?,
    val speedBytesPerSecond: Long,
) {
    enum class State {
        Idle,
        Buffering,
        Complete,
        Failed,
    }
}

internal object BackgroundVideoBufferAndroid {
    private const val CACHE_DIRECTORY = "vod_buffer_cache"
    private var cache: SimpleCache? = null
    private var applicationContext: Context? = null
    private val activeUrls = ConcurrentHashMap.newKeySet<String>()
    private val completedUrls = ConcurrentHashMap.newKeySet<String>()
    private val failedUrls = ConcurrentHashMap.newKeySet<String>()
    private val workers = ConcurrentHashMap<String, Thread>()
    private val progressSamples = ConcurrentHashMap<String, ProgressSample>()

    private data class ProgressSample(
        val timestampMs: Long,
        val downloadedBytes: Long,
    )

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

    /**
     * Starts a queued background buffer even when its URL is not the URL currently
     * attached to ExoPlayer. This is used by next-episode prebuffering.
     */
    fun startPending(context: Context) {
        val request = BackgroundVideoBufferRequests.peekAny() ?: return
        if (!isProgressivePlaybackSource(request.url, emptyMap(), request.streamType)) {
            BackgroundVideoBufferRequests.clearAny()
            return
        }
        start(context, request.url, request.headers)
        BackgroundVideoBufferRequests.clearAny()
    }

    fun isBufferedOrBuffering(context: Context, url: String): Boolean {
        if (url in activeUrls || url in completedUrls) return true
        val cache = getCache(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return length != C.LENGTH_UNSET.toLong() && length >= 0L && cache.isCached(url, 0L, length)
    }

    fun cache(context: Context): SimpleCache = getCache(context)

    fun progress(url: String): BackgroundBufferProgress {
        val context = applicationContext
        if (context == null) {
            return BackgroundBufferProgress(
                state = BackgroundBufferProgress.State.Idle,
                downloadedBytes = 0L,
                totalBytes = null,
                fraction = null,
                speedBytesPerSecond = 0L,
            )
        }
        val cache = getCache(context)
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(url))
            .takeIf { it != C.LENGTH_UNSET.toLong() && it > 0L }
        val downloaded = cache.getCachedSpans(url).sumOf { it.length }.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val previous = progressSamples.put(url, ProgressSample(now, downloaded))
        val speed = previous?.let {
            val elapsed = now - it.timestampMs
            if (elapsed > 0L && downloaded >= it.downloadedBytes) {
                ((downloaded - it.downloadedBytes) * 1000L / elapsed).coerceAtLeast(0L)
            } else 0L
        } ?: 0L
        val isComplete = total != null && downloaded >= total && cache.isCached(url, 0L, total)
        val state = when {
            isComplete -> BackgroundBufferProgress.State.Complete
            url in activeUrls -> BackgroundBufferProgress.State.Buffering
            url in failedUrls -> BackgroundBufferProgress.State.Failed
            downloaded > 0L -> BackgroundBufferProgress.State.Idle
            else -> BackgroundBufferProgress.State.Idle
        }
        return BackgroundBufferProgress(
            state = state,
            downloadedBytes = downloaded,
            totalBytes = total,
            fraction = total?.let { (downloaded.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) },
            speedBytesPerSecond = speed,
        )
    }

    fun fullBufferProgress(url: String): Float? = progress(url).fraction


    private fun downloadInRanges(
        url: String,
        cacheDataSourceFactory: CacheDataSource.Factory,
    ) {
        val chunkBytes = 64L * 1024L * 1024L
        var position = 0L
        val buffer = ByteArray(64 * 1024)

        while (true) {
            val dataSource = cacheDataSourceFactory.createDataSource()
            val dataSpec = DataSpec(
                Uri.parse(url),
                position,
                chunkBytes,
            )
            val available = try {
                dataSource.open(dataSpec)
            } catch (error: Throwable) {
                dataSource.close()
                if (position > 0L) break
                throw error
            }

            var downloadedThisRange = 0L
            try {
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read <= 0) continue
                    downloadedThisRange += read
                    position += read
                }
            } finally {
                dataSource.close()
            }

            if (downloadedThisRange <= 0L) break

            // A short range means the server reached the end of the resource.
            if (available != C.LENGTH_UNSET.toLong() && downloadedThisRange < chunkBytes) break
            if (available == C.LENGTH_UNSET.toLong() && downloadedThisRange < chunkBytes) break
        }
    }

    private fun start(context: Context, url: String, headers: Map<String, String>) {
        if (url in activeUrls) return
        val cache = getCache(context)
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        if (contentLength != C.LENGTH_UNSET.toLong() && contentLength >= 0L && cache.isCached(url, 0L, contentLength)) {
            completedUrls += url
            failedUrls.remove(url)
            activeUrls.remove(url)
            return
        }
        if (workers.containsKey(url)) return
        failedUrls.remove(url)
        activeUrls += url
        progressSamples.remove(url)
        val worker = Thread({
            try {
                val upstreamFactory = PlayerPlaybackNetworking.createDataSourceFactory(
                    context.applicationContext, headers, useLongReadTimeout = true
                )
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                downloadInRanges(
                    url = url,
                    cacheDataSourceFactory = cacheDataSourceFactory,
                )
                completedUrls += url
                Log.i("Player/Buffer", "full video buffer completed url=$url")
            } catch (error: Throwable) {
                failedUrls += url
                Log.w("Player/Buffer", "full video buffer failed url=$url error=${error.message}")
            } finally {
                activeUrls.remove(url)
                workers.remove(url)
                progressSamples.remove(url)
            }
        }, "nuvio-full-video-buffer").apply { isDaemon = true }
        workers[url] = worker
        worker.start()
    }
}
