package com.swiftbrowser.files

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.ui.PrivateLock

/**
 * The Files screen's options (2.9), in one place for Swift's Settings: each kept in Swift's
 * preferences under a key that stays the same from version to version. Files reads them each time
 * it shows, so a change in Settings applies the next time Files is on screen.
 */
object FilesSettings {
    const val KEY_HIDDEN = "files_hidden"
    const val KEY_START_AT = "files_start_at"
    const val KEY_GRID = "files_grid"
    const val KEY_SORT = "files_sort"
    const val KEY_CATEGORY_SORT = "files_category_sort"
    const val KEY_THUMBNAILS = "files_thumbnails"
    const val KEY_RECYCLE_BIN = "files_recycle_bin"
    const val KEY_BIN_DAYS = "files_bin_days"
    const val KEY_WIFI_PORT = "files_wifi_port"
    const val KEY_TWO_PANES = "files_two_panes"

    /** Files opens on its start page… */
    const val START_HOME = "home"

    /** …or where it was left (the default, as before 2.9). */
    const val START_LAST = "last"

    /** How long the recycle bin keeps things, in days (the choices offered). */
    val BIN_DAY_CHOICES = listOf(1, 3, 7, 30, 90)

    /** Private opens with the phone's screen lock, with Swift's own PIN, or isn't set up yet. */
    const val LOCK_SCREEN = "screen"
    const val LOCK_PIN = "pin"
    const val LOCK_NONE = "none"

    private val sp get() = SwiftApp.prefs.sp

    /** Hidden files (names starting with a dot) show in folders. */
    var showHidden: Boolean
        get() = sp.getBoolean(KEY_HIDDEN, false)
        set(v) = sp.edit().putBoolean(KEY_HIDDEN, v).apply()

    /** [START_HOME] or [START_LAST]. */
    var startAt: String
        get() = sp.getString(KEY_START_AT, START_LAST).takeIf { it == START_HOME } ?: START_LAST
        set(v) = sp.edit().putString(KEY_START_AT, if (v == START_HOME) START_HOME else START_LAST).apply()

    /**
     * 3.0.4: two folders side by side when the screen is turned sideways and wide enough (TwoPanes);
     * held upright, one folder always shows. Turned on and off with a button at the top of Files.
     */
    var twoPanes: Boolean
        get() = sp.getBoolean(KEY_TWO_PANES, false)
        set(v) = sp.edit().putBoolean(KEY_TWO_PANES, v).apply()

    /** Folders and categories as tiles instead of rows. */
    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, false)
        set(v) = sp.edit().putBoolean(KEY_GRID, v).apply()

    /** How folders are sorted: an index into [sortLabels] (FileOps.SORT_NAME…). */
    var sort: Int
        get() = sp.getInt(KEY_SORT, FileOps.SORT_NAME).coerceIn(0, FileOps.sortLabels.size - 1)
        set(v) = sp.edit().putInt(KEY_SORT, v.coerceIn(0, FileOps.sortLabels.size - 1)).apply()

    /** How categories (Images, Documents…) are sorted; newest first unless chosen otherwise. */
    var categorySort: Int
        get() = sp.getInt(KEY_CATEGORY_SORT, FileOps.SORT_DATE).coerceIn(0, FileOps.sortLabels.size - 1)
        set(v) = sp.edit().putInt(KEY_CATEGORY_SORT, v.coerceIn(0, FileOps.sortLabels.size - 1)).apply()

    val sortLabels: List<String> get() = FileOps.sortLabels

    /** Small pictures of photos, videos and apps in the lists (off: the kinds' icons only, lighter on a slow phone). */
    var thumbnails: Boolean
        get() = sp.getBoolean(KEY_THUMBNAILS, true)
        set(v) = sp.edit().putBoolean(KEY_THUMBNAILS, v).apply()

    /** Deleting moves things to the recycle bin (off: Files asks, then deletes for good). */
    var useRecycleBin: Boolean
        get() = sp.getBoolean(KEY_RECYCLE_BIN, true)
        set(v) = sp.edit().putBoolean(KEY_RECYCLE_BIN, v).apply()

    /**
     * Days the recycle bin keeps things before they go for good: 1, 3 (3.0.9), 7, 30 (the default) or 90. 3.0:
     * changing it deletes nothing by itself: [setBinDays] does, once Settings has asked (a shorter
     * time used to delete the older things for good at once, without a word).
     */
    var binDays: Int
        get() = sp.getInt(KEY_BIN_DAYS, 30).let { d -> if (d in BIN_DAY_CHOICES) d else 30 }
        set(v) = sp.edit().putInt(KEY_BIN_DAYS, if (v in BIN_DAY_CHOICES) v else 30).apply()

    /** 3.0: things are kept [days] days from now on; what has been in the bin longer goes for good now. */
    fun setBinDays(ctx: Context, days: Int) {
        binDays = days
        RecycleBin.purgeSoon(ctx, force = true)
    }

    /** 3.0: what keeping things only [days] days would delete for good now ([done] on the main thread). */
    fun binDue(ctx: Context, days: Int, done: (RecycleBin.Summary) -> Unit) = RecycleBin.dueSummary(ctx, days, done)

    /** What the recycle bin holds now ([done] on the main thread). */
    fun binSummary(ctx: Context, done: (RecycleBin.Summary) -> Unit) = RecycleBin.summary(ctx, done)

    /** Deletes everything in the recycle bin for good; [done] on the main thread with how many went. */
    fun emptyBin(ctx: Context, done: (Int) -> Unit) = RecycleBin.empty(ctx, done)

    /**
     * How Private opens now: [LOCK_SCREEN], [LOCK_PIN] or [LOCK_NONE] (not set up: no screen lock and
     * no PIN). 3.0: a PIN, once chosen, keeps Private even after the phone gets a screen lock.
     */
    fun privateLockKind(ctx: Context): String = when {
        PrivateFolder.hasPin() -> LOCK_PIN
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardSecure == true -> LOCK_SCREEN
        else -> LOCK_NONE
    }

    /** 3.0: how long before a forgotten PIN can be reset (0: now), or null when no reset was asked for. */
    fun privateResetLeft(): Long? = PrivateFolder.resetLeft()

    /** Sets or changes Private's PIN (asking for the old one first); with a screen lock and no PIN, says that it is used. */
    fun changePin(activity: Activity) = PrivateLock.changePin(activity)

    /** The port Wi-Fi transfer tries first (the next free one when it is taken). */
    var wifiPort: Int
        get() = sp.getInt(KEY_WIFI_PORT, 8080).takeIf { it in 1024..65000 } ?: 8080
        set(v) = sp.edit().putInt(KEY_WIFI_PORT, v.coerceIn(1024, 65000)).apply()
}
