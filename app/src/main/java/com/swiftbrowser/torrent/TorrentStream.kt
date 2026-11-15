package com.swiftbrowser.torrent

import java.io.File
import java.util.BitSet

/**
 * 3.0.11: one torrent file watched while it downloads (Watch now). The player's reads wait on it
 * ([waiter], on the player's loader thread: media.TorrentDataSource) and [Torrents] feeds it from
 * its own thread: the pieces that come in, and what libtorrent is asked for first ([wanted]).
 */
class TorrentStream internal constructor(
    /** The torrent's row in Downloads. */
    val id: Long,
    val hash: String,
    /** The file's place in the torrent's file list, and where it is saved. */
    val index: Int,
    val file: File,
    val map: PieceMap,
    /** Asked for before the rest until they are in: the file's start and end, and the torrent's subtitles. */
    internal val pinned: List<Int>,
    /** The torrent's file count (padding files too), for its file priorities when watching ends. */
    internal val numFiles: Int,
    private val clock: () -> Long,
    /** The player waits somewhere new (a seek): Torrents asks for the pieces from there. */
    private val moved: (TorrentStream) -> Unit,
) : PieceSource {
    private val bits = BitSet()

    /** The whole file is in (the torrent finished while it played): nothing waits any more. */
    @Volatile
    var complete = false
        private set

    /** The torrent was removed: nothing more comes, and a read that needs a missing piece fails. */
    @Volatile
    var gone = false
        private set

    /** Where the player reads the file (its loader, ahead of the picture by what is buffered). */
    @Volatile
    var position = 0L

    /** When (uptime) a piece of the torrent last came in, or watching began. */
    @Volatile
    var lastPieceAt = clock()
        private set

    val waiter = PieceWaiter(map, this, clock)

    /** How many missing pieces are asked for ahead of the reading. */
    private val window = PieceMap.windowFor(map.pieceLength)

    /** The piece the player last waited at: one wait asks once. */
    @Volatile
    private var waitedAt = -1

    // Torrents' thread only: the libtorrent handle watching was set up on, what was asked for, and
    // when the pieces were last read from libtorrent.
    internal var applied: Any? = null
    internal var asked: Set<Int> = emptySet()
    internal var filledAt = 0L

    override fun have(piece: Int): Boolean = complete || synchronized(bits) { bits.get(piece) }

    override fun readingAt(pos: Long) {
        position = pos
        val piece = map.pieceOf(pos)
        if (piece == waitedAt) return
        waitedAt = piece
        moved(this)
    }

    /** Piece [piece] of the torrent came in (one of another file only says the torrent is moving). */
    fun gotPiece(piece: Int) {
        lastPieceAt = clock()
        if (piece < map.firstPiece || piece > map.lastPiece) return
        synchronized(bits) { bits.set(piece) }
        waiter.signal()
    }

    /** What the torrent has, from libtorrent or its saved progress: [has] for each of the file's pieces. */
    fun fill(has: (Int) -> Boolean) {
        var any = false
        synchronized(bits) {
            for (p in map.firstPiece..map.lastPiece) {
                if (!bits.get(p) && has(p)) {
                    bits.set(p)
                    any = true
                }
            }
        }
        if (any) waiter.signal()
    }

    fun markComplete() {
        complete = true
        waiter.signal()
    }

    fun markGone() {
        gone = true
        waiter.close()
    }

    /** The pieces to ask libtorrent for now, most urgent first (PieceMap.firstWanted). */
    fun wanted(): List<Int> = map.firstWanted(position, pinned, window, ::have)

    /** How long (ms) the player has been waiting for a piece; 0 while it isn't. */
    fun waitingMs(): Long = waiter.waitingSince.let { if (it == 0L) 0L else clock() - it }

    /** How long (ms) since a piece of the torrent came in. */
    fun quietMs(): Long = clock() - lastPieceAt
}
