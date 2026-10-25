package com.swiftbrowser.engine

import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.InputStream

/**
 * Small downloads for Swift's own use (news feeds, picture previews), through the engine
 * (GeckoWebExecutor): it has Firefox's up-to-date HTTPS and certificates, which Android 7's own
 * connections lack for many sites today. Without cookies. Blocking: call off the main thread.
 */
object Net {
    const val USER_AGENT = "Mozilla/5.0 (Android 7.1.2; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0"

    /** What came back: the address after redirects, its type and the bytes. */
    class Result(val url: String, val type: String, val bytes: ByteArray)

    /** [url]'s content, at most [maxBytes], or null (no connection, an error, too big, too slow). */
    fun get(url: String, maxBytes: Int, accept: String = "*/*", timeoutMs: Long = 15_000): Result? = try {
        val request = WebRequest.Builder(url)
            .method("GET")
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .build()
        val response = Engine.executor.fetch(request, GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS).poll(timeoutMs)
        val body = response?.body
        if (response == null || body == null || response.statusCode !in 200..299) {
            body?.close()
            null
        } else {
            val bytes = body.use { read(it, maxBytes) }
            bytes?.let { Result(response.uri, response.headers["Content-Type"] ?: "", it) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun read(input: InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }
}
