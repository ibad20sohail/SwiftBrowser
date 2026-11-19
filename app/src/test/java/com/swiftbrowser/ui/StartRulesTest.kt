package com.swiftbrowser.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRulesTest {
    @Test
    fun aSiteOpensWithoutTheStartScreen() {
        assertFalse(StartRules.startScreen(link = false, newTab = false, tabIsSite = true))
    }

    @Test
    fun theHomePageHasTheStartScreen() {
        assertTrue(StartRules.startScreen(link = false, newTab = false, tabIsSite = false))
    }

    @Test
    fun noTabsYetMeansTheHomePage() {
        assertTrue(StartRules.startScreen(link = false, newTab = false, tabIsSite = null))
    }

    @Test
    fun aLinkFromAnotherAppIsASite() {
        assertFalse(StartRules.startScreen(link = true, newTab = false, tabIsSite = false))
        assertFalse(StartRules.startScreen(link = true, newTab = false, tabIsSite = null))
    }

    @Test
    fun aNewTabShortcutIsTheHomePage() {
        assertTrue(StartRules.startScreen(link = false, newTab = true, tabIsSite = true))
    }
}
