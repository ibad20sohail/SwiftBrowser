package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3.0.4: Swift's loading indicator, in place of Android 7's spinning arc: Google's 2025 one
 * (Material 3 Expressive's loading indicator). A soft shape changes form while it slowly turns (a
 * circle, a scalloped cookie, a soft five-pointed star, a rounded pentagon, an oval, a sun, a
 * four-leaf clover, and round again), in the accent (white over a video), on a round [container]
 * if one is given.
 *
 * Light on a slow phone: the shapes' outlines are worked out once ([LoadingShapes]); a frame only
 * blends two of them into one reused Path, drawn at the screen's own size (not scaled). It moves only
 * while it is drawn: each frame asks for the next from onDraw, which isn't called while it is hidden
 * or Swift is out of sight. With Android's animations off it is a still shape.
 */
class LoadingIndicator @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.accent(context) }
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val points = FloatArray(LoadingShapes.POINTS * 2)
    private var startedAt = 0L
    private var animations = true

    /** The shape's colour (the accent unless set). */
    var colour: Int
        get() = shapePaint.color
        set(v) {
            if (shapePaint.color == v) return
            shapePaint.color = v
            invalidate()
        }

    /** A round container behind the shape (0: none), for where the shape alone wouldn't read (over a video). */
    var container: Int = 0
        set(v) {
            if (field == v) return
            field = v
            containerPaint.color = v
            invalidate()
        }

    init {
        // Read as loading, as Android's spinner was.
        if (contentDescription == null) contentDescription = "Loading"
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.ProgressBar::class.java.name

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As big as Android's spinner (48 dp) unless the layout says otherwise.
        val size = (DEFAULT_DP * density + 0.5f).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animations = Motion.enabled(context)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Shown again: from where its shapes had got to (the next frame asks for more).
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val side = minOf(w, h).toFloat()
        if (side <= 0f) return
        val cx = paddingLeft + w / 2f
        val cy = paddingTop + h / 2f
        if (container != 0) canvas.drawCircle(cx, cy, side / 2, containerPaint)
        val radius = side / 2 * if (container != 0) LoadingShapes.CONTAINED else LoadingShapes.FREE
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (startedAt == 0L) startedAt = now
        LoadingShapes.outline(if (animations) now - startedAt else LoadingShapes.STILL_MS, points)
        // Smooth through the points: a curve from each point's middle to the next one's.
        path.rewind()
        val n = LoadingShapes.POINTS
        var px = cx + points[2 * (n - 1)] * radius
        var py = cy + points[2 * (n - 1) + 1] * radius
        val x0 = cx + points[0] * radius
        val y0 = cy + points[1] * radius
        path.moveTo((px + x0) / 2, (py + y0) / 2)
        for (i in 0 until n) {
            px = cx + points[2 * i] * radius
            py = cy + points[2 * i + 1] * radius
            val j = (i + 1) % n
            val nx = cx + points[2 * j] * radius
            val ny = cy + points[2 * j + 1] * radius
            path.quadTo(px, py, (px + nx) / 2, (py + ny) / 2)
        }
        path.close()
        canvas.drawPath(path, shapePaint)
        if (animations) postInvalidateOnAnimation()
    }

    private companion object {
        const val DEFAULT_DP = 48f
    }
}

/**
 * 3.0.4: the loading indicator's shapes and how they move, as plain maths (tested on the PC,
 * LoadingShapesTest). Each shape is its distance from the middle at [POINTS] angles round it, 1 at
 * its widest; a moment blends two of them, as far as a soft spring has got, turned as far as the
 * slow turn and each change of shape (a little twist) have taken it.
 */
internal object LoadingShapes {
    /** Points round a shape: plenty for a smooth outline at 48 dp, few enough for every frame. */
    const val POINTS = 96

    /** One change of shape (Material's own are 650 ms; a touch calmer here). */
    const val MORPH_MS = 700L

    /** One slow turn. */
    const val TURN_MS = 6000L

    /** How far each change of shape turns it besides (degrees). */
    const val TWIST = 45.0

    /** The shape's size in the box: alone (38 of 48 dp, as Material's), and inside a container. */
    const val FREE = 38f / 48f
    const val CONTAINED = 0.7f

    /** The moment shown with Android's animations off: the cookie, still. */
    const val STILL_MS = MORPH_MS

    /** Each change of shape is a spring: about this much of a bounce, settled well within [MORPH_MS]. */
    private const val STIFFNESS = 200.0
    private const val DAMPING = 0.7

    /** Their sizes on screen evened out: as large as a circle of this share of the box, none wider than the box. */
    private const val EVEN = 0.9

    private val angles = DoubleArray(POINTS) { 2 * PI * it / POINTS }
    private val cosines = FloatArray(POINTS) { cos(angles[it]).toFloat() }
    private val sines = FloatArray(POINTS) { sin(angles[it]).toFloat() }

    /** The shapes in the order they come; the last turns back into the first. */
    val shapes: List<FloatArray> = listOf(
        // A circle.
        shape { 1.0 },
        // A cookie: nine soft scallops.
        shape { 1 + 0.075 * cos(9 * it) },
        // A soft five-pointed star: rounded points, wide hollows.
        shape { 0.58 + 0.42 * ((1 + cos(5 * it)) / 2).pow(1.4) },
        // A pentagon with round corners (its outline smoothed).
        smooth(shape { cos(PI / 5) / cos((it % (2 * PI / 5)) - PI / 5) }, 3),
        // An oval, standing.
        shape { 1 / sqrt(0.72 * 0.72 * cos(it) * cos(it) + sin(it) * sin(it)) },
        // A sun: eight soft rays.
        shape { 1 + 0.08 * cos(8 * it) },
        // A four-leaf clover.
        shape { 1 + 0.15 * cos(4 * it) },
    ).map { even(it) }

    private fun shape(r: (Double) -> Double): FloatArray = FloatArray(POINTS) { r(angles[it]).toFloat() }

    /** Rounds a shape's corners: each distance the average of its neighbours' ([k] each side). */
    private fun smooth(r: FloatArray, k: Int): FloatArray =
        FloatArray(r.size) { i -> (-k..k).sumOf { r[(i + it + r.size) % r.size].toDouble() }.toFloat() / (2 * k + 1) }

    /** Scaled to look about as large as the others: the area of a circle of [EVEN], but never wider than 1. */
    private fun even(r: FloatArray): FloatArray {
        val rms = sqrt(r.sumOf { it.toDouble() * it } / r.size)
        val widest = r.max().toDouble()
        val s = minOf(EVEN / rms, 1 / widest).toFloat()
        return FloatArray(r.size) { r[it] * s }
    }

    /** How far a change of shape has got after [fraction] of [MORPH_MS]: 0 to 1, a touch past it and back. */
    fun spring(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        val t = fraction.coerceAtMost(1f) * MORPH_MS / 1000.0
        val w = sqrt(STIFFNESS)
        val wd = w * sqrt(1 - DAMPING * DAMPING)
        val decay = exp(-DAMPING * w * t)
        return (1 - decay * (cos(wd * t) + DAMPING * w / wd * sin(wd * t))).toFloat()
    }

    /**
     * The outline at [ms] since it started, into [out]: x, y of each point round it, for a shape of
     * radius 1 around (0, 0) (y down). The first point is at the top.
     */
    fun outline(ms: Long, out: FloatArray) {
        val t = ms.coerceAtLeast(0L)
        val step = t / MORPH_MS
        val m = spring((t % MORPH_MS).toFloat() / MORPH_MS)
        val from = shapes[(step % shapes.size).toInt()]
        val to = shapes[((step + 1) % shapes.size).toInt()]
        val turn = 2 * PI * (t % TURN_MS) / TURN_MS + Math.toRadians(TWIST * ((step % 8) + m))
        val c = cos(turn).toFloat()
        val s = sin(turn).toFloat()
        for (i in 0 until POINTS) {
            val r = from[i] + (to[i] - from[i]) * m
            // The angle i round from the top, turned by [turn]: (sin, -cos) of their sum.
            val sa = sines[i] * c + cosines[i] * s
            val ca = cosines[i] * c - sines[i] * s
            out[2 * i] = r * sa
            out[2 * i + 1] = -r * ca
        }
    }
}
