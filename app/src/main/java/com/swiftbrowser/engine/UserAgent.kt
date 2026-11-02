package com.swiftbrowser.engine

import com.swiftbrowser.SwiftApp
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.GeckoSession

/**
 * 3.0: what pages are told the browser is. "Tell sites a newer Firefox version" (Settings →
 * Privacy & security, for experts): Swift's engine is Firefox 143, the last for Android 7, and some
 * sites turn versions that old away or keep saying the browser is out of date. With it on they're
 * told Firefox [NEWER] (the current long-term version) instead; the rest stays as the engine writes
 * it, for the mobile and the desktop version of sites alike.
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

    private val newer: Boolean get() = SwiftApp.prefs.newerFirefoxVersion

    /** What a tab sends instead of the engine's own: null with the setting off (the engine's own then). */
    fun overrideFor(desktop: Boolean): String? {
        if (!newer) return null
        val mobile = withVersion(GeckoSession.getDefaultUserAgent(), NEWER)
        return if (desktop) desktopOf(mobile) else mobile
    }

    /** The phone's user agent, for Swift's own downloads (news, pictures) and players. */
    val mobile: String
        get() = GeckoSession.getDefaultUserAgent().let { if (newer) withVersion(it, NEWER) else it }

    /** The setting changed: the open tabs' next pages say so. */
    fun applyToOpenTabs() {
        for (tab in TabManager.tabs) tab.session?.settings?.setUserAgentOverride(overrideFor(tab.desktop))
    }
}
