package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderTest {
    @Test
    fun continueComesFirstUnlessChosenOtherwise() {
        assertEquals(listOf("continue", "shortcuts", "news"), HomeOrder.parse(null))
        assertEquals(listOf("continue", "shortcuts", "news"), HomeOrder.parse(""))
        assertEquals(listOf("news", "shortcuts", "continue"), HomeOrder.parse("news,shortcuts,continue"))
    }

    @Test
    fun nothingIsEverLeftOutOrShownTwice() {
        assertEquals(listOf("shortcuts", "continue", "news"), HomeOrder.parse("shortcuts"))
        assertEquals(listOf("news", "continue", "shortcuts"), HomeOrder.parse("news,news,weather, continue"))
    }

    @Test
    fun partsMoveOnePlaceWithinTheList() {
        val order = HomeOrder.DEFAULT
        assertEquals(listOf("shortcuts", "continue", "news"), HomeOrder.move(order, "shortcuts", -1))
        assertEquals(listOf("continue", "news", "shortcuts"), HomeOrder.move(order, "shortcuts", 1))
        assertEquals(order, HomeOrder.move(order, "continue", -1))
        assertEquals(order, HomeOrder.move(order, "news", 1))
        assertEquals("continue,shortcuts,news", HomeOrder.encode(order))
    }
}
