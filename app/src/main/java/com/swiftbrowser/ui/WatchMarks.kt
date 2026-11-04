package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.swiftbrowser.R

/**
 * 3.0.3: what a video's picture in Files says about it: a thin line in the accent's colour along its
 * bottom for how much of it was watched in Swift's player, and a small tick once it was watched to its
 * end. Drawn over the picture (its foreground): no views of its own, nothing to lay out, no shadow.
 * [tile]: on a tile of the grid (bigger, its tick in the top-start corner), else on a row's picture.
 */
class WatchMarks(ctx: Context, private val tile: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Path()
    private val accent = Ui.accent(ctx)
    private val onAccent = Ui.onAccent(ctx)
    private val ring = Palette.color(ctx, R.color.page_bg)
    private val line = Ui.dpf(ctx, 3f)
    private val radius = Ui.dpf(ctx, if (tile) 11f else 8f)
    private val inset = Ui.dpf(ctx, if (tile) 6f else 2f)
    private val stroke = Ui.dpf(ctx, if (tile) 2f else 1.5f)
    private val edge = Ui.dpf(ctx, 1.5f)

    /** How much was watched (0..1); below 0, no line. */
    private var share = -1f
    private var finished = false

    /** Shows [share] of it watched (null: nothing kept) and whether it was [finished]; false when there is nothing to draw. */
    fun set(share: Float?, finished: Boolean): Boolean {
        val s = share ?: -1f
        if (s != this.share || finished != this.finished) {
            this.share = s
            this.finished = finished
            invalidateSelf()
        }
        return s >= 0f || finished
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (share >= 0f) {
            val top = b.bottom - line
            paint.style = Paint.Style.FILL
            paint.color = TRACK
            c.drawRect(b.left.toFloat(), top, b.right.toFloat(), b.bottom.toFloat(), paint)
            paint.color = accent
            // A little of it shows even at the very start, as on Continue's cards.
            c.drawRect(b.left.toFloat(), top, b.left + b.width() * share.coerceIn(0.04f, 1f), b.bottom.toFloat(), paint)
        }
        if (finished) {
            // A tile's other corners have its play mark and, while selecting, its tick box.
            val cx = if (tile) b.left + inset + radius else b.right - inset - radius
            val cy = if (tile) b.top + inset + radius else b.bottom - inset - radius
            paint.style = Paint.Style.FILL
            paint.color = ring
            c.drawCircle(cx, cy, radius + edge, paint)
            paint.color = accent
            c.drawCircle(cx, cy, radius, paint)
            tick.rewind()
            tick.moveTo(cx - radius * 0.42f, cy + radius * 0.02f)
            tick.lineTo(cx - radius * 0.12f, cy + radius * 0.32f)
            tick.lineTo(cx + radius * 0.44f, cy - radius * 0.3f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = onAccent
            c.drawPath(tick, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** The line's track (what is still to watch): a shade over any picture. */
        const val TRACK = 0x59000000
    }
}
