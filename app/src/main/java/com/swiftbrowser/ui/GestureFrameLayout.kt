package com.swiftbrowser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps the page during full-screen video. Vertical swipes on the left half change brightness,
 * on the right half volume (like MX Player). Taps and other gestures still reach the page,
 * so the site's own player controls keep working.
 */
class GestureFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onSwipe(leftSide: Boolean, delta: Float)
        fun onSwipeEnd()
        fun onTap()
    }

    var gesturesEnabled = false
    var listener: Listener? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false
    private var leftSide = false
    private var moved = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
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

    override fun onTouchEvent(ev: MotionEvent): Boolean {
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
}
