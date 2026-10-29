package com.swiftbrowser.media

import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.json.JSONObject

/**
 * The home page's "Continue" row (2.3): long videos (WatchPositions) and reader view articles left
 * half-way, newest first. Only pages in the profile's history show, so profiles keep apart and
 * clearing history clears the row too. Nothing from incognito tabs is kept.
 */
object Unfinished {
    /** [image]: a picture for the card (the video's poster, the article's first picture), if any. */
    class Item(
        val url: String, val title: String, val progress: Float, val caption: String, val video: Boolean, val updated: Long, val image: String?,
        /** A document left part-way (2.4), opened again in Swift's viewer. */
        val doc: com.swiftbrowser.docs.Docs.Doc? = null,
    )

    private const val MAX = 8
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** The reader view page says how far the article was read ([done]: to its end). */
    fun onReaderPlace(tab: Tab, msg: JSONObject) {
        if (tab.incognito || !SwiftApp.prefs.homeContinue) return
        val url = msg.optString("url").substringBefore('#')
        if (!UrlUtils.isHttp(url)) return
        val share = msg.optDouble("share", 0.0)
        val done = msg.optBoolean("done") || share >= 0.97
        val title = msg.optString("title").ifBlank { null }
        val image = msg.optString("img").takeIf { it.startsWith("http") && it.length < 2000 }
        TabManager.io.execute {
            if (done) SwiftApp.db.deleteReadingPosition(url) else if (share >= 0.05) SwiftApp.db.setReadingPosition(url, title, share, image)
        }
    }

    /** What the row shows (on [TabManager.io]). */
    fun load(profileId: Long): List<Item> {
        if (!SwiftApp.prefs.homeContinue) return emptyList()
        val since = System.currentTimeMillis() - MAX_AGE_MS
        val db = SwiftApp.db
        val videos = db.unfinishedVideos(profileId, since, MAX).map { r ->
            val position = r[2] as Double
            val length = r[3] as Double
            val left = ((length - position) / 60).toInt().coerceAtLeast(1)
            val page = r[0] as String
            Item(page, (r[1] as String?).orEmpty(), (position / length).toFloat(), "$left min left", true, r[4] as Long, youTubePicture(page) ?: r[5] as String?)
        }
        val articles = db.unfinishedArticles(profileId, since, MAX).map { r ->
            val share = (r[2] as Double).toFloat()
            Item(r[0] as String, (r[1] as String?).orEmpty(), share, "${(share * 100).toInt()}% read", false, r[3] as Long, r[4] as String?)
        }
        val docs = db.unfinishedDocuments(profileId, since, MAX).map { d ->
            val caption = if (d.kind == com.swiftbrowser.docs.Docs.Kind.PDF && d.total > 0) "Page ${d.place} of ${d.total}" else "${(d.progress * 100).toInt()}% read"
            Item(d.uri, d.name, d.progress, caption, false, d.updated, null, d)
        }
        return (videos + articles + docs).filter { it.url !in hidden }.sortedByDescending { it.updated }.take(MAX)
    }

    private val YOUTUBE_ID = Regex("^https?://(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})")

    /** YouTube's own small picture of a video (its pages change videos without reloading, so their preview picture can be another video's). */
    fun youTubePicture(url: String): String? =
        YOUTUBE_ID.find(url)?.groupValues?.get(1)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

    /** Taken off the row, waiting for their Undo to run out (2.6). */
    private val hidden = java.util.Collections.synchronizedSet(HashSet<String>())

    fun hide(item: Item) {
        hidden += item.url
    }

    fun unhide(item: Item) {
        hidden -= item.url
    }

    /** Takes [item] off the row. */
    fun remove(item: Item) {
        hidden -= item.url
        TabManager.io.execute {
            when {
                item.doc != null -> SwiftApp.db.setDocumentPlace(item.doc.id, "", 0f, 0)
                item.video -> SwiftApp.db.deleteVideoPosition(item.url)
                else -> SwiftApp.db.deleteReadingPosition(item.url)
            }
        }
    }
}
