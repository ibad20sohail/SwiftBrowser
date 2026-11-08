package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.swiftbrowser.R
import kotlin.math.abs

/**
 * Short messages at the bottom of the screen (1.9), in place of Android's Snackbar and Toast: a
 * rounded pill in Swift's colours that rises in, can be swiped away sideways, and goes by itself.
 * When it offers an action (Undo), a thin line along its bottom shows how long that is possible.
 * One message at a time: a new one takes the place of the old.
 */
object Messages {
    private const val SHORT_MS = 2600L
    private const val ACTION_MS = 5000L
    private const val LONG_MS = 8000L

    /** The main thread's (a pill's own View.getHandler() is null once its screen is gone). */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Pill? = null

    /** A message that stays until it is dismissed, and can change its text and progress. */
    class Handle internal constructor(private val pill: Pill) {
        fun setText(text: CharSequence) {
            pill.text.text = text
        }

        /** 0..1 fills the line at the bottom; null hides it. */
        fun setProgress(fraction: Float?) = pill.setProgress(fraction)
        fun dismiss() = pill.dismiss()
        val isShowing: Boolean get() = pill.parent != null && !pill.leaving
    }

    /**
     * Shows [text] at the bottom of [activity]. [action] (such as "Undo") runs [onAction] when tapped.
     * [bottomOffset] lifts it above a bar at the bottom of the screen (px). [onDismiss] (3.0) runs
     * once when the message is gone, however it went (after its action too, or at once when it can't
     * be shown): what waits for an Undo is done then, not after a fixed time.
     */
    fun show(
        activity: Activity,
        text: CharSequence,
        action: String? = null,
        icon: Int? = null,
        long: Boolean = false,
        bottomOffset: Int = 0,
        onDismiss: (() -> Unit)? = null,
        onAction: (() -> Unit)? = null,
    ) {
        // Long texts stay longer (2.6): about as long as they take to read.
        val reading = 1500L + text.length * 45L
        val duration = when {
            long -> LONG_MS
            action != null -> maxOf(ACTION_MS, reading).coerceAtMost(LONG_MS)
            else -> maxOf(SHORT_MS, reading).coerceAtMost(LONG_MS)
        }
        if (present(activity, text, action, icon, duration, bottomOffset, onAction, onDismiss) == null) onDismiss?.invoke()
    }

    /** A message that stays (an update downloading): change it through the handle, dismiss when done. */
    fun sticky(activity: Activity, text: CharSequence, action: String? = null, bottomOffset: Int = 0, onAction: (() -> Unit)? = null): Handle? =
        present(activity, text, action, null, 0L, bottomOffset, onAction)?.let { Handle(it) }

    /** Takes away the message on screen, if any. */
    fun dismiss() {
        current?.dismiss()
    }

    private fun present(
        activity: Activity,
        text: CharSequence,
        action: String?,
        icon: Int?,
        duration: Long,
        bottomOffset: Int,
        onAction: (() -> Unit)?,
        onDismiss: (() -> Unit)? = null,
    ): Pill? {
        if (activity.isFinishing) return null
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return null
        current?.let { old ->
            if (old.context === activity) old.dismiss(quick = true) else old.removeNow()
        }
        val pill = Pill(activity, text, action, icon, duration, onAction, onDismiss)
        current = pill
        val m = Ui.dp(activity, Ui.MARGIN)
        host.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(m, 0, m, m + bottomOffset)
        })
        pill.enter()
        pill.announceForAccessibility(text)
        return pill
    }

    /**
     * 3.0.3: a screen that is black whatever the mode (the picture viewer, the player over a video)
     * says so, and its messages take the dark colours there.
     */
    interface DarkScreen {
        val darkMessages: Boolean
    }

    /**
     * 3.0.3: the message's colours from Swift's own (the mode's, your chosen ones included): in light
     * mode the sheets' surface with a thin outline, in dark mode the raised surface; the text and the
     * action as everywhere else. It was a fixed charcoal in both modes.
     */
    private class Colours(val bg: Int, val fg: Int, val accent: Int, val stroke: Int?)

    private fun colours(ctx: Context): Colours {
        val night = Palette.isNight(ctx) || (ctx as? DarkScreen)?.darkMessages == true
        return if (night) {
            Colours(Palette.colorFor(ctx, true, R.color.surface_alt), Palette.colorFor(ctx, true, R.color.text_primary), Palette.colorFor(ctx, true, Looks.accent.primary), null)
        } else {
            Colours(Palette.colorFor(ctx, false, R.color.sheet_bg), Palette.colorFor(ctx, false, R.color.text_primary), Palette.colorFor(ctx, false, Looks.accent.primary), Palette.colorFor(ctx, false, R.color.outline))
        }
    }

    @SuppressLint("ViewConstructor")
    internal class Pill(
        ctx: Context,
        message: CharSequence,
        action: String?,
        icon: Int?,
        private val duration: Long,
        onAction: (() -> Unit)?,
        private var onDismiss: (() -> Unit)?,
    ) : FrameLayout(ctx) {
        val text: TextView
        private val line: View
        var leaving = false
            private set
        private val timeout = Runnable { dismiss() }
        private var downX = 0f
        private var dragging = false
        private var timeLeft = duration
        private var startedAt = 0L

        init {
            val c = colours(ctx)
            val fg = c.fg
            val accent = c.accent
            background = Ui.shape(ctx, c.bg, Ui.R_MEDIUM, c.stroke)
            elevation = Ui.dpf(ctx, 6f)
            clipToOutline = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(ctx, 52)
                setPadding(Ui.dp(ctx, if (icon != null) 14 else 18), Ui.dp(ctx, 8), Ui.dp(ctx, if (action != null) 6 else 18), Ui.dp(ctx, 8))
            }
            if (icon != null) {
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(Ui.icon(ctx, icon, fg))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20)).apply { marginEnd = Ui.dp(ctx, 12) })
            }
            text = Ui.text(ctx, Ui.Type.BODY, message, fg, lines = 3)
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (action != null) {
                // 3.0: 48 dp to tap (it was 40), reaching into the pill's own padding so the pill keeps
                // its height; the touch highlight is drawn 40 dp high as before.
                val reach = Ui.dp(ctx, 4)
                row.addView(TextView(ctx).apply {
                    this.text = action
                    Ui.style(this, Ui.Type.LABEL, accent)
                    gravity = Gravity.CENTER
                    minWidth = Ui.dp(ctx, 64)
                    minHeight = Ui.dp(ctx, 48)
                    setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), 0)
                    background = android.graphics.drawable.InsetDrawable(Ui.ripple(ctx, null, 20), 0, reach, 0, reach)
                    setOnClickListener {
                        onAction?.invoke()
                        dismiss()
                    }
                    // 3.0.3: springs when pressed.
                    Ui.pressable(this, Motion.PRESS_BUTTON)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Ui.dp(ctx, 4)
                    topMargin = -reach
                    bottomMargin = -reach
                })
            }
            addView(row)
            line = View(ctx).apply {
                setBackgroundColor(accent)
                pivotX = 0f
                visibility = if (action != null && duration > 0) View.VISIBLE else View.GONE
            }
            addView(line, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 3), Gravity.BOTTOM))
        }

        fun setProgress(fraction: Float?) {
            if (fraction == null) {
                line.visibility = View.GONE
                return
            }
            line.visibility = View.VISIBLE
            line.animate().cancel()
            line.scaleX = fraction.coerceIn(0f, 1f)
        }

        fun enter() {
            alpha = 0f
            translationY = Ui.dpf(context, 40f)
            scaleX = 0.96f
            scaleY = 0.96f
            // 3.0.4: it rises on a spring, settling with a touch of bounce.
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.enter).start()
            Motion.spring(this, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_Y, 0f, stiffness = 500f, bounce = true)
            startTimer(duration)
        }

        private fun startTimer(ms: Long) {
            if (duration <= 0) return
            timeLeft = ms
            startedAt = System.currentTimeMillis()
            mainHandler.removeCallbacks(timeout)
            mainHandler.postDelayed(timeout, ms)
            if (line.visibility == View.VISIBLE) {
                line.animate().scaleX(0f).setDuration(ms).setInterpolator(null).start()
            }
        }

        private fun pauseTimer() {
            if (duration <= 0) return
            mainHandler.removeCallbacks(timeout)
            line.animate().cancel()
            timeLeft = (timeLeft - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1200)
        }

        fun dismiss(quick: Boolean = false, toSide: Float = 0f) {
            if (leaving) return
            leaving = true
            mainHandler.removeCallbacks(timeout)
            if (current === this) current = null
            gone()
            Motion.stopSprings(this)
            val a = animate().alpha(0f).setDuration(if (quick) 120 else Motion.NORMAL).setInterpolator(Motion.exit)
            if (toSide != 0f) a.translationX(toSide) else a.translationY(Ui.dpf(context, if (quick) 12f else 24f))
            a.withEndAction { removeNow() }.start()
        }

        fun removeNow() {
            mainHandler.removeCallbacks(timeout)
            if (current === this) current = null
            gone()
            (parent as? ViewGroup)?.removeView(this)
        }

        /** The message is going: what waited for it is told, once. */
        private fun gone() {
            val f = onDismiss ?: return
            onDismiss = null
            f()
        }

        override fun onDetachedFromWindow() {
            // 3.0: its screen closed (or was made again) with it on: nothing keeps that screen in
            // memory any more (a message that stays never timed out), and an Undo can't come now.
            mainHandler.removeCallbacks(timeout)
            if (current === this) current = null
            gone()
            super.onDetachedFromWindow()
        }

        // Swiping sideways takes it away; holding it keeps it on screen.
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    dragging = false
                    pauseTimer()
                }
                MotionEvent.ACTION_MOVE -> if (abs(ev.rawX - downX) > Ui.dp(context, 8)) {
                    dragging = true
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) startTimer(timeLeft)
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    pauseTimer()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    translationX = dx
                    alpha = (1f - abs(dx) / width).coerceIn(0.2f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - downX
                    if (abs(dx) > width * 0.3f) {
                        dismiss(toSide = if (dx > 0) width.toFloat() else -width.toFloat())
                    } else {
                        animate().translationX(0f).alpha(1f).setDuration(Motion.SHORT).setInterpolator(Motion.standard).start()
                        startTimer(timeLeft)
                    }
                    dragging = false
                }
            }
            return true
        }
    }
}
