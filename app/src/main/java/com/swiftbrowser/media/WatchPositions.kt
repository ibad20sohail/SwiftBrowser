package com.swiftbrowser.media

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.json.JSONObject
import kotlin.math.abs

/**
 * Continue watching: where long videos (films, episodes) were left off, per page, so the next
 * time the page plays the same video Swift offers to go back there. Movie sites often start over
 * after a reload, a crash or reopening the tab. Not in incognito tabs.
 */
object WatchPositions {
    /** Positions before this are just the start: not worth offering, and not saved over an older one. */
    private const val MIN_POSITION = 30.0

    /** Watched to within this of the end counts as finished. */
    private const val END_MARGIN = 60.0
    private const val MIN_LENGTH = 120.0

    private val main = Handler(Looper.getMainLooper())

    private fun pageKey(url: String) = url.substringBefore('#')

    /** A content script reported where a long video is (every few seconds while it plays). */
    fun onPosition(tab: Tab, msg: JSONObject) {
        if (!SwiftApp.prefs.continueWatching || tab.incognito) return
        val t = msg.optDouble("t", -1.0)
        val d = msg.optDouble("d", -1.0)
        if (d < MIN_LENGTH || t < 0 || t.isNaN() || d.isNaN()) return
        val page = pageKey(tab.url)
        val finished = msg.optBoolean("ended") || t > d - END_MARGIN
        if (!finished && t < MIN_POSITION) return
        // Offered to continue but watching from the start again: the place reached last time is
        // kept until this viewing gets past it (then the offer goes).
        if (tab.resumeOffered == page && (finished || t >= tab.resumePosition - 5)) main.post { TabManager.ui?.hideResumeChip(tab) }
        if (!finished && tab.resumeOffered == page && t < tab.resumePosition) return
        TabManager.io.execute {
            if (finished) SwiftApp.db.deleteVideoPosition(page) else SwiftApp.db.setVideoPosition(page, t, d)
        }
    }

    /** A long video started playing: offer to continue it if it was left off earlier. */
    fun onStart(tab: Tab, msg: JSONObject) {
        if (!SwiftApp.prefs.continueWatching || tab.incognito) return
        val t = msg.optDouble("t", 0.0)
        val d = msg.optDouble("d", -1.0)
        if (d < MIN_LENGTH || d.isNaN()) return
        val page = pageKey(tab.url)
        if (tab.resumeOffered == page) return
        TabManager.io.execute {
            val (position, length) = SwiftApp.db.videoPosition(page) ?: return@execute
            main.post {
                // Not the same video, at its start or end anyway, or the site already continued it.
                if (abs(length - d) > 5 || position < MIN_POSITION || position > d - END_MARGIN || t > position - 10) return@post
                if (TabManager.current !== tab || pageKey(tab.url) != page) return@post
                tab.resumeOffered = page
                tab.resumePosition = position
                TabManager.ui?.showResumeChip(tab, format(position)) { Helper.resume(tab, position, d) }
            }
        }
    }

    /** 41:12 or 1:05:09. */
    fun format(seconds: Double): String {
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
