package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0: news pictures are decoded just big enough to cover where they are shown (PreviewLoader.Fit). */
class PreviewFitTest {
    @Test
    fun aWidePictureForACardIsScaledToItsHeight() {
        // 1200 × 675 for a 640 × 360 pixel card: no halving fits, so scaled from 675 to 360 high.
        val f = PreviewLoader.Fit.of(1200, 675, 640, 360)
        assertEquals(1, f.sample)
        assertEquals(675, f.from)
        assertEquals(360, f.to)
        assertTrue(f.scaled)
    }

    @Test
    fun aPictureForASquareIsHalvedThenScaled() {
        // Halved twice to 300 × 168, then 168 → 152 high, which covers the 152 square.
        val f = PreviewLoader.Fit.of(1200, 675, 152, 152)
        assertEquals(4, f.sample)
        assertEquals(168, f.from)
        assertEquals(152, f.to)
    }

    @Test
    fun aTallPictureIsScaledToItsWidth() {
        val f = PreviewLoader.Fit.of(1600, 3200, 640, 360)
        assertEquals(2, f.sample)
        assertEquals(800, f.from)
        assertEquals(640, f.to)
    }

    @Test
    fun smallPicturesAreNeverMadeBigger() {
        assertFalse(PreviewLoader.Fit.of(100, 80, 152, 152).scaled)
        assertFalse(PreviewLoader.Fit.of(600, 1200, 640, 360).scaled)
    }

    @Test
    fun aBoxOfNoSizeStillEnds() {
        // It used to halve until the numbers ran out.
        assertTrue(PreviewLoader.Fit.of(100, 80, 0, 0).sample >= 1)
    }
}
