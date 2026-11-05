package com.swiftbrowser.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 3.0.4: the main colours of a picture (the home page's picture, the phone's wallpaper, an album
 * cover), for colours that go with it, as Android 12's Material You takes them from the wallpaper.
 * Android 7 has none of that, so Swift works them out the same way: it counts the picture's colours,
 * then prefers hues that cover much of it and are colourful (Material's own scoring, on CIELAB
 * lightness and chroma). The counting is plain maths on ARGB numbers, tested on the PC
 * (PictureColoursTest); only the last few functions touch Android's pictures.
 */
object PictureColours {
    /** The longest side a picture is scaled to before counting: plenty for its main colours, and quick. */
    const val SAMPLE = 112

    /** Below this chroma a colour is a grey: its hue says nothing. */
    private const val MIN_CHROMA = 5.0

    /** A hue (with its neighbours) has to cover more than this share of the picture to count. */
    private const val MIN_SHARE = 0.01

    /** Material's scoring: more colourful than this scores up fast, less colourful scores down slowly. */
    private const val TARGET_CHROMA = 48.0

    /**
     * Up to [max] main colours of [pixels] (ARGB), best first, each at least 15° of hue from the
     * others (as far apart as still gives [max] of them); empty when the picture is (nearly) grey.
     * They are the picture's own colours, maybe dark or pale: take a tone that suits with
     * [Tones.tone].
     */
    fun seeds(pixels: IntArray, max: Int = 4): List<Int> {
        if (max <= 0) return emptyList()
        // 1. Colours counted in 4096 boxes (4 bits a channel), each box keeping its average colour.
        val count = IntArray(4096)
        val sumR = LongArray(4096)
        val sumG = LongArray(4096)
        val sumB = LongArray(4096)
        var total = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue // see-through: not part of the picture
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val i = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            count[i]++
            sumR[i] += r.toLong()
            sumG[i] += g.toLong()
            sumB[i] += b.toLong()
            total++
        }
        if (total == 0) return emptyList()

        // 2. Each box's colour, chroma and hue, and how much of the picture each hue (degree) covers.
        val colours = ArrayList<Box>()
        val hueShare = DoubleArray(360)
        for (i in 0 until 4096) {
            val n = count[i]
            if (n == 0) continue
            val r = (sumR[i] / n).toInt()
            val g = (sumG[i] / n).toInt()
            val b = (sumB[i] / n).toInt()
            val lab = lab(r, g, b)
            val chroma = hypot(lab[1], lab[2])
            if (chroma < MIN_CHROMA) continue
            val hue = ((Math.toDegrees(atan2(lab[2], lab[1])) + 360.0) % 360.0).roundToInt() % 360
            hueShare[hue] += n.toDouble() / total
            colours += Box((0xFF shl 24) or (r shl 16) or (g shl 8) or b, chroma, hue)
        }

        // 3. Each hue's share together with its neighbours (14° either side), as Material's score has it.
        val near = DoubleArray(360) { h -> (h - 14..h + 14).sumOf { hueShare[(it + 360) % 360] } }

        // 4. Scored: how much of the picture the hue covers counts most, how colourful it is too.
        val scored = colours.filter { near[it.hue] > MIN_SHARE }
            .sortedByDescending { c ->
                near[c.hue] * 100 * 0.7 + (c.chroma - TARGET_CHROMA) * (if (c.chroma < TARGET_CHROMA) 0.1 else 0.3)
            }

        // 5. The best ones, as far apart in hue as still gives [max] of them (90° down to 15°).
        var chosen = ArrayList<Box>()
        for (apart in 90 downTo 15) {
            chosen = ArrayList()
            for (c in scored) {
                if (chosen.none { hueGap(it.hue, c.hue) < apart }) chosen += c
                if (chosen.size == max) break
            }
            if (chosen.size == max) break
        }
        return chosen.map { it.argb }
    }

    private class Box(val argb: Int, val chroma: Double, val hue: Int)

    /**
     * Swift's colours made from one of a picture's colours [seed], as Material You makes a phone's
     * colours from its wallpaper: the accent (the seed's hue, middle lightness and at least some
     * colour, so a pale or muted picture still gives an accent that shows), light mode's background
     * (nearly white, with a hint of the hue) and dark mode's (nearly black, with a hint of it).
     * Returned as [accent, light background, dark background]. Palette then makes the accent readable
     * on each, as it does for any colour the user picks.
     */
    fun mix(seed: Int): IntArray {
        val lab = lab((seed shr 16) and 0xFF, (seed shr 8) and 0xFF, seed and 0xFF)
        val chroma = hypot(lab[1], lab[2])
        val hue = Math.toDegrees(atan2(lab[2], lab[1]))
        return intArrayOf(
            fit(50.0, max(chroma, 42.0), hue),
            fit(97.0, min(chroma * 0.12, 4.5), hue),
            fit(8.0, min(chroma * 0.15, 6.0), hue),
        )
    }

    /** The colour of CIELAB lightness [l], chroma [c] and hue [hueDeg], with less chroma until the screen can show it. */
    internal fun fit(l: Double, c: Double, hueDeg: Double): Int {
        val rad = Math.toRadians(hueDeg)
        var chroma = c
        while (chroma > 0.0) {
            fromLab(l, chroma * kotlin.math.cos(rad), chroma * kotlin.math.sin(rad))?.let { return it }
            chroma -= 1.0
        }
        return fromLab(l, 0.0, 0.0) ?: 0xFF808080.toInt()
    }

    /** An sRGB colour from CIELAB, or null when the screen can't show it. */
    private fun fromLab(l: Double, a: Double, b: Double): Int? {
        fun finv(t: Double): Double {
            val t3 = t * t * t
            return if (t3 > 0.008856) t3 else (t - 16.0 / 116) / 7.787
        }
        fun gamma(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        val fy = (l + 16) / 116
        val x = 0.95047 * finv(fy + a / 500)
        val y = finv(fy)
        val z = 1.08883 * finv(fy - b / 200)
        val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
        val eps = 0.0005
        if (r < -eps || r > 1 + eps || g < -eps || g > 1 + eps || bl < -eps || bl > 1 + eps) return null
        fun ch(v: Double) = (gamma(v.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(bl)
    }

    /** How far apart two hues are (degrees, 0–180). */
    internal fun hueGap(a: Int, b: Int): Int {
        val d = abs(a - b) % 360
        return min(d, 360 - d)
    }

    /** CIELAB (L, a, b) of an sRGB colour given by its channels, without Android's Color (tested on the PC). */
    internal fun lab(r: Int, g: Int, b: Int): DoubleArray {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        val rl = lin(r)
        val gl = lin(g)
        val bl = lin(b)
        val x = (0.4124 * rl + 0.3576 * gl + 0.1805 * bl) / 0.95047
        val y = 0.2126 * rl + 0.7152 * gl + 0.0722 * bl
        val z = (0.0193 * rl + 0.1192 * gl + 0.9505 * bl) / 1.08883
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    // ---- Android's pictures ----

    /** The main colours of [bitmap] (scaled down to [SAMPLE] first; [bitmap] itself is left as it is). */
    fun seeds(bitmap: Bitmap, max: Int = 4): List<Int> {
        val small = small(bitmap)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bitmap) small.recycle()
        return seeds(px, max)
    }

    /** [b] at most [SAMPLE] pixels on its longest side ([b] itself when it already is). */
    fun small(b: Bitmap): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= SAMPLE) return b
        val s = SAMPLE.toFloat() / longest
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
    }

    /** [d] drawn into a new small picture ([SAMPLE] pixels at most), or null when it has no size. */
    fun small(d: Drawable): Bitmap? {
        if (d is BitmapDrawable) {
            val b = d.bitmap ?: return null
            val s = small(b)
            return if (s === b) b.copy(Bitmap.Config.ARGB_8888, false) else s
        }
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val s = min(1f, SAMPLE.toFloat() / max(w, h))
        val bw = max(1, (w * s).roundToInt())
        val bh = max(1, (h * s).roundToInt())
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            d.setBounds(0, 0, bw, bh)
            d.draw(c)
        }
    }

    /**
     * The phone's wallpaper as a small picture, or null: Android 7 lets any app read it (later
     * Androids want a permission, and a live wallpaper may give none). Android's full-size copy is
     * let go straight after. Slow-ish (it reads the wallpaper file): not on the main thread.
     */
    fun wallpaper(ctx: Context): Bitmap? = try {
        val wm = WallpaperManager.getInstance(ctx)
        val small = wm.drawable?.let { small(it) }
        wm.forgetLoadedWallpaper()
        small
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
