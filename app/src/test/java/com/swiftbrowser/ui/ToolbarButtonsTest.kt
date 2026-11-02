package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0: the tabs button stays in the address bar, as the menu button does (ToolbarButtons.fromSaved). */
class ToolbarButtonsTest {
    @Test
    fun aBarArrangedWithoutTabsGetsItBackBeforeTheMenu() {
        val l = ToolbarButtons.fromSaved("profile,home|menu") { false }
        assertEquals(listOf("profile", "home"), l.left)
        assertEquals(listOf("tabs", "menu"), l.right)
    }

    @Test
    fun aFullBarMakesRoomForTabsFromTheLeft() {
        val l = ToolbarButtons.fromSaved("profile,home|share,menu") { false }
        assertEquals(listOf("home"), l.left)
        assertEquals(listOf("share", "tabs", "menu"), l.right)
    }

    @Test
    fun movedTabsAndMenuStayWhereTheyAre() {
        val l = ToolbarButtons.fromSaved("tabs,profile|menu") { false }
        assertEquals(listOf("tabs", "profile"), l.left)
        assertEquals(listOf("menu"), l.right)
    }

    @Test
    fun aMenuOnTheLeftHasTabsAddedOnTheRight() {
        val l = ToolbarButtons.fromSaved("menu|share") { false }
        assertEquals(listOf("menu"), l.left)
        assertEquals(listOf("share", "tabs"), l.right)
    }

    @Test
    fun beforeArrangingTheOldHomeButtonCarriesOver() {
        val l = ToolbarButtons.fromSaved(null) { true }
        assertEquals(listOf("profile", "home"), l.left)
        assertEquals(listOf("tabs", "menu"), l.right)
        assertEquals(listOf("profile"), ToolbarButtons.fromSaved(null) { false }.left)
    }

    @Test
    fun neverMoreThanFourAndNeverWithoutMenuOrTabs() {
        val l = ToolbarButtons.fromSaved("profile,home,share,bookmark|reload,new_tab,unknown") { false }
        assertEquals(ToolbarButtons.MAX, l.size)
        assertTrue(l.has("menu"))
        assertTrue(l.has("tabs"))
    }
}
