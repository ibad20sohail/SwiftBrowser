package com.swiftbrowser.ui

import android.widget.ImageView

/**
 * 3.0.5: pictures a little dimmer in the dark look, so a bright one doesn't glare on the dark screen:
 * tab pictures, news pictures, Continue cards' pictures and site icons, the same way for all of them
 * (the picture's own alpha: a number on the ImageView that costs nothing to set or draw, with
 * nothing made per picture, and a tinted icon's tint isn't disturbed as a colour filter would).
 * Only for what is routed here: never videos, the player's cover, the picture viewer or Files' own
 * previews.
 */
object SoftPictures {
    /** 90%: about 10% less light, on a screen where what shows through is close to black. */
    private const val DIMMED = 230

    /** [v] (a picture or a site's icon) a little dimmer when [dark]: the dark look, or incognito's dark cards. */
    fun apply(v: ImageView, dark: Boolean = Palette.isNight(v.context)) {
        v.imageAlpha = if (dark) DIMMED else 255
    }
}
