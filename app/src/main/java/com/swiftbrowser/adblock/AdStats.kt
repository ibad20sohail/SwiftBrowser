package com.swiftbrowser.adblock

import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.swiftbrowser.SwiftApp
import java.util.TimeZone

/**
 * How many ads and trackers uBlock Origin blocked each day of the last week (2.3). Only counts,
 * never which sites. Fed by the count uBlock Origin shows for each tab (SessionFactory).
 */
object AdStats {
    private const val KEY = "ad_stats"
    const val DAYS = 7
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val SAVE_DELAY_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var counts: MutableMap<Long, Int>? = null
    private val save = Runnable { write() }

    /** A tab's count went from [before] to [after] (a new page starts again from 0). */
    fun onBadge(before: String?, after: String?) {
        val now = parse(after)
        val was = parse(before)
        val added = if (now >= was) now - was else now
        if (added <= 0) return
        val map = load()
        val day = today()
        map[day] = (map[day] ?: 0) + added
        handler.removeCallbacks(save)
        handler.postDelayed(save, SAVE_DELAY_MS)
    }

    /** The last [DAYS] days, oldest first (today last). */
    fun perDay(): IntArray {
        val map = load()
        val day = today()
        return IntArray(DAYS) { map[day - (DAYS - 1 - it)] ?: 0 }
    }

    fun week(): Int = perDay().sum()

    /** Days since 1970 in the phone's time zone. */
    fun today(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / DAY_MS
    }

    /** uBlock Origin shortens big counts ("1.2k", "12k"). */
    private fun parse(badge: String?): Int {
        val s = badge?.trim()?.trimEnd('+').orEmpty()
        if (s.isEmpty()) return 0
        val times = when (s.last()) {
            'k', 'K' -> 1_000
            'M', 'm' -> 1_000_000
            else -> 1
        }
        val n = (if (times == 1) s else s.dropLast(1)).toFloatOrNull() ?: return 0
        return (n * times).toInt()
    }

    /**
     * 3.0: a small file of its own ([KEY], the same name) rather than the settings, which were all
     * written again with every save.
     */
    private val sp: android.content.SharedPreferences?
        get() = SwiftApp.instance?.getSharedPreferences(KEY, android.content.Context.MODE_PRIVATE)

    private const val DAYS_KEY = "days"

    private fun load(): MutableMap<Long, Int> {
        counts?.let { return it }
        val map = HashMap<Long, Int>()
        val own = sp
        // 3.0: the counts move out of the settings once (or again after an old backup put them back).
        val settings = SwiftApp.prefs.sp
        if (own != null) settings.getString(KEY, null)?.let { old ->
            if (!own.contains(DAYS_KEY)) own.edit { putString(DAYS_KEY, old) }
            settings.edit { remove(KEY) }
        }
        own?.getString(DAYS_KEY, null)?.split(',')?.forEach { entry ->
            val day = entry.substringBefore(':').toLongOrNull() ?: return@forEach
            val n = entry.substringAfter(':').toIntOrNull() ?: return@forEach
            map[day] = n
        }
        counts = map
        return map
    }

    private fun write() {
        val map = load()
        val oldest = today() - (DAYS - 1)
        map.keys.removeAll { it < oldest }
        sp?.edit { putString(DAYS_KEY, map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }) }
    }
}
