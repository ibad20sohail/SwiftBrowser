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
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatSeekBar
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min

/**
 * 3.0.4: Swift's slider, in Google's 2025 look (Material 3 Expressive) on Android 7, whose own is a
 * thin line with a round knob: a thick rounded track, the part done in the accent and the rest in a
 * soft colour, and a slim upright handle with a small gap on either side, a little bigger while it
 * is dragged. Only the look is Swift's: it is still a SeekBar with its own track and handle
 * drawables, so touch, keys and screen readers work as they always have.
 *
 * [Style.WAVE] (the music screen): the part played is a gentle wave, as in Android's own media
 * player, that flows while the music plays ([waving]) and lies flat while it is paused or dragged.
 * Cheap: one path, made again only when the wave's height changes (flowing only moves it), and the
 * next frame is asked for only while the bar is drawn, never while it is hidden or Swift is out of
 * sight, nor once the wave lies still.
 */
class SwiftSlider(context: Context, style: Style) : AppCompatSeekBar(context) {
    /**
     * The sizes of each look (dp): the track's thickness (the wave's line), the handle's width and
     * height at rest, and the gap either side of it. The handle is three times the track's height or
     * more, as Material has it; the track is thinner than Material's 16 dp on this small screen.
     */
    enum class Style(val track: Float, val handleWidth: Float, val handleHeight: Float, val gap: Float) {
        /** Over a video: slim (6 dp), so it hides little of the picture and reads as the video's own. */
        VIDEO(6f, 4f, 18f, 4f),

        /** The music screen: the wave's line (4 dp) and the same handle as over a video (the bar keeps its height). */
        WAVE(4f, 4f, 18f, 4f),

        /** In a panel (the equalizer): thicker (12 dp), with a dot at the track's end; six fit in half the screen. */
        PANEL(12f, 4f, 30f, 5f),
    }

    private val density = resources.displayMetrics.density
    private val animations = Motion.enabled(context)

    /** The look; VIDEO and WAVE can be swapped (the same height), the player does as it changes between them. */
    var style: Style = style
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    private var activeColour = Color.WHITE
    private var inactiveColour = 0x40FFFFFF
    private var loadedColour = 0x66FFFFFF
    private var handleColour = Color.WHITE

    private val track = Track()
    private val handle = Handle()

    /** Drawn on the track, under the handle (the player's A-B marks); it gets the track's bounds. */
    var marks: Drawable? = null
        set(v) {
            field?.callback = null
            field = v
            v?.callback = track
            v?.bounds = track.bounds
            invalidate()
        }

    /** WAVE: the music plays and the bar is in sight: the wave flows. Otherwise it lies flat. */
    var waving = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    init {
        // No ripple round the handle (Material's slider has none): the handle itself answers the finger.
        background = null
        // The gap round the handle is drawn by the track (with rounded ends), not cut out by SeekBar.
        splitTrack = false
        progressDrawable = track
        thumb = handle
        // In a panel the track gets the room AppCompat's own 16 dp at each end would take.
        if (style == Style.PANEL) {
            val side = (8 * density + 0.5f).toInt()
            setPadding(side, 0, side, 0)
        }
    }

    /**
     * The colours: the part done ([active]), the rest ([inactive]), the handle, and the part of the
     * rest already loaded ([loaded], a website's video).
     */
    fun setColours(active: Int, inactive: Int, handle: Int = active, loaded: Int = inactive) {
        if (activeColour == active && inactiveColour == inactive && handleColour == handle && loadedColour == loaded) return
        activeColour = active
        inactiveColour = inactive
        handleColour = handle
        loadedColour = loaded
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The wave flows, or is still settling flat or rising: the next frame.
        if (style == Style.WAVE && track.moving) postInvalidateOnAnimation()
    }

    private fun fraction(value: Int): Float {
        val top = max
        return if (top <= 0) 0f else (value.toFloat() / top).coerceIn(0f, 1f)
    }

    /** [v] pixels rounded up to an even number, so the handle and the track share their middle. */
    private fun even(v: Float): Int = (ceil(v / 2f) * 2f).toInt()

    /** The handle: a slim upright bar with round ends, a little bigger while dragged (or chosen with keys). */
    private inner class Handle : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private var held = false
        private var enabled = true

        /** 0 at rest, 1 held. */
        private var grown = 0f
        private val grow = ValueAnimator().apply {
            duration = 120
            interpolator = Motion.standard
            addUpdateListener {
                grown = it.animatedValue as Float
                invalidateSelf()
            }
        }

        /** Half its width as drawn now (px): the gap is measured from it. */
        fun halfWidth(): Float = (style.handleWidth + GROW_WIDTH * grown) * density / 2

        override fun getIntrinsicWidth(): Int = even((style.handleWidth + GROW_WIDTH) * density)

        // The bar's height: the held handle and a little room (SeekBar is as high as its handle).
        override fun getIntrinsicHeight(): Int = even((style.handleHeight + GROW_HEIGHT + 1) * density)

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val nowHeld = android.R.attr.state_pressed in state || android.R.attr.state_focused in state
            val nowEnabled = android.R.attr.state_enabled in state
            val changed = nowHeld != held || nowEnabled != enabled
            enabled = nowEnabled
            if (nowHeld != held) {
                held = nowHeld
                val to = if (held) 1f else 0f
                grow.cancel()
                if (animations && isShown) {
                    grow.setFloatValues(grown, to)
                    grow.start()
                } else {
                    grown = to
                }
            }
            return changed
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val hw = halfWidth()
            val hh = (style.handleHeight + GROW_HEIGHT * grown) * density / 2
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            box.set(cx - hw, cy - hh, cx + hw, cy + hh)
            paint.color = handleColour
            paint.alpha = (Color.alpha(handleColour) * track.share).toInt()
            canvas.drawRoundRect(box, hw, hw, paint)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * The track: the part done up to the gap before the handle, the rest from the gap after it (and
     * how much of it is loaded), and [marks] on top. Its corners by the handle are only slightly
     * round, the far ends fully, as Material draws them.
     */
    private inner class Track : Drawable(), Drawable.Callback {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val shape = Path()
        private val radii = FloatArray(8)
        private val box = RectF()

        /** How opaque it is: SeekBar fades it while disabled (a live stream); the handle follows. */
        var share = 1f
            private set

        // The wave: its path (made for [madeWidth] and [madeHeight]), how high it is now (0 flat … 1),
        // where that is heading, and how far it has flowed.
        private val wave = Path()
        private var madeWidth = -1
        private var madeHeight = -1f
        private var amount = 0f
        private var amountFrom = 0f
        private var amountTo = 0f
        private var amountAt = 0L
        private var phase = 0f
        private var lastFrame = 0L

        /** WAVE: it flows or changes height, so another frame is wanted. */
        var moving = false
            private set

        override fun getIntrinsicHeight(): Int = handle.intrinsicHeight

        override fun onLevelChange(level: Int): Boolean = true

        override fun onBoundsChange(bounds: Rect) {
            marks?.bounds = bounds
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width()
            if (w <= 0) {
                moving = false
                return
            }
            // Where SeekBar puts the handle's middle (to the pixel, as it does), so the gaps are even.
            val x = b.left + (fraction(progress) * w + 0.5f).toInt().toFloat()
            val cut = handle.halfWidth() + style.gap * density
            val loaded = b.left + fraction(secondaryProgress) * w
            if (style == Style.WAVE) drawWave(canvas, b, x, cut, loaded) else drawFlat(canvas, b, x, cut, loaded)
            marks?.draw(canvas)
        }

        private fun drawFlat(c: Canvas, b: Rect, x: Float, cut: Float, loaded: Float) {
            moving = false
            val h = style.track * density
            val cy = b.exactCenterY()
            val outer = h / 2
            val inner = min(SliderMaths.INNER_CORNER * density, outer)
            val right = b.right.toFloat()
            val restStart = x + cut
            bar(c, restStart, right, cy, h, inner, outer, inactiveColour)
            if (loaded > restStart && loadedColour != inactiveColour) {
                bar(c, restStart, min(loaded, right), cy, h, inner, if (loaded >= right - 0.5f) outer else inner, loadedColour)
            }
            bar(c, b.left.toFloat(), x - cut, cy, h, outer, inner, activeColour)
            // PANEL: a dot at the end of the track (Material's stop indicator), while the handle is away from it.
            if (style == Style.PANEL && restStart < right - h) {
                paint(fill, activeColour)
                c.drawCircle(right - outer, cy, min(SliderMaths.STOP_DOT * density, h / 4), fill)
            }
        }

        /** A piece of the track from [l] to [r], its corners [start] at its start and [end] at its end. */
        private fun bar(c: Canvas, l: Float, r: Float, cy: Float, h: Float, start: Float, end: Float, colour: Int) {
            if (r - l < 1f) return
            val s = min(start, (r - l) / 2)
            val e = min(end, (r - l) / 2)
            radii[0] = s
            radii[1] = s
            radii[2] = e
            radii[3] = e
            radii[4] = e
            radii[5] = e
            radii[6] = s
            radii[7] = s
            box.set(l, cy - h / 2, r, cy + h / 2)
            shape.rewind()
            shape.addRoundRect(box, radii, Path.Direction.CW)
            paint(fill, colour)
            c.drawPath(shape, fill)
        }

        /** A straight line from [l] to [r] with round ends. */
        private fun straight(c: Canvas, l: Float, r: Float, cy: Float, h: Float, colour: Int) {
            if (r - l < 1f) return
            paint(fill, colour)
            c.drawRoundRect(l, cy - h / 2, r, cy + h / 2, h / 2, h / 2, fill)
        }

        private fun drawWave(c: Canvas, b: Rect, x: Float, cut: Float, loaded: Float) {
            val now = AnimationUtils.currentAnimationTimeMillis()
            val h = style.track * density
            val cy = b.exactCenterY()
            val left = b.left.toFloat()
            val right = b.right.toFloat()
            val restStart = x + cut
            straight(c, restStart, right, cy, h, inactiveColour)
            if (loaded > restStart && loadedColour != inactiveColour) straight(c, restStart, min(loaded, right), cy, h, loadedColour)

            // How high the wave is: rising while it plays, flat while paused or dragged (in a moment).
            val target = if (waving && !isPressed) 1f else 0f
            if (target != amountTo) {
                amountFrom = amount
                amountTo = target
                amountAt = now
            }
            amount = if (!animations) {
                target
            } else {
                val t = ((now - amountAt).toFloat() / SliderMaths.FLATTEN_MS).coerceIn(0f, 1f)
                amountFrom + (amountTo - amountFrom) * Motion.standard.getInterpolation(t)
            }
            val flows = animations && amount > 0f && target > 0f
            val length = SliderMaths.WAVELENGTH * density
            if (flows) {
                val dt = if (lastFrame == 0L) 0L else (now - lastFrame).coerceIn(0L, 50L)
                phase = (phase + dt * SliderMaths.SPEED * density / 1000f) % length
                lastFrame = now
            } else {
                lastFrame = 0L
            }
            moving = flows || amount != amountTo

            // The part played: a line from the start to the gap, round at both ends.
            val from = left + h / 2
            val to = x - cut - h / 2
            if (to < from) return
            val amp = amount * SliderMaths.WAVE_HEIGHT * density
            if (amp < 0.3f) {
                straight(c, left, x - cut, cy, h, activeColour)
                return
            }
            makeWave(b.width(), amp, length)
            line.strokeWidth = h
            paint(line, activeColour)
            c.save()
            c.clipRect(from, cy - amp - h, to, cy + amp + h)
            c.translate(left + phase, cy)
            c.drawPath(wave, line)
            c.restore()
            // The clip cuts the line square: round ends where it starts and stops.
            paint(fill, activeColour)
            c.drawCircle(from, cy + SliderMaths.waveY(from - left - phase, amp, length), h / 2, fill)
            c.drawCircle(to, cy + SliderMaths.waveY(to - left - phase, amp, length), h / 2, fill)
        }

        /** The wave of height [amp], from two wavelengths before the start to one past the end (it flows by up to one). */
        private fun makeWave(width: Int, amp: Float, length: Float) {
            if (width == madeWidth && amp == madeHeight) return
            madeWidth = width
            madeHeight = amp
            val half = length / 2
            val k = SliderMaths.CONTROL * half
            wave.rewind()
            var x = -2 * length
            var y = amp
            wave.moveTo(x, y)
            val end = width + length
            while (x < end) {
                val nx = x + half
                val ny = -y
                wave.cubicTo(x + k, y, nx - k, ny, nx, ny)
                x = nx
                y = ny
            }
        }

        private fun paint(p: Paint, colour: Int) {
            p.color = colour
            if (share < 1f) p.alpha = (Color.alpha(colour) * share).toInt()
        }

        override fun setAlpha(alpha: Int) {
            share = alpha / 255f
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            line.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        // The marks' own changes redraw the track.
        override fun invalidateDrawable(who: Drawable) = invalidateSelf()

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    private companion object {
        /** How much wider and taller (dp) the handle is while held. */
        const val GROW_WIDTH = 1f
        const val GROW_HEIGHT = 5f
    }
}

/** 3.0.4: the slider's sizes and its wave, as plain maths (tested on the PC, SliderMathsTest). */
internal object SliderMaths {
    /** The track's corners beside the handle (dp), as Material's: only slightly round. */
    const val INNER_CORNER = 2f

    /** The dot at the end of a panel's track (its radius, dp). */
    const val STOP_DOT = 2f

    /** The music screen's wave: its length and height (dp), as gentle as Android's own media player's. */
    const val WAVELENGTH = 32f
    const val WAVE_HEIGHT = 3f

    /** How fast it flows (dp a second): half a wave a second, slow enough to calm rather than busy. */
    const val SPEED = 16f

    /** How long it takes to lie flat or rise again (ms). */
    const val FLATTEN_MS = 200f

    /**
     * Where the control points of each half wave's curve sit (a share of the half wave): this makes
     * the curve follow a cosine to within a 2000th of its height, so [waveY] is where the line is.
     */
    const val CONTROL = 0.3642f

    /** The wave's height at [x] (px from its start, flow included): crests every [length], [amp] high. */
    fun waveY(x: Float, amp: Float, length: Float): Float = (amp * cos(2 * PI * x / length)).toFloat()
}
