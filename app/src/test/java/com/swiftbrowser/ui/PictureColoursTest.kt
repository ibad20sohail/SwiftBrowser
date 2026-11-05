package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.roundToInt

class PictureColoursTest {
    /** A picture made of [parts]: (colour, how many pixels). */
    private fun picture(vararg parts: Pair<Int, Int>): IntArray =
        parts.flatMap { (c, n) -> List(n) { c } }.toIntArray()

    private fun hue(c: Int): Int {
        val lab = PictureColours.lab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        return ((Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360).roundToInt() % 360
    }

    @Test
    fun theColourCoveringMostComesFirst() {
        val blue = 0xFF1E5AD2.toInt()
        val red = 0xFFD23C32.toInt()
        val seeds = PictureColours.seeds(picture(blue to 7000, red to 3000))
        assertEquals(2, seeds.size)
        assertEquals(blue, seeds[0])
        assertEquals(red, seeds[1])
    }

    @Test
    fun aGreyPictureHasNoColours() {
        val seeds = PictureColours.seeds(picture(0xFF808080.toInt() to 5000, 0xFFF2F2F2.toInt() to 3000, 0xFF101010.toInt() to 2000))
        assertTrue(seeds.isEmpty())
    }

    @Test
    fun aTinySpotDoesNotCount() {
        val green = 0xFF2E8B57.toInt()
        val seeds = PictureColours.seeds(picture(green to 9000, 0xFFFF0000.toInt() to 50, 0xFF777777.toInt() to 950))
        assertEquals(listOf(green), seeds)
    }

    @Test
    fun greyAroundAColourStillFindsIt() {
        // A photo that is mostly grey sky with an orange subject: the orange is the colour.
        val orange = 0xFFE07B20.toInt()
        val seeds = PictureColours.seeds(picture(0xFF9A9A9A.toInt() to 8000, orange to 2000))
        assertEquals(listOf(orange), seeds)
    }

    @Test
    fun coloursAreFarApartInHue() {
        // Two near blues and a yellow: the second blue is too close to the first to be offered.
        val seeds = PictureColours.seeds(
            picture(0xFF2050D0.toInt() to 5000, 0xFF2858C8.toInt() to 3000, 0xFFE8C020.toInt() to 2000),
        )
        assertEquals(2, seeds.size)
        assertTrue(PictureColours.hueGap(hue(seeds[0]), hue(seeds[1])) >= 15)
    }

    @Test
    fun noMoreThanAskedFor() {
        val many = picture(
            0xFFD03030.toInt() to 2000, 0xFF30A030.toInt() to 2000, 0xFF3040D0.toInt() to 2000,
            0xFFD0C030.toInt() to 2000, 0xFFA030C0.toInt() to 2000,
        )
        assertEquals(3, PictureColours.seeds(many, max = 3).size)
        assertEquals(4, PictureColours.seeds(many).size)
    }

    @Test
    fun seeThroughPixelsAreNotPartOfThePicture() {
        val pink = 0xFFD0306E.toInt()
        val clearRed = 0x00FF0000
        assertEquals(listOf(pink), PictureColours.seeds(picture(clearRed to 9000, pink to 1000)))
        assertTrue(PictureColours.seeds(picture(clearRed to 100)).isEmpty())
    }

    private fun lightness(c: Int) = PictureColours.lab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)[0]

    @Test
    fun aMixKeepsThePicturesHue() {
        val navy = 0xFF08304A.toInt()
        val (accent, light, dark) = PictureColours.mix(navy).toList()
        assertTrue(PictureColours.hueGap(hue(navy), hue(accent)) <= 3)
        // The accent sits in the middle: neither the near-black of the picture nor a pastel.
        assertEquals(50.0, lightness(accent), 1.5)
        assertEquals(97.0, lightness(light), 1.5)
        assertEquals(8.0, lightness(dark), 1.5)
    }

    @Test
    fun aMutedColourStillGivesAnAccentThatShows() {
        val beige = 0xFFB8A890.toInt()
        val accent = PictureColours.mix(beige)[0]
        val lab = PictureColours.lab((accent shr 16) and 0xFF, (accent shr 8) and 0xFF, accent and 0xFF)
        assertTrue(Math.hypot(lab[1], lab[2]) >= 30)
    }

    @Test
    fun backgroundsAreOnlyAHintOfColour() {
        val green = 0xFF1E9E3C.toInt()
        val (_, light, dark) = PictureColours.mix(green).toList()
        for (c in listOf(light, dark)) {
            val lab = PictureColours.lab((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            assertTrue(Math.hypot(lab[1], lab[2]) <= 6.5)
        }
    }

    @Test
    fun hueGapGoesRoundTheCircle() {
        assertEquals(20, PictureColours.hueGap(350, 10))
        assertEquals(180, PictureColours.hueGap(0, 180))
        assertEquals(0, PictureColours.hueGap(42, 42))
    }
}
