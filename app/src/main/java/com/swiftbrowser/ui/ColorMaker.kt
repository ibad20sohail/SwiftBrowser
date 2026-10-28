package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * The colour maker (2.5): any colour, made with a finger. The square sets how strong and how
 * light the colour is, the strip below it the hue. [onChange] hears each change while dragging.
 */
class ColorMaker(ctx: Context, initial: Int, private val onChange: (Int) -> Unit) : View(ctx) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    private val density = ctx.resources.displayMetrics.density
    private val gap = 14 * density
    private val stripH = 28 * density
    private val radius = 12 * density
    private val square = RectF()
    private val strip = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private var shownHue = -1f
    private var hueShader: Shader? = null
    private var darkShader: Shader? = null
    private var stripShader: Shader? = null
    private var dragging = 0

    val color: Int get() = Color.HSVToColor(hsv)

    init {
        contentDescription = "Colour maker"
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val squareH = (w * 0.55f).coerceAtMost(200 * density)
        setMeasuredDimension(w, (squareH + gap + stripH + 2 * radius).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val squareH = (w * 0.55f).coerceAtMost(200 * density)
        square.set(radius, radius, w - radius, radius + squareH)
        strip.set(radius, square.bottom + gap, w - radius, square.bottom + gap + stripH)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        stripShader = LinearGradient(strip.left, 0f, strip.right, 0f, hues, null, Shader.TileMode.CLAMP)
        darkShader = LinearGradient(0f, square.top, 0f, square.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        shownHue = -1f
    }

    override fun onDraw(c: Canvas) {
        if (hsv[0] != shownHue) {
            shownHue = hsv[0]
            hueShader = LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
        }
        // White to the pure hue across, then a layer darkening to black downwards (two layers: Android 7
        // can't combine two gradients in one shader when drawing with the GPU).
        val r = 12 * density
        paint.shader = hueShader
        c.drawRoundRect(square, r, r, paint)
        paint.shader = darkShader
        c.drawRoundRect(square, r, r, paint)
        paint.shader = stripShader
        c.drawRoundRect(strip, stripH / 2, stripH / 2, paint)
        paint.shader = null
        // Where the finger is: a ring filled with the colour.
        val x = square.left + hsv[1] * square.width()
        val y = square.top + (1 - hsv[2]) * square.height()
        thumb(c, x, y, color)
        thumb(c, strip.left + hsv[0] / 360f * strip.width(), strip.centerY(), Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    }

    private fun thumb(c: Canvas, x: Float, y: Float, fill: Int) {
        paint.color = fill
        c.drawCircle(x, y, radius - 2 * density, paint)
        ring.color = Color.WHITE
        c.drawCircle(x, y, radius - 2 * density, ring)
        ring.color = 0x40000000
        ring.strokeWidth = 1 * density
        c.drawCircle(x, y, radius, ring)
        ring.strokeWidth = 3 * density
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y >= strip.top - gap / 2) 2 else 1
                // The dialog doesn't scroll while a colour is being made.
                parent?.requestDisallowInterceptTouchEvent(true)
                move(e)
            }
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun move(e: MotionEvent) {
        if (dragging == 1) {
            hsv[1] = ((e.x - square.left) / square.width()).coerceIn(0f, 1f)
            hsv[2] = (1 - (e.y - square.top) / square.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            hsv[0] = ((e.x - strip.left) / strip.width() * 360f).coerceIn(0f, 359.9f)
        }
        invalidate()
        onChange(color)
    }
}
