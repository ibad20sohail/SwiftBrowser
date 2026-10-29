package com.swiftbrowser.core

import android.content.Context
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.docs.DocThumbs
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import org.mozilla.geckoview.StorageController

/**
 * Clearing browsing data (2.6): what to remove, for a time — the last hour, day, week, four weeks,
 * or all of it. The engine clears site data by site, not by time, so "the last hour" clears the
 * cookies and caches of the sites visited in that hour.
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

    /** What is ticked when the dialog opens. */
    val DEFAULTS = setOf(What.HISTORY, What.CACHE)

    private val io = Background.single("clearing")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Clears [what] of [profileId] for [range]; [done] runs on the main thread. */
    fun clear(ctx: Context, profileId: Long, range: Range, what: Set<What>, done: () -> Unit) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val since = if (range == Range.ALL) 0L else now - range.ms
        io.execute {
            val db = SwiftApp.db
            // The sites of that time (read before history goes), for their cookies, caches and icons.
            val hosts = if (since > 0) db.historyHostsSince(profileId, since) else emptySet()
            // Whole sites (example.com with www. and the rest): cookies are often set for all of it.
            val sites = hosts.map { UrlUtils.siteOfHost(it) }.toSet()
            val storage = Engine.runtime.storageController
            // What the engine is asked to clear, waited for at the end (Quit closes Swift right after).
            val asked = ArrayList<org.mozilla.geckoview.GeckoResult<Void>>()
            if (What.COOKIES in what) {
                if (since == 0L) {
                    // The engine gives no answer for this one: a moment for it before going on.
                    storage.clearDataForSessionContext(Profiles.contextId(profileId))
                    Thread.sleep(800)
                } else {
                    for (s in sites) asked += storage.clearDataFromBaseDomain(s, StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.SITE_DATA)
                }
            }
            if (What.CACHE in what) {
                if (since == 0L) {
                    asked += storage.clearData(StorageController.ClearFlags.ALL_CACHES)
                } else {
                    for (s in sites) asked += storage.clearDataFromBaseDomain(s, StorageController.ClearFlags.ALL_CACHES)
                }
                DocThumbs.clear(app)
            }
            if (What.HISTORY in what) {
                if (since == 0L) {
                    db.clearHistory(profileId)
                    db.clearClosedTabs(profileId)
                    Favicons.clear()
                    Diagnostics.clearLogs()
                } else {
                    db.deleteHistorySince(profileId, since)
                    db.deleteClosedTabsSince(profileId, since)
                    Favicons.clearSince(since, hosts)
                }
                main.post { Helper.clearArticles() }
                TopSites.historyChanged++
            }
            if (What.DOWNLOADS in what) main.post { Downloads.removeEntriesSince(since) }
            if (What.DOCUMENTS in what) {
                db.deleteDocumentsSince(profileId, since)
                Docs.clearCache(app)
                DocThumbs.clear(app)
            }
            if (What.PLACES in what) db.clearPositionsSince(profileId, since)
            if (What.SITE_SETTINGS in what) {
                main.post {
                    val sites = SiteSettings.sites() + com.swiftbrowser.adblock.AdBlock.overrides().keys
                    val chosen = if (since == 0L) sites.toSet() else sites.filter { s -> hosts.any { it == s || it.endsWith(".$s") } }.toSet()
                    chosen.forEach { SiteSettings.reset(it) }
                }
                for (s in (if (since == 0L) emptySet() else sites)) asked += storage.clearDataFromBaseDomain(s, StorageController.ClearFlags.PERMISSIONS)
                if (since == 0L) asked += storage.clearData(StorageController.ClearFlags.PERMISSIONS)
            }
            if (What.PASSWORDS in what) Passwords.clearSince(profileId, since)
            for (r in asked) {
                try {
                    r.poll(15_000)
                } catch (e: Throwable) {
                    L.w("Clearing took too long or failed", e)
                }
            }
            main.post(done)
        }
    }
}
