package com.swiftbrowser.ui

import android.content.Context
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 3.0.5: the one short date of Swift's lists (Downloads, Files, the recycle bin, the cleaner and the
 * network folders; before, each said it its own way, "4 Nov" here and "Nov 5" there): the time for
 * today, "5 Nov" for this year, "5 Nov 2025" for older years. Long dates (History's day headings, a
 * file's details) stay as they are.
 *
 * One per screen: its formatters and calendars are reused, so a long list doesn't make new ones for
 * every row while it scrolls (a slow phone).
 */
class ShortDates internal constructor(private val timeOfDay: DateFormat, locale: Locale) {
    constructor(ctx: Context) : this(android.text.format.DateFormat.getTimeFormat(ctx), Locale.getDefault())

    private val dayAndMonth = SimpleDateFormat("d MMM", locale)
    private val dayMonthAndYear = SimpleDateFormat("d MMM yyyy", locale)
    private val nowCalendar = Calendar.getInstance()
    private val thenCalendar = Calendar.getInstance()

    /** [time] as a list shows it, or null when it isn't known. */
    fun text(time: Long, now: Long = System.currentTimeMillis()): String? {
        if (time <= 0) return null
        nowCalendar.timeInMillis = now
        thenCalendar.timeInMillis = time
        val sameYear = nowCalendar.get(Calendar.YEAR) == thenCalendar.get(Calendar.YEAR)
        val format = when {
            !sameYear -> dayMonthAndYear
            nowCalendar.get(Calendar.DAY_OF_YEAR) == thenCalendar.get(Calendar.DAY_OF_YEAR) -> timeOfDay
            else -> dayAndMonth
        }
        return format.format(Date(time))
    }
}
