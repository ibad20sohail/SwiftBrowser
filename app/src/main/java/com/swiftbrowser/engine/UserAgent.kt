package com.swiftbrowser.engine

import org.mozilla.geckoview.GeckoSession

/**
 * 3.0: what pages are told the browser is. Swift's engine is Firefox 143, the last for Android 7, and
 * some sites turn versions that old away or keep saying the browser is out of date, so pages are told
 * Firefox [NEWER] (the current long-term version) instead; the rest stays as the engine writes it, for
 * the mobile and the desktop version of sites alike. 3.0.11: always (it was "Avoid 'browser too old'
 * messages" in Settings, off at first).
 */
object UserAgent {
    /** The Firefox ESR line of the time. */
    const val NEWER = 153

    private val VERSION = Regex("(rv:|Gecko/|Firefox/)\\d+\\.\\d+")
    private val PHONE = Regex("Android \\d.+?; [a-zA-Z]+")
    private val GECKO = Regex("Gecko/[0-9.]+")

    /** [ua] with its Firefox version (rv:, Gecko/ and Firefox/) changed to [version]. */
    fun withVersion(ua: String, version: Int): String = VERSION.replace(ua) { it.groupValues[1] + "$version.0" }

    /** The desktop version of a phone's user agent, made the way the engine makes it (GeckoViewSettings). */
    fun desktopOf(mobile: String): String = GECKO.replace(PHONE.replace(mobile, "X11; Linux x86_64"), "Gecko/20100101")

    /** What a tab sends instead of the engine's own. */
    fun overrideFor(desktop: Boolean): String = forTab(desktop)

    /** What a tab's pages are told (3.0.1): for the videos and downloads Swift fetches for it. */
    fun forTab(desktop: Boolean): String = if (desktop) desktopOf(mobile) else mobile

    /** The phone's user agent, for Swift's own downloads (news, pictures) and players. */
    val mobile: String
        get() = withVersion(GeckoSession.getDefaultUserAgent(), NEWER)
}
