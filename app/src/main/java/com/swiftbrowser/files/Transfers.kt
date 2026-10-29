package com.swiftbrowser.files

import android.os.StatFs
import com.swiftbrowser.core.UrlUtils
import java.io.File
import java.io.FileInputStream

/**
 * Copying, moving and deleting (2.7), run as file jobs. Moving on one storage renames (instant);
 * between the phone and a card it copies, then deletes the original. Folders already at the
 * destination are merged; for files with a name already taken the user chooses.
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
        w.counting()
        val sizes = sources.associateWith { FileOps.measure(listOf(it), { w.job.cancelled }) }
        w.check()
        w.totals(sizes.values.sumOf { it.files }, sizes.values.sumOf { it.bytes })
        val destRoot = Volumes.of(ctx, dest)?.root?.path
        // Moves within one storage take no room; copies need it.
        val needed = sources.filter { !move || Volumes.of(ctx, it)?.root?.path != destRoot }.sumOf { sizes[it]?.bytes ?: 0L }
        val free = runCatching { StatFs(dest.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (needed > free) {
            throw FileProblem("There isn't enough free space: this needs ${UrlUtils.formatBytes(needed)}, and ${UrlUtils.formatBytes(free)} is free.")
        }
        w.touched(dest)
        var done = 0
        for (src in sources) {
            w.check()
            w.touched(src.parentFile)
            val ok = if (move) moveItem(w, src, dest, destAccess, sizes[src]) else copyItem(w, src, dest, destAccess)
            if (ok) done++
        }
        val what = if (sources.size == 1) "“${sources[0].name}”" else FileJobs.items(sources.size)
        w.message(buildString {
            append(if (move) "Moved " else "Copied ").append(what)
            if (w.job.skipped > 0) append(", skipped ").append(w.job.skipped)
        })
        if (done == 0 && w.job.skipped == 0) throw FileProblem(if (move) "Nothing was moved." else "Nothing was copied.")
    }

    /** Copies [src] into [destDir] under its own name (or the one the user chose). True when all of it was copied. */
    private fun copyItem(w: FileJobs.Work, src: File, destDir: File, access: FileAccess): Boolean {
        w.check()
        val t0 = File(destDir, src.name)
        val target = w.resolve(t0, src.isDirectory, sameAsSource = t0.path == src.path) ?: run {
            if (!src.isDirectory) w.progress(src.length())
            return false
        }
        return copyTo(w, src, target, access)
    }

    private fun copyTo(w: FileJobs.Work, src: File, target: File, access: FileAccess): Boolean {
        if (src.isDirectory) {
            if (!target.isDirectory && !access.mkdir(target)) throw FileProblem("Swift couldn't make the folder “${target.name}”.")
            var all = true
            for (c in src.listFiles().orEmpty()) if (!copyItem(w, c, target, access)) all = false
            access.setTime(target, src.lastModified())
            return all
        }
        w.current(src.name)
        w.write(target, access, src.lastModified()) { out -> FileInputStream(src).use { w.copy(it, out) } }
        w.fileDone()
        return true
    }

    /** Moves [src] into [destDir]: a rename on the same storage, otherwise a copy then a delete. */
    private fun moveItem(w: FileJobs.Work, src: File, destDir: File, destAccess: FileAccess, size: FileOps.Totals?): Boolean {
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
                var all = true
                for (c in src.listFiles().orEmpty()) if (!moveItem(w, c, t0, destAccess, null)) all = false
                if (all) srcAccess.delete(src)
                return all
            }
            val target = w.resolve(t0, isDir) ?: return false
            w.current(src.name)
            val sizeNow = size ?: FileOps.measure(listOf(src))
            if (target.path == t0.path && target.exists()) destAccess.delete(target)
            val renamed = if (target.path == t0.path) srcAccess.moveInto(src, destDir) else srcAccess is PlainAccess && src.renameTo(target)
            if (renamed) {
                w.progress(sizeNow.bytes)
                repeat(sizeNow.files) { w.fileDone() }
                FileOps.forget(ctx, src)
                remember(w, target)
                return true
            }
            // The storage couldn't rename (some cards): copy, then delete.
            return copyThenDelete(w, src, target, destAccess, srcAccess)
        }
        val target = w.resolve(t0, isDir) ?: return false
        return copyThenDelete(w, src, target, destAccess, srcAccess)
    }

    private fun copyThenDelete(w: FileJobs.Work, src: File, target: File, destAccess: FileAccess, srcAccess: FileAccess): Boolean {
        val all = copyTo(w, src, target, destAccess)
        // Only what was fully copied goes from where it was.
        if (all) {
            srcAccess.delete(src)
            FileOps.forget(w.ctx, src)
        }
        return all
    }

    /** Files that came by renaming go into the media index too (a few hundred at most). */
    private fun remember(w: FileJobs.Work, f: File) {
        if (!f.isDirectory) {
            w.made(f)
            return
        }
        f.walkTopDown().filter { it.isFile }.take(300).forEach { w.made(it) }
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
            val ok = if (access is PlainAccess) deletePlain(w, f) else {
                val n = if (f.isDirectory) FileOps.measure(listOf(f)).files else 1
                access.delete(f).also { if (it) repeat(n) { w.fileDone() } }
            }
            if (!ok) failed++
            FileOps.forget(ctx, f)
        }
        val gone = files.size - failed
        w.message(when {
            failed == 0 -> "Deleted " + if (files.size == 1) "“${files[0].name}”" else FileJobs.items(files.size)
            gone == 0 -> throw FileProblem("Swift couldn't delete " + if (files.size == 1) "“${files[0].name}”." else "these.")
            else -> "Deleted ${FileJobs.items(gone)}. $failed couldn't be deleted."
        })
    }

    private fun deletePlain(w: FileJobs.Work, f: File): Boolean {
        w.check()
        var ok = true
        if (f.isDirectory) {
            f.listFiles()?.forEach { if (!deletePlain(w, it)) ok = false }
        }
        val isFile = f.isFile
        if (!f.delete() && f.exists()) return false
        if (isFile) w.fileDone()
        return ok
    }
}
