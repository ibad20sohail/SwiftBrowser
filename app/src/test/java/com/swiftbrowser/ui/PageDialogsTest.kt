package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.WebRequestError

/** 3.0: endless page dialogs (DialogLoop) and which failed pages wait for the connection (ErrorPage). */
class PageDialogsTest {
    @Test
    fun theThirdDialogInARowOffersToBlock() {
        var inARow = 0
        var closedAt = -1L
        val shown = ArrayList<Boolean>()
        // A loop: each alert comes right after the one before is closed (read for 2 s each).
        var t = 100_000L
        repeat(4) {
            inARow = DialogLoop.inARow(inARow, closedAt, t)
            shown += DialogLoop.offers(inARow)
            t += 2_000
            closedAt = t
            t += 50
        }
        assertEquals(listOf(false, false, true, true), shown)
    }

    @Test
    fun dialogsFarApartDontCount() {
        var inARow = DialogLoop.inARow(0, -1L, 0)
        assertEquals(1, inARow)
        inARow = DialogLoop.inARow(inARow, 1_000, 5_000)
        assertEquals(2, inARow)
        // The next one only half a minute after the second closed: a new row.
        inARow = DialogLoop.inARow(inARow, 6_000, 36_000)
        assertEquals(1, inARow)
        assertFalse(DialogLoop.offers(inARow))
        assertTrue(DialogLoop.offers(3))
    }

    @Test
    fun onlyLostConnectionsWait() {
        // A mistyped site while online is not coming back by waiting (2.9 reloaded it on every visit to its tab).
        assertFalse(ErrorPage.waitsForConnection(WebRequestError.ERROR_UNKNOWN_HOST, online = true))
        assertTrue(ErrorPage.waitsForConnection(WebRequestError.ERROR_UNKNOWN_HOST, online = false))
        assertFalse(ErrorPage.waitsForConnection(WebRequestError.ERROR_NET_TIMEOUT, online = true))
        assertTrue(ErrorPage.waitsForConnection(WebRequestError.ERROR_CONNECTION_REFUSED, online = false))
        assertTrue(ErrorPage.waitsForConnection(WebRequestError.ERROR_NET_INTERRUPT, online = true))
        assertTrue(ErrorPage.waitsForConnection(WebRequestError.ERROR_OFFLINE, online = true))
        assertFalse(ErrorPage.waitsForConnection(WebRequestError.ERROR_SECURITY_BAD_CERT, online = false))
    }
}
