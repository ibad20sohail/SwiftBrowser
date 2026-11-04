package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DragSelectTest {
    @Test
    fun slidingOnReachesEveryFileOnTheWay() {
        val s = DragSelect.step(5, 5, 9)
        assertEquals(listOf(6, 7, 8, 9), s.reached)
        assertEquals(emptyList<Int>(), s.left)
        // One more, a little further.
        val next = DragSelect.step(5, 9, 10)
        assertEquals(listOf(10), next.reached)
        assertEquals(emptyList<Int>(), next.left)
    }

    @Test
    fun goingBackLeavesFilesAsTheyWere() {
        val s = DragSelect.step(5, 9, 7)
        assertEquals(emptyList<Int>(), s.reached)
        assertEquals(listOf(8, 9), s.left)
        // All the way back to the held file: only that one stays as the hold made it.
        assertEquals(listOf(6, 7), DragSelect.step(5, 7, 5).left)
    }

    @Test
    fun crossingTheHeldFileTurnsTheOtherWay() {
        val s = DragSelect.step(5, 9, 2)
        assertEquals(listOf(2, 3, 4), s.reached)
        assertEquals(listOf(6, 7, 8, 9), s.left)
        assertTrue(5 !in s.reached && 5 !in s.left)
        // Upwards first, then down past it.
        val down = DragSelect.step(5, 2, 6)
        assertEquals(listOf(6), down.reached)
        assertEquals(listOf(2, 3, 4), down.left)
    }

    @Test
    fun theSameFileChangesNothing() {
        val s = DragSelect.step(3, 3, 3)
        assertTrue(s.reached.isEmpty() && s.left.isEmpty())
    }

    @Test
    fun theListScrollsOnlyNearItsEdges() {
        // A list on show from 0 to 1000 px, 100 px near its edges, 20 px a frame at most.
        assertEquals(0, DragSelect.edgeStep(500f, 0, 1000, 100, 20))
        assertEquals(0, DragSelect.edgeStep(100f, 0, 1000, 100, 20))
        assertTrue(DragSelect.edgeStep(50f, 0, 1000, 100, 20) < 0)
        assertTrue(DragSelect.edgeStep(950f, 0, 1000, 100, 20) > 0)
        // Faster nearer the edge, and at its fastest past it (the finger over the bars).
        assertTrue(DragSelect.edgeStep(10f, 0, 1000, 100, 20) < DragSelect.edgeStep(60f, 0, 1000, 100, 20))
        assertEquals(-20, DragSelect.edgeStep(-40f, 0, 1000, 100, 20))
        assertEquals(20, DragSelect.edgeStep(1200f, 0, 1000, 100, 20))
        // Just inside an edge it moves, if only by a pixel.
        assertEquals(1, DragSelect.edgeStep(901f, 0, 1000, 100, 20))
        assertEquals(-1, DragSelect.edgeStep(99f, 0, 1000, 100, 20))
        // The list's top need not be 0.
        assertEquals(0, DragSelect.edgeStep(300f, 200, 1000, 100, 20))
        assertTrue(DragSelect.edgeStep(250f, 200, 1000, 100, 20) < 0)
    }

    @Test
    fun aShortListKeepsAMiddleWhereNothingScrolls() {
        // 150 px on show: a third of it at each edge at most.
        assertEquals(0, DragSelect.edgeStep(75f, 0, 150, 100, 20))
        assertTrue(DragSelect.edgeStep(20f, 0, 150, 100, 20) < 0)
        assertEquals(0, DragSelect.edgeStep(1f, 0, 0, 100, 20))
        assertEquals(0, DragSelect.edgeStep(1f, 100, 50, 100, 20))
    }
}
