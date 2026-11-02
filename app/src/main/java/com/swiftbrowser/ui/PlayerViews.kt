package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.files.PrivateFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 3.0: what Swift's player and picture viewer may show. Other apps can open both (they are
 * exported), so a link or a path that reaches Swift's own private storage (its data folder: the
 * Private folder, saved sign-ins, the engine's profile) is refused unless Swift itself asked: its
 * own opens carry a mark only this run of Swift knows, which another app can't copy. Without that
 * check, another app could have them show such a file, then share or copy it out.
 */
object ViewerAccess {
    private const val EXTRA_OWN = "com.swiftbrowser.extra.OWN"
    private val token = java.util.UUID.randomUUID().toString()

    const val REFUSED = "Swift can't open this file"

    /** [i] opens a viewer for Swift itself (Files, Downloads, the notification of a download). */
    fun mark(i: Intent): Intent = i.putExtra(EXTRA_OWN, token)

    /** [i] came from Swift itself (this run of it). */
    fun isOwn(i: Intent?): Boolean = runCatching { i?.getStringExtra(EXTRA_OWN) == token }.getOrDefault(false)

    /** A link another app sent (Open with): refused when it reaches Swift's own files. */
    fun refused(ctx: Context, uri: Uri): Boolean = AppFiles.refusedFromOutside(ctx, uri)

    /**
     * File [f] asked for by its path: one in Swift's private storage only when Swift asked (or from
     * Private while it is open in Files, however Files opened it).
     */
    fun mayOpen(ctx: Context, i: Intent?, f: File): Boolean {
        if (!AppFiles.isPrivate(ctx, f)) return true
        if (isOwn(i)) return true
        if (!PrivateFolder.isUnlocked) return false
        // Both resolved (/data/user/0 and /data/data are the same folder).
        val real = runCatching { f.canonicalPath }.getOrNull() ?: return false
        val root = runCatching { PrivateFolder.root(ctx).canonicalPath }.getOrNull() ?: return false
        return real == root || real.startsWith("$root/")
    }

    /** It must be kept out of screenshots: asked for (AppFiles.EXTRA_SECURE), or a file of Private. */
    fun secure(ctx: Context, i: Intent?, f: File?): Boolean =
        runCatching { i?.getBooleanExtra(AppFiles.EXTRA_SECURE, false) == true }.getOrDefault(false) || (f != null && AppFiles.isPrivate(ctx, f))
}

/**
 * 2.9: the player's video area: its one child (the video's SurfaceView) sized to the chosen shape,
 * centred. Zoom makes it bigger than the screen, which cuts off the sides (or top and bottom).
 */
class VideoFrame(context: Context) : ViewGroup(context) {
    enum class Fit(val label: String) {
        FIT("Fit to screen"),
        FILL("Stretch to fill"),
        ZOOM("Zoom to fill"),
        WIDE("16:9"),
        STANDARD("4:3"),
    }

    var fit = Fit.FIT
        set(v) {
            field = v
            requestLayout()
        }

    /** The video's shape (width / height, its pixels' own shape included), 0 until known. */
    private var ratio = 0f

    /** Where the picture is, in this view (bitmap subtitles are placed on it). */
    val picture = Rect()

    fun setVideoSize(width: Int, height: Int, pixelRatio: Float) {
        val r = if (width > 0 && height > 0) width * pixelRatio / height else 0f
        if (abs(r - ratio) < 0.001f) return
        ratio = r
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val r = when (fit) {
            Fit.WIDE -> 16f / 9f
            Fit.STANDARD -> 4f / 3f
            else -> ratio
        }
        var cw = w
        var ch = h
        if (r > 0f && fit != Fit.FILL && w > 0 && h > 0) {
            val wider = r > w.toFloat() / h
            if (fit == Fit.ZOOM) {
                if (wider) cw = (h * r).roundToInt() else ch = (w / r).roundToInt()
            } else {
                if (wider) ch = (w / r).roundToInt() else cw = (h * r).roundToInt()
            }
        }
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = (w - c.measuredWidth) / 2
            val top = (h - c.measuredHeight) / 2
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
            picture.set(maxOf(left, 0), maxOf(top, 0), minOf(left + c.measuredWidth, w), minOf(top + c.measuredHeight, h))
        }
    }
}

/**
 * 2.9: the player's touch layer, over the video, under the controls: a tap shows or hides the
 * controls, a double tap on the left or right skips back or on (in the middle: play or pause),
 * holding plays faster until let go, swiping up and down changes the brightness (left half) or the
 * volume (right half), and swiping sideways seeks. Swipes starting at the screen's top or bottom
 * edge are left to Android (they bring its bars).
 */
@SuppressLint("ViewConstructor")
class PlayerGestures(context: Context, private val host: Host) : View(context) {
    interface Host {
        /** The screen is locked: a touch only shows the unlock button. */
        fun locked(): Boolean

        /** Swipes, double taps and holding are on (Settings). */
        fun gesturesOn(): Boolean

        fun onTap()

        /** -1 on the left third, 1 on the right, 0 in the middle. */
        fun onDoubleTap(side: Int)

        fun onHold(start: Boolean)

        fun onSwipeStart(kind: Int)

        /** Up and down: how far as a share of the height (up is more); sideways: of the width (right is on). */
        fun onSwipe(kind: Int, amount: Float)

        fun onSwipeEnd(kind: Int)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private val edge = Ui.dp(context, 28)
    private var downX = 0f
    private var downY = 0f
    private var swipe = 0
    private var holding = false
    private var canSwipe = false

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            host.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (host.locked() || !host.gesturesOn()) {
                host.onTap()
                return true
            }
            val third = width / 3f
            host.onDoubleTap(
                when {
                    e.x < third -> -1
                    e.x > width - third -> 1
                    else -> 0
                },
            )
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (host.locked() || !host.gesturesOn() || swipe != 0) return
            holding = true
            host.onHold(true)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipe = 0
                holding = false
                canSwipe = !host.locked() && host.gesturesOn() && ev.y > edge && ev.y < height - edge
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipe != 0) {
                    if (swipe == SEEK) host.onSwipe(swipe, (ev.x - downX) / width.coerceAtLeast(1)) else host.onSwipe(swipe, (downY - ev.y) / height.coerceAtLeast(1))
                    return true
                }
                if (canSwipe && !holding && ev.pointerCount == 1) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        swipe = if (abs(dx) > abs(dy)) SEEK else if (downX < width / 2f) BRIGHTNESS else VOLUME
                        // No tap, double tap or hold out of this touch any more.
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        detector.onTouchEvent(cancel)
                        cancel.recycle()
                        // Measured from here, so the change doesn't jump by the first few pixels.
                        downX = ev.x
                        downY = ev.y
                        host.onSwipeStart(swipe)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swipe != 0) {
                    val kind = swipe
                    swipe = 0
                    host.onSwipeEnd(kind)
                    return true
                }
                if (holding) {
                    holding = false
                    host.onHold(false)
                }
            }
        }
        detector.onTouchEvent(ev)
        return true
    }

    companion object {
        const val BRIGHTNESS = 1
        const val VOLUME = 2
        const val SEEK = 3
    }
}
