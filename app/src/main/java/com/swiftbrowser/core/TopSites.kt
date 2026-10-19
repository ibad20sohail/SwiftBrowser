package com.swiftbrowser.core

import android.net.Uri
import java.util.Locale

/** The shortcuts on the home page: the profile's most visited sites. */
object TopSites {
    data class Tile(val key: String, val url: String, val label: String)

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

    fun label(key: String): String =
        NAMES[key] ?: key.substringBefore('.').replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** Blocking; reads history. */
    fun load(db: Db, profileId: Long, hidden: Set<String>, max: Int = 8): List<Tile> {
        class Site(val scheme: String, val host: String) {
            var visits = 0
            var last = 0L
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
            site.visits += r.visits
            site.last = maxOf(site.last, r.lastVisit)
        }
        val tiles = sites.entries
            .sortedWith(compareByDescending<Map.Entry<String, Site>> { it.value.visits }.thenByDescending { it.value.last })
            .take(max)
            .map { (key, s) -> Tile(key, "${s.scheme}://${s.host}/", label(key)) }
            .toMutableList()
        if (tiles.size < 4) {
            for (d in DEFAULTS) {
                if (tiles.size >= max) break
                if (d.key !in hidden && tiles.none { it.key == d.key }) tiles += d
            }
        }
        return tiles
    }
}
