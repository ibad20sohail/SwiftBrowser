package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 3.0.4: the loading indicator's shapes and how they move (LoadingShapes). */
class LoadingShapesTest {
    private fun outline(ms: Long) = FloatArray(LoadingShapes.POINTS * 2).also { LoadingShapes.outline(ms, it) }

    private fun distances(o: FloatArray) = FloatArray(LoadingShapes.POINTS) { hypot(o[2 * it], o[2 * it + 1]) }

    @Test
    fun everyShapeFitsItsBoxAndLooksAboutAsLarge() {
        assertEquals(7, LoadingShapes.shapes.size)
        for (s in LoadingShapes.shapes) {
            assertEquals(LoadingShapes.POINTS, s.size)
            assertTrue(s.max() <= 1.0001f)
            assertTrue(s.min() > 0.5f)
            // Its area about a circle's of 0.9 (none much smaller, none larger).
            val area = s.sumOf { it.toDouble() * it } / s.size
            assertTrue(area in 0.55..0.82)
        }
    }

    @Test
    fun itStartsAsACircleWithItsFirstPointAtTheTop() {
        val o = outline(0)
        for (d in distances(o)) assertEquals(0.9f, d, 1e-4f)
        assertEquals(0f, o[0], 1e-4f)
        assertEquals(-0.9f, o[1], 1e-4f)
    }

    @Test
    fun aChangeOfShapeEndsAsTheNextShape() {
        for (k in 1..7) {
            val d = distances(outline(k * LoadingShapes.MORPH_MS))
            val shape = LoadingShapes.shapes[k % 7]
            for (i in 0 until LoadingShapes.POINTS) assertEquals(shape[i], d[i], 1e-4f)
        }
    }

    @Test
    fun itNeverJumps() {
        // From one shape to the next, round the whole list and past the twist's full turn.
        for (k in 1..16) {
            val t = k * LoadingShapes.MORPH_MS
            val before = outline(t - 1)
            val after = outline(t)
            for (i in before.indices) assertEquals("at $t", before[i], after[i], 0.01f)
        }
        // And frame to frame (60 a second) as it goes: a few degrees of turn at most, never a leap.
        var last = outline(0)
        for (t in 16L..20_000L step 16L) {
            val now = outline(t)
            for (i in now.indices) assertTrue("at $t", kotlin.math.abs(now[i] - last[i]) < 0.2f)
            last = now
        }
    }

    @Test
    fun itStaysInsideItsBox() {
        // The spring takes a shape only a touch past itself.
        for (t in 0L..(7 * LoadingShapes.MORPH_MS) step 5L) assertTrue(distances(outline(t)).max() <= 1.06f)
    }

    @Test
    fun theSpringSettlesWithASmallBounce() {
        assertEquals(0f, LoadingShapes.spring(0f), 0f)
        assertEquals(1f, LoadingShapes.spring(1f), 0.005f)
        var top = 0f
        for (i in 0..100) top = maxOf(top, LoadingShapes.spring(i / 100f))
        assertTrue(top > 1f)
        assertTrue(top < 1.06f)
    }

    @Test
    fun stillItIsTheCookie() {
        val d = distances(outline(LoadingShapes.STILL_MS))
        for (i in d.indices) assertEquals(LoadingShapes.shapes[1][i], d[i], 1e-4f)
    }
}
