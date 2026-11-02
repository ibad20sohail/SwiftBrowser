package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncognitoChoicesTest {
    @Test
    fun withoutAChoiceTheSavedSettingCounts() {
        val c = IncognitoChoices<Float>()
        assertEquals(1.25f, c.get("example.com", 1.25f))
        assertNull(c.get("example.com", null))
        assertTrue(c.isEmpty())
    }

    @Test
    fun anIncognitoChoiceHidesTheSavedOneEvenWhenItIsNothing() {
        val c = IncognitoChoices<Float>()
        c.set("example.com", 1.5f)
        assertEquals(1.5f, c.get("example.com", 1.25f))
        // "Same as all sites" chosen in incognito: the saved size doesn't come back.
        c.set("example.com", null)
        assertNull(c.get("example.com", 1.25f))
        // Other sites keep their saved setting.
        assertEquals(0.9f, c.get("other.org", 0.9f))
    }

    @Test
    fun aChoiceFromANormalTabTakesOverInIncognito() {
        val c = IncognitoChoices<Boolean>()
        c.set("example.com", true)
        c.drop("example.com")
        assertEquals(false, c.get("example.com", false))
    }

    @Test
    fun closingIncognitoForgetsEverythingAndSaysIfThereWasAnything() {
        val c = IncognitoChoices<Boolean>()
        assertFalse(c.clear())
        c.set("example.com", true)
        assertTrue(c.clear())
        assertTrue(c.isEmpty())
        assertEquals(false, c.get("example.com", false))
    }

    @Test
    fun levelsInForceAreTheSavedOnesWithIncognitoOnTop() {
        val c = IncognitoChoices<String>()
        val saved = linkedMapOf("a.com" to "off", "b.com" to "extreme")
        assertEquals(saved, c.applyTo(saved))
        c.set("a.com", null) // back to the level for all sites
        c.set("b.com", "high") // changed
        c.set("c.com", "off") // added
        assertEquals(mapOf("b.com" to "high", "c.com" to "off"), c.applyTo(saved))
        // The saved map itself is left alone.
        assertEquals(mapOf("a.com" to "off", "b.com" to "extreme"), saved)
    }

    @Test
    fun sitesOnFollowTheIncognitoSwitches() {
        val c = IncognitoChoices<Boolean>()
        val saved = setOf("a.com", "b.com")
        assertEquals(saved, c.sitesOn(saved))
        c.set("a.com", false)
        c.set("c.com", true)
        assertEquals(setOf("b.com", "c.com"), c.sitesOn(saved))
    }
}
