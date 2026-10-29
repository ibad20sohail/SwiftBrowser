package com.swiftbrowser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.DocIcon
import com.swiftbrowser.docs.DocThumbs
import com.swiftbrowser.docs.Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Documents (2.4): the documents opened in Swift lately, then every PDF, Word, Excel, PowerPoint,
 * e-book and text file on the phone (Android's media index), newest first, with filters and search.
 * Without storage access, Android's own file picker still opens any of them.
 *
 * 2.5: starred documents first; sorting by date, name or size; one folder at a time; files on the
 * memory card marked as such; rename, delete (with Undo) and home screen shortcuts.
 */
class DocumentsActivity : ListActivity() {
    /** One document in the list: [uri] is where it is, [doc] its record when Swift opened it before. */
    private class Entry(
        val uri: String, val name: String, val kind: Docs.Kind, val size: Long, val time: Long, val where: String, val doc: Docs.Doc?,
        val path: String? = null,
    )

    private sealed class Row {
        class Header(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
        class Item(val e: Entry) : Row()
    }

    private val filters = listOf<Pair<String, Set<Docs.Kind>?>>(
        "All" to null,
        "PDF" to setOf(Docs.Kind.PDF),
        "Word" to setOf(Docs.Kind.WORD, Docs.Kind.OLD_WORD, Docs.Kind.ODT, Docs.Kind.RTF),
        "Excel" to setOf(Docs.Kind.SHEET),
        "PowerPoint" to setOf(Docs.Kind.SLIDES, Docs.Kind.OLD_SLIDES, Docs.Kind.ODP),
        "E-books" to setOf(Docs.Kind.EPUB),
        "Text" to setOf(Docs.Kind.TEXT),
        "ZIP" to setOf(Docs.Kind.ZIP),
    )
    private var filter = 0
    private var recent: List<Entry> = emptyList()

    /** Documents taken off the list, forgotten when their Undo runs out (or the screen closes). */
    private val pendingForget = LinkedHashMap<String, Runnable>()
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onPause() {
        for ((_, r) in pendingForget.toList()) {
            undoHandler.removeCallbacks(r)
            r.run()
        }
        pendingForget.clear()
        super.onPause()
    }
    private var phone: List<Entry> = emptyList()
    private var starred: List<Entry> = emptyList()
    private var starredUris: Set<String> = emptySet()
    private var hiddenUris: Set<String> = emptySet()
    private var folder: String? = null
    private val renderSoon = Runnable { render() }

    private val leaveFolder = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (search.text.isNotEmpty()) {
                search.setText("")
            } else {
                folder = null
                render()
            }
        }
    }
    private var loaded = false
    private val adapter = Adapter()

    /** Card roots (other than the phone's own storage), for "SD card" in the list. */
    private val cardRoots: List<String> by lazy {
        getExternalFilesDirs(null).drop(1).filterNotNull().map { it.path.substringBefore("/Android/data") }
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) load() else render()
    }

    private var afterWriteAccess: (() -> Unit)? = null
    private val askWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val run = afterWriteAccess
        afterWriteAccess = null
        if (granted) run?.invoke() else Messages.show(this, "Swift needs access to your files to change them")
    }

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        open(uri, null)
    }

    private val prefs get() = SwiftApp.prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Documents"
        val iconColor = Palette.color(this, R.color.text_primary)
        toolbar.menu.add(0, MENU_SORT, 0, "Sort").apply {
            setIcon(Ui.icon(this@DocumentsActivity, R.drawable.ic_sort, iconColor))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, MENU_BROWSE, 1, "Browse files").apply {
            setIcon(Ui.icon(this@DocumentsActivity, R.drawable.ic_folder, iconColor))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, MENU_FOLDERS, 2, "Folders").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        // 2.7: all the phone's files, in Swift's file manager.
        toolbar.menu.add(0, MENU_FILES, 3, "Files").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_BROWSE -> browse()
                MENU_SORT -> chooseSort()
                MENU_FOLDERS -> chooseFolder()
                MENU_FILES -> FilesActivity.open(this)
            }
            true
        }
        // Back leaves a folder (after a search is cleared) before closing (2.6).
        onBackPressedDispatcher.addCallback(this, leaveFolder)
        search.visibility = View.VISIBLE
        search.hint = "Search documents"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // After a pause in typing (2.6): each render sorts every document.
                list.removeCallbacks(renderSoon)
                list.postDelayed(renderSoon, 150)
            }
        })
        addFilterChips()
        list.adapter = adapter
        list.itemAnimator = null
        if (hasAccess() || Build.VERSION.SDK_INT > 32) load() else askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAccess() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun canWrite() = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Runs [action] once Swift may change files (asking Android first if needed). */
    private fun withWriteAccess(action: () -> Unit) {
        if (canWrite() || Build.VERSION.SDK_INT > 28) {
            action()
        } else {
            afterWriteAccess = action
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun addFilterChips() {
        val box = list.parent as? ViewGroup ?: return
        val column = box.parent as? LinearLayout ?: return
        val chips = Sheets.chips(this, filters.map { it.first }, 0) { i ->
            filter = i
            render()
        }.apply {
            isSingleLine = true
            setPadding(Ui.dp(this@DocumentsActivity, 16), 0, Ui.dp(this@DocumentsActivity, 16), 0)
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        column.addView(scroller, column.indexOfChild(box), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onResume() {
        super.onResume()
        // Back from a document: what was read moves to the top of Recent.
        if (loaded) lifecycleScope.launch { recent = withContext(Dispatchers.IO) { loadRecent() }; render() }
    }

    private fun load() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { Triple(loadRecent(), if (hasAccess()) loadPhone() else emptyList(), loadStarred()) }
            recent = all.first
            phone = all.second
            starred = all.third
            starredUris = starred.map { it.uri }.toSet()
            loaded = true
            render()
            Motion.fadeIn(list, fromDy = 8)
        }
    }

    private fun loadRecent(): List<Entry> = SwiftApp.db.recentDocuments(Profiles.currentId, 6).map { d ->
        Entry(d.uri, d.name, d.kind, d.size, d.opened, describeWhere(d.uri, d.web), d, fileOf(d.uri))
    }

    private fun loadStarred(): List<Entry> {
        val known = SwiftApp.db.recentDocuments(Profiles.currentId, 200).associateBy { it.uri }
        return SwiftApp.db.starredDocuments(Profiles.currentId).mapNotNull { s ->
            val kind = Docs.Kind.ofKey(s.kind) ?: return@mapNotNull null
            val path = fileOf(s.uri)
            // A starred file that was deleted elsewhere drops out of the list.
            if (path != null && !File(path).exists()) return@mapNotNull null
            Entry(s.uri, s.name, kind, s.size, path?.let { File(it).lastModified() } ?: s.added, describeWhere(s.uri, known[s.uri]?.web), known[s.uri], path)
        }
    }

    private fun fileOf(uri: String): String? = Uri.parse(uri).takeIf { it.scheme == "file" }?.path

    private fun describeWhere(uri: String, web: String?): String {
        val u = Uri.parse(uri)
        return when {
            u.scheme == "file" && u.path.orEmpty().startsWith(cacheDir.path) -> web?.let { UrlUtils.host(it)?.removePrefix("www.") + " · not kept" } ?: "From a ZIP · not kept"
            u.scheme == "file" -> folderName(u.path.orEmpty())
            web != null -> UrlUtils.host(web)?.removePrefix("www.") ?: "Web"
            else -> "From another app"
        }
    }

    private fun folderName(path: String): String {
        val parent = File(path).parentFile ?: return ""
        val card = cardRoots.firstOrNull { path.startsWith("$it/") }
        if (card != null) return if (parent.path == card) "SD card" else "SD card › " + parent.name
        val root = android.os.Environment.getExternalStorageDirectory().path
        return if (parent.path == root) "Phone" else parent.name
    }

    /** Every document Android's media index knows about, newest first. */
    private fun loadPhone(): List<Entry> {
        val exts = Docs.Kind.entries.flatMap { it.extensions }
        val selection = exts.joinToString(" OR ", "(", ")") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" }
        val args = exts.map { "%.$it" }.toTypedArray()
        val out = ArrayList<Entry>()
        val known = SwiftApp.db.recentDocuments(Profiles.currentId, 200).associateBy { it.uri }
        try {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED),
                selection, args, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 3000) {
                    val path = c.getString(0) ?: continue
                    val name = path.substringAfterLast('/')
                    // Swift's own copies and other apps' private caches aren't the user's documents.
                    if (name.startsWith(".") || path.contains("/Android/data/")) continue
                    val kind = Docs.kindOf(name, null) ?: continue
                    val uri = Uri.fromFile(File(path)).toString()
                    out += Entry(uri, name, kind, c.getLong(1), c.getLong(2) * 1000, folderName(path), known[uri], path)
                }
            }
        } catch (e: Exception) {
            com.swiftbrowser.core.L.w("Couldn't list the phone's documents", e)
        }
        return out
    }

    private fun sorted(list: List<Entry>): List<Entry> = when (prefs.sp.getInt(PREF_SORT, 0)) {
        1 -> list.sortedBy { it.name.lowercase() }
        2 -> list.sortedByDescending { it.size }
        else -> list.sortedByDescending { it.time }
    }

    private fun render() {
        leaveFolder.isEnabled = folder != null
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val kinds = filters[filter].second
        val dir = folder
        val keep = { e: Entry ->
            e.uri !in hiddenUris && (kinds == null || e.kind in kinds) && (q.isEmpty() || e.name.lowercase().contains(q)) &&
                (dir == null || e.path?.let { File(it).parent } == dir)
        }
        val rows = ArrayList<Row>()
        val shown = HashSet<String>()
        if (dir != null) {
            rows += Row.Header("In " + folderName("$dir/x"), "Show all") {
                folder = null
                render()
            }
        }
        val s = sorted(starred.filter(keep))
        if (s.isNotEmpty()) {
            rows += Row.Header("Starred")
            s.forEach { rows += Row.Item(it); shown += it.uri }
        }
        val r = if (dir == null) recent.filter { keep(it) && it.uri !in shown } else emptyList()
        if (r.isNotEmpty()) {
            rows += Row.Header("Opened in Swift")
            r.forEach { rows += Row.Item(it); shown += it.uri }
        }
        val p = sorted(phone.filter { keep(it) && it.uri !in shown })
        if (p.isNotEmpty()) {
            rows += Row.Header(if (dir == null) "On this phone" else "Files")
            p.forEach { rows += Row.Item(it) }
        }
        adapter.rows = rows
        if (rows.none { it is Row.Item }) showNothing(q.isNotEmpty() || kinds != null || dir != null) else empty.visibility = View.GONE
    }

    /** Nothing to list: why, and what to do (allow access, or pick a file with Android's picker). */
    private fun showNothing(filtered: Boolean) {
        empty.removeAllViews()
        val noAccess = !hasAccess() && Build.VERSION.SDK_INT <= 32
        val state = Ui.emptyState(
            this, R.drawable.ic_documents,
            when {
                !loaded -> "Looking for documents…"
                filtered -> "No documents match"
                noAccess -> "See your phone's documents"
                else -> "No documents on this phone yet"
            },
            when {
                !loaded -> null
                filtered -> "Try another word, filter or folder."
                noAccess -> "Allow Swift to see your files to list your PDFs, Word, Excel and PowerPoint files here. Or pick one with Browse files."
                else -> "PDFs, Word, Excel and PowerPoint files you download or receive show here."
            },
        )
        if (loaded && !filtered) {
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, Ui.dp(this@DocumentsActivity, 18), 0, 0)
            }
            if (noAccess) {
                buttons.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = "Allow access"
                    setOnClickListener { requestAccess() }
                })
            }
            buttons.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Browse files"
                setOnClickListener { browse() }
            })
            state.addView(buttons)
        }
        empty.addView(state, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (empty.visibility != View.VISIBLE) Motion.fadeIn(empty, fromDy = 12)
    }

    private fun requestAccess() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) || !hasAsked) {
            hasAsked = true
            askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Android won't ask again ("Don't ask again" was ticked): its settings for Swift.
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private var hasAsked = false

    /** Android's own file picker: any document, also from Drive or a memory card. */
    private fun browse() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, Docs.allMimeTypes)
        }
        try {
            pick.launch(intent)
        } catch (_: Exception) {
            Messages.show(this, "There's no file picker on this phone")
        }
    }

    private fun chooseSort() {
        val options = arrayOf("Newest first", "Name", "Biggest first")
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort documents")
            .setSingleChoiceItems(options, prefs.sp.getInt(PREF_SORT, 0)) { d, which ->
                prefs.sp.edit().putInt(PREF_SORT, which).apply()
                d.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** The folders that hold documents, most first: one is shown at a time. */
    private fun chooseFolder() {
        val counts = phone.mapNotNull { it.path?.let { p -> File(p).parent } }.groupingBy { it }.eachCount()
        if (counts.isEmpty()) {
            Messages.show(this, if (hasAccess()) "No folders with documents yet" else "Allow Swift to see your files first")
            return
        }
        val dirs = counts.entries.sortedByDescending { it.value }.map { it.key }
        val labels = dirs.map { d -> folderName("$d/x") + "  ·  " + counts[d] }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Folders")
            .setItems(labels) { _, which ->
                folder = dirs[which]
                render()
                list.scrollToPosition(0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun open(uri: Uri, name: String?) {
        if (Docs.open(this, uri, name, backTo = Docs.BACK_DOCUMENTS)) {
            finish()
        } else {
            Messages.show(this, "Swift can't show this kind of file")
        }
    }

    private fun hold(e: Entry) {
        val doc = e.doc
        val isStarred = e.uri in starredUris
        val onPhone = e.path != null && !e.path.startsWith(cacheDir.path) && !e.path.startsWith(filesDir.path)
        val actions = arrayListOf(
            Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { open(Uri.parse(e.uri), e.name) },
            Sheets.Action(R.drawable.ic_share, "Share", primary = true) { Docs.handOver(this, asDoc(e), share = true) },
            Sheets.Action(if (isStarred) R.drawable.ic_star else R.drawable.ic_star_border, if (isStarred) "Remove star" else "Star", primary = true) { toggleStar(e) },
            Sheets.Action(R.drawable.ic_reopen, "Open with another app") { Docs.handOver(this, asDoc(e), share = false) },
            Sheets.Action(R.drawable.ic_home_add, "Add to home screen") { addShortcut(e) },
        )
        if (onPhone) {
            actions += Sheets.Action(R.drawable.ic_folder, "Show in Files") { FilesActivity.open(this, File(e.path!!).parentFile) }
            actions += Sheets.Action(R.drawable.ic_edit, "Rename") { rename(e) }
            actions += Sheets.Action(R.drawable.ic_delete, "Delete", danger = true) { delete(e) }
        }
        if (doc != null) {
            actions += Sheets.Action(R.drawable.ic_close, "Remove from Opened in Swift") {
                // Off the list at once, forgotten once the Undo is gone (2.6).
                val before = recent
                recent = recent.filter { it.doc?.id != doc.id }
                render()
                val commit = Runnable {
                    pendingForget.remove(doc.id)
                    lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.forgetDocument(doc.id) }
                }
                pendingForget[doc.id] = commit
                undoHandler.postDelayed(commit, 5600)
                Messages.show(this, "Removed from Opened in Swift", "Undo") {
                    if (pendingForget.remove(doc.id) != null) {
                        undoHandler.removeCallbacks(commit)
                        recent = before
                        render()
                    }
                }
            }
        }
        val details = listOfNotNull(e.kind.label, e.size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) }, e.where.ifBlank { null }).joinToString(" · ")
        Sheets.actions(this, e.name, details, actions, docIcon(e.kind))
    }

    /** The document's mark (the kind's colour and badge) for the sheet's header. */
    private fun docIcon(kind: Docs.Kind): android.graphics.Bitmap {
        val size = Ui.dp(this, 40)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        DocIcon(kind, size).apply { setBounds(0, 0, size, size) }.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    private fun asDoc(e: Entry) = e.doc ?: Docs.Doc("", e.uri, e.name, e.kind, e.size, null, null, 0f, 0, 0, 0)

    private fun toggleStar(e: Entry) {
        val profile = Profiles.currentId
        val on = e.uri !in starredUris
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (on) SwiftApp.db.starDocument(profile, e.uri, e.name, e.kind.key, e.size) else SwiftApp.db.unstarDocument(profile, e.uri)
            }
            starred = withContext(Dispatchers.IO) { loadStarred() }
            starredUris = starred.map { it.uri }.toSet()
            render()
            Messages.show(this@DocumentsActivity, if (on) "Starred: it stays at the top" else "Star removed")
        }
    }

    private fun addShortcut(e: Entry) {
        lifecycleScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val id = e.doc?.id ?: Docs.remember(this@DocumentsActivity, Uri.parse(e.uri), e.name, e.kind, e.size)
                SwiftApp.db.document(id)
            }
            if (doc != null) Docs.pinShortcut(this@DocumentsActivity, doc)
        }
    }

    /** A new name for a file on the phone (its type, the ending, stays). */
    private fun rename(e: Entry) {
        val path = e.path ?: return
        val ext = e.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == e.name) "" else ".$it" }
        val base = e.name.removeSuffix(ext)
        Sheets.askText(this, "Rename", "Name", base) { input ->
            val name = UrlUtils.sanitizeFileName(input.trim()).ifBlank { return@askText }
            if (name == base) return@askText
            withWriteAccess {
                lifecycleScope.launch {
                    val from = File(path)
                    val to = File(from.parentFile, name + ext)
                    val ok = withContext(Dispatchers.IO) {
                        if (to.exists() || !from.renameTo(to)) return@withContext false
                        try {
                            contentResolver.delete(MediaStore.Files.getContentUri("external"), MediaStore.Files.FileColumns.DATA + "=?", arrayOf(from.path))
                        } catch (_: Exception) {
                        }
                        MediaScannerConnection.scanFile(this@DocumentsActivity, arrayOf(to.path), null, null)
                        val newUri = Uri.fromFile(to).toString()
                        SwiftApp.db.renameDocument(e.uri, newUri, to.name, Docs.idFor(newUri, Profiles.currentId))
                        true
                    }
                    if (!ok) {
                        Messages.show(this@DocumentsActivity, if (to.exists()) "There's already a file called ${to.name}" else "Swift couldn't rename this file")
                        return@launch
                    }
                    // Android's media index learns the new name a moment later: the list changes now.
                    val newUri = Uri.fromFile(to).toString()
                    phone = phone.map { if (it.uri == e.uri) Entry(newUri, to.name, it.kind, it.size, it.time, it.where, null, to.path) else it }
                    reload(phoneToo = false)
                    Messages.show(this@DocumentsActivity, "Renamed to ${to.name}")
                }
            }
        }
    }

    /** Deletes a file from the phone, with a moment to change one's mind (Undo). */
    private fun delete(e: Entry) {
        val path = e.path ?: return
        withWriteAccess {
            removeLater(e.uri, "Deleted ${e.name}", hide = {
                hiddenUris = hiddenUris + e.uri
                render()
            }, restore = {
                hiddenUris = hiddenUris - e.uri
                render()
            }) {
                val app = applicationContext
                lifecycleScope.launch(Dispatchers.IO) {
                    val f = File(path)
                    if (f.delete() || !f.exists()) {
                        try {
                            app.contentResolver.delete(MediaStore.Files.getContentUri("external"), MediaStore.Files.FileColumns.DATA + "=?", arrayOf(path))
                        } catch (_: Exception) {
                        }
                        SwiftApp.db.forgetDocumentUri(e.uri)
                    } else {
                        launch(Dispatchers.Main) {
                            hiddenUris = hiddenUris - e.uri
                            if (!isFinishing) {
                                render()
                                Messages.show(this@DocumentsActivity, "Swift couldn't delete ${e.name}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun reload(phoneToo: Boolean = true) {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { Triple(loadRecent(), if (phoneToo && hasAccess()) loadPhone() else phone, loadStarred()) }
            recent = all.first
            phone = all.second
            starred = all.third
            starredUris = starred.map { it.uri }.toSet()
            render()
        }
    }

    // ------------------------------------------------------------------ the list

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@DocumentsActivity
            if (viewType == 0) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                row.addView(Ui.text(ctx, Ui.Type.SECTION, "").apply { id = R.id.row_title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Ui.text(ctx, Ui.Type.BODY, "").apply {
                    id = R.id.row_subtitle
                    setTextColor(Palette.color(ctx, R.color.accent))
                    setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
                    setBackgroundResource(RowHolder.selectableBackground(ctx))
                })
                return Holder(row)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 10))
                minimumHeight = Ui.dp(ctx, 72)
                setBackgroundResource(RowHolder.selectableBackground(ctx))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val thumb = ImageView(ctx).apply {
                id = R.id.row_icon
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.shape(ctx, Palette.color(ctx, R.color.surface_alt), 8, Palette.color(ctx, R.color.outline))
                setPadding(Ui.dp(ctx, 1), Ui.dp(ctx, 1), Ui.dp(ctx, 1), Ui.dp(ctx, 1))
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(thumb, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 54)))
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 14), 0, 0, 0)
                addView(Ui.text(ctx, Ui.Type.BODY, "", lines = 2).apply { id = R.id.row_title })
                addView(Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1).apply { id = R.id.row_subtitle })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // ⋮ like the other lists (2.6), not only a long press.
            row.addView(android.widget.ImageButton(ctx).apply {
                id = R.id.row_action
                // Not a stop for the list's focus (it showed a grey box on the first row).
                isFocusable = false
                setImageResource(R.drawable.ic_more)
                contentDescription = "More"
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.textSecondary(ctx))
                setBackgroundResource(RowHolder.selectableBackground(ctx))
            }, LinearLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)).apply { marginEnd = -Ui.dp(ctx, 8) })
            return Holder(row)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> {
                    h.itemView.findViewById<TextView>(R.id.row_title).text = r.text
                    val action = h.itemView.findViewById<TextView>(R.id.row_subtitle)
                    action.text = r.action.orEmpty()
                    action.visibility = if (r.action != null) View.VISIBLE else View.GONE
                    action.setOnClickListener { r.onAction?.invoke() }
                }
                is Row.Item -> bind(h.itemView, r.e)
            }
        }
    }

    private fun bind(v: View, e: Entry) {
        val ctx = this
        v.findViewById<TextView>(R.id.row_title).text = e.name
        val doc = e.doc
        val progress = when {
            doc == null || doc.place == null || doc.progress <= 0.02f -> null
            doc.kind == Docs.Kind.PDF && doc.total > 0 -> "Page ${doc.place} of ${doc.total}"
            doc.progress >= 0.97f -> "Read"
            else -> "${(doc.progress * 100).toInt()}% read"
        }
        val time = if (e.time > 0) DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() else null
        val star = if (e.uri in starredUris) "★" else null
        v.findViewById<TextView>(R.id.row_subtitle).text =
            listOfNotNull(star, progress, e.where.ifBlank { null }, e.size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) }, time).joinToString(" · ")
        val thumb = v.findViewById<ImageView>(R.id.row_icon)
        thumb.tag = e.uri
        thumb.scaleType = ImageView.ScaleType.CENTER
        thumb.setImageDrawable(DocIcon(e.kind, Ui.dp(ctx, 30)))
        // Page previews: a PDF's first page, an Office file's or book's own picture.
        DocThumbs.load(ctx, e.uri, e.kind, Ui.dp(ctx, 44)) { bmp ->
            if (thumb.tag != e.uri) return@load
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.setImageBitmap(bmp)
        }
        v.contentDescription = "${e.name}, ${e.kind.label}" + if (star != null) ", starred" else ""
        v.setOnClickListener {
            if (doc != null && doc.uri == e.uri && Docs.reopen(doc.id, backTo = Docs.BACK_DOCUMENTS)) finish() else open(Uri.parse(e.uri), e.name)
        }
        v.setOnLongClickListener {
            hold(e)
            true
        }
        v.findViewById<View>(R.id.row_action)?.setOnClickListener { hold(e) }
    }

    private companion object {
        const val MENU_BROWSE = 1
        const val MENU_SORT = 2
        const val MENU_FOLDERS = 3
        const val MENU_FILES = 4
        const val PREF_SORT = "documents_sort"
    }
}
