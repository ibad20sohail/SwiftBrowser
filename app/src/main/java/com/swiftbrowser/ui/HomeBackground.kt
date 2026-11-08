package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.exifinterface.media.ExifInterface
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import java.io.File
import java.io.FileOutputStream

/**
 * 3.0.4: what is behind the home page (Settings → Home & news → Background). Choices:
 * - Swift's own colour, as before;
 * - a soft colour gradient that fades into the page colour at the bottom;
 * - a picture of your own, kept as a JPEG the size of the screen in Swift's files and drawn under a
 *   veil of the page colour, so the shortcuts and texts on it stay readable.
 *
 * Never in incognito. Only the choice goes into backups (a picture is too big for them).
 */
object HomeBackground {
    const val NONE = ""
    const val PICTURE = "picture"
    const val ACCENT = "accent"

    /** A gradient: its name and its two colours in light mode and in dark mode. */
    class Preset(val key: String, val label: String, val light: IntArray, val dark: IntArray)

    val PRESETS = listOf(
        Preset("dawn", "Dawn", intArrayOf(0xFFFFE0D0.toInt(), 0xFFF7D2E4.toInt()), intArrayOf(0xFF3A2430.toInt(), 0xFF2A2238.toInt())),
        Preset("sea", "Sea", intArrayOf(0xFFD0EFF2.toInt(), 0xFFD9E4FF.toInt()), intArrayOf(0xFF0E2F37.toInt(), 0xFF132036.toInt())),
        Preset("forest", "Forest", intArrayOf(0xFFDAF1DC.toInt(), 0xFFE8F0D2.toInt()), intArrayOf(0xFF132E1D.toInt(), 0xFF1C2A17.toInt())),
        Preset("dusk", "Dusk", intArrayOf(0xFFE3DCFF.toInt(), 0xFFFFDAE6.toInt()), intArrayOf(0xFF282041.toInt(), 0xFF381F2F.toInt())),
    )

    /** The name of [key], for Settings. */
    fun label(key: String): String = when (key) {
        NONE -> "Swift's colour"
        PICTURE -> "Your picture"
        ACCENT -> "Your accent colour"
        else -> PRESETS.firstOrNull { it.key == key }?.label ?: "Swift's colour"
    }

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "home_background.jpg")

    /** The choice that applies: a picture only while its file is there (a restored backup has none). */
    fun current(ctx: Context): String {
        val k = SwiftApp.prefs.homeBackground
        return when {
            k == PICTURE && !file(ctx).isFile -> NONE
            k == NONE || k == PICTURE || k == ACCENT || PRESETS.any { it.key == k } -> k
            else -> NONE
        }
    }

    /** The picture as it was last read, and the file's time then. */
    private var cached: Bitmap? = null
    private var cachedTime = 0L
    private val main = Handler(Looper.getMainLooper())
    private val exec = Background.single("home-background")

    /** Lets go of the picture (Swift out of sight); it is read again when the home page shows. */
    fun trim() {
        cached = null
        cachedTime = 0L
    }

    /**
     * What to draw behind the home page now; null for Swift's colour (or in incognito). A picture
     * not read yet comes through [later] once it is.
     */
    fun drawable(ctx: Context, incognito: Boolean, later: (Drawable) -> Unit): Drawable? {
        if (incognito) return null
        val night = Palette.isNight(ctx)
        val page = Palette.color(ctx, R.color.home_bg)
        return when (val k = current(ctx)) {
            NONE -> null
            PICTURE -> {
                val f = file(ctx)
                val bmp = cached
                val veil = veilAt(SwiftApp.prefs.homePictureClarity)
                if (bmp != null && cachedTime == f.lastModified()) {
                    PictureDrawable(bmp, page, night, veil)
                } else {
                    exec.execute {
                        val read = try {
                            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                        } catch (e: Throwable) {
                            L.w("Home background couldn't be read", e)
                            null
                        }
                        if (read != null) main.post {
                            cached = read
                            cachedTime = f.lastModified()
                            later(PictureDrawable(read, page, night, veilAt(SwiftApp.prefs.homePictureClarity)))
                        }
                    }
                    null
                }
            }
            else -> {
                val two = if (k == ACCENT) {
                    val a = Palette.color(ctx, Looks.accent.primary)
                    intArrayOf(ColorUtils.blendARGB(page, a, if (night) 0.30f else 0.22f), ColorUtils.blendARGB(page, a, if (night) 0.14f else 0.10f))
                } else {
                    val p = PRESETS.first { it.key == k }
                    if (night) p.dark else p.light
                }
                gradient(two, page)
            }
        }
    }

    /** How much of the veil is left at this clearness (0..100): all of it at 0, none at 100. */
    private fun veilAt(clarity: Int) = 1f - clarity.coerceIn(0, 100) / 100f

    /** [picture], from [drawable], at this clearness (0..100), while Settings' slider moves. Anything else is left as it is. */
    fun showClarity(picture: Drawable?, clarity: Int) {
        (picture as? PictureDrawable)?.veilStrength = veilAt(clarity)
    }

    /**
     * How text standing straight on the home page's picture looks: [color], and with light text a
     * soft shadow, which carries it over the brighter parts of the picture.
     */
    class Ink(val color: Int, val shadow: Boolean)

    /** For [background], from [drawable]: a picture's [Ink]; null for anything else, whose text is as on any page. */
    fun inkOf(ctx: Context, background: Drawable?): Ink? = (background as? PictureDrawable)?.ink(ctx)

    /** [tv] as [ink] says; without one, in [plain] and with no shadow. */
    fun paint(tv: TextView, ink: Ink?, plain: Int) {
        tv.setTextColor(ink?.color ?: plain)
        if (ink?.shadow == true) tv.setShadowLayer(Ui.dpf(tv.context, 3f), 0f, Ui.dpf(tv.context, 1f), INK_SHADOW) else tv.setShadowLayer(0f, 0f, 0f, 0)
    }

    /** [picture], from [drawable], also fills this much room past the page's top and bottom: where the address bar is. */
    fun reach(picture: Drawable?, above: Int, below: Int) {
        (picture as? PictureDrawable)?.reach(above, below)
    }

    /** What draws the part of [picture] that is behind the address bar, as the bar's own background; null when it isn't a picture. */
    fun barPart(picture: Drawable?): Drawable? = (picture as? PictureDrawable)?.barPart

    /** The colour of the address bar's buttons over [picture], whichever reads best on the strip behind them; null when it isn't a picture. */
    fun barInk(ctx: Context, picture: Drawable?): Int? = (picture as? PictureDrawable)?.barInk(ctx)

    /** About the colour along the top edge of [picture] as it shows, for the status bar above it; null when it isn't a picture. */
    fun topColour(ctx: Context, picture: Drawable?): Int? = (picture as? PictureDrawable)?.topColour(ctx)

    private val INK_LIGHT = 0xFFFFFFFF.toInt()
    private val INK_DARK = 0xFF1A1C21.toInt()
    private val INK_SHADOW = 0x8C000000.toInt()

    // Where on the picture the text stands: most of the screen's width, from the top down to the news.
    private const val SAMPLE_COLUMNS = 12
    private const val SAMPLE_ROWS = 18
    private const val SAMPLE_TOP = 0.03f
    private const val SAMPLE_BOTTOM = 0.75f

    // The strip behind the address bar, and the top edge for the status bar.
    private const val BAR_ROWS = 4
    private const val TOP_ROWS = 2
    private const val TOP_EDGE = 0.02f

    /** A gradient from [two] at the top into the page colour at the bottom. */
    fun gradient(two: IntArray, page: Int): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(two[0], two[1], page))

    /** A small round sample of [key] for the chooser. */
    fun swatch(ctx: Context, key: String): Drawable {
        val night = Palette.isNight(ctx)
        val page = Palette.color(ctx, R.color.home_bg)
        val two = when (key) {
            ACCENT -> {
                val a = Palette.color(ctx, Looks.accent.primary)
                intArrayOf(ColorUtils.blendARGB(page, a, 0.45f), ColorUtils.blendARGB(page, a, 0.2f))
            }
            NONE -> intArrayOf(page, page)
            else -> PRESETS.first { it.key == key }.let { if (night) it.dark else it.light }
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, two).apply {
            shape = GradientDrawable.OVAL
            setStroke(Ui.dp(ctx, 1), Palette.color(ctx, R.color.outline))
        }
    }

    /**
     * Keeps the picture at [uri] as the home page's (turned upright, no bigger than the screen).
     * Blocking: off the main thread. False when it couldn't be read.
     */
    fun savePicture(ctx: Context, uri: Uri): Boolean = try {
        val cr = ctx.contentResolver
        val dm = ctx.resources.displayMetrics
        val longest = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(1600)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            false
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
            val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            if (raw == null) {
                false
            } else {
                val turn = runCatching {
                    cr.openInputStream(uri)?.use { input ->
                        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    } ?: 0
                }.getOrDefault(0)
                // 3.0.5: exactly the screen's size: halving alone left up to twice that each way, kept in memory.
                val biggest = maxOf(raw.width, raw.height)
                val fitted = if (biggest > longest) {
                    Bitmap.createScaledBitmap(raw, raw.width * longest / biggest, raw.height * longest / biggest, true).also { if (it !== raw) raw.recycle() }
                } else {
                    raw
                }
                val upright = if (turn != 0) {
                    Bitmap.createBitmap(fitted, 0, 0, fitted.width, fitted.height, Matrix().apply { postRotate(turn.toFloat()) }, true).also { if (it !== fitted) fitted.recycle() }
                } else {
                    fitted
                }
                val target = file(ctx)
                val tmp = File(target.parentFile, target.name + ".part")
                FileOutputStream(tmp).use { upright.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                upright.recycle()
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
                trim()
                target.isFile
            }
        }
    } catch (e: Throwable) {
        L.w("Home background couldn't be saved", e)
        false
    }

    /** Forgets the picture (back to Swift's colour if it was in use). */
    fun removePicture(ctx: Context) {
        file(ctx).delete()
        trim()
        if (SwiftApp.prefs.homeBackground == PICTURE) SwiftApp.prefs.homeBackground = NONE
    }

    fun hasPicture(ctx: Context) = file(ctx).isFile

    /**
     * 3.0.4: the home page's picture, small (about [PictureColours.SAMPLE] pixels across), for the
     * colours that go with it (Settings → Colours → From your picture); null without one. Blocking:
     * off the main thread.
     */
    fun smallPicture(ctx: Context): Bitmap? = try {
        val f = file(ctx)
        if (!f.isFile) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PictureColours.SAMPLE) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Throwable) {
        L.w("Home background couldn't be read for its colours", e)
        null
    }

    /**
     * The picture filling the screen (cut to its shape, centred), under a veil of the page colour: light
     * at the top where the logo and search box are, thicker lower down behind the shortcuts' names.
     * [strength]: how much of that veil there is, 1 all of it, 0 none (Settings' slider).
     * 3.0.5: it is cut for the page and the address bar's room together ([reach]), and [barPart] draws
     * the bar's share, so the picture runs on unbroken behind the see-through bar.
     */
    private class PictureDrawable(private val bmp: Bitmap, private val page: Int, private val night: Boolean, strength: Float) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val veil = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, veilColours(strength))

        /** How far the picture has faded in on the page (a picture read late), 255 once it has. */
        private var fade = 255

        /** All the picture fills: the page ([getBounds]) and the bar's room past its edges. */
        private val area = Rect()
        private var above = 0
        private var below = 0

        fun reach(above: Int, below: Int) {
            if (this.above == above && this.below == below) return
            this.above = above
            this.below = below
            barLookedAt = -1f
            fit()
            invalidateSelf()
            barPart.invalidateSelf()
        }

        /** The bar's share of the picture, in the bar's own place. */
        val barPart: Drawable = object : Drawable() {
            override fun draw(canvas: Canvas) {
                canvas.save()
                canvas.clipRect(bounds)
                // The bar stands right above the page, or right below it.
                canvas.translate(0f, if (above > 0) above.toFloat() else -this@PictureDrawable.bounds.bottom.toFloat())
                // Always whole: a picture read late fades in on the page, while the bar turns see-through over it.
                drawAll(canvas, 255)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) = Unit

            override fun setColorFilter(colorFilter: ColorFilter?) = Unit

            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        var veilStrength = strength
            set(v) {
                if (field == v) return
                field = v
                veil.colors = veilColours(v)
                invalidateSelf()
                barPart.invalidateSelf()
            }

        private fun veilStops() = if (night) intArrayOf(0x73, 0xB0, 0xE6) else intArrayOf(0x66, 0xA6, 0xE6)

        private fun veilColours(strength: Float): IntArray {
            val stops = veilStops()
            return IntArray(stops.size) { ColorUtils.setAlphaComponent(page, (stops[it] * strength).toInt()) }
        }

        private var inkAt = -1f
        private var inkLight = false

        /** Light or dark text, as reads best on the picture as it shows now (looked at once for each strength of the veil). */
        fun ink(ctx: Context): Ink {
            if (inkAt != veilStrength) {
                inkLight = lightReadsBetter(ctx)
                inkAt = veilStrength
            }
            // The look's own colour when that is the side it comes down on.
            return Ink(if (inkLight == night) Ui.text(ctx) else if (inkLight) INK_LIGHT else INK_DARK, inkLight)
        }

        /** A couple of hundred points of the picture where text stands, each seen through the veil there. */
        private fun lightReadsBetter(ctx: Context): Boolean {
            if (bmp.isRecycled) return night
            var light = 0
            look(ctx, SAMPLE_TOP, SAMPLE_BOTTOM, SAMPLE_ROWS) { if (Contrast.ratio(INK_LIGHT, it) >= Contrast.ratio(INK_DARK, it)) light++ }
            return PictureInk.light(light, SAMPLE_COLUMNS * SAMPLE_ROWS)
        }

        private var barLookedAt = -1f
        private var barLight = false
        private var edge = 0

        /** The strip behind the bar and the top edge, looked at once for each strength of the veil. */
        private fun lookAtBar(ctx: Context) {
            if (barLookedAt == veilStrength) return
            barLookedAt = veilStrength
            barLight = night
            edge = page
            if (bmp.isRecycled) return
            val share = (above + below).toFloat() / ctx.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            var light = 0
            val from = if (above > 0) 0f else 1f - share
            look(ctx, from, from + share, BAR_ROWS) { if (Contrast.ratio(INK_LIGHT, it) >= Contrast.ratio(INK_DARK, it)) light++ }
            barLight = PictureInk.barLight(light, SAMPLE_COLUMNS * BAR_ROWS)
            var r = 0
            var g = 0
            var b = 0
            look(ctx, 0f, TOP_EDGE, TOP_ROWS) {
                r += (it shr 16) and 0xFF
                g += (it shr 8) and 0xFF
                b += it and 0xFF
            }
            val points = SAMPLE_COLUMNS * TOP_ROWS
            edge = 0xFF000000.toInt() or ((r / points) shl 16) or ((g / points) shl 8) or (b / points)
        }

        fun barInk(ctx: Context): Int {
            lookAtBar(ctx)
            return if (barLight == night) Ui.text(ctx) else if (barLight) INK_LIGHT else INK_DARK
        }

        fun topColour(ctx: Context): Int {
            lookAtBar(ctx)
            return edge
        }

        /** Gives [seen] [rows] rows of points of the picture, between the heights [from] and [to] of the screen (0..1), each as it shows through the veil. */
        private inline fun look(ctx: Context, from: Float, to: Float, rows: Int, seen: (Int) -> Unit) {
            val dm = ctx.resources.displayMetrics
            val scale = maxOf(dm.widthPixels.toFloat() / bmp.width, dm.heightPixels.toFloat() / bmp.height)
            val sw = (dm.widthPixels / scale).toInt().coerceIn(1, bmp.width)
            val sh = (dm.heightPixels / scale).toInt().coerceIn(1, bmp.height)
            val left = (bmp.width - sw) / 2
            val top = (bmp.height - sh) / 2
            val stops = veilStops()
            for (c in 0 until SAMPLE_COLUMNS) for (r in 0 until rows) {
                val y = (from + (to - from) * r / (rows - 1)).coerceIn(0f, 1f)
                val point = bmp.getPixel(left + (sw - 1) * (2 * c + 1) / (2 * SAMPLE_COLUMNS), top + ((sh - 1) * y).toInt())
                val veil = if (y < 0.5f) stops[0] + (stops[1] - stops[0]) * y * 2 else stops[1] + (stops[2] - stops[1]) * (y - 0.5f) * 2
                seen(Contrast.mix(point, page, veil * veilStrength / 255.0))
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            fit()
            barPart.invalidateSelf()
        }

        private fun fit() {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            area.set(b.left, b.top - above, b.right, b.bottom + below)
            val w = area.width().toFloat()
            val h = area.height().toFloat()
            // As much of the picture as has the screen's shape, from its middle.
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val sw = (w / scale).toInt().coerceAtMost(bmp.width)
            val sh = (h / scale).toInt().coerceAtMost(bmp.height)
            val left = (bmp.width - sw) / 2
            val top = (bmp.height - sh) / 2
            src.set(left, top, left + sw, top + sh)
            veil.bounds = area
        }

        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipRect(bounds)
            drawAll(canvas, fade)
            canvas.restore()
        }

        private fun drawAll(canvas: Canvas, alpha: Int) {
            paint.alpha = alpha
            veil.alpha = alpha
            // The picture covers everything: the page colour is only needed if it has gone.
            if (bmp.isRecycled) canvas.drawColor(page) else canvas.drawBitmap(bmp, src, area, paint)
            if (veilStrength > 0f) veil.draw(canvas)
        }

        /** The whole thing fades, the veil with the picture (a picture read late fades in, see HomeView). */
        override fun setAlpha(alpha: Int) {
            fade = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}

/** Which text a picture takes. Plain rules, tested on the PC (PictureInkTest). */
internal object PictureInk {
    /** How much of a picture light text must read better on: about half is enough, as its shadow carries it over the rest. */
    const val LIGHT_FROM = 0.45f

    fun light(lightBetter: Int, points: Int): Boolean = points > 0 && lightBetter.toFloat() / points >= LIGHT_FROM

    /** The address bar's buttons have no shadow to carry them: light only where that reads better on at least half the strip. */
    fun barLight(lightBetter: Int, points: Int): Boolean = points > 0 && lightBetter * 2 >= points
}
