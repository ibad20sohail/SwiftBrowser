package com.swiftbrowser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 3.0.4: the play button's icon: Material Symbols Rounded's play triangle or pause bars (filled),
 * changing with a short morph instead of swapping at once: the triangle parts in two and the parts
 * square up into the bars, rounding their corners as they go (and back). Set without the morph it is
 * simply right ([show] with animate false: as the screen comes back, or while it isn't seen). The
 * path is made at the screen's own size, again only while it changes.
 */
class PlayPauseIcon(ctx: Context) : Drawable() {
    private val size = Ui.dp(ctx, 24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val corners = FloatArray(PlayPauseShape.SIZE)
    private val outline = FloatArray(PlayPauseShape.SIZE * 2)
    private val madeFor = Rect()
    private var madeAt = Float.NaN

    /** 0 play … 1 pause, as drawn now. */
    private var shown = 0f

    /** Pause is shown, or on its way. */
    var showsPause = false
        private set

    private val morph = ValueAnimator().apply {
        interpolator = Motion.standard
        addUpdateListener {
            shown = it.animatedValue as Float
            invalidateSelf()
        }
    }

    var colour: Int
        get() = paint.color
        set(v) {
            if (paint.color == v) return
            paint.color = v
            invalidateSelf()
        }

    /** Pause ([pause]) or play; with [animate], morphing there (a change of mind midway turns back from where it is). */
    fun show(pause: Boolean, animate: Boolean) {
        val to = if (pause) 1f else 0f
        if (pause == showsPause && (morph.isRunning || shown == to)) return
        showsPause = pause
        morph.cancel()
        if (!animate) {
            shown = to
            invalidateSelf()
            return
        }
        morph.setFloatValues(shown, to)
        morph.duration = (MORPH_MS * abs(to - shown)).toLong().coerceAtLeast(1L)
        morph.start()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (shown != madeAt || b != madeFor) {
            madeAt = shown
            madeFor.set(b)
            PlayPauseShape.at(shown, corners)
            PlayPauseShape.round(corners, outline)
            val sx = b.width() / PlayPauseShape.GRID
            val sy = b.height() / PlayPauseShape.GRID
            val k = PlayPauseShape.PULL
            path.rewind()
            for (part in 0 until 2) {
                val o = part * 24
                // From the end of the first corner's curve, along each side and round each corner.
                path.moveTo(b.left + outline[o + 4] * sx, b.top + outline[o + 5] * sy)
                for (n in 1..4) {
                    val i = o + (n % 4) * 6
                    val ax = outline[i]
                    val ay = outline[i + 1]
                    val px = outline[i + 2]
                    val py = outline[i + 3]
                    val bx = outline[i + 4]
                    val by = outline[i + 5]
                    path.lineTo(b.left + ax * sx, b.top + ay * sy)
                    path.cubicTo(
                        b.left + (ax + k * (px - ax)) * sx, b.top + (ay + k * (py - ay)) * sy,
                        b.left + (bx + k * (px - bx)) * sx, b.top + (by + k * (py - by)) * sy,
                        b.left + bx * sx, b.top + by * sy,
                    )
                }
                path.close()
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicWidth(): Int = size

    override fun getIntrinsicHeight(): Int = size

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** The morph from one to the other (ms): short, as the button answers a tap. */
        const val MORPH_MS = 200f
    }
}

/**
 * 3.0.4: the play and pause shapes for [PlayPauseIcon], as plain maths (tested on the PC,
 * PlayPauseShapeTest), on Material Symbols' 960 grid (y down). Each shape is two parts of four
 * corners: x, y and how far along its sides the corner's rounding starts. Play is the triangle cut
 * in two at x 540 (the parts meet there with sharp corners, so they look as one), its tip a short
 * flat between two small roundings; pause is two bars with round ends.
 */
internal object PlayPauseShape {
    const val GRID = 960f

    /** Floats in a shape: 2 parts × 4 corners × (x, y, rounding). */
    const val SIZE = 24

    /** How far a corner's curve pulls toward the corner: 0.55 makes a square corner a quarter circle. */
    const val PULL = 0.55f

    val PLAY = floatArrayOf(
        320f, 200.3f, 72f, 540f, 340f, 0f, 540f, 620f, 0f, 320f, 759.7f, 72f,
        540f, 340f, 0f, 725.9f, 458f, 22f, 725.9f, 502f, 22f, 540f, 620f, 0f,
    )

    val PAUSE = floatArrayOf(
        240f, 200f, 80f, 400f, 200f, 80f, 400f, 760f, 80f, 240f, 760f, 80f,
        560f, 200f, 80f, 720f, 200f, 80f, 720f, 760f, 80f, 560f, 760f, 80f,
    )

    /** The corners [t] of the way from play (0) to pause (1), into [out] ([SIZE] floats). */
    fun at(t: Float, out: FloatArray) {
        for (i in 0 until SIZE) out[i] = PLAY[i] + (PAUSE[i] - PLAY[i]) * t
    }

    /**
     * Each corner of [corners] as where its rounding starts (A, on the side from the corner before),
     * the corner itself (P) and where it ends (B, on the side to the next): Ax, Ay, Px, Py, Bx, By,
     * into [out] (2 × [SIZE] floats). A rounding never reaches past half of either side.
     */
    fun round(corners: FloatArray, out: FloatArray) {
        for (part in 0 until 2) {
            for (c in 0 until 4) {
                val i = (part * 4 + c) * 3
                val prev = (part * 4 + (c + 3) % 4) * 3
                val next = (part * 4 + (c + 1) % 4) * 3
                val px = corners[i]
                val py = corners[i + 1]
                val la = hypot(corners[prev] - px, corners[prev + 1] - py)
                val lb = hypot(corners[next] - px, corners[next + 1] - py)
                val d = minOf(corners[i + 2], la / 2, lb / 2)
                val o = (part * 4 + c) * 6
                out[o] = if (la > 0f) px + (corners[prev] - px) / la * d else px
                out[o + 1] = if (la > 0f) py + (corners[prev + 1] - py) / la * d else py
                out[o + 2] = px
                out[o + 3] = py
                out[o + 4] = if (lb > 0f) px + (corners[next] - px) / lb * d else px
                out[o + 5] = if (lb > 0f) py + (corners[next + 1] - py) / lb * d else py
            }
        }
    }
}
