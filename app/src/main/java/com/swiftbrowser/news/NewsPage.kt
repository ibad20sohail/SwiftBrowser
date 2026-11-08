package com.swiftbrowser.news

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 3.0.5: the stories on Google News' own page, with their pictures. Its feeds (RSS) have no pictures
 * at all, so the news cards never had one; the page has the headline, the site, the time, the
 * article's real address and a picture for about fifty stories of each topic. Only stories with a
 * picture are taken. Plain code on the page's bytes, tested on the PC (NewsPageTest); News falls back
 * on the feed whenever this finds too little (Google changed the page, a consent page instead).
 */
internal object NewsPage {
    /** Fewer stories than this and the page isn't what Swift expects: the feed is used instead. */
    const val MIN_STORIES = 12

    /** A story's markup is all within this many bytes of its start. */
    private const val MAX_BLOCK = 16 * 1024

    /** Each story on the page starts here. Looked for in the bytes: the page is 3 to 4 MB, and most of it isn't stories. */
    private val START = "jsname=\"hXwDdf\"".toByteArray(Charsets.US_ASCII)

    private val PICTURE = Regex("src=\"/api/attachments/([A-Za-z0-9_-]+)=-w")
    private val HEADLINE = Regex("<a href=\"\\./read/([^\"]+)\"[^>]*>([^<]+)</a>")
    private val SOURCE = Regex("data-n-tid=\"9\">([^<]+)</a>")
    private val TIME = Regex("datetime=\"([^\"]+)\"")
    private val SITE = Regex("faviconV2\\?url=(https?://[^&\"]+)&")
    private val LINK = Regex("jslog=\"\\d+; 5:([A-Za-z0-9+/=_-]+);")
    private val LAST_ADDRESS = Regex("\"(https?:[^\"]+)\"\\s*]\\s*$")
    private val UNICODE = Regex("\\\\u([0-9a-fA-F]{4})")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|lt|gt|apos|nbsp);")

    /** The picture at the size of a card (a 16:9 band of a phone's width), as a small WebP (about 30 KB). */
    fun pictureAddress(id: String) = "https://news.google.com/api/attachments/$id=-w640-h360-p-df-rw"

    /**
     * The stories with a picture on [page], in its order, at most [max]. [base64] decodes a text
     * (Android's decoder in the app, Java's in tests) or says null.
     */
    fun stories(page: ByteArray, max: Int, base64: (String) -> ByteArray?): List<Story> {
        val out = LinkedHashMap<String, Story>()
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var at = indexOf(page, START, 0)
        while (at >= 0 && out.size < max) {
            val next = indexOf(page, START, at + START.size)
            val end = minOf(if (next < 0) page.size else next, at + MAX_BLOCK)
            story(String(page, at, end - at, Charsets.UTF_8), time, base64)?.let { out.putIfAbsent(it.title, it) }
            at = next
        }
        return out.values.toList()
    }

    private fun story(block: String, time: SimpleDateFormat, base64: (String) -> ByteArray?): Story? {
        val picture = PICTURE.find(block)?.groupValues?.get(1) ?: return null
        val head = HEADLINE.find(block) ?: return null
        val title = unescape(head.groupValues[2]).trim().takeIf { it.isNotEmpty() } ?: return null
        val source = SOURCE.find(block)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()
        val link = LINK.find(block)?.groupValues?.get(1)?.let { realAddress(it, base64) }
            ?: ("https://news.google.com/read/" + unescape(head.groupValues[1]))
        val site = SITE.find(block)?.groupValues?.get(1)
        val at = TIME.find(block)?.groupValues?.get(1)?.let { runCatching { time.parse(it)?.time }.getOrNull() } ?: 0L
        return Story(title, link, source.ifEmpty { site?.substringAfter("://")?.removePrefix("www.") ?: "News" }, site, at, pictureAddress(picture))
    }

    /** The article's own address, the last text of the small list Google packs into the story's link. Opens without going through Google. */
    private fun realAddress(packed: String, base64: (String) -> ByteArray?): String? {
        val text = base64(packed)?.toString(Charsets.UTF_8) ?: return null
        val raw = LAST_ADDRESS.find(text)?.groupValues?.get(1) ?: return null
        return UNICODE.replace(raw.replace("\\/", "/")) { it.groupValues[1].toInt(16).toChar().toString() }
    }

    /** The page's text as text: the HTML entities a headline or a site's name can have. */
    fun unescape(text: String): String = ENTITY.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e == "amp" -> "&"
            e == "quot" -> "\""
            e == "lt" -> "<"
            e == "gt" -> ">"
            e == "apos" -> "'"
            e == "nbsp" -> " "
            else -> (if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())
                ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    private fun indexOf(data: ByteArray, what: ByteArray, from: Int): Int {
        var i = from
        val last = data.size - what.size
        outer@ while (i <= last) {
            for (j in what.indices) {
                if (data[i + j] != what[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
