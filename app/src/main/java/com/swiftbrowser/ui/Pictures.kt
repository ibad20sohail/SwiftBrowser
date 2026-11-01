package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.swiftbrowser.core.L
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reading pictures for the picture viewer and its editor (2.9), from a file or from another app's
 * link (content://). Only what the screen needs is decoded: the whole picture at about the size it
 * is shown, and, zoomed in, just the part on screen in full detail. A camera photo decoded whole
 * takes 30 MB or more, too much to keep around on a 2 GB phone. Everything here blocks: it runs
 * off the main thread.
 */
object Pictures {
    /** The graphics chip draws at most this many pixels a side in one go. */
    private const val MAX_SIDE = 4096

    /** GIFs bigger than this stay still: Android keeps every frame of a playing GIF in memory. */
    const val MAX_GIF_BYTES = 8L * 1_048_576
    private const val MAX_GIF_PIXELS = 2048 * 2048

    /** Where a picture comes from: a file (in a folder to swipe through) or another app's link. */
    class Source private constructor(val file: File?, val uri: Uri?) {
        /** Its name in the caches. */
        val key: String get() = file?.path ?: uri.toString()

        fun open(ctx: Context): InputStream =
            if (file != null) FileInputStream(file) else ctx.contentResolver.openInputStream(uri!!) ?: throw IOException("The other app gave no picture")

        companion object {
            fun of(file: File) = Source(file, null)
            fun of(uri: Uri) = Source(null, uri)
        }
    }

    /** A picture's stored size and type, and which way up it is meant to be shown (EXIF). */
    class Info(val rawWidth: Int, val rawHeight: Int, val mime: String?, val orientation: Int) {
        /** Stored on its side (a photo taken with the phone upright): width and height swap when shown. */
        private val sideways: Boolean get() = orientation in 5..8
        val width: Int get() = if (sideways) rawHeight else rawWidth
        val height: Int get() = if (sideways) rawWidth else rawHeight

        /** Android can decode just a part of it (sharp zoom, editing big photos). */
        val hasParts: Boolean get() = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp"
    }

    /** [src]'s size, type and orientation, read without decoding it; null when Android can't show it. */
    fun info(ctx: Context, src: Source): Info? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (src.file != null) BitmapFactory.decodeFile(src.file.path, o) else src.open(ctx).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val orientation = if (o.outMimeType == "image/jpeg") {
            exif(ctx, src)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        } else {
            ExifInterface.ORIENTATION_NORMAL
        }
        return Info(o.outWidth, o.outHeight, o.outMimeType, orientation)
    }

    /** [src]'s camera details (EXIF, in JPEGs), or null. */
    fun exif(ctx: Context, src: Source): ExifInterface? = try {
        if (src.file != null) ExifInterface(src.file.path) else src.open(ctx).use { ExifInterface(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Stored pixels ([w] × [h]) → upright, as EXIF [orientation] says (turned or mirrored), then
     * [turns] quarter turns clockwise. The result starts at 0, 0.
     */
    fun turnMatrix(orientation: Int, turns: Int, w: Float, h: Float): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        val q = ((turns % 4) + 4) % 4
        if (q != 0) m.postRotate(90f * q)
        val r = RectF(0f, 0f, w, h)
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        return m
    }

    /** [bmp] as stored, turned upright (a new picture when it had to turn; the old one is freed). */
    fun upright(bmp: Bitmap, orientation: Int): Bitmap {
        if (orientation <= ExifInterface.ORIENTATION_NORMAL || orientation > ExifInterface.ORIENTATION_ROTATE_270) return bmp
        return try {
            val m = turnMatrix(orientation, 0, bmp.width.toFloat(), bmp.height.toFloat())
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
        } catch (_: OutOfMemoryError) {
            bmp
        }
    }

    /**
     * The whole picture upright, at about the size it is shown on a [boxW] × [boxH] screen: at least
     * that size, so it is sharp as it fits, and less than twice it (Android decodes quickly only by
     * halving). Null when it can't be read.
     */
    fun decodeFit(ctx: Context, src: Source, info: Info, boxW: Int, boxH: Int): Bitmap? {
        val fit = min(boxW.toFloat() / info.width, boxH.toFloat() / info.height)
        var sample = 1
        while (sample * 2 * fit <= 1f) sample *= 2
        while (max(info.rawWidth, info.rawHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = (if (src.file != null) BitmapFactory.decodeFile(src.file.path, opts) else src.open(ctx).use { BitmapFactory.decodeStream(it, null, opts) })
            ?: return null
        return upright(bmp, info.orientation)
    }

    /** Opens [src] for decoding parts of it (JPEG, PNG and WebP); null for other kinds or when it fails. */
    // 2.9: newInstance(path) without the second argument is Android 12's; Swift runs from Android 7.
    @Suppress("DEPRECATION")
    fun regionDecoder(ctx: Context, src: Source): BitmapRegionDecoder? = try {
        if (src.file != null) BitmapRegionDecoder.newInstance(src.file.path, false) else src.open(ctx).use { BitmapRegionDecoder.newInstance(it, false) }
    } catch (e: Exception) {
        L.w("A picture can't be decoded in parts", e)
        null
    }

    /** Part of a picture in full detail: [bitmap] upright, covering [rect] of the upright picture's pixels. */
    class Part(val bitmap: Bitmap, val rect: RectF)

    /**
     * The part [want] of the upright picture, decoded with at least one picture pixel per screen
     * pixel at [scale] (screen pixels per picture pixel): never more than about twice the screen's
     * pixels either way. Null when it fails.
     */
    fun decodePart(dec: BitmapRegionDecoder, info: Info, want: RectF, scale: Float): Part? {
        val toUpright = turnMatrix(info.orientation, 0, info.rawWidth.toFloat(), info.rawHeight.toFloat())
        val back = Matrix()
        if (!toUpright.invert(back)) return null
        val r = RectF(want)
        back.mapRect(r)
        val raw = Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
        if (!raw.intersect(0, 0, info.rawWidth, info.rawHeight) || raw.isEmpty) return null
        var sample = 1
        while (sample * 2 * scale <= 1f) sample *= 2
        val bmp = dec.decodeRegion(raw, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val done = RectF(raw)
        toUpright.mapRect(done)
        return Part(upright(bmp, info.orientation), done)
    }

    /**
     * An animated GIF ready to play, or null (a still one, too big, or one Android can't read).
     * 2.9: android.graphics.Movie is what Android 7 has (ImageDecoder came with Android 9). It has
     * no WebP: animated WebP pictures show their first frame.
     */
    @Suppress("DEPRECATION")
    fun movie(ctx: Context, src: Source): android.graphics.Movie? = try {
        val m = if (src.file != null) android.graphics.Movie.decodeFile(src.file.path) else src.open(ctx).use { android.graphics.Movie.decodeStream(it) }
        m?.takeIf { it.duration() > 0 && it.width() > 0 && it.height() > 0 && it.width().toLong() * it.height() <= MAX_GIF_PIXELS }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The name and size another app gives with its link (null and -1 when it says nothing). */
    fun nameAndSize(ctx: Context, uri: Uri): Pair<String?, Long> = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                (if (n >= 0) c.getString(n) else null) to (if (s >= 0 && !c.isNull(s)) c.getLong(s) else -1L)
            }
        } ?: (null to -1L)
    } catch (e: Exception) {
        null to -1L
    }
}
