package com.swiftbrowser.core

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import com.swiftbrowser.ui.BookmarkFile
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * 3.0.11: "Bring in from Chrome". Chrome's own sync is closed to other browsers and Google has no way
 * for them to read Chrome's bookmarks or history, but Google Takeout gives them as a .zip (choose
 * Chrome there): Chrome/Bookmarks.html (the file every browser exports) and Chrome/BrowserHistory.json
 * (each visit: its address, title and time in microseconds). Passwords come as a .csv from Google
 * Password Manager (PasswordsCsv). [HistoryTally] is plain Kotlin, tested on the PC.
 */
object ChromeImport {
    private const val DAY = 24L * 3600 * 1000

    /** Swift lets pages last visited over a year ago go (Db.pruneHistoryIfDue): older ones aren't brought in. */
    private const val YEAR = 365 * DAY
    const val MAX_PAGES = 100_000
    private const val MAX_URL = 8192
    private const val MAX_TITLE = 2048
    private const val MAX_BOOKMARKS_FILE = 30_000_000

    /** A page of Chrome's history: its visits folded into one, as Swift keeps one row per address. */
    class Page(val url: String, var title: String, var last: Long, var visits: Int)

    /** Folds Takeout's visits by address: the newest title, the last visit, how many there were. */
    class HistoryTally(private val now: Long) {
        private val pages = LinkedHashMap<String, Page>()

        fun add(url: String, title: String, timeUsec: Long) {
            val t = timeUsec / 1000
            if (t <= 0 || t > now + DAY || t < now - YEAR) return
            if (!UrlUtils.isHttp(url) || url.length > MAX_URL) return
            val p = pages[url]
            if (p == null) {
                if (pages.size >= MAX_PAGES) return
                pages[url] = Page(url, title.take(MAX_TITLE), t, 1)
            } else {
                p.visits++
                if (t > p.last) {
                    p.last = t
                    if (title.isNotBlank()) p.title = title.take(MAX_TITLE)
                }
            }
        }

        /** Newest first. */
        fun pages(): List<Page> = pages.values.sortedByDescending { it.last }
    }

    /** What a Takeout .zip held; [chrome] false when it had none of Chrome's files (Chrome not chosen). */
    class Takeout(val marks: List<BookmarkFile.Mark>, val pages: List<Page>, val chrome: Boolean)

    /** What was added, for Undo: the new bookmarks and new history pages (pages already there only got their visits). */
    class Added(val bookmarks: List<String>, val pages: List<String>, val merged: Int)

    /** The file at [uri] starts like a .zip ("PK"). */
    fun looksLikeZip(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(2)
            s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        } ?: false
    } catch (_: Exception) {
        false
    }

    /** Reads Chrome's bookmarks and history from a Takeout .zip, as it goes (never the whole file). Null when it can't be opened. */
    fun readTakeout(ctx: Context, uri: Uri, now: Long = System.currentTimeMillis()): Takeout? {
        var marks: List<BookmarkFile.Mark> = emptyList()
        val tally = HistoryTally(now)
        var chrome = false
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').lowercase()
                when {
                    name.endsWith("chrome/bookmarks.html") -> {
                        chrome = true
                        Storage.readAtMost(zip, MAX_BOOKMARKS_FILE)?.let { marks = BookmarkFile.parse(String(it, Charsets.UTF_8)) }
                    }
                    name.endsWith("chrome/browserhistory.json") || name.endsWith("chrome/history.json") -> {
                        chrome = true
                        readHistory(zip, tally)
                    }
                }
            }
        }
        return Takeout(marks, tally.pages(), chrome)
    }

    /** {"Browser History": [{"url", "title", "time_usec", …}, …]}, one visit at a time. */
    private fun readHistory(input: InputStream, tally: HistoryTally) {
        // The zip goes on after this file: reading to its end must not close it.
        val keepOpen = object : FilterInputStream(input) {
            override fun close() = Unit
        }
        val r = JsonReader(InputStreamReader(keepOpen, Charsets.UTF_8))
        r.isLenient = true
        if (r.peek() != JsonToken.BEGIN_OBJECT) return
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "Browser History" || r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue()
                continue
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                var url = ""
                var title = ""
                var time = 0L
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "url" -> url = text(r)
                        "title" -> title = text(r)
                        "time_usec" -> time = when (r.peek()) {
                            JsonToken.NUMBER -> r.nextLong()
                            JsonToken.STRING -> r.nextString().toLongOrNull() ?: 0L
                            else -> {
                                r.skipValue()
                                0L
                            }
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                tally.add(url, title, time)
            }
            r.endArray()
        }
    }

    private fun text(r: JsonReader): String = if (r.peek() == JsonToken.STRING) r.nextString() else {
        r.skipValue()
        ""
    }

    /** Into profile [pid]: bookmarks not there yet, and history (a page already there keeps its larger visit count and last visit). */
    fun add(db: Db, pid: Long, t: Takeout): Added {
        val fresh = BookmarkFile.fresh(db, pid, t.marks)
        val newPages = ArrayList<String>()
        var merged = 0
        db.transaction {
            for (m in fresh) db.importBookmark(pid, m.url, m.title, m.added, m.folder)
            for (p in t.pages) if (db.mergeHistory(pid, p.url, p.title, p.last, p.visits)) newPages += p.url else merged++
        }
        return Added(fresh.map { it.url }, newPages, merged)
    }

    /** Undo: what [a] added goes again (pages that were already there stay). */
    fun undo(db: Db, pid: Long, a: Added) {
        db.transaction {
            for (u in a.bookmarks) db.removeBookmark(pid, u)
            for (u in a.pages) db.deleteHistoryUrl(pid, u)
        }
    }
}
