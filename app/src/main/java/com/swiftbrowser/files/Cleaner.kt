package com.swiftbrowser.files

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * "Free up space" (2.9): one walk through the phone's storage and SD cards finds big files, files
 * that are there twice, app files (.apk) already installed or left behind, empty folders, and
 * Swift's own leftovers. Apps' private folders (Android/data, Android/obb), hidden folders and the
 * recycle bin are left alone. Twins are found by size first, then by a short sample of their
 * content, and only then read whole (small files under 100 KB aren't worth it).
 */
object Cleaner {
    /** The lists, with what each says and whether its things are ticked to start with. */
    enum class Kind(val title: String, val icon: Int, val hint: String, val nothing: String) {
        LARGE("Large files", R.drawable.ic_files_large, "Files over 50 MB, biggest first. Tick the ones you no longer need.", "No files over 50 MB"),
        DUPLICATES(
            "Duplicate files", R.drawable.ic_copy,
            "Files that are there more than once. One copy of each always stays: the one in your camera or Pictures folder, otherwise the newest.",
            "No duplicate files",
        ),
        APKS("App files", R.drawable.ic_apk, "Installer files (.apk). Once an app is installed, its file is no longer needed: those are ticked.", "No app files"),
        EMPTY("Empty folders", R.drawable.ic_folder, "Folders with nothing in them.", "No empty folders"),
        LEFTOVERS("Swift's leftovers", R.drawable.ic_files_clean, "Pictures and copies Swift keeps for a while, and unfinished copies. Swift makes them again when needed.", "Nothing left over"),
    }

    /** One thing found: [ticked] is what the user chose; [gone] once it was deleted. */
    class Found(
        val file: File,
        val size: Long,
        val time: Long,
        val isDir: Boolean,
        /** A word about it ("Installed", "Kept: the newest copy"). */
        val note: String?,
        /** The group of twins it belongs to (duplicates), otherwise -1. */
        val group: Int,
        var ticked: Boolean,
        /** 3.0: the copy of its twins meant to stay (duplicates). */
        val keeper: Boolean = false,
    ) {
        var gone = false
        val kind: FileKind get() = FileKind.of(file.name, isDir)
    }

    class Results(val lists: Map<Kind, List<Found>>) {
        fun of(k: Kind): List<Found> = lists[k].orEmpty().filter { !it.gone }
    }

    /** How far the walk is, read by the screen a few times a second. */
    class Progress {
        @Volatile
        var stage = "Looking through your files…"

        @Volatile
        var files = 0

        /** 0..1 while comparing files, otherwise -1. */
        @Volatile
        var fraction = -1f
    }

    const val LARGE_BYTES = 50L * 1_048_576
    private const val TWIN_MIN_BYTES = 100L * 1024
    private const val SAMPLE = 16 * 1024

    /** Folders at the top of a storage that apps and Android expect to be there, even empty. */
    private val STANDARD = setOf(
        "alarms", "android", "audiobooks", "dcim", "documents", "download", "movies", "music",
        "notifications", "pictures", "podcasts", "recordings", "ringtones",
    )

    /** libtorrent's file of a torrent's pieces that belong to files not chosen (".<info hash>.parts"). */
    private val TORRENT_PARTS = Regex("""\.([0-9a-fA-F]{40})\.parts""")

    /** Folders the camera and screenshots write to, wherever they are: kept even when empty. */
    private val KEEP = setOf("camera", "screenshots")

    fun scan(ctx: Context, progress: Progress, stop: () -> Boolean): Results {
        val large = ArrayList<Found>()
        val apks = ArrayList<File>()
        val empty = ArrayList<Found>()
        val leftovers = ArrayList<Found>()
        val bySize = HashMap<Long, MutableList<File>>()
        val visited = HashSet<String>()
        // 3.0: while a file job runs, its unfinished copies are being written: none is listed then.
        val jobsIdle = FileJobs.active.none { it.kind != FileJobs.Kind.OPEN }
        val dayAgo = System.currentTimeMillis() - DAY

        fun visitFile(f: File) {
            val name = f.name
            if (name.startsWith(".")) {
                // Unfinished copies Swift left (a copy cut off, an upload cut off). 3.0: only ones a day
                // old or more, and not one being written now.
                if (Parts.isPart(name) && jobsIdle && f.lastModified() < dayAgo && !Parts.isLive(f)) {
                    leftovers += Found(f, f.length(), f.lastModified(), false, "An unfinished copy", -1, true)
                }
                // 2.9.2: pieces a torrent kept for files that weren't chosen, once it is finished or gone.
                TORRENT_PARTS.matchEntire(name)?.let { m ->
                    val item = com.swiftbrowser.download.Downloads.findTorrent(m.groupValues[1])
                    if (item == null || item.status == com.swiftbrowser.download.Downloads.DONE) {
                        leftovers += Found(f, f.length(), f.lastModified(), false, "Left over from a torrent", -1, true)
                    }
                }
                return
            }
            progress.files++
            val size = f.length()
            if (size >= LARGE_BYTES) large += Found(f, size, f.lastModified(), false, null, -1, false)
            if (FileKind.extension(name) == "apk") apks += f
            if (size >= TWIN_MIN_BYTES) bySize.getOrPut(size) { ArrayList(2) } += f
        }

        /** Walks [dir]; true when something is in it (a file anywhere below, or what isn't walked). */
        fun walk(dir: File, depth: Int, top: Boolean): Boolean {
            if (stop()) return true
            val children = dir.listFiles() ?: return true
            var content = false
            val emptyKids = ArrayList<File>()
            for (c in children) {
                if (stop()) return true
                if (c.isDirectory) {
                    val skip = c.name.startsWith(".") || (top && c.name == "Android") || depth > 24 ||
                        !visited.add(runCatching { c.canonicalPath }.getOrDefault(c.path))
                    if (skip) {
                        content = true
                        continue
                    }
                    if (walk(c, depth + 1, false)) content = true else emptyKids += c
                } else {
                    content = true
                    visitFile(c)
                }
            }
            // The topmost empty folders are listed (one with only empty folders in it counts as empty);
            // at a storage's top the usual folders stay, and the empty ones inside them are listed instead.
            if (content || top) {
                for (e in emptyKids) {
                    if (top && e.name.lowercase() in STANDARD) {
                        e.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name.lowercase() !in KEEP }
                            ?.forEach { empty += Found(it, 0, it.lastModified(), true, null, -1, true) }
                    } else if (e.name.lowercase() !in KEEP) {
                        empty += Found(e, 0, e.lastModified(), true, null, -1, true)
                    }
                }
            }
            return content
        }

        for (v in Volumes.refresh(ctx)) {
            if (stop()) break
            walk(v.root, 0, true)
        }

        // Swift's own: pictures of documents, files opened from archives, copies made for sharing, its cache on the cards.
        progress.stage = "Looking at Swift's own files…"
        val own = listOf(
            File(ctx.cacheDir, "doc_thumbs") to "Pictures of documents",
            File(ctx.cacheDir, "docs/archive") to "Files opened from archives",
            File(ctx.cacheDir, "docs/share") to "Copies made for sharing",
            File(ctx.cacheDir, "torrent-fetch") to "Torrent file lists",
        ) + ctx.externalCacheDirs.filterNotNull().map { it to "Swift's cache on " + (if (Volumes.isCard(ctx, it)) "the SD card" else "the phone's storage") }
        for ((dir, label) in own) {
            if (!dir.isDirectory) continue
            val size = FileOps.measure(listOf(dir)).bytes
            if (size > 0) leftovers += Found(dir, size, dir.lastModified(), true, label, -1, true)
        }

        val duplicates = if (stop()) emptyList() else twins(bySize, progress, stop)
        progress.fraction = -1f
        progress.stage = "Looking at app files…"
        val apkList = if (stop()) emptyList() else apks.map { describeApk(ctx, it) }

        L.d("Cleaner: ${progress.files} files, ${large.size} large, ${duplicates.size} duplicates, ${apkList.size} app files, ${empty.size} empty folders")
        return Results(
            mapOf(
                Kind.LARGE to large.sortedByDescending { it.size },
                Kind.DUPLICATES to duplicates,
                Kind.APKS to apkList.sortedByDescending { it.size },
                Kind.EMPTY to empty.sortedBy { it.file.path.lowercase() },
                Kind.LEFTOVERS to leftovers.sortedByDescending { it.size },
            ),
        )
    }

    /** Files with the same content, in groups (biggest first): all ticked but the newest of each. */
    private fun twins(bySize: Map<Long, List<File>>, progress: Progress, stop: () -> Boolean): List<Found> {
        val candidates = bySize.values.filter { it.size > 1 }
        if (candidates.isEmpty()) return emptyList()
        progress.stage = "Comparing files…"
        progress.fraction = 0f
        // A sample from the start, middle and end tells most apart without reading them whole.
        val sampled = ArrayList<List<File>>()
        for (group in candidates) {
            if (stop()) return emptyList()
            group.groupBy { sample(it) }.forEach { (k, same) -> if (k != null && same.size > 1) sampled += same }
        }
        val total = sampled.sumOf { g -> g.sumOf { it.length() } }.coerceAtLeast(1)
        var read = 0L
        val out = ArrayList<List<File>>()
        for (group in sampled) {
            if (stop()) return emptyList()
            val byHash = group.groupBy { f ->
                digest(f, stop) { n ->
                    read += n
                    progress.fraction = (read.toFloat() / total).coerceIn(0f, 1f)
                }
            }
            byHash.forEach { (k, same) -> if (k != null && same.size > 1) out += same }
        }
        val found = ArrayList<Found>()
        out.sortedByDescending { it[0].length() }.forEachIndexed { i, group ->
            val sorted = group.sortedByDescending { it.lastModified() }
            val keep = keeperOf(sorted.map { it.path })
            sorted.forEachIndexed { j, f ->
                val kept = j == keep
                found += Found(f, f.length(), f.lastModified(), false, if (kept) keptNote(f.path) else null, i, !kept, keeper = kept)
            }
        }
        return found
    }

    // ------------------------------------------------------------------ twins (3.0)

    /** How much a copy at [path] is worth keeping: the camera's above Pictures, both above the rest, Download last. */
    private fun keepScore(path: String): Int {
        val p = path.lowercase(java.util.Locale.ROOT)
        return when {
            "/dcim/" in p -> 3
            "/pictures/" in p -> 2
            "/download/" in p || "/downloads/" in p -> 0
            else -> 1
        }
    }

    /** 3.0: which of [paths] (newest first) stays: one in the camera's folder or Pictures before one in Download; otherwise the newest. */
    fun keeperOf(paths: List<String>): Int {
        if (paths.isEmpty()) return -1
        var best = 0
        for (i in paths.indices) if (keepScore(paths[i]) > keepScore(paths[best])) best = i
        return best
    }

    private fun keptNote(path: String) = when (keepScore(path)) {
        3 -> "Kept: the copy in your camera's folder"
        2 -> "Kept: the copy in Pictures"
        else -> "Kept: the newest copy"
    }

    /** 3.0: the twins of [f] still there (it among them). */
    fun twinsOf(all: List<Found>, f: Found): List<Found> = all.filter { it.group == f.group && !it.gone }

    /** 3.0: whether [f] may be ticked: one copy of its twins always stays unticked. */
    fun canTick(all: List<Found>, f: Found): Boolean = f.group < 0 || twinsOf(all, f).any { it !== f && !it.ticked }

    /** 3.0: the copy of a group that stays: the one chosen to, while it is there, otherwise the best of the rest. */
    private fun keeperIn(group: List<Found>): Found? =
        group.firstOrNull { it.keeper } ?: group.sortedByDescending { it.time }.let { g -> g.getOrNull(keeperOf(g.map { it.file.path })) }

    /**
     * 3.0: twins as they may be: a group with one copy left (the others deleted) is no group, and
     * nothing of it is ticked; a group never has every copy ticked (the one meant to stay is unticked).
     */
    fun tidyTwins(all: List<Found>) {
        for ((_, group) in all.filter { it.group >= 0 && !it.gone }.groupBy { it.group }) {
            if (group.size < 2) {
                group.forEach { it.ticked = false }
            } else if (group.all { it.ticked }) {
                keeperIn(group)?.ticked = false
            }
        }
    }

    /** 3.0: "Tick all" for twins: every copy but the one meant to stay, in each group of two or more. */
    fun tickAllTwins(all: List<Found>) {
        for ((_, group) in all.filter { it.group >= 0 && !it.gone }.groupBy { it.group }) {
            val keep = if (group.size < 2) null else keeperIn(group)
            group.forEach { it.ticked = group.size >= 2 && it !== keep }
        }
    }

    /** 3.0: how many twins can be ticked at most (all but one of each group), and what they take. */
    fun tickableTwins(all: List<Found>): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        for ((_, group) in all.filter { it.group >= 0 && !it.gone }.groupBy { it.group }) {
            if (group.size < 2) continue
            n += group.size - 1
            bytes += group[0].size * (group.size - 1)
        }
        return n to bytes
    }

    private fun sample(f: File): String? = try {
        RandomAccessFile(f, "r").use { raf ->
            val md = MessageDigest.getInstance("MD5")
            val len = raf.length()
            val buf = ByteArray(SAMPLE)
            for (at in longArrayOf(0L, len / 2, (len - SAMPLE).coerceAtLeast(0L))) {
                raf.seek(at)
                val n = raf.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        }
    } catch (_: Exception) {
        null
    }

    private fun digest(f: File, stop: () -> Boolean, read: (Int) -> Unit): String? = try {
        f.inputStream().use { input ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(FileJobs.BUFFER)
            while (true) {
                if (stop()) return null
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                read(n)
            }
            md.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * An app file: its app and version, and whether that (or newer) is installed. 3.0: only those are
     * ticked (one not installed, an update not installed yet, or one that can't be read may still be wanted).
     */
    private fun describeApk(ctx: Context, f: File): Found {
        val pm = ctx.packageManager
        var installedAlready = false
        val note = try {
            val info = pm.getPackageArchiveInfo(f.path, 0)
            if (info == null) {
                "Can't be read: damaged or not finished"
            } else {
                info.applicationInfo?.let {
                    it.sourceDir = f.path
                    it.publicSourceDir = f.path
                }
                val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                val version = info.versionName?.takeIf { it.isNotBlank() }
                val app = listOfNotNull(label, version).joinToString(" ")
                val installed = try {
                    pm.getPackageInfo(info.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                installedAlready = installed != null && versionOf(installed) >= versionOf(info)
                val state = when {
                    installed == null -> "Not installed"
                    installedAlready -> "Installed (this version or newer)"
                    else -> "An older version is installed"
                }
                if (app.isEmpty()) state else "$app · $state"
            }
        } catch (_: Exception) {
            "Can't be read"
        }
        return Found(f, f.length(), f.lastModified(), false, note, -1, installedAlready)
    }

    private const val DAY = 24L * 3600 * 1000

    private fun versionOf(p: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
}
