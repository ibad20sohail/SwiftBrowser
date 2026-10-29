package com.swiftbrowser.core

import android.net.Uri
import androidx.core.content.edit
import com.swiftbrowser.SwiftApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The shortcuts on the home page: the ones the user added (pinned, in their order), then the
 * profile's most visited sites.
 */
object TopSites {
    data class Tile(val key: String, val url: String, val label: String, val pinned: Boolean = false)

    /** Up to this many shortcuts on the home page (2.7: 15, five to a row). */
    const val MAX = 15

    /** Counts history clearings, so the home page works its shortcuts out again (2.6). */
    @Volatile var historyChanged = 0

    private fun pinnedKey(profileId: Long) = "pinned_tiles_$profileId"

    /** The shortcuts the user added, in their order. */
    fun pinned(profileId: Long): List<Tile> {
        val json = SwiftApp.prefs.sp.getString(pinnedKey(profileId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                val host = Uri.parse(url).host ?: return@mapNotNull null
                Tile(key(host), url, o.optString("label").ifBlank { label(key(host)) }, pinned = true)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setPinned(profileId: Long, tiles: List<Tile>) {
        val arr = JSONArray()
        tiles.forEach { arr.put(JSONObject().put("url", it.url).put("label", it.label)) }
        SwiftApp.prefs.sp.edit { putString(pinnedKey(profileId), arr.toString()) }
    }

    fun addPinned(profileId: Long, url: String, label: String) {
        val host = Uri.parse(url).host ?: return
        setPinned(profileId, pinned(profileId).filter { it.url != url } + Tile(key(host), url, label.ifBlank { label(key(host)) }, true))
    }

    /** Replaces [old] (pinned or not) with a pinned shortcut to [url] named [label], in the same place. */
    fun editPinned(profileId: Long, old: Tile, url: String, label: String) {
        val host = Uri.parse(url).host ?: return
        val tile = Tile(key(host), url, label.ifBlank { label(key(host)) }, true)
        val list = pinned(profileId).toMutableList()
        val i = list.indexOfFirst { it.url == old.url }
        if (i >= 0) list[i] = tile else list += tile
        setPinned(profileId, list)
    }

    fun unpin(profileId: Long, tile: Tile) = setPinned(profileId, pinned(profileId).filter { it.url != tile.url })

    /** Puts [tile] first among the pinned shortcuts (pinning it if it wasn't). */
    fun moveToStart(profileId: Long, tile: Tile) {
        val rest = pinned(profileId).filter { it.url != tile.url }
        setPinned(profileId, listOf(tile.copy(pinned = true)) + rest)
    }

    private val DEFAULTS = listOf(
        Tile("google.com", "https://www.google.com/", "Google"),
        Tile("youtube.com", "https://m.youtube.com/", "YouTube"),
        Tile("mail.google.com", "https://mail.google.com/", "Gmail"),
        Tile("wikipedia.org", "https://www.wikipedia.org/", "Wikipedia"),
    )

    private val NAMES = mapOf(
        "google.com" to "Google", "youtube.com" to "YouTube", "mail.google.com" to "Gmail",
        "facebook.com" to "Facebook", "wikipedia.org" to "Wikipedia", "github.com" to "GitHub",
        "whatsapp.com" to "WhatsApp", "web.whatsapp.com" to "WhatsApp", "x.com" to "X", "twitter.com" to "Twitter",
        "linkedin.com" to "LinkedIn", "reddit.com" to "Reddit", "instagram.com" to "Instagram",
        "netflix.com" to "Netflix", "imdb.com" to "IMDb", "amazon.com" to "Amazon", "chatgpt.com" to "ChatGPT",
        "tiktok.com" to "TikTok", "drive.google.com" to "Drive", "maps.google.com" to "Maps",
    )

    /** Sign-in and consent pages are visited a lot but are no place to start from. */
    private val SKIP = setOf(
        "accounts.google.com", "consent.google.com", "consent.youtube.com", "accounts.youtube.com",
        "myaccount.google.com", "support.google.com", "ogs.google.com",
    )

    fun key(host: String): String = host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")

    /** "en.m.wikipedia.org" → "Wikipedia": the site's own name, not its language or mobile prefix. */
    fun label(key: String): String =
        NAMES[key] ?: UrlUtils.siteOfHost(key).substringBefore('.').replaceFirstChar { it.titlecase(Locale.ROOT) }

    /**
     * "Cnn" → "CNN", "Ipinfo" → "IPinfo" (2.6): the name written the way the site's own page title
     * writes it, when the title has it as a word.
     */
    private fun casedLike(key: String, label: String, title: String?): String {
        if (title.isNullOrBlank() || key in NAMES) return label
        val i = title.indexOf(label, ignoreCase = true)
        if (i < 0) return label
        val before = if (i > 0) title[i - 1] else ' '
        val after = if (i + label.length < title.length) title[i + label.length] else ' '
        if (before.isLetterOrDigit() || after.isLetterOrDigit()) return label
        return title.substring(i, i + label.length)
    }

    /** Blocking; reads history. The user's own shortcuts first, then the most visited sites. */
    fun load(db: Db, profileId: Long, hidden: Set<String>): List<Tile> {
        val pinned = pinned(profileId).take(MAX)
        val taken = pinned.map { it.key }.toSet()
        val visited = loadVisited(db, profileId, hidden + taken, (8 - pinned.size).coerceAtLeast(0))
        return pinned + visited
    }

    private fun loadVisited(db: Db, profileId: Long, hidden: Set<String>, max: Int): List<Tile> {
        if (max <= 0) return emptyList()
        class Site(val scheme: String, val host: String) {
            var visits = 0
            var last = 0L
            var title: String? = null
        }
        val sites = LinkedHashMap<String, Site>()
        for (r in db.recentVisits(profileId, 1500)) {
            val uri = try {
                Uri.parse(r.url)
            } catch (_: Exception) {
                continue
            }
            val host = uri.host ?: continue
            val key = key(host)
            if (key in SKIP || key in hidden) continue
            val site = sites.getOrPut(key) { Site(uri.scheme ?: "https", host) }
            if (site.title == null && r.title.isNotBlank()) site.title = r.title
            site.visits += r.visits
            site.last = maxOf(site.last, r.lastVisit)
        }
        val tiles = sites.entries
            .sortedWith(compareByDescending<Map.Entry<String, Site>> { it.value.visits }.thenByDescending { it.value.last })
            .take(max)
            .map { (key, s) -> Tile(key, "${s.scheme}://${s.host}/", casedLike(key, label(key), s.title)) }
            .toMutableList()
        if (tiles.size < 4 && tiles.size < max) {
            for (d in DEFAULTS) {
                if (tiles.size >= max) break
                if (d.key !in hidden && tiles.none { it.key == d.key }) tiles += d
            }
        }
        return tiles
    }
}
