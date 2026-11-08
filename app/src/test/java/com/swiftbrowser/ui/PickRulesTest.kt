package com.swiftbrowser.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickRulesTest {
    private fun accepts(mime: String?, vararg wanted: String) = PickRules.accepts(arrayOf(*wanted), mime)

    @Test
    fun aPageThatSaysNothingTakesAnything() {
        assertTrue(accepts("video/mp4"))
        assertTrue(accepts("video/mp4", "*/*"))
    }

    @Test
    fun aWholeKindOfFile() {
        assertTrue(accepts("image/png", "image/*"))
        assertFalse(accepts("video/mp4", "image/*"))
        // Only the part before the slash counts.
        assertFalse(accepts("imagery/png", "image/*"))
    }

    @Test
    fun exactTypes() {
        assertTrue(accepts("application/pdf", "application/pdf", "image/*"))
        assertFalse(accepts("application/zip", "application/pdf"))
    }

    @Test
    fun capitalsAndSpacesDontMatter() {
        assertTrue(accepts("IMAGE/PNG", " image/* "))
    }

    @Test
    fun aFileOfUnknownType() {
        assertFalse(accepts(null, "image/*"))
        assertTrue(accepts(null))
        assertTrue(accepts(null, "*/*"))
    }
}
