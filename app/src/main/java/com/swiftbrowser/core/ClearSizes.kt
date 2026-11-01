package com.swiftbrowser.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.download.Downloads
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * How much Clear browsing data would take away (2.8), worked out before it clears, off the main
 * thread: the size of history and of what the engine keeps for the sites of that time, and how
 * many entries each list loses. The engine can't say what it keeps per site, so its folders are
 * measured on disk: each site's stored data in the profile (storage/default/https+++example.com…)
 * and its cached files (cache2/entries, one file per address).
 */
object ClearSizes {
    /**
     * One row's measure: [count] of entries, and [bytes] (-1: a list, shown by its count); [about]: a
     * rough guess; not [known]: it couldn't be worked out.
     */
    class Measure(val count: Int, val bytes: Long = -1L, val about: Boolean = false, val known: Boolean = true) {
        companion object {
            val UNKNOWN = Measure(0, 0L, known = false)
        }
    }

    /** Downloaded files of that time that are still there: the downloads' [ids] and their size together. */
    class Files(val ids: Set<Long>, val bytes: Long)

    /**
     * About what a page in history takes besides its address and title: its row's numbers and two
     * index entries (by time, and by address: so the address counts twice).
     */
    private const val HISTORY_ROW = 48L
    private const val CLOSED_TAB_ROW = 24L

    /** The cache is read for at most this many files or this long (a slow phone): the rest is worked out from those. */
    private const val MAX_ENTRIES = 3000
    private const val MAX_MS = 3000L

    /** Gecko's cache entries: their data comes in chunks of this size, each with a 2-byte hash in the metadata. */
    private const val CHUNK = 256L * 1024
    private const val MAX_KEY = 32 * 1024

    private val io = Background.single("clear-sizes")
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()

    /** Where the engine keeps its profile and its cache, found once. */
    @Volatile private var profiles: List<File>? = null
    @Volatile private var caches: List<File>? = null

    /** Stops the measuring going on: another time was chosen, or the sheet closed. */
    fun cancel() {
        generation.incrementAndGet()
    }

    /**
     * Works out what clearing [span] of [profileId] would take away. Each row's measure comes to
     * [onPart] on the main thread as soon as it is known (the lists first, the cache last), the
     * downloaded files of that time to [onFiles]. A new call or [cancel] stops the one before.
     * Call on the main thread.
     */
    fun measure(
        ctx: Context, profileId: Long, span: Clearing.Span,
        onPart: (Clearing.What, Measure) -> Unit, onFiles: (Files) -> Unit,
    ) {
        val app = ctx.applicationContext
        val gen = generation.incrementAndGet()
        val live = { generation.get() == gen }
        // Read here, on the main thread where they change.
        val withSettings = (SiteSettings.sites() + com.swiftbrowser.adblock.AdBlock.overrides().keys).toSet()
        val contextId = Profiles.contextId(profileId)
        val onlyProfile = Profiles.all().size <= 1
        io.execute {
            val started = SystemClock.elapsedRealtime()
            val posted = HashSet<Clearing.What>()
            fun post(w: Clearing.What, m: Measure) {
                posted += w
                main.post { if (live()) onPart(w, m) }
            }
            try {
                val db = SwiftApp.db
                val all = span.isAll
                val from = span.from
                val to = span.to
                val hosts = if (all) emptySet() else db.historyHostsSince(profileId, from, to)
                val sites = hosts.map { UrlUtils.siteOfHost(it) }.toSet()
                if (!live()) return@execute

                // The lists: quick counts.
                val entries = Downloads.entriesSince(from, to)
                post(Clearing.What.DOWNLOADS, Measure(entries.size))
                post(Clearing.What.DOCUMENTS, Measure(db.countDocuments(profileId, from, to)))
                post(Clearing.What.PLACES, Measure(db.countPositions(profileId, from, to)))
                val settings = if (all) withSettings.size else withSettings.count { s -> hosts.any { it == s || it.endsWith(".$s") } }
                post(Clearing.What.SITE_SETTINGS, Measure(settings))
                post(Clearing.What.PASSWORDS, Measure(db.countLogins(profileId, from, to)))

                // Finished downloads of that time whose files are still there.
                val kept = entries.filter { it.status == Downloads.DONE }.mapNotNull { item ->
                    File(item.filePath).takeIf { it.isFile }?.let { item.id to it.length() }
                }
                val files = Files(kept.map { it.first }.toSet(), kept.sumOf { it.second })
                main.post { if (live()) onFiles(files) }
                if (!live()) return@execute

                // History: its rows in the database, its site icons, and (all of it) the logs, which name sites.
                val pages = db.historyBytes(profileId, from, to)
                val closed = db.closedTabsBytes(profileId, from, to)
                var history = pages[1] * 2 + pages[2] + pages[0] * HISTORY_ROW + closed[1] + closed[0] * CLOSED_TAB_ROW
                history += Favicons.sizeSince(from, hosts, to)
                if (all) history += Storage.sizeOf(File(app.filesDir, "logs"))
                post(Clearing.What.HISTORY, Measure(pages[0].toInt(), history))
                if (!live()) return@execute

                val siteData = siteData(app, all, sites, contextId, onlyProfile, live) ?: return@execute
                post(Clearing.What.COOKIES, Measure(sites.size, siteData))

                val cache = cache(app, span, sites, live) ?: return@execute
                post(Clearing.What.CACHE, cache)
                L.d(
                    "Clear sizes worked out in ${SystemClock.elapsedRealtime() - started} ms: ${if (all) "all time" else "${sites.size} sites"}" +
                        (if (cache.about) ", cache in part" else ""),
                )
            } catch (e: Exception) {
                L.w("Couldn't work out what clearing takes", e)
                // Not "Working out…" for ever.
                for (w in Clearing.What.entries) if (w !in posted) post(w, Measure.UNKNOWN)
            }
        }
    }

    // ------------------------------------------------------------------ the engine's folders

    /**
     * The engine's profile folders (files/mozilla/<name>.default): those with site storage or
     * cookies. Looked for further down the app's files when not where expected.
     */
    private fun profileDirs(ctx: Context): List<File> {
        profiles?.let { return it }
        val isProfile = { d: File -> File(d, "storage").isDirectory || File(d, "cookies.sqlite").isFile }
        val found = File(ctx.filesDir, "mozilla").listFiles()?.filter { it.isDirectory && isProfile(it) }.orEmpty().toMutableList()
        if (found.isEmpty()) findDirs(ctx.filesDir, 3, found, isProfile)
        if (found.isNotEmpty()) profiles = found
        return found
    }

    /** The engine's disk caches ("cache2", with its "entries"): under the app's cache folder or in the profile. */
    private fun cacheDirs(ctx: Context): List<File> {
        caches?.let { return it }
        val isCache = { d: File -> d.name == "cache2" && File(d, "entries").isDirectory }
        val found = ArrayList<File>()
        findDirs(ctx.cacheDir, 4, found, isCache)
        for (p in profileDirs(ctx)) findDirs(p, 2, found, isCache)
        if (found.isNotEmpty()) caches = found
        return found
    }

    /** Folders under [root] (at most [depth] down, not inside one found) that [match]; Swift's own big folders are passed by. */
    private fun findDirs(root: File, depth: Int, found: MutableList<File>, match: (File) -> Boolean) {
        var level = listOf(root)
        var looked = 0
        for (d in 0 until depth) {
            val next = ArrayList<File>()
            for (dir in level) {
                val subs = dir.listFiles { f -> f.isDirectory && f.name !in PASS_BY } ?: continue
                for (s in subs) {
                    if (match(s)) {
                        if (found.none { it.absolutePath == s.absolutePath }) found += s
                    } else {
                        next += s
                    }
                }
                if (++looked > 300) return
            }
            level = next
        }
    }

    /** Swift's own folders and the engine's many-file ones, never where a profile or cache is. */
    private val PASS_BY = setOf("docs", "doc_thumbs", "gecko_temp", "gecko_temp_old", "favicons", "thumbs", "logs", "downloads", "entries", "storage", "news")

    // ------------------------------------------------------------------ cookies and site data

    /**
     * The size of the data sites stored that clearing takes: for a time, the folders of [sites]
     * (their subdomains, and what other sites stored while on them); for all time, every site's
     * folder of this profile (each profile's folders are marked with its context id), and the
     * cookies file when there is one profile. Null when stopped.
     */
    private fun siteData(ctx: Context, all: Boolean, sites: Set<String>, contextId: String, onlyProfile: Boolean, live: () -> Boolean): Long? {
        if (!all && sites.isEmpty()) return 0L
        var bytes = 0L
        // With one profile, every site's folder too: used if none is marked as its own (the engine
        // marking them in another way).
        var everySite = 0L
        var marked = false
        for (p in profileDirs(ctx)) {
            for (kind in listOf("default", "temporary")) {
                val origins = File(p, "storage/$kind").listFiles() ?: continue
                for (o in origins) {
                    if (!live()) return null
                    if (!o.isDirectory) continue
                    if (!all) {
                        if (originOf(o.name, sites)) bytes += Storage.sizeOf(o)
                        continue
                    }
                    if (!web(o.name)) continue
                    val mine = param(o.name, "geckoViewUserContextId") == contextId
                    if (!mine && !onlyProfile) continue
                    val size = Storage.sizeOf(o)
                    everySite += size
                    if (mine) {
                        bytes += size
                        marked = true
                    }
                }
            }
        }
        if (all && onlyProfile) {
            if (!marked) bytes = everySite
            // Cookies of all profiles share one file, so it only counts when there is one.
            for (p in profileDirs(ctx)) bytes += File(p, "cookies.sqlite").length() + File(p, "cookies.sqlite-wal").length()
        }
        return bytes
    }

    private fun web(folder: String) = folder.startsWith("http+++") || folder.startsWith("https+++")

    /** "https+++m.example.com+8080^…": a site of [sites], or stored for one (partitioned, "partitionKey=(https,example.com)"). */
    private fun originOf(folder: String, sites: Set<String>): Boolean {
        if (!web(folder)) return false
        val host = folder.substringBefore('^').substringAfter("+++").substringBefore('+')
        if (host.isNotEmpty() && UrlUtils.siteOfHost(host) in sites) return true
        return partitionSite(folder.substringAfter('^', ""))?.let { UrlUtils.siteOfHost(it) in sites } == true
    }

    /** One value of origin attributes ("geckoViewUserContextId=profile-1&partitionKey=…"), decoded. */
    private fun param(folder: String, name: String): String? =
        folder.substringAfter('^', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.let { decode(it) }

    /** The site of a partition key in origin attributes: "partitionKey=%28https%2Cexample.com%29" → example.com. */
    private fun partitionSite(attributes: String): String? {
        val at = attributes.indexOf("partitionKey=")
        if (at < 0) return null
        val value = decode(attributes.substring(at + 13).substringBefore('&'))
        return value.removePrefix("(").substringBefore(')').split(',').getOrNull(1)?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    /** %XX decoding, leaving "+" as it is (it isn't a space in these names). */
    private fun decode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.append(v.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ the cache

    /**
     * The cache that clearing takes. All of it: the engine's cache folders. For a time: the cached
     * files of [sites] (and those kept for them from other sites), read from each file's key; a
     * file whose key can't be read counts when it was written in [span]. Swift's document pictures
     * go whatever the time. Null when stopped.
     */
    private fun cache(ctx: Context, span: Clearing.Span, sites: Set<String>, live: () -> Boolean): Measure? {
        val thumbs = Storage.sizeOf(File(ctx.cacheDir, "doc_thumbs"))
        val dirs = cacheDirs(ctx)
        if (span.isAll) return Measure(0, dirs.sumOf { Storage.sizeOf(it) } + thumbs)
        if (sites.isEmpty()) return Measure(0, thumbs)
        val files = dirs.flatMap { File(it, "entries").listFiles()?.toList().orEmpty() }
        val started = SystemClock.elapsedRealtime()
        var read = 0
        var bytes = 0L
        for (f in files) {
            if (!live()) return null
            if (read >= MAX_ENTRIES || SystemClock.elapsedRealtime() - started > MAX_MS) break
            read++
            val length = f.length()
            val key = cacheKey(f, length)
            val goes = if (key != null) keyOf(key, sites) else f.lastModified() in span.from..span.to
            if (goes) bytes += length
        }
        // Stopped part-way: the rest are taken to be like those read (their names are hashes, so in no order).
        val part = read < files.size
        if (part && read > 0) bytes = bytes * files.size / read
        return Measure(0, bytes + thumbs, about = part)
    }

    /**
     * The key of a Gecko cache entry file, or null when it doesn't read as one. The file is the
     * data, then its metadata: a 4-byte hash, a 2-byte hash per 256 KB chunk of data, a header of
     * eight 4-byte numbers (the 7th is the key's length; version 1 has seven), the key and a 0
     * byte, the rest, and last the metadata's offset in 4 bytes. All numbers big-endian.
     */
    private fun cacheKey(f: File, length: Long): String? {
        if (length < 48) return null
        return try {
            RandomAccessFile(f, "r").use { r ->
                r.seek(length - 4)
                val meta = r.readInt().toLong() and 0xFFFFFFFFL
                val end = length - 4
                if (meta > end) return null
                val header = meta + 4 + (meta + CHUNK - 1) / CHUNK * 2
                if (header + 32 > end) return null
                r.seek(header)
                val version = r.readInt()
                if (version !in 1..16) return null
                r.seek(header + 24)
                val size = r.readInt()
                val keyAt = header + if (version == 1) 28 else 32
                if (size !in 1..MAX_KEY || keyAt + size + 1 > end) return null
                val bytes = ByteArray(size + 1)
                r.seek(keyAt)
                r.readFully(bytes)
                if (bytes[size] != 0.toByte()) return null
                String(bytes, 0, size, Charsets.ISO_8859_1)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether a cache key belongs to [sites]: "a,:https://img.example.com/x.png", or with other
     * tags before the address ("O^partitionKey=%28https%2Cexample.com%29,a,~1700000000,:https://…").
     * The address starts after the first ":" that follows a comma (or starts the key).
     */
    private fun keyOf(key: String, sites: Set<String>): Boolean {
        var colon = key.indexOf(':')
        while (colon > 0 && key[colon - 1] != ',') colon = key.indexOf(':', colon + 1)
        if (colon < 0) return false
        hostOf(key.substring(colon + 1))?.let { if (UrlUtils.siteOfHost(it) in sites) return true }
        val attributes = key.substring(0, colon).split(',').firstOrNull { it.startsWith("O^") } ?: return false
        return partitionSite(attributes)?.let { UrlUtils.siteOfHost(it) in sites } == true
    }

    /** The host of an address, quickly (thousands of keys): "https://user@img.example.com:8080/x" → img.example.com. */
    private fun hostOf(url: String): String? {
        val scheme = url.indexOf("://")
        if (scheme < 0) return null
        var end = url.length
        for (i in scheme + 3 until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        val authority = url.substring(scheme + 3, end).substringAfterLast('@')
        if (authority.startsWith("[")) return authority.substringBefore(']') + "]"
        return authority.substringBefore(':').lowercase(Locale.ROOT).trimEnd('.').takeIf { it.isNotEmpty() }
    }
}
