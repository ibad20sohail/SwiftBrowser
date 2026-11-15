package com.swiftbrowser.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.swiftbrowser.core.Background
import java.io.File
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Small square pictures for the Files list (2.7): photos (read at a fraction of their size, JPEGs
 * in the lighter 16-bit format), a frame of each video and an app's icon. Made two at a time off
 * the main thread, skipped for rows scrolled away before their turn, and a few hundred kept in
 * memory.
 */
object FileThumbs {
    private val pool = Background.pool("file-thumbs", 2)
    private val main = Handler(Looper.getMainLooper())
    // 2.8: the grid's bigger pictures need more room: a share of what Swift may use (about 8 MB on a
    // 2 GB phone), so scrolling back up doesn't make them all again.
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 24).toInt().coerceIn(4 * 1024 * 1024, 12 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Files known to have no picture (not tried again this time). */
    private val none: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * 3.0.11: what the read that made a picture also told about its file: a video's length (ms) and
     * a photo's own size, upright (px); 0 when not known. No more than that one read, ever.
     */
    class Facts(val lengthMs: Long, val width: Int, val height: Int)

    /** By file and time, whatever size its picture was made at; a few bytes each, so many are kept. They go with the pictures ([trim]). */
    private val facts = LruCache<String, Facts>(1024)

    private fun factsKey(file: File, time: Long) = "${file.path}:$time"

    /** 3.0.11: [file]'s facts (as it was at [time]), once a picture of it was made; null before. */
    fun facts(file: File, time: Long): Facts? = facts.get(factsKey(file, time))

    /** A picture asked for by a row: cancelled when the row shows another file or scrolls away. */
    class Request internal constructor() {
        @Volatile
        var cancelled = false

        fun cancel() {
            cancelled = true
        }
    }

    fun has(kind: FileKind) = kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.APK

    /**
     * [onLoaded] runs on the main thread with [file]'s picture ([size] px square), straight away
     * when it is in memory. Returns the request to cancel, or null when there is nothing to wait for.
     * 3.0.3: [wide], a video's whole frame [size] px wide at most (Continue's cards in Files).
     * 3.0.11: [onNone] (main thread) when it turned out there is no picture to make, so a loading
     * pulse (PictureLoading) can end.
     */
    fun load(
        ctx: Context, file: File, time: Long, kind: FileKind, size: Int, wide: Boolean = false,
        onNone: (() -> Unit)? = null, onLoaded: (Bitmap) -> Unit,
    ): Request? {
        if (!has(kind)) return null
        val frame = wide && kind == FileKind.VIDEO
        val key = (if (frame) "w" else "") + "$size:${file.path}:$time"
        memory.get(key)?.let {
            onLoaded(it)
            return null
        }
        if (key in none) return null
        val r = Request()
        val app = ctx.applicationContext
        pool.execute {
            if (r.cancelled) return@execute
            val bmp = try {
                if (frame) video(file, time)?.let { wideOf(it, size) } else make(app, file, time, kind, size)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (bmp == null) {
                none += key
                if (onNone != null) main.post { if (!r.cancelled) onNone() }
                return@execute
            }
            main.post {
                memory.put(key, bmp)
                if (!r.cancelled) onLoaded(bmp)
            }
        }
        return r
    }

    /** Swift left the screen or memory is short: the pictures go (they are made again when needed). 3.0.11: their facts too. */
    fun trim() {
        memory.evictAll()
        facts.evictAll()
    }

    private fun make(ctx: Context, file: File, time: Long, kind: FileKind, size: Int): Bitmap? = when (kind) {
        FileKind.IMAGE -> image(file, time, size)
        FileKind.VIDEO -> video(file, time)?.let { square(it, size, 0) }
        FileKind.APK -> apkIcon(ctx, file, size)
        else -> null
    }

    /**
     * A frame from the video (Android's own small size, 512 × 384 at most), the one Android's own
     * small pictures show (ThumbnailUtils did it before: the same frame, whatever Android version).
     * 3.0.11: and its length, from the same opening of the file (kept in [facts]).
     */
    private fun video(file: File, time: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val length = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (length > 0) facts.put(factsKey(file, time), Facts(length, 0, 0))
            return frameOf(r, length)
        } catch (_: RuntimeException) {
            // A video Android can't read.
            return null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /** What ThumbnailUtils.createVideoThumbnail(path, MINI_KIND) picks: Android 10 changed it. */
    private fun frameOf(r: MediaMetadataRetriever, length: Long): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) {
            val bmp = r.getFrameAtTime(-1) ?: return null
            val most = max(bmp.width, bmp.height)
            if (most <= MINI_WIDE) return bmp
            val s = MINI_WIDE.toFloat() / most
            return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true).also { if (it !== bmp) bmp.recycle() }
        }
        // Android 10 and later: the video's own cover picture if it has one, else its middle frame.
        r.embeddedPicture?.let { raw ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
            val sample = max(o.outWidth / MINI_WIDE, o.outHeight / MINI_HIGH)
            val opts = BitmapFactory.Options().apply { if (sample > 1) inSampleSize = sample }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)?.let { return it }
        }
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val at = length * 1000 / 2
        return if (w in 1 until MINI_WIDE && h in 1 until MINI_HIGH) {
            r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } else {
            r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MINI_WIDE, MINI_HIGH)
        }
    }

    /** Android's own small size for a video's picture (ThumbnailUtils' MINI_KIND). */
    private const val MINI_WIDE = 512
    private const val MINI_HIGH = 384

    private fun image(file: File, time: Long, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val jpeg = bounds.outMimeType == "image/jpeg"
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Photos have no see-through parts: half the memory.
            if (jpeg) inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        // 3.0: AndroidX's reader, the same on every Android version (Android 7's own misses some photos' turn).
        val degrees = if (jpeg) {
            when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) }.getOrDefault(0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } else {
            0
        }
        // 3.0.11: its own size as it shows, upright, read with it (a picture opened from Files grows to it).
        val sideways = degrees == 90 || degrees == 270
        facts.put(factsKey(file, time), Facts(0, if (sideways) bounds.outHeight else bounds.outWidth, if (sideways) bounds.outWidth else bounds.outHeight))
        return square(bmp, size, degrees)
    }

    /** 3.0.3: [src] as it is, [width] px wide at most, in the lighter 16-bit format (a frame has no see-through parts). */
    private fun wideOf(src: Bitmap, width: Int): Bitmap {
        val s = min(1f, width.toFloat() / src.width.coerceAtLeast(1))
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /** The middle square of [src], [size] px, turned upright. */
    private fun square(src: Bitmap, size: Int, degrees: Int): Bitmap {
        val side = min(src.width, src.height)
        if (side <= 0) return src
        val m = Matrix()
        val s = size.toFloat() / side
        if (s < 1f) m.setScale(s, s)
        if (degrees != 0) m.postRotate(degrees.toFloat())
        val out = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun apkIcon(ctx: Context, file: File, size: Int): Bitmap? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(file.path, 0)?.applicationInfo ?: return null
        info.sourceDir = file.path
        info.publicSourceDir = file.path
        val icon = info.loadIcon(pm) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))
        return bmp
    }
}
