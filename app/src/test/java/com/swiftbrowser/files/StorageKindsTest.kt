package com.swiftbrowser.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageKindsTest {
    private val gb = 1_000_000_000L

    @Test
    fun whatIsUsedAndNoneOfTheKindsIsOther() {
        val s = StorageKinds.split(32 * gb, 7 * gb, StorageKinds.Sizes(3 * gb, 8 * gb, gb, gb / 10))!!
        assertArrayEquals(longArrayOf(3 * gb, 8 * gb, gb, gb / 10, 25 * gb - 12 * gb - gb / 10, 7 * gb), s.parts())
        // Everything adds up to the storage's size.
        assertEquals(32 * gb, s.parts().sum())
    }

    @Test
    fun nothingKnownOfTheKindsIsAllOther() {
        val s = StorageKinds.split(16 * gb, 4 * gb, StorageKinds.Sizes(0, 0, 0, 0))!!
        assertArrayEquals(longArrayOf(0, 0, 0, 0, 12 * gb, 4 * gb), s.parts())
    }

    @Test
    fun anIndexCountingMoreThanIsUsedIsMadeToFit() {
        // Files gone since the index last looked: 12 GB counted, 10 GB used.
        val s = StorageKinds.split(16 * gb, 6 * gb, StorageKinds.Sizes(6 * gb, 3 * gb, 3 * gb, 0))!!
        assertEquals(0L, s.other)
        assertEquals(6 * gb, s.free)
        assertEquals(10 * gb, s.pictures + s.videos + s.music + s.documents)
        // In proportion: half, a quarter, a quarter.
        assertEquals(5 * gb, s.pictures)
        assertEquals(2_500_000_000L, s.videos)
        assertEquals(2_500_000_000L, s.music)
        // Sizes that don't divide evenly still add up exactly.
        val odd = StorageKinds.split(1001, 0, StorageKinds.Sizes(1000, 1000, 1000, 1))!!
        assertEquals(1001L, odd.pictures + odd.videos + odd.music + odd.documents + odd.other)
    }

    @Test
    fun aStorageOfUnknownSizeHasNoSplit() {
        assertNull(StorageKinds.split(-1, -1, StorageKinds.Sizes(1, 1, 1, 1)))
        assertNull(StorageKinds.split(0, 0, StorageKinds.Sizes(1, 1, 1, 1)))
        assertNull(StorageKinds.split(10 * gb, -1, StorageKinds.Sizes(1, 1, 1, 1)))
    }

    @Test
    fun oddNumbersAreKeptInBounds() {
        // More free than there is (a storage changing as it is asked), sizes below nothing.
        val s = StorageKinds.split(10 * gb, 12 * gb, StorageKinds.Sizes(-5, gb, 0, 0))!!
        assertEquals(10 * gb, s.free)
        assertEquals(0L, s.pictures)
        assertEquals(0L, s.other)
        assertEquals(10 * gb, s.parts().sum())
        // A full storage: everything used, nothing free.
        val full = StorageKinds.split(8 * gb, 0, StorageKinds.Sizes(gb, 0, 0, 0))!!
        assertEquals(7 * gb, full.other)
        assertEquals(0L, full.free)
    }

    @Test
    fun widthsAndGapsFillTheBar() {
        val w = StorageKinds.widths(longArrayOf(30, 20, 10, 0, 40, 100), 412, 4, 8)
        // Five parts show, with four gaps between them.
        assertEquals(0, w[3])
        assertEquals(412 - 4 * 4, w.sum())
        // In proportion to their sizes: 30, 20, 10, 40 and 100 of 200.
        assertEquals(59, w[0])
        assertEquals(40, w[1])
        assertEquals(20, w[2])
        assertEquals(79, w[4])
        assertEquals(198, w[5])
    }

    @Test
    fun aTinyPartStillShowsAsADot() {
        // 50 MB of music on a 32 GB phone: well under a pixel, shown as wide as the bar is high.
        val w = StorageKinds.widths(longArrayOf(3 * gb, 8 * gb, 50_000_000, gb, 13 * gb, 7 * gb), 300, 2, 8)
        assertEquals(8, w[2])
        assertTrue(w.all { it >= 8 })
        assertEquals(300 - 5 * 2, w.sum())
        // The others share what is left in proportion (the biggest stays the biggest).
        assertEquals(4, w.indices.maxByOrNull { w[it] })
    }

    @Test
    fun nothingToShowOrNoRoom() {
        assertArrayEquals(IntArray(3), StorageKinds.widths(longArrayOf(0, 0, 0), 300, 2, 8))
        assertArrayEquals(IntArray(2), StorageKinds.widths(longArrayOf(5, 5), 0, 2, 8))
        // One part fills the bar.
        assertArrayEquals(intArrayOf(0, 300), StorageKinds.widths(longArrayOf(0, 9), 300, 2, 8))
        // Too narrow for each at its smallest: the same width each.
        val w = StorageKinds.widths(longArrayOf(1, 1000, 1), 22, 2, 8)
        assertEquals(18, w.sum())
        assertTrue(w.all { it == 6 })
    }
}
