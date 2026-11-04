package com.swiftbrowser.media

import android.content.Context
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.json.JSONObject
import java.io.File

/**
 * The home page's "Continue" row (2.3): long videos (WatchPositions) and reader view articles left
 * half-way, newest first. Only pages in the profile's history show, so profiles keep apart and
 * clearing history clears the row too. Nothing from incognito tabs is kept.
 *
 * 3.0.3: web things in the browser, phone things in Files. The browser's row ([forBrowser]) has
 * website videos, articles and documents read straight from a link; Files' start page ([forFiles])
 * has the videos and songs on the phone left part-way in Swift's player. Documents kept on the phone
 * are in Files' "Recent documents".
 */
object Unfinished {
    /** [image]: a picture for the card (the video's poster, the article's first picture), if any. */
    class Item(
        val url: String, val title: String, val progress: Float, val caption: String, val video: Boolean, val updated: Long, val image: String?,
        /** A document left part-way (2.4), opened again in Swift's viewer. */
        val doc: com.swiftbrowser.docs.Docs.Doc? = null,
        /** A video or song on the phone left part-way in Swift's player (2.9). */
        val localPath: String? = null,
        /** 3.0.3: when that file last changed (its picture on the card is kept by it). */
        val fileTime: Long = 0,
    )

    private const val MAX = 8
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** 3.0.3: documents looked through for the browser's row (many may be kept on the phone, so Files'). */
    private const val DOCS_LOOKED_AT = 40

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

    /**
     * 3.0.3: the browser home's row (on [TabManager.io]): website videos, articles, and documents
     * read straight from a link without saving them (their copies in Swift's cache, Docs.isWebCopy).
     */
    fun forBrowser(profileId: Long): List<Item> {
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
        // 2.9: a document whose file was deleted leaves the row (and is forgotten).
        val docs = db.unfinishedDocuments(profileId, since, DOCS_LOOKED_AT).filter { d ->
            !Docs.isGone(d.uri).also { gone -> if (gone) db.forgetDocumentUri(d.uri) }
        }.filter { Docs.isWebCopy(it) }.map { d ->
            val caption = if (d.kind == Docs.Kind.PDF && d.total > 0) "Page ${d.place} of ${d.total}" else "${(d.progress * 100).toInt()}% read"
            Item(d.uri, d.name, d.progress, caption, false, d.updated, null, d)
        }
        return newest(videos + articles + docs)
    }

    /**
     * 3.0.3: Files' start page's row (off the main thread): the videos and songs on the phone left
     * part-way in Swift's player (2.9), files that still exist and aren't in Private.
     */
    fun forFiles(ctx: Context): List<Item> {
        if (!SwiftApp.prefs.homeContinue) return emptyList()
        val since = System.currentTimeMillis() - MAX_AGE_MS
        val local = LocalMedia.recent(ctx, MAX).filter { it.updated >= since && it.duration > 0 && !com.swiftbrowser.files.PrivateFolder.isInside(ctx, File(it.path)) }.map { m ->
            val left = ((m.duration - m.position) / 60_000).toInt().coerceAtLeast(1)
            Item(
                "file://" + m.path, m.title, (m.position.toFloat() / m.duration).coerceIn(0f, 1f), "$left min left", m.video, m.updated, null,
                localPath = m.path, fileTime = File(m.path).lastModified(),
            )
        }
        return newest(local)
    }

    /** 3.0.3: what a row shows of [items]: the newest first, not those waiting for their Undo ([hidden]), [MAX] at most. */
    internal fun newest(items: List<Item>, hidden: Set<String> = this.hidden): List<Item> =
        items.filter { it.url !in hidden }.sortedByDescending { it.updated }.take(MAX)

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

    /** 3.0.3: [item] was taken off its row and waits for its Undo to run out. */
    fun isHidden(item: Item): Boolean = item.url in hidden

    /** Takes [item] off the row. */
    fun remove(item: Item) {
        hidden -= item.url
        TabManager.io.execute {
            when {
                item.localPath != null -> SwiftApp.instance?.let { LocalMedia.forget(it, item.localPath) }
                item.doc != null -> SwiftApp.db.setDocumentPlace(item.doc.id, "", 0f, 0)
                item.video -> SwiftApp.db.deleteVideoPosition(item.url)
                else -> SwiftApp.db.deleteReadingPosition(item.url)
            }
        }
    }
}
