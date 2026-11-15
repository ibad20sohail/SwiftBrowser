package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPictureTest {
    @Test
    fun theLongerSideIsTheSameForEveryShape() {
        // A film (16:9), an old one (4:3), a phone held upright (9:16), a square.
        assertEquals(320 to 180, SeekPictureMaths.size(16f / 9f, 320))
        assertEquals(320 to 240, SeekPictureMaths.size(4f / 3f, 320))
        assertEquals(180 to 320, SeekPictureMaths.size(9f / 16f, 320))
        assertEquals(320 to 320, SeekPictureMaths.size(1f, 320))
    }

    @Test
    fun aShapeNotKnownYetIsTakenAsAFilmsAndAnOddOneIsKeptInBounds() {
        assertEquals(320 to 180, SeekPictureMaths.size(0f, 320))
        assertEquals(320 to 180, SeekPictureMaths.size(Float.NaN, 320))
        assertEquals(320 to 180, SeekPictureMaths.size(-2f, 320))
        // A very wide one keeps a quarter of the height at least, and so does a very tall one's width.
        assertEquals(320 to 80, SeekPictureMaths.size(10f, 320))
        assertEquals(80 to 320, SeekPictureMaths.size(0.1f, 320))
    }

    @Test
    fun thePictureIsOverTheFingerButStaysOnTheScreen() {
        // In the middle: centred on the handle.
        assertEquals(260, SeekPictureMaths.left(360f, 200, 720, 32))
        // Near either side: as far as the margin lets it.
        assertEquals(32, SeekPictureMaths.left(20f, 200, 720, 32))
        assertEquals(488, SeekPictureMaths.left(710f, 200, 720, 32))
        // Wider than the room there is: at the margin.
        assertEquals(32, SeekPictureMaths.left(100f, 700, 720, 32))
    }
}
