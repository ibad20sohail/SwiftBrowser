package com.swiftbrowser.files

import android.os.StatFs
import com.swiftbrowser.core.UrlUtils
import java.io.File
import java.io.FileInputStream

/**
 * Copying, moving and deleting (2.7), run as file jobs. Moving on one storage renames (instant);
 * between the phone and a card it copies, then deletes the original. Folders already at the
 * destination are merged; for files with a name already taken the user chooses.
 *
 * 3.0: a move that copies takes away only what it copied, file by file, then the folders that left
 * empty: a file that came into a folder meanwhile (a download finishing, a new camera photo) stays
 * where it is. A folder that can't be read (a failing card) stops the job instead of counting as
 * empty. Only what is copied is counted first: a move on one storage starts at once.
 */
object Transfers {
    const val CARD_TEXT = "Swift can only read the SD card. Allow it to change files there, then try again."

    /** Copies or moves [sources] into [dest]. */
    fun copy(w: FileJobs.Work, sources: List<File>, dest: File, move: Boolean) {
        val ctx = w.ctx
        val destAccess = Volumes.accessFor(ctx, dest) ?: throw FileProblem(CARD_TEXT)
        for (s in sources) {
            if (s.isDirectory && FileOps.isInside(dest, s)) throw FileProblem("A folder can't go inside itself.")
        }
        w.destination(dest)
        w.counting()
        val destVolume = Volumes.of(ctx, dest)
        val destRoot = destVolume?.root?.path
        // 3.0: a move on one storage is a rename, done at once however big: only what is copied is counted.
        val renamed = sources.filter { move && Volumes.of(ctx, it)?.root?.path == destRoot }.toSet()
        val sizes = sources.filter { it !in renamed }.associateWith { FileOps.measure(listOf(it), { w.job.cancelled }) }
        w.check()
        w.totals(sizes.values.sumOf { it.files } + renamed.size, sizes.values.sumOf { it.bytes })
        val needed = sizes.values.sumOf { it.bytes }
        val free = runCatching { StatFs(dest.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        // 3.0: some room is always left for the phone itself (50 MB), as for extracting.
        val room = (free - FileJobs.MARGIN).coerceAtLeast(0)
        if (needed > room) {
            throw FileProblem("There isn't enough free space: this needs ${UrlUtils.formatBytes(needed)}, and ${UrlUtils.formatBytes(room)} can be used.")
        }
        // 3.0: a memory card formatted as FAT32 can't take a file over 4 GB: said before copying the rest.
        if (destVolume != null && Volumes.isFat32(destVolume)) {
            sizes.entries.firstOrNull { it.value.biggest > FileErrors.FAT32_MAX }?.let {
                throw FileProblem(FileErrors.FAT32 + " " + (if (it.key.isDirectory) "“${it.key.name}” has one." else "“${it.key.name}” is ${UrlUtils.formatBytes(it.key.length())}."))
            }
        }
        // Counted from here (a job may copy to more than one place).
        if (free != Long.MAX_VALUE) w.limit = w.written + room
        w.touched(dest)
        var done = 0
        for (src in sources) {
            w.check()
            w.touched(src.parentFile)
            val ok = if (move) moveItem(w, src, dest, destAccess) else copyItem(w, src, dest, destAccess, null)
            if (ok) done++
        }
        val what = if (sources.size == 1) "“${sources[0].name}”" else FileJobs.items(sources.size)
        w.message(buildString {
            append(if (move) "Moved " else "Copied ").append(what)
            if (w.job.skipped > 0) append(", skipped ").append(w.job.skipped)
            if (w.stayedBehind > 0) {
                append(". ").append(if (w.stayedBehind == 1) "1 file" else "${w.stayedBehind} files")
                append(" couldn't be taken from where " + (if (w.stayedBehind == 1) "it was, so it's" else "they were, so they're") + " in both places")
            }
        })
        if (done == 0 && w.job.skipped == 0) throw FileProblem(if (move) "Nothing was moved." else "Nothing was copied.")
    }

    /** Copies [src] into [destDir] under its own name (or the one the user chose). True when all of it was copied. */
    private fun copyItem(w: FileJobs.Work, src: File, destDir: File, access: FileAccess, copied: Originals?): Boolean {
        w.check()
        val t0 = File(destDir, src.name)
        val target = w.resolve(t0, src.isDirectory, sameAsSource = t0.path == src.path) ?: run {
            if (!src.isDirectory) w.progress(src.length())
            return false
        }
        return copyTo(w, src, target, access, copied)
    }

    private fun copyTo(w: FileJobs.Work, src: File, target: File, access: FileAccess, copied: Originals?): Boolean {
        if (src.isDirectory) {
            // 3.0: unreadable (a failing card) is a problem, not an empty folder whose original would then go.
            val children = src.listFiles() ?: throw FileProblem("Swift couldn't read the folder “${src.name}”. The memory card may be failing.")
            if (!target.isDirectory && !access.mkdir(target)) throw FileProblem("Swift couldn't make the folder “${target.name}”.")
            var all = true
            for (c in children) if (!copyItem(w, c, target, access, copied)) all = false
            access.setTime(target, src.lastModified())
            copied?.folder(src, target)
            return all
        }
        w.current(src.name)
        val length = src.length()
        val time = src.lastModified()
        w.write(target, access, time) { out -> FileInputStream(src).use { w.copy(it, out) } }
        w.fileDone()
        copied?.file(src, target, length, time)
        return true
    }

    /** Moves [src] into [destDir]: a rename on the same storage, otherwise a copy then a delete. */
    private fun moveItem(w: FileJobs.Work, src: File, destDir: File, destAccess: FileAccess): Boolean {
        w.check()
        if (src.parentFile?.path == destDir.path) {
            // Already there.
            w.skipped()
            return false
        }
        val ctx = w.ctx
        val srcAccess = Volumes.accessFor(ctx, src) ?: throw FileProblem(CARD_TEXT)
        val sameStorage = Volumes.of(ctx, src)?.root?.path == Volumes.of(ctx, destDir)?.root?.path && srcAccess.javaClass == destAccess.javaClass
        val isDir = src.isDirectory
        val t0 = File(destDir, src.name)
        if (sameStorage) {
            if (isDir && t0.isDirectory) {
                // A folder of the same name is there: what this one holds goes into it.
                val children = src.listFiles() ?: throw FileProblem("Swift couldn't read the folder “${src.name}”. The memory card may be failing.")
                // 3.0: counted as they come (nothing was counted for a rename).
                w.addTotals(children.size - 1, 0)
                var all = true
                for (c in children) if (!moveItem(w, c, t0, destAccess)) all = false
                // 3.0: the folder goes only once it is empty (something may have come into it meanwhile).
                if (src.list()?.isEmpty() == true && srcAccess.deleteEmptyFolder(src)) FileOps.forgetPaths(ctx, listOf(src.path))
                return all
            }
            val target = w.resolve(t0, isDir) ?: return false
            w.current(src.name)
            if (target.path == t0.path && target.exists()) destAccess.delete(target)
            val renamed = if (target.path == t0.path) srcAccess.moveInto(src, destDir) else srcAccess is PlainAccess && src.renameTo(target)
            if (renamed) {
                w.fileDone()
                FileOps.forget(ctx, src)
                remember(w, target)
                return true
            }
            // The storage couldn't rename (some cards): copy, then delete; counted now.
            val size = FileOps.measure(listOf(src), { w.job.cancelled })
            w.addTotals(size.files - 1, size.bytes)
            return copyThenDelete(w, src, target, destAccess, srcAccess)
        }
        val target = w.resolve(t0, isDir) ?: return false
        return copyThenDelete(w, src, target, destAccess, srcAccess)
    }

    private fun copyThenDelete(w: FileJobs.Work, src: File, target: File, destAccess: FileAccess, srcAccess: FileAccess): Boolean {
        val copied = Originals()
        val all = copyTo(w, src, target, destAccess, copied)
        // 3.0: only what was copied goes from where it was, a file at a time, then the folders it left
        // empty. What couldn't be deleted is in both places; the copy is never taken back.
        val removed = copied.remove(srcAccess)
        FileOps.forgetPaths(w.ctx, removed.gone.map { it.path })
        w.stayedBehind += removed.stayed.size
        return all
    }

    /** Files that came by renaming go into the media index too (3.0: all of them, not a few hundred). */
    private fun remember(w: FileJobs.Work, f: File) {
        if (!f.isDirectory) {
            w.made(f)
            return
        }
        f.walkTopDown().filter { it.isFile }.forEach { w.made(it) }
    }

    /** Deletes [files] (folders with all they hold). */
    fun delete(w: FileJobs.Work, files: List<File>) {
        val ctx = w.ctx
        w.counting()
        val totals = FileOps.measure(files, { w.job.cancelled })
        w.check()
        w.totals(totals.files, -1)
        var failed = 0
        for (f in files) {
            w.check()
            val access = Volumes.accessFor(ctx, f) ?: throw FileProblem(CARD_TEXT)
            w.current(f.name)
            w.touched(f.parentFile)
            val gone = ArrayList<String>()
            val ok = if (access is PlainAccess) deletePlain(w, f, gone) else {
                val n = if (f.isDirectory) FileOps.measure(listOf(f)).files else 1
                access.delete(f).also { if (it) repeat(n) { w.fileDone() } }
            }
            if (!ok) failed++
            // 3.0: a folder only partly deleted keeps the rest in the Gallery.
            if (ok || access !is PlainAccess) FileOps.forget(ctx, f) else FileOps.forgetPaths(ctx, gone)
        }
        val gone = files.size - failed
        w.message(when {
            failed == 0 -> "Deleted " + if (files.size == 1) "“${files[0].name}”" else FileJobs.items(files.size)
            gone == 0 -> throw FileProblem("Swift couldn't delete " + if (files.size == 1) "“${files[0].name}”." else "these.")
            else -> "Deleted ${FileJobs.items(gone)}. $failed couldn't be deleted."
        })
    }

    private fun deletePlain(w: FileJobs.Work, f: File, gone: MutableList<String>): Boolean {
        w.check()
        var ok = true
        if (f.isDirectory) {
            f.listFiles()?.forEach { if (!deletePlain(w, it, gone)) ok = false }
        }
        val isFile = f.isFile
        if (!f.delete() && f.exists()) return false
        gone += f.path
        if (isFile) w.fileDone()
        return ok
    }
}

/**
 * What a move copied (3.0): each original file (as it was when copied) and folder with its copy, so
 * that only those leave where they were. Never a whole folder at once: one may have got new files
 * meanwhile, and a file still growing (a download being written) isn't the one that was copied.
 */
class Originals {
    /** The files copied whole, in the order they were copied. */
    val files = ArrayList<File>()

    /** The folders gone through, each after everything in it (the deepest first). */
    val folders = ArrayList<File>()

    /** Each original's copy. */
    val copyOf = HashMap<File, File>()

    private val sizes = HashMap<File, Pair<Long, Long>>()

    /** [orig] was [length] bytes, changed at [time], when it was copied to [copy]. */
    fun file(orig: File, copy: File, length: Long = orig.length(), time: Long = orig.lastModified()) {
        files += orig
        copyOf[orig] = copy
        sizes[orig] = length to time
    }

    fun folder(orig: File, copy: File) {
        folders += orig
        copyOf[orig] = copy
    }

    /** What [remove] did: the originals that went, and the files that couldn't. */
    class Removed(val gone: List<File>, val stayed: List<File>)

    /** Deletes the originals through [access]: the files one by one, then the folders left empty, deepest first. */
    fun remove(access: FileAccess): Removed = remove({ access.delete(it) }, { access.deleteEmptyFolder(it) })

    fun remove(deleteFile: (File) -> Boolean, deleteEmptyFolder: (File) -> Boolean): Removed {
        val gone = ArrayList<File>()
        val stayed = ArrayList<File>()
        for (f in files) {
            // Changed since it was copied (still being written): it stays.
            val was = sizes[f]
            val same = was == null || (f.length() == was.first && f.lastModified() == was.second)
            if (same && (runCatching { deleteFile(f) }.getOrDefault(false) || !f.exists())) gone += f else if (f.exists()) stayed += f
        }
        for (d in folders) {
            if (d.list()?.isEmpty() == true && runCatching { deleteEmptyFolder(d) }.getOrDefault(false)) gone += d
        }
        return Removed(gone, stayed)
    }
}
