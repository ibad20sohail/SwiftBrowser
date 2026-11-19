package com.swiftbrowser.core

import com.swiftbrowser.core.MemoryGuard.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryGuardTest {
    private val mb = 1024L * 1024L

    /** The Moto E4's line: Android starts closing apps below 144 MB. */
    private val line = 144 * mb

    @Test
    fun plentyOfMemoryDoesNothing() {
        assertEquals(Action.NONE, MemoryGuard.decide(419 * mb, line, false, 10_000, MemoryGuard.NEVER))
        assertEquals(Action.NONE, MemoryGuard.decide(288 * mb, line, false, 10_000, MemoryGuard.NEVER))
    }

    @Test
    fun underTwiceTheLineOtherTabsGo() {
        assertEquals(Action.UNLOAD, MemoryGuard.decide(287 * mb, line, false, 10_000, MemoryGuard.NEVER))
        assertEquals(Action.UNLOAD, MemoryGuard.decide(217 * mb, line, false, 10_000, MemoryGuard.NEVER))
    }

    @Test
    fun underOneAndAHalfTimesTheLineCleansUpAtOnce() {
        assertEquals(Action.RELIEVE, MemoryGuard.decide(215 * mb, line, false, 0, MemoryGuard.NEVER))
        assertEquals(Action.RELIEVE, MemoryGuard.decide(91 * mb, line, false, 5_000, MemoryGuard.NEVER))
    }

    @Test
    fun androidSayingLowCountsAsCritical() {
        assertEquals(Action.RELIEVE, MemoryGuard.decide(400 * mb, line, true, 5_000, MemoryGuard.NEVER))
    }

    @Test
    fun cleanUpAtMostEveryFifteenSeconds() {
        assertEquals(Action.UNLOAD, MemoryGuard.decide(100 * mb, line, false, 20_000, 10_000))
        assertEquals(Action.RELIEVE, MemoryGuard.decide(100 * mb, line, false, 25_000, 10_000))
    }

    @Test
    fun unknownNumbersDoNothing() {
        assertEquals(Action.NONE, MemoryGuard.decide(0, line, false, 0, MemoryGuard.NEVER))
        assertEquals(Action.NONE, MemoryGuard.decide(100 * mb, 0, true, 0, MemoryGuard.NEVER))
    }
}
