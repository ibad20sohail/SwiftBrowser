package com.swiftbrowser.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabRulesTest {
    @Test
    fun webPagesAreLeftToTheirSiteName() {
        assertNull(TabRules.logKind("https://example.com/watch?v=1"))
        assertNull(TabRules.logKind("HTTP://Example.com/"))
    }

    @Test
    fun otherAddressesAreLoggedOnlyByTheirKind() {
        assertEquals("a magnet link", TabRules.logKind("magnet:?xt=urn:btih:abc&dn=Tears+of+Steel&tr=udp://tracker"))
        assertEquals("a file", TabRules.logKind("file:///sdcard/Download/My%20holiday.mp4"))
        assertEquals("a file", TabRules.logKind("content://com.android.providers/document/42"))
        assertEquals("a data address", TabRules.logKind("data:image/png;base64,iVBORw0KGgo="))
        assertEquals("a blob address", TabRules.logKind("blob:https://example.com/0f1e2d"))
        assertEquals("an extension page", TabRules.logKind("moz-extension://1234/reader.html?url=https://example.com/a"))
        assertEquals("an intent: link", TabRules.logKind("intent://x#Intent;scheme=content;end"))
        assertEquals("a tel: link", TabRules.logKind("tel:123"))
        assertEquals("about:", TabRules.logKind("about:reader?url=https%3A%2F%2Fexample.com"))
        assertEquals("a tel: link", TabRules.logKind("tel:+15551234"))
        // Nothing of the address itself, whatever its scheme looks like.
        assertEquals("an address", TabRules.logKind("not a url: secret words"))
    }

    @Test
    fun aBlankPageIsNotLogged() {
        assertEquals("", TabRules.logKind("about:blank"))
        assertEquals("", TabRules.logKind(null))
        assertEquals("", TabRules.logKind(""))
    }

    @Test
    fun aPictureAddressIsNotSaved() {
        val picture = "data:image/png;base64," + "A".repeat(3 * 1024 * 1024)
        assertFalse(TabRules.storable(picture))
        assertEquals("about:blank", TabRules.storedUrl(picture))
        assertEquals("https://example.com/", TabRules.storedUrl("https://example.com/"))
    }

    @Test
    fun aHistoryTooLongToReadBackIsNotSaved() {
        assertNull(TabRules.storedState("x".repeat(TabRules.MAX_STORED_STATE + 1)))
        assertEquals("{}", TabRules.storedState("{}"))
        assertNull(TabRules.storedState(null))
        assertEquals(TabRules.MAX_STORED_TITLE, TabRules.storedTitle("t".repeat(5000)).length)
    }

    @Test
    fun aRestoreRunsOnlyIfNothingNewerWasAskedOfItsTab() {
        assertTrue(TabRules.restoreStillWanted(token = 7, tabToken = 7))
        // The user opened a page meanwhile (TabManager.load sets the tab's token to 0).
        assertFalse(TabRules.restoreStillWanted(token = 7, tabToken = 0))
        // A newer restore took its place (the tab was let go of and opened again).
        assertFalse(TabRules.restoreStillWanted(token = 7, tabToken = 8))
        assertFalse(TabRules.restoreStillWanted(token = 0, tabToken = 0))
    }

    @Test
    fun restoredPagesWaitForTheAdBlockerUntilItIsReady() {
        val now = 1_000_000L
        assertEquals(TabRules.USER_WAIT_MS, TabRules.adBlockWait(userNavAt = now - 500, now = now))
        assertEquals(0L, TabRules.adBlockWait(userNavAt = now - 60_000, now = now))
        assertEquals(0L, TabRules.adBlockWait(userNavAt = 0L, now = now))
    }

    @Test
    fun aPageThatEndsAgainWithinAMinuteShowsTheCrashPage() {
        assertEquals(TabRules.Revive.CRASH_PAGE, TabRules.revive(now = 100_000, autoRevivedAt = 70_000, onScreen = true))
        assertEquals(TabRules.Revive.NOW, TabRules.revive(now = 200_000, autoRevivedAt = 70_000, onScreen = true))
        // Never loaded again before: even just after the phone started.
        assertEquals(TabRules.Revive.NOW, TabRules.revive(now = 30_000, autoRevivedAt = 0, onScreen = true))
    }

    @Test
    fun aPageThatEndsWhileSwiftIsAwayLoadsWhenItIsBack() {
        assertEquals(TabRules.Revive.WHEN_BACK, TabRules.revive(now = 200_000, autoRevivedAt = 0, onScreen = false))
    }

    @Test
    fun aLinkFromAnotherAppFindsTheTabShowingIt() {
        assertTrue(TabRules.sameAddress("https://example.com/", "https://example.com"))
        assertTrue(TabRules.sameAddress("https://example.com/a?b=1", "https://example.com/a?b=1"))
        assertFalse(TabRules.sameAddress("https://example.com/a", "https://example.com/b"))
        assertFalse(TabRules.sameAddress("https://example.com/#top", "https://example.com/"))
    }

    // ------------------------------------------------------------------ unloading (3.0.1)

    private val idleSince = 1_000L

    private fun tab(id: Long, lastUsed: Long, spared: Boolean = false, kept: Boolean = false) =
        TabRules.Loaded(id, lastUsed, spared, kept)

    @Test
    fun theLongestUnusedTabsGoFirstUntilTheLimitIsKept() {
        val others = listOf(tab(3, 5_000), tab(1, 2_000), tab(2, 3_000))
        // The open tab and three others with a limit of 2: the two oldest go.
        assertEquals(listOf(1L, 2L), TabRules.toUnload(others, openCounts = true, max = 2, idleSince = idleSince))
        assertEquals(emptyList<Long>(), TabRules.toUnload(others, openCounts = true, max = 4, idleSince = idleSince))
    }

    @Test
    fun aPlayingTabStaysButStillCounts() {
        val others = listOf(tab(1, 2_000, spared = true), tab(2, 3_000))
        assertEquals(listOf(2L), TabRules.toUnload(others, openCounts = true, max = 2, idleSince = idleSince))
    }

    @Test
    fun aKeptTabStaysAndLeavesRoomForOthers() {
        val others = listOf(tab(1, 2_000, kept = true), tab(2, 3_000))
        assertEquals(emptyList<Long>(), TabRules.toUnload(others, openCounts = true, max = 2, idleSince = idleSince))
        // Even with battery saver or low memory (a limit of 1) and long unused, it stays.
        val old = listOf(tab(1, 10, kept = true), tab(2, 20))
        assertEquals(listOf(2L), TabRules.toUnload(old, openCounts = true, max = 1, idleSince = idleSince))
    }

    @Test
    fun tabsNotOpenedForHalfAnHourGoWhateverTheNumber() {
        val others = listOf(tab(1, 500), tab(2, 3_000), tab(3, 700, spared = true))
        assertEquals(listOf(1L), TabRules.toUnload(others, openCounts = true, max = 10, idleSince = idleSince))
    }

    @Test
    fun aPictureOfOneColourIsNoPicture() {
        assertTrue(TabRules.looksBlank(IntArray(144) { 0xFF000000.toInt() }))
        assertTrue(TabRules.looksBlank(IntArray(144) { if (it % 7 == 0) 0xFF0A0A0A.toInt() else 0xFF050505.toInt() }))
        // A white page with a few dark letters is a page.
        assertFalse(TabRules.looksBlank(IntArray(144) { if (it % 13 == 0) 0xFF202020.toInt() else 0xFFFFFFFF.toInt() }))
        assertTrue(TabRules.looksBlank(IntArray(0)))
    }

    @Test
    fun warningsWhileOnScreenCountOnlyWithMemoryReallyLow() {
        // 5, 10 and 15 come with Swift on screen, often with most of the memory free.
        for (level in listOf(5, 10, 15)) {
            assertEquals(TabRules.Warning.NONE, TabRules.onWarning(level, memoryLow = false))
            assertEquals(TabRules.Warning.TRIM, TabRules.onWarning(level, memoryLow = true))
        }
        // Merely leaving Swift isn't a reason; in the background, background tabs go.
        assertEquals(TabRules.Warning.NONE, TabRules.onWarning(20, memoryLow = true))
        for (level in listOf(40, 60, 80)) assertEquals(TabRules.Warning.BACKGROUND_TABS, TabRules.onWarning(level, memoryLow = false))
    }

    // ------------------------------------------------------------------ pinned tabs (3.0.4)

    private val pins = hashSetOf<String>()
    private val isPinned: (String) -> Boolean = { it in pins }

    @Test
    fun pinnedTabsComeFirstAndEveryoneKeepsTheirOrder() {
        pins += listOf("p2", "p1")
        assertEquals(listOf("p2", "p1", "a", "b", "c"), TabRules.pinnedFirst(listOf("a", "p2", "b", "p1", "c"), isPinned))
        // Nothing pinned: nothing moves.
        pins.clear()
        assertEquals(listOf("a", "b"), TabRules.pinnedFirst(listOf("a", "b"), isPinned))
    }

    @Test
    fun aTabPinnedGoesAfterThoseAlreadyPinned() {
        pins += listOf("p1", "p2")
        val before = listOf("p1", "p2", "a", "b", "c")
        pins += "b"
        assertEquals(listOf("p1", "p2", "b", "a", "c"), TabRules.withPinChanged(before, "b", isPinned))
        // In the order they were pinned: one pinned later goes after it.
        pins += "c"
        assertEquals(listOf("p1", "p2", "b", "c", "a"), TabRules.withPinChanged(listOf("p1", "p2", "b", "a", "c"), "c", isPinned))
    }

    @Test
    fun aTabUnpinnedGoesFirstOfTheOthers() {
        pins += listOf("p1", "p2", "p3")
        pins -= "p1"
        assertEquals(listOf("p2", "p3", "p1", "a", "b"), TabRules.withPinChanged(listOf("p1", "p2", "p3", "a", "b"), "p1", isPinned))
        // The last pinned tab unpinned: the front of the list.
        pins.clear()
        assertEquals(listOf("p3", "a", "b"), TabRules.withPinChanged(listOf("p3", "a", "b"), "p3", isPinned))
    }

    @Test
    fun aTabOpenedFromAPinnedOneGoesAfterThePinnedTabs() {
        // A link opened in a new tab goes next to its tab, which is pinned here: it moves after them.
        pins += listOf("p1", "p2")
        assertEquals(listOf("p1", "p2", "new", "a"), TabRules.pinnedFirst(listOf("p1", "new", "p2", "a"), isPinned))
    }

    // ------------------------------------------------------------------ sound and the group strip (3.0.4)

    @Test
    fun aMutedTabSaysSoUntilUnmuted() {
        assertEquals(TabRules.Sound.NONE, TabRules.sound(playing = false, muted = false))
        assertEquals(TabRules.Sound.PLAYING, TabRules.sound(playing = true, muted = false))
        assertEquals(TabRules.Sound.MUTED, TabRules.sound(playing = true, muted = true))
        // Paused, or its page went: still muted.
        assertEquals(TabRules.Sound.MUTED, TabRules.sound(playing = false, muted = true))
    }

    @Test
    fun theGroupStripShowsWithAGroupedTabsPage() {
        assertEquals(TabRules.Strip.SHOWN, TabRules.strip(enabled = true, grouped = true, hasPage = true, fullScreen = false, pageShown = true, typing = false))
        assertEquals(TabRules.Strip.NONE, TabRules.strip(enabled = true, grouped = false, hasPage = true, fullScreen = false, pageShown = true, typing = false))
        assertEquals(TabRules.Strip.NONE, TabRules.strip(enabled = false, grouped = true, hasPage = true, fullScreen = false, pageShown = true, typing = false))
        // A new tab's home page has no page to make room in; a full-screen video has the whole screen.
        assertEquals(TabRules.Strip.NONE, TabRules.strip(enabled = true, grouped = true, hasPage = false, fullScreen = false, pageShown = false, typing = false))
        assertEquals(TabRules.Strip.NONE, TabRules.strip(enabled = true, grouped = true, hasPage = true, fullScreen = true, pageShown = true, typing = false))
    }

    @Test
    fun theGroupStripKeepsItsRoomWhileItMakesWay() {
        // Typing an address, or the home page over the tab's page: hidden, but the page keeps its size.
        assertEquals(TabRules.Strip.ROOM, TabRules.strip(enabled = true, grouped = true, hasPage = true, fullScreen = false, pageShown = true, typing = true))
        assertEquals(TabRules.Strip.ROOM, TabRules.strip(enabled = true, grouped = true, hasPage = true, fullScreen = false, pageShown = false, typing = false))
    }

    @Test
    fun anOpenTabKeptLoadedDoesNotCount() {
        val others = listOf(tab(1, 2_000))
        assertEquals(listOf(1L), TabRules.toUnload(others, openCounts = true, max = 1, idleSince = idleSince))
        assertEquals(emptyList<Long>(), TabRules.toUnload(others, openCounts = false, max = 1, idleSince = idleSince))
    }
}
