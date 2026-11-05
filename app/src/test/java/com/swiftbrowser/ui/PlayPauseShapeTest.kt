package com.swiftbrowser.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 3.0.4: the play button's morph between Material Symbols' play and pause (PlayPauseShape). */
class PlayPauseShapeTest {
    private fun corners(t: Float) = FloatArray(PlayPauseShape.SIZE).also { PlayPauseShape.at(t, it) }

    private fun rounded(t: Float) = FloatArray(PlayPauseShape.SIZE * 2).also { PlayPauseShape.round(corners(t), it) }

    @Test
    fun itGoesFromPlayToPause() {
        assertArrayEquals(PlayPauseShape.PLAY, corners(0f), 0f)
        assertArrayEquals(PlayPauseShape.PAUSE, corners(1f), 1e-4f)
    }

    @Test
    fun playIsOneTriangleCutInTwo() {
        val p = PlayPauseShape.PLAY
        // The left part's right corners are the right part's left ones, sharp, so the cut doesn't show.
        assertEquals(p[3], p[12], 0f)
        assertEquals(p[4], p[13], 0f)
        assertEquals(p[6], p[21], 0f)
        assertEquals(p[7], p[22], 0f)
        for (i in intArrayOf(5, 8, 14, 23)) assertEquals(0f, p[i], 0f)
        // Upright on the left, the tip on the middle line: as Material Symbols' play_arrow.
        assertEquals(p[0], p[9], 0f)
        assertEquals(480f, (p[16] + p[19]) / 2, 0.01f)
        assertEquals(480f, (p[1] + p[10]) / 2, 0.01f)
    }

    @Test
    fun pausesBarsHaveRoundEnds() {
        val r = rounded(1f)
        for (part in 0 until 2) {
            val o = part * 24
            // The top corners' roundings meet in the bar's middle, as the bottom ones do: a half circle.
            assertEquals(r[o + 4], r[o + 6], 1e-3f)
            assertEquals(r[o + 5], r[o + 7], 1e-3f)
            assertEquals(r[o + 16], r[o + 18], 1e-3f)
            assertEquals(r[o + 17], r[o + 19], 1e-3f)
        }
        // The left bar's top: from x 240 to 400, its middle at 320.
        assertEquals(320f, r[4], 1e-3f)
        assertEquals(200f, r[5], 1e-3f)
    }

    @Test
    fun playsCornersAreRoundedAsMaterialDrawsThem() {
        val r = rounded(0f)
        // The top left corner: its rounding starts 72 below it on the upright side, as play_arrow's (y 273).
        assertEquals(320f, r[0], 1e-3f)
        assertEquals(272.3f, r[1], 0.01f)
        // The cut's corners stay sharp.
        assertEquals(r[6], r[8], 0f)
        assertEquals(r[7], r[9], 0f)
        assertEquals(r[8], r[10], 0f)
    }

    @Test
    fun aRoundingNeverReachesPastHalfASide() {
        for (step in 0..20) {
            val t = step / 20f
            val c = corners(t)
            val r = rounded(t)
            for (part in 0 until 2) {
                for (k in 0 until 4) {
                    val i = (part * 4 + k) * 3
                    val prev = (part * 4 + (k + 3) % 4) * 3
                    val next = (part * 4 + (k + 1) % 4) * 3
                    val o = (part * 4 + k) * 6
                    val sideIn = hypot(c[prev] - c[i], c[prev + 1] - c[i + 1])
                    val sideOut = hypot(c[next] - c[i], c[next + 1] - c[i + 1])
                    assertTrue(hypot(r[o] - c[i], r[o + 1] - c[i + 1]) <= sideIn / 2 + 1e-3f)
                    assertTrue(hypot(r[o + 4] - c[i], r[o + 5] - c[i + 1]) <= sideOut / 2 + 1e-3f)
                }
            }
        }
    }

    @Test
    fun itStaysInsideTheIcon() {
        for (step in 0..20) {
            val c = corners(step / 20f)
            for (i in 0 until PlayPauseShape.SIZE step 3) {
                assertTrue(c[i] in 0f..PlayPauseShape.GRID)
                assertTrue(c[i + 1] in 0f..PlayPauseShape.GRID)
            }
        }
    }
}
