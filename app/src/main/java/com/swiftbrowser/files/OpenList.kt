package com.swiftbrowser.files

/**
 * 3.0.13: the files a list of Files shows (a category such as Pictures or Music, a search, recent
 * files, a folder), handed to the picture viewer or the player, so that swiping and next/previous go
 * through the same files in the same order. Before, both always used the file's own folder (by name):
 * a picture opened from Pictures (45 of them) showed "30 of 30", only its folder's.
 *
 * Handed over in memory, by a token in the intent: thousands of paths are more than an intent may
 * carry. A screen made again after Android closed Swift finds no list and uses the folder, as before.
 * Never written down (Private's lists included). The rules are plain, for tests (OpenListTest).
 */
object OpenList {
    /** The intent's extra with the token. */
    const val EXTRA = "files_list"

    /** One file of the list, as Files listed it. */
    class Item(val path: String, val name: String, val time: Long, val size: Long)

    /** A list handed over: [title] says where it came from ("Music", "Search results"…). */
    class Handed(val token: String, val title: String, val items: List<Item>)

    private const val KEEP = 4
    private val lists = LinkedHashMap<String, Handed>()

    /** Keeps [items] for a screen to take by the token returned; only the last few are kept. */
    @Synchronized
    fun put(title: String, items: List<Item>): String {
        val token = java.util.UUID.randomUUID().toString()
        lists[token] = Handed(token, title, items)
        while (lists.size > KEEP) lists.remove(lists.keys.first())
        return token
    }

    @Synchronized
    fun get(token: String?): Handed? = token?.let { lists[it] }

    /** The screen that had the list is done with it. */
    @Synchronized
    fun drop(token: String?) {
        if (token != null) lists.remove(token)
    }

    /**
     * The items before and after the one at [path], at most [each] on each side (the player keeps a
     * thousand at most); null when [path] isn't in the list.
     */
    fun around(items: List<Item>, path: String, each: Int): Pair<List<Item>, List<Item>>? {
        val i = items.indexOfFirst { it.path == path }
        if (i < 0) return null
        return items.subList(maxOf(0, i - each), i).toList() to items.subList(i + 1, minOf(items.size, i + 1 + each)).toList()
    }
}
