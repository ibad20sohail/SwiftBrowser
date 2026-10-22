package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

/**
 * GeckoView that remembers how the engine handled the start of each touch, so a pull down at the
 * top of a page can become "pull to refresh" without stealing scrolls from the page.
 */
class SwiftGeckoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : GeckoView(context, attrs) {

    private var downTime = 0L

    /** The engine's answer for the current touch; null until it arrives. */
    private var downDetail: PanZoomController.InputResultDetail? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || session == null) return super.onTouchEvent(event)
        val time = event.downTime
        downTime = time
        downDetail = null
        onTouchEventForDetailResult(event).accept({ detail ->
            if (downTime == time) downDetail = detail
        }, { _ -> })
        return true
    }

    /**
     * True when the current touch started on a page that is scrolled to the top and lets the
     * browser handle vertical dragging (no inner scroller, map or the site's own pull gesture).
     */
    fun touchAllowsPullToRefresh(): Boolean {
        val d = downDetail ?: return false
        return when (d.handledResult()) {
            PanZoomController.INPUT_RESULT_UNHANDLED -> true
            PanZoomController.INPUT_RESULT_HANDLED ->
                d.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP == 0 &&
                    d.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL != 0
            else -> false
        }
    }

    /** True when the current touch scrolls the page itself up or down (not an inner scroller, a map or a slider). */
    fun touchScrollsPage(): Boolean {
        val d = downDetail ?: return false
        return d.handledResult() == PanZoomController.INPUT_RESULT_HANDLED &&
            d.scrollableDirections() and (PanZoomController.SCROLLABLE_FLAG_TOP or PanZoomController.SCROLLABLE_FLAG_BOTTOM) != 0
    }

    /** True once the engine has answered for the current touch. */
    val touchAnswered: Boolean get() = downDetail != null
}
