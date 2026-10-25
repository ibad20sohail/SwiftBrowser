package com.swiftbrowser.ui

import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Engine
import org.json.JSONArray
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.net.URLEncoder

data class Suggestion(val kind: Kind, val title: String, val url: String) {
    enum class Kind { GO, SEARCH, BOOKMARK, HISTORY, CLIPBOARD }
}

/** Address bar suggestions: the search engine's suggest service plus the profile's bookmarks and history. */
object Suggestions {
    /** Blocking; call from a background thread. */
    fun fromEngine(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val url = com.swiftbrowser.core.SearchEngine.current.suggestUrl(query)
        return try {
            val request = WebRequest.Builder(url).method("GET").build()
            val response = Engine.executor.fetch(request, GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS).poll(5000)
                ?: return emptyList()
            val body = response.body ?: return emptyList()
            val text = body.use { String(it.readBytes(), Charsets.UTF_8) }
            val arr = JSONArray(text).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }.take(6)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Blocking; call from a background thread. */
    fun local(profileId: Long, text: String): List<Suggestion> {
        val db = SwiftApp.db
        val out = ArrayList<Suggestion>()
        if (text.isBlank()) {
            db.bookmarks(profileId).take(12).forEach { out.add(Suggestion(Suggestion.Kind.BOOKMARK, it.title.ifBlank { it.url }, it.url)) }
            db.history(profileId, null, 30).distinctBy { UrlUtils.host(it.url) }.take(8).forEach {
                if (out.none { s -> s.url == it.url }) out.add(Suggestion(Suggestion.Kind.HISTORY, it.title.ifBlank { it.url }, it.url))
            }
            return out
        }
        db.bookmarkMatches(profileId, text, 3).forEach { out.add(Suggestion(Suggestion.Kind.BOOKMARK, it.title.ifBlank { it.url }, it.url)) }
        db.historyMatches(profileId, text, 5).forEach {
            if (out.none { s -> s.url == it.url }) out.add(Suggestion(Suggestion.Kind.HISTORY, it.title.ifBlank { it.url }, it.url))
        }
        return out
    }
}
