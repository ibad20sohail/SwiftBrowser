package com.swiftbrowser.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInkTest {
    @Test
    fun aDarkPictureTakesLightText() {
        assertTrue(PictureInk.light(216, 216))
        assertTrue(PictureInk.light(150, 216))
    }

    @Test
    fun aBrightPictureTakesDarkText() {
        assertFalse(PictureInk.light(0, 216))
        assertFalse(PictureInk.light(60, 216))
    }

    @Test
    fun aMixedPictureTakesLightTextWithItsShadow() {
        assertTrue(PictureInk.light(100, 216))
    }

    @Test
    fun noPointsMeansNoLightText() {
        assertFalse(PictureInk.light(0, 0))
    }

    @Test
    fun theBarsButtonsGoLightOnlyOnHalfTheStripOrMore() {
        assertTrue(PictureInk.barLight(48, 48))
        assertTrue(PictureInk.barLight(24, 48))
        assertFalse(PictureInk.barLight(23, 48))
        assertFalse(PictureInk.barLight(0, 0))
    }
}
