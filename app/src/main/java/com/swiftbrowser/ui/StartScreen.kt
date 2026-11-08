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
import android.widget.LinearLayout
import com.swiftbrowser.R

/**
 * Swift's start screen (2.7): the logo in your accent on your background. Android's own screen could
 * only be white (it is drawn before Swift starts), so Swift draws this one itself as soon as it can,
 * builds the browser behind it, then glides the logo into the home page's. 3.0.5: with [loading] (a
 * site is open, so its page has to load), the loader and what's loading are under the logo, the same
 * as on the browser's start-up cover that takes over from this screen.
 */
class StartScreen(activity: Activity, loading: Boolean = false) {
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
        val moved = arrayListOf<View>(mark)
        if (loading) {
            val steps = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(LoadingIndicator(activity).apply { colour = Ui.accent(activity) }, LinearLayout.LayoutParams(Ui.dp(activity, 32), Ui.dp(activity, 32)))
                addView(Ui.text(activity, Ui.Type.BODY_SECONDARY, StartupSteps.text(StartupSteps.Step.BROWSER, "")), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(activity, 12) })
            }
            addView(steps, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = Ui.dp(activity, 132)
            })
            moved += steps
        }
        keepCentred(activity, this, moved)
    }

    /**
     * Glides the logo onto [target] (the home page's logo) while the background fades to the page
     * behind; without one (a site was open) it just fades. [covered]: the same logo and loader are
     * under it (the browser's start-up cover), so it only has to go. [done] after it has gone.
     */
    fun finish(target: View?, covered: Boolean = false, done: () -> Unit) {
        if (covered) {
            view.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                done()
            }.start()
            return
        }
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

    companion object {
        /**
         * Keeps [views] centred on the whole screen rather than on [host], so that they stay put when
         * the status bar goes (full screen) and the start screen and the cover line up.
         */
        fun keepCentred(activity: Activity, host: View, views: List<View>) {
            val screen = android.graphics.Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
            val at = IntArray(2)
            host.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.getLocationOnScreen(at)
                val shift = screen.y / 2f - (at[1] + v.height / 2f)
                views.forEach { it.translationY = shift }
            }
        }
    }
}
