package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStepsTest {
    @Test
    fun aFrameLastsASecondOverTheFrameRate() {
        assertEquals(40_000L, PlayerRules.frameUs(25f))
        assertEquals(41_708L, PlayerRules.frameUs(23.976f))
        assertEquals(33_367L, PlayerRules.frameUs(29.97f))
        assertEquals(16_667L, PlayerRules.frameUs(60f))
    }

    @Test
    fun withoutAFrameRateItIsThirtyFramesASecond() {
        // Format.NO_VALUE, nothing, or nonsense.
        assertEquals(33_333L, PlayerRules.frameUs(-1f))
        assertEquals(33_333L, PlayerRules.frameUs(0f))
        assertEquals(33_333L, PlayerRules.frameUs(Float.NaN))
        assertEquals(33_333L, PlayerRules.frameUs(Float.POSITIVE_INFINITY))
        assertEquals(33_333L, PlayerRules.frameUs(5_000f))
    }

    @Test
    fun aStepLandsBetweenFrames() {
        // On: half a frame after the one shown; back: a frame and a half before it.
        assertEquals(1_020_000L, PlayerRules.frameStepUs(1_000_000, 40_000, 1, 10_000_000))
        assertEquals(940_000L, PlayerRules.frameStepUs(1_000_000, 40_000, -1, 10_000_000))
        // The first frame has none before it; the last none after it.
        assertNull(PlayerRules.frameStepUs(20_000, 40_000, -1, 10_000_000))
        assertNull(PlayerRules.frameStepUs(9_960_000, 40_000, 1, 10_000_000))
        // The second frame goes back to the start, the last but one on to the last.
        assertEquals(0L, PlayerRules.frameStepUs(40_000, 40_000, -1, 10_000_000))
        assertEquals(9_940_000L, PlayerRules.frameStepUs(9_920_000, 40_000, 1, 10_000_000))
        // Length not known: always on.
        assertEquals(1_020_000L, PlayerRules.frameStepUs(1_000_000, 40_000, 1, 0))
    }

    /** What a seek to [targetUs] shows: the first frame at or after it (ExoPlayer's exact seek). */
    private fun shown(frames: List<Long>, targetUs: Long): Long = frames.first { it >= targetUs }

    @Test
    fun everyStepIsOneFrameEvenWhenTheRateSaidIsOff() {
        // A 23.976 fps film whose frame rate isn't known (taken as 30), and one with its own.
        for (stepUs in longArrayOf(PlayerRules.frameUs(-1f), PlayerRules.frameUs(23.976f))) {
            val frames = (0 until 400).map { (it * 1_001_000_000L / 24_000L) }
            val length = frames.last() + 41_708
            var at = 150
            repeat(120) {
                val target = PlayerRules.frameStepUs(frames[at], stepUs, 1, length)!!
                // A seek goes to whole milliseconds.
                val next = shown(frames, target / 1000 * 1000)
                assertEquals("on from frame $at", frames[at + 1], next)
                at++
            }
            repeat(200) {
                val target = PlayerRules.frameStepUs(frames[at], stepUs, -1, length)!!
                val before = shown(frames, target / 1000 * 1000)
                assertEquals("back from frame $at", frames[at - 1], before)
                at--
            }
        }
    }

    @Test
    fun theExactTimeIsToTheMillisecond() {
        assertEquals("1:23.456", PlayerRules.exactTime(83_456))
        assertEquals("0:00.000", PlayerRules.exactTime(0))
        assertEquals("0:00.041", PlayerRules.exactTime(41))
        assertEquals("1:02:03.004", PlayerRules.exactTime(3_723_004))
        assertEquals("0:00.000", PlayerRules.exactTime(-5))
    }
}
