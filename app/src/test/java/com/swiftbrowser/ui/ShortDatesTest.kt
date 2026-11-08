package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ShortDatesTest {
    private val dates = ShortDates(SimpleDateFormat("HH:mm", Locale.UK), Locale.UK)

    /** The time at [hour]:[minute] on that day, in the time zone of the machine running the test. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private val now = at(2026, Calendar.NOVEMBER, 5, 14, 30)

    @Test
    fun todayIsTheTime() {
        assertEquals("09:05", dates.text(at(2026, Calendar.NOVEMBER, 5, 9, 5), now))
        assertEquals("00:00", dates.text(at(2026, Calendar.NOVEMBER, 5, 0, 0), now))
    }

    @Test
    fun earlierThisYearIsTheDayAndMonth() {
        assertEquals("4 Nov", dates.text(at(2026, Calendar.NOVEMBER, 4, 23, 59), now))
        assertEquals("1 Jan", dates.text(at(2026, Calendar.JANUARY, 1), now))
    }

    @Test
    fun anotherYearHasTheYear() {
        assertEquals("31 Dec 2025", dates.text(at(2025, Calendar.DECEMBER, 31, 23, 59), now))
        assertEquals("5 Nov 2024", dates.text(at(2024, Calendar.NOVEMBER, 5), now))
    }

    @Test
    fun aClockSetAheadStillReadsAsADate() {
        assertEquals("6 Nov", dates.text(at(2026, Calendar.NOVEMBER, 6), now))
        assertEquals("1 Jan 2027", dates.text(at(2027, Calendar.JANUARY, 1), now))
    }

    @Test
    fun aTimeNotKnownIsNothing() {
        assertNull(dates.text(0, now))
        assertNull(dates.text(-1, now))
    }

    @Test
    fun theSameFormatterComesOutRightRowAfterRow() {
        // One object serves a whole list: each row is worked out afresh from what it is given.
        assertEquals("09:05", dates.text(at(2026, Calendar.NOVEMBER, 5, 9, 5), now))
        assertEquals("4 Nov", dates.text(at(2026, Calendar.NOVEMBER, 4), now))
        assertEquals("31 Dec 2025", dates.text(at(2025, Calendar.DECEMBER, 31), now))
        assertEquals("09:05", dates.text(at(2026, Calendar.NOVEMBER, 5, 9, 5), now))
    }
}
