package com.swiftbrowser.ui

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.ChromeImport
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Passwords
import com.swiftbrowser.core.PasswordsCsv
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.Storage
import com.swiftbrowser.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 3.0.11: "Bring in from Chrome" (Settings → Storage & data → Backup, the Bookmarks screen's Import,
 * and a Takeout .zip or passwords .csv downloaded in Swift): Chrome's bookmarks and history from
 * Google Takeout, and its passwords from Google Password Manager's export (ChromeImport, PasswordsCsv).
 */
object ChromeImportUi {
    /** Takeout with only Chrome chosen. */
    const val TAKEOUT_URL = "https://takeout.google.com/settings/takeout/custom/chrome"

    /** Google Password Manager's settings, where "Export passwords" is. */
    const val PASSWORDS_URL = "https://passwords.google.com/options"

    /** A passwords file is read whole: one bigger than this was picked by mistake. */
    private const val MAX_CSV = 8 * 1024 * 1024

    /** The two steps, from Settings: [open] a page in a tab, [chooseZip] / [chooseCsv] the files. */
    fun sheet(a: Activity, open: (String) -> Unit, chooseZip: () -> Unit, chooseCsv: () -> Unit) {
        val col = Sheets.column(a)
        lateinit var dialog: BottomSheetDialog
        col.addView(Sheets.header(a, "Bring in from Chrome", "Chrome's own sync is closed to other browsers, so its data comes in through files Google makes for you."))
        col.addView(Sheets.label(a, "Bookmarks and history"))
        col.addView(Sheets.note(a, "Google Takeout makes a .zip of your Chrome data (Chrome is already chosen; keep the .zip type). It can take a few minutes, and Google emails you when it's ready. Download it in Swift and tap Bring into Swift, or choose it here."))
        col.addView(Sheets.row(a, R.drawable.ic_open_new, "Open Google Takeout") {
            dialog.dismiss()
            open(TAKEOUT_URL)
        })
        col.addView(Sheets.row(a, R.drawable.ic_folder, "Choose the .zip") {
            dialog.dismiss()
            chooseZip()
        })
        col.addView(Sheets.label(a, "Passwords"))
        col.addView(Sheets.note(a, "In Google Password Manager, Settings → Export passwords gives a .csv file of them. Chrome's own export works too."))
        col.addView(Sheets.row(a, R.drawable.ic_open_new, "Open Google Password Manager") {
            dialog.dismiss()
            open(PASSWORDS_URL)
        })
        col.addView(Sheets.row(a, R.drawable.ic_password, "Choose the .csv") {
            dialog.dismiss()
            chooseCsv()
        })
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    /** A Takeout .zip into the profile in use; the result says how much came, with Undo. */
    fun takeout(a: AppCompatActivity, uri: Uri, then: (() -> Unit)? = null) {
        val pid = Profiles.currentId
        val wait = Messages.sticky(a, "Bringing in Chrome's bookmarks and history…")
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val t = ChromeImport.readTakeout(a, uri) ?: return@withContext null
                    if (!t.chrome) return@withContext t to null
                    t to ChromeImport.add(SwiftApp.db, pid, t)
                } catch (e: Exception) {
                    L.w("Chrome import: couldn't read the .zip", e)
                    null
                }
            }
            wait?.dismiss()
            if (a.isFinishing) return@launch
            val (t, added) = result ?: run {
                Messages.show(a, "Couldn't read that file. Choose the .zip Google Takeout made.", long = true)
                return@launch
            }
            if (added == null) {
                Messages.show(a, "This .zip has no Chrome data. In Google Takeout, choose Chrome.", long = true)
                return@launch
            }
            L.d("Chrome import: ${added.bookmarks.size} of ${t.marks.size} bookmarks and ${added.pages.size} of ${t.pages.size} pages added")
            val text = addedText(added)
            if (added.bookmarks.isEmpty() && added.pages.isEmpty()) {
                Messages.show(a, text, long = true, onDismiss = then)
            } else {
                Messages.show(a, text, "Undo", long = true, onDismiss = then) {
                    a.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { ChromeImport.undo(SwiftApp.db, pid, added) }
                        Messages.show(a, "Taken out again")
                    }
                }
            }
        }
    }

    /** "Added 312 bookmarks and 4,120 history pages" (and what was already there). */
    fun addedText(added: ChromeImport.Added): String {
        val parts = listOfNotNull(
            added.bookmarks.size.takeIf { it > 0 }?.let { n -> if (n == 1) "1 bookmark" else "${"%,d".format(n)} bookmarks" },
            added.pages.size.takeIf { it > 0 }?.let { n -> if (n == 1) "1 history page" else "${"%,d".format(n)} history pages" },
        )
        val already = if (added.merged > 0) " (${"%,d".format(added.merged)} pages were already here)" else ""
        return if (parts.isEmpty()) "Nothing new: it was all in Swift already" else "Added ${parts.joinToString(" and ")}$already"
    }

    /**
     * A passwords .csv into the profile in use. The file holds them as plain text: the result offers
     * to delete it ([download]: it is one of Swift's downloads; otherwise the picked file, where the
     * app that keeps it allows).
     */
    fun passwords(a: AppCompatActivity, uri: Uri, download: Downloads.Item? = null, then: (() -> Unit)? = null) {
        val pid = Profiles.currentId
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bytes = a.contentResolver.openInputStream(uri)?.use { Storage.readAtMost(it, MAX_CSV) } ?: return@withContext null
                    val csv = PasswordsCsv.parse(String(bytes, Charsets.UTF_8)) ?: return@withContext Triple(-1, 0, 0)
                    val (added, already) = Passwords.importRows(pid, csv.rows)
                    Triple(added, already, csv.skipped)
                } catch (e: Exception) {
                    L.w("Chrome import: couldn't bring the passwords in", e)
                    null
                }
            }
            if (a.isFinishing) return@launch
            val (added, already, skipped) = result ?: run {
                Messages.show(a, "Couldn't read that file", long = true)
                return@launch
            }
            if (added < 0) {
                Messages.show(a, "That isn't a passwords file. Choose the .csv that Export passwords made.", long = true)
                return@launch
            }
            L.d("Chrome import: $added passwords added, $already already here, $skipped left out")
            val text = buildString {
                append(if (added == 1) "Added 1 password" else "Added ${"%,d".format(added)} passwords")
                val notes = listOfNotNull(
                    already.takeIf { it > 0 }?.let { "$it already there" },
                    skipped.takeIf { it > 0 }?.let { "$it for apps or empty, left out" },
                )
                if (notes.isNotEmpty()) append(" (${notes.joinToString(", ")})")
            }
            Messages.show(a, text, "Delete the file", long = true, onDismiss = then) { deleteFile(a, uri, download) }
        }
    }

    /** The passwords file (plain text) goes: a download of Swift's, or the picked file where its app allows. */
    private fun deleteFile(a: Activity, uri: Uri, download: Downloads.Item?) {
        val gone = try {
            if (download != null) {
                Downloads.remove(download.id, deleteFile = true)
                true
            } else if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() } == true
            } else {
                DocumentsContract.deleteDocument(a.contentResolver, uri)
            }
        } catch (e: Exception) {
            L.d("Chrome import: the passwords file couldn't be deleted (${e.javaClass.simpleName})")
            false
        }
        Messages.show(a, if (gone) "The passwords file is deleted" else "Couldn't delete it here: delete it in your phone's Files app", long = !gone)
    }
}
