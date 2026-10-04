package com.nuvio.app.features.player

object BackgroundVideoBufferRequests {
    data class Request(val url: String, val headers: Map<String, String>, val streamType: String?)
    @Volatile private var pending: Request? = null
    fun request(url: String, headers: Map<String, String>, streamType: String?) {
        if (url.isNotBlank()) pending = Request(url, headers, streamType)
    }
    fun peek(url: String): Request? = pending?.takeIf { it.url == url }
    fun clear(url: String) { if (pending?.url == url) pending = null }
}
