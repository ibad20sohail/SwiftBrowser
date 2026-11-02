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
 *
 * 3.0: Android 7's PDF renderer is years behind on fixes and runs inside Swift, on PDFs from
 * anywhere. A PDF's picture is now the first page as Swift's viewer drew it (pdf.js, sent back once,
 * see [fromViewer]); Android's renderer is only the fallback for a PDF not opened since, and a PDF
 * it once didn't finish (Swift crashed or was closed meanwhile) is never given to it again.
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
            val file = File(dir(app), hash(key) + ".jpg")
            // 2.8: a preview with nothing on it (an empty picture some programs store, a page too
            // pale to see) shows the kind's mark instead; one kept from before is dropped.
            val kept = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null)?.takeUnless { looksBlank(it).also { blank -> if (blank) file.delete() } }
            val bmp = kept
                ?: make(app, uri, kind, width)?.takeUnless { looksBlank(it) }?.also { b ->
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

    private fun dir(ctx: Context) = File(ctx.cacheDir, "doc_thumbs").apply { mkdirs() }

    /** The first page of the PDF at [uri] as the viewer drew it (3.0), kept for every size. */
    private fun viewerFile(ctx: Context, uri: String) = File(dir(ctx), hash("page:$uri") + ".page.jpg")

    /** Whether the viewer has drawn the PDF at [uri] for its picture already (it is asked once). */
    fun hasViewerPicture(ctx: Context, uri: String) = viewerFile(ctx, uri).exists()

    /**
     * The first page of the PDF at [uri], drawn by the viewer as a JPEG ([base64]) (3.0). Only a
     * small, well-formed JPEG is kept; the PDF's pictures made from it show from now on.
     */
    fun fromViewer(ctx: Context, uri: String, base64: String) {
        if (base64.length > MAX_VIEWER_PICTURE * 4 / 3 + 4) return
        val bytes = try {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return
        }
        // A JPEG (FF D8 FF) of a sensible size, nothing else.
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() || bytes[2] != 0xFF.toByte()) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 16..1200 || bounds.outHeight !in 16..2400) return
        try {
            viewerFile(ctx, uri).writeBytes(bytes)
        } catch (_: Exception) {
            return
        }
        // A PDF found to have no picture this time gets this one when it is next shown.
        main.post { none.removeAll { it.endsWith(":$uri") } }
    }

    private fun make(ctx: Context, uri: String, kind: Docs.Kind, width: Int): Bitmap? = try {
        val u = Uri.parse(uri)
        when (kind) {
            Docs.Kind.PDF -> viewerPage(ctx, uri, width) ?: pdfPage(ctx, uri, u, width)
            Docs.Kind.WORD, Docs.Kind.SLIDES, Docs.Kind.SHEET -> zipPicture(ctx, u, width) { it.equals("docProps/thumbnail.jpeg", true) }
            Docs.Kind.ODT, Docs.Kind.ODP -> zipPicture(ctx, u, width) { it.equals("Thumbnails/thumbnail.png", true) }
            Docs.Kind.EPUB -> zipPicture(ctx, u, width) { n ->
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

    /** The viewer's drawing of the first page, at about [width]. */
    private fun viewerPage(ctx: Context, uri: String, width: Int): Bitmap? {
        val f = viewerFile(ctx, uri)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Nearly one colour all over: under one sample in 200 differs from the corner's colour. */
    private fun looksBlank(b: Bitmap): Boolean {
        if (b.width < 4 || b.height < 4) return true
        val first = b.getPixel(1, 1)
        var differ = 0
        var total = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = b.getPixel(x * (b.width - 1) / 23, y * (b.height - 1) / 23)
            total++
            if (Math.abs(Color.red(p) - Color.red(first)) > 24 || Math.abs(Color.green(p) - Color.green(first)) > 24 ||
                Math.abs(Color.blue(p) - Color.blue(first)) > 24
            ) differ++
        }
        return differ * 200 < total
    }

    /**
     * Android's own renderer (the fallback, 3.0). A "trying" mark is left while it draws: if Swift
     * stops meanwhile (the renderer crashed it), the mark stays and that PDF is never tried again.
     */
    private fun pdfPage(ctx: Context, key: String, uri: Uri, width: Int): Bitmap? {
        val trying = File(dir(ctx), hash("page:$key") + ".trying")
        if (trying.exists()) return null
        val pfd = Docs.descriptor(ctx, uri)
        if (pfd.statSize <= 0) {
            pfd.close()
            return null
        }
        try {
            trying.createNewFile()
        } catch (_: Exception) {
            pfd.close()
            return null
        }
        try {
            // The renderer closes the file when it is closed.
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    // 2.8: drawn twice as big, then shrunk smoothly, so small text still shows as grey lines.
                    val h = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, width * 2)
                    val big = Bitmap.createBitmap(width * 2, h * 2, Bitmap.Config.ARGB_8888)
                    big.eraseColor(Color.WHITE)
                    page.render(big, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bmp = Bitmap.createScaledBitmap(big, width, h, true)
                    if (bmp !== big) big.recycle()
                    return bmp
                }
            }
        } finally {
            trying.delete()
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
                    // 3.0: never unpacked past 4 MB (its stated size can be missing or false).
                    if (e.size >= MAX_PICTURE) return null
                    val bytes = readCapped(zip, MAX_PICTURE) ?: return null
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

    /** All of [input] when it is under [cap] bytes, else null (reading stops there). */
    private fun readCapped(input: InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n >= cap) return null
            out.write(buf, 0, n)
        }
    }

    private fun stream(ctx: Context, uri: Uri): InputStream =
        if (uri.scheme == ContentResolver.SCHEME_FILE) FileInputStream(uri.path) else ctx.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException(uri.toString())

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(12).joinToString("") { String.format("%02x", it) }

    private const val MAX_PICTURE = 4_000_000

    /** The largest page picture taken from the viewer (it sends about 40 KB). */
    private const val MAX_VIEWER_PICTURE = 600_000
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
