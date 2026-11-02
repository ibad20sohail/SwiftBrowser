package com.swiftbrowser.files

import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import com.swiftbrowser.R
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import java.io.File
import java.util.Locale

/** What a file is, for its icon, its picture and how it opens (2.7). */
enum class FileKind(val icon: Int, val label: String) {
    FOLDER(R.drawable.ic_folder, "Folder"),
    ARCHIVE(R.drawable.ic_archive, "Archive"),
    IMAGE(R.drawable.ic_image, "Picture"),
    VIDEO(R.drawable.ic_movie, "Video"),
    AUDIO(R.drawable.ic_headphones, "Sound"),
    DOCUMENT(R.drawable.ic_documents, "Document"),
    APK(R.drawable.ic_apk, "App to install"),
    WEB(R.drawable.ic_globe, "Web page"),
    OTHER(R.drawable.ic_file, "File");

    companion object {
        private val IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "heic", "heif")
        private val VIDEOS = setOf("mp4", "m4v", "webm", "ogv", "3gp", "3g2", "mov", "mkv", "avi", "flv", "wmv", "ts", "mpg", "mpeg")
        private val AUDIOS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba", "amr", "mid", "midi", "wma")
        private val WEB_PAGES = setOf("html", "htm", "xhtml", "mht", "mhtml")

        fun of(name: String, dir: Boolean = false): FileKind {
            if (dir) return FOLDER
            val ext = extension(name)
            return when {
                Archives.formatOf(name) != null -> ARCHIVE
                ext in IMAGES -> IMAGE
                ext in VIDEOS -> VIDEO
                ext in AUDIOS -> AUDIO
                ext == "apk" -> APK
                Docs.kindOf(name, null) != null -> DOCUMENT
                ext in WEB_PAGES -> WEB
                else -> OTHER
            }
        }

        fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { if (it == name.lowercase(Locale.ROOT)) "" else it }

        /** Pictures, videos and sounds Firefox's engine plays itself (the rest open in another app). */
        private val IN_SWIFT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif",
            "mp4", "m4v", "webm", "ogv", "3gp", "mov",
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba",
            "html", "htm", "xhtml",
        )

        /** Whether a tab can show the file (a picture, video, sound or web page). */
        fun showsInTab(name: String) = extension(name) in IN_SWIFT
    }
}

/** One file or folder in a list (2.7), read once off the main thread. */
class FileItem(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val time: Long,
    /** A folder's number of items (-1: not counted). */
    var count: Int,
    val kind: FileKind,
    /** Where a search found it, relative to the folder searched. */
    val where: String? = null,
) {
    val hidden: Boolean get() = name.startsWith(".")

    companion object {
        fun of(f: File, count: Boolean = false, where: String? = null): FileItem {
            val dir = f.isDirectory
            return FileItem(f, f.name, dir, if (dir) 0L else f.length(), f.lastModified(), if (dir && count) (f.list()?.size ?: -1) else -1, FileKind.of(f.name, dir), where)
        }
    }
}

/** Listing, sorting, searching and naming files (2.7). */
object FileOps {
    const val SORT_NAME = 0
    const val SORT_DATE = 1
    const val SORT_SIZE = 2
    const val SORT_TYPE = 3

    val sortLabels = listOf("Name", "Date (newest first)", "Size (biggest first)", "Type")

    /** Most folders hold fewer; past this many sub-folders their item counts aren't read (slow on a busy card). */
    private const val MAX_COUNTED = 400

    /** What [dir] holds, folders first, sorted. Null when it can't be read. */
    fun list(dir: File, showHidden: Boolean, sort: Int): List<FileItem>? {
        val files = dir.listFiles() ?: return null
        val shown = if (showHidden) files.asList() else files.filter { !it.name.startsWith(".") }
        val count = shown.count { it.isDirectory } <= MAX_COUNTED
        return sorted(shown.map { FileItem.of(it, count) }, sort)
    }

    fun sorted(items: List<FileItem>, sort: Int): List<FileItem> {
        val byName = Comparator<FileItem> { a, b -> naturalCompare(a.name, b.name) }
        val inside: Comparator<FileItem> = when (sort) {
            SORT_DATE -> compareByDescending<FileItem> { it.time }.then(byName)
            SORT_SIZE -> Comparator<FileItem> { a, b ->
                if (a.isDir && b.isDir) naturalCompare(a.name, b.name) else b.size.compareTo(a.size).takeIf { it != 0 } ?: naturalCompare(a.name, b.name)
            }
            SORT_TYPE -> compareBy<FileItem> { FileKind.extension(it.name) }.then(byName)
            else -> byName
        }
        return items.sortedWith(compareBy<FileItem> { !it.isDir }.then(inside))
    }

    /** Names in the order people expect: "photo 2" before "photo 10", capitals or not. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * Files and folders under [dir] whose name holds [query], nearest first, at most [max]. Stops
     * when [stop] says so (a newer search, the screen closed).
     */
    fun search(dir: File, query: String, showHidden: Boolean, max: Int = 300, stop: () -> Boolean): List<FileItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        val queue = ArrayDeque<File>()
        queue.add(dir)
        var visited = 0
        while (queue.isNotEmpty() && out.size < max && visited < 50_000) {
            if (stop()) break
            val d = queue.removeFirst()
            val children = d.listFiles() ?: continue
            for (c in children) {
                visited++
                if (!showHidden && c.name.startsWith(".")) continue
                val isDir = c.isDirectory
                if (c.name.lowercase(Locale.ROOT).contains(q)) {
                    out += FileItem.of(c, where = relative(dir, c.parentFile))
                    if (out.size >= max) break
                }
                if (isDir) queue.add(c)
            }
        }
        return out
    }

    /** [f] as seen from [base] ("Download › Swift"), or "" when it is [base]. */
    fun relative(base: File, f: File?): String {
        f ?: return ""
        if (f.path == base.path) return ""
        return f.path.removePrefix(base.path).trimStart('/').replace("/", " › ")
    }

    /** Totals of files and folders under [files] (folders summed), stopping when [stop] says so. */
    class Totals {
        var files = 0
        var folders = 0
        var bytes = 0L

        /** 3.0: the biggest single file (a FAT32 card can't take one over 4 GB). */
        var biggest = 0L
    }

    fun measure(files: Collection<File>, stop: () -> Boolean = { false }, progress: ((Totals) -> Unit)? = null): Totals {
        val t = Totals()
        var last = System.currentTimeMillis()
        fun walk(f: File) {
            if (stop()) return
            if (f.isDirectory) {
                t.folders++
                f.listFiles()?.forEach { walk(it) }
            } else {
                t.files++
                val n = f.length()
                t.bytes += n
                if (n > t.biggest) t.biggest = n
            }
            if (progress != null && System.currentTimeMillis() - last > 250) {
                last = System.currentTimeMillis()
                progress(t)
            }
        }
        files.forEach { walk(it) }
        return t
    }

    /**
     * "photo (1).jpg", "photo (2).jpg"…: a name not taken in [f]'s folder. 3.0: [isDir] is what will
     * have the name (a file keeps its ending even where a folder of that name is in the way).
     */
    fun freeName(f: File, isDir: Boolean = f.isDirectory): File {
        if (!f.exists()) return f
        val dir = f.parentFile
        val name = f.name
        val ext = if (isDir) "" else FileKind.extension(name).let { if (it.isEmpty()) "" else ".$it" }
        val base = name.substring(0, name.length - ext.length).replace(Regex(" \\(\\d+\\)$"), "")
        var n = 1
        while (true) {
            val c = File(dir, "$base ($n)$ext")
            if (!c.exists()) return c
            n++
        }
    }

    /** Characters a memory card (FAT) or Windows can't have in a name, and the ones that end a path. */
    private val BAD_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** Invisible characters that turn text around or hide it ("report‮fdp.apk" showing as "reportkpa.pdf"). */
    private val HIDDEN_CHARS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    private const val MAX_NAME = 120

    /**
     * A name a user typed, as a safe file name ("" when nothing is left). 3.0: Files' own cleaning,
     * which keeps a dot at the start (".nomedia", hidden folders): the download names' cleaning takes
     * it off. Characters a memory card can't have become "_", invisible ones that turn text around
     * go, and spaces and dots at the end go (memory cards and Windows drop those themselves).
     */
    fun cleanName(input: String): String {
        val t = input.replace(HIDDEN_CHARS, "").replace(BAD_CHARS, "_").trim().trimEnd('.', ' ')
        if (t.isEmpty() || t.all { it == '.' }) return ""
        if (t.length <= MAX_NAME) return t
        // Too long: shortened before the ending, which stays.
        val dot = t.lastIndexOf('.')
        val ext = if (dot > 0) t.substring(dot + 1).takeIf { it.length in 1..10 && ' ' !in it } else null
        return if (ext == null) t.take(MAX_NAME) else t.take(MAX_NAME - 1 - ext.length).trimEnd() + "." + ext
    }

    /** Whether [f] is [dir] or inside it. */
    fun isInside(f: File, dir: File): Boolean {
        val a = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val b = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return a == b || a.startsWith(b + File.separator)
    }

    /**
     * 3.0: whether [a] and [b] are one file: the same path, or the same name in other capitals on a
     * storage that ignores capitals (the phone's storage, memory cards). On one that doesn't (Swift's
     * own, where Private is), "photo.jpg" and "Photo.jpg" can be two files: their folder lists both.
     */
    fun sameFile(a: File, b: File): Boolean {
        if (a.path == b.path) return true
        if (a.parentFile?.path != b.parentFile?.path || !a.name.equals(b.name, ignoreCase = true)) return false
        val names = a.parentFile?.list() ?: return false
        return sameByNames(a.name, b.name, names.asList())
    }

    /** [a] and [b] (the same but for capitals) are one file unless their folder's [names] has both. */
    fun sameByNames(a: String, b: String, names: List<String>): Boolean =
        a.equals(b, ignoreCase = true) && !(a != b && a in names && b in names)

    fun mimeOf(name: String): String = UrlUtils.mimeFromName(name) ?: "*/*"

    // ------------------------------------------------------------------ the phone's media index

    /** 3.0: the media index is told on a thread of its own, a batch at a time. */
    private val scanner = Background.single("media-scan")

    /** At most this many files are handed to the media index at once. */
    private const val SCAN_BATCH = 500

    /**
     * New or changed files show in the Gallery and music apps. 3.0: every one of them (moving the
     * camera's 2,000 photos used to bring back only the first 500), [SCAN_BATCH] at a time, each batch
     * waiting for the one before, off the main thread; never Private's or Swift's own files.
     */
    fun scan(ctx: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val app = ctx.applicationContext
        val list = paths.toList()
        scanner.execute {
            val own = ownRoots(app)
            val shared = list.filter { p -> own.none { p == it || p.startsWith("$it/") } }
            for (batch in shared.chunked(SCAN_BATCH)) scanBatch(app, batch)
        }
    }

    /** Swift's data folder by each of its names (Private is in it): never told to the media index. */
    private fun ownRoots(app: Context): List<String> = listOfNotNull(
        app.applicationInfo.dataDir,
        runCatching { File(app.applicationInfo.dataDir).canonicalPath }.getOrNull(),
        "/data/data/${app.packageName}",
        "/data/user/0/${app.packageName}",
    ).distinct()

    /** 3.0: every file in [root] (a folder renamed, restored or moved) back in the Gallery, not just the folder. */
    fun scanTree(ctx: Context, root: File) {
        val app = ctx.applicationContext
        scanner.execute {
            if (AppFiles.isPrivate(app, root)) return@execute
            val all = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.map { it.path }.toList() else listOf(root.path)
            for (batch in all.chunked(SCAN_BATCH)) scanBatch(app, batch)
        }
    }

    private fun scanBatch(app: Context, batch: List<String>) {
        val done = java.util.concurrent.CountDownLatch(batch.size)
        try {
            MediaScannerConnection.scanFile(app, batch.toTypedArray(), null) { _, _ -> done.countDown() }
            // The media index answers for each file; a stuck one doesn't hold up the rest for long.
            done.await(SCAN_WAIT_S + batch.size / 10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            L.w("Couldn't tell the media index", e)
        }
    }

    private const val SCAN_WAIT_S = 30L

    /** 3.0: [s] for a LIKE question, its "%" and "_" (and "\") as themselves (with ESCAPE '\'). */
    fun likeEscaped(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Deleted files (and everything in a deleted folder) leave the Gallery and music apps. 2.9: only
     * the index's rows: Android 9 and older otherwise delete a picture, video or song still at that
     * path along with its row ("deletedata=false" tells them not to). 3.0: the folder's "_" and "%"
     * mean themselves (deleting "My_Pics" no longer forgot "MyXPics" too).
     */
    fun forget(ctx: Context, f: File) {
        try {
            ctx.contentResolver.delete(
                MediaStore.Files.getContentUri("external").buildUpon().appendQueryParameter("deletedata", "false").build(),
                MediaStore.Files.FileColumns.DATA + "=? OR " + MediaStore.Files.FileColumns.DATA + " LIKE ? ESCAPE '\\'",
                arrayOf(f.path, likeEscaped(f.path) + "/%"),
            )
        } catch (_: Exception) {
        }
    }

    /** Several gone files at once (2.9: ones the media index still listed), rows only as in [forget]. */
    fun forgetPaths(ctx: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val uri = MediaStore.Files.getContentUri("external").buildUpon().appendQueryParameter("deletedata", "false").build()
        for (chunk in paths.chunked(200)) {
            try {
                ctx.contentResolver.delete(
                    uri,
                    MediaStore.Files.FileColumns.DATA + " IN (" + chunk.joinToString(",") { "?" } + ")",
                    chunk.toTypedArray(),
                )
            } catch (_: Exception) {
            }
        }
    }
}
