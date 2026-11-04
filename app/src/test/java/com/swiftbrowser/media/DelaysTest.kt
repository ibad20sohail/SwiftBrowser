package com.swiftbrowser.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelaysTest {
    @Test
    fun stepsAreATenthOfASecondUpToTenSecondsEitherWay() {
        assertEquals(100L, Delays.step(0, 1))
        assertEquals(-100L, Delays.step(0, -1))
        assertEquals(400L, Delays.step(300, 1))
        // 3.0.1 kept quarter seconds: they go on from where they were.
        assertEquals(350L, Delays.step(250, 1))
        assertEquals(10_000L, Delays.step(9_950, 1))
        assertEquals(10_000L, Delays.step(10_000, 1))
        assertEquals(-10_000L, Delays.step(-10_000, -1))
        assertEquals(10_000L, Delays.clamp(60_000))
        assertEquals(-10_000L, Delays.clamp(-60_000))
    }

    @Test
    fun labelsSayWhichWay() {
        assertEquals("In time", Delays.label(0))
        assertEquals("0.3 s later", Delays.label(300))
        assertEquals("0.3 s earlier", Delays.label(-300))
        assertEquals("0.25 s later", Delays.label(250))
        assertEquals("1 s later", Delays.label(1_000))
        assertEquals("10 s earlier", Delays.label(-10_000))
        assertEquals("2.5 s later", Delays.label(2_500))
    }

    @Test
    fun laterSubtitlesAreRenderedBehindThePicture() {
        // 0.5 s later: the renderer is told the video is half a second back.
        assertEquals(9_500_000L, Delays.subtitlePositionUs(10_000_000, 500_000, C.TIME_UNSET))
        // Earlier: ahead.
        assertEquals(10_500_000L, Delays.subtitlePositionUs(10_000_000, -500_000, C.TIME_UNSET))
        assertEquals(10_000_000L, Delays.subtitlePositionUs(10_000_000, 0, C.TIME_UNSET))
    }

    @Test
    fun laterSubtitlesAreBackInTimeAtTheEndOfAStream() {
        val end = 60_000_000L
        // Well before the end: behind.
        assertEquals(55_000_000L, Delays.subtitlePositionUs(58_000_000, 3_000_000, end))
        // The last second and a half: the true position, so the stream's end is read and reached on time.
        assertEquals(58_600_000L, Delays.subtitlePositionUs(58_600_000, 3_000_000, end))
        assertEquals(end, Delays.subtitlePositionUs(end, 3_000_000, end))
        // Earlier subtitles are ahead already: left as they are.
        assertEquals(61_000_000L, Delays.subtitlePositionUs(59_000_000, -2_000_000, end))
    }

    @Test
    fun soundLaterPutsTheClockAhead() {
        // The sound 0.2 s later: the clock the picture follows says 0.2 s further on than the sound is.
        assertEquals(5_200_000L, Delays.soundClockUs(5_000_000, 200_000))
        assertEquals(4_800_000L, Delays.soundClockUs(5_000_000, -200_000))
        // "Not known yet" stays so.
        assertEquals(Long.MIN_VALUE, Delays.soundClockUs(Long.MIN_VALUE, 200_000))
    }

    @Test
    fun subtitlesAreReadAgainOnlyWhenEarlierOnesComeBack() {
        assertTrue(Delays.rereadSubtitles(-2_000, 0))
        assertTrue(Delays.rereadSubtitles(-1_000, -500))
        assertFalse(Delays.rereadSubtitles(0, 500))
        assertFalse(Delays.rereadSubtitles(-500, -1_000))
        assertFalse(Delays.rereadSubtitles(500, 0))
    }
}
