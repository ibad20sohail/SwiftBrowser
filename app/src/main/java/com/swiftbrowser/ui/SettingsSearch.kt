package com.swiftbrowser.ui

import java.util.Locale

/**
 * Finding settings by their words (3.0). Whole words, so "downloads" no longer finds everything
 * about ads, nor "skip" and "zip" the VPN (through "ip"); a search of several words finds the
 * settings that have every one of them, in any order ("clear cookies", "delete history"), the last
 * word also as the start of a longer one while it is being typed. A word is found in a setting's
 * title, what its row says, its keywords and its topic, or through an everyday word for it.
 *
 * Plain Kotlin, so the PC's tests check it.
 */
object SettingsSearch {
    /** What is searched in for one setting. */
    class Entry(val title: String, val summary: String, val keywords: String, val topic: String)

    /**
     * Everyday words for what settings are about ("battery" finds Lite mode and Data saver), each
     * with the words of the settings they find. A key ending in "*" also stands for longer words
     * ("vibrat*": vibrate, vibration).
     */
    val SYNONYMS: Map<String, List<String>> = mapOf(
        "battery" to listOf("battery saver", "lite", "data saver", "draw pages", "tabs kept", "sleep", "dark"),
        "music" to listOf("playing", "background", "sleep", "audio"),
        "song" to listOf("playing", "background", "sleep"),
        "sound" to listOf("audio", "volume", "sound", "autoplay", "playing"),
        "volume" to listOf("volume", "swipes"),
        "ads" to listOf("ad", "block", "ublock", "cookie", "pop-up"),
        "advert*" to listOf("ad", "block", "ublock"),
        "popup" to listOf("pop-up", "cookie", "block"),
        "slow" to listOf("lite", "speed", "draw pages", "tabs kept", "designed"),
        "fast" to listOf("lite", "speed", "draw pages", "tabs kept"),
        "smooth" to listOf("lite", "draw pages", "toolbar", "designed"),
        "internet" to listOf("data", "wi-fi", "secure", "downloads"),
        "wifi" to listOf("wi-fi", "data"),
        "mobile" to listOf("data", "wi-fi"),
        "space" to listOf("storage", "cache", "download folder"),
        "memory" to listOf("tabs kept", "storage", "memory"),
        "font" to listOf("text size", "reader"),
        "big" to listOf("text size", "zoom"),
        "night" to listOf("dark"),
        "black" to listOf("dark", "colours"),
        "colour" to listOf("colours"),
        "color" to listOf("colours"),
        "background" to listOf("colours"),
        "theme" to listOf("theme", "colours"),
        "privacy" to listOf("clear", "secure", "cookie", "incognito", "site settings"),
        "password" to listOf("site settings", "clear"),
        "location" to listOf("site settings", "vpn"),
        "desktop" to listOf("site settings", "desktop"),
        "youtube" to listOf("playing", "background", "designed", "autoplay", "video"),
        "film" to listOf("continue", "video", "download"),
        "movie" to listOf("continue", "video", "download"),
        "save" to listOf("download", "backup", "data saver"),
        "phone" to listOf("backup", "restore", "storage"),
        "vibrat*" to listOf("vibration"),
        "home" to listOf("address bar buttons", "address bar"),
        "button" to listOf("address bar buttons", "reset the menu"),
        "toolbar" to listOf("address bar buttons", "address bar"),
        "menu" to listOf("reset the menu"),
        "dns" to listOf("secure dns"),
        "test" to listOf("test the locations"),
        "expir*" to listOf("vpn locations"),
        "search" to listOf("search engine", "suggestions"),
        "google" to listOf("search engine", "suggestions"),
        "update" to listOf("updates", "filter lists"),
        "country" to listOf("vpn", "country"),
        "ip" to listOf("vpn"),
        "proton" to listOf("vpn"),
        "delete" to listOf("clear"),
        "erase" to listOf("clear"),
        "remove" to listOf("clear"),
        "licence" to listOf("open-source licences"),
        "license" to listOf("open-source licences"),
    )

    /** Words left out of a search of several words ("how to clear the cache"), unless nothing else is left. */
    private val FILLERS = setOf(
        "a", "an", "the", "to", "of", "my", "for", "and", "or", "in", "on", "off", "how", "do", "does", "i", "is", "it",
        "its", "can", "where", "what", "which", "with", "me", "turn", "get", "make", "set", "change", "want", "please",
        "setting", "settings", "option", "options",
    )

    /** The words of [text], in small letters ("Wi-Fi" is "wi" and "fi"). */
    fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(NOT_WORD).filter { it.isNotEmpty() }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** The words searched for: [query]'s, without the fillers when others are left. */
    fun queryWords(query: String): List<String> {
        val all = words(query)
        val meaningful = all.filter { it !in FILLERS }
        return meaningful.ifEmpty { all }
    }

    /** One word and its plural count as the same ("cookie", "cookies"; "ad", "ads"; "battery", "batteries"). */
    fun sameWord(a: String, b: String): Boolean = a == b || singular(a) == singular(b)

    private fun singular(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
        w.length >= 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** [word] is one of [text]'s words; with [prefix] (the word being typed), or the start of one. */
    private fun hasWord(text: List<String>, word: String, prefix: Boolean): Boolean =
        text.any { sameWord(it, word) || (prefix && it.startsWith(word)) }

    /** [phrase]'s words come one after another in [text]. */
    private fun hasPhrase(text: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > text.size) return false
        for (i in 0..text.size - phrase.size) {
            if (phrase.indices.all { j -> sameWord(text[i + j], phrase[j]) }) return true
        }
        return false
    }

    /** The phrases the everyday word [word] stands for ([prefix]: it may be the start of one, 3 letters at least). */
    private fun synonymsOf(word: String, prefix: Boolean, synonyms: Map<String, List<String>>): List<List<String>> =
        synonyms.filterKeys { key ->
            val k = key.removeSuffix("*")
            val stem = key.endsWith("*") && word.startsWith(k)
            stem || sameWord(word, k) || (prefix && word.length >= 3 && k.startsWith(word))
        }.values.flatten().map { words(it) }

    /** Whether [e] is found by [query]: every word of it is in the setting's words, or stands for some of them. */
    fun matches(query: String, e: Entry, synonyms: Map<String, List<String>> = SYNONYMS): Boolean {
        val q = queryWords(query)
        if (q.isEmpty()) return false
        val text = words(e.title) + words(e.summary) + words(e.keywords) + words(e.topic)
        return q.withIndex().all { (i, w) ->
            val prefix = i == q.lastIndex
            hasWord(text, w, prefix) || synonymsOf(w, prefix, synonyms).any { hasPhrase(text, it) }
        }
    }
}
