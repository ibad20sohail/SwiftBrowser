package com.swiftbrowser.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.swiftbrowser.engine.DataSaver
import java.util.concurrent.Executors

/**
 * Small pictures for sheets (the picture a long press was on) and news rows: loaded in the background, at most
 * 1.5 MB and a few seconds, shrunk to about [size] pixels. Nothing loads on mobile data with Data
 * saver on; when it can't load, the sheet keeps its plain icon.
 */
object PreviewLoader {
    private const val MAX_BYTES = 1_500_000
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Recent pictures (about 4 MB), so scrolling back or reopening doesn't load them again. */
    private val cache = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String, size: Int): Bitmap? = cache.get("$size:$url")

    /** Android is short of memory: the kept pictures go (they load again when needed). */
    fun trim() = cache.evictAll()

    fun load(activity: Activity, url: String, size: Int, onLoaded: (Bitmap) -> Unit) {
        cached(url, size)?.let {
            onLoaded(it)
            return
        }
        if (DataSaver.active && !url.startsWith("data:")) return
        io.execute {
            val bytes = try {
                read(url)
            } catch (_: Exception) {
                null
            } ?: return@execute
            val bmp = decode(bytes, size) ?: return@execute
            cache.put("$size:$url", bmp)
            main.post { if (!activity.isFinishing && !activity.isDestroyed) onLoaded(bmp) }
        }
    }

    private fun read(url: String): ByteArray? {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).contains(";base64")) return null
            return Base64.decode(url.substring(comma + 1), Base64.DEFAULT).takeIf { it.size <= MAX_BYTES }
        }
        if (!url.startsWith("http")) return null
        // Through the engine: Android 7's own HTTPS can't reach many sites (Net).
        return com.swiftbrowser.engine.Net.get(url, MAX_BYTES, "image/*", 5000)?.bytes
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
