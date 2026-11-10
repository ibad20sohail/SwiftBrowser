package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLevelTest {
    @Test
    fun nobodysSitesChangeLookWithTheUpdate() {
        // The old Lite mode on: Medium, whatever "Show sites as designed" said.
        assertEquals(LiteLevel.MEDIUM, LiteLevel.migrated(liteOn = true, asDesigned = true, autoAsDesigned = true))
        // "Show sites as designed" on (chosen, or by itself on a fast phone): Off.
        assertEquals(LiteLevel.OFF, LiteLevel.migrated(liteOn = false, asDesigned = true, autoAsDesigned = false))
        assertEquals(LiteLevel.OFF, LiteLevel.migrated(liteOn = false, asDesigned = null, autoAsDesigned = true))
        // Both off (the Moto E4 with Lite mode off): Low, what Swift did then.
        assertEquals(LiteLevel.LOW, LiteLevel.migrated(liteOn = false, asDesigned = false, autoAsDesigned = true))
        assertEquals(LiteLevel.LOW, LiteLevel.migrated(liteOn = false, asDesigned = null, autoAsDesigned = false))
    }

    @Test
    fun aSitesOwnLevelWinsAndBatterySavingIsAtLeastMedium() {
        assertEquals(LiteLevel.HIGH, LiteLevel.effective(LiteLevel.LOW, LiteLevel.HIGH, saving = false))
        assertEquals(LiteLevel.OFF, LiteLevel.effective(LiteLevel.EXTREME, LiteLevel.OFF, saving = false))
        assertEquals(LiteLevel.LOW, LiteLevel.effective(LiteLevel.LOW, null, saving = false))
        assertEquals(LiteLevel.MEDIUM, LiteLevel.effective(LiteLevel.LOW, LiteLevel.OFF, saving = true))
        assertEquals(LiteLevel.EXTREME, LiteLevel.effective(LiteLevel.EXTREME, null, saving = true))
    }

    @Test
    fun keysAreKept() {
        for (l in LiteLevel.entries) assertEquals(l, LiteLevel.fromKey(l.key))
        assertNull(LiteLevel.fromKey("on"))
        assertNull(LiteLevel.fromKey(null))
    }

    @Test
    fun eachStyleReachesTheSitesAtItsLevel() {
        val sites = mapOf("a.com" to LiteLevel.OFF, "b.com" to LiteLevel.HIGH, "c.com" to LiteLevel.MEDIUM)
        // All sites at Medium: "lite" everywhere but a.com; "litehigh" only on b.com.
        val lite = LiteReach.of(LiteLevel.MEDIUM, LiteLevel.MEDIUM, sites)
        assertTrue(lite.on)
        assertEquals(listOf("a.com"), lite.exclude)
        assertNull(lite.only)
        val high = LiteReach.of(LiteLevel.HIGH, LiteLevel.MEDIUM, sites)
        assertTrue(high.on)
        assertEquals(listOf("b.com"), high.only)
        // All sites Off, no site above it: nowhere.
        val none = LiteReach.of(LiteLevel.LOW, LiteLevel.OFF, mapOf("a.com" to LiteLevel.OFF))
        assertFalse(none.on)
    }
}
