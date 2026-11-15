package com.swiftbrowser.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceMapTest {
    // A torrent of three files back to back (1000, 5000 and 300 bytes) in pieces of 1024: seven
    // pieces, the last one 156 bytes. The middle file starts inside piece 0 and ends inside piece 5.
    private val pl = 1024
    private val total = 6300L
    private val first = PieceMap(0, 1000, pl, total)
    private val middle = PieceMap(1000, 5000, pl, total)
    private val last = PieceMap(6000, 300, pl, total)

    @Test
    fun aFileInsideATorrentMapsToItsPieces() {
        assertEquals(7, middle.numPieces)
        assertEquals(0, middle.firstPiece)
        assertEquals(5, middle.lastPiece)
        // Byte 23 of the middle file is byte 1023 of the torrent (piece 0), byte 24 starts piece 1.
        assertEquals(0, middle.pieceOf(23))
        assertEquals(1, middle.pieceOf(24))
        assertEquals(5, middle.pieceOf(4999))
        // Piece 0 holds only its first 24 bytes; piece 5 ends with the file, not with the piece.
        assertEquals(24L, middle.pieceEnd(0))
        assertEquals(24L + 1024, middle.pieceEnd(1))
        assertEquals(5000L, middle.pieceEnd(5))
        assertEquals(0, first.firstPiece)
        assertEquals(0, first.lastPiece)
    }

    @Test
    fun theTorrentsLastPieceIsShorter() {
        assertEquals(1024L, last.pieceSize(0))
        assertEquals(1024L, last.pieceSize(5))
        assertEquals(156L, last.pieceSize(6))
        assertEquals(5, last.firstPiece)
        assertEquals(6, last.lastPiece)
        // The last file ends exactly where the torrent does: its last piece is the short one.
        assertEquals(300L, last.pieceEnd(6))
        assertEquals(6 * 1024L - 6000, last.pieceEnd(5))
        // A torrent that is whole pieces has no short one.
        assertEquals(1024L, PieceMap(0, 4096, pl, 4096).pieceSize(3))
        assertEquals(4, PieceMap(0, 4096, pl, 4096).numPieces)
    }

    @Test
    fun whatCanBeReadStopsAtTheFirstMissingPiece() {
        val have = setOf(0, 1, 2)
        // Pieces 0 to 2 hold the middle file's bytes up to 3 × 1024 − 1000.
        assertEquals(2072L, middle.readable(0, have::contains))
        assertEquals(1972L, middle.readable(100, have::contains))
        assertEquals(50L, middle.readable(100, have::contains, max = 50))
        // In piece 3, which isn't in: nothing yet.
        assertEquals(0L, middle.readable(2072, have::contains))
        // With every piece: to the end of the file, never past it.
        val all: (Int) -> Boolean = { true }
        assertEquals(5000L - 4000, middle.readable(4000, all))
        assertEquals(0L, middle.readable(5000, all))
        // A gap after the first pieces stops there even when later ones are in.
        assertEquals(2072L, middle.readable(0, setOf(0, 1, 2, 4, 5)::contains))
    }

    @Test
    fun theStartAndEndOfAFilmComeFirst() {
        val mb = 1L shl 20
        val film = PieceMap(0, 100 * mb, mb.toInt(), 100 * mb)
        assertEquals(listOf(0, 99), film.edgePieces(PieceMap.HEAD_BYTES, PieceMap.tailBytes(100 * mb)))
        assertEquals(listOf(0, 96, 97, 98, 99), film.edgePieces(mb, 4 * mb))
        assertEquals(listOf(0, 1, 99), film.edgePieces(2 * mb, 1))
        // 1% of the file, from 1 MB to 8 MB.
        assertEquals(mb, PieceMap.tailBytes(10 * mb))
        assertEquals(8 * mb, PieceMap.tailBytes(10_000 * mb))
        assertEquals(5 * mb, PieceMap.tailBytes(500 * mb))
        // A file inside one piece: that piece, once.
        assertEquals(listOf(0), first.edgePieces(PieceMap.HEAD_BYTES, PieceMap.tailBytes(1000)))
        // A file in the middle of the torrent: the pieces it shares with its neighbours count.
        assertEquals(listOf(0, 4, 5), middle.edgePieces(24, 1500))
        assertEquals(listOf(0), PieceMap(0, 0, pl, 0).edgePieces(1, 1))
    }

    @Test
    fun theNextMissingPiecesFromWhereItIsRead() {
        val have = setOf(1, 2, 4)
        assertEquals(listOf(0, 3, 5), middle.missingFrom(0, 3, have::contains))
        assertEquals(listOf(3, 5), middle.missingFrom(1500, 10, have::contains))
        // Never past the file's last piece.
        assertEquals(emptyList<Int>(), middle.missingFrom(4999, 5) { true })
        assertEquals(emptyList<Int>(), middle.missingFrom(0, 0) { false })
    }

    @Test
    fun theEdgesComeBeforeTheWindowAndEachPieceOnce() {
        val mb = 1L shl 20
        val film = PieceMap(0, 100 * mb, mb.toInt(), 100 * mb)
        val have = setOf(0, 50)
        // Piece 0 is in: only 99 of the edges is still wanted, then the window after where it reads.
        assertEquals(listOf(99, 51, 52), film.firstWanted(50 * mb + 10, listOf(0, 99), 2, have::contains))
        // A pinned piece inside the window isn't asked for twice.
        assertEquals(listOf(52, 51), film.firstWanted(51 * mb, listOf(52), 2, have::contains))
    }

    @Test
    fun theWindowFitsThePieceSize() {
        assertEquals(64, PieceMap.windowFor(256 * 1024))
        assertEquals(4, PieceMap.windowFor(4 shl 20))
        assertEquals(2, PieceMap.windowFor(16 shl 20))
        assertEquals(64, PieceMap.windowFor(16 * 1024))
    }

    @Test
    fun anotherFilesPieces() {
        assertEquals(0..5, PieceMap.piecesOf(1000, 5000, pl))
        assertEquals(5..6, PieceMap.piecesOf(6000, 300, pl))
        assertTrue(PieceMap.piecesOf(500, 0, pl).isEmpty())
    }
}
