package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.swiftbrowser.files.StorageKinds
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 3.0.4: how full a storage is on Files' start page: a bar split into what fills it (pictures,
 * videos, music, documents, the rest) with what is free at the end, each part a thin rounded bar of
 * its own colour, a little apart. Until the split is known it has two parts, used and free, as
 * before. Drawn from widths worked out when what it shows or its size changes, never while drawing.
 */
class StorageBar(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val gap = Ui.dp(ctx, 2)
    private var parts = LongArray(0)
    private var colors = IntArray(0)
    private var widths = IntArray(0)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows [parts] (bytes, in order) in [colors]; the same again changes nothing. */
    fun show(parts: LongArray, colors: IntArray) {
        if (parts.contentEquals(this.parts) && colors.contentEquals(this.colors)) return
        this.parts = parts
        this.colors = colors
        measureParts()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureParts()
    }

    private fun measureParts() {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        // A part too small to see is a dot as wide as the bar is high.
        widths = if (w > 0 && h > 0) StorageKinds.widths(parts, w, gap, h) else IntArray(0)
    }

    override fun onDraw(canvas: Canvas) {
        if (widths.size != parts.size) return
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        // Thin bars stay fully round.
        val r = (bottom - top) / 2f
        var x = paddingLeft.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in parts.indices) {
            val w = widths[i]
            if (w <= 0) continue
            if (rtl) rect.set(width - x - w, top, width - x, bottom) else rect.set(x, top, x + w, bottom)
            paint.color = colors.getOrElse(i) { 0 }
            canvas.drawRoundRect(rect, r, r, paint)
            x += w + gap
        }
    }
}

/**
 * 3.0.4: the storage bar's colours, which go with the accent chosen (no rainbow): pictures in the
 * accent itself; videos, music and documents in its hue turned a quarter of the way round the colour
 * wheel each, as light as the accent and a little softer; the rest a neutral grey. Each reads on the
 * card (3:1, as a part of a chart should), in light and dark mode and on any background; an accent
 * with hardly any hue (a grey of your own) starts from a blue. Worked out in OKLab, whose hues look
 * evenly spaced; plain maths on ARGB numbers, tested on the PC (StorageColoursTest).
 */
internal object StorageColours {
    /** Below this chroma a colour has no hue to speak of. */
    private const val GREY = 0.04

    /** The least chroma of the kinds' colours, and the most of the three made from the accent. */
    private const val SOFT = 0.09
    private const val STRONG = 0.12

    /** The hue (degrees) a grey accent's colours start from: a blue. */
    private const val BLUE = 255.0

    /** How far round the colour wheel each kind is from the one before. */
    private const val TURN = 90.0

    /** What a part of a chart needs against what is around it (WCAG's 3:1 for graphics). */
    private const val GRAPHIC = 3.0

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Pictures, videos, music and documents for [accent] (the accent as on screen) on [card]. */
    fun kinds(accent: Int, card: Int, night: Boolean): IntArray {
        val lab = oklab(accent)
        val chroma = hypot(lab[1], lab[2])
        val grey = chroma < GREY
        val hue = if (grey) BLUE else Math.toDegrees(atan2(lab[2], lab[1]))
        // As light as the accent, within what reads well in this mode.
        val low = if (night) 0.70 else 0.45
        val high = if (night) 0.84 else 0.60
        val tone = lab[0].coerceIn(low, high)
        val bgs = listOf(card)
        val toward = if (night) WHITE else BLACK
        return IntArray(4) { i ->
            val c = if (i == 0 && !grey && chroma >= SOFT && lab[0] in low..high) {
                accent or BLACK
            } else {
                lch(tone, if (i == 0) chroma.coerceAtLeast(SOFT) else chroma.coerceIn(SOFT, STRONG), hue + TURN * i)
            }
            Contrast.readable(c, bgs, GRAPHIC, toward)
        }
    }

    /** The rest of what is used: a neutral grey that reads on [card]. */
    fun other(card: Int, night: Boolean): Int =
        Contrast.readable(if (night) 0xFF969696.toInt() else 0xFF8B8B8B.toInt(), listOf(card), GRAPHIC, if (night) WHITE else BLACK)

    // ---- OKLab (Björn Ottosson's), on plain ARGB numbers

    private fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

    /** L (0 black … 1 white), a and b of [c]. */
    internal fun oklab(c: Int): DoubleArray {
        val r = lin((c shr 16) and 0xFF)
        val g = lin((c shr 8) and 0xFF)
        val b = lin(c and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    /** The colour, or null when the screen can't show it. */
    private fun fromOklab(lightness: Double, a: Double, b: Double): Int? {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        val eps = 0.0005
        if (r < -eps || r > 1 + eps || g < -eps || g > 1 + eps || bl < -eps || bl > 1 + eps) return null
        fun ch(v: Double) = (gamma(v.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
        return BLACK or (ch(r) shl 16) or (ch(g) shl 8) or ch(bl)
    }

    /** Lightness [lightness], hue [hue] (degrees), and as much of [chroma] as the screen can show there. */
    private fun lch(lightness: Double, chroma: Double, hue: Double): Int {
        val rad = Math.toRadians(hue)
        val a = chroma * cos(rad)
        val b = chroma * sin(rad)
        fromOklab(lightness, a, b)?.let { return it }
        var lo = 0.0
        var hi = 1.0
        var best = fromOklab(lightness, 0.0, 0.0) ?: if (lightness > 0.5) WHITE else BLACK
        repeat(18) {
            val mid = (lo + hi) / 2
            val c = fromOklab(lightness, a * mid, b * mid)
            if (c != null) {
                best = c
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }
}
