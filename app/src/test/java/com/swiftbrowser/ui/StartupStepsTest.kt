package com.swiftbrowser.ui

import com.swiftbrowser.ui.StartupSteps.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStepsTest {
    private fun now(ad: Boolean, attached: Boolean, vpn: Boolean = false, connecting: Boolean = false) =
        StartupSteps.now(ad, attached, vpn, connecting)

    @Test
    fun theStepsComeInTheOrderTheyHappen() {
        assertEquals(Step.BROWSER, now(ad = true, attached = false))
        assertEquals(Step.AD_BLOCKER, now(ad = true, attached = true))
        assertEquals(Step.PAGE, now(ad = false, attached = true))
    }

    @Test
    fun theVpnComesAfterTheAdBlockerAndOnlyWhenItHoldsPagesBack() {
        assertEquals(Step.AD_BLOCKER, now(ad = true, attached = true, vpn = true, connecting = true))
        assertEquals(Step.VPN_CONNECTING, now(ad = false, attached = true, vpn = true, connecting = true))
        assertEquals(Step.VPN_WAITING, now(ad = false, attached = true, vpn = true, connecting = false))
        assertEquals(Step.PAGE, now(ad = false, attached = true, vpn = false, connecting = true))
    }

    @Test
    fun aPageWithoutTheAdBlockerNeedsOnlyItsPage() {
        assertEquals(Step.PAGE, now(ad = false, attached = false))
    }

    @Test
    fun theLineNamesTheSiteWhenThereIsOne() {
        assertEquals("Opening dawn.com\u2026", StartupSteps.text(Step.PAGE, "dawn.com"))
        assertEquals("Opening your page\u2026", StartupSteps.text(Step.PAGE, ""))
        assertEquals("Loading the ad blocker\u2026", StartupSteps.text(Step.AD_BLOCKER, "dawn.com"))
    }
}
