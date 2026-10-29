package com.swiftbrowser.news

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Net
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** One story: its headline, address, the site it is from, when, and a picture's address if any. */
data class Story(
    val title: String,
    val url: String,
    val source: String,
    val sourceUrl: String?,
    val time: Long,
    val image: String?,
)

/**
 * The home page's news (2.0), in English, from Google News' public feeds for the chosen country and
 * topic, and from news sites the user added (any RSS or Atom feed). Stories are kept on the phone,
 * so the home page shows them at once; they are fetched again at most once an hour, only while
 * the home page is open. Nothing is personalised and nothing about browsing is sent anywhere.
 */
object News {
    class Topic(val key: String, val label: String, val googleSection: String?)

    val topics = listOf(
        Topic("top", "Top stories", null),
        Topic("world", "World", "WORLD"),
        Topic("nation", "Your country", "NATION"),
        Topic("business", "Business", "BUSINESS"),
        Topic("tech", "Tech", "TECHNOLOGY"),
        Topic("sports", "Sports", "SPORTS"),
        Topic("entertainment", "Entertainment", "ENTERTAINMENT"),
        Topic("science", "Science", "SCIENCE"),
        Topic("health", "Health", "HEALTH"),
    )

    /** Stories from the user's own news sites, as one more topic. */
    const val MY_SITES = "mine"

    /** Countries with an English Google News edition: code to name. */
    val countries = linkedMapOf(
        "US" to "United States", "GB" to "United Kingdom", "IN" to "India", "PK" to "Pakistan",
        "BD" to "Bangladesh", "AU" to "Australia", "CA" to "Canada", "IE" to "Ireland", "NZ" to "New Zealand",
        "ZA" to "South Africa", "NG" to "Nigeria", "KE" to "Kenya", "GH" to "Ghana", "SG" to "Singapore",
        "MY" to "Malaysia", "PH" to "Philippines", "AE" to "United Arab Emirates", "SA" to "Saudi Arabia",
    )

    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val MAX_STORIES = 60
    private const val MAX_FEED_BYTES = 3_000_000
    private const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5"

    private val io = com.swiftbrowser.core.Background.single("news")
    private val main = Handler(Looper.getMainLooper())
    private val memory = HashMap<String, Pair<Long, List<Story>>>()
    private val loading = HashSet<String>()
    private val waiting = HashMap<String, ArrayList<(List<Story>?) -> Unit>>()

    /** The country of the news: chosen in Settings, or the phone's region when it has an English edition. */
    fun country(): String {
        val chosen = SwiftApp.prefs.newsCountry
        if (chosen in countries) return chosen
        val region = Locale.getDefault().country.uppercase(Locale.ROOT)
        return if (region in countries) region else "US"
    }

    fun countryName(): String = countries[country()] ?: "United States"

    /** The topics shown as chips: those chosen in Settings, and "Your sites" when there are any. */
    fun shownTopics(): List<Topic> {
        val keys = SwiftApp.prefs.newsTopics
        val list = topics.filter { it.key in keys || it.key == "top" }
        return if (SwiftApp.prefs.newsFeeds.isNotEmpty()) list + Topic(MY_SITES, "Your sites", null) else list
    }

    private fun googleUrl(t: Topic): String {
        val c = country()
        val tail = "hl=en-$c&gl=$c&ceid=$c:en"
        return if (t.googleSection == null) "https://news.google.com/rss?$tail"
        else "https://news.google.com/rss/headlines/section/topic/${t.googleSection}?$tail"
    }

    private fun cacheKey(topic: String) = if (topic == MY_SITES) "mine" else "${country()}-$topic"

    private fun file(ctx: Context, key: String) = File(File(ctx.filesDir, "news").apply { mkdirs() }, "$key.json")

    /** Stories kept for [topic] (memory or file), without the hidden sources; null when there are none yet. */
    fun cached(ctx: Context, topic: String): List<Story>? {
        val key = cacheKey(topic)
        memory[key]?.let { return visible(it.second) }
        val f = file(ctx, key)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val stories = fromJson(o.getJSONArray("stories"))
            memory[key] = o.getLong("time") to stories
            visible(stories)
        } catch (_: Exception) {
            null
        }
    }

    /** Whether [topic]'s stories are over an hour old (or missing). */
    fun stale(topic: String): Boolean {
        val t = memory[cacheKey(topic)]?.first ?: return true
        return System.currentTimeMillis() - t > REFRESH_MS
    }

    /**
     * Fetches [topic]'s stories in the background; [done] gets them on the main thread, or null
     * when they couldn't be loaded (no connection).
     */
    fun refresh(ctx: Context, topic: String, done: (List<Story>?) -> Unit) {
        val key = cacheKey(topic)
        // Asked again while loading (2.6): told with the first caller, not forgotten (a pull-down
        // spinner kept turning).
        waiting.getOrPut(key) { ArrayList() } += done
        if (!loading.add(key)) return
        val app = ctx.applicationContext
        val t = topics.firstOrNull { it.key == topic }
        val feeds = SwiftApp.prefs.newsFeeds
        io.execute {
            val stories: List<Story>? = try {
                if (topic == MY_SITES) {
                    val all = feeds.flatMap { url -> runCatching { fetch(url) }.getOrElse { emptyList() } }
                    all.sortedByDescending { it.time }.distinctBy { it.url }.take(MAX_STORIES).takeIf { it.isNotEmpty() || feeds.isEmpty() }
                } else {
                    fetch(googleUrl(t ?: topics[0])).take(MAX_STORIES)
                }
            } catch (e: Exception) {
                L.d("News: ${e.message}")
                null
            }
            if (stories != null) {
                try {
                    file(app, key).writeText(JSONObject().put("time", System.currentTimeMillis()).put("stories", toJson(stories)).toString())
                } catch (_: Exception) {
                }
            }
            main.post {
                loading.remove(key)
                if (stories != null) memory[key] = System.currentTimeMillis() to stories
                val shown = stories?.let { visible(it) }
                waiting.remove(key)?.forEach { it(shown) }
            }
        }
    }

    /**
     * The news feed of what the user typed (a site, or a feed's own address), or null; blocking.
     * A site's page names its feeds (link rel="alternate"); the usual addresses are tried after.
     */
    fun findFeed(input: String): String? {
        val text = input.trim()
        val base = if (UrlUtils.isHttp(text)) text else "https://$text"
        val first = Net.get(base, MAX_FEED_BYTES, ACCEPT) ?: return null
        if (isFeed(first)) return first.url
        val tried = HashSet<String>().apply { add(base); add(first.url) }
        fun tryUrl(url: String): String? {
            if (!tried.add(url)) return null
            return Net.get(url, MAX_FEED_BYTES, ACCEPT)?.takeIf { isFeed(it) }?.url
        }
        for (href in alternates(first)) tryUrl(href)?.let { return it }
        val root = first.url.substringBefore('?').substringBefore('#').trimEnd('/')
        val origin = root.substringBefore("://") + "://" + (UrlUtils.host(root) ?: return null)
        for (url in listOf("$root/feed", "$root/rss", "$root/rss.xml", "$root/feed.xml", "$root/index.xml", "$origin/feed", "$origin/rss", "$origin/rss.xml", "$origin/feeds/posts/default")) {
            tryUrl(url)?.let { return it }
        }
        return null
    }

    private fun isFeed(r: Net.Result): Boolean {
        val head = String(r.bytes, 0, minOf(r.bytes.size, 600), Charsets.UTF_8).trimStart().lowercase(Locale.ROOT)
        if (!head.startsWith("<?xml") && !head.contains("<rss") && !head.contains("<feed")) return false
        return try {
            parse(java.io.ByteArrayInputStream(r.bytes), r.url).first.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** The feeds a page names in its head (link rel="alternate" type="application/rss+xml"). */
    private fun alternates(page: Net.Result): List<String> {
        val html = String(page.bytes, Charsets.UTF_8)
        return linkTag.findAll(html).map { it.value }
            .filter { tag ->
                attr(tag, "rel")?.contains("alternate", ignoreCase = true) == true &&
                    (attr(tag, "type") ?: "").let { it.contains("rss", true) || it.contains("atom", true) }
            }
            .mapNotNull { tag -> attr(tag, "href") }
            .mapNotNull { href ->
                try {
                    URL(URL(page.url), android.text.Html.fromHtml(href, 0).toString()).toString()
                } catch (_: Exception) {
                    null
                }
            }
            .take(4)
            .toList()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    /** Hides a site's stories from now on (holding a story → Hide stories from …). */
    fun hideSource(source: String) {
        SwiftApp.prefs.newsHidden = SwiftApp.prefs.newsHidden + source
    }

    fun clearMemory() = memory.clear()

    private fun visible(list: List<Story>): List<Story> {
        val hidden = SwiftApp.prefs.newsHidden
        return if (hidden.isEmpty()) list else list.filter { it.source !in hidden }
    }

    /** Fetched through the engine (Net): Android 7's own HTTPS can't reach many news sites. */
    private fun fetch(url: String): List<Story> {
        val r = Net.get(url, MAX_FEED_BYTES, ACCEPT) ?: throw java.io.IOException("No answer from $url")
        return parse(java.io.ByteArrayInputStream(r.bytes), r.url).first
    }

    // ------------------------------------------------------------------ RSS and Atom

    private val rfc822 = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm zzz",
    )
    private val iso = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val text = s.trim()
        for (f in rfc822 + iso) {
            try {
                val fmt = SimpleDateFormat(f, Locale.US)
                if (f.endsWith("'Z'")) fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return fmt.parse(text)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0
    }

    private val imgInHtml = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** The stories of an RSS or Atom feed, and the feed's own title. */
    private fun parse(input: InputStream, feedUrl: String): Pair<List<Story>, String?> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val out = ArrayList<Story>()
        var feedTitle: String? = null
        var inItem = false
        var title: String? = null
        var link: String? = null
        var source: String? = null
        var sourceUrl: String? = null
        var time: String? = null
        var image: String? = null
        var description: String? = null
        val feedSite = UrlUtils.siteName(feedUrl)
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < MAX_STORIES) {
            val name = p.name?.lowercase(Locale.ROOT)
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> {
                        inItem = true
                        title = null; link = null; source = null; sourceUrl = null; time = null; image = null; description = null
                    }
                    !inItem && name == "title" && feedTitle == null -> feedTitle = text(p)
                    inItem && name == "title" -> title = text(p)
                    inItem && name == "link" -> {
                        val href = p.getAttributeValue(null, "href")
                        val rel = p.getAttributeValue(null, "rel")
                        if (href != null) {
                            if (rel == null || rel == "alternate") link = href
                        } else {
                            link = text(p)
                        }
                    }
                    inItem && name == "source" -> {
                        sourceUrl = p.getAttributeValue(null, "url")
                        source = text(p)
                    }
                    inItem && (name == "pubdate" || name == "published" || name == "updated" || name == "dc:date") ->
                        if (time == null) time = text(p)
                    inItem && (name == "media:content" || name == "media:thumbnail" || name == "enclosure") -> {
                        val type = p.getAttributeValue(null, "type") ?: ""
                        val url = p.getAttributeValue(null, "url")
                        if (image == null && url != null && (type.isEmpty() || type.startsWith("image"))) image = url
                    }
                    inItem && (name == "description" || name == "summary" || name == "content:encoded" || name == "content") ->
                        if (description == null) description = text(p)
                }
                XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                    inItem = false
                    val l = link?.trim()
                    var t = title?.trim()?.let { android.text.Html.fromHtml(it, 0).toString().trim() }
                    if (!l.isNullOrEmpty() && UrlUtils.isHttp(l) && !t.isNullOrEmpty()) {
                        var src = source?.trim()
                        // Google News puts the site after the headline: "Headline - Site".
                        if (src != null && t.endsWith(" - $src")) t = t.removeSuffix(" - $src").trim()
                        // A feed's own title often names the section too ("Dawn - Home"): just the site.
                        if (src.isNullOrEmpty()) src = feedTitle?.trim()?.substringBefore(" - ")?.substringBefore(" | ")?.trim()?.takeIf { it.isNotEmpty() } ?: feedSite
                        val img = image ?: description?.let { imgInHtml.find(it)?.groupValues?.get(1) }?.takeIf { UrlUtils.isHttp(it) }
                        out += Story(t, l, src, sourceUrl ?: if (feedUrl.contains("news.google.com")) null else feedUrl, parseTime(time), img)
                    }
                }
            }
            event = p.next()
        }
        return out to feedTitle
    }

    private fun text(p: XmlPullParser): String? = try {
        val t = p.nextText()
        t
    } catch (_: Exception) {
        null
    }

    private fun toJson(list: List<Story>) = JSONArray().apply {
        for (s in list) put(JSONObject().put("t", s.title).put("u", s.url).put("s", s.source).put("su", s.sourceUrl ?: "").put("time", s.time).put("i", s.image ?: ""))
    }

    private fun fromJson(a: JSONArray): List<Story> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Story(o.getString("t"), o.getString("u"), o.optString("s"), o.optString("su").ifEmpty { null }, o.optLong("time"), o.optString("i").ifEmpty { null })
    }
}
