package com.swiftbrowser.core

import android.net.Uri
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import java.net.URLEncoder
import java.util.Locale

/** Where searches from the address bar and the home page go (Settings → Browsing & tabs). */
enum class SearchEngine(
    val key: String,
    private val title: String,
    private val search: String,
    private val suggest: String,
    val icon: Int,
) {
    GOOGLE(
        "google", "Google", "https://www.google.com/search?q=",
        "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&q=", R.drawable.ic_google_g,
    ),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=", "https://ac.duckduckgo.com/ac/?type=list&q=", R.drawable.ic_engine_duckduckgo),
    BING("bing", "Bing", "https://www.bing.com/search?q=", "https://api.bing.com/osjson.aspx?query=", R.drawable.ic_engine_bing),
    STARTPAGE(
        "startpage", "Startpage", "https://www.startpage.com/sp/search?query=",
        "https://www.startpage.com/suggestions?format=opensearch&q=", R.drawable.ic_engine_startpage,
    ),

    /** One the user gave (2.6): an address with %s where the words go. No suggestions. */
    CUSTOM("custom", "Your own", "", "", R.drawable.ic_search);

    val label: String
        get() = if (this == CUSTOM) SwiftApp.prefs.customSearchName.ifBlank { title } else title

    /** The empty search box's hint. */
    val hint: String get() = "Search or type web address"

    fun searchUrl(query: String): String {
        val words = URLEncoder.encode(query, "UTF-8")
        if (this == CUSTOM) {
            val template = SwiftApp.prefs.customSearchUrl
            return if ("%s" in template) template.replace("%s", words) else GOOGLE.searchUrl(query)
        }
        return search + words
    }

    /** Suggestions in the OpenSearch format: ["typed", ["suggestion", ...]]; null: the engine has none. */
    fun suggestUrl(query: String): String? = if (suggest.isEmpty()) null else suggest + URLEncoder.encode(query, "UTF-8")

    companion object {
        /** Whether [icon] is one of the engines' logos (drawn in its own colours). */
        fun isLogo(icon: Int) = entries.any { it.icon == icon }

        fun fromKey(key: String?): SearchEngine = entries.firstOrNull { it.key == key } ?: GOOGLE

        val current: SearchEngine get() = fromKey(SwiftApp.prefs.searchEngine)

        /** Google's own sites: google.com, google.de, google.co.uk, google.com.pk, with or without www. */
        private val GOOGLE_HOST = Regex("^(www\\.)?google\\.(com|cat|[a-z]{2}|(co|com)\\.[a-z]{2})$")

        /**
         * 3.0: the engine whose own site [host] is, matched exactly: "google.github.io" or
         * "google.example.com" isn't Google, so a page there can't put words next to Google's logo in
         * the address bar as if they were a search.
         */
        fun ofHost(host: String?): SearchEngine? {
            val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
            fun on(site: String) = h == site || h.endsWith(".$site")
            return when {
                GOOGLE_HOST.matches(h) -> GOOGLE
                on("duckduckgo.com") -> DUCKDUCKGO
                on("bing.com") -> BING
                on("startpage.com") -> STARTPAGE
                else -> null
            }
        }

        /**
         * 3.0: the names of the address parts that hold the search words when [host] and [path] are
         * a results page of one of these engines; null for any other page.
         */
        fun wordsParams(host: String?, path: String): List<String>? = when (ofHost(host)) {
            GOOGLE, BING -> if (path == "/search") listOf("q") else null
            DUCKDUCKGO -> if (path == "/" || path.isEmpty()) listOf("q") else null
            STARTPAGE -> if (path.startsWith("/sp/search") || path.startsWith("/do/search")) listOf("query", "q") else null
            else -> null
        }

        /** The search words if [url] is a results page of any of these engines, whatever they are. */
        fun wordsOf(url: String?): String? {
            if (url == null || !UrlUtils.isHttp(url)) return null
            val u = try {
                Uri.parse(url)
            } catch (_: Exception) {
                return null
            }
            val params = wordsParams(u.host, u.path ?: "") ?: return null
            return params.firstNotNullOfOrNull { u.getQueryParameter(it) }
        }

        /**
         * The search terms if [url] is a results page of any of these engines, to show in the address
         * bar. 3.0: not when they read like an address ("accounts.google.com"): the bar shows the
         * site then, so a link to such a search can't pass for that site.
         */
        fun queryOf(url: String?): String? = wordsOf(url)?.takeIf { it.isNotBlank() && !UrlUtils.looksLikeAddress(it) }

        /** 3.0: the engine whose results page [url] is, for its logo next to the words. */
        fun ofResults(url: String?): SearchEngine? = if (wordsOf(url) != null) ofHost(UrlUtils.host(url)) else null
    }
}
