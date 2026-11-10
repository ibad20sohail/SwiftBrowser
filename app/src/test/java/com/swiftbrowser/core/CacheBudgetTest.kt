package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheBudgetTest {
    @Test
    fun theEngineGetsTheLimitLessSwiftsOwnFiles() {
        assertEquals(90 * 1024, CacheBudget.engineKb(100))
        assertEquals(40 * 1024, CacheBudget.engineKb(50))
        assertEquals(190 * 1024, CacheBudget.engineKb(200))
        // An odd saved value: the default.
        assertEquals(90 * 1024, CacheBudget.engineKb(7))
    }

    @Test
    fun noSingleFileTakesItAll() {
        assertEquals(10 * 1024, CacheBudget.maxFileKb(100))
        assertEquals(8 * 1024, CacheBudget.maxFileKb(50))
    }

    @Test
    fun theOldestGoFirstAndFilesInUseStay() {
        val mb = 1024L * 1024
        val files = listOf(
            Triple("old", 6 * mb, 100L),
            Triple("middle", 4 * mb, 200L),
            Triple("new", 5 * mb, 900L),
        )
        // 15 MB in a 10 MB budget: the oldest (6 MB) is enough.
        assertEquals(listOf("old"), CacheBudget.toDelete(files, 10 * mb, keepAfter = 500L))
        // 15 MB in 4 MB: both old ones go, the one in use stays.
        assertEquals(listOf("old", "middle"), CacheBudget.toDelete(files, 4 * mb, keepAfter = 500L))
        // Within the budget: nothing.
        assertTrue(CacheBudget.toDelete(files, 20 * mb, keepAfter = 0L).isEmpty())
    }
}
