package com.swiftbrowser.ui

import com.swiftbrowser.ui.PictureGrow.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0.11: where a picture opened from Files is as it grows out of its small picture (PictureGrow). */
class PictureGrowTest {
    private fun assertBox(expected: Box, actual: Box, delta: Float = 0.01f) {
        assertEquals("left", expected.left, actual.left, delta)
        assertEquals("top", expected.top, actual.top, delta)
        assertEquals("right", expected.right, actual.right, delta)
        assertEquals("bottom", expected.bottom, actual.bottom, delta)
    }

    @Test
    fun aPhotoFitsTheScreenInItsMiddleAsTheViewerShowsIt() {
        // 4000 × 3000 on a 720 × 1184 screen: as wide as the screen, in the middle.
        assertBox(Box(0f, 322f, 720f, 862f), PictureGrow.fit(4000, 3000, 720, 1184))
        // Upright, 3000 × 4000 on a turned 1184 × 720 screen: as high as the screen, in the middle.
        assertBox(Box(322f, 0f, 862f, 720f), PictureGrow.fit(3000, 4000, 1184, 720))
    }

    @Test
    fun itStartsWithItsMiddleSquareFillingTheSmallPicture() {
        val square = Box(100f, 200f, 180f, 280f)
        val whole = PictureGrow.aroundSquare(4000, 3000, square)
        // As high as the square, wider than it, about the same middle.
        assertEquals(80f, whole.height, 0.01f)
        assertEquals(80f * 4 / 3, whole.width, 0.01f)
        assertEquals(square.centerX, whole.centerX, 0.01f)
        assertEquals(square.centerY, whole.centerY, 0.01f)
        // Its middle square is the small picture exactly, upright or turned.
        assertBox(square, PictureGrow.middle(whole, 4000, 3000))
        assertBox(square, PictureGrow.middle(PictureGrow.aroundSquare(3000, 4000, square), 3000, 4000))
    }

    @Test
    fun aSquarePictureIsAllItsMiddle() {
        val fit = PictureGrow.fit(1000, 1000, 720, 1184)
        assertBox(fit, PictureGrow.middle(fit, 1000, 1000))
    }

    @Test
    fun withOnlyTheSmallPictureItShowsNoMoreThanItsMiddleSquare() {
        // Growing from the small picture before the whole one is decoded: what shows (the small
        // picture's place moving to the middle square on the screen) is at every step the middle
        // square of the picture as it is then, all the small picture knows of.
        val w = 4000
        val h = 3000
        val thumb = Box(30f, 900f, 130f, 1000f)
        val from = PictureGrow.aroundSquare(w, h, thumb)
        val to = PictureGrow.fit(w, h, 720, 1184)
        val end = PictureGrow.middle(to, w, h)
        for (step in 0..10) {
            val f = step / 10f
            assertBox(PictureGrow.middle(PictureGrow.mix(from, to, f), w, h), PictureGrow.mix(thumb, end, f), 0.05f)
        }
    }

    @Test
    fun aBoxMovesInAStraightLine() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(100f, 200f, 300f, 400f)
        assertBox(a, PictureGrow.mix(a, b, 0f))
        assertBox(b, PictureGrow.mix(a, b, 1f))
        assertBox(Box(50f, 100f, 155f, 205f), PictureGrow.mix(a, b, 0.5f))
    }

    @Test
    fun onlyThePartOnTheScreenShowsAsItShrinks() {
        val screen = Box(0f, 0f, 720f, 1184f)
        // Zoomed in: wider than the screen, cut at its sides.
        assertBox(Box(0f, 100f, 720f, 900f), PictureGrow.within(Box(-500f, 100f, 1300f, 900f), screen))
        // Wholly off it: nothing.
        val off = PictureGrow.within(Box(800f, 0f, 900f, 100f), screen)
        assertTrue(off.width <= 0f || off.height <= 0f)
    }
}
