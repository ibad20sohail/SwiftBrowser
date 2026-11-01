package com.swiftbrowser.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One picture of the viewer: it fits the screen and zooms with a pinch or a double tap (in and
 * back), drags and flings when zoomed. While zoomed in it keeps the finger, so the pictures don't
 * change under a drag, until the picture's edge is reached; then a swipe goes on to the next one.
 *
 * 2.9 (ZoomImageView before): it draws the picture itself, at about the screen's size, and over it,
 * once a zoom settles, the part on screen in full detail ([setDetail]), so a big photo stays sharp
 * close up while only one screenful of full-size pixels is ever in memory. GIFs play ([play]) by
 * drawing each frame into one picture of the GIF's size.
 */
class PictureView(context: Context) : View(context) {
    var onTap: (() -> Unit)? = null

    /** The picture rests (no finger, no fling, no zoom moving): time to ask for detail. */
    var onSettled: ((PictureView) -> Unit)? = null

    /** The whole picture's size, upright, in its own pixels (0 until it is set). */
    var fullWidth = 0
        private set
    var fullHeight = 0
        private set

    /** Said in the middle when the picture couldn't be read. */
    var message: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var picture: Bitmap? = null
    private var detail: Bitmap? = null
    private val detailRect = RectF()

    /** Counts the requests for detail: an answer to an older one is dropped. */
    @Volatile
    var detailWanted = 0
        private set

    /** Picture pixels → the view. */
    private val m = Matrix()
    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private val bounds = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }

    /** The scale at which the whole picture fits. */
    private var fitScale = 1f
    private val scroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0
    private var zoomAnim: ValueAnimator? = null
    private var touching = false

    /** This drag went past the picture's edge: the pager may take it (to the next picture). */
    private var letGo = false

    val hasPicture: Boolean get() = picture != null

    /** Screen pixels per picture pixel. */
    val scale: Float
        get() {
            m.getValues(values)
            return values[Matrix.MSCALE_X]
        }

    val isZoomed: Boolean get() = picture != null && scale > fitScale * 1.01f

    // 2.9: close enough to see the photo's own pixels twice over, and always at least 4 times the fit.
    private val maxScale: Float get() = max(fitScale * 4f, 2f)

    /** Shows [bmp] (the whole picture, any size) as a picture of [width] × [height]; null clears it. */
    fun setPicture(bmp: Bitmap?, width: Int, height: Int) {
        if (bmp != null && bmp === picture && width == fullWidth && height == fullHeight) return
        stopMovie()
        picture = bmp
        fullWidth = width
        fullHeight = height
        if (bmp != null) message = null
        clearDetail()
        fit()
        invalidate()
    }

    /** Back to the whole picture, without detail (a page swiped away). */
    fun reset() {
        zoomAnim?.cancel()
        scroller.forceFinished(true)
        clearDetail()
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearDetail()
        fit()
        settleSoon()
    }

    /** The whole picture in the middle of the view. */
    private fun fit() {
        if (width == 0 || height == 0 || fullWidth <= 0 || fullHeight <= 0) return
        fitScale = min(width.toFloat() / fullWidth, height.toFloat() / fullHeight)
        m.setScale(fitScale, fitScale)
        m.postTranslate((width - fullWidth * fitScale) / 2f, (height - fullHeight * fitScale) / 2f)
        invalidate()
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val now = scale
        val target = (now * factor).coerceIn(fitScale, maxScale)
        val f = target / now
        m.postScale(f, f, fx, fy)
        keepInside()
    }

    /** No empty edge where the picture could still fill the view; centred when smaller than it. */
    private fun keepInside() {
        bounds.set(0f, 0f, fullWidth.toFloat(), fullHeight.toFloat())
        m.mapRect(bounds)
        val dx = when {
            bounds.width() <= width -> width / 2f - bounds.centerX()
            bounds.left > 0 -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> height / 2f - bounds.centerY()
            bounds.top > 0 -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        invalidate()
    }

    /** Zooms smoothly to [target] around a point, keeping that point under the finger. */
    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        val from = scale
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.NORMAL
            interpolator = Motion.standard
            addUpdateListener {
                // Even steps in zoom (not in size): it feels the same going in and coming back.
                val want = from * (target / from).pow(it.animatedValue as Float)
                zoomBy(want / scale, fx, fy)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (target <= fitScale) fit()
                    settleSoon()
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ detail (sharp zoom)

    private val settle = Runnable {
        if (!touching && scroller.isFinished && zoomAnim?.isRunning != true) onSettled?.invoke(this)
    }

    fun settleSoon() {
        removeCallbacks(settle)
        postDelayed(settle, 160)
    }

    /**
     * The part of the picture on screen (in its pixels), when the picture drawn there is coarser
     * than the screen and detail would show more; null when it is sharp as it is.
     */
    fun partWanted(): RectF? {
        val p = picture ?: return null
        if (width == 0 || fullWidth <= 0 || movie != null) return null
        val s = scale
        // Screen pixels per pixel of the picture drawn: above 1, the picture is stretched.
        if (s * fullWidth / p.width <= 1.1f) {
            clearDetail()
            return null
        }
        val inverse = Matrix()
        if (!m.invert(inverse)) return null
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(r)
        if (!r.intersect(0f, 0f, fullWidth.toFloat(), fullHeight.toFloat())) return null
        val d = detail
        if (d != null && detailRect.contains(r) && s * detailRect.width() / d.width <= 1.1f) return null
        detailWanted++
        return r
    }

    /** Detail for request [ticket]: [bmp] covers [rect] of the picture. Dropped when a newer request is on its way. */
    fun setDetail(bmp: Bitmap, rect: RectF, ticket: Int) {
        if (ticket != detailWanted || picture == null) return
        detail = bmp
        detailRect.set(rect)
        invalidate()
    }

    private fun clearDetail() {
        detailWanted++
        if (detail != null) {
            detail = null
            invalidate()
        }
    }

    // ------------------------------------------------------------------ GIFs

    @Suppress("DEPRECATION")
    private var movie: android.graphics.Movie? = null
    private var frameCanvas: Canvas? = null
    private var movieStart = 0L

    /** Draws the next frame at about 25 frames a second (GIFs rarely change faster). */
    private val tick = object : Runnable {
        override fun run() {
            drawFrame()
            postDelayed(this, 40)
        }
    }

    val isPlaying: Boolean get() = movie != null

    /** Plays the GIF [mv] (the same picture as shown, at its full size) in place of the still picture. */
    @Suppress("DEPRECATION")
    fun play(mv: android.graphics.Movie) {
        stopMovie()
        val frame = try {
            Bitmap.createBitmap(mv.width(), mv.height(), Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return
        }
        movie = mv
        frameCanvas = Canvas(frame)
        movieStart = SystemClock.uptimeMillis()
        picture = frame
        clearDetail()
        drawFrame()
        if (isAttachedToWindow && windowVisibility == VISIBLE) post(tick)
    }

    /** Stops a GIF on the frame it shows. */
    fun stopMovie() {
        removeCallbacks(tick)
        movie = null
        frameCanvas = null
    }

    @Suppress("DEPRECATION")
    private fun drawFrame() {
        val mv = movie ?: return
        val c = frameCanvas ?: return
        val length = mv.duration().coerceAtLeast(1)
        mv.setTime(((SystemClock.uptimeMillis() - movieStart) % length).toInt())
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        mv.draw(c, 0f, 0f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (movie != null) {
            removeCallbacks(tick)
            post(tick)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(settle)
        zoomAnim?.cancel()
        super.onDetachedFromWindow()
    }

    // A GIF stops while Swift is in the background (it costs the battery for nothing).
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(tick)
        if (visibility == VISIBLE && movie != null) post(tick)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val p = picture
        if (p == null) {
            message?.let { canvas.drawText(it, width / 2f, height / 2f, textPaint) }
            return
        }
        drawMatrix.set(m)
        drawMatrix.preScale(fullWidth.toFloat() / p.width, fullHeight.toFloat() / p.height)
        canvas.drawBitmap(p, drawMatrix, paint)
        val d = detail ?: return
        drawMatrix.set(m)
        drawMatrix.preTranslate(detailRect.left, detailRect.top)
        drawMatrix.preScale(detailRect.width() / d.width, detailRect.height() / d.height)
        canvas.drawBitmap(d, drawMatrix, paint)
    }

    // ------------------------------------------------------------------ touch

    /** This touch zoomed with the fingers (a pinch, or a double tap held and dragged). */
    private var scaled = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaled = true
            zoomAnim?.cancel()
            return picture != null
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (picture == null) return false
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent) = true

        // 2.9: the zoom comes when the second tap lifts, unless it was held and dragged: that zooms
        // with one finger (Android's own gesture) instead.
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked != MotionEvent.ACTION_UP || scaled || picture == null) return true
            if (isZoomed) animateZoom(fitScale, e.x, e.y) else animateZoom((fitScale * 2.5f).coerceAtMost(maxScale), e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed || scaler.isInProgress || e2.pointerCount > 1) return false
            bounds.set(0f, 0f, fullWidth.toFloat(), fullHeight.toFloat())
            m.mapRect(bounds)
            // 2.9: at the edge, a sideways drag further out goes to the next or previous picture.
            if (abs(dx) > abs(dy) && ((dx > 0 && bounds.right <= width + 1f) || (dx < 0 && bounds.left >= -1f))) letGo = true
            m.postTranslate(-dx, -dy)
            keepInside()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!isZoomed || letGo) return false
            bounds.set(0f, 0f, fullWidth.toFloat(), fullHeight.toFloat())
            m.mapRect(bounds)
            val minX = if (bounds.width() > width) (width - bounds.right).toInt() else 0
            val maxX = if (bounds.width() > width) (-bounds.left).toInt() else 0
            val minY = if (bounds.height() > height) (height - bounds.bottom).toInt() else 0
            val maxY = if (bounds.height() > height) (-bounds.top).toInt() else 0
            lastFlingX = 0
            lastFlingY = 0
            scroller.fling(0, 0, vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val x = scroller.currX
        val y = scroller.currY
        m.postTranslate((x - lastFlingX).toFloat(), (y - lastFlingY).toFloat())
        lastFlingX = x
        lastFlingY = y
        keepInside()
        if (scroller.isFinished) settleSoon() else postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                letGo = false
                scaled = false
                removeCallbacks(settle)
                scroller.forceFinished(true)
                zoomAnim?.cancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        // Zoomed in or pinching: the pager doesn't take the drag (unless it went past the edge).
        val hold = !letGo && (isZoomed || event.pointerCount > 1 || scaler.isInProgress)
        parent?.requestDisallowInterceptTouchEvent(hold)
        if (!touching && scroller.isFinished) settleSoon()
        return true
    }
}
