package com.swiftbrowser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.swiftbrowser.R

/**
 * Swift's start screen (2.7): the logo in your accent on your background. Android's own screen could
 * only be white (it is drawn before Swift starts), so Swift draws this one itself as soon as it can,
 * builds the browser behind it, then glides the logo into the home page's.
 */
class StartScreen(activity: Activity) {
    private val background = ColorDrawable(Palette.color(activity, R.color.page_bg))

    private val mark = ImageView(activity).apply {
        setImageDrawable(Ui.logo(activity))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val view: FrameLayout = FrameLayout(activity).apply {
        background = this@StartScreen.background
        // Touches don't reach the browser being built behind it.
        isClickable = true
        val size = Ui.dp(activity, 96)
        addView(mark, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        // In the middle of the whole screen, so it stays put when the status bar goes (full screen).
        val screen = android.graphics.Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
        val at = IntArray(2)
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.getLocationOnScreen(at)
            mark.translationY = screen.y / 2f - (at[1] + v.height / 2f)
        }
    }

    /**
     * Glides the logo onto [target] (the home page's logo) while the background fades to the page
     * behind; without one (a site was open) it just fades. [done] after it has gone.
     */
    fun finish(target: View?, done: () -> Unit) {
        val to = target?.takeIf { it.isShown && it.width > 0 }
        to?.alpha = 0f
        mark.pivotX = 0f
        mark.pivotY = 0f
        val shift = mark.translationY
        val from = IntArray(2)
        val at = IntArray(2)
        mark.getLocationInWindow(from)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (to != null) 420L else Motion.LONG
            interpolator = Motion.standard
            addUpdateListener {
                val f = it.animatedValue as Float
                // The page shows through quickly; the logo travels the whole time.
                background.alpha = (255 * (1f - (f * 1.6f).coerceAtMost(1f))).toInt()
                if (to != null) {
                    // Read each frame: the home page may still settle as it fills in.
                    to.getLocationInWindow(at)
                    val scale = 1f + (to.width.toFloat() / mark.width - 1f) * f
                    mark.scaleX = scale
                    mark.scaleY = scale
                    mark.translationX = (at[0] - from[0]) * f
                    mark.translationY = shift + (at[1] - from[1]) * f
                } else {
                    mark.alpha = 1f - f
                    mark.scaleX = 1f + 0.15f * f
                    mark.scaleY = mark.scaleX
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    to?.alpha = 1f
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    done()
                }
            })
            start()
        }
    }
}
