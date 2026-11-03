package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RightNowTest {
    @Test
    fun processesAreShownAsThePartsPeopleKnow() {
        val parts = RightNow.byPart(
            listOf(
                "com.swiftbrowser.app" to 120L,
                "com.swiftbrowser.app:tab0" to 180L,
                "com.swiftbrowser.app:tab1" to 30L,
                "com.swiftbrowser.app:gpu" to 40L,
                "com.swiftbrowser.app:crashhelper" to 5L,
                "com.swiftbrowser.app:crashreport" to 0L,
            ),
        )
        assertEquals(listOf("pages" to 210L, "the app" to 120L, "graphics" to 40L, "other" to 5L), parts)
    }

    @Test
    fun onlyLastWeeksCrashesCount() {
        val now = 100L * 24 * 3600 * 1000
        val day = 24L * 3600 * 1000
        assertEquals(2, RightNow.crashesSince(listOf(now - day, now - 6 * day, now - 8 * day), now))
        assertEquals(0, RightNow.crashesSince(emptyList(), now))
    }

    @Test
    fun theDetailsSayWhenAndWhyTabsWereUnloaded() {
        assertEquals(
            "Last unloaded: 2 tabs, 5 min ago, because Swift was out of sight for a minute.",
            RightNow.unloadedText(2, 5 * 60_000L + 10_000, "Swift was out of sight for a minute"),
        )
        assertEquals("Last unloaded: 1 tab, just now, because the phone's memory was low.", RightNow.unloadedText(1, 20_000, "the phone's memory was low"))
        assertEquals("Last unloaded: 3 tabs, 2 h 5 min ago, because more than 2 tabs were loaded.", RightNow.unloadedText(3, 125 * 60_000L, "more than 2 tabs were loaded"))
    }

    @Test
    fun theRowSaysMemoryAndTabs() {
        assertEquals("412 MB free of 1862 MB · 3 of 9 tabs loaded", RightNow.summary(412, 1862, 3, 9))
        assertEquals("412 MB free of 1862 MB · 1 of 1 tab loaded", RightNow.summary(412, 1862, 1, 1))
    }

    @Test
    fun detailsSayWhatIsKnownAndNotAvailableOtherwise() {
        val text = RightNow.details(
            RightNow.Snapshot(
                freeMb = 300, totalMb = 1862, low = true, parts = emptyList(), tabs = 7, loaded = 2, kept = 1,
                limitedBy = "Battery saver", crashesThisWeek = 1, lastRun = null,
            ),
        )
        assertEquals(
            "Phone memory: 300 MB free of 1862 MB, low.\n" +
                "Swift's own memory: not available.\n" +
                "Tabs: 2 of 7 loaded, 1 kept loaded.\n" +
                "Battery saver: only the open tab and tabs kept loaded stay loaded.\n" +
                "1 page engine crash in the last 7 days.",
            text,
        )
        val parts = RightNow.details(
            RightNow.Snapshot(
                freeMb = 800, totalMb = 1862, low = false, parts = listOf("pages" to 200L, "the app" to 100L), tabs = 3,
                loaded = 3, kept = 0, limitedBy = null, crashesThisWeek = 0, lastRun = "Last time, Android closed Swift.",
            ),
        )
        assertTrue(parts.contains("Swift uses 300 MB: pages 200 MB, the app 100 MB."))
        assertTrue(parts.contains("No page engine crashes in the last 7 days."))
        assertTrue(parts.endsWith("Last time, Android closed Swift."))
    }
}
