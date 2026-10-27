package com.swiftbrowser.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.swiftbrowser.SwiftApp
import java.lang.ref.WeakReference

/**
 * Night light (2.3, Settings → Look & screen): a warm layer over Swift's screens, pages and videos
 * included, at night (7 in the evening until 7 in the morning) or always. Taps go through it. Off,
 * it isn't there at all.
 */
object NightLight {
    const val OFF = 0
    const val AT_NIGHT = 1
    const val ALWAYS = 2

    /** An amber with less blue light; how much of it covers the screen at each strength. */
    private const val AMBER = 0xFF8A00
    private val ALPHA = intArrayOf(0, 0x1C, 0x2E, 0x42)

    private val layers = ArrayList<WeakReference<View>>()

    fun label(mode: Int) = when (mode) {
        AT_NIGHT -> "At night"
        ALWAYS -> "Always"
        else -> "Off"
    }

    fun strengthLabel(s: Int) = when (s) {
        1 -> "Low"
        3 -> "High"
        else -> "Medium"
    }

    fun on(): Boolean = when (SwiftApp.prefs.nightLight) {
        ALWAYS -> true
        AT_NIGHT -> Looks.isNightTime()
        else -> false
    }

    /** Puts the layer over [activity]'s whole window (once) and shows it as set. Called on resume. */
    fun attach(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        var layer = decor.findViewWithTag<View>(TAG)
        if (layer == null) {
            if (!on()) return
            layer = View(activity).apply {
                tag = TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            decor.addView(layer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layers += WeakReference(layer)
        }
        show(layer)
    }

    /** The setting or the time changed: every open screen follows. */
    fun refreshAll() {
        layers.removeAll { it.get() == null }
        for (ref in layers) ref.get()?.let { show(it) }
    }

    private fun show(layer: View) {
        val strength = SwiftApp.prefs.nightLightStrength.coerceIn(1, 3)
        layer.setBackgroundColor((ALPHA[strength] shl 24) or AMBER)
        val visible = on()
        if (visible) layer.bringToFront()
        if ((layer.visibility == View.VISIBLE) != visible) {
            if (visible) Motion.fadeIn(layer, fromDy = 0, duration = Motion.LONG) else Motion.fadeOut(layer, toDy = 0, duration = Motion.NORMAL)
        }
    }

    private const val TAG = "swift-night-light"
}
