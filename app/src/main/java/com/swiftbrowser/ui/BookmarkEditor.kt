package com.swiftbrowser.ui

import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.BookmarkRow
import com.swiftbrowser.core.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3.0.12: one bookmark's editor (its name, address and folder), shared by Bookmarks and the
 * browser's "Bookmarked · Edit" message. Before, it was Bookmarks' own, and the folder was a separate step.
 */
object BookmarkEditor {
    private const val TOP = "Top of the list"

    /** The bookmark's name, address and folder; [changed] runs once something was saved. */
    fun edit(a: AppCompatActivity, r: BookmarkRow, profileId: Long, changed: () -> Unit) {
        a.lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { folders(profileId) }
            if (!a.isFinishing) show(a, r, profileId, folders, changed)
        }
    }

    private fun show(a: AppCompatActivity, r: BookmarkRow, profileId: Long, folders: List<String>, changed: () -> Unit) {
        val pad = Ui.dp(a, 24)
        val name = EditText(a).apply {
            hint = "Name"
            setSingleLine()
            setText(r.title)
        }
        val address = EditText(a).apply {
            hint = "Address"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(r.url)
        }
        Ui.field(name)
        Ui.field(address)
        var folder = r.folder
        // Its folder, changed here too: the button says where it is, and offers the others.
        val place = MaterialButton(a, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setIconResource(R.drawable.ic_folder)
            text = folder.ifEmpty { TOP }
            isAllCaps = false
            setOnClickListener {
                chooseFolder(a, folders, folder) { picked ->
                    folder = picked
                    Motion.swapText(this, picked.ifEmpty { TOP })
                }
            }
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            // Starts where the title does.
            setPadding(pad, pad / 3, pad, 0)
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(a, Ui.S)
            })
            addView(place, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(a, Ui.S)
            })
        }
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle("Edit bookmark")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        // A wrong address keeps the dialog open with what was typed (2.6).
        val save = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        save?.setOnClickListener {
            val url = UrlUtils.toUrlOrSearch(address.text.toString())
            if (!UrlUtils.isHttp(url) || UrlUtils.searchQuery(url) != null && !address.text.contains('.')) {
                address.error = "That isn't a web address"
                address.requestFocus()
                return@setOnClickListener
            }
            save.isEnabled = false
            val title = name.text.toString().trim()
            val into = folder
            a.lifecycleScope.launch {
                // 3.0: an address another bookmark has can't be saved twice (it crashed Swift): the
                // dialog stays open and says so.
                val taken = withContext(Dispatchers.IO) {
                    val db = SwiftApp.db
                    val clash = !db.updateBookmark(r.id, title, url) && db.bookmark(profileId, url)?.let { it.id != r.id } == true
                    if (!clash && into != r.folder) db.setBookmarkFolder(r.id, into)
                    clash
                }
                if (taken) {
                    save.isEnabled = true
                    address.error = "That address is already bookmarked"
                    address.requestFocus()
                    return@launch
                }
                dialog.dismiss()
                changed()
            }
        }
    }

    /** Into a folder, a new one, or back to the top of the list (Bookmarks' "Move to folder"). */
    fun moveToFolder(a: AppCompatActivity, r: BookmarkRow, profileId: Long, changed: () -> Unit) {
        a.lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { folders(profileId) }
            if (a.isFinishing) return@launch
            chooseFolder(a, folders, r.folder) { name ->
                a.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SwiftApp.db.setBookmarkFolder(r.id, name) }
                    changed()
                    Messages.show(a, if (name.isEmpty()) "Moved to the top of the list" else "Moved to $name")
                }
            }
        }
    }

    /** The other folders, the top of the list when it is in one, and a new folder; [picked] gets the name ("" = the top). */
    private fun chooseFolder(a: AppCompatActivity, folders: List<String>, current: String, picked: (String) -> Unit) {
        val names = folders.filter { it != current }
        val choices = (if (current.isNotEmpty()) listOf(TOP) else emptyList()) + names + "New folder…"
        MaterialAlertDialogBuilder(a)
            .setTitle("Move to folder")
            .setItems(choices.toTypedArray()) { _, which ->
                when {
                    which == choices.lastIndex -> Sheets.askText(a, "New folder", "Folder name", "") { name ->
                        val n = name.trim().take(60)
                        if (n.isNotEmpty()) picked(n)
                    }
                    current.isNotEmpty() && which == 0 -> picked("")
                    else -> picked(choices[which])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun folders(profileId: Long): List<String> =
        SwiftApp.db.bookmarks(profileId).map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
}
