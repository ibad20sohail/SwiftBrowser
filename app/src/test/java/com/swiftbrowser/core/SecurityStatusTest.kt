package com.swiftbrowser.core

import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.SecurityStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStatusTest {
    private fun inputs(
        adLevel: AdLevel = AdLevel.HIGH,
        uboReady: Boolean = true,
        secureDns: String = "cloudflare",
        vpn: SecurityStatus.Vpn = SecurityStatus.Vpn.OFF,
        passwords: Int? = 0,
        offer: Boolean = true,
        location: Int = 0,
    ) = SecurityStatus.Inputs(adLevel, uboReady, secureDns, httpsOnly = false, saferBrowsing = false, passwords = passwords, offerPasswords = offer, vpn = vpn, vpnServer = "NL#1", locationSites = location)

    private fun state(i: SecurityStatus.Inputs, name: String) = SecurityStatus.lines(i).first { it.name == name }.state

    @Test
    fun onlyWhatSwiftSawWorkingIsChecked() {
        assertEquals(State.CHECKED, state(inputs(uboReady = true), "Ad blocking"))
        assertEquals(State.ON, state(inputs(uboReady = false), "Ad blocking"))
        assertEquals(State.CHECKED, state(inputs(vpn = SecurityStatus.Vpn.ON), "VPN"))
        assertEquals(State.ON, state(inputs(vpn = SecurityStatus.Vpn.CONNECTING), "VPN"))
        assertEquals(State.OFF, state(inputs(vpn = SecurityStatus.Vpn.FAILED), "VPN"))
        // Settings that are only switched on are never "Checked".
        val lines = SecurityStatus.lines(inputs(vpn = SecurityStatus.Vpn.OFF))
        assertEquals(listOf("Ad blocking"), lines.filter { it.state == State.CHECKED }.map { it.name })
    }

    @Test
    fun thePhishingListFollowsTheLevel() {
        assertEquals(State.ON, state(inputs(adLevel = AdLevel.HIGH), "Fake login (phishing) sites"))
        assertEquals(State.ON, state(inputs(adLevel = AdLevel.EXTREME), "Fake login (phishing) sites"))
        assertEquals(State.OFF, state(inputs(adLevel = AdLevel.BALANCED), "Fake login (phishing) sites"))
        assertEquals(State.OFF, state(inputs(adLevel = AdLevel.OFF), "Fake login (phishing) sites"))
        assertEquals(State.OFF, state(inputs(adLevel = AdLevel.OFF), "Ad blocking"))
    }

    @Test
    fun secureDnsSaysWhoIsAskedAndThatItFallsBack() {
        val quad9 = SecurityStatus.lines(inputs(secureDns = "quad9")).first { it.name == "Private site lookups" }
        assertEquals(State.ON, quad9.state)
        assertTrue(quad9.detail.startsWith("Quad9 looks up sites for you"))
        assertTrue(quad9.detail.contains("your internet provider does"))
        assertEquals(State.OFF, state(inputs(secureDns = "off"), "Private site lookups"))
    }

    @Test
    fun passwordsAndLocationAreCounted() {
        assertTrue(SecurityStatus.lines(inputs(passwords = 3)).first { it.name == "Saved passwords" }.detail.startsWith("3 for this profile"))
        assertEquals(State.OFF, state(inputs(passwords = 0, offer = false), "Saved passwords"))
        assertEquals("1 site may use it without asking (Site settings).", SecurityStatus.lines(inputs(location = 1)).first { it.name == "Location" }.detail)
        assertEquals(State.OFF, state(inputs(location = 0), "Location"))
    }

    @Test
    fun theTextExplainsTheWords() {
        val text = SecurityStatus.text(SecurityStatus.lines(inputs()))
        assertTrue(text.startsWith("Ad blocking: Checked. High: the ad blocker is running"))
        assertTrue(text.endsWith("On: set, but not something Swift can check from here."))
    }
}
