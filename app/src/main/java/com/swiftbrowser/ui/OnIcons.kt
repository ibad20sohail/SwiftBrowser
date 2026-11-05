package com.swiftbrowser.ui

import android.widget.ImageView
import com.swiftbrowser.R

/**
 * 3.0.4: Swift's icons are Google's Material Symbols (rounded), outlined, and filled while what
 * they stand for is on or chosen, as in Google's own apps. This pairs an icon with its filled
 * one, for the places that show both states with the same icon: the ≡ menu's buttons, the
 * address bar's VPN, the player's buttons, a pinned or kept tab, Files' two folders.
 */
object OnIcons {
    /** [icon] while on: its filled version, or [icon] itself if it has none (a cross, an arrow). */
    fun filled(icon: Int): Int = when (icon) {
        R.drawable.ic_dark -> R.drawable.ic_dark_on
        R.drawable.ic_desktop -> R.drawable.ic_desktop_on
        R.drawable.ic_lite -> R.drawable.ic_lite_on
        R.drawable.ic_lock -> R.drawable.ic_lock_on
        R.drawable.ic_memory -> R.drawable.ic_memory_on
        R.drawable.ic_pin -> R.drawable.ic_pin_on
        R.drawable.ic_reader -> R.drawable.ic_reader_on
        // Repeat and shuffle are only arrows: theirs are Google's "on" ones, in a filled square.
        R.drawable.ic_repeat -> R.drawable.ic_repeat_on
        R.drawable.ic_repeat_one -> R.drawable.ic_repeat_one_on
        R.drawable.ic_shuffle -> R.drawable.ic_shuffle_on
        R.drawable.ic_speed -> R.drawable.ic_speed_on
        R.drawable.ic_star_border -> R.drawable.ic_star
        R.drawable.ic_subtitles -> R.drawable.ic_subtitles_on
        R.drawable.ic_timer -> R.drawable.ic_timer_on
        R.drawable.ic_two_panes -> R.drawable.ic_two_panes_on
        R.drawable.ic_volume_off -> R.drawable.ic_volume_off_on
        R.drawable.ic_vpn -> R.drawable.ic_vpn_on
        else -> icon
    }

    /** [icon], filled if [on]. */
    fun of(icon: Int, on: Boolean): Int = if (on) filled(icon) else icon

    /**
     * Shows [icon] in [v], filled if [on], only when that changes: for views brought up to date
     * often (on every page change, twice a second), as an icon set again is loaded again. The one
     * shown is kept where [Motion.swapIcon] keeps it.
     */
    fun show(v: ImageView, icon: Int, on: Boolean) {
        val res = of(icon, on)
        if (v.getTag(R.id.motion_icon) == res) return
        v.setTag(R.id.motion_icon, res)
        v.setImageResource(res)
    }
}
