package com.swiftbrowser.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorrentStreamTest {
    private var t = 1_000L
    private val moves = ArrayList<Long>()
    private val mb = 1L shl 20

    /** A 100 MB film in 1 MB pieces, its start and end pinned. */
    private fun film(): TorrentStream {
        val map = PieceMap(0, 100 * mb, mb.toInt(), 100 * mb)
        return TorrentStream(1, "hash", 0, File("film.mkv"), map, map.edgePieces(PieceMap.HEAD_BYTES, PieceMap.tailBytes(100 * mb)), 1, { t }) { moves += it.position }
    }

    @Test
    fun aWaitAsksOnceForEachPlace() {
        val s = film()
        s.readingAt(10)
        s.readingAt(500)
        // Another piece (a seek, or the next one): asked again.
        s.readingAt(5 * mb)
        assertEquals(listOf(10L, 5 * mb), moves)
        assertEquals(5 * mb, s.position)
    }

    @Test
    fun theEdgesThenTheWindowAreWantedFirst() {
        val s = film()
        // 16 MB ahead in 1 MB pieces: 16 after where it reads, the edges before them.
        val want = s.wanted()
        assertEquals(listOf(0, 99), want.take(2))
        assertEquals((1..15).toList(), want.drop(2))
        s.gotPiece(0)
        s.gotPiece(99)
        s.position = 50 * mb
        assertEquals((50..65).toList(), s.wanted())
    }

    @Test
    fun piecesComeInAndTheWholeFileOnceItIsDone() {
        val s = film()
        assertFalse(s.have(3))
        t += 5_000
        s.gotPiece(3)
        assertTrue(s.have(3))
        assertEquals(0L, s.quietMs())
        s.fill { it in 10..12 }
        assertTrue(s.have(11))
        assertFalse(s.have(13))
        // Finished while it plays: nothing waits.
        s.markComplete()
        assertTrue(s.have(13))
        assertEquals(mb, s.waiter.await(13 * mb, mb, 10))
    }
}
