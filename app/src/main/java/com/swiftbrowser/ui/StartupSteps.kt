package com.swiftbrowser.ui

/**
 * 3.0.5: what the start-up screen says Swift is loading, one thing at a time, in the order they
 * happen: the browser itself, the ad blocker's filters, the VPN when pages have to wait for it, and
 * then the page. Plain code, tested on the PC (StartupStepsTest).
 */
object StartupSteps {
    enum class Step { BROWSER, AD_BLOCKER, VPN_CONNECTING, VPN_WAITING, PAGE }

    /**
     * What the open tab's page is waiting for now. [waitsForAdBlocker]: it can't load before the ad
     * blocker has its filters; [adBlockerAttached]: the browser engine is up and has the ad blocker;
     * [vpnHoldsPages]: pages can't go out until the VPN is back; [vpnConnecting]: it's connecting.
     */
    fun now(waitsForAdBlocker: Boolean, adBlockerAttached: Boolean, vpnHoldsPages: Boolean, vpnConnecting: Boolean): Step = when {
        waitsForAdBlocker && !adBlockerAttached -> Step.BROWSER
        waitsForAdBlocker -> Step.AD_BLOCKER
        vpnHoldsPages && vpnConnecting -> Step.VPN_CONNECTING
        vpnHoldsPages -> Step.VPN_WAITING
        else -> Step.PAGE
    }

    /** The line under the loader; [site] is the page's site, blank when it has no name to show. */
    fun text(step: Step, site: String): String = when (step) {
        Step.BROWSER -> "Starting the browser…"
        Step.AD_BLOCKER -> "Loading the ad blocker…"
        Step.VPN_CONNECTING -> "Connecting the VPN…"
        Step.VPN_WAITING -> "Waiting for the VPN…"
        Step.PAGE -> if (site.isBlank()) "Opening your page…" else "Opening $site…"
    }
}
