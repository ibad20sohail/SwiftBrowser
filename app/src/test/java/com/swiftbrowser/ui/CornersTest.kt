package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0.5: corners that nest (Corners.nested), and the one change of page (Motion and res/anim). */
class CornersTest {
    private val sizes = setOf(Ui.R_XSMALL, Ui.R_SMALL, Ui.R_MEDIUM, Ui.R_LARGE)

    @Test
    fun anInnerCornerIsTheOuterCornerLessTheGap() {
        // A tab card's picture and a Files tile's: 16 - 4.
        assertEquals(12, Corners.nested(16, 4))
        // A card inside a sheet.
        assertEquals(16, Corners.nested(24, 8))
        // Nothing between them: the same corner.
        assertEquals(24, Corners.nested(24, 0))
    }

    @Test
    fun betweenTwoSizesItIsTheNearestOneAndTheSmallerOnATie() {
        // 16 - 5 = 11: the nearest of the four sizes is 12.
        assertEquals(12, Corners.nested(16, 5))
        // 16 - 2 = 14, as far from 12 as from 16: the smaller keeps inside the outer corner.
        assertEquals(12, Corners.nested(16, 2))
        // 16 - 7 = 9: a group card's small pictures.
        assertEquals(8, Corners.nested(16, 7))
    }

    @Test
    fun eightIsTheSmallest() {
        // A tab row's picture: 12 - 6 = 6 would be a size the app doesn't have.
        assertEquals(Ui.R_XSMALL, Corners.nested(12, 6))
        assertEquals(Ui.R_XSMALL, Corners.nested(12, 40))
    }

    @Test
    fun everyAnswerIsOneOfTheFourSizes() {
        for (outer in 0..40) for (gap in 0..40) {
            assertTrue(sizes.contains(Corners.nested(outer, gap)))
        }
    }

    /** The app's resources, from where the unit tests happen to run. */
    private fun anim(name: String): String =
        File(listOf("src/main/res", "app/src/main/res").first { File(it).isDirectory }, "anim/$name.xml").readText()

    @Test
    fun windowAnimationsTakeAsLongAsEveryOtherPageChange() {
        for (name in listOf("swift_open_enter", "swift_close_exit", "swift_hold")) {
            assertTrue(name, anim(name).contains("android:duration=\"${Motion.PAGE_MS}\""))
        }
    }

    @Test
    fun theWindowsDistanceIsThePageDistanceOnAPhone() {
        // 8% of a 360 dp wide phone.
        assertEquals(Motion.PAGE_SLIDE_DP.toFloat(), 360 * 0.08f, 1f)
        assertTrue(anim("swift_open_enter").contains("android:fromXDelta=\"8%\""))
        assertTrue(anim("swift_close_exit").contains("android:toXDelta=\"8%\""))
    }
}
