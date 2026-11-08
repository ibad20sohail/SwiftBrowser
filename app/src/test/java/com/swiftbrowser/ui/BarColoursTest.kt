package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0.4: websites' colours in the address bar (CssColour, BarTones, BarColours). */
class BarColoursTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** Every colour of #rgb: a sweep through all hues and tones. */
    private val sweep: List<Int> = (0 until 4096).map { i ->
        val r = (i shr 8) and 15
        val g = (i shr 4) and 15
        val b = i and 15
        black or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
    }

    @Test
    fun overAPictureTheBarIsClearAndItsButtonsAreInTheInk() {
        val pill = 0xFF2A2C31.toInt()
        val danger = 0xFFB00020.toInt()
        for (ink in listOf(white, 0xFF1A1C21.toInt())) {
            val c = BarColours.overPicture(ink, pill, danger)
            assertEquals(0, c.bar ushr 24)
            assertEquals(0, c.line ushr 24)
            assertEquals(ink, c.text)
            assertEquals(ink, c.icon)
            assertEquals(ink, c.accent)
            assertEquals(pill, c.pill)
        }
    }

    @Test
    fun overAPictureTheRipplesFollowTheInk() {
        assertTrue(BarColours.overPicture(white, 0, 0).dark)
        assertEquals(false, BarColours.overPicture(0xFF1A1C21.toInt(), 0, 0).dark)
    }

    @Test
    fun hexColours() {
        assertEquals(white, CssColour.parse("#fff"))
        assertEquals(white, CssColour.parse("#FFF"))
        assertEquals(0xFF1E2327.toInt(), CssColour.parse("#1e2327"))
        assertEquals(0xFF1E2327.toInt(), CssColour.parse("  #1E2327 "))
        assertEquals(0x44112233, CssColour.parse("#11223344"))
        assertEquals(0xDDAABBCC.toInt(), CssColour.parse("#abcd"))
        assertNull(CssColour.parse("#12345"))
        assertNull(CssColour.parse("#ggg"))
        assertNull(CssColour.parse("#"))
    }

    @Test
    fun rgbColours() {
        assertEquals(0xFFFF0000.toInt(), CssColour.parse("rgb(255, 0, 0)"))
        assertEquals(0xFFFF0000.toInt(), CssColour.parse("RGB(255,0,0)"))
        // YouTube's: almost opaque.
        assertEquals(0xFA0F0F0F.toInt(), CssColour.parse("rgba(15, 15, 15, 0.98)"))
        // The newer way: spaces and "/ alpha".
        assertEquals(0x800080FF.toInt(), CssColour.parse("rgb(0 128 255 / 50%)"))
        assertEquals(0xFFFF8000.toInt(), CssColour.parse("rgb(100%, 50%, 0%)"))
        // Out of range is clamped, as CSS does.
        assertEquals(0xFFFF0000.toInt(), CssColour.parse("rgb(300, -5, 0)"))
        assertEquals(0xFF0A0B0C.toInt(), CssColour.parse("rgba(10 11 12)"))
        assertEquals(0xFF0A0B0C.toInt(), CssColour.parse("rgb(1e1, 11.2, 12)"))
        assertNull(CssColour.parse("rgb(1, 2)"))
        assertNull(CssColour.parse("rgb(1 2 3 4)"))
        assertNull(CssColour.parse("rgb(1, 2, 3 / 0.5)"))
        assertNull(CssColour.parse("rgb(NaN, 0, 0)"))
        assertNull(CssColour.parse("rgb(1d, 0, 0)"))
        assertNull(CssColour.parse("rgb(0x10, 0, 0)"))
        assertNull(CssColour.parse("rgb(1, 2, 3"))
    }

    @Test
    fun hslColours() {
        assertEquals(0xFFFF0000.toInt(), CssColour.parse("hsl(0, 100%, 50%)"))
        assertEquals(0xFF008000.toInt(), CssColour.parse("hsl(120deg 100% 25%)"))
        assertEquals(0x800000FF.toInt(), CssColour.parse("hsla(240, 100%, 50%, .5)"))
        assertEquals(0xFF00FFFF.toInt(), CssColour.parse("hsl(0.5turn 100% 50%)"))
        assertEquals(0xFF00FFFF.toInt(), CssColour.parse("hsl(200grad 100% 50%)"))
        assertEquals(0xFFFF0000.toInt(), CssColour.parse("hsl(-360 100% 50%)"))
        assertEquals(0xFF808080.toInt(), CssColour.parse("hsl(50 0% 50.2%)"))
        assertNull(CssColour.parse("hsl(0, 100%)"))
    }

    @Test
    fun namedColours() {
        assertEquals(white, CssColour.parse("white"))
        assertEquals(0xFF663399.toInt(), CssColour.parse("RebeccaPurple"))
        assertEquals(0xFF000080.toInt(), CssColour.parse(" navy "))
        assertEquals(0xFF808080.toInt(), CssColour.parse("grey"))
        assertEquals(0xFF9ACD32.toInt(), CssColour.parse("yellowgreen"))
        assertEquals(0xFFF0F8FF.toInt(), CssColour.parse("aliceblue"))
        assertEquals(0, CssColour.parse("transparent"))
    }

    @Test
    fun notColoursSwiftReads() {
        for (s in listOf(null, "", "   ", "currentcolor", "inherit", "whitee", "lab(50% 40 59)", "color-mix(in srgb, red, blue)", "#" + "f".repeat(200))) {
            assertNull(s, CssColour.parse(s))
        }
    }

    @Test
    fun aSeeThroughColourIsMadeOpaqueOverTheBar() {
        // Almost opaque: as it is, over the bar it sits on.
        assertEquals(0xFF0F0F0F.toInt(), BarTones.forBar(0xFA0F0F0F.toInt(), 0xFF0F0F0F.toInt(), dark = false))
        // Half black over white: grey.
        assertEquals(0xFF7F7F7F.toInt(), BarTones.forBar(0x80000000.toInt(), white, dark = false))
        // Hardly there at all: none.
        assertNull(BarTones.forBar(0, white, dark = false))
        assertNull(BarTones.forBar(0x10FF0000, white, dark = false))
        // Opaque: unchanged, whatever is under it.
        assertEquals(0xFF336699.toInt(), BarTones.forBar(0xFF336699.toInt(), black, dark = false))
    }

    @Test
    fun theDarkLookTakesOnlyDarkColours() {
        val dark = 0xFF15171C.toInt()
        assertNull(BarTones.forBar(white, dark, dark = true))
        assertNull(BarTones.forBar(0xFFFF0000.toInt(), dark, dark = true))
        assertNull(BarTones.forBar(0xFF1DA1F2.toInt(), dark, dark = true))
        // GitHub's, YouTube's and X's dark colours.
        assertEquals(0xFF1E2327.toInt(), BarTones.forBar(0xFF1E2327.toInt(), dark, dark = true))
        assertEquals(0xFF0F0F0F.toInt(), BarTones.forBar(0xFF0F0F0F.toInt(), dark, dark = true))
        assertEquals(black, BarTones.forBar(black, dark, dark = true))
        // The light look takes light colours too.
        assertEquals(white, BarTones.forBar(white, white, dark = false))
        for (c in sweep) {
            val used = BarTones.forBar(c, dark, dark = true) ?: continue
            assertTrue(Contrast.ratio(used, white) >= BarTones.DARK_LOOK_CONTRAST)
        }
    }

    @Test
    fun textAndIconsReadOnEveryColour() {
        val accent = 0xFF1B66E4.toInt()
        val danger = 0xFFC5221F.toInt()
        for (c in sweep) {
            val b = BarColours.forSite(c, accent, danger)
            assertEquals(c, b.bar)
            assertTrue(b.text == BarTones.DARK_INK || b.text == BarTones.LIGHT_INK)
            // The site's name on the address box: 4.5:1.
            assertTrue("text on ${Integer.toHexString(c)}", Contrast.ratio(b.text, b.pill) >= Contrast.TEXT)
            // Icons on the bar and the box: 3:1; the text on the bar at least 4:1.
            assertTrue("icons on ${Integer.toHexString(c)}", Contrast.ratio(b.icon, b.bar) >= BarTones.GRAPHIC)
            assertTrue(Contrast.ratio(b.icon, b.pill) >= BarTones.GRAPHIC)
            assertTrue(Contrast.ratio(b.text, b.bar) >= 4.0)
            // The accent's bits stand out, or take the text's colour.
            assertTrue(b.accent == accent || b.accent == b.text)
            assertTrue(Contrast.ratio(b.accent, b.bar) >= BarTones.GRAPHIC)
            assertTrue(b.danger == danger || b.danger == b.text)
            assertTrue(b.site)
        }
    }

    @Test
    fun theAddressBoxIsAStepOffTheBar() {
        // Darker on a light bar, lighter on a dark one, as Swift's own.
        val onWhite = BarTones.pill(white, BarTones.ink(white))
        assertTrue(Contrast.luminance(onWhite) < Contrast.luminance(white))
        assertNotEquals(white, onWhite)
        val onBlack = BarTones.pill(black, BarTones.ink(black))
        assertTrue(Contrast.luminance(onBlack) > Contrast.luminance(black))
        assertEquals(BarTones.DARK_INK, BarTones.ink(white))
        assertEquals(BarTones.LIGHT_INK, BarTones.ink(black))
        assertEquals(BarTones.LIGHT_INK, BarTones.ink(0xFF1E2327.toInt()))
    }

    @Test
    fun theAccentStaysVisible() {
        val blue = 0xFF1B66E4.toInt()
        val ink = BarTones.ink(blue)
        // Blue on a blue bar: the bar's text colour instead.
        assertEquals(ink, BarTones.onBar(blue, blue, ink))
        assertEquals(blue, BarTones.onBar(blue, white, BarTones.ink(white)))
    }

    @Test
    fun coloursChangeGradually() {
        assertEquals(black, BarTones.blend(black, white, 0.0))
        assertEquals(white, BarTones.blend(black, white, 1.0))
        assertEquals(0xFF808080.toInt(), BarTones.blend(black, white, 0.5))
        assertEquals(0x80000000.toInt(), BarTones.blend(0, black, 0.5))
        val a = BarColours.forSite(0xFF336699.toInt(), 0xFF1B66E4.toInt(), 0xFFC5221F.toInt())
        val b = BarColours(white, 0xFFEFF2F7.toInt(), 0xFF1A1C21.toInt(), 0xFF5C6370.toInt(), 0xFFE6E9EF.toInt(), 0xFF1B66E4.toInt(), 0xFFC5221F.toInt())
        val start = BarColours.between(a, b, 0f)
        assertEquals(a.bar, start.bar)
        assertEquals(a.pill, start.pill)
        assertEquals(a.text, start.text)
        assertEquals(b, BarColours.between(a, b, 1f))
        // Half way from a black bar to a white one: grey.
        assertEquals(0xFF808080.toInt(), BarColours.between(BarColours(black, black, black, black, black, black, black), b, 0.5f).bar)
    }
}
