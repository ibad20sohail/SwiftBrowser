package com.swiftbrowser.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.swiftbrowser.engine.DataSaver

/**
 * Small pictures for sheets (the picture a long press was on), news rows and cards: loaded in the
 * background, at most 1.5 MB and a few seconds, shrunk to just cover a box of [width] by [size]
 * pixels (a square of [size] unless said). Nothing loads on mobile data with Data saver on; when it
 * can't load, the sheet keeps its plain icon.
 */
object PreviewLoader {
    private const val MAX_BYTES = 1_500_000
    private val io = com.swiftbrowser.core.Background.pool("previews", 2)
    private val main = Handler(Looper.getMainLooper())

    /** Recent pictures (about 4 MB), so scrolling back or reopening doesn't load them again. */
    private val cache = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String, size: Int, width: Int = size): Bitmap? = cache.get(key(url, size, width))

    private fun key(url: String, size: Int, width: Int) = "${width}x$size:$url"

    /** Android is short of memory: the kept pictures go (they load again when needed). */
    fun trim() = cache.evictAll()

    fun load(activity: Activity, url: String, size: Int, width: Int = size, onLoaded: (Bitmap) -> Unit) {
        cached(url, size, width)?.let {
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
            val bmp = decode(bytes, width, size) ?: return@execute
            cache.put(key(url, size, width), bmp)
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

    /**
     * 3.0: decoded at the size it is shown. Halving alone (while both sides stay big enough) left a
     * 1200 × 675 picture for a 360-pixel card at full size, 1.6 MB (3.2 MB as PNG or WebP); the
     * decoder now scales it the rest of the way itself, into a bitmap of the final size (the bigger
     * one it decodes into is let go at once).
     */
    private fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val fit = Fit.of(bounds.outWidth, bounds.outHeight, width, height)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = fit.sample
            // Pictures without see-through parts in half the memory (2.6: photos; 3.0: PNG and WebP
            // too). Android decodes those that have see-through parts in full colour anyway.
            inPreferredConfig = Bitmap.Config.RGB_565
            if (fit.scaled) {
                // Scaled by inTargetDensity / inDensity while decoding.
                inScaled = true
                inDensity = fit.from
                inTargetDensity = fit.to
            }
        })?.also {
            // Shown at its own size, as before (the densities above were only the scale).
            it.density = Bitmap.DENSITY_NONE
        }
    }

    /**
     * How a picture of [w] × [h] is brought down to just cover a [boxW] × [boxH] box: halved [sample]
     * times while both sides stay big enough, then scaled from [from] to [to] pixels (never up).
     */
    internal class Fit(val sample: Int, val from: Int, val to: Int) {
        val scaled get() = to < from

        companion object {
            fun of(w: Int, h: Int, width: Int, height: Int): Fit {
                // A box of no size would halve for ever.
                val boxW = width.coerceAtLeast(1)
                val boxH = height.coerceAtLeast(1)
                var sample = 1
                while (w / (sample * 2) >= boxW && h / (sample * 2) >= boxH) sample *= 2
                val sw = w / sample
                val sh = h / sample
                // The side that has to cover its box exactly decides the scale.
                return if (sw.toLong() * boxH >= sh.toLong() * boxW) Fit(sample, sh, minOf(sh, boxH)) else Fit(sample, sw, minOf(sw, boxW))
            }
        }
    }
}
