package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinRulesTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun waitsGrowAfterFiveWrongPins() {
        for (n in 0..4) assertEquals("no wait after $n", 0L, PinWait.after(n))
        assertEquals(30_000L, PinWait.after(5))
        assertEquals(5 * minute, PinWait.after(6))
        assertEquals(30 * minute, PinWait.after(7))
        assertEquals(2 * hour, PinWait.after(8))
        assertEquals(8 * hour, PinWait.after(9))
        assertEquals(24 * hour, PinWait.after(10))
        // Waiting never starts the count again: it stays at a day.
        assertEquals(24 * hour, PinWait.after(50))
    }

    @Test
    fun aWaitCountsOnlyTimeThePhoneWasOn() {
        val start = Moment(boot = 3, elapsed = 1_000_000L, wall = 1_700_000_000_000L)
        val w = Wait(30 * minute, start)
        // Ten minutes later on the same start of the phone.
        assertEquals(20 * minute, w.leftAt(Moment(3, start.elapsed + 10 * minute, start.wall + 10 * minute)))
        // The clock put a day forward doesn't shorten it.
        assertEquals(29 * minute, w.leftAt(Moment(3, start.elapsed + minute, start.wall + 24 * hour)))
        // The clock put back doesn't lengthen it beyond the wait.
        assertEquals(29 * minute, w.leftAt(Moment(3, start.elapsed + minute, start.wall - 24 * hour)))
        // Over.
        assertEquals(0L, w.leftAt(Moment(3, start.elapsed + 31 * minute, start.wall)))
    }

    @Test
    fun afterARestartOnlyTheTimeSinceCounts() {
        val start = Moment(boot = 3, elapsed = 50 * minute, wall = 1_700_000_000_000L)
        val w = Wait(30 * minute, start)
        // Restarted (a new boot number) 5 minutes ago, the clock saying the wait is long over.
        assertEquals(25 * minute, w.leftAt(Moment(4, 5 * minute, start.wall + 10 * hour)))
        // No boot number known, but the time since the start went back: a restart too.
        val unknown = Wait(30 * minute, Moment(-1, 50 * minute, 0L))
        assertEquals(25 * minute, unknown.leftAt(Moment(-1, 5 * minute, 0L)))
    }

    @Test
    fun rollingAWaitKeepsWhatIsLeft() {
        val start = Moment(1, 0L, 0L)
        val rolled = Wait(30 * minute, start).at(Moment(1, 10 * minute, 0L))
        assertEquals(20 * minute, rolled.left)
        // A restart then loses only the time since it was rolled.
        assertEquals(15 * minute, rolled.leftAt(Moment(2, 5 * minute, 0L)))
    }

    @Test
    fun waitsAreKeptAsText() {
        val w = Wait(12345L, Moment(7, 999L, 1_700_000_000_000L))
        val back = Wait.decode(w.encode())
        assertNotNull(back)
        assertEquals(12345L, back!!.left)
        assertEquals(7, back.at.boot)
        assertEquals(999L, back.at.elapsed)
        assertEquals(1_700_000_000_000L, back.at.wall)
        assertNull(Wait.decode(null))
        assertNull(Wait.decode("rubbish"))
        assertNull(Wait.decode("-5:1:2:3"))
    }

    @Test
    fun pinsAreKeptTheNewWayAndTheOldOneStillOpens() {
        val salt = ByteArray(16) { it.toByte() }
        val stored = PinHash.make("4711", salt)
        assertTrue(stored.startsWith("2:"))
        assertTrue(PinHash.matches("4711", stored))
        assertFalse(PinHash.matches("4712", stored))
        assertFalse(PinHash.outdated(stored))

        // 2.9's way: PBKDF2 with SHA-1, 4,000 rounds, 256 bits.
        val spec = PBEKeySpec("1234".toCharArray(), salt, 4000, 256)
        val old = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        val v1 = "1:" + hex(salt) + ":" + hex(old)
        assertTrue(PinHash.matches("1234", v1))
        assertFalse(PinHash.matches("0000", v1))
        assertTrue(PinHash.outdated(v1))

        assertFalse(PinHash.matches("1234", null))
        assertFalse(PinHash.matches("1234", "3:00:00"))
        assertFalse(PinHash.matches("1234", "2:zz:zz"))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
}
