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

    /**
     * 3.0.5: the colour behind text on the home page's picture: the page's own, see-through, just
     * enough of it for [text] to read at [min]:1 on any picture (HomeBacking). Soft shapes in it go
     * behind the shortcuts and the headings; everything else on the page stands on the picture.
     */
    fun backing(ctx: Context, text: Int, min: Double = Contrast.TEXT): Int {
        val page = Palette.color(ctx, R.color.home_bg)
        val veil = if (Palette.isNight(ctx)) HomeBacking.VEIL_TOP_DARK else HomeBacking.VEIL_TOP_LIGHT
        return ColorUtils.setAlphaComponent(page, HomeBacking.opacity(page, text, veil, min))
    }

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
                if (bmp != null && cachedTime == f.lastModified()) {
                    PictureDrawable(bmp, page, night)
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
                            later(PictureDrawable(read, page, night))
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
     */
    private class PictureDrawable(private val bmp: Bitmap, private val page: Int, night: Boolean) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val veil = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (night) intArrayOf(ColorUtils.setAlphaComponent(page, HomeBacking.VEIL_TOP_DARK), ColorUtils.setAlphaComponent(page, 0xB0), ColorUtils.setAlphaComponent(page, 0xE6))
            else intArrayOf(ColorUtils.setAlphaComponent(page, HomeBacking.VEIL_TOP_LIGHT), ColorUtils.setAlphaComponent(page, 0xA6), ColorUtils.setAlphaComponent(page, 0xE6)),
        )

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            // As much of the picture as has the screen's shape, from its middle.
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val sw = (w / scale).toInt().coerceAtMost(bmp.width)
            val sh = (h / scale).toInt().coerceAtMost(bmp.height)
            val left = (bmp.width - sw) / 2
            val top = (bmp.height - sh) / 2
            src.set(left, top, left + sw, top + sh)
            veil.bounds = bounds
        }

        override fun draw(canvas: Canvas) {
            canvas.drawColor(page)
            if (!bmp.isRecycled) canvas.drawBitmap(bmp, src, bounds, paint)
            veil.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}

/**
 * 3.0.5: how much of the page colour goes behind text on the home page's picture so it reads on any
 * picture, bright or dark, wherever it has scrolled to. Plain numbers, tested on the PC
 * (HomeBackingTest).
 */
internal object HomeBacking {
    /** The veil over the picture at its thinnest, at the top of the screen (HomeBackground.PictureDrawable). */
    const val VEIL_TOP_LIGHT = 0x66
    const val VEIL_TOP_DARK = 0x73

    /**
     * The least opacity (0..255) of [page] that gives [text] [min]:1 over the worst picture there can
     * be under a veil of [veil] (0..255): black under the light look's dark text, white under the
     * dark look's light text. The picture's real colours can only make it easier.
     */
    fun opacity(page: Int, text: Int, veil: Int, min: Double = Contrast.TEXT): Int {
        val worst = if (Contrast.luminance(text) > Contrast.luminance(page)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val veiled = Contrast.mix(worst, page, veil / 255.0)
        for (a in 0..255) if (Contrast.ratio(text, Contrast.mix(veiled, page, a / 255.0)) >= min) return a
        return 255
    }
}
