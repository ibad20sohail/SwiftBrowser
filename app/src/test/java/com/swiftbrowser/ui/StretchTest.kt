package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0.11: how far a list pulled past its end stretches, and how it bounces when a fling hits it (Stretch). */
class StretchTest {
    @Test
    fun aPullStretchesLessAndLessTheFurtherItGoes() {
        assertEquals(0f, Stretch.amount(0f), 0f)
        assertEquals(0f, Stretch.amount(-0.2f), 0f)
        // Half the most when pulled the "half" share of the list's length.
        assertEquals(Stretch.MOST / 2, Stretch.amount(Stretch.HALF_AT), 1e-6f)
        val small = Stretch.amount(0.1f)
        val twice = Stretch.amount(0.2f)
        assertTrue(twice > small && twice < 2 * small)
    }

    @Test
    fun itNeverStretchesPastTheMost() {
        assertTrue(Stretch.amount(10f) < Stretch.MOST)
        assertTrue(Stretch.amount(1_000f) <= Stretch.MOST)
        // A whole screen's pull is a gentle stretch, not a big one.
        assertTrue(Stretch.amount(1f) in 0.05f..Stretch.MOST)
    }

    @Test
    fun aStretchCaughtOnItsWayBackGoesOnFromWhereItIs() {
        for (pull in floatArrayOf(0.01f, 0.1f, 0.4f, 1.5f)) {
            assertEquals(pull, Stretch.pullFor(Stretch.amount(pull)), 1e-4f)
        }
        assertEquals(0f, Stretch.pullFor(0f), 0f)
        // Never a pull without end, even right at the most.
        assertTrue(Stretch.pullFor(Stretch.MOST).isFinite())
    }

    @Test
    fun aFlingBouncesAboutAsFarAsAShortPull() {
        val length = 1184
        val v = 8000f
        val kick = Stretch.kick(v, length)
        // A spring that settles without bouncing, started at speed k, goes at most k / (e × √stiffness) out.
        val peak = kick / (kotlin.math.E.toFloat() * kotlin.math.sqrt(Motion.SPRING_PANEL))
        assertEquals(Stretch.amount(v * Stretch.FLING_S / length), peak, 1e-5f)
        assertTrue(peak < Stretch.MOST)
        // Faster flings bounce further; either way round the same; a list of no size not at all.
        assertTrue(Stretch.kick(12_000f, length) > kick)
        assertEquals(kick, Stretch.kick(-v, length), 1e-6f)
        assertEquals(0f, Stretch.kick(v, 0), 0f)
    }

    @Test
    fun theStretchShowsOnlyWhereTheListIs() {
        // Stretched a tenth, 1100 px of it fill its 1210 px: what would go past its far edge is cut off.
        assertEquals(1100f, Stretch.kept(1210, 0.1f), 1e-3f)
        assertEquals(1210f, Stretch.kept(1210, 0f), 0f)
        assertEquals(1210f, Stretch.kept(1210, -1f), 0f)
    }
}
