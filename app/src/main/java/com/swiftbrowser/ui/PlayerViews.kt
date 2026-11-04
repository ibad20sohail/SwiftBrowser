package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
        // 3.0.3: named as pinching out calls it (it was "Zoom to fill").
        ZOOM("Fill screen"),
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
 *
 * 3.0.3: pinching fills the screen or fits the picture; after a double tap, further taps on that
 * side skip again; an up or down drag starting on the subtitles moves them. With two fingers down
 * no swipe starts.
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

        /** 3.0.3: a pinch out far enough ([fill]: fill the screen) or in (fit the picture). */
        fun onPinch(fill: Boolean)

        /** 3.0.3: [x], [y] are on the subtitles (a drag up or down from there moves them). */
        fun onSubtitles(x: Float, y: Float): Boolean
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private val edge = Ui.dp(context, 28)
    private var downX = 0f
    private var downY = 0f
    private var swipe = 0
    private var holding = false
    private var canSwipe = false

    /** The last single tap was in the middle third (shown or hid the controls already). */
    private var tappedMiddle = false

    /** 3.0.3: a second finger came down in this touch: no taps or swipes from it, only the pinch. */
    private var twoFingers = false

    /** 3.0.3: this touch started on the subtitles. */
    private var onSubtitles = false

    /** 3.0.3: this touch is a further tap of a double tap's series (see [TapSeries]). */
    private var seriesTap = false
    private val series = TapSeries()

    /** 3.0.3: a double tap on a side was made in this touch: the series starts when it lets go. */
    private var skippedSide = 0
    private val pinch = PinchSteps()

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (host.locked() || !host.gesturesOn() || holding) return false
            pinch.begin()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            when (pinch.add(detector.scaleFactor)) {
                1 -> host.onPinch(true)
                -1 -> host.onPinch(false)
            }
            return true
        }
    }).apply {
        // Not a double tap and a drag (that is the seek's and the skips' own).
        isQuickScaleEnabled = false
    }

    private fun inMiddle(x: Float): Boolean = x >= width / 3f && x <= width - width / 3f

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        // 3.0.2: a tap in the middle third shows or hides the controls at once, without waiting to
        // see whether a second tap follows (double taps only skip, at the sides, as VLC advises);
        // at the sides a single tap still waits, for the double tap's sake.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tappedMiddle = inMiddle(e.x)
            if (tappedMiddle) host.onTap()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!tappedMiddle) host.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // A second quick tap in the middle is simply another tap.
            if (host.locked() || !host.gesturesOn() || inMiddle(e.x)) {
                host.onTap()
                return true
            }
            val side = if (e.x < width / 2f) -1 else 1
            skippedSide = side
            host.onDoubleTap(side)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (host.locked() || !host.gesturesOn() || swipe != 0) return
            holding = true
            host.onHold(true)
        }
    })

    /** No tap, double tap or hold out of this touch any more. */
    private fun cancelTaps(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        detector.onTouchEvent(cancel)
        cancel.recycle()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scale.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipe = 0
                holding = false
                twoFingers = false
                skippedSide = 0
                canSwipe = !host.locked() && host.gesturesOn() && ev.y > edge && ev.y < height - edge
                onSubtitles = canSwipe && host.onSubtitles(ev.x, ev.y)
                // 3.0.3: another tap on the side that was just skipped skips again, at once.
                seriesTap = !host.locked() && host.gesturesOn() && series.claims(PlayerRules.third(ev.x, width), ev.eventTime)
                if (seriesTap) return true
                series.clear()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 3.0.3: a second finger (a pinch): a swipe under way ends where it is, and nothing else comes of this touch.
                if (!twoFingers) {
                    twoFingers = true
                    seriesTap = false
                    if (swipe != 0) {
                        val kind = swipe
                        swipe = 0
                        host.onSwipeEnd(kind)
                    }
                    cancelTaps(ev)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (seriesTap) {
                    // Moved: not a tap after all.
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                        seriesTap = false
                        series.clear()
                    }
                    return true
                }
                if (twoFingers) return true
                if (swipe != 0) {
                    if (swipe == SEEK) host.onSwipe(swipe, (ev.x - downX) / width.coerceAtLeast(1)) else host.onSwipe(swipe, (downY - ev.y) / height.coerceAtLeast(1))
                    return true
                }
                if (canSwipe && !holding && ev.pointerCount == 1) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        swipe = when {
                            onSubtitles && abs(dy) > abs(dx) -> SUBTITLES
                            abs(dx) > abs(dy) -> SEEK
                            downX < width / 2f -> BRIGHTNESS
                            else -> VOLUME
                        }
                        cancelTaps(ev)
                        // Measured from here, so the change doesn't jump by the first few pixels.
                        downX = ev.x
                        downY = ev.y
                        host.onSwipeStart(swipe)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (seriesTap) {
                    seriesTap = false
                    val side = series.side
                    if (ev.actionMasked == MotionEvent.ACTION_UP && side != 0) {
                        host.onDoubleTap(side)
                        series.start(side, ev.eventTime)
                    } else {
                        series.clear()
                    }
                    return true
                }
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
                if (twoFingers) {
                    twoFingers = false
                    return true
                }
                // 3.0.3: a double tap on a side: further taps there skip again (the series).
                if (ev.actionMasked == MotionEvent.ACTION_UP && skippedSide != 0) series.start(skippedSide, ev.eventTime)
            }
        }
        detector.onTouchEvent(ev)
        return true
    }

    companion object {
        const val BRIGHTNESS = 1
        const val VOLUME = 2
        const val SEEK = 3

        /** 3.0.3: the subtitles, dragged up or down. */
        const val SUBTITLES = 4
    }
}
