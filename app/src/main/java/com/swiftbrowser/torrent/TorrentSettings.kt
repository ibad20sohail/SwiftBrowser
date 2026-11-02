package com.swiftbrowser.torrent

import androidx.core.content.edit
import com.swiftbrowser.SwiftApp

/**
 * 2.9.2: the torrent options (Settings → Video & downloads → Torrents), kept with Swift's other
 * settings. A change is applied to running torrents at once ([Torrents.applySettings]).
 */
object TorrentSettings {
    private const val AT_ONCE = "torrent_at_once"
    private const val DOWN_LIMIT = "torrent_down_kbps"
    private const val UP_LIMIT = "torrent_up_kbps"
    private const val WIFI_ONLY = "torrent_wifi_only"
    private const val BATTERY_PAUSE = "torrent_battery_pause"
    private const val EXTRA_TRACKERS = "torrent_extra_trackers"
    private const val CHARGING_ONLY = "torrent_charging_only"
    private const val NIGHT_ONLY = "torrent_night_only"
    private const val ENCRYPTED_ONLY = "torrent_encrypted_only"

    /** "At night only": from 1 am to 7 am, when some data plans are cheaper. */
    const val NIGHT_FROM = 1
    const val NIGHT_TO = 7

    val AT_ONCE_CHOICES = intArrayOf(1, 2, 3)

    /** Speed limits in KB/s; 0 is no limit. */
    val DOWN_CHOICES = intArrayOf(0, 256, 512, 1024, 2048, 5120)
    val UP_CHOICES = intArrayOf(0, 20, 50, 100, 250, 500)

    private val sp get() = SwiftApp.prefs.sp

    /** How many torrents download at the same time; the others wait their turn. */
    var atOnce: Int
        get() = sp.getInt(AT_ONCE, 2).coerceIn(1, 3)
        set(v) = sp.edit { putInt(AT_ONCE, v.coerceIn(1, 3)) }

    var downLimitKb: Int
        get() = sp.getInt(DOWN_LIMIT, 0).coerceAtLeast(0)
        set(v) = sp.edit { putInt(DOWN_LIMIT, v.coerceAtLeast(0)) }

    var upLimitKb: Int
        get() = sp.getInt(UP_LIMIT, 0).coerceAtLeast(0)
        set(v) = sp.edit { putInt(UP_LIMIT, v.coerceAtLeast(0)) }

    /** On mobile data torrents wait for Wi-Fi (on at first: they are big). */
    var wifiOnly: Boolean
        get() = sp.getBoolean(WIFI_ONLY, true)
        set(v) = sp.edit { putBoolean(WIFI_ONLY, v) }

    /** Below 15% battery, unless charging, torrents pause (they go on at 20% or when charging). */
    var batteryPause: Boolean
        get() = sp.getBoolean(BATTERY_PAUSE, true)
        set(v) = sp.edit { putBoolean(BATTERY_PAUSE, v) }

    /**
     * Well-known public trackers are added to every torrent, so ones with few sharers start sooner.
     * 3.0: never to private torrents (from private trackers, which forbid it).
     */
    var extraTrackers: Boolean
        get() = sp.getBoolean(EXTRA_TRACKERS, true)
        set(v) = sp.edit { putBoolean(EXTRA_TRACKERS, v) }

    /**
     * 3.0: off ("Prefer encrypted connections"): encrypted with those that can, plain with the others.
     * On ("Encrypted only"): plain connections are refused, so fewer people can share with the phone.
     */
    var encryptedOnly: Boolean
        get() = sp.getBoolean(ENCRYPTED_ONLY, false)
        set(v) = sp.edit { putBoolean(ENCRYPTED_ONLY, v) }

    val ENCRYPTION_CHOICES = listOf("Prefer encrypted connections", "Encrypted only (fewer people to download from)")

    /** Torrents only run while the phone charges (it stays cool, the battery full). */
    var chargingOnly: Boolean
        get() = sp.getBoolean(CHARGING_ONLY, false)
        set(v) = sp.edit { putBoolean(CHARGING_ONLY, v) }

    /** Torrents only run at night ([NIGHT_FROM] to [NIGHT_TO]). */
    var nightOnly: Boolean
        get() = sp.getBoolean(NIGHT_ONLY, false)
        set(v) = sp.edit { putBoolean(NIGHT_ONLY, v) }

    fun isNight(): Boolean = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in NIGHT_FROM until NIGHT_TO

    /** 3.0: when the next night starts (1 am, wall clock), for the alarm that starts waiting torrents. */
    fun nextNight(now: Long): Long {
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, NIGHT_FROM)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 5)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun speedLabel(kb: Int): String = when {
        kb <= 0 -> "No limit"
        kb >= 1024 -> "${kb / 1024} MB/s"
        else -> "$kb KB/s"
    }
}
