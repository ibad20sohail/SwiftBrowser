package com.swiftbrowser.core

/**
 * 3.0.7: how the cache's room is shared (Settings → Storage & data → Cache size: 50, 100 or 200 MB,
 * as Android and Swift's Storage count it). Since 3.0.5 the engine chose its own size, hundreds of
 * MB, and video sites filled it with the pieces of video they stream. Now the engine gets the limit
 * less [OWN_MB], with no single file over [MAX_FILE_MB] (one video can't take it all); Swift's own
 * temporary files (copies for sharing, files opened from archives, picture edits, torrent lists)
 * keep to [OWN_MB], oldest first out. Plain, for tests.
 */
object CacheBudget {
    val CHOICES_MB = listOf(50, 100, 200)
    const val DEFAULT_MB = 100

    /** Swift's own temporary files. */
    const val OWN_MB = 10

    /** The biggest file the engine keeps. */
    const val MAX_FILE_MB = 10

    /** The engine's share of [limitMb], in KB (its own unit). */
    fun engineKb(limitMb: Int): Int = (valid(limitMb) - OWN_MB) * 1024

    /** The biggest file the engine keeps, in KB: never more than a fifth of its share. */
    fun maxFileKb(limitMb: Int): Int = minOf(MAX_FILE_MB * 1024, engineKb(limitMb) / 5)

    /** A choice Swift offers (an old or odd saved value becomes the default). */
    fun valid(limitMb: Int): Int = if (limitMb in CHOICES_MB) limitMb else DEFAULT_MB

    /**
     * Which of Swift's own files go, oldest first, so what is left fits in [budget] bytes; files
     * changed after [keepAfter] (in use now) always stay. [files]: (path, size, last changed).
     */
    fun toDelete(files: List<Triple<String, Long, Long>>, budget: Long, keepAfter: Long): List<String> {
        var total = files.sumOf { it.second }
        if (total <= budget) return emptyList()
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.third }) {
            if (total <= budget) break
            if (f.third > keepAfter) continue
            out += f.first
            total -= f.second
        }
        return out
    }
}
