package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLinesTest {
    @Test
    fun aLinesTimeOfDay() {
        assertEquals(((13L * 60 + 5) * 60 + 9) * 1000 + 42, LogLines.timeOfDay("13:05:09.042 D Tab 3 loading example.com"))
        assertNull(LogLines.timeOfDay("    at a stack line"))
        assertNull(LogLines.timeOfDay(""))
    }

    @Test
    fun clearingATimeKeepsTheLinesAroundIt() {
        val day = 1_000_000_000L
        val lines = listOf(
            "09:00:00.000 D before",
            "10:00:00.000 D in the time",
            "the same message, next line",
            "10:30:00.000 W also in it",
            "11:00:00.001 D after",
        )
        val from = day + 10L * 3_600_000
        val to = day + 11L * 3_600_000
        assertEquals(listOf("09:00:00.000 D before", "11:00:00.001 D after"), LogLines.keepOutside(lines, day, from, to))
        // A first line without a time stays (nothing says when it was written).
        assertEquals(listOf("no time", "09:00:00.000 D before"), LogLines.keepOutside(listOf("no time", "09:00:00.000 D before"), day, from, to))
    }

    @Test
    fun aReportWithoutSitesSaysASite() {
        assertEquals("12:00:00.000 D Tab 3 loading a site", LogLines.withoutSites("12:00:00.000 D Tab 3 loading m.youtube.com"))
        assertEquals("Tab 3 loaded a site in 1.2 s", LogLines.withoutSites("Tab 3 loaded example.co.uk:8080 in 1.2 s"))
        assertEquals("Blocked pop-up to a site", LogLines.withoutSites("Blocked pop-up to ads.tracker-net.io"))
        assertEquals("engine Gecko: a site failed", LogLines.withoutSites("engine Gecko: https://www.example.com/watch?v=1 failed"))
        assertEquals("current site: a site", LogLines.withoutSites("current site: thepiratebay.org"))
    }

    @Test
    fun codeAndFilesStayInAReportWithoutSites() {
        val crash = "E Swift crashed on main: java.lang.IllegalStateException: no view"
        assertEquals(crash, LogLines.withoutSites(crash))
        val where = "memory access error (SIGSEGV) in libxul.so+0x1a2b3c"
        assertEquals(where, LogLines.withoutSites(where))
        val pkg = "at com.swiftbrowser.ui.MainActivity.onCreate and org.mozilla.geckoview.GeckoSession"
        assertEquals(pkg, LogLines.withoutSites(pkg))
        val numbers = "Swift 3.0 (22) started; uBlock Origin 1.75.0; 1.2 s"
        assertEquals(numbers, LogLines.withoutSites(numbers))
        assertEquals("Deleted Swift-2.9.2.apk", LogLines.withoutSites("Deleted Swift-2.9.2.apk"))
    }

    @Test
    fun aReportWithSitesKeepsOnlyTheirNames() {
        assertEquals("loaded https://www.example.com/… ok", LogLines.trimAddress("loaded https://www.example.com/watch?v=secret ok"))
        assertTrue(LogLines.trimAddress("Tab 3 loading example.com").contains("example.com"))
        assertFalse(LogLines.trimAddress("https://a.com/private/path").contains("private"))
    }

    private fun at(year: Int, month: Int, day: Int, h: Int, min: Int, s: Int = 0, ms: Int = 0): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, h, min, s)
            set(java.util.Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test
    fun androidLogTimes() {
        val now = at(2026, 11, 11, 3, 26)
        assertEquals(at(2026, 11, 10, 4, 26, 44, 525), LogLines.logcatTime("11-10 04:26:44.525 E/AndroidRuntime(10809): FATAL EXCEPTION: clearing", now))
        assertNull(LogLines.logcatTime("--------- beginning of crash", now))
        // In January, a December line is last year's.
        assertEquals(at(2026, 12, 31, 23, 59, 59), LogLines.logcatTime("12-31 23:59:59.000 E/Tag( 1): x", at(2027, 1, 1, 0, 5)))
    }

    @Test
    fun onlyTheLastRunsEndIsKept() {
        // From swift-report (2): the clear-data crash of 11-10 came back after every bad end on 11-11.
        val lines = listOf(
            "--------- beginning of crash",
            "11-10 04:26:44.525 E/AndroidRuntime(10809): FATAL EXCEPTION: clearing",
            "--------- beginning of main",
            "11-11 05:46:19.080 E/Adreno-GSL(21477): <gsl_memory_alloc_pure:2125>: GSL MEM ERROR: kgsl_sharedmem_alloc ioctl failed.",
            "11-11 05:46:19.086 E/OpenGLRenderer(21477): GL error:  Out of memory!",
            // The new run's own start, after it began: not about how the last one ended.
            "11-11 05:46:24.100 E/GeckoLibLoad(24001): Load sqlite start",
        )
        val lastRunStart = at(2026, 11, 11, 3, 26)
        val thisRunStart = at(2026, 11, 11, 5, 46, 24)
        val kept = LogLines.between(lines, lastRunStart, thisRunStart, at(2026, 11, 11, 5, 46, 25))
        assertEquals(lines.subList(3, 5), kept)
        assertTrue(LogLines.graphicsOutOfMemory(kept))
        assertFalse(LogLines.graphicsOutOfMemory(lines.take(3)))
    }
}
