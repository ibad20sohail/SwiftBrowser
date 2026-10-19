package com.swiftbrowser.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import com.swiftbrowser.engine.Engine
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Site icons for tabs, shortcuts and lists. A page reports its icon (helper extension); the icon is
 * fetched once, shrunk to 64 px and kept on disk per site. Never used for incognito tabs.
 */
object Favicons {
    private const val SIZE = 64
    private const val MAX_BYTES = 512 * 1024
    private const val REFRESH_MS = 7L * 24 * 3600 * 1000

    private lateinit var dir: File
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(96)
    private val absent = HashSet<String>()
    private val fetching = HashSet<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "favicons").apply { mkdirs() }
    }

    /** Icons are shared by all pages of a site, with or without "www." / "m.". */
    fun key(url: String?): String? = UrlUtils.host(url)?.removePrefix("www.")?.removePrefix("m.")

    private fun file(key: String) = File(dir, key.replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** The icon if it is in memory. */
    fun cached(url: String?): Bitmap? = key(url)?.let { cache.get(it) }

    /** Calls [onLoaded] on the main thread with the site's icon, or null if there is none yet. */
    fun load(url: String?, onLoaded: (Bitmap?) -> Unit) {
        val key = key(url)
        if (key == null) {
            onLoaded(null)
            return
        }
        cache.get(key)?.let {
            onLoaded(it)
            return
        }
        if (key in absent) {
            onLoaded(null)
            return
        }
        exec.execute {
            val f = file(key)
            val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            main.post {
                if (bmp != null) cache.put(key, bmp) else absent.add(key)
                onLoaded(bmp)
            }
        }
    }

    /** A page in a normal tab told us its icon address (or none: then /favicon.ico is tried). */
    fun onPageIcon(pageUrl: String, iconUrl: String?) {
        if (!UrlUtils.isHttp(pageUrl)) return
        val key = key(pageUrl) ?: return
        if (!fetching.add(key)) return
        val origin = pageUrl.substringBefore("://") + "://" + (UrlUtils.host(pageUrl) ?: return)
        val candidates = listOfNotNull(
            iconUrl?.takeIf { UrlUtils.isHttp(it) || it.startsWith("data:image/") },
            "$origin/favicon.ico",
        ).distinct()
        exec.execute {
            var bmp: Bitmap? = null
            try {
                val f = file(key)
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return@execute
                for (c in candidates) {
                    bmp = fetch(c)
                    if (bmp != null) break
                }
                bmp?.let { b -> FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            } catch (e: Exception) {
                L.d("No icon for $key: ${e.message}")
            } finally {
                val result = bmp
                main.post {
                    fetching.remove(key)
                    if (result != null) {
                        cache.put(key, result)
                        absent.remove(key)
                        listeners.forEach { it(key) }
                    }
                }
            }
        }
    }

    /** Blocking. */
    private fun fetch(url: String): Bitmap? {
        val bytes = if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
            Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
        } else {
            val request = WebRequest.Builder(url).addHeader("Accept", "image/*").build()
            val response = Engine.executor.fetch(request, GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS).poll(10_000)
                ?: return null
            val body = response.body ?: return null
            if (response.statusCode !in 200..299) {
                body.close()
                return null
            }
            body.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // Tiny icons (16 px) look better scaled up than left tiny.
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        Canvas(out).drawBitmap(src, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        if (src !== out) src.recycle()
        return out
    }

    /** Removes all stored icons (when clearing browsing data). */
    fun clear() {
        cache.evictAll()
        absent.clear()
        exec.execute { dir.listFiles()?.forEach { it.delete() } }
    }
}
