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
}
