package com.swiftbrowser.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.files.FileOps
import com.swiftbrowser.files.Volumes
import java.io.File

/**
 * Deleting pictures, videos and music from the screens that show them (2.9), in one place: into
 * Files' recycle bin (with Undo; kept as long as Settings says), or for good when the bin can't take
 * them (it asks then). Either way they leave Android's media index, so galleries stop listing them.
 *
 * The caller asks first (and gets Android's permission, and an SD card's access, beforehand): on
 * a card Swift may not change yet, a file is skipped and not counted.
 */
object MediaDelete {
    /**
     * A file under its real name: other apps often hand "/sdcard/…", a link to the phone's storage,
     * which the recycle bin wouldn't recognise as being on it (2.9).
     */
    fun real(f: File): File = try {
        f.canonicalFile
    } catch (_: Exception) {
        f
    }

    /** Whether [files] go to the recycle bin (with Undo) rather than for good. */
    fun canUseBin(a: Activity, files: List<File>): Boolean =
        com.swiftbrowser.files.FilesSettings.useRecycleBin && files.all { com.swiftbrowser.files.RecycleBin.canHold(a, real(it)) }

    private val worker = Background.single("media-delete")
    private val main = Handler(Looper.getMainLooper())

    /** Deletes [files] off the main thread; [done] then runs on it with how many went. */
    fun delete(a: Activity, given: List<File>, done: (deleted: Int) -> Unit) {
        val files = given.map { real(it) }
        if (canUseBin(a, files)) {
            // The bin also says so when Undo brings them back: only the move itself is reported.
            var reported = false
            com.swiftbrowser.files.RecycleBin.delete(a, files) {
                if (!reported) {
                    reported = true
                    done(files.count { !it.exists() })
                }
            }
            return
        }
        val app = a.applicationContext
        worker.execute {
            var deleted = 0
            for (f in files) {
                val gone = try {
                    val access = Volumes.accessFor(app, f)
                    access != null && access.delete(f) && !f.exists()
                } catch (e: Exception) {
                    L.w("Couldn't delete a file", e)
                    false
                }
                if (gone) {
                    FileOps.forget(app, f)
                    deleted++
                }
            }
            main.post { done(deleted) }
        }
    }
}
