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
        DUPLICATES("Duplicate files", R.drawable.ic_copy, "Files that are there more than once. The newest copy of each is kept unticked.", "No duplicate files"),
        APKS("App files", R.drawable.ic_apk, "Installer files (.apk). Once an app is installed, its file is no longer needed.", "No app files"),
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

    /** Folders the camera and screenshots write to, wherever they are: kept even when empty. */
    private val KEEP = setOf("camera", "screenshots")

    fun scan(ctx: Context, progress: Progress, stop: () -> Boolean): Results {
        val large = ArrayList<Found>()
        val apks = ArrayList<File>()
        val empty = ArrayList<Found>()
        val leftovers = ArrayList<Found>()
        val bySize = HashMap<Long, MutableList<File>>()
        val visited = HashSet<String>()

        fun visitFile(f: File) {
            val name = f.name
            if (name.startsWith(".")) {
                // Unfinished copies Swift left (a copy that failed while replacing a file, an upload cut off).
                if (name.startsWith(".swift-") && name.endsWith(".part")) leftovers += Found(f, f.length(), f.lastModified(), false, "An unfinished copy", -1, true)
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
            val newest = group.maxByOrNull { it.lastModified() }
            for (f in group.sortedByDescending { it.lastModified() }) {
                val keep = f === newest
                found += Found(f, f.length(), f.lastModified(), false, if (keep) "The newest copy: kept" else null, i, !keep)
            }
        }
        return found
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

    /** An app file: its app and version, and whether that (or newer) is installed. All are ticked. */
    private fun describeApk(ctx: Context, f: File): Found {
        val pm = ctx.packageManager
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
                val state = when {
                    installed == null -> "Not installed"
                    versionOf(installed) >= versionOf(info) -> "Installed (this version or newer)"
                    else -> "An older version is installed"
                }
                if (app.isEmpty()) state else "$app · $state"
            }
        } catch (_: Exception) {
            "Can't be read"
        }
        return Found(f, f.length(), f.lastModified(), false, note, -1, true)
    }

    private fun versionOf(p: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
}
