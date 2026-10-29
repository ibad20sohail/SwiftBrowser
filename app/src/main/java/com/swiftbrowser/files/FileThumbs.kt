package com.swiftbrowser.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import com.swiftbrowser.core.Background
import java.io.File
import java.util.Collections
import kotlin.math.min

/**
 * Small square pictures for the Files list (2.7): photos (read at a fraction of their size, JPEGs
 * in the lighter 16-bit format), a frame of each video and an app's icon. Made two at a time off
 * the main thread, skipped for rows scrolled away before their turn, and a few hundred kept in
 * memory.
 */
object FileThumbs {
    private val pool = Background.pool("file-thumbs", 2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Files known to have no picture (not tried again this time). */
    private val none: MutableSet<String> = Collections.synchronizedSet(HashSet())

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
     */
    fun load(ctx: Context, file: File, time: Long, kind: FileKind, size: Int, onLoaded: (Bitmap) -> Unit): Request? {
        if (!has(kind)) return null
        val key = "$size:${file.path}:$time"
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
                make(app, file, kind, size)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            if (bmp == null) {
                none += key
                return@execute
            }
            main.post {
                memory.put(key, bmp)
                if (!r.cancelled) onLoaded(bmp)
            }
        }
        return r
    }

    /** Swift left the screen or memory is short: the pictures go (they are made again when needed). */
    fun trim() = memory.evictAll()

    private fun make(ctx: Context, file: File, kind: FileKind, size: Int): Bitmap? = when (kind) {
        FileKind.IMAGE -> image(file, size)
        FileKind.VIDEO -> video(file)?.let { square(it, size, 0) }
        FileKind.APK -> apkIcon(ctx, file, size)
        else -> null
    }

    /** A frame from the video (Android's own small size, 512 × 384 at most). */
    @Suppress("DEPRECATION")
    private fun video(file: File): Bitmap? = ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Images.Thumbnails.MINI_KIND)

    private fun image(file: File, size: Int): Bitmap? {
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
        return square(bmp, size, degrees)
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
