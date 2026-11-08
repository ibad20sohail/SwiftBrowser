package com.swiftbrowser.files

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.core.L
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.ui.MainActivity
import com.swiftbrowser.ui.Messages
import java.io.File

/**
 * Opening files from the Files screen (2.7): documents in Swift's viewer; web pages in a Swift tab
 * (Back there returns to Files); apps with Android's installer; the rest in another app. Sharing
 * goes through Swift's file provider (Android refuses file:// links).
 *
 * 2.8: documents, pictures, videos and music open on screens of their own over Files (Back returns
 * to the folder), no longer in browser tabs.
 *
 * 3.0: a file of Private's opens in Swift's viewers marked secure (AppFiles.EXTRA_SECURE): the
 * player, the picture viewer and the document viewer keep themselves out of screenshots and the
 * recent-apps picture then.
 */
object FileOpener {
    /**
     * Opens [f]. True when Swift's browser now shows it (the Files screen closes then; Back in
     * that tab brings it back). 3.0: [secure] for a file of Private's.
     */
    fun open(a: Activity, f: File, name: String = f.name, secure: Boolean = false): Boolean {
        val ext = FileKind.extension(name)
        return when {
            ext == "apk" -> {
                install(a, f)
                false
            }
            secure && Docs.kindOf(name, null) != null -> {
                if (!openDocumentSecurely(a, f, name)) Messages.show(a, "Swift can't show “$name”")
                false
            }
            Docs.kindOf(name, null) != null -> {
                if (!Docs.open(a, Uri.fromFile(f), name)) Messages.show(a, "Swift can't show “$name”")
                false
            }
            com.swiftbrowser.ui.PlayerActivity.kindOf(name) != null -> {
                if (secure) a.startActivity(com.swiftbrowser.ui.PlayerActivity.intentFor(a, f).putExtra(AppFiles.EXTRA_SECURE, true))
                else com.swiftbrowser.ui.PlayerActivity.open(a, f)
                false
            }
            com.swiftbrowser.ui.MediaViewerActivity.kindOf(name) == com.swiftbrowser.ui.MediaViewerActivity.Kind.IMAGE -> {
                if (secure) {
                    a.startActivity(
                        Intent(a, com.swiftbrowser.ui.MediaViewerActivity::class.java)
                            .putExtra(com.swiftbrowser.ui.MediaViewerActivity.EXTRA_PATH, f.path)
                            .putExtra(AppFiles.EXTRA_SECURE, true),
                    )
                } else {
                    com.swiftbrowser.ui.MediaViewerActivity.open(a, f)
                }
                false
            }
            FileKind.showsInTab(name) -> inSwift(a, name) {
                // The engine plays and shows these itself; MainActivity shows the tab when it comes up.
                TabManager.newTab(Uri.fromFile(f).toString(), incognito = false).backTo = Docs.BACK_FILES
                true
            }
            else -> {
                openWith(a, f, name)
                false
            }
        }
    }

    /**
     * 3.0: a document of Private's in Swift's viewer: as an incognito one (kept out of the recent
     * documents) and marked secure. False when Swift can't show it.
     */
    fun openDocumentSecurely(a: Activity, f: File, name: String = f.name): Boolean {
        val uri = Docs.shareUri(a, f) ?: return false
        val id = Docs.register(a, uri, name, incognito = true) ?: return false
        a.startActivity(
            Intent(a, com.swiftbrowser.ui.DocumentActivity::class.java)
                .putExtra(com.swiftbrowser.ui.DocumentActivity.EXTRA_ID, id)
                .putExtra(com.swiftbrowser.ui.DocumentActivity.EXTRA_INCOGNITO, true)
                .putExtra(AppFiles.EXTRA_SECURE, true),
        )
        return true
    }

    private fun inSwift(a: Activity, name: String, show: () -> Boolean): Boolean {
        val ok = try {
            show()
        } catch (e: Exception) {
            L.w("Couldn't open a file in Swift", e)
            false
        }
        if (!ok) {
            Messages.show(a, "Swift can't show “$name”")
            return false
        }
        L.d("Opening a file from Files (${FileKind.extension(name)})")
        a.startActivity(Intent(a, MainActivity::class.java))
        return true
    }

    /** Android's "Open with" list (not Swift itself). */
    fun openWith(a: Activity, f: File, name: String = f.name) {
        val uri = Docs.shareUri(a, f)
        if (uri == null) {
            Messages.show(a, "Swift couldn't hand this file to another app")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, FileOps.mimeOf(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val chooser = Intent.createChooser(intent, "Open with")
            // Swift's own screens too (3.0): they refuse links to Swift's own files coming "from another
            // app", and Files opens these files in them directly anyway.
            chooser.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(
                    ComponentName(a, MainActivity::class.java),
                    ComponentName(a, com.swiftbrowser.ui.DocumentActivity::class.java),
                    ComponentName(a, com.swiftbrowser.ui.MediaViewerActivity::class.java),
                    ComponentName(a, com.swiftbrowser.ui.PlayerActivity::class.java),
                ),
            )
            a.startActivity(chooser)
        } catch (e: Exception) {
            Messages.show(a, "No app on your phone can open this file")
        }
    }

    /** Android's installer for an app file (.apk). */
    fun install(a: Activity, f: File) {
        val uri = Docs.shareUri(a, f)
        if (uri == null) {
            Messages.show(a, "Swift couldn't hand this app to Android's installer")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Messages.show(a, "Your phone can't install apps from files")
        }
    }

    /** Shares files (not folders) with another app. */
    fun share(a: Activity, files: List<File>) {
        val list = files.filter { it.isFile }
        if (list.isEmpty()) {
            Messages.show(a, "Folders can't be shared. Compress them into a ZIP first.")
            return
        }
        val uris = ArrayList<Uri>(list.mapNotNull { Docs.shareUri(a, it) })
        if (uris.isEmpty()) {
            Messages.show(a, "Swift couldn't hand these files to another app")
            return
        }
        val mimes = list.map { FileOps.mimeOf(it.name) }.distinct()
        val type = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.takeIf { it != "*" }?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        // The receiving app may read every one of them (Android 7 grants only what the clip lists).
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size < list.size) Messages.show(a, "${list.size - uris.size} of them couldn't be shared")
        try {
            a.startActivity(Intent.createChooser(intent, if (uris.size == 1) "Share ${list[0].name}" else "Share ${uris.size} files"))
        } catch (_: Exception) {
            Messages.show(a, "No app on your phone can take these files")
        }
    }
}
