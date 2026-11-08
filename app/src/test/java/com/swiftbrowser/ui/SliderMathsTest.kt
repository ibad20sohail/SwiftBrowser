package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** 3.0.4: the slider's wave (SliderMaths). */
class SliderMathsTest {
    private val length = 64f
    private val amp = 6f

    @Test
    fun theWaveCrestsEveryWavelength() {
        assertEquals(amp, SliderMaths.waveY(0f, amp, length), 1e-4f)
        assertEquals(-amp, SliderMaths.waveY(length / 2, amp, length), 1e-4f)
        assertEquals(0f, SliderMaths.waveY(length / 4, amp, length), 1e-4f)
        assertEquals(amp, SliderMaths.waveY(-2 * length, amp, length), 1e-4f)
        assertEquals(SliderMaths.waveY(10f, amp, length), SliderMaths.waveY(10f + length, amp, length), 1e-4f)
        assertEquals(0f, SliderMaths.waveY(13f, 0f, length), 0f)
    }

    /**
     * The wave is drawn as curves from crest to trough with their middle points at [SliderMaths.CONTROL]:
     * they follow the cosine [SliderMaths.waveY] gives, so the round ends drawn there sit on the line.
     */
    @Test
    fun theCurvesFollowTheCosine() {
        val half = length / 2
        val k = SliderMaths.CONTROL * half
        var worst = 0f
        for (i in 0..200) {
            val t = i / 200f
            val u = 1 - t
            // From (0, amp) to (half, -amp), controls (k, amp) and (half - k, -amp).
            val x = 3 * u * u * t * k + 3 * u * t * t * (half - k) + t * t * t * half
            val y = u * u * u * amp + 3 * u * u * t * amp - 3 * u * t * t * amp - t * t * t * amp
            worst = maxOf(worst, abs(y - SliderMaths.waveY(x, amp, length)))
        }
        assertTrue("off by $worst", worst < amp * 0.002f)
    }
}
