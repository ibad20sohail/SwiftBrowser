package com.swiftbrowser.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.swiftbrowser.core.L
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Small pictures of tabs for the tab switcher. Taken from the screen when the switcher opens and
 * when the app goes to the background; kept in memory and, for normal tabs, on disk.
 */
object Thumbnails {
    /** Width of a stored picture in pixels; height follows [ASPECT]. */
    private const val WIDTH = 300
    const val ASPECT = 1.2f

    private lateinit var dir: File
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Long, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val absent = HashSet<Long>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
    }

    private fun file(id: Long) = File(dir, "$id.jpg")

    /** Deletes pictures of tabs that no longer exist. */
    fun cleanup(tabIds: Set<Long>) {
        exec.execute {
            dir.listFiles()?.forEach { f ->
                val id = f.nameWithoutExtension.toLongOrNull()
                if (id == null || id !in tabIds) f.delete()
            }
        }
    }

    /** Calls [onLoaded] on the main thread with the tab's picture, or null if there is none. */
    fun load(tab: Tab, onLoaded: (Bitmap?) -> Unit) {
        val id = tab.id
        cache.get(id)?.let {
            onLoaded(it)
            return
        }
        if (tab.incognito || id in absent) {
            onLoaded(null)
            return
        }
        exec.execute {
            val f = file(id)
            val bmp = if (f.exists()) {
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            } else {
                null
            }
            main.post {
                if (bmp != null) cache.put(id, bmp) else absent.add(id)
                onLoaded(bmp)
            }
        }
    }

    /**
     * Takes a picture of [tab] as [view] shows it now. [visibleHeight] is how much of the view is on
     * screen (the rest is under the bottom edge while the toolbar is shown).
     */
    fun capture(view: GeckoView, tab: Tab, visibleHeight: Int, done: (() -> Unit)? = null) {
        val session = tab.session
        if (session == null || view.session !== session || !session.isOpen || tab.isHome) {
            done?.invoke()
            return
        }
        view.capturePixels().accept({ full ->
            if (full == null) {
                done?.invoke()
                return@accept
            }
            exec.execute {
                val thumb = try {
                    shrink(full, visibleHeight)
                } catch (e: Throwable) {
                    L.w("Thumbnail failed", e)
                    null
                } finally {
                    full.recycle()
                }
                if (thumb != null && !tab.incognito) {
                    try {
                        FileOutputStream(file(tab.id)).use { thumb.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    } catch (e: Exception) {
                        L.w("Saving thumbnail failed", e)
                    }
                }
                main.post {
                    if (thumb != null && TabManager.tabs.contains(tab)) {
                        cache.put(tab.id, thumb)
                        absent.remove(tab.id)
                    }
                    done?.invoke()
                }
            }
        }, { _ -> done?.invoke() })
    }

    private fun shrink(full: Bitmap, visibleHeight: Int): Bitmap {
        val srcW = full.width
        val srcH = minOf(full.height, visibleHeight.takeIf { it > 0 } ?: full.height, (srcW * ASPECT).toInt())
        val outH = (WIDTH * srcH.toFloat() / srcW).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(WIDTH, outH, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(full, Rect(0, 0, srcW, srcH), Rect(0, 0, WIDTH, outH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun remove(tab: Tab) {
        cache.remove(tab.id)
        absent.remove(tab.id)
        if (!tab.incognito) exec.execute { file(tab.id).delete() }
    }

    /** A closed tab came back as [to]: it keeps [from]'s picture. */
    fun move(from: Tab, to: Tab) {
        cache.remove(from.id)?.let { cache.put(to.id, it) }
        absent.remove(from.id)
        if (!from.incognito) exec.execute { file(from.id).renameTo(file(to.id)) }
    }
}
