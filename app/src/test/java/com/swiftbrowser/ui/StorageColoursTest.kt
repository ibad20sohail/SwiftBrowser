package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class StorageColoursTest {
    // The accents as on screen: the hand-picked ones of light and dark mode, others worked out.
    private val lightAccents = listOf(
        0xFF1B66E4, 0xFF1E7D45, 0xFF7A4CD9, 0xFFC2570C, 0xFFC62845, 0xFF00796B, // blue, green, purple, orange, red, teal
        0xFF3F52C6, 0xFFB80D5B, 0xFF805600, 0xFF006874, 0xFF8A4F32, 0xFF445D9B, // indigo, pink, amber, cyan, brown, graphite
        0xFF767676, 0xFF000000, 0xFF595959, 0xFF776C1C, 0xFF5C7700, 0xFF0000FF, 0xFFE00000, // your own: greys, black, yellow, lime, blue, red
    ).map { it.toInt() }
    private val darkAccents = listOf(
        0xFF8AB4FF, 0xFF7DD3A0, 0xFFCDB4FF, 0xFFFFB68A, 0xFFFFB2BB, 0xFF6FD6C8,
        0xFFC5C0FF, 0xFFFFB1C4, 0xFFFFBA43, 0xFF47D8EC, 0xFFFFB695, 0xFFB6C4FF,
        0xFF8A8A8A, 0xFFFFFFFF, 0xFFC0C0C0, 0xFFFFEB3B, 0xFFC6FF00, 0xFF7C7CFF, 0xFFFF6E6E,
    ).map { it.toInt() }

    // The cards: white, warm paper and cool grey; dark grey, pure black, navy and warm dark.
    private val lightCards = listOf(0xFFFFFFFF, 0xFFFCF8F0, 0xFFF4F6F9).map { it.toInt() }
    private val darkCards = listOf(0xFF15171C, 0xFF000000, 0xFF0D1322, 0xFF1B1815).map { Contrast.above(it.toInt(), DarkSteps.CARD) }

    private fun distance(a: Int, b: Int): Double {
        val x = StorageColours.oklab(a)
        val y = StorageColours.oklab(b)
        return sqrt((x[0] - y[0]) * (x[0] - y[0]) + (x[1] - y[1]) * (x[1] - y[1]) + (x[2] - y[2]) * (x[2] - y[2]))
    }

    private fun hue(c: Int): Double {
        val lab = StorageColours.oklab(c)
        return (Math.toDegrees(atan2(lab[2], lab[1])) + 360) % 360
    }

    private fun each(check: (accent: Int, card: Int, night: Boolean, colours: List<Int>) -> Unit) {
        for (night in listOf(false, true)) {
            for (card in if (night) darkCards else lightCards) {
                for (accent in if (night) darkAccents else lightAccents) {
                    check(accent, card, night, StorageColours.kinds(accent, card, night).toList() + StorageColours.other(card, night))
                }
            }
        }
    }

    @Test
    fun everyPartReadsOnTheCard() {
        each { accent, card, night, colours ->
            for (c in colours) {
                assertTrue("${Integer.toHexString(c)} on ${Integer.toHexString(card)} (accent ${Integer.toHexString(accent)}, night $night)", Contrast.ratio(c, card) >= 3.0)
            }
        }
    }

    @Test
    fun thePartsAreToldApart() {
        each { accent, _, night, colours ->
            for (i in colours.indices) for (j in i + 1 until colours.size) {
                assertTrue("parts $i and $j for ${Integer.toHexString(accent)} (night $night)", distance(colours[i], colours[j]) >= 0.08)
            }
        }
    }

    @Test
    fun picturesAreInTheAccentItself() {
        // The hand-picked accents, light and dark.
        for (a in lightAccents.take(6)) assertEquals(a, StorageColours.kinds(a, lightCards[0], false)[0])
        for (a in darkAccents.take(6)) assertEquals(a, StorageColours.kinds(a, darkCards[0], true)[0])
    }

    @Test
    fun theOthersTurnFromTheAccentsHue() {
        // Blue: videos a quarter of the way round from it, music half, documents three quarters.
        val blue = 0xFF1B66E4.toInt()
        val k = StorageColours.kinds(blue, lightCards[0], false)
        for (i in 1..3) {
            val turned = (hue(k[i]) - hue(blue) + 360) % 360
            assertTrue("kind $i turned $turned", abs(turned - 90.0 * i) < 12)
        }
    }

    @Test
    fun otherIsANeutralGrey() {
        for (card in lightCards + darkCards) {
            val o = StorageColours.other(card, card in darkCards)
            val lab = StorageColours.oklab(o)
            assertTrue(sqrt(lab[1] * lab[1] + lab[2] * lab[2]) < 0.01)
        }
    }

    @Test
    fun aGreyAccentStillGivesColours() {
        // Your own grey has no hue: the parts start from a blue, and none of them is grey.
        for ((grey, night) in listOf(0xFF767676.toInt() to false, 0xFF8A8A8A.toInt() to true)) {
            for (c in StorageColours.kinds(grey, if (night) darkCards[0] else lightCards[0], night)) {
                val lab = StorageColours.oklab(c)
                assertTrue(sqrt(lab[1] * lab[1] + lab[2] * lab[2]) > 0.05)
            }
        }
    }
}
