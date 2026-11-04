package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRulesTest {
    @Test
    fun aPinchSnapsBetweenFillAndFit() {
        val p = PinchSteps()
        p.begin()
        assertEquals(0, p.add(1.1f))
        // 1.1 × 1.06 is past 1.15: fill.
        assertEquals(1, p.add(1.06f))
        // Measured afresh from the snap: going on out doesn't snap again at once.
        assertEquals(0, p.add(1.05f))
        // Back in, past 0.87 of where it snapped: fit.
        assertEquals(-1, p.add(0.8f))
        assertEquals(0, p.add(Float.NaN))
        assertEquals(1, PlayerRules.pinch(1.2f))
        assertEquals(-1, PlayerRules.pinch(0.85f))
        assertEquals(0, PlayerRules.pinch(1f))
    }

    @Test
    fun furtherTapsOnTheSameSideSkipAgain() {
        val s = TapSeries()
        assertFalse(s.claims(1, 0))
        s.start(1, 1_000)
        assertTrue(s.claims(1, 1_500))
        assertTrue(s.claims(1, 1_650))
        // The other side, the middle, or too late: an ordinary tap.
        assertFalse(s.claims(-1, 1_200))
        assertFalse(s.claims(0, 1_200))
        assertFalse(s.claims(1, 1_700))
        s.start(1, 1_600)
        assertTrue(s.claims(1, 2_200))
        s.clear()
        assertFalse(s.claims(1, 1_700))
    }

    @Test
    fun skipsAddUpAsOnWebsitesVideos() {
        assertEquals(20_000L, PlayerRules.runTotal(10_000, 1, 1_000, 1, 2_100, 10_000))
        // Over 1.2 s since the last, or the other side: a new run.
        assertEquals(10_000L, PlayerRules.runTotal(10_000, 1, 1_000, 1, 2_200, 10_000))
        assertEquals(10_000L, PlayerRules.runTotal(30_000, 1, 1_000, -1, 1_300, 10_000))
        assertEquals("+30 s", PlayerRules.runLabel(1, 30_000))
        assertEquals("−20 s", PlayerRules.runLabel(-1, 20_000))
    }

    @Test
    fun theScreenIsSplitInThirds() {
        assertEquals(-1, PlayerRules.third(100f, 900))
        assertEquals(0, PlayerRules.third(450f, 900))
        assertEquals(1, PlayerRules.third(800f, 900))
        assertEquals(0, PlayerRules.third(10f, 0))
    }

    @Test
    fun onlyAVideoOfAnotherShapeIsWorthPinching() {
        val phone = 16f / 9f
        assertFalse(PlayerRules.shapeDiffers(16f / 9f, phone))
        // 1.85:1 films are 4% wider: as good as the same.
        assertFalse(PlayerRules.shapeDiffers(1.85f, phone))
        assertTrue(PlayerRules.shapeDiffers(2.39f, phone))
        assertTrue(PlayerRules.shapeDiffers(4f / 3f, phone))
        assertFalse(PlayerRules.shapeDiffers(0f, phone))
    }

    @Test
    fun subtitlesStayOnScreenAndAboveTheControls() {
        assertEquals(0.6f, PlayerRules.lift(0.9f))
        assertEquals(-0.02f, PlayerRules.lift(-0.5f))
        assertEquals(0f, PlayerRules.lift(Float.NaN))
        assertEquals(120f, PlayerRules.subtitleUp(40f, 120f, 0f))
        assertEquals(300f, PlayerRules.subtitleUp(300f, 120f, 0f))
        assertEquals(500f, PlayerRules.subtitleUp(40f, 0f, 500f))
    }

    @Test
    fun thePictureMovesUpAboveAnUprightPanel() {
        // 16:9 in the middle of a 1280 high screen, a panel 640 high: up into the middle of the half above.
        val (move, scale) = PlayerRules.fitAbove(437.5f, 842.5f, 640f)
        assertEquals(-320f, move, 0.01f)
        assertEquals(1f, scale, 0.001f)
        // A tall picture filling the screen: smaller too, to be whole.
        val (move2, scale2) = PlayerRules.fitAbove(0f, 1280f, 640f)
        assertEquals(-320f, move2, 0.01f)
        assertEquals(0.5f, scale2, 0.001f)
        // Already above it: stays.
        assertEquals(0f to 1f, PlayerRules.fitAbove(100f, 500f, 640f))
    }
}
