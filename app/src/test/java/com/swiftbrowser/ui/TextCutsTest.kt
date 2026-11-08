package com.swiftbrowser.ui

import com.swiftbrowser.ui.TextCuts.Cut.DISPLAY
import com.swiftbrowser.ui.TextCuts.Cut.EVERYDAY
import com.swiftbrowser.ui.TextCuts.Cut.SMALL
import org.junit.Assert.assertEquals
import org.junit.Test

class TextCutsTest {
    private fun cutOf(type: Ui.Type) = TextCuts.of(type.sp, type.weight)

    @Test
    fun theSmallestTextGetsTheOpenCut() {
        assertEquals(SMALL, cutOf(Ui.Type.CAPTION))
        assertEquals(SMALL, cutOf(Ui.Type.SMALL))
    }

    @Test
    fun bigTitlesGetTheTightCut() {
        assertEquals(DISPLAY, cutOf(Ui.Type.DISPLAY))
        assertEquals(DISPLAY, cutOf(Ui.Type.HEADLINE))
    }

    @Test
    fun everythingBetweenIsTheEverydayCut() {
        for (type in listOf(Ui.Type.TITLE, Ui.Type.HEADING, Ui.Type.SUBTITLE, Ui.Type.BODY, Ui.Type.BODY_SECONDARY, Ui.Type.LABEL, Ui.Type.SECTION)) {
            assertEquals(type.name, EVERYDAY, cutOf(type))
        }
    }

    @Test
    fun theTypeScaleSitsOnTheRightSideOfTheThresholds() {
        // Should the scale change, a new size must still land in a cut made for it.
        for (type in Ui.Type.entries) {
            when {
                type.sp <= TextCuts.SMALL_UP_TO -> assertEquals(type.name, SMALL, cutOf(type))
                type.sp >= TextCuts.DISPLAY_FROM -> assertEquals(type.name, DISPLAY, cutOf(type))
                else -> assertEquals(type.name, EVERYDAY, cutOf(type))
            }
        }
    }

    @Test
    fun smallBoldStaysEveryday() {
        // There is no small bold cut: the everyday bold, not a fake one.
        assertEquals(EVERYDAY, TextCuts.of(11f, Ui.Weight.BOLD))
        assertEquals(EVERYDAY, TextCuts.of(12.5f, Ui.Weight.BOLD))
    }

    @Test
    fun bigTextOtherThanBoldStaysEveryday() {
        // The tight cut is only made in bold.
        assertEquals(EVERYDAY, TextCuts.of(30f, Ui.Weight.REGULAR))
        assertEquals(EVERYDAY, TextCuts.of(23f, Ui.Weight.MEDIUM))
    }

    @Test
    fun thresholdsAreInclusive() {
        assertEquals(SMALL, TextCuts.of(12.5f, Ui.Weight.REGULAR))
        assertEquals(SMALL, TextCuts.of(12.5f, Ui.Weight.MEDIUM))
        assertEquals(EVERYDAY, TextCuts.of(12.6f, Ui.Weight.REGULAR))
        assertEquals(DISPLAY, TextCuts.of(23f, Ui.Weight.BOLD))
        assertEquals(EVERYDAY, TextCuts.of(22.9f, Ui.Weight.BOLD))
    }
}
