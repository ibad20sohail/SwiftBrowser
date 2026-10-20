package com.swiftbrowser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps the page.
 *  - Pull to refresh: dragging down at the top of a page reloads it, as in Chrome.
 *  - Swiping in from the left edge goes back, from the right edge forward.
 *  - Full-screen video: vertical swipes on the left half change brightness, on the right half
 *    volume (like MX Player). Taps and other gestures still reach the page, so the site's own
 *    player controls keep working.
 */
class GestureFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onSwipe(leftSide: Boolean, delta: Float)
        fun onSwipeEnd()
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

    var gesturesEnabled = false
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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (gesturesEnabled) return interceptVideoGesture(ev)
        if (interceptEdge(ev)) return true
        return interceptPull(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (edgeDragging) return handleEdge(ev)
        if (pulling) return handlePull(ev)
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

    private fun interceptVideoGesture(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                dragging = false
                // Leave the top and bottom edges to the player controls and system bars.
                tracking = ev.y > height * 0.12f && ev.y < height * 0.85f
                leftSide = ev.x < width / 2f
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (tracking && !dragging && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2) {
                    dragging = true
                    lastY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }
}
