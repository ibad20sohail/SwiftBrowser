package com.swiftbrowser.torrent

import com.swiftbrowser.download.Downloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNotesTest {
    private val long = 60_000L

    @Test
    fun aShortWaitSaysNothing() {
        assertNull(WatchNotes.of(1_000, long, Downloads.QUEUED, Torrents.PAUSED_VPN, 0))
        assertNull(WatchNotes.of(0, long, Downloads.PAUSED, null, 0))
    }

    @Test
    fun aRuleThatPausedTheTorrentIsSaidInPlainWords() {
        val vpn = WatchNotes.of(5_000, 0, Downloads.QUEUED, Torrents.PAUSED_VPN, 0)
        assertTrue(vpn!!.contains("VPN"))
        assertTrue(WatchNotes.of(5_000, 0, Downloads.QUEUED, Torrents.WAITING_WIFI, 0)!!.contains("Wi-Fi"))
        assertTrue(WatchNotes.of(5_000, 0, Downloads.QUEUED, Torrents.PAUSED_BATTERY, 0)!!.contains("battery"))
        assertTrue(WatchNotes.of(5_000, 0, Downloads.QUEUED, Torrents.PAUSED_STORAGE, 0)!!.contains("space"))
        assertNotNull(WatchNotes.of(5_000, 0, Downloads.QUEUED, Torrents.WAITING_TURN, 0))
        assertNotNull(WatchNotes.of(5_000, 0, Downloads.QUEUED, Torrents.NOBODY, 0))
        assertEquals(Torrents.CHECKING, WatchNotes.of(5_000, 0, Downloads.RUNNING, Torrents.CHECKING, 3))
        assertTrue(WatchNotes.of(5_000, 0, Downloads.PAUSED, null, 0)!!.contains("Downloads"))
        assertEquals("The torrent stopped: No one shared it for hours.", WatchNotes.of(5_000, 0, Downloads.FAILED, Torrents.NOBODY_FAILED, 0))
        assertEquals("The torrent stopped.", WatchNotes.of(5_000, 0, Downloads.FAILED, null, 0))
        // No technical words: never libtorrent's, never "peers" or "pieces".
        for (n in listOf(vpn, WatchNotes.of(5_000, long, Downloads.RUNNING, null, 0), WatchNotes.of(5_000, long, Downloads.RUNNING, null, 4))) {
            assertTrue(n!!, !n.contains("peer", ignoreCase = true) && !n.contains("piece", ignoreCase = true))
        }
    }

    @Test
    fun piecesOnTheirWaySayNothingUntilNothingComesForAWhile() {
        // Running, with pieces coming in: the player's loading circle is enough.
        assertNull(WatchNotes.of(long, 5_000, Downloads.RUNNING, null, 4))
        val quiet = WatchNotes.of(long, WatchNotes.QUIET_MS, Downloads.RUNNING, null, 4)
        val nobody = WatchNotes.of(long, WatchNotes.QUIET_MS, Downloads.RUNNING, null, 0)
        assertNotNull(quiet)
        assertNotNull(nobody)
        assertTrue(quiet != nobody)
        // Finished meanwhile: nothing waits.
        assertNull(WatchNotes.of(long, long, Downloads.DONE, null, 0))
    }

    @Test
    fun aNoteIsSaidOnceAndAgainEveryHalfMinute() {
        var t = 1_000L
        val r = WatchNotes.Repeat { t }
        assertEquals("a", r.next("a"))
        t += 10_000
        assertNull(r.next("a"))
        // Another one is said at once.
        assertEquals("b", r.next("b"))
        t += WatchNotes.AGAIN_MS
        assertEquals("b", r.next("b"))
        // Gone and back: said again.
        assertNull(r.next(null))
        assertEquals("b", r.next("b"))
    }
}
