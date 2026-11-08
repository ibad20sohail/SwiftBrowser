package com.swiftbrowser.ui

import com.swiftbrowser.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0.5: text on the home page's picture stays readable on any picture (HomeBacking). */
class HomeBackingTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // The text and the pages it stands on: light and dark mode, stock, ready-made, your own at the limit.
    private val textLight = 0xFF1A1C21.toInt()
    private val greyLight = 0xFF5C6370.toInt()
    private val textDark = 0xFFE6E8EC.toInt()
    private val greyDark = 0xFFA3A9B4.toInt()
    private val lightPages = listOf(0xFFFFFFFF, 0xFFFCF8F0, 0xFFF4F6F9).map { it.toInt() } + Palette.limitLight(0xFF8899AA.toInt())
    private val darkPages = listOf(0xFF15171C, 0xFF000000, 0xFF0D1322, 0xFF1B1815).map { it.toInt() } + Palette.limitDark(0xFFFFFFFF.toInt())

    /** Picture pixels to stand on: black, white, and some in between. */
    private val pictures = listOf(black, white, 0xFF808080, 0xFFFFD700, 0xFF003080, 0xFF40A060, 0xFFFF2010, 0xFFE0E0E0).map { it.toInt() }

    private fun backdrop(picture: Int, page: Int, veil: Int, opacity: Int): Int =
        Contrast.mix(Contrast.mix(picture, page, veil / 255.0), page, opacity / 255.0)

    private fun assertReads(page: Int, text: Int, veil: Int, min: Double = Contrast.TEXT) {
        val o = HomeBacking.opacity(page, text, veil, min)
        assertTrue("opacity $o", o in 0..255)
        for (picture in pictures) {
            val r = Contrast.ratio(text, backdrop(picture, page, veil, o))
            assertTrue("text %08X on picture %08X page %08X: $r".format(text, picture, page), r >= min)
        }
    }

    @Test
    fun theTextReadsOnEveryPicture() {
        for (page in lightPages) {
            assertReads(page, textLight, HomeBacking.VEIL_TOP_LIGHT)
            // Grey text as the app shows it on this background (deepened where the stock one wouldn't read, 3.0).
            assertReads(page, Palette.lightRoles(page, greyLight).getValue(R.color.text_secondary), HomeBacking.VEIL_TOP_LIGHT)
        }
        for (page in darkPages) {
            assertReads(page, textDark, HomeBacking.VEIL_TOP_DARK)
            assertReads(page, Palette.darkRoles(page, greyDark).getValue(R.color.text_secondary), HomeBacking.VEIL_TOP_DARK)
        }
    }

    @Test
    fun itsOnlyAsStrongAsItHasToBe() {
        // Under the most see-through veil, the page colour goes behind light text only partly: the
        // picture still shows through it. One step less isn't enough for the worst picture.
        val page = 0xFF15171C.toInt()
        val o = HomeBacking.opacity(page, textDark, HomeBacking.VEIL_TOP_DARK)
        assertTrue("$o", o in 40..200)
        val worst = white
        assertTrue(Contrast.ratio(textDark, backdrop(worst, page, HomeBacking.VEIL_TOP_DARK, o - 1)) < Contrast.TEXT)
        val light = HomeBacking.opacity(white, textLight, HomeBacking.VEIL_TOP_LIGHT)
        assertTrue("$light", light in 10..200)
        assertTrue(Contrast.ratio(textLight, backdrop(black, white, HomeBacking.VEIL_TOP_LIGHT, light - 1)) < Contrast.TEXT)
    }

    @Test
    fun aMissingVeilAsksForMore() {
        // A veil already gives something; the backing makes up the rest.
        val page = 0xFF15171C.toInt()
        val thinVeil = HomeBacking.opacity(page, textDark, 0)
        val thickVeil = HomeBacking.opacity(page, textDark, 0xE6)
        assertTrue(thickVeil < thinVeil)
        assertEquals(0, HomeBacking.opacity(page, textDark, 255))
    }

    @Test
    fun aPageThatDoesntReadGivesFullBacking() {
        // Nothing helps when the page itself is too close to the text: it stays as strong as can be.
        assertEquals(255, HomeBacking.opacity(0xFF808080.toInt(), 0xFF777777.toInt(), 0x66))
    }
}
