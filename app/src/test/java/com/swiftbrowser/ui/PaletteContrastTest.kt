package com.swiftbrowser.ui

import com.swiftbrowser.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0: text stays readable on your own colours (Palette, Contrast). */
class PaletteContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** The stock grey text of light and dark mode (values/colors.xml, values-night/colors.xml). */
    private val greyLight = 0xFF5C6370.toInt()
    private val greyDark = 0xFFA3A9B4.toInt()

    @Test
    fun luminanceAndRatioAreWcags() {
        assertEquals(1.0, Contrast.luminance(white), 1e-9)
        assertEquals(0.0, Contrast.luminance(black), 1e-9)
        assertEquals(21.0, Contrast.ratio(white, black), 1e-9)
        assertEquals(21.0, Contrast.ratio(black, white), 1e-9)
        assertEquals(1.0, Contrast.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-9)
        // The stock grey text on white: about 6:1.
        assertEquals(6.04, Contrast.ratio(greyLight, white), 0.05)
    }

    @Test
    fun mixingGoesPartWay() {
        assertEquals(0xFF808080.toInt(), Contrast.mix(black, white, 0.5))
        assertEquals(white, Contrast.mix(white, black, 0.0))
        assertEquals(black, Contrast.mix(white, black, 1.0))
    }

    @Test
    fun readableLeavesAGoodColourAsItIs() {
        assertEquals(greyLight, Contrast.readable(greyLight, listOf(white, 0xFFEFF2F7.toInt()), Contrast.TEXT, black))
    }

    @Test
    fun readableDeepensOrLightensUntilItReads() {
        val bg = 0xFFC0C0C0.toInt()
        val deeper = Contrast.readable(greyLight, listOf(bg), Contrast.TEXT, black)
        assertTrue(Contrast.ratio(deeper, bg) >= Contrast.TEXT)
        val dark = 0xFF555555.toInt()
        assertTrue(Contrast.ratio(greyDark, dark) < Contrast.TEXT)
        val lighter = Contrast.readable(greyDark, listOf(dark), Contrast.TEXT, white)
        assertTrue(Contrast.ratio(lighter, dark) >= Contrast.TEXT)
        assertTrue(Contrast.luminance(lighter) > Contrast.luminance(greyDark))
    }

    @Test
    fun ownBackgroundsStayLightOrDarkEnough() {
        assertTrue(Contrast.luminance(Palette.limitLight(0xFF336699.toInt())) >= 0.6)
        assertTrue(Contrast.luminance(Palette.limitDark(0xFFCCDDEE.toInt())) <= 0.07)
    }

    @Test
    fun greyTextReadsOnYourOwnLightBackground() {
        // Light enough for Swift (limitLight), yet the stock grey is only about 3.5:1 on it.
        val bg = Palette.limitLight(0xFF8899AA.toInt())
        assertTrue(Contrast.ratio(greyLight, bg) < Contrast.TEXT)
        val roles = Palette.lightRoles(bg, greyLight)
        val text = roles.getValue(R.color.text_secondary)
        assertTrue(Contrast.ratio(text, bg) >= Contrast.TEXT)
        assertTrue(Contrast.ratio(text, roles.getValue(R.color.surface_alt)) >= Contrast.TEXT)
    }

    @Test
    fun greyTextReadsOnYourOwnDarkBackground() {
        val bg = Palette.limitDark(0xFF556677.toInt())
        val roles = Palette.darkRoles(bg, greyDark)
        val text = roles.getValue(R.color.text_secondary)
        assertNotEquals(greyDark, text)
        assertTrue(Contrast.ratio(text, bg) >= Contrast.TEXT)
        assertTrue(Contrast.ratio(text, roles.getValue(R.color.surface_alt)) >= Contrast.TEXT)
    }

    @Test
    fun greyTextOnTheReadyMadeBackgroundsIsAsBefore() {
        // Warm paper and cool grey; pure black, navy and warm dark (Palette's presets).
        for (bg in listOf(0xFFFCF8F0, 0xFFF4F6F9)) assertEquals(greyLight, Palette.lightRoles(bg.toInt(), greyLight).getValue(R.color.text_secondary))
        for (bg in listOf(0xFF000000, 0xFF0D1322, 0xFF1B1815)) assertEquals(greyDark, Palette.darkRoles(bg.toInt(), greyDark).getValue(R.color.text_secondary))
    }

    @Test
    fun yourAccentReadsAsTextOnTheBackgroundChosen() {
        val bgs = listOf(white, 0xFFEFF2F7.toInt())
        // A light orange is deepened until it reads; white on it reads too.
        val (accent, on) = Palette.customAccent(0xFFFFA500.toInt(), night = false, bgs = bgs)
        bgs.forEach { assertTrue(Contrast.ratio(accent, it) >= Contrast.TEXT) }
        assertTrue(Contrast.ratio(on, accent) >= Contrast.TEXT)
        // The stock blue already reads (5.2:1 on white, 4.6:1 on the grey surface): as made.
        assertEquals(0xFF1B66E4.toInt(), Palette.customAccent(0xFF1B66E4.toInt(), night = false, bgs = bgs).first)
        // On your own darker light background, the same blue is deepened.
        val own = Palette.limitLight(0xFF8899AA.toInt())
        val onOwn = Palette.customAccent(0xFF1B66E4.toInt(), night = false, bgs = listOf(own)).first
        assertTrue(Contrast.ratio(onOwn, own) >= Contrast.TEXT)
    }

    @Test
    fun greysHaveNoHueToPickAThemeBy() {
        assertTrue(Contrast.greyish(0xFF808080.toInt()))
        assertTrue(Contrast.greyish(white))
        assertTrue(Contrast.greyish(black))
        // Nearly black, with a trace of colour.
        assertTrue(Contrast.greyish(0xFF050200.toInt()))
        assertFalse(Contrast.greyish(0xFF1B66E4.toInt()))
        assertFalse(Contrast.greyish(0xFF8A5A44.toInt()))
        // Graphite still has its blue.
        assertFalse(Contrast.greyish(0xFF5B6270.toInt()))
    }
}
