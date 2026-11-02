package com.swiftbrowser.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val blocking = SettingsSearch.Entry("Blocking level", "High: ads, trackers and pop-ups", "ads off balanced high extreme ublock", "Ad blocking")
    private val cookiePopups = SettingsSearch.Entry("Hide cookie pop-ups", "Hides \"Accept cookies\" notices on most sites", "cookies consent gdpr accept notices banner", "Ad blocking")
    private val vpn = SettingsSearch.Entry("VPN", "", "vpn proton wireguard country ip hide location change country", "VPN")
    private val skip = SettingsSearch.Entry("Double-tap to skip", "In full screen: double-tap a side to skip 10 seconds", "seek forward back 10 seconds hold speed 2x", "Video & downloads")
    private val clearData = SettingsSearch.Entry(
        "Clear browsing data", "The last hour, day, week, days you choose or all of it: history, cookies, the cache and more",
        "history cookies cache delete last hour day week time", "Storage & data",
    )
    private val askDownloads = SettingsSearch.Entry("Ask before downloading", "Sites can't start a download without asking you", "confirm download", "Video & downloads")
    private val lite = SettingsSearch.Entry("Lite mode", "Plainer sites that scroll the smoothest", "smooth scrolling fast animations shadows blur", "Speed & memory")
    private val vibration = SettingsSearch.Entry("Light vibration", "A short buzz when you hold something or drag a tab", "haptic vibrate feedback touch", "Look & screen")
    private val backup = SettingsSearch.Entry("Back up", "Settings, bookmarks, history, open tabs and site settings, to one file", "export save copy backup", "Storage & data")

    private fun finds(query: String, e: SettingsSearch.Entry) = SettingsSearch.matches(query, e)

    @Test
    fun wordsInsideOtherWordsDontCount() {
        // "downloads" holds "ads", "skip" and "zip" hold "ip".
        assertFalse(finds("downloads", blocking))
        assertFalse(finds("downloads", cookiePopups))
        assertFalse(finds("skip", vpn))
        assertFalse(finds("zip", vpn))
        assertTrue(finds("skip", skip))
        assertTrue(finds("downloads", askDownloads))
    }

    @Test
    fun everyWordOfASearchMustBeFound() {
        assertTrue(finds("clear cookies", clearData))
        assertTrue(finds("delete history", clearData))
        assertTrue(finds("cookies clear", clearData))
        assertFalse(finds("clear cookies", cookiePopups))
        assertFalse(finds("clear vpn", vpn))
    }

    @Test
    fun fillersAreLeftOut() {
        assertTrue(finds("how to clear the cache", clearData))
        assertTrue(finds("turn off the vpn", vpn))
    }

    @Test
    fun theLastWordCanBeStillBeingTyped() {
        assertTrue(finds("clear coo", clearData))
        assertTrue(finds("vibr", vibration))
        assertFalse(finds("coo clear", clearData))
    }

    @Test
    fun pluralsAndEverydayWords() {
        assertTrue(finds("cookie", clearData))
        assertTrue(finds("ad", blocking))
        assertTrue(finds("ads", cookiePopups))
        assertTrue(finds("battery", lite))
        assertTrue(finds("vibrating", vibration))
        assertTrue(finds("ip", vpn))
        assertTrue(finds("phone", backup))
        assertTrue(finds("batteries", lite))
    }

    @Test
    fun nothingForNothing() {
        assertFalse(finds("", clearData))
        assertFalse(finds("   ", clearData))
        assertFalse(finds("zebra", clearData))
    }
}
