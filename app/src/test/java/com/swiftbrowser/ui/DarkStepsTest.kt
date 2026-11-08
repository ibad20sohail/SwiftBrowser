package com.swiftbrowser.ui

import com.swiftbrowser.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0.5: the same steps above the page on every dark background (Palette.darkRoles, DarkSteps). */
class DarkStepsTest {
    /** The stock text colours of dark mode (values-night/colors.xml). */
    private val textDark = 0xFFE6E8EC.toInt()
    private val greyDark = 0xFFA3A9B4.toInt()

    /** Every ready-made dark background, and your own: from black to the lightest Palette.limitDark allows. */
    private val backgrounds = Palette.darkBackgrounds.map { it.seed } + listOf(
        0xFF2B1F3C, 0xFF030303, 0xFF1A2B3C, 0xFF5A3A1A, 0xFF4A4A4A, 0xFF16202E, 0xFF3C1F1F,
    ).map { it.toInt() } + listOf(0xFFFFFFFF, 0xFF8899AA, 0xFFFF0080).map { Palette.limitDark(it.toInt()) }

    private fun on(page: Int, res: Int) = Palette.darkRoles(page, greyDark).getValue(res)

    private fun name(page: Int) = "%08X".format(page)

    @Test
    fun cardsStandClearOfThePageOnEveryDarkBackground() {
        for (page in backgrounds) {
            val card = on(page, R.color.card_bg)
            val step = Contrast.ratio(card, page)
            assertTrue("${name(page)} card $step", step >= 1.2)
            assertTrue("${name(page)} card $step", step <= 1.26)
            // On Settings' and the tab grid's page, a little darker than the page, a little more.
            val settings = on(page, R.color.settings_bg)
            assertTrue("${name(page)} on settings", Contrast.ratio(card, settings) >= step)
        }
    }

    @Test
    fun aPureBlackPageHasCardsYouCanSee() {
        assertTrue(Contrast.ratio(on(0xFF000000.toInt(), R.color.card_bg), 0xFF000000.toInt()) >= 1.2)
        // Not the 1.07:1 of before.
        assertTrue(on(0xFF000000.toInt(), R.color.card_bg) != 0xFF0B0B0B.toInt())
    }

    @Test
    fun sheetsAndTabCardsAreOnTheCardStep() {
        for (page in backgrounds) {
            assertEquals(on(page, R.color.card_bg), on(page, R.color.sheet_bg))
            assertEquals(on(page, R.color.card_bg), on(page, R.color.tab_card_bg))
        }
    }

    @Test
    fun theSurfacesRiseInOrder() {
        for (page in backgrounds) {
            val order = listOf(
                on(page, R.color.settings_bg), page, on(page, R.color.toolbar_bg), on(page, R.color.card_bg),
                on(page, R.color.tab_thumb_bg), on(page, R.color.surface_alt), on(page, R.color.divider), on(page, R.color.outline),
            ).map { Contrast.luminance(it) }
            for (i in 1 until order.size) {
                assertTrue("${name(page)} surface $i (${order[i]}) above ${order[i - 1]}", order[i] >= order[i - 1])
            }
            // Clearly above the card: what sits on a card (a chip, a track, a line, an outline).
            val card = on(page, R.color.card_bg)
            assertTrue(name(page), Contrast.ratio(on(page, R.color.surface_alt), card) >= 1.12)
            assertTrue(name(page), Contrast.ratio(on(page, R.color.divider), card) >= 1.12)
            assertTrue(name(page), Contrast.ratio(on(page, R.color.outline), card) >= 1.4)
        }
    }

    @Test
    fun theStepsAreEvenFromBackgroundToBackground() {
        for (res in listOf(R.color.card_bg, R.color.surface_alt, R.color.outline)) {
            val steps = backgrounds.map { Contrast.ratio(on(it, res), it) }
            assertTrue("$steps", steps.max() - steps.min() < 0.03)
        }
    }

    @Test
    fun textStaysReadable() {
        for (page in backgrounds) {
            val roles = Palette.darkRoles(page, greyDark)
            val grey = roles.getValue(R.color.text_secondary)
            for (res in listOf(R.color.page_bg, R.color.card_bg, R.color.sheet_bg, R.color.surface_alt, R.color.tile_bg, R.color.home_search_bg)) {
                val bg = roles.getValue(res)
                assertTrue("text on ${name(bg)} (page ${name(page)})", Contrast.ratio(textDark, bg) >= Contrast.TEXT)
                assertTrue("grey text on ${name(bg)} (page ${name(page)})", Contrast.ratio(grey, bg) >= Contrast.TEXT)
            }
        }
    }

    @Test
    fun aboveStepsJustFarEnough() {
        val page = 0xFF20252A.toInt()
        val up = Contrast.above(page, 1.5)
        assertTrue(Contrast.ratio(up, page) >= 1.5)
        // The next colour down in lightness (a step less of white) doesn't reach it.
        assertTrue(Contrast.ratio(Contrast.mix(up, 0xFF000000.toInt(), 0.02), page) < 1.5)
    }

    @Test
    fun theStockGreyIsDarkRolesOfItsSeed() {
        val xml = File("src/main/res/values-night/colors.xml").readText()
        fun stock(name: String): Int {
            val hex = Regex("<color name=\"$name\">#([0-9A-Fa-f]{8})</color>").find(xml)?.groupValues?.get(1) ?: throw AssertionError("$name isn't in colors.xml")
            return hex.toLong(16).toInt()
        }
        val roles = Palette.darkRoles(0xFF15171C.toInt(), greyDark)
        for ((res, name) in listOf(
            R.color.page_bg to "page_bg", R.color.home_bg to "home_bg", R.color.settings_bg to "settings_bg", R.color.switcher_bg to "switcher_bg",
            R.color.toolbar_bg to "toolbar_bg", R.color.card_bg to "card_bg", R.color.sheet_bg to "sheet_bg", R.color.tab_card_bg to "tab_card_bg",
            R.color.surface_alt to "surface_alt", R.color.home_search_bg to "home_search_bg", R.color.tile_bg to "tile_bg", R.color.ptr_bg to "ptr_bg",
            R.color.tab_thumb_bg to "tab_thumb_bg", R.color.skeleton to "skeleton", R.color.divider to "divider", R.color.outline to "outline",
            R.color.text_secondary to "text_secondary",
        )) {
            assertEquals("$name in values-night/colors.xml", "%08X".format(roles.getValue(res)), "%08X".format(stock(name)))
        }
    }
}
