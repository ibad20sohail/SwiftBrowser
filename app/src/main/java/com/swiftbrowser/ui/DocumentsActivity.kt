package com.swiftbrowser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
 */
class DocumentsActivity : ListActivity() {
    /** One document in the list: [uri] is where it is, [doc] its record when Swift opened it before. */
    private class Entry(val uri: String, val name: String, val kind: Docs.Kind, val size: Long, val time: Long, val where: String, val doc: Docs.Doc?)

    private sealed class Row {
        class Header(val text: String) : Row()
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
    )
    private var filter = 0
    private var recent: List<Entry> = emptyList()
    private var phone: List<Entry> = emptyList()
    private var loaded = false
    private val adapter = Adapter()

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) load() else render()
    }

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        open(uri, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Documents"
        toolbar.menu.add(0, MENU_BROWSE, 0, "Browse files").apply {
            setIcon(Ui.icon(this@DocumentsActivity, R.drawable.ic_folder, Palette.color(this@DocumentsActivity, R.color.text_primary)))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == MENU_BROWSE) browse()
            true
        }
        search.visibility = View.VISIBLE
        search.hint = "Search documents"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        addFilterChips()
        list.adapter = adapter
        list.itemAnimator = null
        if (hasAccess() || Build.VERSION.SDK_INT > 32) load() else askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAccess() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

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
            val (r, p) = withContext(Dispatchers.IO) { loadRecent() to if (hasAccess()) loadPhone() else emptyList() }
            recent = r
            phone = p
            loaded = true
            render()
            Motion.fadeIn(list, fromDy = 8)
        }
    }

    private fun loadRecent(): List<Entry> = SwiftApp.db.recentDocuments(Profiles.currentId, 6).map { d ->
        Entry(d.uri, d.name, d.kind, d.size, d.opened, describeWhere(d.uri, d.web), d)
    }

    private fun describeWhere(uri: String, web: String?): String {
        val u = Uri.parse(uri)
        return when {
            u.scheme == "file" -> folderName(u.path.orEmpty())
            web != null -> UrlUtils.host(web)?.removePrefix("www.") ?: "Web"
            else -> "From another app"
        }
    }

    private fun folderName(path: String): String {
        val parent = File(path).parentFile ?: return ""
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
                    out += Entry(uri, name, kind, c.getLong(1), c.getLong(2) * 1000, folderName(path), known[uri])
                }
            }
        } catch (e: Exception) {
            com.swiftbrowser.core.L.w("Couldn't list the phone's documents", e)
        }
        return out
    }

    private fun render() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val kinds = filters[filter].second
        val keep = { e: Entry -> (kinds == null || e.kind in kinds) && (q.isEmpty() || e.name.lowercase().contains(q)) }
        val rows = ArrayList<Row>()
        val r = recent.filter(keep)
        if (r.isNotEmpty()) {
            rows += Row.Header("Opened in Swift")
            r.forEach { rows += Row.Item(it) }
        }
        val recentUris = r.map { it.uri }.toSet()
        val p = phone.filter { keep(it) && it.uri !in recentUris }
        if (p.isNotEmpty()) {
            rows += Row.Header("On this phone")
            p.forEach { rows += Row.Item(it) }
        }
        adapter.rows = rows
        if (rows.isEmpty()) showNothing(q.isNotEmpty() || kinds != null) else empty.visibility = View.GONE
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
                filtered -> "Try another word or filter."
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

    private fun open(uri: Uri, name: String?) {
        if (Docs.open(this, uri, name)) {
            finish()
        } else {
            Messages.show(this, "Swift can't show this kind of file")
        }
    }

    private fun hold(e: Entry) {
        val doc = e.doc
        val actions = arrayListOf(
            Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { open(Uri.parse(e.uri), e.name) },
            Sheets.Action(R.drawable.ic_share, "Share", primary = true) { Docs.handOver(this, asDoc(e), share = true) },
            Sheets.Action(R.drawable.ic_reopen, "Open with another app", primary = true) { Docs.handOver(this, asDoc(e), share = false) },
        )
        if (doc != null) {
            actions += Sheets.Action(R.drawable.ic_close, "Remove from Opened in Swift") {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SwiftApp.db.forgetDocument(doc.id) }
                    recent = recent.filter { it.doc?.id != doc.id }
                    render()
                }
            }
        }
        val details = listOfNotNull(e.kind.label, e.size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) }, e.where.ifBlank { null }).joinToString(" · ")
        Sheets.actions(this, e.name, details, actions, null)
    }

    private fun asDoc(e: Entry) = e.doc ?: Docs.Doc("", e.uri, e.name, e.kind, e.size, null, null, 0f, 0, 0, 0)

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
                return Holder(Ui.text(ctx, Ui.Type.SECTION, "").apply {
                    setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 20), Ui.dp(ctx, 6))
                })
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
            return Holder(row)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (h.itemView as TextView).text = r.text
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
        v.findViewById<TextView>(R.id.row_subtitle).text =
            listOfNotNull(progress, e.where.ifBlank { null }, e.size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) }, time).joinToString(" · ")
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
        v.contentDescription = "${e.name}, ${e.kind.label}"
        v.setOnClickListener {
            if (doc != null && doc.uri == e.uri && Docs.reopen(doc.id)) finish() else open(Uri.parse(e.uri), e.name)
        }
        v.setOnLongClickListener {
            hold(e)
            true
        }
    }

    private companion object {
        const val MENU_BROWSE = 1
    }
}
