package com.swiftbrowser.docs

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Small pictures of documents (2.4) for the Documents list and Continue: a PDF's first page (drawn
 * by Android's own PDF renderer), or the picture an Office file or e-book carries. Kept in memory
 * and on disk, so each is made once.
 */
object DocThumbs {
    private val io = com.swiftbrowser.core.Background.single("doc-thumbs")
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Documents known to have no picture (no need to look again this time). */
    private val none = HashSet<String>()

    /** Clearing browsing data (2.6): the pictures of documents go, in memory and on disk. */
    fun clear(ctx: Context) {
        memory.evictAll()
        none.clear()
        io.execute { File(ctx.cacheDir, "doc_thumbs").deleteRecursively() }
    }

    /** Swift went out of sight or Android is short of memory: the pictures kept in memory go (they are on disk too). */
    fun trim() = memory.evictAll()

    /** [onLoaded] runs on the main thread, only when there is a picture. */
    fun load(ctx: Context, uri: String, kind: Docs.Kind, width: Int, onLoaded: (Bitmap) -> Unit) {
        val key = "$width:$uri"
        memory.get(key)?.let {
            onLoaded(it)
            return
        }
        if (key in none) return
        val app = ctx.applicationContext
        io.execute {
            val file = File(File(app.cacheDir, "doc_thumbs").apply { mkdirs() }, hash(key) + ".jpg")
            val bmp = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null)
                ?: make(app, Uri.parse(uri), kind, width)?.also { b ->
                    try {
                        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    } catch (_: Exception) {
                    }
                }
            main.post {
                if (bmp == null) {
                    none += key
                } else {
                    memory.put(key, bmp)
                    onLoaded(bmp)
                }
            }
        }
    }

    private fun make(ctx: Context, uri: Uri, kind: Docs.Kind, width: Int): Bitmap? = try {
        when (kind) {
            Docs.Kind.PDF -> pdfPage(ctx, uri, width)
            Docs.Kind.WORD, Docs.Kind.SLIDES, Docs.Kind.SHEET -> zipPicture(ctx, uri, width) { it.equals("docProps/thumbnail.jpeg", true) }
            Docs.Kind.ODT, Docs.Kind.ODP -> zipPicture(ctx, uri, width) { it.equals("Thumbnails/thumbnail.png", true) }
            Docs.Kind.EPUB -> zipPicture(ctx, uri, width) { n ->
                val l = n.lowercase(Locale.ROOT)
                l.contains("cover") && (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png"))
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun pdfPage(ctx: Context, uri: Uri, width: Int): Bitmap? {
        val pfd = Docs.descriptor(ctx, uri)
        if (pfd.statSize <= 0) {
            pfd.close()
            return null
        }
        // The renderer closes the file when it is closed.
        PdfRenderer(pfd).use { r ->
            if (r.pageCount == 0) return null
            r.openPage(0).use { page ->
                val h = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 2)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }

    /** The first entry of the zip (Office files and e-books are zips) that [wanted] names. */
    private fun zipPicture(ctx: Context, uri: Uri, width: Int, wanted: (String) -> Boolean): Bitmap? {
        stream(ctx, uri).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var scanned = 0
                while (scanned++ < 4000) {
                    val e = zip.nextEntry ?: return null
                    if (e.isDirectory || !wanted(e.name)) continue
                    val bytes = zip.readBytes().takeIf { it.size < 4_000_000 } ?: return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }
        return null
    }

    private fun stream(ctx: Context, uri: Uri): InputStream =
        if (uri.scheme == ContentResolver.SCHEME_FILE) FileInputStream(uri.path) else ctx.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException(uri.toString())

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(12).joinToString("") { String.format("%02x", it) }
}

/** A document's icon: its kind's colour with a short mark (PDF, W, X, P, EPUB…), as in file managers. */
class DocIcon(private val kind: Docs.Kind, private val sizePx: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b)
        fill.color = kind.color
        val r = b.width() * 0.22f
        c.drawRoundRect(rect, r, r, fill)
        text.textSize = b.width() * if (kind.badge.length > 2) 0.27f else 0.46f
        c.drawText(kind.badge, b.exactCenterX(), b.exactCenterY() - (text.descent() + text.ascent()) / 2, text)
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        text.alpha = alpha
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
