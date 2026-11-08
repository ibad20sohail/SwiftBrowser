package com.swiftbrowser.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RowCornersTest {
    /** Each item's corners in a list where [groups] gives each item's group (null: a heading). */
    private fun corners(vararg groups: Any?): List<Int> = groups.indices.map { i -> RowCorners.at(i, groups.size, { groups[it] }) }

    private fun gaps(vararg groups: Any?): List<Int> = groups.indices.map { i -> RowCorners.gapAt(i) { groups[it] } }

    @Test
    fun aGroupsFirstAndLastRowsGetTheLargeCorners() {
        assertEquals(RowCorners.SINGLE, RowCorners.of(first = true, last = true))
        assertEquals(RowCorners.FIRST, RowCorners.of(first = true, last = false))
        assertEquals(RowCorners.MIDDLE, RowCorners.of(first = false, last = false))
        assertEquals(RowCorners.LAST, RowCorners.of(first = false, last = true))
    }

    @Test
    fun rowsOfAGroupGoTogether() {
        assertEquals(listOf(RowCorners.SINGLE), corners(0))
        assertEquals(listOf(RowCorners.FIRST, RowCorners.LAST), corners(0, 0))
        assertEquals(listOf(RowCorners.FIRST, RowCorners.MIDDLE, RowCorners.MIDDLE, RowCorners.LAST), corners(0, 0, 0, 0))
    }

    @Test
    fun aHeadingEndsAGroup() {
        // History: Today, two pages, Yesterday, one page, a date, three pages.
        val shown = corners(null, 0, 0, null, 0, null, 0, 0, 0)
        assertEquals(RowCorners.FIRST, shown[1])
        assertEquals(RowCorners.LAST, shown[2])
        assertEquals(RowCorners.SINGLE, shown[4])
        assertEquals(listOf(RowCorners.FIRST, RowCorners.MIDDLE, RowCorners.LAST), shown.subList(6, 9))
    }

    @Test
    fun anotherGroupStartsAfresh() {
        // Bookmarks: two folders, then three bookmarks.
        assertEquals(
            listOf(RowCorners.FIRST, RowCorners.LAST, RowCorners.FIRST, RowCorners.MIDDLE, RowCorners.LAST),
            corners(true, true, false, false, false),
        )
        // One folder and one bookmark: each alone.
        assertEquals(listOf(RowCorners.SINGLE, RowCorners.SINGLE), corners(true, false))
    }

    @Test
    fun aRowRemovedLeavesItsNeighboursTheRightCorners() {
        // The middle one goes: the two left are the first and the last.
        assertEquals(listOf(RowCorners.FIRST, RowCorners.LAST), corners(0, 0))
        // The last one under a heading goes: the one before is the group's last now.
        assertEquals(listOf(RowCorners.FIRST, RowCorners.LAST), corners(null, 0, 0, null).subList(1, 3))
        // A day's only page goes (the day goes with it): the next day's pages are untouched.
        assertEquals(listOf(RowCorners.SINGLE), corners(null, 0).subList(1, 2))
    }

    @Test
    fun theGapAboveARow() {
        assertEquals(
            listOf(RowCorners.GAP_NONE, RowCorners.GAP_ROW, RowCorners.GAP_ROW),
            gaps(0, 0, 0),
        )
        // Under a heading no gap; a group straight after another a wider one.
        assertEquals(
            listOf(RowCorners.GAP_NONE, RowCorners.GAP_NONE, RowCorners.GAP_ROW, RowCorners.GAP_NONE, RowCorners.GAP_NONE),
            gaps(null, 0, 0, null, 0),
        )
        assertEquals(
            listOf(RowCorners.GAP_NONE, RowCorners.GAP_ROW, RowCorners.GAP_GROUP, RowCorners.GAP_ROW),
            gaps(true, true, false, false),
        )
    }

    @Test
    fun rowsAreOneOfTwoHeightsAtLeast() {
        assertEquals(Ui.ROW_ONE_LINE, RowRhythm.minHeight(twoLines = false))
        assertEquals(Ui.ROW_TWO_LINES, RowRhythm.minHeight(twoLines = true))
        // A row with a second line is taller, or the second line would be squeezed.
        assertEquals(true, RowRhythm.minHeight(twoLines = true) > RowRhythm.minHeight(twoLines = false))
    }

    @Test
    fun theRadiiOfEachKind() {
        val l = 24f
        val s = 8f
        assertArrayEquals(floatArrayOf(l, l, l, l, l, l, l, l), RowCorners.radii(RowCorners.SINGLE, l, s), 0f)
        // Top-left, top-right, bottom-right, bottom-left.
        assertArrayEquals(floatArrayOf(l, l, l, l, s, s, s, s), RowCorners.radii(RowCorners.FIRST, l, s), 0f)
        assertArrayEquals(floatArrayOf(s, s, s, s, s, s, s, s), RowCorners.radii(RowCorners.MIDDLE, l, s), 0f)
        assertArrayEquals(floatArrayOf(s, s, s, s, l, l, l, l), RowCorners.radii(RowCorners.LAST, l, s), 0f)
    }
}
