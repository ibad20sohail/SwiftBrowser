package com.swiftbrowser.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPanesTest {
    @Test
    fun twoFoldersOnlySidewaysOnAWideScreen() {
        // The phone and the emulator turned sideways: 640 dp.
        assertTrue(TwoPanes.shows(on = true, landscape = true, widthDp = 640))
        assertTrue(TwoPanes.shows(on = true, landscape = true, widthDp = 560))
        assertFalse(TwoPanes.shows(on = true, landscape = true, widthDp = 559))
        // Held upright: always one folder, and no button.
        assertFalse(TwoPanes.shows(on = true, landscape = false, widthDp = 800))
        assertFalse(TwoPanes.offered(landscape = false, widthDp = 800))
        // Turned off: the button is still there to turn them on.
        assertFalse(TwoPanes.shows(on = false, landscape = true, widthDp = 640))
        assertTrue(TwoPanes.offered(landscape = true, widthDp = 640))
    }

    @Test
    fun theOtherSide() {
        assertEquals(TwoPanes.RIGHT, TwoPanes.other(TwoPanes.LEFT))
        assertEquals(TwoPanes.LEFT, TwoPanes.other(TwoPanes.RIGHT))
        assertEquals(TwoPanes.LEFT, TwoPanes.side(null))
        assertEquals(TwoPanes.LEFT, TwoPanes.side(7))
        assertEquals(TwoPanes.RIGHT, TwoPanes.side(TwoPanes.RIGHT))
    }

    @Test
    fun theOtherSideIsNamedByWhereItIs() {
        assertEquals("right", TwoPanes.toward(TwoPanes.LEFT, rtl = false))
        assertEquals("left", TwoPanes.toward(TwoPanes.RIGHT, rtl = false))
        // A right-to-left language lays the row out from the right: the first side is on the right.
        assertEquals("left", TwoPanes.toward(TwoPanes.LEFT, rtl = true))
        assertEquals("right", TwoPanes.toward(TwoPanes.RIGHT, rtl = true))
    }

    @Test
    fun eachSideIsHalfTheScreen() {
        assertEquals(319, TwoPanes.paneWidthDp(640, two = true, maxDp = 560))
        // One folder keeps its readable width, as before.
        assertEquals(560, TwoPanes.paneWidthDp(640, two = false, maxDp = 560))
        assertEquals(360, TwoPanes.paneWidthDp(360, two = false, maxDp = 560))
        // Three tiles across a side, as across a phone held upright; more on one wide list.
        assertEquals(3, TwoPanes.gridColumns(319))
        assertEquals(3, TwoPanes.gridColumns(360))
        assertEquals(5, TwoPanes.gridColumns(560))
        assertEquals(6, TwoPanes.gridColumns(2000))
    }

    @Test
    fun copyingGoesToTheOtherSidesFolder() {
        assertEquals("/storage/emulated/0/Download", TwoPanes.target("/storage/emulated/0/Download", otherInArchive = false))
        // Private's folders are paths too (it asks to be unlocked before it shows).
        assertEquals("/data/user/0/com.swiftbrowser/files/private", TwoPanes.target("/data/user/0/com.swiftbrowser/files/private", false))
        // Not into the start page, a category, the recycle bin, nor into an archive.
        assertNull(TwoPanes.target("home", false))
        assertNull(TwoPanes.target("cat:videos", false))
        assertNull(TwoPanes.target("bin", false))
        assertNull(TwoPanes.target("/storage/emulated/0/Download", otherInArchive = true))
        assertNull(TwoPanes.target(null, false))
    }

    @Test
    fun turningThePhoneKeepsBothSides() {
        val dl = "/storage/emulated/0/Download"
        val dcim = "/storage/emulated/0/DCIM"
        // Sideways, the right side worked on last: both come back where they were.
        assertArrayEquals(arrayOf<String?>(dcim, dl), TwoPanes.places(TwoPanes.RIGHT, dl, dcim, two = true))
        // Upright: only the side worked on, with its folder.
        assertArrayEquals(arrayOf<String?>(null, dl), TwoPanes.places(TwoPanes.RIGHT, dl, dcim, two = false))
        // The first time two show, the other side opens the same folder…
        assertArrayEquals(arrayOf<String?>(dl, dl), TwoPanes.places(TwoPanes.LEFT, dl, null, two = true))
        // …or its own start page, beside a start page or a category.
        assertArrayEquals(arrayOf<String?>("cat:images", null), TwoPanes.places(TwoPanes.LEFT, "cat:images", null, two = true))
        assertArrayEquals(arrayOf<String?>(null, "home"), TwoPanes.places(TwoPanes.RIGHT, "home", null, two = true))
    }
}
