package com.swiftbrowser.media

import com.swiftbrowser.media.HardwareVideo.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVideoTest {
    @Test
    fun theChipIsAskedFirst() {
        assertEquals(Fit.CHIP, HardwareVideo.fit(listOf(false, true)))
        assertEquals(Fit.SOFTWARE_ONLY, HardwareVideo.fit(emptyList()))
        assertEquals(Fit.TOO_MUCH, HardwareVideo.fit(listOf(false, false)))
        // A decoder that didn't say isn't taken for a no.
        assertEquals(Fit.UNKNOWN, HardwareVideo.fit(listOf(false, null)))
    }

    @Test
    fun onlyAStutterAheadIsSaid() {
        assertNull(HardwareVideo.message(Fit.CHIP, 1280, 720))
        assertNull(HardwareVideo.message(Fit.UNKNOWN, 1280, 720))
        assertTrue(HardwareVideo.message(Fit.SOFTWARE_ONLY, 1280, 720)!!.contains("in software"))
        assertTrue(HardwareVideo.message(Fit.TOO_MUCH, 3840, 2160)!!.contains("3840 × 2160"))
    }
}
