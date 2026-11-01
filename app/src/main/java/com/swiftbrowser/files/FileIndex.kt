package com.swiftbrowser.files

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.download.Downloads
import java.io.File
import java.util.Locale

/**
 * What the Files start page shows (2.8): pictures, videos, music, documents, archives, app files and
 * downloads from all over the phone and its SD card, counted and listed newest first; the files
 * changed lately; and a search through every file. All from Android's media index, which knows every
 * file already: walking the folders one by one takes minutes on a full phone. Downloads are read from
 * their folders (they are few, and Swift's own may not be in the index yet).
 */
object FileIndex {
    enum class Category(val key: String, val label: String, val icon: Int, val nothing: String, val hint: String) {
        IMAGES("images", "Images", R.drawable.ic_image, "No pictures found", "Photos and pictures you take, download or receive show here."),
        VIDEOS("videos", "Videos", R.drawable.ic_movie, "No videos found", "Videos you record, download or receive show here."),
        MUSIC("music", "Music", R.drawable.ic_headphones, "No music found", "Songs, voice notes and other sound files show here."),
        DOCUMENTS("documents", "Documents", R.drawable.ic_documents, "No documents found", "PDFs, Word, Excel, PowerPoint, e-books and text files show here."),
        ARCHIVES("archives", "Archives", R.drawable.ic_archive, "No archives found", "ZIP, RAR, 7z and other archives show here."),
        APKS("apks", "APKs", R.drawable.ic_apk, "No app files found", "Apps you download as files (.apk) show here."),
        DOWNLOADS("downloads", "Downloads", R.drawable.ic_download, "No downloads yet", "Files you download show here."),
        RECENT("recent", "Recent files", R.drawable.ic_history, "No recent files", "Files you download, take or change show here.");

        companion object {
            /** The ones on the start page's grid (Recent has a section of its own). */
            val shown = listOf(IMAGES, VIDEOS, MUSIC, DOCUMENTS, ARCHIVES, APKS, DOWNLOADS)

            fun of(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    /** How many files a category has, and their size together. */
    class Summary(val count: Int, val bytes: Long)

    /** The start page's numbers and its recent files. */
    class Overview(val counts: Map<Category, Summary>, val recent: List<FileItem>)

    /** The start page and its "Show all" list. */
    const val RECENT_MAX = 30

    /** A list holds at most this many (the newest): enough for anyone scrolling, light on a 2 GB phone. */
    const val LIST_MAX = 5000

    // The file's path: deprecated by name on Android 10, still filled in (and the only way on Android 7).
    private const val DATA = "_data"
    private const val SIZE = MediaStore.Files.FileColumns.SIZE
    private const val DATE = MediaStore.Files.FileColumns.DATE_MODIFIED
    private const val MEDIA_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
    private val PROJECTION = arrayOf(DATA, SIZE, DATE, MEDIA_TYPE)

    private val DOCUMENT_EXTS: List<String> = Docs.Kind.entries.filter { it != Docs.Kind.ZIP }.flatMap { it.extensions }
    private val ARCHIVE_EXTS = listOf("zip", "7z", "rar", "tar", "tgz", "tbz2", "tbz", "txz", "gz", "bz2", "xz")

    /** Settings and logs that apps leave in shared folders: not what anyone means by recent files. */
    private val NOT_RECENT = setOf("json", "xml", "ini", "cfg", "yaml", "yml", "log")

    private const val DAY = 24L * 3600 * 1000

    /** Which category a file is in: by its name, or by what the media index found it to be. */
    fun categoryOf(name: String, mediaType: Int = 0): Category? = when (FileKind.of(name)) {
        FileKind.IMAGE -> Category.IMAGES
        FileKind.VIDEO -> Category.VIDEOS
        FileKind.AUDIO -> Category.MUSIC
        FileKind.DOCUMENT -> Category.DOCUMENTS
        FileKind.ARCHIVE -> Category.ARCHIVES
        FileKind.APK -> Category.APKS
        else -> when (mediaType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Category.IMAGES
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> Category.MUSIC
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Category.VIDEOS
            else -> null
        }
    }

    /** Hidden files and folders, and apps' private folders, are left out of every list here. */
    private fun skip(path: String): Boolean = path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")

    /** "DCIM › Camera", "SD card › Music", or "Phone storage" for a storage's top folder. */
    fun placeOf(ctx: Context, dir: File?): String {
        dir ?: return ""
        val v = Volumes.of(ctx, dir) ?: return dir.path
        val rel = FileOps.relative(v.root, dir)
        return when {
            rel.isEmpty() -> v.name
            v.removable -> v.name + " › " + rel
            else -> rel
        }
    }

    /** The media index's question for [cats]: by kind for pictures, videos and sounds, by ending for the rest. */
    private fun selectionFor(cats: Collection<Category>): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        val media = cats.mapNotNull {
            when (it) {
                Category.IMAGES -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                Category.MUSIC -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                Category.VIDEOS -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                else -> null
            }
        }
        if (media.isNotEmpty()) parts += "$MEDIA_TYPE IN (${media.joinToString(",")})"
        val exts = cats.flatMap {
            when (it) {
                Category.DOCUMENTS -> DOCUMENT_EXTS
                Category.ARCHIVES -> ARCHIVE_EXTS
                Category.APKS -> listOf("apk")
                else -> emptyList()
            }
        }
        for (e in exts) {
            parts += "$DATA LIKE ?"
            args += "%.$e"
        }
        return parts.joinToString(" OR ", "(", ")") to args.toTypedArray()
    }

    /** Runs [each] on the index's files (newest first) until it returns false. */
    private inline fun query(ctx: Context, selection: String, args: Array<String>, each: (path: String, size: Long, time: Long, mediaType: Int) -> Boolean) {
        try {
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), PROJECTION, selection, args, "$DATE DESC")?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (!each(path, c.getLong(1), c.getLong(2) * 1000, c.getInt(3))) break
                }
            }
        } catch (e: Exception) {
            L.w("Couldn't read Android's media index", e)
        }
    }

    /** Every category's count and size, and the files changed lately (one pass through the index). */
    fun overview(ctx: Context, stop: () -> Boolean): Overview {
        val totals = HashMap<Category, LongArray>()
        val recent = ArrayList<FileItem>()
        val now = System.currentTimeMillis()
        val (selection, args) = selectionFor(Category.shown)
        query(ctx, selection, args) { path, size, time, mediaType ->
            if (skip(path)) return@query true
            val name = path.substringAfterLast('/')
            val cat = categoryOf(name, mediaType) ?: return@query true
            totals.getOrPut(cat) { LongArray(2) }.let {
                it[0]++
                it[1] += size.coerceAtLeast(0)
            }
            // A file dated in the future (a wrong clock somewhere) would stay at the top for good.
            if (recent.size < RECENT_MAX && time <= now + DAY && FileKind.extension(name) !in NOT_RECENT) {
                stillThere(ctx, path, name, size)?.let { recent += it }
            }
            !stop()
        }
        var count = 0L
        var bytes = 0L
        for (f in downloadFiles(LIST_MAX, stop)) {
            count++
            bytes += f.length()
        }
        totals[Category.DOWNLOADS] = longArrayOf(count, bytes)
        return Overview(totals.mapValues { (_, v) -> Summary(v[0].toInt(), v[1]) }, recent)
    }

    /** A file the index knows, if it is still there (the index may not know yet that it went). */
    private fun stillThere(ctx: Context, path: String, name: String, size: Long): FileItem? {
        val f = File(path)
        val t = f.lastModified()
        if (t <= 0L) return null
        return FileItem(f, name, false, size, t, -1, FileKind.of(name), placeOf(ctx, f.parentFile))
    }

    /** A category's files, newest first. */
    fun list(ctx: Context, cat: Category, stop: () -> Boolean): List<FileItem> {
        if (cat == Category.DOWNLOADS) {
            return downloadFiles(LIST_MAX, stop).map { FileItem.of(it, where = placeOf(ctx, it.parentFile)) }.sortedByDescending { it.time }
        }
        val out = ArrayList<FileItem>()
        val now = System.currentTimeMillis()
        val recentOnly = cat == Category.RECENT
        val (selection, args) = selectionFor(if (recentOnly) Category.shown else listOf(cat))
        query(ctx, selection, args) { path, size, time, mediaType ->
            if (skip(path)) return@query true
            val name = path.substringAfterLast('/')
            val kind = categoryOf(name, mediaType)
            if (recentOnly) {
                if (kind == null || time > now + DAY || FileKind.extension(name) in NOT_RECENT) return@query true
            } else if (kind != cat) {
                return@query true
            }
            // Its date read from the file itself also tells that it is still there.
            stillThere(ctx, path, name, size)?.let { out += it }
            out.size < (if (recentOnly) RECENT_MAX else LIST_MAX) && !stop()
        }
        return out
    }

    /** Files and folders anywhere on the phone with [words] in their name, newest first. */
    fun search(ctx: Context, words: String, showHidden: Boolean, max: Int, stop: () -> Boolean): List<FileItem> {
        val q = words.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<FileItem>()
        query(ctx, "$DATA LIKE ? ESCAPE '\\'", arrayOf(like)) { path, _, _, _ ->
            val name = path.substringAfterLast('/')
            val hidden = path.contains("/.")
            if (!name.lowercase(Locale.ROOT).contains(q) || (hidden && !showHidden) || path.contains("/Android/data/")) return@query true
            val f = File(path)
            if (f.exists()) out += FileItem.of(f, count = f.isDirectory, where = placeOf(ctx, f.parentFile))
            out.size < max && !stop()
        }
        return out
    }

    // ------------------------------------------------------------------ downloads

    /** The phone's Download folder, and Swift's own when it is somewhere else. */
    @Suppress("DEPRECATION")
    fun downloadFolders(): List<File> {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val own = runCatching { Downloads.directory() }.getOrNull()
        val all = listOfNotNull(public, own).filter { it.isDirectory }.distinctBy { it.path }
        return all.filter { a -> all.none { b -> b !== a && a.path.startsWith(b.path + "/") } }
    }

    /** The files in the download folders and a few folders below (no hidden ones), at most [max]. */
    private fun downloadFiles(max: Int, stop: () -> Boolean): List<File> {
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val children = d.listFiles() ?: return
            for (c in children) {
                if (out.size >= max || stop()) return
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) {
                    if (depth < 3) walk(c, depth + 1)
                } else {
                    out += c
                }
            }
        }
        for (root in downloadFolders()) walk(root, 0)
        return out
    }
}

/** Folders kept at hand on the Files start page (2.8), in the order they were added. */
object Favourites {
    private const val PREF = "files_favourites"

    fun paths(): List<String> = SwiftApp.prefs.sp.getString(PREF, null).orEmpty().split('\n').filter { it.isNotEmpty() }

    fun has(f: File): Boolean = f.path in paths()

    fun add(f: File) {
        val p = paths()
        if (f.path !in p) save(p + f.path)
    }

    fun remove(f: File) = save(paths() - f.path)

    private fun save(paths: List<String>) {
        SwiftApp.prefs.sp.edit().putString(PREF, paths.joinToString("\n")).apply()
    }
}
