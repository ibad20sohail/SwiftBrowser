package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderTest {
    @Test
    fun shortcutsComeFirstUnlessChosenOtherwise() {
        assertEquals(listOf("shortcuts", "continue", "news"), HomeOrder.parse(null))
        assertEquals(listOf("shortcuts", "continue", "news"), HomeOrder.parse(""))
        assertEquals(listOf("news", "shortcuts", "continue"), HomeOrder.parse("news,shortcuts,continue"))
        // 3.0.2's own first order is what the start-up step clears (SwiftApp.migratePrefs).
        assertEquals("continue,shortcuts,news", HomeOrder.OLD_DEFAULT)
    }

    @Test
    fun nothingIsEverLeftOutOrShownTwice() {
        assertEquals(listOf("shortcuts", "continue", "news"), HomeOrder.parse("shortcuts"))
        assertEquals(listOf("news", "continue", "shortcuts"), HomeOrder.parse("news,news,weather, continue"))
    }

    @Test
    fun partsMoveOnePlaceWithinTheList() {
        val order = HomeOrder.DEFAULT
        assertEquals(order, HomeOrder.move(order, "shortcuts", -1))
        assertEquals(listOf("continue", "shortcuts", "news"), HomeOrder.move(order, "shortcuts", 1))
        assertEquals(listOf("continue", "shortcuts", "news"), HomeOrder.move(order, "continue", -1))
        assertEquals(order, HomeOrder.move(order, "news", 1))
        assertEquals("shortcuts,continue,news", HomeOrder.encode(order))
    }
}
