package com.swiftbrowser.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartRepeatTest {
    @Test
    fun theEndComesASecondAfterTheStartAtLeast() {
        assertTrue(PartRepeat.endFits(83_000, 84_000))
        assertTrue(PartRepeat.endFits(0, 105_000))
        assertFalse(PartRepeat.endFits(83_000, 83_999))
        // Before the start (a seek back, then the chip): refused too.
        assertFalse(PartRepeat.endFits(83_000, 60_000))
        assertFalse(PartRepeat.endFits(-1, 5_000))
    }

    @Test
    fun reachingTheEndGoesBackToTheStart() {
        assertEquals(83_000L, PartRepeat.backTo(83_000, 105_000, 105_000))
        assertEquals(83_000L, PartRepeat.backTo(83_000, 105_000, 105_040))
        // Inside the part: on it goes.
        assertNull(PartRepeat.backTo(83_000, 105_000, 104_990))
        assertNull(PartRepeat.backTo(83_000, 105_000, 90_000))
    }

    @Test
    fun aSeekOutsideThePartKeepsIt() {
        // Before the start: it plays on into the part (and goes round once at its end).
        assertNull(PartRepeat.backTo(83_000, 105_000, 10_000))
        // Past the end: back to the start, as VLC does.
        assertEquals(83_000L, PartRepeat.backTo(83_000, 105_000, 600_000))
    }

    @Test
    fun withoutAnEndNothingRepeats() {
        assertNull(PartRepeat.backTo(83_000, C.TIME_UNSET, 600_000))
        assertNull(PartRepeat.backTo(C.TIME_UNSET, C.TIME_UNSET, 600_000))
    }

    @Test
    fun anEndAtTheVeryEndIsWatchedForJustBefore() {
        assertEquals(105_000L, PartRepeat.watchAt(105_000, 600_000))
        assertEquals(599_800L, PartRepeat.watchAt(600_000, 600_000))
        assertEquals(599_800L, PartRepeat.watchAt(599_900, 600_000))
        // Length not known (a stream): as set.
        assertEquals(105_000L, PartRepeat.watchAt(105_000, C.TIME_UNSET))
    }
}
