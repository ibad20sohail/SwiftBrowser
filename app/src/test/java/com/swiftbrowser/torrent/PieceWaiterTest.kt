package com.swiftbrowser.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PieceWaiterTest {
    /** Pieces that come in when a test says so; where the reads waited. */
    private class FakeSource : PieceSource {
        val pieces: MutableSet<Int> = Collections.synchronizedSet(HashSet())
        val asked: MutableList<Long> = Collections.synchronizedList(ArrayList())

        override fun have(piece: Int) = piece in pieces

        override fun readingAt(pos: Long) {
            asked += pos
        }
    }

    /** Milliseconds since the test began, never 0 (0 means "not waiting"). */
    private val origin = System.nanoTime()
    private val now: () -> Long = { (System.nanoTime() - origin) / 1_000_000 + 1 }

    // A file of four 1024-byte pieces, the torrent's only one.
    private val map = PieceMap(0, 4096, 1024, 4096)

    /** Runs [block] on a thread of its own after [delayMs]. */
    private fun later(delayMs: Long, block: () -> Unit) = Thread {
        Thread.sleep(delayMs)
        block()
    }.apply { start() }

    @Test
    fun aReadWaitsForItsPieceToComeIn() {
        val source = FakeSource()
        val waiter = PieceWaiter(map, source, now)
        later(100) {
            source.pieces += 0
            waiter.signal()
        }
        val start = now()
        assertEquals(1024L, waiter.await(0, 4096, 10_000))
        val took = now() - start
        assertTrue("came in after about 100 ms, took $took", took in 80L..5_000L)
        // It asked for the pieces from where it waited.
        assertEquals(listOf(0L), source.asked.toList())
        assertEquals(0L, waiter.waitingSince)
    }

    @Test
    fun aReadThatFindsItsPieceDoesNotWaitOrAsk() {
        val source = FakeSource().apply { pieces += setOf(0, 1) }
        val waiter = PieceWaiter(map, source, now)
        assertEquals(2048L, waiter.await(0, 4096, 10_000))
        assertEquals(100L, waiter.await(1000, 100, 10_000))
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun theWaitEndsWhenItsTimeRunsOut() {
        val source = FakeSource()
        val waiter = PieceWaiter(map, source, now)
        val start = now()
        assertEquals(0L, waiter.await(0, 4096, 200))
        assertTrue(now() - start >= 190)
        // Still waiting, as the player is: since the first try, not since this one.
        val since = waiter.waitingSince
        assertTrue(since > 0)
        assertEquals(0L, waiter.await(0, 4096, 50))
        assertEquals(since, waiter.waitingSince)
        // Once its piece is in, the next try has it at once, and nothing waits any more.
        source.pieces += 0
        assertEquals(1024L, waiter.await(0, 4096, 10_000))
        assertEquals(0L, waiter.waitingSince)
    }

    @Test
    fun aSeekWaitsWhereItNowReads() {
        val source = FakeSource().apply { pieces += 0 }
        val waiter = PieceWaiter(map, source, now)
        assertEquals(1024L, waiter.await(0, 4096, 10_000))
        // The player seeks to byte 2500 (piece 2): that is asked for, and waited for.
        later(100) {
            source.pieces += 2
            waiter.signal()
        }
        assertEquals(3072L - 2500, waiter.await(2500, 4096, 10_000))
        assertEquals(listOf(2500L), source.asked.toList())
        // Pieces 0 and 2 are in, 1 isn't: back at 1500 it waits again.
        assertEquals(0L, waiter.await(1500, 4096, 50))
        assertEquals(listOf(2500L, 1500L), source.asked.toList())
    }

    @Test
    fun aPieceThatCameWithoutASignalIsSeenAnyway() {
        val source = FakeSource()
        val waiter = PieceWaiter(map, source, now)
        later(50) { source.pieces += 0 }
        val start = now()
        assertEquals(1024L, waiter.await(0, 4096, 10_000))
        assertTrue(now() - start < 5_000)
    }

    @Test
    fun aReadThePlayerStopsEndsAtOnce() {
        val waiter = PieceWaiter(map, FakeSource(), now)
        val result = AtomicReference<Throwable?>()
        val waiting = CountDownLatch(1)
        val done = CountDownLatch(1)
        val t = Thread {
            try {
                waiting.countDown()
                waiter.await(0, 4096, 60_000)
            } catch (e: Throwable) {
                result.set(e)
                // The player's loader clears the flag itself afterwards: it is kept.
                if (!Thread.currentThread().isInterrupted) result.set(AssertionError("interrupt flag lost"))
            }
            done.countDown()
        }
        t.start()
        waiting.await()
        Thread.sleep(100)
        t.interrupt()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("got ${result.get()}", result.get() is InterruptedIOException)
        assertEquals(0L, waiter.waitingSince)
    }

    @Test
    fun aRemovedTorrentFailsTheWaitButNotWhatIsIn() {
        val source = FakeSource().apply { pieces += 0 }
        val waiter = PieceWaiter(map, source, now)
        val result = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread {
            try {
                waiter.await(2000, 4096, 60_000)
            } catch (e: Throwable) {
                result.set(e)
            }
            done.countDown()
        }.start()
        Thread.sleep(100)
        waiter.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val e = result.get()
        assertTrue("got $e", e is IOException && e !is InterruptedIOException)
        // What is in still reads; a read that would wait fails straight away.
        assertEquals(1024L, waiter.await(0, 4096, 10_000))
        try {
            waiter.await(1024, 4096, 10_000)
            fail("a removed torrent's missing piece was waited for")
        } catch (_: IOException) {
        }
    }

    @Test
    fun theEndOfTheFileIsSaid() {
        val waiter = PieceWaiter(map, FakeSource(), now)
        assertEquals(-1L, waiter.await(4096, 10, 10_000))
    }
}
