package com.swiftbrowser.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerRulesTest {
    @Test
    fun bandsAreNamedByTheirMiddle() {
        assertEquals("60 Hz", EqualizerRules.frequency(60_000))
        assertEquals("230 Hz", EqualizerRules.frequency(230_000))
        assertEquals("910 Hz", EqualizerRules.frequency(910_000))
        assertEquals("3.6 kHz", EqualizerRules.frequency(3_600_000))
        assertEquals("14 kHz", EqualizerRules.frequency(14_000_000))
        assertEquals("12.5 kHz", EqualizerRules.frequency(12_500_000))
    }

    @Test
    fun levelsAreInDecibels() {
        assertEquals("+3 dB", EqualizerRules.level(300))
        assertEquals("−4.5 dB", EqualizerRules.level(-450))
        assertEquals("0 dB", EqualizerRules.level(0))
        assertEquals("+15 dB", EqualizerRules.level(1_500))
    }

    @Test
    fun keptLevelsFitThePhone() {
        assertArrayEquals(intArrayOf(300, 0, -200, 0, 0), EqualizerRules.levels("300,0,-200", 5, -1500, 1500))
        // Out of this phone's range, or unreadable: held in it, or 0.
        assertArrayEquals(intArrayOf(1500, -1500, 0), EqualizerRules.levels("4000,-9000,x", 3, -1500, 1500))
        assertArrayEquals(intArrayOf(0, 0), EqualizerRules.levels(null, 2, -1500, 1500))
        assertEquals("300,0,-200", EqualizerRules.format(intArrayOf(300, 0, -200)))
    }

    @Test
    fun bassIsAShare() {
        assertEquals("0%", EqualizerRules.bassLabel(0))
        assertEquals("50%", EqualizerRules.bassLabel(500))
        assertEquals("100%", EqualizerRules.bassLabel(1000))
    }
}
