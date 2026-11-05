package com.swiftbrowser.files

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.swiftbrowser.core.UrlUtils
import java.io.File

/**
 * 3.0.4: how much each folder holds, for Files' lists ("12 items · 1.40 GB") and for sorting by
 * size. Measured by walking the folder off the main thread: Android's media index (FileIndex) leaves
 * out hidden files, Android/data, Private and whatever apps wrote without telling it, so its sums
 * would be wrong for the very folders people want measured. Links are left out (one pointing back
 * up would go round for ever). A walk stops after [MAX_MS] or [MAX_ENTRIES] things, and its size
 * then shows as "over 1.40 GB". Sizes are kept by path, measured again after [FRESH_MS], and
 * forgotten at once when a job (copy, move, delete, the recycle bin…) changes something in the
 * folder or below it.
 */
object FolderSizes {
    /** A walk looks at this many files and folders at most… */
    const val MAX_ENTRIES = 20_000

    /** …for this long at most. */
    const val MAX_MS = 2_000L

    /** A size this old is measured again (it still shows meanwhile). */
    const val FRESH_MS = 10 * 60_000L

    /** Folders this deep below the one measured aren't gone into (no real folder is). */
    private const val MAX_DEPTH = 64

    /** Sizes kept at most (the least used go first): a few hundred KB at most. */
    private const val MAX_KEPT = 1_500

    /** The last changes remembered, to tell whether one reached a folder being walked meanwhile. */
    private const val RECENT_CHANGES = 64

    /** A folder's size: [bytes] in [files] files; all of it when [complete], otherwise at least that. */
    class Size(val bytes: Long, val files: Int, val complete: Boolean)

    /** What a walk sees at a path, without following a link: a folder, a plain file, or neither (a link). */
    class Node(val dir: Boolean, val file: Boolean, val bytes: Long)

    /** The disk as a walk reads it: the phone's, or a test's. */
    interface Disk {
        /** The names in [dir]; null when it can't be read. */
        fun names(dir: String): Array<String>?

        /** What [path] is; null when it is gone. */
        fun node(path: String): Node?
    }

    /**
     * Walks [root] on [disk], adding up its files' bytes (links and other odd things left out).
     * Stops once [maxEntries] things were looked at or [maxMs] went by on [clock]: the size is then
     * not complete. Null when [stop] says so (the list on show changed): nothing of it is kept.
     * [found] hears of each folder right under [root] once all of it is counted (the next ones
     * opened, most likely). A root that can't be read is an empty, incomplete size.
     */
    fun walk(
        root: String, disk: Disk, clock: () -> Long, stop: () -> Boolean,
        maxEntries: Int = MAX_ENTRIES, maxMs: Long = MAX_MS, found: ((String, Size) -> Unit)? = null,
    ): Size? {
        val w = Walk(disk, maxEntries, clock() + maxMs, clock, stop, found)
        val sum = Sum()
        w.into(root, 0, sum)
        if (w.stopped) return null
        return Size(sum.bytes, sum.files, sum.whole)
    }

    private class Sum {
        var bytes = 0L
        var files = 0
        var whole = true
    }

    private class Walk(
        val disk: Disk, val maxEntries: Int, val deadline: Long, val clock: () -> Long,
        val stop: () -> Boolean, val found: ((String, Size) -> Unit)?,
    ) {
        var entries = 0
        var cut = false
        var stopped = false

        fun into(dir: String, depth: Int, sum: Sum) {
            val names = disk.names(dir)
            if (names == null) {
                // A folder that can't be read: what is around it is "at least" so much.
                sum.whole = false
                return
            }
            for (name in names) {
                if (stop()) {
                    stopped = true
                    return
                }
                if (entries >= maxEntries || clock() >= deadline) {
                    cut = true
                    sum.whole = false
                    return
                }
                entries++
                val path = if (dir.endsWith("/")) dir + name else "$dir/$name"
                val n = disk.node(path) ?: continue
                when {
                    n.dir -> {
                        if (depth + 1 >= MAX_DEPTH) {
                            sum.whole = false
                            continue
                        }
                        val sub = Sum()
                        into(path, depth + 1, sub)
                        if (stopped) return
                        sum.bytes += sub.bytes
                        sum.files += sub.files
                        if (!sub.whole) sum.whole = false else if (depth == 0) found?.invoke(path, Size(sub.bytes, sub.files, true))
                        if (cut) return
                    }
                    n.file -> {
                        sum.bytes += n.bytes.coerceAtLeast(0)
                        sum.files++
                    }
                }
            }
        }
    }

    /** "1.40 GB", or "over 1.40 GB" when the walk stopped short; null when nothing can be said. */
    fun text(s: Size): String? = when {
        s.complete -> UrlUtils.formatBytes(s.bytes)
        s.bytes > 0 -> "over " + UrlUtils.formatBytes(s.bytes)
        else -> null
    }

    // ------------------------------------------------------------------ kept sizes

    private class Kept(val size: Size, val at: Long)

    private val kept = object : LinkedHashMap<String, Kept>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > MAX_KEPT
    }

    /** Goes up with each change (see [changed]); the last ones, with the folders they named, in [recent]. */
    private var changes = 0L
    private val recent = ArrayDeque<Pair<Long, List<String>>>()

    /** [path]'s size as last measured, however long ago (it shows until measured again). */
    @Synchronized
    fun cached(path: String): Size? = kept[path]?.size

    /** The bytes of those of [paths] measured, as they are now: a sort's sizes mustn't change while it sorts. */
    @Synchronized
    fun bytesFor(paths: Collection<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (p in paths) kept[p]?.let { out[p] = it.size.bytes }
        return out
    }

    /** Whether [path]'s size is known and was measured less than [FRESH_MS] before [now] (an elapsed-time clock). */
    @Synchronized
    fun fresh(path: String, now: Long = SystemClock.elapsedRealtime()): Boolean =
        kept[path]?.let { now - it.at in 0 until FRESH_MS } == true

    /**
     * Keeps [size] for [path], measured at [at], unless a change since the walk began ([since], from
     * [mark]) reached that folder (one elsewhere doesn't matter). False when it wasn't kept.
     */
    @Synchronized
    fun keep(path: String, size: Size, at: Long, since: Long = changes): Boolean {
        if (changedSince(path, since)) return false
        kept[path] = Kept(size, at)
        return true
    }

    /** Whether a change after [since] reached [path] (or more came than are remembered, so it can't be told). */
    private fun changedSince(path: String, since: Long): Boolean {
        if (since >= changes) return false
        val oldest = recent.firstOrNull()?.first ?: return true
        if (oldest > since + 1) return true
        return recent.any { (n, paths) -> n > since && paths.any { affects(it, path) } }
    }

    /** How many changes there were so far, for [keep]. */
    @Synchronized
    fun mark(): Long = changes

    /**
     * Something changed in each of [paths] (folders a job wrote to or took from): their sizes, those
     * of the folders above them and those of the folders inside them go, to be measured again.
     */
    fun changed(paths: Collection<String>) {
        val all = paths.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
        if (all.isEmpty()) return
        synchronized(this) {
            changes++
            recent.addLast(changes to all)
            while (recent.size > RECENT_CHANGES) recent.removeFirst()
            kept.keys.removeAll { k -> all.any { affects(it, k) } }
        }
    }

    /** Whether a change in [changed] alters [folder]'s size: it is that folder, one above it, or one inside it. */
    fun affects(changed: String, folder: String): Boolean = folder == changed || under(changed, folder) || under(folder, changed)

    /** [path] is inside [dir] (no new strings: this runs for every size kept, for each change). */
    private fun under(path: String, dir: String) = path.length > dir.length && path[dir.length] == '/' && path.startsWith(dir)

    /** Forgets every size. */
    @Synchronized
    fun clear() = kept.clear()

    /**
     * Measures [dir] on the phone's disk (never on the main thread), keeping its size and those of
     * the folders right in it; null when [stop] said so, or something in it changed meanwhile.
     */
    fun measure(dir: File, stop: () -> Boolean): Size? {
        val since = mark()
        val s = walk(dir.path, PhoneDisk, { SystemClock.uptimeMillis() }, stop) { path, sub ->
            keep(path, sub, SystemClock.elapsedRealtime(), since)
        } ?: return null
        return s.takeIf { keep(dir.path, s, SystemClock.elapsedRealtime(), since) }
    }

    /** The phone's disk: one lstat a name tells its kind and size at once, without following a link. */
    private object PhoneDisk : Disk {
        override fun names(dir: String): Array<String>? = File(dir).list()

        override fun node(path: String): Node? = try {
            val st = Os.lstat(path)
            Node(OsConstants.S_ISDIR(st.st_mode), OsConstants.S_ISREG(st.st_mode), st.st_size)
        } catch (_: Exception) {
            null
        }
    }
}
