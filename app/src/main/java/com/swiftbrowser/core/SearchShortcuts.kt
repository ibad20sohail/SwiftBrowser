package com.swiftbrowser.core

import com.swiftbrowser.SwiftApp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Search shortcuts (2.6): a word, a space and what to look for searches one site, so "yt cats"
 * searches YouTube and "w comet" Wikipedia. Swift comes with a few; you can add your own and remove
 * any (Settings → Browsing & tabs → Search shortcuts).
 */
object SearchShortcuts {
    class Shortcut(val keyword: String, val name: String, val url: String)

    private const val PREF_OWN = "search_shortcuts"
    private const val PREF_REMOVED = "search_shortcuts_removed"

    private val BUILT_IN = listOf(
        Shortcut("yt", "YouTube", "https://m.youtube.com/results?search_query=%s"),
        Shortcut("w", "Wikipedia", "https://en.m.wikipedia.org/w/index.php?search=%s"),
        Shortcut("g", "Google", "https://www.google.com/search?q=%s"),
        Shortcut("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Shortcut("bing", "Bing", "https://www.bing.com/search?q=%s"),
        Shortcut("img", "Google Images", "https://www.google.com/search?tbm=isch&q=%s"),
        Shortcut("maps", "Google Maps", "https://www.google.com/maps/search/%s"),
        Shortcut("r", "Reddit", "https://www.reddit.com/search/?q=%s"),
        Shortcut("gh", "GitHub", "https://github.com/search?q=%s"),
        Shortcut("amz", "Amazon", "https://www.amazon.com/s?k=%s"),
    )

    private val sp get() = SwiftApp.prefs.sp

    private fun own(): List<Shortcut> = try {
        val a = JSONArray(sp.getString(PREF_OWN, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Shortcut(o.optString("k"), o.optString("n"), o.optString("u")).takeIf { it.keyword.isNotBlank() && "%s" in it.url }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun removed(): Set<String> = sp.getStringSet(PREF_REMOVED, emptySet()) ?: emptySet()

    private fun write(list: List<Shortcut>, removed: Set<String>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("k", it.keyword).put("n", it.name).put("u", it.url)) }
        sp.edit().putString(PREF_OWN, a.toString()).putStringSet(PREF_REMOVED, removed).apply()
    }

    /** Yours first, then Swift's (those you didn't remove or replace). */
    fun all(): List<Shortcut> {
        val mine = own()
        val gone = removed()
        return mine + BUILT_IN.filter { b -> b.keyword !in gone && mine.none { it.keyword == b.keyword } }
    }

    /** The shortcut [input] starts with ("yt " for YouTube), or null. */
    fun shortcutOf(input: String): Shortcut? {
        val t = input.trimStart()
        val space = t.indexOf(' ')
        if (space <= 0) return null
        val keyword = t.substring(0, space).lowercase()
        return all().firstOrNull { it.keyword == keyword }
    }

    /** The address for "keyword words", or null when [input] doesn't start with a shortcut. */
    fun match(input: String): String? {
        val s = shortcutOf(input) ?: return null
        val words = input.trimStart().substringAfter(' ').trim()
        if (words.isEmpty()) return null
        return s.url.replace("%s", URLEncoder.encode(words, "UTF-8"))
    }

    fun add(keyword: String, name: String, url: String) {
        val k = keyword.trim().lowercase()
        write(own().filter { it.keyword != k } + Shortcut(k, name.trim(), url.trim()), removed() - k)
    }

    fun remove(keyword: String) {
        val gone = if (BUILT_IN.any { it.keyword == keyword }) removed() + keyword else removed()
        write(own().filter { it.keyword != keyword }, gone)
    }

    /** Swift's shortcuts back as they came, yours removed. */
    fun reset() {
        sp.edit().remove(PREF_OWN).remove(PREF_REMOVED).apply()
    }
}
