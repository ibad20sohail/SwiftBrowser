package com.swiftbrowser.torrent

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 3.0.11: what a file being watched is read from: which pieces are in, and where the player reads. */
interface PieceSource {
    /** Piece [piece] is downloaded and checked: its bytes can be read from the file. */
    fun have(piece: Int): Boolean

    /** The player waits at byte [pos] of the file: the pieces from there are wanted first (libtorrent's piece deadlines). */
    fun readingAt(pos: Long)
}

/**
 * 3.0.11: the player's reads of a torrent's file being watched (Watch now) wait here for the
 * pieces they need. A read that finds its piece missing asks [source] for the pieces from there
 * first (also after a seek, which reads from somewhere else), then waits, at most a given time, for
 * [signal] (a piece came in); it looks again now and then in case a signal was missed. The player
 * shows that it is loading meanwhile. [clock]: uptime in ms (the tests' own).
 */
class PieceWaiter(private val map: PieceMap, private val source: PieceSource, private val clock: () -> Long) {
    private val lock = ReentrantLock()
    private val cameIn = lock.newCondition()

    @Volatile
    private var closed = false

    /** Since when (by [clock]) a read has been waiting for a piece; 0 while none waits. */
    @Volatile
    var waitingSince = 0L
        private set

    /**
     * Waits until bytes at [pos] can be read, for at most [timeoutMs]: how many can be read from
     * there (at most [max]), 0 when the time ran out first, -1 at the file's end. Throws
     * InterruptedIOException when the player stops the read (a seek, closing), and IOException once
     * [close]d.
     */
    fun await(pos: Long, max: Long, timeoutMs: Long): Long {
        if (pos >= map.fileSize) return -1
        val ready = map.readable(pos, source::have, max)
        if (ready > 0) {
            waitingSince = 0
            return ready
        }
        if (closed) throw IOException("The torrent was removed")
        if (waitingSince == 0L) waitingSince = clock()
        source.readingAt(pos)
        val until = clock() + timeoutMs
        lock.withLock {
            while (true) {
                if (closed) throw IOException("The torrent was removed")
                val n = map.readable(pos, source::have, max)
                if (n > 0) {
                    waitingSince = 0
                    return n
                }
                val left = until - clock()
                if (left <= 0) return 0
                try {
                    cameIn.await(minOf(left, LOOK_AGAIN_MS), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // A seek waits afresh. The flag is kept for the player's loader, which clears it
                    // when its read is over.
                    waitingSince = 0
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Stopped while waiting for the torrent")
                }
            }
        }
    }

    /** A piece came in (or the whole file did): waiting reads look again. */
    fun signal() {
        lock.withLock { cameIn.signalAll() }
    }

    /** Nothing more will come (the torrent was removed): reads that need a missing piece fail. */
    fun close() {
        closed = true
        signal()
    }

    private companion object {
        /** Reads look again this often even without a signal. */
        const val LOOK_AGAIN_MS = 250L
    }
}
