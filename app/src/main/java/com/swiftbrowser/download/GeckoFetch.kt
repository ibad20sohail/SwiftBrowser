package com.swiftbrowser.download

import android.os.Handler
import android.os.HandlerThread
import com.swiftbrowser.engine.Engine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException

/**
 * 3.0: waiting for the engine's answer to a request (GeckoWebExecutor) in a way a stop can cut
 * short. GeckoResult.poll() waits through interrupts, so Pause or Cancel used to wait up to 45
 * seconds for a download's request to come back (and a new run of the download started meanwhile).
 * The engine can't take a request back, so the answer to one let go of is closed the moment it
 * comes, which ends its transfer.
 */
object GeckoFetch {
    /** Where the engine's answers are handed over (each callback only passes one on). */
    private val callbacks by lazy { Handler(HandlerThread("gecko-fetch").apply { start() }.looper) }

    /** For downloads (coroutines): suspends until the answer; a cancel lets the request go. */
    suspend fun fetch(request: WebRequest, flags: Int, timeoutMs: Long): WebResponse =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<WebResponse> { cont ->
                val waiting = AtomicReference<WebResponse?>()
                val gone = AtomicBoolean(false)
                cont.invokeOnCancellation {
                    gone.set(true)
                    waiting.getAndSet(null)?.let { close(it) }
                }
                start(request, flags).accept({ r ->
                    if (r == null) {
                        cont.resumeWithException(IOException("No response"))
                        return@accept
                    }
                    waiting.set(r)
                    if (gone.get()) waiting.getAndSet(null)?.let { close(it) } else cont.resume(r) { _, value, _ -> close(value) }
                }, { e -> cont.resumeWithException(asIo(e)) })
            }
        } ?: throw SocketTimeoutException("No answer from the site")

    /**
     * For the player's loading threads (Media3 loads, which Media3 stops by interrupting them):
     * blocks until the answer, and gives up at once when interrupted.
     */
    fun fetchBlocking(request: WebRequest, flags: Int, timeoutMs: Long): WebResponse {
        val done = CountDownLatch(1)
        val got = AtomicReference<Any?>()
        val gone = AtomicBoolean(false)
        start(request, flags).accept({ r ->
            got.set(r ?: IOException("No response"))
            done.countDown()
            if (gone.get()) (got.getAndSet(null) as? WebResponse)?.let { close(it) }
        }, { e ->
            got.set(asIo(e))
            done.countDown()
        })
        val answered = try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            letGo(got, gone)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Stopped")
        }
        if (!answered) {
            letGo(got, gone)
            throw SocketTimeoutException("No answer from the site")
        }
        return when (val v = got.getAndSet(null)) {
            is WebResponse -> v
            is IOException -> throw v
            else -> throw IOException("No response")
        }
    }

    private fun letGo(got: AtomicReference<Any?>, gone: AtomicBoolean) {
        gone.set(true)
        (got.getAndSet(null) as? WebResponse)?.let { close(it) }
    }

    private fun start(request: WebRequest, flags: Int) = Engine.executor.fetch(request, flags).withHandler(callbacks)

    private fun asIo(e: Throwable?): IOException = e as? IOException ?: IOException("Network error: ${e?.message ?: e?.javaClass?.simpleName ?: "unknown"}", e)

    /** Ends [r]'s transfer (the engine stops fetching its body). */
    fun close(r: WebResponse) {
        runCatching { r.body?.close() }
    }
}
