package com.swiftbrowser.ui

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.Thumbnails
import kotlin.math.abs

/**
 * Swiping sideways on the toolbar switches to the next or previous tab, as in Chrome. The other
 * tab's picture (from the tab grid) slides in over the page with the finger: the page itself is
 * drawn by the engine in a separate surface, which can't be moved along cheaply. The picture stays
 * until the other tab's page has been drawn.
 */
class TabSwipe(
    private val host: Host,
    private val peek: View,
    private val peekImage: ImageView,
    private val peekTitle: TextView,
) {
    interface Host {
        /** Whether a swipe may start at this point of the screen (raw coordinates). */
        fun canSwipeTabs(rawX: Float, rawY: Float): Boolean

        /** The tab after or before the current one, in the tab grid's order. */
        fun neighbourTab(next: Boolean): Tab?

        fun switchToTab(tab: Tab)

        /** The colour behind a tab's picture. */
        fun backgroundOf(tab: Tab): Int
    }

    enum class Result {
        /** Not a tab swipe: the touch goes to the screen as usual. */
        PASS,

        /** A tab swipe starts with this event: the views under the finger must let go of the touch. */
        STARTED,
        CONSUMED,
    }

    private val config = ViewConfiguration.get(peek.context)
    private val slop = config.scaledTouchSlop
    private val flingVelocity = config.scaledMinimumFlingVelocity * 8f
    private var tracking = false
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private var velocity: VelocityTracker? = null

    /** The tab whose picture is shown, and on which side it comes in from. */
    private var target: Tab? = null
    private var fromRight = false
    private val pictures = HashMap<Long, Bitmap?>()

    /** Switched to this tab; its picture stays until the page has been drawn. */
    private var switching: Tab? = null

    /** The tab had been put to sleep and loads its page again: wait until some of it is drawn. */
    private var needContent = false
    private val stopWaiting = Runnable { fadeOut() }

    private val width: Float get() = (peek.parent as View).width.toFloat()

    fun onTouchEvent(ev: MotionEvent): Result {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                tracking = switching == null && host.canSwipeTabs(ev.rawX, ev.rawY)
                if (tracking) {
                    startX = ev.rawX
                    startY = ev.rawY
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                    loadPictures()
                }
                return Result.PASS
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return Result.PASS
                velocity?.addMovement(ev)
                val dx = ev.rawX - startX
                val dy = ev.rawY - startY
                if (dragging) {
                    move(dx)
                    return Result.CONSUMED
                }
                if (abs(dy) > slop && abs(dy) > abs(dx)) {
                    stopTracking()
                    return Result.PASS
                }
                if (abs(dx) <= slop || abs(dx) < abs(dy) * 1.5f) return Result.PASS
                dragging = true
                // Count from where the swipe was recognised, so the picture doesn't jump in.
                startX += if (dx > 0) slop else -slop
                move(ev.rawX - startX)
                return Result.STARTED
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) return Result.CONSUMED
                stopTracking()
                return Result.PASS
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return Result.PASS
                val wasDragging = dragging
                val v = velocity
                var vx = 0f
                if (v != null) {
                    v.addMovement(ev)
                    v.computeCurrentVelocity(1000)
                    vx = v.xVelocity
                }
                stopTracking()
                if (!wasDragging) return Result.PASS
                release(ev.rawX - startX, vx, ev.actionMasked == MotionEvent.ACTION_CANCEL)
                return Result.CONSUMED
            }
        }
        return if (dragging) Result.CONSUMED else Result.PASS
    }

    /**
     * [tab] was asleep and loads its page again: show its last picture meanwhile, as if it had
     * stayed open, until some of the page is drawn.
     */
    fun coverUntilDrawn(tab: Tab) {
        if (switching != null || tracking) return
        peek.animate().cancel()
        pictures.clear()
        switching = tab
        needContent = true
        target = tab
        Thumbnails.load(tab) { bmp ->
            if (switching !== tab) return@load
            if (bmp == null) {
                // Nothing to show: no cover at all is better than a plain one.
                reset()
                switching = null
                return@load
            }
            pictures[tab.id] = bmp
            showPicture(tab)
            peek.translationX = 0f
            peek.alpha = 1f
            peek.visibility = View.VISIBLE
        }
        peek.postDelayed(stopWaiting, 3000)
    }

    /** The engine has drawn [tab]'s page: its picture can go. */
    fun onPageDrawn(tab: Tab, contentful: Boolean) {
        if (switching === tab && (contentful || !needContent)) fadeOut()
    }

    private fun stopTracking() {
        tracking = false
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    /** Pictures of both neighbours, so whichever way the finger goes, one is ready. */
    private fun loadPictures() {
        pictures.clear()
        for (next in booleanArrayOf(true, false)) {
            val tab = host.neighbourTab(next) ?: continue
            Thumbnails.load(tab) { bmp ->
                pictures[tab.id] = bmp
                if (target === tab) showPicture(tab)
            }
        }
    }

    private fun showPicture(tab: Tab) {
        peek.setBackgroundColor(host.backgroundOf(tab))
        val bmp = pictures[tab.id]
        peekImage.setImageBitmap(bmp)
        peekTitle.text = tab.displayTitle
        peekTitle.visibility = if (bmp == null) View.VISIBLE else View.GONE
    }

    /** Swiping left brings in the next tab from the right; swiping right, the previous one. */
    private fun move(dx: Float) {
        val next = dx < 0
        val tab = host.neighbourTab(next)
        if (tab !== target || next != fromRight) {
            target = tab
            fromRight = next
            if (tab != null) showPicture(tab)
        }
        if (tab == null) {
            peek.visibility = View.GONE
            return
        }
        val w = width
        peek.animate().cancel()
        peek.alpha = 1f
        peek.translationX = if (next) w + dx.coerceAtLeast(-w) else -w + dx.coerceAtMost(w)
        peek.visibility = View.VISIBLE
    }

    private fun release(dx: Float, vx: Float, cancelled: Boolean) {
        val tab = target
        if (tab == null) {
            reset()
            return
        }
        val w = width
        val towards = if (fromRight) -1f else 1f
        val flung = vx * towards > flingVelocity
        val switch = !cancelled && (dx * towards > w * 0.3f || (flung && dx * towards > slop))
        if (switch) {
            val remaining = abs(peek.translationX) / w
            peek.animate().translationX(0f)
                .setDuration((60 + 160 * remaining).toLong())
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    switching = tab
                    needContent = tab.session == null
                    host.switchToTab(tab)
                    // The home page is drawn by the app straight away; a page is drawn by the
                    // engine, which says so (onPageDrawn). Don't wait for it for ever.
                    if (tab.isHome) fadeOut() else peek.postDelayed(stopWaiting, if (needContent) 3000 else 1500)
                }
                .start()
        } else {
            peek.animate().translationX(if (fromRight) w else -w)
                .setDuration(150)
                .withEndAction { reset() }
                .start()
        }
    }

    private fun fadeOut() {
        peek.removeCallbacks(stopWaiting)
        switching = null
        peek.animate().alpha(0f).setDuration(150).withEndAction { reset() }.start()
    }

    private fun reset() {
        peek.visibility = View.GONE
        peek.alpha = 1f
        peek.translationX = 0f
        peekImage.setImageDrawable(null)
        target = null
    }
}
