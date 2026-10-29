package com.swiftbrowser.files

import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.swiftbrowser.core.L
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Date
import java.util.Locale

/**
 * Archives (2.7), as in ZArchiver: ZIP (with ZipCrypto or AES passwords), 7z (with passwords), RAR
 * (RAR4; RAR5 can't be read in plain Java), tar and tar.gz/.bz2/.xz, and single .gz/.bz2/.xz files.
 * Swift lists what one holds, takes out all of it or some, opens one file from it, and makes ZIP
 * (optionally with an AES-256 password) and 7z archives. Names that point outside the folder
 * ("../", "/etc") are never written, and nothing is written past the free space (less 50 MB).
 */
object Archives {
    enum class Format(val label: String, val tar: Boolean = false, val single: Boolean = false) {
        ZIP("ZIP"),
        SEVEN_Z("7z"),
        RAR("RAR"),
        TAR("TAR", tar = true),
        TAR_GZ("TAR.GZ", tar = true),
        TAR_BZ2("TAR.BZ2", tar = true),
        TAR_XZ("TAR.XZ", tar = true),
        GZ("GZ", single = true),
        BZ2("BZ2", single = true),
        XZ("XZ", single = true),
    }

    /** The formats Swift makes (RAR can only be read). */
    enum class Output(val ext: String, val label: String) {
        ZIP("zip", "ZIP"),
        SEVEN_Z("7z", "7z"),
    }

    fun formatOf(name: String): Format? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Format.TAR_GZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> Format.TAR_BZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Format.TAR_XZ
            n.endsWith(".tar") -> Format.TAR
            n.endsWith(".zip") -> Format.ZIP
            n.endsWith(".7z") -> Format.SEVEN_Z
            n.endsWith(".rar") -> Format.RAR
            n.endsWith(".gz") -> Format.GZ
            n.endsWith(".bz2") -> Format.BZ2
            n.endsWith(".xz") -> Format.XZ
            else -> null
        }
    }

    /** The archive's name without its ending ("photos.tar.gz" → "photos"): its folder's name when extracted. */
    fun baseName(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        val cut = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".tar", ".zip", ".7z", ".rar", ".gz", ".bz2", ".xz")
            .firstOrNull { n.endsWith(it) }?.length ?: 0
        return name.substring(0, name.length - cut).ifBlank { "archive" }
    }

    /** One file or folder in an archive; [path] is cleaned ("a/b/c.txt"). */
    class Entry(val path: String, val isDir: Boolean, val size: Long, val time: Long, val encrypted: Boolean) {
        val name: String get() = path.substringAfterLast('/')
        val parent: String get() = path.substringBeforeLast('/', "")
    }

    /** The archive's contents, as folders. [password] once the user gave it (kept in memory only). */
    class Index(val file: File, val format: Format, val entries: List<Entry>, var password: String?, val unsafe: Int, internal val zipUtf8: Boolean) {
        private val byParent = HashMap<String, MutableList<Entry>>()

        init {
            val dirs = HashSet<String>()
            for (e in entries) {
                if (e.isDir) dirs += e.path
                var p = e.parent
                while (p.isNotEmpty() && dirs.add(p)) p = p.substringBeforeLast('/', "")
            }
            // Folders the archive only names through their files get an entry of their own.
            val listed = entries.filter { it.isDir }.associateBy { it.path }
            val all = entries.filter { !it.isDir } + dirs.map { d -> listed[d] ?: Entry(d, true, 0, 0, false) }
            for (e in all) byParent.getOrPut(e.parent) { ArrayList() }.add(e)
        }

        /** What folder [dir] ("" for the top) holds. */
        fun children(dir: String): List<Entry> = byParent[dir].orEmpty()

        fun count(dir: String): Int = byParent[dir]?.size ?: 0

        /** Every file in [dir] and below. */
        fun filesUnder(dir: String): List<Entry> = entries.filter { !it.isDir && (dir.isEmpty() || it.path.startsWith("$dir/")) }

        val encrypted: Boolean get() = entries.any { it.encrypted }

        val totalSize: Long get() = entries.sumOf { if (it.isDir) 0L else it.size.coerceAtLeast(0) }

        fun search(dir: String, query: String, max: Int = 300): List<Entry> {
            val q = query.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) return emptyList()
            return byParent.values.flatten()
                .filter { (dir.isEmpty() || it.path.startsWith("$dir/")) && it.name.lowercase(Locale.ROOT).contains(q) }
                .take(max)
        }
    }

    /** The archive needs a password ([wrong]: the one given didn't work). */
    class NeedPassword(val wrong: Boolean) : Exception()

    const val RAR5_TEXT = "This RAR file uses the RAR5 format, which Swift can't open."
    private const val DAMAGED = "Swift couldn't read this archive. It may be damaged, or not what its name says."

    /** Most memory a 7z or xz file may need to be read (larger ones were made for big computers). */
    private const val MEMORY_KB = 192 * 1024

    /**
     * A name from an archive as a safe relative path ("a/b.txt"): backslashes become folders, a
     * leading "/" or drive goes. Null when it points outside ("../"), "" for the archive's top.
     */
    fun clean(raw: String): String? {
        var p = raw.replace('\\', '/')
        if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) p = p.substring(2)
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Characters a memory card (FAT) can't have in a name. */
    private val BAD = Regex("[:*?\"<>|\\u0000-\\u001f]")

    private fun safeSegment(s: String) = s.replace(BAD, "_").trim().ifEmpty { "_" }

    /** The name an entry gets when it is taken out (what a memory card accepts). */
    fun outputName(name: String) = safeSegment(name)

    // ------------------------------------------------------------------ reading what it holds

    /** Lists [file]; throws [NeedPassword], [FileProblem] (RAR5, damaged) or IOException. [stop] ends a long read. */
    fun read(file: File, password: String?, stop: () -> Boolean = { false }): Index {
        val format = formatOf(file.name) ?: throw FileProblem(DAMAGED)
        val raw = ArrayList<Pair<String, Entry>>()
        var utf8 = true
        try {
            when (format) {
                Format.ZIP -> {
                    var headers = openZip(file, password, true).use { it.fileHeaders.toList() }
                    // Names not written as UTF-8 (older Windows ZIPs) are read in the DOS code page.
                    if (headers.any { '�' in it.fileName }) {
                        utf8 = false
                        headers = openZip(file, password, false).use { it.fileHeaders.toList() }
                    }
                    for (h in headers) raw += h.fileName to Entry("", h.isDirectory, h.uncompressedSize, h.lastModifiedTimeEpoch, h.isEncrypted)
                }
                Format.SEVEN_Z -> open7z(file, password).use { sz ->
                    for (e in sz.entries) {
                        if (e.isAntiItem) continue
                        raw += e.name.orEmpty() to Entry("", e.isDirectory, e.size, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L, false)
                    }
                }
                Format.RAR -> openRar(file, password).use { ar ->
                    for (h in ar.fileHeaders) {
                        raw += h.fileName.orEmpty() to Entry("", h.isDirectory, h.fullUnpackSize, h.mTime?.time ?: 0L, h.isEncrypted)
                    }
                }
                Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_XZ -> TarArchiveInputStream(decompress(format, Counting(FileInputStream(file), stop))).use { tar ->
                    while (true) {
                        if (stop()) throw Cancelled()
                        val e = tar.nextTarEntry ?: break
                        if (e.isSymbolicLink || e.isLink) continue
                        raw += e.name to Entry("", e.isDirectory, e.size, e.modTime?.time ?: 0L, false)
                    }
                }
                Format.GZ, Format.BZ2, Format.XZ -> raw += singleName(file, format) to Entry("", false, singleSize(file, format), file.lastModified(), false)
            }
        } catch (e: NeedPassword) {
            throw e
        } catch (e: FileProblem) {
            throw e
        } catch (e: Cancelled) {
            throw e
        } catch (e: OutOfMemoryError) {
            L.w("Archive too big to read", e)
            throw FileProblem("This archive is too big for the phone's memory.")
        } catch (e: Exception) {
            L.w("Couldn't read an archive (${format.label})", e)
            throw FileProblem(DAMAGED)
        }
        var unsafe = 0
        val entries = ArrayList<Entry>(raw.size)
        for ((name, e) in raw) {
            val path = clean(name)
            if (path == null) {
                unsafe++
                continue
            }
            if (path.isEmpty()) continue
            entries += Entry(path, e.isDir, e.size, e.time, e.encrypted)
        }
        if (unsafe > 0) L.w("Archive has $unsafe entries pointing outside its folder")
        return Index(file, format, entries, password, unsafe, utf8)
    }

    private fun openZip(file: File, password: String?, utf8: Boolean): ZipFile =
        ZipFile(file, password?.toCharArray()).apply { if (utf8) charset = Charsets.UTF_8 }

    /** 7z from a file channel (never Android 8's java.nio.file). */
    private fun open7z(file: File, password: String?): SevenZFile {
        val ch = RandomAccessFile(file, "r").channel
        val options = SevenZFileOptions.builder().withMaxMemoryLimitInKb(MEMORY_KB).build()
        try {
            return SevenZFile(ch, file.name, password?.toCharArray(), options)
        } catch (e: PasswordRequiredException) {
            ch.close()
            throw NeedPassword(false)
        } catch (e: org.apache.commons.compress.MemoryLimitException) {
            ch.close()
            throw FileProblem("This archive needs more memory than the phone has.")
        } catch (e: IOException) {
            ch.close()
            // Its list of files is locked: a wrong password reads as nonsense.
            if (password != null) throw NeedPassword(true)
            throw e
        }
    }

    private fun isRar5(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val b = ByteArray(8)
            input.read(b) == 8 && String(b, 0, 6, Charsets.ISO_8859_1) == "Rar!\u001A\u0007" && b[6] == 1.toByte()
        }
    } catch (_: Exception) {
        false
    }

    private fun openRar(file: File, password: String?): Archive {
        if (isRar5(file)) throw FileProblem(RAR5_TEXT)
        try {
            return Archive(file, password)
        } catch (e: UnsupportedRarV5Exception) {
            throw FileProblem(RAR5_TEXT)
        } catch (e: UnsupportedRarEncryptedException) {
            throw NeedPassword(password != null)
        } catch (e: RarException) {
            if (password != null) throw NeedPassword(true)
            throw e
        }
    }

    private fun decompress(format: Format, input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, FileJobs.BUFFER)
        return when (format) {
            Format.TAR_GZ, Format.GZ -> GzipCompressorInputStream(buffered, true)
            Format.TAR_BZ2, Format.BZ2 -> BZip2CompressorInputStream(buffered, true)
            Format.TAR_XZ, Format.XZ -> XZInputStream(buffered, MEMORY_KB)
            else -> buffered
        }
    }

    /** The one file a .gz, .bz2 or .xz holds: its own name (.gz keeps it), or the archive's without the ending. */
    private fun singleName(file: File, format: Format): String {
        if (format == Format.GZ) {
            try {
                GzipCompressorInputStream(BufferedInputStream(FileInputStream(file))).use { gz ->
                    gz.metaData.filename?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
                }
            } catch (_: Exception) {
            }
        }
        return baseName(file.name).let { if (it == "archive") file.name + ".out" else it }
    }

    /** A .gz file's size unpacked, from its last four bytes (-1 for the others: not known before reading). */
    private fun singleSize(file: File, format: Format): Long {
        if (format != Format.GZ || file.length() < 18) return -1
        return try {
            RandomAccessFile(file, "r").use { r ->
                r.seek(file.length() - 4)
                val b = ByteArray(4)
                r.readFully(b)
                (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
            }
        } catch (_: Exception) {
            -1
        }
    }

    /** Counts what was read of the archive (progress through a tar.gz) and stops for [stop]. */
    private class Counting(input: InputStream, private val stop: () -> Boolean, private val onRead: ((Long) -> Unit)? = null) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            if (stop()) throw Cancelled()
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (stop()) throw Cancelled()
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) count(s)
            return s
        }

        private fun count(n: Long) {
            count += n
            onRead?.invoke(count)
        }
    }

    // ------------------------------------------------------------------ taking files out

    /**
     * Takes files out of [file] into [dest]: all of it ([select] null) or the entries in [select]
     * (a folder brings everything in it), named from [base] down (the folder being looked at in the
     * archive). Asks for the password when one is needed or was wrong.
     */
    fun extract(w: FileJobs.Work, file: File, index: Index?, select: Set<String>?, base: String, dest: File, access: FileAccess): Int =
        Extractor(w, file, formatOf(file.name) ?: throw FileProblem(DAMAGED), index, select, base, dest, access).run()

    private class Extractor(
        val w: FileJobs.Work, val file: File, val format: Format, val index: Index?, val select: Set<String>?,
        val base: String, val dest: File, val access: FileAccess,
    ) {
        /** Entries done already (a wrong password starts the archive again: these aren't written twice). */
        val done = HashSet<String>()
        val destPath: String = runCatching { dest.canonicalPath }.getOrDefault(dest.absolutePath)
        var unsafe = 0
        var written = 0

        /** Whether [path] was chosen: it, or a folder it is in (looked up folder by folder, quick for big archives). */
        fun selected(path: String): Boolean {
            if (base.isNotEmpty() && !path.startsWith("$base/")) return false
            val s = select ?: return true
            var p = path
            while (true) {
                if (p in s) return true
                val cut = p.lastIndexOf('/')
                if (cut < 0) return false
                p = p.substring(0, cut)
            }
        }

        /** Where entry [path] goes, or null when it would leave [dest]. */
        fun target(path: String): File? {
            val rel = if (base.isEmpty()) path else path.removePrefix("$base/")
            val t = File(dest, rel.split('/').joinToString("/") { safeSegment(it) })
            val canonical = runCatching { t.canonicalPath }.getOrNull()
            if (canonical == null || !canonical.startsWith("$destPath/")) {
                unsafe++
                return null
            }
            return t
        }

        fun run(): Int {
            var password = index?.password
            // The folder named after the archive, or the chosen one.
            if (!access.mkdirs(dest)) throw FileProblem("Swift couldn't make the folder “${dest.name}”.")
            w.touched(dest.parentFile)
            w.touched(dest)
            // Nothing past the free space, less a margin: an archive that unpacks into far more than its size stops.
            val free = runCatching { android.os.StatFs(dest.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            w.limit = (free - 50L * 1_048_576).coerceAtLeast(0)
            if (index != null) {
                val files = index.entries.filter { !it.isDir && selected(it.path) }
                plan(files.size, files.sumOf { it.size.coerceAtLeast(0) })
                if (password == null && files.any { it.encrypted }) password = w.askPassword(file.name, false)
            }
            while (true) {
                try {
                    when (format) {
                        Format.ZIP -> zip(password)
                        Format.SEVEN_Z -> sevenZ(password)
                        Format.RAR -> rar(password)
                        Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_XZ -> tar()
                        Format.GZ, Format.BZ2, Format.XZ -> single()
                    }
                    break
                } catch (e: NeedPassword) {
                    password = w.askPassword(file.name, e.wrong)
                } catch (e: org.tukaani.xz.MemoryLimitException) {
                    throw FileProblem("This archive needs more memory than the phone has.")
                }
            }
            index?.password = password
            if (unsafe > 0) L.w("Skipped $unsafe archive entries pointing outside the folder")
            w.message(buildString {
                append("Extracted ").append(if (written == 1) "1 file" else "$written files")
                if (w.job.skipped > 0) append(", skipped ").append(w.job.skipped)
                if (unsafe > 0) append(". ").append(if (unsafe == 1) "1 file was" else "$unsafe files were").append(" left out: its name pointed outside the folder")
            })
            return written
        }

        private var planned = false

        /**
         * What will be taken out ([files], [bytes] unpacked), once known: refused when it can't fit,
         * and the progress counts towards it. Tar files are read as one stream: their progress is
         * how far through the archive Swift is.
         */
        fun plan(files: Int, bytes: Long) {
            if (planned) return
            planned = true
            if (bytes > w.limit) {
                throw FileProblem("There isn't enough free space: this needs ${com.swiftbrowser.core.UrlUtils.formatBytes(bytes)}, and ${com.swiftbrowser.core.UrlUtils.formatBytes(w.limit)} can be used.")
            }
            w.totals(files, if (format.tar || format.single) -1 else bytes)
        }

        /** Writes one file ([fill] writes its bytes). False when it was skipped (a name taken, or one pointing outside). */
        fun write(path: String, time: Long, fill: (OutputStream) -> Unit): Boolean {
            val t0 = target(path) ?: return false
            w.check()
            w.current(t0.name)
            t0.parentFile?.let { if (!access.mkdirs(it)) throw FileProblem("Swift couldn't make the folder “${it.name}”.") }
            val t = w.resolve(t0, false) ?: run {
                done += path
                return false
            }
            w.write(t, access, time, fill = fill)
            done += path
            written++
            w.fileDone()
            return true
        }

        fun folder(path: String) {
            val t = target(path) ?: return
            if (!access.mkdirs(t)) throw FileProblem("Swift couldn't make the folder “${t.name}”.")
            done += path
        }

        fun zip(password: String?) {
            openZip(file, password, index?.zipUtf8 ?: true).use { zf ->
                val headers = zf.fileHeaders
                headers.filter { !it.isDirectory && clean(it.fileName)?.let { p -> p.isNotEmpty() && selected(p) } == true }
                    .let { chosen -> plan(chosen.size, chosen.sumOf { it.uncompressedSize.coerceAtLeast(0) }) }
                for (h in headers) {
                    w.check()
                    val path = clean(h.fileName) ?: continue
                    if (path.isEmpty() || !selected(path) || path in done) continue
                    if (h.isDirectory) {
                        folder(path)
                        continue
                    }
                    if (h.isEncrypted && password == null) throw NeedPassword(false)
                    try {
                        // Opened first: a wrong password shows here, before anything is written.
                        zf.getInputStream(h).use { input -> write(path, h.lastModifiedTimeEpoch) { out -> w.copy(input, out) } }
                    } catch (e: ZipException) {
                        if (h.isEncrypted && (e.type == ZipException.Type.WRONG_PASSWORD || e.type == ZipException.Type.CHECKSUM_MISMATCH)) {
                            throw NeedPassword(password != null)
                        }
                        throw e
                    }
                }
            }
        }

        fun sevenZ(password: String?) {
            open7z(file, password).use { sz ->
                sz.entries.filter { !it.isDirectory && !it.isAntiItem && clean(it.name.orEmpty())?.let { p -> p.isNotEmpty() && selected(p) } == true }
                    .let { chosen -> plan(chosen.size, chosen.sumOf { it.size.coerceAtLeast(0) }) }
                while (true) {
                    w.check()
                    val e: SevenZArchiveEntry = sz.nextEntry ?: break
                    if (e.isAntiItem) continue
                    val path = clean(e.name.orEmpty()) ?: continue
                    if (path.isEmpty() || !selected(path) || path in done) continue
                    if (e.isDirectory) {
                        folder(path)
                        continue
                    }
                    val time = if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L
                    try {
                        // The first piece is read before anything is written: a wrong password shows there.
                        val buf = ByteArray(FileJobs.BUFFER)
                        val first = if (e.hasStream()) sz.read(buf) else -1
                        write(path, time) { out ->
                            if (first > 0) {
                                out.write(buf, 0, first)
                                while (true) {
                                    w.check()
                                    val n = sz.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    } catch (ex: PasswordRequiredException) {
                        throw NeedPassword(false)
                    } catch (ex: org.apache.commons.compress.MemoryLimitException) {
                        throw FileProblem("This archive needs more memory than the phone has.")
                    } catch (ex: FileProblem) {
                        throw ex
                    } catch (ex: Cancelled) {
                        throw ex
                    } catch (ex: IOException) {
                        // Locked content read with a wrong password comes out as nonsense.
                        if (password != null) throw NeedPassword(true)
                        throw ex
                    }
                }
            }
        }

        fun rar(password: String?) {
            openRar(file, password).use { ar ->
                // A solid RAR packs its files as one stream: those before a chosen one are read too.
                val solid = ar.mainHeader?.isSolid == true
                ar.fileHeaders.filter { !it.isDirectory && clean(it.fileName.orEmpty())?.let { p -> p.isNotEmpty() && selected(p) } == true }
                    .let { chosen -> plan(chosen.size, chosen.sumOf { it.fullUnpackSize.coerceAtLeast(0) }) }
                for (h in ar.fileHeaders) {
                    w.check()
                    val path = clean(h.fileName.orEmpty())
                    val wanted = path != null && path.isNotEmpty() && selected(path) && path !in done
                    if (h.isDirectory) {
                        if (wanted) folder(path!!)
                        continue
                    }
                    if (!wanted) {
                        if (solid && (!h.isEncrypted || password != null)) ar.extractFile(h, NullOutput)
                        continue
                    }
                    if (h.isEncrypted && password == null) throw NeedPassword(false)
                    try {
                        val took = write(path!!, h.mTime?.time ?: 0L) { out -> ar.extractFile(h, out) }
                        // Skipped in a solid RAR: read anyway, as the files after it need it.
                        if (!took && solid) ar.extractFile(h, NullOutput)
                    } catch (e: CrcErrorException) {
                        if (h.isEncrypted) throw NeedPassword(true)
                        throw FileProblem(DAMAGED)
                    } catch (e: RarException) {
                        // junrar wraps what went wrong writing (no space, Cancel).
                        (e.cause as? FileProblem)?.let { throw it }
                        (e.cause as? Cancelled)?.let { throw it }
                        if (h.isEncrypted) throw NeedPassword(true)
                        throw FileProblem(DAMAGED)
                    }
                }
            }
        }

        fun tar() {
            val total = file.length().coerceAtLeast(1)
            val counting = Counting(FileInputStream(file), { w.job.cancelled }) { n -> w.fraction(n.toFloat() / total) }
            TarArchiveInputStream(decompress(format, counting)).use { tar ->
                while (true) {
                    w.check()
                    val e = tar.nextTarEntry ?: break
                    if (e.isSymbolicLink || e.isLink) continue
                    val path = clean(e.name) ?: continue
                    if (path.isEmpty() || !selected(path) || path in done) continue
                    if (e.isDirectory) folder(path) else write(path, e.modTime?.time ?: 0L) { out -> w.copy(tar, out) }
                }
            }
        }

        fun single() {
            val name = singleName(file, format)
            if (!selected(name)) return
            val total = file.length().coerceAtLeast(1)
            val counting = Counting(FileInputStream(file), { w.job.cancelled }) { n -> w.fraction(n.toFloat() / total) }
            decompress(format, counting).use { input -> write(name, file.lastModified()) { out -> w.copy(input, out) } }
        }
    }

    /** Throws away what is written (the files read on the way in a solid RAR). */
    private object NullOutput : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    // ------------------------------------------------------------------ making archives

    /**
     * Packs [sources] (files and whole folders) into [dest] as ZIP or 7z. A ZIP with [password] is
     * locked with AES-256 (Windows' own unzip can't open those; 7-Zip, WinRAR and phones can).
     */
    fun compress(w: FileJobs.Work, sources: List<File>, dest: File, output: Output, password: String?, access: FileAccess) {
        w.counting()
        val totals = FileOps.measure(sources, { w.job.cancelled })
        w.check()
        w.totals(totals.files, totals.bytes)
        val destPath = runCatching { dest.canonicalPath }.getOrDefault(dest.absolutePath)
        var ok = false
        try {
            when (output) {
                Output.ZIP -> ZipOutputStream(BufferedOutputStream(access.output(dest), FileJobs.BUFFER), password?.toCharArray()).use { zip ->
                    fun add(f: File, name: String) {
                        w.check()
                        if (runCatching { f.canonicalPath }.getOrNull() == destPath) return
                        val dir = f.isDirectory
                        val p = ZipParameters()
                        p.compressionMethod = CompressionMethod.DEFLATE
                        p.compressionLevel = CompressionLevel.NORMAL
                        p.fileNameInZip = if (dir) "$name/" else name
                        p.lastModifiedFileTime = f.lastModified().coerceAtLeast(315_532_800_000L)
                        if (password != null && !dir) {
                            p.isEncryptFiles = true
                            p.encryptionMethod = EncryptionMethod.AES
                            p.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                        w.current(f.name)
                        zip.putNextEntry(p)
                        if (!dir) FileInputStream(f).use { input -> w.copyCountingInput(input, zip) }
                        zip.closeEntry()
                        if (dir) f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") } else w.fileDone()
                    }
                    for (s in sources) add(s, s.name)
                }
                Output.SEVEN_Z -> SevenZOutputFile(access.channel(dest)).use { sz ->
                    // A light setting: quick on a slow phone, still much smaller than ZIP.
                    sz.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(3))))
                    fun add(f: File, name: String) {
                        w.check()
                        if (runCatching { f.canonicalPath }.getOrNull() == destPath) return
                        val dir = f.isDirectory
                        val e = SevenZArchiveEntry()
                        e.name = name
                        e.isDirectory = dir
                        e.setLastModifiedDate(Date(f.lastModified()))
                        w.current(f.name)
                        sz.putArchiveEntry(e)
                        if (!dir) {
                            FileInputStream(f).use { input ->
                                val buf = ByteArray(FileJobs.BUFFER)
                                while (true) {
                                    w.check()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    sz.write(buf, 0, n)
                                    w.progress(n.toLong())
                                }
                            }
                        }
                        sz.closeArchiveEntry()
                        if (dir) f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") } else w.fileDone()
                    }
                    for (s in sources) add(s, s.name)
                    sz.finish()
                }
            }
            ok = true
        } finally {
            if (!ok) runCatching { access.delete(dest) }
        }
        w.made(dest)
        w.result(dest)
        w.touched(dest.parentFile)
        w.message("Made ${dest.name}")
    }
}
