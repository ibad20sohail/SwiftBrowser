package com.swiftbrowser.core

import android.net.Uri
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import java.net.URLEncoder

/** Where searches from the address bar and the home page go (Settings → Browsing & tabs). */
enum class SearchEngine(
    val key: String,
    val label: String,
    private val search: String,
    private val suggest: String,
    val icon: Int,
) {
    GOOGLE(
        "google", "Google", "https://www.google.com/search?q=",
        "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&q=", R.drawable.ic_google_g,
    ),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=", "https://ac.duckduckgo.com/ac/?type=list&q=", R.drawable.ic_search),
    BING("bing", "Bing", "https://www.bing.com/search?q=", "https://api.bing.com/osjson.aspx?query=", R.drawable.ic_search),
    STARTPAGE(
        "startpage", "Startpage", "https://www.startpage.com/sp/search?query=",
        "https://www.startpage.com/suggestions?format=opensearch&q=", R.drawable.ic_search,
    );

    /** The empty search box's hint (longer names don't fit on a small screen). */
    val hint: String get() = if (label.length <= 6) "Search $label or type URL" else "Search or type URL"

    fun searchUrl(query: String): String = search + URLEncoder.encode(query, "UTF-8")

    /** Suggestions in the OpenSearch format: ["typed", ["suggestion", ...]]. */
    fun suggestUrl(query: String): String = suggest + URLEncoder.encode(query, "UTF-8")

    companion object {
        fun fromKey(key: String?): SearchEngine = entries.firstOrNull { it.key == key } ?: GOOGLE

        val current: SearchEngine get() = fromKey(SwiftApp.prefs.searchEngine)

        /** The search terms if [url] is a results page of any of these engines, to show in the address bar. */
        fun queryOf(url: String?): String? {
            if (url == null || !UrlUtils.isHttp(url)) return null
            val u = try {
                Uri.parse(url)
            } catch (_: Exception) {
                return null
            }
            val host = u.host?.lowercase() ?: return null
            val path = u.path ?: ""
            return when {
                host.contains("google.") && path == "/search" -> u.getQueryParameter("q")
                (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) && (path == "/" || path.isEmpty()) -> u.getQueryParameter("q")
                (host == "bing.com" || host.endsWith(".bing.com")) && path == "/search" -> u.getQueryParameter("q")
                host.endsWith("startpage.com") && (path.startsWith("/sp/search") || path.startsWith("/do/search")) ->
                    u.getQueryParameter("query") ?: u.getQueryParameter("q")
                else -> null
            }
        }
    }
}
