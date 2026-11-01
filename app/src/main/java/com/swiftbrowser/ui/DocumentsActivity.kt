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
import androidx.recyclerview.widget.GridLayoutManager
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
 *
 * 2.8: Recently opened as a row of page previews; a list or a grid; hold to select several (share,
 * star, delete); Details; Back clears the search, then the folder or filter, then closes.
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

        /** Recently opened (2.8): a row of page previews that scrolls sideways. */
        class Recent(val items: List<Entry>) : Row()
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

    /** The documents ticked (2.8), by their address. */
    private val selected = LinkedHashSet<String>()

    /** A grid of page previews rather than a list (2.8). */
    private var grid = false
    private var filterChips: com.google.android.material.chip.ChipGroup? = null

    /** Back takes one step at a time (2.8): the selection, the search, the folder, the filter. */
    private val backStep = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                selected.isNotEmpty() -> clearSelection()
                search.text.isNotEmpty() -> search.setText("")
                folder != null -> {
                    folder = null
                    render()
                }
                filter != 0 -> setFilter(0)
            }
        }
    }

    private fun updateBack() {
        backStep.isEnabled = selected.isNotEmpty() || search.text.isNotEmpty() || folder != null || filter != 0
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
        grid = prefs.sp.getBoolean(PREF_GRID, false)
        setupMenu()
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_BROWSE -> browse()
                MENU_SORT -> chooseSort()
                MENU_FOLDERS -> chooseFolder()
                // 2.7: all the phone's files, in Swift's file manager.
                MENU_FILES -> FilesActivity.open(this)
                MENU_VIEW -> setGrid(!grid)
                MENU_SELECT -> Messages.show(this, "Hold a document to select it, then tap others")
                MENU_SHARE -> shareSelected()
                MENU_STAR -> starSelected()
                MENU_DELETE -> deleteSelected()
                MENU_SELECT_ALL -> selectAll()
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, backStep)
        search.visibility = View.VISIBLE
        search.hint = "Search documents"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateBack()
                // After a pause in typing (2.6): each render sorts every document.
                list.removeCallbacks(renderSoon)
                list.postDelayed(renderSoon, 150)
            }
        })
        addFilterChips()
        list.layoutManager = GridLayoutManager(this, spanCount()).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (grid && adapter.rows.getOrNull(position) is Row.Item) 1 else spanCount
            }
        }
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
            // 2.8: smaller than a sheet's chips, so the list has more room.
            for (i in 0 until childCount) {
                (getChildAt(i) as? com.google.android.material.chip.Chip)?.apply {
                    chipMinHeight = Ui.dpf(this@DocumentsActivity, 32f)
                    textSize = 13.5f
                }
            }
        }
        filterChips = chips
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        column.addView(scroller, column.indexOfChild(box), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Back to the "All" filter (or another), with its chip ticked. */
    private fun setFilter(i: Int) {
        filter = i
        filterChips?.let { g -> g.getChildAt(i)?.let { g.check(it.id) } }
        render()
    }

    // ------------------------------------------------------------------ the bar (2.8)

    private fun setupMenu() {
        val m = toolbar.menu
        m.clear()
        val iconColor = Palette.color(this, R.color.text_primary)
        fun icon(id: Int, order: Int, title: String, res: Int) = m.add(0, id, order, title).apply {
            setIcon(Ui.icon(this@DocumentsActivity, res, iconColor))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (selected.isNotEmpty()) {
            toolbar.title = "${selected.size} selected"
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = "Clear selection"
            toolbar.setNavigationOnClickListener { clearSelection() }
            icon(MENU_SHARE, 0, "Share", R.drawable.ic_share)
            icon(MENU_STAR, 1, "Star", R.drawable.ic_star_border)
            icon(MENU_DELETE, 2, "Delete", R.drawable.ic_delete)
            m.add(0, MENU_SELECT_ALL, 3, "Select all").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        } else {
            toolbar.title = "Documents"
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.navigationContentDescription = "Back"
            toolbar.setNavigationOnClickListener { finish() }
            icon(MENU_VIEW, 0, if (grid) "List" else "Grid", if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
            icon(MENU_SORT, 1, "Sort", R.drawable.ic_sort)
            m.add(0, MENU_BROWSE, 2, "Browse files").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            m.add(0, MENU_FOLDERS, 3, "Folders").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            m.add(0, MENU_FILES, 4, "Files").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            m.add(0, MENU_SELECT, 5, "Select").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    /** Columns of the grid: two on a phone held upright, more when there is room. */
    private fun spanCount(): Int = if (!grid) 1 else (resources.configuration.screenWidthDp / 170).coerceAtLeast(2)

    private fun setGrid(on: Boolean) {
        grid = on
        prefs.sp.edit().putBoolean(PREF_GRID, on).apply()
        (list.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
        setupMenu()
        adapter.notifyDataSetChanged()
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

    private fun loadRecent(): List<Entry> = SwiftApp.db.recentDocuments(Profiles.currentId, 12).map { d ->
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
        updateBack()
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
        // Just looking (no search or folder): what was read lately, as page previews in a row.
        val browsing = dir == null && q.isEmpty()
        val r = recent.filter(keep)
        if (browsing && r.isNotEmpty()) {
            rows += Row.Header("Recently opened")
            rows += Row.Recent(r)
        }
        val s = sorted(starred.filter(keep))
        if (s.isNotEmpty()) {
            rows += Row.Header("Starred")
            s.forEach { rows += Row.Item(it); shown += it.uri }
        }
        if (!browsing) {
            val more = r.filter { it.uri !in shown }
            if (more.isNotEmpty()) {
                rows += Row.Header("Opened in Swift")
                more.forEach { rows += Row.Item(it); shown += it.uri }
            }
        }
        val p = sorted(phone.filter { keep(it) && it.uri !in shown })
        if (p.isNotEmpty()) {
            rows += Row.Header(if (dir == null) "On this phone" else "Files")
            p.forEach { rows += Row.Item(it) }
        }
        adapter.rows = rows
        if (rows.none { it is Row.Item || it is Row.Recent }) showNothing(q.isNotEmpty() || kinds != null || dir != null) else empty.visibility = View.GONE
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
        // 2.8: over this screen (Back returns here), no longer in a browser tab.
        if (!Docs.open(this, uri, name)) Messages.show(this, "Swift can't show this kind of file")
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
            Sheets.Action(R.drawable.ic_info, "Details") { details(e) },
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

    /** What there is to know about a document (2.8): type, size, pages, folder, dates, how far it was read. */
    private fun details(e: Entry) {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, e.name, e.kind.label))
        val pad = Ui.dp(this, 24)
        fun line(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            col.addView(Ui.text(this, Ui.Type.CAPTION, label).apply { setPadding(pad, Ui.dp(this@DocumentsActivity, 10), pad, 0) })
            col.addView(Ui.text(this, Ui.Type.BODY, value).apply {
                setPadding(pad, Ui.dp(this@DocumentsActivity, 2), pad, 0)
                setTextIsSelectable(true)
            })
        }
        val when_ = { ms: Long -> DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME) }
        val doc = e.doc
        val ext = e.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != e.name }?.uppercase()
        line("Type", listOfNotNull(e.kind.label, ext?.let { ".$it file" }).joinToString(", "))
        line("Size", e.size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) })
        line("Pages", doc?.total?.takeIf { it > 0 && e.kind == Docs.Kind.PDF }?.toString())
        line("Where", e.path?.let { File(it).parent } ?: e.where)
        line("Changed", e.path?.let { File(it).lastModified() }?.takeIf { it > 0 }?.let(when_))
        line("Last opened in Swift", doc?.opened?.takeIf { it > 0 }?.let(when_))
        line("Read", doc?.takeIf { it.progress > 0.02f }?.let { if (it.progress >= 0.97f) "All of it" else "${(it.progress * 100).toInt()}%" })
        col.addView(View(this), LinearLayout.LayoutParams(1, Ui.dp(this, 20)))
        Sheets.sheet(this, col).showImmersive()
    }

    // ------------------------------------------------------------------ selecting several (2.8)

    private fun toggleSelect(e: Entry) {
        if (!selected.remove(e.uri)) selected += e.uri
        selectionChanged()
    }

    private fun clearSelection() {
        selected.clear()
        selectionChanged()
    }

    private fun selectAll() {
        adapter.rows.forEach { if (it is Row.Item) selected += it.e.uri }
        selectionChanged()
    }

    private fun selectionChanged() {
        setupMenu()
        updateBack()
        adapter.notifyDataSetChanged()
    }

    /** The ticked documents, each once. */
    private fun selectedEntries(): List<Entry> = (starred + recent + phone).filter { it.uri in selected }.distinctBy { it.uri }

    private fun shareSelected() {
        val uris = ArrayList<Uri>()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                for (e in selectedEntries()) {
                    val u = Uri.parse(e.uri)
                    (if (u.scheme == "file") Docs.shareUri(this@DocumentsActivity, File(u.path.orEmpty())) else u)?.let { uris += it }
                }
            }
            if (uris.isEmpty()) {
                Messages.show(this@DocumentsActivity, "Swift couldn't share these files")
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(intent, "Share ${uris.size} documents"))
            } catch (_: Exception) {
                Messages.show(this@DocumentsActivity, "No app on this phone can share these files")
            }
        }
    }

    /** Stars them all, or takes the star off when they all have one. */
    private fun starSelected() {
        val list = selectedEntries()
        val on = list.any { it.uri !in starredUris }
        val profile = Profiles.currentId
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                for (e in list) {
                    if (on && e.uri !in starredUris) SwiftApp.db.starDocument(profile, e.uri, e.name, e.kind.key, e.size)
                    if (!on) SwiftApp.db.unstarDocument(profile, e.uri)
                }
                starred = loadStarred()
            }
            starredUris = starred.map { it.uri }.toSet()
            clearSelection()
            render()
            Messages.show(this@DocumentsActivity, if (on) "Starred ${list.size}" else "Stars removed")
        }
    }

    /**
     * Files on the phone are deleted (after asking); documents that aren't files of the phone (from
     * the web, another app) only leave the list.
     */
    private fun deleteSelected() {
        val list = selectedEntries()
        val files = list.filter { it.path != null && !it.path.startsWith(cacheDir.path) && !it.path.startsWith(filesDir.path) }
        val others = list - files.toSet()
        val run = {
            withWriteAccess {
                lifecycleScope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        var failed = 0
                        for (e in files) {
                            val f = File(e.path!!)
                            if (f.delete() || !f.exists()) {
                                try {
                                    contentResolver.delete(MediaStore.Files.getContentUri("external"), MediaStore.Files.FileColumns.DATA + "=?", arrayOf(f.path))
                                } catch (_: Exception) {
                                }
                                SwiftApp.db.forgetDocumentUri(e.uri)
                            } else {
                                failed++
                            }
                        }
                        others.forEach { e -> e.doc?.let { SwiftApp.db.forgetDocument(it.id) } }
                        failed
                    }
                    clearSelection()
                    reload()
                    Messages.show(
                        this@DocumentsActivity,
                        if (failed > 0) "Swift couldn't delete $failed of them" else if (files.isEmpty()) "Removed from the list" else "Deleted ${files.size}",
                    )
                }
            }
        }
        if (files.isEmpty()) {
            run()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (files.size == 1) "Delete ${files[0].name}?" else "Delete ${files.size} files?")
            .setMessage("They are deleted from the phone. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> run() }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
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

    private inner class Holder(
        v: View,
        val thumb: ImageView? = null,
        val title: TextView? = null,
        val sub: TextView? = null,
        val check: ImageView? = null,
        val more: View? = null,
        val strip: LinearLayout? = null,
    ) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Recent -> 3
            is Row.Item -> if (grid) 2 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@DocumentsActivity
            when (viewType) {
                0 -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val title = Ui.text(ctx, Ui.Type.SECTION, "")
                    row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val action = Ui.text(ctx, Ui.Type.BODY, "").apply {
                        setTextColor(Palette.color(ctx, R.color.accent))
                        setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
                        setBackgroundResource(RowHolder.selectableBackground(ctx))
                    }
                    row.addView(action)
                    return Holder(row, title = title, sub = action)
                }
                3 -> {
                    val strip = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4))
                    }
                    val scroller = HorizontalScrollView(ctx).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        addView(strip)
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    return Holder(scroller, strip = strip)
                }
                2 -> {
                    val tile = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 6))
                        setBackgroundResource(RowHolder.selectableBackground(ctx))
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val frame = FrameLayout(ctx)
                    val thumb = previewView(ctx)
                    frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 150)))
                    val check = ImageView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    frame.addView(check, FrameLayout.LayoutParams(Ui.dp(ctx, 26), Ui.dp(ctx, 26), Gravity.TOP or Gravity.END).apply {
                        setMargins(0, Ui.dp(ctx, 6), Ui.dp(ctx, 6), 0)
                    })
                    tile.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(ctx, 4) })
                    val title = Ui.text(ctx, Ui.Type.LABEL, "", lines = 2)
                    val sub = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
                    val more = moreButton(ctx)
                    tile.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, Ui.dp(ctx, 6), 0, 0)
                        addView(LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(title)
                            addView(sub)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(more, LinearLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 36)))
                    })
                    return Holder(tile, thumb, title, sub, check, more)
                }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 10))
                minimumHeight = Ui.dp(ctx, 72)
                setBackgroundResource(RowHolder.selectableBackground(ctx))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val thumb = previewView(ctx)
            row.addView(thumb, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 54)))
            val title = Ui.text(ctx, Ui.Type.BODY, "", lines = 2)
            val sub = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 14), 0, 0, 0)
                addView(title)
                addView(sub)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // ⋮ like the other lists (2.6); while selecting, a tick in its place (2.8).
            val more = moreButton(ctx)
            row.addView(more, LinearLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)).apply { marginEnd = -Ui.dp(ctx, 8) })
            val check = ImageView(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER
            }
            row.addView(check, LinearLayout.LayoutParams(Ui.dp(ctx, 40), Ui.dp(ctx, 48)))
            return Holder(row, thumb, title, sub, check, more)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> {
                    h.title?.text = r.text
                    h.sub?.text = r.action.orEmpty()
                    h.sub?.visibility = if (r.action != null) View.VISIBLE else View.GONE
                    h.sub?.setOnClickListener { r.onAction?.invoke() }
                }
                is Row.Recent -> bindRecent(h.strip ?: return, r.items)
                is Row.Item -> bind(h, r.e, tile = grid)
            }
        }
    }

    private fun previewView(ctx: android.content.Context) = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.shape(ctx, Palette.color(ctx, R.color.surface_alt), 8, Palette.color(ctx, R.color.outline))
        setPadding(Ui.dp(ctx, 1), Ui.dp(ctx, 1), Ui.dp(ctx, 1), Ui.dp(ctx, 1))
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun moreButton(ctx: android.content.Context) = android.widget.ImageButton(ctx).apply {
        // Not a stop for the list's focus (it showed a grey box on the first row).
        isFocusable = false
        setImageResource(R.drawable.ic_more)
        contentDescription = "More"
        imageTintList = android.content.res.ColorStateList.valueOf(Ui.textSecondary(ctx))
        setBackgroundResource(RowHolder.selectableBackground(ctx))
    }

    /** A page preview in [thumb] (a PDF's first page, an Office file's or book's own picture), the kind's mark meanwhile. */
    private fun showPreview(thumb: ImageView, e: Entry, widthDp: Int, markDp: Int) {
        thumb.tag = e.uri
        thumb.scaleType = ImageView.ScaleType.CENTER
        thumb.setImageDrawable(DocIcon(e.kind, Ui.dp(this, markDp)))
        DocThumbs.load(this, e.uri, e.kind, Ui.dp(this, widthDp)) { bmp ->
            if (thumb.tag != e.uri) return@load
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.setImageBitmap(bmp)
        }
    }

    private fun progressText(doc: Docs.Doc?): String? = when {
        doc == null || doc.place == null || doc.progress <= 0.02f -> null
        doc.kind == Docs.Kind.PDF && doc.total > 0 -> "Page ${doc.place} of ${doc.total}"
        doc.progress >= 0.97f -> "Read"
        else -> "${(doc.progress * 100).toInt()}% read"
    }

    private fun openEntry(e: Entry) {
        val doc = e.doc
        if (doc != null && doc.uri == e.uri) Docs.reopen(this, doc.id) else open(Uri.parse(e.uri), e.name)
    }

    private fun bind(h: Holder, e: Entry, tile: Boolean) {
        val v = h.itemView
        h.title?.text = e.name
        val progress = progressText(e.doc)
        val time = if (e.time > 0) DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() else null
        val star = if (e.uri in starredUris) "★" else null
        val size = e.size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) }
        h.sub?.text = if (tile) {
            listOfNotNull(star, progress ?: size, time).joinToString(" · ")
        } else {
            listOfNotNull(star, progress, e.where.ifBlank { null }, size, time).joinToString(" · ")
        }
        h.thumb?.let { if (tile) showPreview(it, e, 170, 48) else showPreview(it, e, 44, 30) }
        // Selecting (2.8): a tick (or an empty ring) instead of ⋮; a tap ticks rather than opens.
        val selecting = selected.isNotEmpty()
        val on = e.uri in selected
        h.check?.visibility = if (selecting) View.VISIBLE else View.GONE
        h.check?.setImageDrawable(if (on) Ui.icon(this, R.drawable.ic_check_circle, Ui.accent(this)) else ring())
        h.check?.background = if (tile && selecting) Ui.circle(Palette.color(this, R.color.page_bg)) else null
        h.more?.visibility = if (!selecting) View.VISIBLE else if (tile) View.INVISIBLE else View.GONE
        h.more?.isEnabled = !selecting
        v.isActivated = on
        if (on) v.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Ui.accent(this), 40)) else v.setBackgroundResource(RowHolder.selectableBackground(this))
        v.contentDescription = "${e.name}, ${e.kind.label}" + (if (star != null) ", starred" else "") + if (on) ", selected" else ""
        v.setOnClickListener { if (selected.isNotEmpty()) toggleSelect(e) else openEntry(e) }
        v.setOnLongClickListener {
            com.swiftbrowser.ui.Haptics.hold(v)
            toggleSelect(e)
            true
        }
        h.more?.setOnClickListener { hold(e) }
    }

    /** An empty tick ring, for a document not ticked while selecting. */
    private fun ring() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setStroke(Ui.dp(this@DocumentsActivity, 2), Ui.textSecondary(this@DocumentsActivity))
        setSize(Ui.dp(this@DocumentsActivity, 22), Ui.dp(this@DocumentsActivity, 22))
    }

    /** Recently opened: cards with the page preview, the name and how far it was read. */
    private fun bindRecent(strip: LinearLayout, items: List<Entry>) {
        strip.removeAllViews()
        val ctx = this
        for (e in items) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
                setBackgroundResource(RowHolder.selectableBackground(ctx))
                contentDescription = "${e.name}, ${e.kind.label}"
                setOnClickListener { openEntry(e) }
                setOnLongClickListener {
                    hold(e)
                    true
                }
            }
            val thumb = previewView(ctx)
            card.addView(thumb, LinearLayout.LayoutParams(Ui.dp(ctx, 104), Ui.dp(ctx, 132)))
            showPreview(thumb, e, 104, 40)
            card.addView(Ui.text(ctx, Ui.Type.LABEL, e.name, lines = 2).apply { setPadding(0, Ui.dp(ctx, 6), 0, 0) }, LinearLayout.LayoutParams(Ui.dp(ctx, 104), ViewGroup.LayoutParams.WRAP_CONTENT))
            (progressText(e.doc) ?: e.where.ifBlank { null })?.let {
                card.addView(Ui.text(ctx, Ui.Type.CAPTION, it, lines = 1), LinearLayout.LayoutParams(Ui.dp(ctx, 104), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            strip.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(ctx, 4) })
        }
    }

    private companion object {
        const val MENU_BROWSE = 1
        const val MENU_SORT = 2
        const val MENU_FOLDERS = 3
        const val MENU_FILES = 4
        const val MENU_VIEW = 5
        const val MENU_SELECT = 6
        const val MENU_SHARE = 7
        const val MENU_STAR = 8
        const val MENU_DELETE = 9
        const val MENU_SELECT_ALL = 10
        const val PREF_SORT = "documents_sort"
        const val PREF_GRID = "documents_grid"
    }
}
