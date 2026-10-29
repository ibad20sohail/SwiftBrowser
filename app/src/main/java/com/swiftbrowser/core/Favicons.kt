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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Site icons for tabs, shortcuts and lists. A page reports its icon (helper extension); the icon is
 * fetched once, shrunk to 64 px and kept on disk per site. Never used for incognito tabs.
 */
object Favicons {
    private const val SIZE = 64
    private const val MAX_BYTES = 512 * 1024
    private const val REFRESH_MS = 7L * 24 * 3600 * 1000

    /** A site that had no icon to give is asked again after a day (2.6). */
    private const val RETRY_MS = 24L * 3600 * 1000

    /** Icons kept in memory when Swift goes out of sight: the last ones used (about what one screen shows). */
    private const val KEEP_ON_TRIM = 24

    private lateinit var dir: File
    private val exec = Background.single("favicons")
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(96)
    private val absent = HashSet<String>()
    private val fetching = HashSet<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val unsafe = Regex("[^a-z0-9.-]")

    /**
     * Sites with no icon to be had lately (2.6), with when and which addresses were tried (as hash
     * codes): not asked again on every visit, only after [RETRY_MS] or when a page names another
     * icon. Also on disk, as "<site>.none" beside the icons, so it holds after Swift restarts.
     */
    private class Miss(val time: Long, val tried: Set<Int>) {
        fun covers(addresses: Set<Int>) = System.currentTimeMillis() - time < RETRY_MS && tried.containsAll(addresses)
    }

    /** Used on the main thread, and cleared from the one clearing browsing data. */
    private val missed = ConcurrentHashMap<String, Miss>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "favicons").apply { mkdirs() }
    }

    /** Icons are shared by all pages of a site, with or without "www." / "m.". */
    fun key(url: String?): String? = UrlUtils.host(url)?.removePrefix("www.")?.removePrefix("m.")

    private fun file(key: String) = File(dir, key.replace(unsafe, "_") + ".png")

    private fun missFile(key: String) = File(dir, key.replace(unsafe, "_") + ".none")

    private fun readMiss(f: File): Miss? = try {
        if (f.exists()) Miss(f.lastModified(), f.readLines().mapNotNull { it.trim().toIntOrNull() }.toSet()) else null
    } catch (_: Exception) {
        null
    }

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** The icon if it is in memory. */
    fun cached(url: String?): Bitmap? = key(url)?.let { cache.get(it) }

    /**
     * Swift went out of sight or Android is short of memory (2.6): icons leave memory (they load again
     * from disk), but for the last few used, as the address bar and suggestions only show icons that
     * are in memory.
     */
    fun trim() = cache.trimToSize(KEEP_ON_TRIM)

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
        val origin = pageUrl.substringBefore("://") + "://" + (UrlUtils.host(pageUrl) ?: return)
        val candidates = listOfNotNull(
            iconUrl?.takeIf { UrlUtils.isHttp(it) || it.startsWith("data:image/") },
            "$origin/favicon.ico",
        ).distinct()
        val addresses = candidates.map { it.hashCode() }.toSet()
        if (missed[key]?.covers(addresses) == true) return
        if (!fetching.add(key)) return
        exec.execute {
            var bmp: Bitmap? = null
            var miss: Miss? = null
            try {
                val f = file(key)
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return@execute
                // Swift restarted since the site had none: its mark on disk says so.
                val marked = missFile(key)
                readMiss(marked)?.takeIf { it.covers(addresses) }?.let {
                    miss = it
                    return@execute
                }
                for (c in candidates) {
                    bmp = fetch(c)
                    if (bmp != null) break
                }
                val b = bmp
                if (b != null) {
                    FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    marked.delete()
                } else {
                    // The site answered, with nothing usable. A network error or time-out (an
                    // exception) isn't remembered: the next visit tries again.
                    miss = Miss(System.currentTimeMillis(), addresses)
                    marked.writeText(addresses.joinToString("\n"))
                }
            } catch (e: Exception) {
                L.d("No icon for $key: ${e.message}")
            } finally {
                val result = bmp
                val none = miss
                main.post {
                    fetching.remove(key)
                    if (result != null) {
                        missed.remove(key)
                        cache.put(key, result)
                        absent.remove(key)
                        listeners.forEach { it(key) }
                    } else if (none != null) {
                        missed[key] = none
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

    /**
     * Removes the icons of [hosts] and those fetched since [since] (clearing browsing data for a
     * time, 2.6): together they would still tell which sites were visited. The marks of sites
     * without an icon go the same way.
     */
    fun clearSince(since: Long, hosts: Set<String>) {
        cache.evictAll()
        absent.clear()
        missed.clear()
        val keys = hosts.map { it.removePrefix("www.").removePrefix("m.") }.toSet()
        exec.execute {
            dir.listFiles()?.forEach { f -> if (f.lastModified() >= since || f.name.substringBeforeLast('.') in keys) f.delete() }
        }
    }

    /** Removes all stored icons, and the marks of sites without one (when clearing browsing data). */
    fun clear() {
        cache.evictAll()
        absent.clear()
        missed.clear()
        exec.execute { dir.listFiles()?.forEach { it.delete() } }
    }
}
