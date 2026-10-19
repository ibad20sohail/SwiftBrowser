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
        fun onTap()
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
    var page: SwiftGeckoView? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false
    private var leftSide = false
    private var moved = false

    private var pullCandidate = false
    private var pulling = false
    private var pullStartY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (gesturesEnabled) return interceptVideoGesture(ev)
        return interceptPull(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
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
                moved = false
                // Leave the top and bottom edges to the player controls and system bars.
                tracking = ev.y > height * 0.12f && ev.y < height * 0.85f
                leftSide = ev.x < width / 2f
            }
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                if (tracking && !dragging && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 2) {
                    dragging = true
                    lastY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if (!moved) listener?.onTap()
        }
        return false
    }
}
