package com.swiftbrowser.engine

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.swiftbrowser.SwiftApp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data used per day (2.6): what Swift sent and received, on mobile data and otherwise, for the last
 * 60 days. Android counts the app's bytes; Swift reads the count every minute while it runs and when
 * the network changes, and adds what was used since to the network it was on.
 */
object DataUsage {
    private const val PREF = "data_usage"
    private const val KEEP_DAYS = 60
    private const val EVERY_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val uid = Process.myUid()

    /** "2026-10-29" -> bytes on mobile data, bytes on other networks. */
    private val days = HashMap<String, LongArray>()
    private var last = -1L
    private var lastMobile = false
    private var dirty = false

    class Day(val day: String, val mobile: Long, val other: Long)

    fun init() {
        load()
        lastMobile = DataSaver.onMobileData
        last = total()
        DataSaver.networkListeners += {
            sample()
            lastMobile = DataSaver.onMobileData
        }
        main.postDelayed(tick, EVERY_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            sample()
            save()
            main.postDelayed(this, EVERY_MS)
        }
    }

    private fun total(): Long {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        return if (rx < 0 || tx < 0) -1 else rx + tx
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Adds what was used since the last look. */
    fun sample() {
        val now = total()
        if (now < 0) return
        if (last in 0..now && now > last) {
            days.getOrPut(today()) { LongArray(2) }[if (lastMobile) 0 else 1] += now - last
            dirty = true
        }
        last = now
    }

    /** Written when Swift goes to the background, and every minute while it's in use. */
    fun save() {
        if (!dirty) return
        dirty = false
        val keep = days.keys.sortedDescending().take(KEEP_DAYS).toSet()
        days.keys.retainAll(keep)
        val o = JSONObject()
        for ((d, v) in days) o.put(d, org.json.JSONArray().put(v[0]).put(v[1]))
        SwiftApp.prefs.sp.edit().putString(PREF, o.toString()).apply()
    }

    private fun load() {
        val o = try {
            JSONObject(SwiftApp.prefs.sp.getString(PREF, null) ?: return)
        } catch (_: Exception) {
            return
        }
        for (d in o.keys()) {
            val a = o.optJSONArray(d) ?: continue
            days[d] = longArrayOf(a.optLong(0), a.optLong(1))
        }
    }

    /** Newest first. */
    fun recent(): List<Day> {
        sample()
        return days.entries.sortedByDescending { it.key }.map { Day(it.key, it.value[0], it.value[1]) }
    }

    fun mobileToday(): Long {
        sample()
        return days[today()]?.get(0) ?: 0
    }

    fun mobileThisMonth(): Long {
        sample()
        val month = today().take(7)
        return days.filterKeys { it.startsWith(month) }.values.sumOf { it[0] }
    }

    fun reset() {
        days.clear()
        dirty = true
        save()
    }
}
