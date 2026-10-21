package com.swiftbrowser.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Wraps the page.
 *  - Pull to refresh: dragging down at the top of a page reloads it, as in Chrome.
 *  - Swiping in from the left edge goes back, from the right edge forward.
 *  - Full-screen video: double-tap the left or right half to skip back or forward 10 seconds (more
 *    taps skip more), hold a finger down for double speed, and (if chosen) swipe up and down on
 *    the left half for brightness, on the right half for volume. Single taps and other gestures
 *    still reach the page, so the site's own player controls keep working.
 */
class GestureFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onSwipe(leftSide: Boolean, delta: Float)
        fun onSwipeEnd()
        fun onDoubleTap(leftSide: Boolean)
        fun onHold(start: Boolean)
    }

    interface EdgeListener {
        /** Whether a swipe from this edge would do anything now. */
        fun canEdgeSwipe(fromLeft: Boolean): Boolean
        fun onEdgeSwipe(fromLeft: Boolean, distance: Float)
        fun onEdgeSwipeEnd(fromLeft: Boolean, distance: Float)
    }

    interface PullListener {
        /** Whether a pull may start now (page at the top, toolbar shown, ...). */
        fun canPull(): Boolean
        fun onPull(distance: Float)
        fun onPullEnd(distance: Float)
    }

    /** A full-screen video is shown: its gestures apply instead of the page ones. */
    var videoMode = false
        private set
    private var swipeGestures = false
    private var tapGestures = false
    var listener: Listener? = null
    var pullListener: PullListener? = null
    var edgeListener: EdgeListener? = null
    var page: SwiftGeckoView? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false
    private var leftSide = false

    private var pullCandidate = false
    private var pulling = false
    private var pullStartY = 0f

    /** Only the outermost strip of the page starts an edge swipe, so pages keep their own swipes. */
    private val edgeWidth = 20 * resources.displayMetrics.density
    private var edgeCandidate = false
    private var edgeFromLeft = false
    private var edgeDragging = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    /** Turns the full-screen video gestures on or off. */
    fun setVideoMode(on: Boolean, swipes: Boolean, taps: Boolean) {
        if (!on) endVideoGestures()
        videoMode = on
        swipeGestures = on && swipes
        tapGestures = on && taps
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (videoMode) return interceptVideoGesture(ev)
        if (interceptEdge(ev)) return true
        return interceptPull(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (edgeDragging) return handleEdge(ev)
        if (pulling) return handlePull(ev)
        if (videoTap) return handleVideoTap(ev)
        if (holding) return handleHold(ev)
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val delta = (lastY - ev.y) / height.coerceAtLeast(1)
                lastY = ev.y
                listener?.onSwipe(leftSide, delta)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                listener?.onSwipeEnd()
            }
        }
        return true
    }

    // ------------------------------------------------------------------ swipe from the edges

    private fun interceptEdge(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDragging = false
                edgeDownX = ev.x
                edgeDownY = ev.y
                edgeFromLeft = ev.x < edgeWidth
                val fromRight = ev.x > width - edgeWidth
                edgeCandidate = (edgeFromLeft || fromRight) && edgeListener?.canEdgeSwipe(edgeFromLeft) == true
            }
            MotionEvent.ACTION_POINTER_DOWN -> edgeCandidate = false
            MotionEvent.ACTION_MOVE -> {
                if (!edgeCandidate) return false
                val inward = if (edgeFromLeft) ev.x - edgeDownX else edgeDownX - ev.x
                val dy = abs(ev.y - edgeDownY)
                if (dy > slop * 2 && dy > inward) {
                    edgeCandidate = false
                } else if (inward > slop && inward > dy * 1.5f) {
                    edgeCandidate = false
                    edgeDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeCandidate = false
        }
        return false
    }

    private fun handleEdge(ev: MotionEvent): Boolean {
        val distance = (if (edgeFromLeft) ev.x - edgeDownX else edgeDownX - ev.x).coerceAtLeast(0f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> edgeListener?.onEdgeSwipe(edgeFromLeft, distance)
            MotionEvent.ACTION_UP -> {
                edgeDragging = false
                edgeListener?.onEdgeSwipeEnd(edgeFromLeft, distance)
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeDragging = false
                edgeListener?.onEdgeSwipeEnd(edgeFromLeft, 0f)
            }
        }
        return true
    }

    // ------------------------------------------------------------------ pull to refresh

    private fun interceptPull(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                pulling = false
                pullCandidate = pullListener?.canPull() == true
            }
            MotionEvent.ACTION_POINTER_DOWN -> pullCandidate = false
            MotionEvent.ACTION_MOVE -> {
                if (!pullCandidate) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dy < -slop || abs(dx) > slop * 2) {
                    pullCandidate = false
                } else if (dy > slop && dy > abs(dx) * 1.5f) {
                    val p = page
                    when {
                        p == null -> pullCandidate = false
                        // Pages with touch handlers answer late; keep waiting while the finger moves down.
                        !p.touchAnswered -> Unit
                        !p.touchAllowsPullToRefresh() -> pullCandidate = false
                        else -> {
                            pulling = true
                            pullStartY = ev.y
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pullCandidate = false
        }
        return false
    }

    private fun handlePull(ev: MotionEvent): Boolean {
        val distance = (ev.y - pullStartY).coerceAtLeast(0f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> pullListener?.onPull(distance)
            MotionEvent.ACTION_UP -> {
                pulling = false
                pullListener?.onPullEnd(distance)
            }
            MotionEvent.ACTION_CANCEL -> {
                pulling = false
                pullListener?.onPullEnd(0f)
            }
        }
        return true
    }

    // ------------------------------------------------------------------ full-screen video

    // Taps on a full-screen video.
    private var downTime = 0L
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapSeries = 0
    private var videoTap = false
    private var holding = false
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop

    /** Held still long enough: double speed, and the page lets go of the touch (no long-press menu). */
    private val holdRunnable = Runnable {
        if (!tracking || !videoMode) return@Runnable
        holding = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        for (i in 0 until childCount) getChildAt(i).dispatchTouchEvent(cancel)
        cancel.recycle()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onHold(true)
    }

    private fun interceptVideoGesture(ev: MotionEvent): Boolean {
        // The touch the page was holding is taken over now; its last event (letting go) may arrive
        // here rather than in onTouchEvent.
        if (holding) return handleHold(ev)
        if (videoTap) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                downTime = ev.eventTime
                dragging = false
                // Leave the top and bottom edges to the player controls and system bars.
                tracking = ev.y > height * 0.12f && ev.y < height * 0.85f
                leftSide = ev.x < width / 2f
                if (tapGestures && tracking) {
                    val since = ev.eventTime - lastTapUp
                    val near = hypot(ev.x - lastTapX, ev.y - lastTapY) < doubleTapSlop
                    // The second tap of a double tap, or another quick tap after one: skip.
                    if ((since < DOUBLE_TAP_MS && near) || (tapSeries > 0 && since < SERIES_MS)) {
                        videoTap = true
                        return true
                    }
                    tapSeries = 0
                    postDelayed(holdRunnable, HOLD_MS)
                } else {
                    tapSeries = 0
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false
                removeCallbacks(holdRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(holdRunnable)
                if (swipeGestures && tracking && !dragging && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2) {
                    dragging = true
                    lastY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                val quick = ev.eventTime - downTime < ViewConfiguration.getTapTimeout() * 3
                if (quick && hypot(ev.x - downX, ev.y - downY) < slop) {
                    lastTapUp = ev.eventTime
                    lastTapX = ev.x
                    lastTapY = ev.y
                } else {
                    lastTapUp = 0
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(holdRunnable)
        }
        return false
    }

    private fun handleVideoTap(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                videoTap = false
                tapSeries++
                lastTapUp = ev.eventTime
                lastTapX = ev.x
                lastTapY = ev.y
                listener?.onDoubleTap(ev.x < width / 2f)
            }
            MotionEvent.ACTION_CANCEL -> videoTap = false
        }
        return true
    }

    private fun handleHold(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            holding = false
            listener?.onHold(false)
        }
        return true
    }

    private fun endVideoGestures() {
        removeCallbacks(holdRunnable)
        if (holding) {
            holding = false
            listener?.onHold(false)
        }
        videoTap = false
        dragging = false
        tapSeries = 0
        lastTapUp = 0
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L

        /** After a double tap, each further tap within this time skips again. */
        const val SERIES_MS = 650L

        /** Shorter than the engine's own long-press (500 ms), so its menu doesn't open. */
        const val HOLD_MS = 420L
    }
}
