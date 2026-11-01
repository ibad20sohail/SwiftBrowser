package com.swiftbrowser.core

import android.content.Context
import android.media.MediaScannerConnection
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.docs.DocThumbs
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import org.mozilla.geckoview.StorageController
import java.io.File
import java.util.Calendar

/**
 * Clearing browsing data (2.6): what to remove, for a time — the last hour, day, week, four weeks,
 * or all of it; 2.8: or days chosen on a calendar. The engine clears site data by site, not by
 * time, so "the last hour" clears the cookies and caches of the sites visited in that hour.
 */
object Clearing {
    enum class What(val label: String, val hint: String) {
        HISTORY("History", "Pages you visited, recently closed tabs and reader view articles"),
        COOKIES("Cookies and site data", "Signs you out of those sites"),
        CACHE("Saved pictures and files", "Pages load them again when needed"),
        DOWNLOADS("Downloads list", "The files themselves stay"),
        DOCUMENTS("Documents list", "Opened in Swift; the files themselves stay"),
        PLACES("Where you left off", "Videos, articles and documents in Continue"),
        SITE_SETTINGS("Site settings", "Desktop version, text size, location, ad blocking"),
        PASSWORDS("Saved passwords", "Sign-ins Swift fills in for you"),
    }

    enum class Range(val label: String, val ms: Long) {
        HOUR("Last hour", 3_600_000L),
        DAY("Last 24 hours", 24 * 3_600_000L),
        WEEK("Last 7 days", 7 * 24 * 3_600_000L),
        MONTH("Last 4 weeks", 28 * 24 * 3_600_000L),
        ALL("All time", 0L),
    }

    /**
     * 2.8: the time to clear, from [from] to [to] (milliseconds, both included). All time is 0 to
     * Long.MAX_VALUE. The last hour and the like run on past now, so what comes in while clearing
     * goes too; chosen days run from 00:00 of the first to the end of the last, local time.
     */
    data class Span(val from: Long, val to: Long) {
        val isAll: Boolean get() = from <= 0L && to == Long.MAX_VALUE

        companion object {
            val ALL = Span(0L, Long.MAX_VALUE)

            fun of(range: Range, now: Long = System.currentTimeMillis()): Span =
                if (range == Range.ALL) ALL else Span(now - range.ms, Long.MAX_VALUE)

            /** Whole days, [first] to [last] (as [dayOf] gives them). */
            fun days(first: Int, last: Int): Span = Span(startOfDay(first), startOfDay(nextDay(last)) - 1)
        }
    }

    /** 2.8: a day as a number, 20261012 for 12 October 2026 (local time): easy to keep and compare. */
    fun dayOf(ms: Long): Int = Calendar.getInstance().run {
        timeInMillis = ms
        get(Calendar.YEAR) * 10000 + (get(Calendar.MONTH) + 1) * 100 + get(Calendar.DAY_OF_MONTH)
    }

    /** 00:00 of [day], local time. */
    fun startOfDay(day: Int): Long = calendarOf(day).timeInMillis

    fun nextDay(day: Int): Int = calendarOf(day).run {
        add(Calendar.DAY_OF_MONTH, 1)
        dayOf(timeInMillis)
    }

    /** A calendar at 00:00 of [day]. */
    fun calendarOf(day: Int): Calendar = Calendar.getInstance().apply {
        clear()
        set(day / 10000, day / 100 % 100 - 1, day % 100)
    }

    /** What is ticked when the dialog opens. */
    val DEFAULTS = setOf(What.HISTORY, What.CACHE)

    private val io = Background.single("clearing")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Clears [what] of [profileId] for [range]; [done] runs on the main thread. */
    fun clear(ctx: Context, profileId: Long, range: Range, what: Set<What>, done: () -> Unit) =
        clear(ctx, profileId, Span.of(range), what, emptySet(), done)

    /**
     * Clears [what] of [profileId] for [span] (2.8); [files]: the downloads (by id) whose files are
     * deleted too, from those the downloads list loses. [done] runs on the main thread.
     */
    fun clear(ctx: Context, profileId: Long, span: Span, what: Set<What>, files: Set<Long>, done: () -> Unit) {
        val app = ctx.applicationContext
        val all = span.isAll
        val since = span.from
        val until = span.to
        io.execute {
            val db = SwiftApp.db
            // The sites of that time (read before history goes), for their cookies, caches and icons.
            val hosts = if (!all) db.historyHostsSince(profileId, since, until) else emptySet()
            // Whole sites (example.com with www. and the rest): cookies are often set for all of it.
            val sites = hosts.map { UrlUtils.siteOfHost(it) }.toSet()
            val storage = Engine.runtime.storageController
            // What the engine is asked to clear, waited for at the end (Quit closes Swift right after).
            val asked = ArrayList<org.mozilla.geckoview.GeckoResult<Void>>()
            if (What.COOKIES in what) {
                if (all) {
                    // The engine gives no answer for this one: a moment for it before going on.
                    storage.clearDataForSessionContext(Profiles.contextId(profileId))
                    Thread.sleep(800)
                } else {
                    for (s in sites) asked += storage.clearDataFromBaseDomain(s, StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA)
                }
            }
            if (What.CACHE in what) {
                if (all) {
                    asked += storage.clearData(StorageController.ClearFlags.ALL_CACHES)
                } else {
                    for (s in sites) asked += storage.clearDataFromBaseDomain(s, StorageController.ClearFlags.ALL_CACHES)
                }
                DocThumbs.clear(app)
            }
            if (What.HISTORY in what) {
                if (all) {
                    db.clearHistory(profileId)
                    db.clearClosedTabs(profileId)
                    Favicons.clear()
                    Diagnostics.clearLogs()
                } else {
                    db.deleteHistorySince(profileId, since, until)
                    db.deleteClosedTabsSince(profileId, since, until)
                    Favicons.clearSince(since, hosts, until)
                }
                // 2.8: for a time, only the reader view articles made then.
                main.post { if (all) Helper.clearArticles() else Helper.clearArticles(since, until) }
                TopSites.historyChanged++
            }
            if (What.DOWNLOADS in what) {
                // 2.8: the files the user agreed to delete go first, here off the main thread; then the entries.
                if (files.isNotEmpty()) deleteFiles(app, files, span)
                main.post { Downloads.removeEntriesSince(since, until) }
            }
            if (What.DOCUMENTS in what) {
                db.deleteDocumentsSince(profileId, since, until)
                Docs.clearCache(app)
                DocThumbs.clear(app)
            }
            if (What.PLACES in what) db.clearPositionsSince(profileId, since, until)
            if (What.SITE_SETTINGS in what) {
                main.post {
                    val withSettings = SiteSettings.sites() + com.swiftbrowser.adblock.AdBlock.overrides().keys
                    val chosen = if (all) withSettings.toSet() else withSettings.filter { s -> hosts.any { it == s || it.endsWith(".$s") } }.toSet()
                    chosen.forEach { SiteSettings.reset(it) }
                }
                for (s in (if (all) emptySet() else sites)) asked += storage.clearDataFromBaseDomain(s, StorageController.ClearFlags.PERMISSIONS)
                if (all) asked += storage.clearData(StorageController.ClearFlags.PERMISSIONS)
            }
            if (What.PASSWORDS in what) Passwords.clearSince(profileId, since, until)
            for (r in asked) {
                try {
                    r.poll(15_000)
                } catch (e: Throwable) {
                    L.w("Clearing took too long or failed", e)
                }
            }
            L.d("Cleared ${what.joinToString { it.name.lowercase() }} (${if (all) "all time" else "${sites.size} sites"})")
            main.post(done)
        }
    }

    /**
     * 2.8: deletes the files of the downloads [ids] (finished, started in [span]: only what Swift
     * downloaded then, as the user agreed to), and tells Android they are gone, so galleries and
     * other apps stop listing them.
     */
    private fun deleteFiles(app: Context, ids: Set<Long>, span: Span) {
        val gone = ArrayList<String>()
        for (id in ids) {
            val item = Downloads.get(id) ?: continue
            if (item.status != Downloads.DONE || item.created !in span.from..span.to) continue
            val f = File(item.filePath)
            if (f.isFile && f.delete()) gone += f.path
        }
        if (gone.isNotEmpty()) MediaScannerConnection.scanFile(app, gone.toTypedArray(), null, null)
        L.d("Clearing: ${gone.size} of ${ids.size} downloaded files deleted")
    }
}
