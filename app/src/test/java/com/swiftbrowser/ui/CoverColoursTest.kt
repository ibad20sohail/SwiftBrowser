package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** 3.0.4: the music screen in its cover's colours (CoverColours, and Tones.tone's chroma). */
class CoverColoursTest {
    /** The stock text colours of light and dark mode (values/colors.xml, values-night/colors.xml). */
    private val textLight = 0xFF1A1C21.toInt()
    private val greyLight = 0xFF5C6370.toInt()
    private val textDark = 0xFFE6E8EC.toInt()
    private val greyDark = 0xFFA3A9B4.toInt()

    /** Covers' main colours: red, blue, yellow, green, brown, purple, blue-grey, cyan, orange, olive, and a dull one. */
    private val seeds = intArrayOf(
        0xFFD23C32.toInt(), 0xFF1E5AD2.toInt(), 0xFFE8C020.toInt(), 0xFF2E8B57.toInt(), 0xFF8A5A44.toInt(),
        0xFF9C27B0.toInt(), 0xFF607D8B.toInt(), 0xFF00BCD4.toInt(), 0xFFFF9800.toInt(), 0xFF6D6D60.toInt(),
        0xFF8C8478.toInt(),
    )

    private fun lightness(c: Int) = Tones.lab(c).first

    private fun chroma(c: Int) = Tones.lab(c).let { hypot(it.second, it.third) }

    private fun hue(c: Int) = Tones.lab(c).let { Math.toDegrees(atan2(it.third, it.second)) }

    private fun hueGap(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return minOf(d, 360 - d)
    }

    @Test
    fun everythingReadsOnTheBackground() {
        for (seed in seeds) {
            for (night in listOf(false, true)) {
                val s = CoverColours.scheme(seed, night, if (night) textDark else textLight, if (night) greyDark else greyLight)
                val what = "%08X %s".format(seed, if (night) "dark" else "light")
                assertTrue(what, Contrast.ratio(s.accent, s.background) >= CoverColours.CONTROLS)
                assertTrue(what, Contrast.ratio(s.onAccent, s.accent) >= Contrast.TEXT)
                assertTrue(what, Contrast.ratio(s.text, s.background) >= Contrast.TEXT)
                assertTrue(what, Contrast.ratio(s.secondary, s.background) >= Contrast.TEXT)
            }
        }
    }

    @Test
    fun theBackgroundIsSoft() {
        for (seed in seeds) {
            val light = CoverColours.scheme(seed, false, textLight, greyLight)
            assertEquals(95.0, lightness(light.background), 1.0)
            assertTrue(chroma(light.background) <= 8.0)
            val dark = CoverColours.scheme(seed, true, textDark, greyDark)
            assertEquals(10.0, lightness(dark.background), 1.0)
            assertTrue(chroma(dark.background) <= 11.0)
        }
    }

    @Test
    fun aDullCoversAccentIsClearToo() {
        // A warm grey cover (chroma under 8) gets an accent of its hue, 40 colourful (CoverColours' least).
        val s = CoverColours.scheme(0xFF8C8478.toInt(), false, textLight, greyLight)
        assertEquals(40.0, chroma(s.accent), 1.5)
    }

    @Test
    fun theAccentIsTheCoversColourAndClear() {
        for (seed in seeds) {
            val s = CoverColours.scheme(seed, false, textLight, greyLight)
            assertTrue("%08X".format(seed), hueGap(hue(s.accent), hue(seed)) < 20.0)
            // A dull cover still gives a clear accent (as clear as its hue can be at this tone).
            assertTrue("%08X".format(seed), chroma(s.accent) > 20.0)
            assertEquals(40.0, lightness(s.accent), 2.0)
            assertEquals(80.0, lightness(CoverColours.scheme(seed, true, textDark, greyDark).accent), 2.0)
        }
    }

    @Test
    fun theStockTextIsKeptWhenItReads() {
        val s = CoverColours.scheme(0xFF1E5AD2.toInt(), false, textLight, greyLight)
        assertEquals(textLight, s.text)
        assertEquals(greyLight, s.secondary)
    }

    @Test
    fun toneCanAskForLessOrMoreColour() {
        val blue = 0xFF1E5AD2.toInt()
        assertEquals(Tones.tone(blue, 60.0), Tones.tone(blue, 60.0, Double.NaN))
        assertTrue(chroma(Tones.tone(blue, 60.0, 0.0)) < 1.0)
        assertEquals(10.0, chroma(Tones.tone(blue, 60.0, 10.0)), 1.0)
        assertTrue(hueGap(hue(Tones.tone(blue, 60.0, 10.0)), hue(blue)) < 5.0)
        assertEquals(60.0, lightness(Tones.tone(blue, 60.0, 10.0)), 0.6)
    }

    @Test
    fun keptPerSongAndGreyCoversToo() {
        val key = "light file:///sdcard/Music/test/a song.mp3"
        assertNull(CoverColours.kept(key))
        val s = CoverColours.scheme(0xFFD23C32.toInt(), false, textLight, greyLight)
        CoverColours.remember(key, s)
        assertSame(s, CoverColours.kept(key))
        CoverColours.remember("$key 2", null)
        assertSame(CoverColours.NONE, CoverColours.kept("$key 2"))
        assertNotSame(CoverColours.NONE, CoverColours.kept(key))
    }
}
