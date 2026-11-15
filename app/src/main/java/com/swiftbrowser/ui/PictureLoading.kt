package com.swiftbrowser.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import java.util.WeakHashMap

/**
 * 3.0.11: a soft pulse on a picture's place while Swift's own picture for it loads (news, Continue,
 * Files, Downloads, the cleaner): the box (or the kind's icon) fades gently in and out, as the news
 * cards do while news loads (SkeletonView), until the picture comes or there is none.
 *
 * Light on purpose for a slow phone: ONE animator for all of them, which only changes each waiting
 * view's alpha (nothing is drawn again), and only while something waits. A picture that comes within
 * [WAIT_MS] (from memory, or decoded quickly) never pulses. Still when animations are off.
 * Main thread only.
 */
object PictureLoading {
    /** A picture coming sooner than this shows no pulse at all. */
    private const val WAIT_MS = 150L

    /** A picture still not there after this stops pulsing (something is off; it may still come). */
    private const val GIVE_UP_MS = 30_000L
    /** How far it fades: deep enough to be seen in the dark look, where the boxes are close to their cards. */
    private const val LOW = 0.3f

    /** The views waiting for their picture, and since when (weak: a view thrown away is let go). */
    private val waiting = WeakHashMap<View, Long>()
    private var pulse: ValueAnimator? = null

    /** [v]'s picture is on its way: it pulses until [stop] (after a moment). */
    fun start(v: View) {
        if (!Motion.enabled(v.context)) return
        waiting[v] = SystemClock.uptimeMillis()
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { tick(it.animatedValue as Float) }
            start()
        }
    }

    /** [v] has its picture now, or will get none, or shows something else (a row used again). */
    fun stop(v: View) {
        if (waiting.remove(v) != null) v.alpha = 1f
        if (waiting.isEmpty()) end()
    }

    private fun tick(f: Float) {
        val now = SystemClock.uptimeMillis()
        val alpha = 1f - (1f - LOW) * f
        val it = waiting.entries.iterator()
        while (it.hasNext()) {
            val (v, since) = it.next()
            // Gone from the screen for good (its screen closed), or waiting too long: as it was.
            if (!v.isAttachedToWindow || now - since > GIVE_UP_MS) {
                v.alpha = 1f
                it.remove()
                continue
            }
            if (now - since >= WAIT_MS) v.alpha = alpha
        }
        if (waiting.isEmpty()) end()
    }

    private fun end() {
        pulse?.cancel()
        pulse = null
    }
}
