package com.swiftbrowser.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import com.swiftbrowser.download.GeckoFetch
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * 3.0: website videos played in Swift's player (and an HLS stream's playlists, keys and parts) are
 * fetched by the engine, as the page itself is: Firefox's up-to-date HTTPS and certificates (Android
 * 7's own connections fail for many sites, see engine.Net), the site's cookies, and an incognito
 * tab's private session for its videos. A Media3 DataSource over GeckoWebExecutor, used for http(s)
 * links; files on the phone and other apps' links are still read by Media3's own.
 */
@OptIn(UnstableApi::class)
class GeckoDataSource private constructor(
    /** The page's headers: Referer, Cookie, User-Agent, Origin. */
    private val headers: Map<String, String>,
    /** From an incognito tab: its private cookies, nothing written to the normal profile. */
    private val private: Boolean,
) : BaseDataSource(true) {
    class Factory(private val headers: Map<String, String>, private val private: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = GeckoDataSource(headers, private)
    }

    private var spec: DataSpec? = null
    private var body: InputStream? = null
    private var uri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    /** Bytes left to read, or C.LENGTH_UNSET when the site didn't say. */
    private var left = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val response = try {
            GeckoFetch.fetchBlocking(request(dataSpec), if (private) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE, CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        uri = runCatching { Uri.parse(response.uri) }.getOrDefault(dataSpec.uri)
        responseHeaders = response.headers.mapValues { listOf(it.value) }
        val status = response.statusCode
        val contentRange = header(response, "Content-Range")
        if (status !in 200..299) {
            // Asked for the file from its very end: nothing is left (as Media3's own HTTP source does).
            if (status == 416 && dataSpec.position == HttpUtil.getDocumentSize(contentRange)) {
                GeckoFetch.close(response)
                opened = true
                left = 0
                transferStarted(dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
            }
            // The error page itself isn't read: a slow site would hold the player up for it.
            GeckoFetch.close(response)
            throw HttpDataSource.InvalidResponseCodeException(status, null, null, responseHeaders, dataSpec, ByteArray(0))
        }
        // A site that ignored the range sends the file from its start; one that sent a part from
        // before the place asked for sends some bytes too many: those are skipped.
        val from = if (status == 206) contentRange?.let { rangeStart(it) } ?: dataSpec.position else 0L
        val skip = (dataSpec.position - from).coerceAtLeast(0)
        val encoded = header(response, "Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } == true
        left = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            // The engine unpacks compressed answers: their Content-Length isn't what is read.
            encoded -> C.LENGTH_UNSET.toLong()
            else -> HttpUtil.getContentLength(header(response, "Content-Length"), contentRange).let { n ->
                if (n == C.LENGTH_UNSET.toLong()) n else (n - skip).coerceAtLeast(0)
            }
        }
        val input = response.body ?: run {
            GeckoFetch.close(response)
            throw HttpDataSource.HttpDataSourceException("The site sent nothing", dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        response.setReadTimeoutMillis(READ_TIMEOUT_MS)
        body = input
        opened = true
        transferStarted(dataSpec)
        if (skip > 0) skipFully(input, skip, dataSpec)
        return left
    }

    private fun request(dataSpec: DataSpec): WebRequest {
        val method = when (dataSpec.httpMethod) {
            DataSpec.HTTP_METHOD_HEAD -> "HEAD"
            DataSpec.HTTP_METHOD_POST -> "POST"
            else -> "GET"
        }
        // Video data isn't kept in the engine's cache (a film would push the pages out of it).
        val b = WebRequest.Builder(dataSpec.uri.toString()).method(method).cacheMode(WebRequest.CACHE_MODE_NO_STORE)
        for ((k, v) in headers + dataSpec.httpRequestHeaders) {
            when (k.lowercase(Locale.ROOT)) {
                "referer" -> b.referrer(v)
                // The engine sets these itself.
                "range", "host", "content-length", "connection", "accept-encoding" -> Unit
                else -> b.header(k, v)
            }
        }
        HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)?.let { b.header("Range", it) }
        if (method == "POST") dataSpec.httpBody?.let { bytes -> b.body(ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }) }
        return b.build()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val input = body ?: return C.RESULT_END_OF_INPUT
        val want = if (left == C.LENGTH_UNSET.toLong()) length else minOf(left, length.toLong()).toInt()
        val n = try {
            input.read(buffer, offset, want)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, spec ?: DataSpec(Uri.EMPTY), HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (left != C.LENGTH_UNSET.toLong()) left -= n
        bytesTransferred(n)
        return n
    }

    private fun skipFully(input: InputStream, count: Long, dataSpec: DataSpec) {
        val buf = ByteArray(16 * 1024)
        var rest = count
        while (rest > 0) {
            if (Thread.currentThread().isInterrupted) throw HttpDataSource.HttpDataSourceException(java.io.InterruptedIOException(), dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            val n = try {
                input.read(buf, 0, minOf(buf.size.toLong(), rest).toInt())
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }
            if (n < 0) throw HttpDataSource.HttpDataSourceException("The site's answer ended early", dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            rest -= n
            bytesTransferred(n)
        }
    }

    override fun getUri(): Uri? = if (opened || body != null) uri else null

    override fun getResponseHeaders(): Map<String, List<String>> = if (opened) responseHeaders else emptyMap()

    override fun close() {
        try {
            body?.close()
        } catch (_: IOException) {
        } finally {
            body = null
            if (opened) {
                opened = false
                transferEnded()
            }
            spec = null
        }
    }

    private fun header(r: WebResponse, name: String): String? =
        r.headers[name] ?: r.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val READ_TIMEOUT_MS = 20_000L

        private val RANGE_START = Regex("bytes\\s+(\\d+)-", RegexOption.IGNORE_CASE)

        /** "bytes 100-199/1000" → 100. */
        private fun rangeStart(contentRange: String): Long? = RANGE_START.find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
    }
}
