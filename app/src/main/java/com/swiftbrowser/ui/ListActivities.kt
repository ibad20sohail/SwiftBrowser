package com.swiftbrowser.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.BookmarkRow
import com.swiftbrowser.core.HistoryRow
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared shell for the list screens (toolbar, optional search box, list, empty screen). Rows can be
 * swiped away sideways (2.0); a removal waits until its Undo is gone (or the screen is left) before
 * it really happens.
 */
abstract class ListActivity : AppCompatActivity() {
    protected lateinit var toolbar: MaterialToolbar
    protected lateinit var list: RecyclerView
    protected lateinit var empty: FrameLayout
    protected lateinit var search: EditText

    private val handler = Handler(Looper.getMainLooper())

    /** Removals waiting for their Undo to run out, by key. */
    private val pending = LinkedHashMap<String, Runnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        Palette.paint(findViewById<View>(android.R.id.content))
        toolbar = findViewById(R.id.list_toolbar)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.list_empty)
        search = findViewById(R.id.list_search)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.navigationContentDescription = "Back"
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setTitleTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        toolbar.setTitleTextColor(Palette.color(this, R.color.text_primary))
        toolbar.setSubtitleTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        toolbar.setSubtitleTextColor(Palette.color(this, R.color.text_secondary))
        window.statusBarColor = Palette.color(this, R.color.page_bg)
        list.layoutManager = LinearLayoutManager(this)
        // Back clears a search first (2.6), then leaves the screen.
        val clearSearch = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                search.setText("")
            }
        }
        onBackPressedDispatcher.addCallback(this, clearSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearSearch.isEnabled = !s.isNullOrEmpty()
            }
        })
        // The list holds the focus, so the search box doesn't show a cursor until it is tapped.
        list.isFocusableInTouchMode = true
        list.requestFocus()
        SystemBars.keepHidden(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onResume() {
        super.onResume()
        NightLight.attach(this)
    }

    override fun onPause() {
        // Leaving the screen: what was removed is removed now (Undo goes with the screen).
        flushRemovals()
        super.onPause()
    }

    /**
     * Removes something with an Undo: [hide] takes it off the screen now, [commit] really removes
     * it once the Undo is gone, [restore] puts it back when Undo is tapped.
     */
    protected fun removeLater(key: String, message: String, hide: () -> Unit, restore: () -> Unit, commit: () -> Unit) {
        pending.remove(key)?.let {
            handler.removeCallbacks(it)
            it.run()
        }
        hide()
        val r = Runnable {
            pending.remove(key)
            commit()
        }
        pending[key] = r
        handler.postDelayed(r, UNDO_MS)
        Haptics.tick(list)
        Messages.show(this, message, "Undo") {
            handler.removeCallbacks(r)
            if (pending.remove(key) != null) restore()
        }
    }

    private fun flushRemovals() {
        for ((_, r) in pending.toList()) {
            handler.removeCallbacks(r)
            r.run()
        }
        pending.clear()
    }

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Rows for which [canSwipe] says yes can be swiped away sideways; [onSwiped] then removes them. */
    protected fun enableSwipe(canSwipe: (RecyclerView.ViewHolder) -> Boolean, onSwiped: (RecyclerView.ViewHolder) -> Unit) {
        val icon = Ui.icon(this, R.drawable.ic_delete, Ui.danger(this))
        val iconSize = Ui.dp(this, 24)
        val margin = Ui.dp(this, 24)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (canSwipe(vh)) super.getSwipeDirs(rv, vh) else 0

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = onSwiped(vh)

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    // What is revealed behind the row: a soft red band with a bin on the near side.
                    val v = vh.itemView
                    swipePaint.color = Palette.color(this@ListActivity, R.color.danger_container)
                    val left = if (dX > 0) v.left.toFloat() else v.right + dX
                    val right = if (dX > 0) v.left + dX else v.right.toFloat()
                    c.drawRect(left, v.top.toFloat(), right, v.bottom.toFloat(), swipePaint)
                    if (icon != null && kotlin.math.abs(dX) > margin) {
                        val top = v.top + (v.height - iconSize) / 2
                        val x = if (dX > 0) v.left + margin else v.right - margin - iconSize
                        icon.setBounds(x, top, x + iconSize, top + iconSize)
                        icon.alpha = (kotlin.math.abs(dX) / (v.width * 0.3f) * 255).toInt().coerceIn(0, 255)
                        icon.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, state, active)
            }
        }).attachToRecyclerView(list)
    }

    /** An empty list: an icon, what is missing, and how to fill it. */
    protected fun showEmpty(isEmpty: Boolean, title: String, hint: String? = null, icon: Int = R.drawable.ic_info) {
        if (!isEmpty) {
            empty.visibility = View.GONE
            return
        }
        empty.removeAllViews()
        empty.addView(Ui.emptyState(this, icon, title, hint), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        if (empty.visibility != View.VISIBLE) Motion.fadeIn(empty, fromDy = 12)
    }

    protected fun openUrl(url: String, newTab: Boolean = false) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_NEW_TAB, newTab))
        finish()
    }

    protected fun share(url: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share"))
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** A little longer than the Undo shows (Messages), so it can still be tapped at the end. */
        const val UNDO_MS = 5600L
    }
}

class HistoryActivity : ListActivity() {
    /** A day's heading, before that day's pages. */
    private class Day(val label: String, val from: Long, val to: Long)

    private val adapter = DayAdapter()
    private var searchJob: Job? = null
    private var query = ""
    private var rows: List<HistoryRow> = emptyList()

    // Removed, but still undoable.
    private val hiddenIds = HashSet<Long>()
    private val hiddenDays = ArrayList<Pair<Long, Long>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "History"
        toolbar.subtitle = Profiles.current().name
        // The last hour, day, week or all of it (2.6), and what else to clear with the history.
        toolbar.menu.add(0, 1, 0, "Clear").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            ClearData.show(this, preset = setOf(com.swiftbrowser.core.Clearing.What.HISTORY)) { load() }
            true
        }
        search.visibility = View.VISIBLE
        search.hint = "Search your history"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(200)
                    load()
                }
            }
        })
        list.adapter = adapter
        enableSwipe({ it is RowHolder }) { vh ->
            (adapter.items.getOrNull(vh.bindingAdapterPosition) as? HistoryRow)?.let { remove(it) } ?: adapter.notifyItemChanged(vh.bindingAdapterPosition)
        }
        load()
    }

    private fun load() {
        val q = search.text.toString()
        query = q.trim()
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { SwiftApp.db.history(Profiles.currentId, q) }
            render()
        }
    }

    private fun render() {
        val shown = rows.filter { r -> r.id !in hiddenIds && hiddenDays.none { r.lastVisit >= it.first && r.lastVisit < it.second } }
        adapter.items = byDay(shown)
        if (query.isBlank()) {
            showEmpty(shown.isEmpty(), "No history yet", "Pages you open show up here, newest first.", R.drawable.ic_history)
        } else {
            showEmpty(shown.isEmpty(), "Nothing found", "No page in your history has \"$query\" in its title or address.", R.drawable.ic_search)
        }
    }

    private fun remove(r: HistoryRow) {
        removeLater(
            "h${r.id}", "Removed from history",
            hide = {
                hiddenIds += r.id
                render()
            },
            restore = {
                hiddenIds -= r.id
                render()
            },
            commit = {
                lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.deleteHistory(r.id) }
            },
        )
    }

    /** Today, Yesterday, then dates, each followed by its pages, newest first. */
    private fun byDay(rows: List<HistoryRow>): List<Any> {
        val out = ArrayList<Any>()
        var day: Day? = null
        for (r in rows) {
            if (day == null || r.lastVisit < day.from) {
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = r.lastVisit
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val from = cal.timeInMillis
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                day = Day(dayLabel(from), from, cal.timeInMillis)
                out += day
            }
            out += r
        }
        return out
    }

    private fun dayLabel(start: Long): String {
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayMs = 24L * 3600 * 1000
        return when {
            start >= today -> "Today"
            start >= today - dayMs -> "Yesterday"
            start >= today - 6 * dayMs -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(start))
            else -> java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(start))
        }
    }

    /** The search word in bold and the accent colour. */
    private fun marked(text: String): CharSequence {
        if (query.isEmpty()) return text
        val i = text.indexOf(query, ignoreCase = true)
        if (i < 0) return text
        return android.text.SpannableString(text).apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + query.length, 0)
            setSpan(android.text.style.ForegroundColorSpan(Ui.accent(this@HistoryActivity)), i, i + query.length, 0)
        }
    }

    /** A whole day goes at once, with Undo (no question first). */
    private fun deleteDay(day: Day) {
        val range = day.from to day.to
        val message = if (day.label == "Today" || day.label == "Yesterday") "Deleted ${day.label.lowercase()}'s history" else "Deleted the history of ${day.label}"
        removeLater(
            "d${day.from}", message,
            hide = {
                hiddenDays += range
                render()
            },
            restore = {
                hiddenDays -= range
                render()
            },
            commit = {
                lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.deleteHistoryBetween(Profiles.currentId, day.from, day.to) }
            },
        )
    }

    private inner class DayAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Any> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Day) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) return RowHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)))
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(Ui.text(ctx, Ui.Type.SECTION, "").apply { id = R.id.row_title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.text(ctx, Ui.Type.LABEL, "Delete day", Ui.textSecondary(ctx)).apply {
                    id = R.id.row_action
                    setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
                    background = Ui.ripple(ctx, null, 16)
                })
            }
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Day -> {
                    holder.itemView.findViewById<TextView>(R.id.row_title).text = item.label
                    // Searching shows matches from many days: deleting a whole day isn't offered then.
                    holder.itemView.findViewById<View>(R.id.row_action).apply {
                        visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
                        contentDescription = "Delete the history of ${item.label}"
                        setOnClickListener { deleteDay(item) }
                    }
                }
                is HistoryRow -> {
                    val h = holder as RowHolder
                    val time = DateUtils.formatDateTime(this@HistoryActivity, item.lastVisit, DateUtils.FORMAT_SHOW_TIME)
                    h.set(R.drawable.ic_globe, marked(UrlUtils.readableTitle(item.title, item.url)), marked("${UrlUtils.siteName(item.url)} · $time"))
                    h.setFavicon(item.url)
                    h.setAction(R.drawable.ic_close, "Remove from history") { remove(item) }
                    h.itemView.setOnClickListener { openUrl(item.url) }
                    h.itemView.setOnLongClickListener {
                        rowMenu(item)
                        true
                    }
                }
            }
        }
    }

    private fun rowMenu(r: HistoryRow) {
        Sheets.actions(
            this, UrlUtils.readableTitle(r.title, r.url), UrlUtils.siteName(r.url),
            listOf(
                Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { openUrl(r.url, newTab = true) },
                Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(r.url) },
                Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copyText(this, r.url) },
                Sheets.Action(R.drawable.ic_delete, "Remove", primary = true, danger = true) { remove(r) },
            ),
            com.swiftbrowser.core.Favicons.cached(r.url), fullAddress = r.url,
        )
    }
}

class BookmarksActivity : ListActivity() {
    /** A row: a folder, or a bookmark (2.6). */
    private sealed class Item {
        class Folder(val name: String, val count: Int) : Item()
        class Mark(val row: BookmarkRow) : Item()
    }

    private var rows: List<BookmarkRow> = emptyList()
    private val hiddenIds = HashSet<Long>()

    /** The folder open ("" is the top of the list). */
    private var folder = ""
    private var query = ""

    private val adapter = RowAdapter<Item>(
        bind = { h, item ->
            when (item) {
                is Item.Folder -> {
                    h.set(R.drawable.ic_folder, item.name, if (item.count == 1) "1 bookmark" else "${item.count} bookmarks")
                    h.setAction(R.drawable.ic_more, "More") { folderMenu(item.name) }
                }
                is Item.Mark -> {
                    val r = item.row
                    // Found by searching: which folder it's in.
                    val where = if (query.isNotEmpty() && r.folder.isNotEmpty()) "${r.folder} · " else ""
                    h.set(R.drawable.ic_star, UrlUtils.readableTitle(r.title, r.url), where + UrlUtils.siteName(r.url))
                    h.setFavicon(r.url)
                    h.setAction(R.drawable.ic_more, "More") { rowMenu(r) }
                }
            }
        },
        onClick = { item ->
            when (item) {
                is Item.Folder -> openFolder(item.name)
                is Item.Mark -> openUrl(item.row.url)
            }
        },
        onLongClick = { item ->
            when (item) {
                is Item.Folder -> folderMenu(item.name)
                is Item.Mark -> rowMenu(item.row)
            }
        },
    )

    /** Back leaves a search, then a folder, then the screen. */
    private val back = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (query.isNotEmpty()) search.setText("") else openFolder("")
        }
    }

    private val importPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Bookmarks"
        toolbar.subtitle = Profiles.current().name
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.menu.add(0, 1, 0, "Import").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            Dialogs.confirm(
                this, "Import bookmarks",
                "From a bookmarks file another browser saved: in Chrome on a computer, Bookmarks → Bookmark manager → ⋮ → Export bookmarks; " +
                    "in Firefox, Bookmarks → Manage bookmarks → Import and Backup → Export. Copy the file to this phone, then choose it.",
                "Choose file",
            ) {
                try {
                    importPicker.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream"))
                } catch (e: Exception) {
                    Messages.show(this, "No app to choose a file with")
                }
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, back)
        search.visibility = View.VISIBLE
        search.hint = "Search your bookmarks"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
        })
        list.adapter = adapter
        enableSwipe({ vh -> adapter.items.getOrNull(vh.bindingAdapterPosition) is Item.Mark }) { vh ->
            (adapter.items.getOrNull(vh.bindingAdapterPosition) as? Item.Mark)?.let { delete(it.row) }
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { SwiftApp.db.bookmarks(Profiles.currentId) }
            render()
        }
    }

    private fun folders(): List<String> = rows.map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

    private fun openFolder(name: String) {
        folder = name
        toolbar.title = name.ifEmpty { "Bookmarks" }
        list.scrollToPosition(0)
        render()
    }

    private fun render() {
        val visible = rows.filter { it.id !in hiddenIds }
        val items: List<Item> = if (query.isNotEmpty()) {
            visible.filter { it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) }.map { Item.Mark(it) }
        } else {
            val inFolder = visible.filter { it.folder == folder }.map { Item.Mark(it) }
            if (folder.isEmpty()) {
                folders().map { name -> Item.Folder(name, visible.count { it.folder == name }) } + inFolder
            } else {
                inFolder
            }
        }
        adapter.items = items
        back.isEnabled = query.isNotEmpty() || folder.isNotEmpty()
        when {
            query.isNotEmpty() -> showEmpty(items.isEmpty(), "Nothing found", "No bookmark has \"$query\" in its name or address.", R.drawable.ic_search)
            folder.isNotEmpty() -> showEmpty(items.isEmpty(), "This folder is empty", "Move bookmarks here with ⋮ → Move to folder.", R.drawable.ic_folder)
            else -> showEmpty(items.isEmpty(), "No bookmarks yet", "Tap the star in the ≡ menu to keep a page here.", R.drawable.ic_star_border)
        }
    }

    private fun delete(r: BookmarkRow) {
        removeLater(
            "b${r.id}", "Bookmark removed",
            hide = {
                hiddenIds += r.id
                render()
            },
            restore = {
                hiddenIds -= r.id
                render()
            },
            commit = {
                lifecycleScope.launch(Dispatchers.IO) { SwiftApp.db.deleteBookmark(r.id) }
            },
        )
    }

    /** The bookmark's name and its address. */
    private fun editBookmark(r: BookmarkRow) {
        val pad = Ui.dp(this, 24)
        val name = EditText(this).apply {
            hint = "Name"
            setSingleLine()
            setText(r.title)
        }
        val address = EditText(this).apply {
            hint = "Address"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(r.url)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 3, pad, 0)
            addView(name)
            addView(address)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit bookmark")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        // A wrong address keeps the dialog open with what was typed (2.6).
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val url = UrlUtils.toUrlOrSearch(address.text.toString())
            if (!UrlUtils.isHttp(url) || UrlUtils.searchQuery(url) != null && !address.text.contains('.')) {
                address.error = "That isn't a web address"
                address.requestFocus()
                return@setOnClickListener
            }
            dialog.dismiss()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { SwiftApp.db.updateBookmark(r.id, name.text.toString().trim(), url) }
                load()
            }
        }
    }

    /** Into a folder, a new one, or back to the top of the list. */
    private fun moveToFolder(r: BookmarkRow) {
        val names = folders().filter { it != r.folder }
        val choices = (if (r.folder.isNotEmpty()) listOf("Top of the list") else emptyList()) + names + "New folder…"
        MaterialAlertDialogBuilder(this)
            .setTitle("Move to folder")
            .setItems(choices.toTypedArray()) { _, which ->
                val pick = choices[which]
                when {
                    which == choices.lastIndex -> Sheets.askText(this, "New folder", "Folder name", "") { name ->
                        val n = name.trim().take(60)
                        if (n.isNotEmpty()) setFolder(r, n)
                    }
                    pick == "Top of the list" && r.folder.isNotEmpty() && which == 0 -> setFolder(r, "")
                    else -> setFolder(r, pick)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun setFolder(r: BookmarkRow, name: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SwiftApp.db.setBookmarkFolder(r.id, name) }
            load()
            Messages.show(this@BookmarksActivity, if (name.isEmpty()) "Moved to the top of the list" else "Moved to $name")
        }
    }

    private fun folderMenu(name: String) {
        Sheets.actions(
            this, name, null,
            listOf(
                Sheets.Action(R.drawable.ic_edit, "Rename", primary = true) {
                    Sheets.askText(this, "Rename folder", "Folder name", name) { text ->
                        val n = text.trim().take(60)
                        if (n.isEmpty() || n == name) return@askText
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { SwiftApp.db.renameBookmarkFolder(Profiles.currentId, name, n) }
                            load()
                        }
                    }
                },
                // The bookmarks stay, at the top of the list.
                Sheets.Action(R.drawable.ic_close, "Remove folder (keeps its bookmarks)") {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SwiftApp.db.renameBookmarkFolder(Profiles.currentId, name, "") }
                        load()
                        Messages.show(this@BookmarksActivity, "Its bookmarks are at the top of the list")
                    }
                },
            ),
        )
    }

    private fun rowMenu(r: BookmarkRow) {
        Sheets.actions(
            this, UrlUtils.readableTitle(r.title, r.url), UrlUtils.siteName(r.url),
            listOf(
                Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { openUrl(r.url, newTab = true) },
                Sheets.Action(R.drawable.ic_edit, "Edit", primary = true) { editBookmark(r) },
                Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(r.url) },
                Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copyText(this, r.url) },
                Sheets.Action(R.drawable.ic_folder, "Move to folder") { moveToFolder(r) },
                Sheets.Action(R.drawable.ic_delete, "Remove bookmark", danger = true) { delete(r) },
            ),
            com.swiftbrowser.core.Favicons.cached(r.url), fullAddress = r.url,
        )
    }

    /** A bookmarks file saved by Chrome, Firefox or another browser (the "Netscape" format they share). */
    private fun importFrom(uri: android.net.Uri) {
        val pid = Profiles.currentId
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext -1
                    if (bytes.size > 30_000_000) return@withContext -1
                    val text = String(bytes, Charsets.UTF_8)
                    val marks = BookmarkFile.parse(text)
                    val db = SwiftApp.db
                    val had = db.bookmarks(pid).map { it.url }.toHashSet()
                    var added = 0
                    for (m in marks) {
                        if (m.url in had) continue
                        db.importBookmark(pid, m.url, m.title, m.added, m.folder)
                        had += m.url
                        added++
                    }
                    added
                } catch (e: Exception) {
                    com.swiftbrowser.core.L.w("Couldn't import bookmarks", e)
                    -1
                }
            }
            load()
            Messages.show(
                this@BookmarksActivity,
                when (count) {
                    -1 -> "That file couldn't be read"
                    0 -> "No new bookmarks in that file"
                    1 -> "1 bookmark imported"
                    else -> "$count bookmarks imported"
                },
            )
        }
    }
}

/** Reads a browser's bookmarks file: links in folders (<H3>), as Chrome and Firefox export them (2.6). */
object BookmarkFile {
    class Mark(val url: String, val title: String, val folder: String, val added: Long)

    private val TOKEN = Regex("<(/?)(dl|h3|a)\\b([^>]*)>(?:([^<]*)</(?:h3|a)>)?", RegexOption.IGNORE_CASE)
    private val HREF = Regex("href\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val ADDED = Regex("add_date\\s*=\\s*\"(\\d+)\"", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<Mark> {
        val out = ArrayList<Mark>()
        val stack = ArrayList<String>()
        var pending: String? = null
        val now = System.currentTimeMillis()
        for (m in TOKEN.findAll(html)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            when {
                tag == "h3" && !closing -> pending = decode(m.groupValues[4]).trim()
                tag == "dl" && !closing -> {
                    stack += pending ?: (stack.lastOrNull() ?: "")
                    pending = null
                }
                tag == "dl" && closing -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                tag == "a" && !closing -> {
                    val url = HREF.find(m.groupValues[3])?.groupValues?.get(1)?.let { decode(it) } ?: continue
                    if (!UrlUtils.isHttp(url)) continue
                    val added = ADDED.find(m.groupValues[3])?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 } ?: now
                    out += Mark(url, decode(m.groupValues[4]).trim(), stack.lastOrNull()?.take(60) ?: "", added.coerceAtMost(now))
                }
            }
        }
        return out
    }

    private fun decode(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

class DownloadsActivity : ListActivity() {
    private val adapter = DownloadAdapter()
    private val listener: () -> Unit = { refresh() }
    private val hiddenIds = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Downloads"
        toolbar.subtitle = Downloads.describe(Downloads.directory())
        // Search by name (2.6), like History and Bookmarks.
        search.visibility = View.VISIBLE
        search.hint = "Search your downloads"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refresh()
            }
        })
        list.adapter = adapter
        list.itemAnimator = null
        // Finished, failed and paused downloads can be swiped off the list (the file stays).
        enableSwipe({ vh ->
            val d = adapter.items.getOrNull(vh.bindingAdapterPosition)
            d != null && d.status != Downloads.RUNNING && d.status != Downloads.QUEUED && d.status != Downloads.CONVERTING
        }) { vh -> adapter.items.getOrNull(vh.bindingAdapterPosition)?.let { removeFromList(it, deleteFile = false) } }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        Downloads.addListener(listener)
        refresh()
    }

    override fun onStop() {
        Downloads.removeListener(listener)
        super.onStop()
    }

    private var query = ""

    /** What each row showed, so a progress tick redraws only the rows that changed (2.6). */
    private var drawn: List<String> = emptyList()

    private fun refresh() {
        val items = Downloads.all().filter { it.id !in hiddenIds && (query.isEmpty() || it.fileName.contains(query, ignoreCase = true)) }
        val now = items.map { "${it.status}|${it.done}|${it.total}|${it.fileName}|${it.error}|${it.bytesPerSecond}" }
        if (items.size == adapter.items.size && items.indices.all { items[it] === adapter.items[it] }) {
            for (i in now.indices) if (now[i] != drawn.getOrNull(i)) adapter.notifyItemChanged(i)
        } else {
            adapter.items = items
        }
        drawn = now
        if (query.isNotEmpty()) {
            showEmpty(items.isEmpty(), "Nothing found", "No download has \"$query\" in its name.", R.drawable.ic_search)
        } else {
            showEmpty(
                items.isEmpty(), "No downloads yet",
                "Hold a link, picture or video and choose Download. On pages with videos, the download button appears next to the address.",
                R.drawable.ic_download,
            )
        }
    }

    /** Off the list (and with [deleteFile], the file too) once the Undo is gone. */
    private fun removeFromList(d: Downloads.Item, deleteFile: Boolean) {
        val running = d.status == Downloads.RUNNING || d.status == Downloads.QUEUED || d.status == Downloads.CONVERTING
        removeLater(
            "dl${d.id}",
            when {
                running -> "Download cancelled"
                deleteFile -> "Deleted ${d.fileName}"
                else -> "Removed from the list (the file stays)"
            },
            hide = {
                hiddenIds += d.id
                refresh()
            },
            restore = {
                hiddenIds -= d.id
                refresh()
            },
            commit = {
                Downloads.remove(d.id, deleteFile = deleteFile || running)
                hiddenIds -= d.id
            },
        )
    }

    private inner class DownloadHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.dl_icon)
        val name: TextView = v.findViewById(R.id.dl_name)
        val progress: ProgressBar = v.findViewById(R.id.dl_progress)
        val status: TextView = v.findViewById(R.id.dl_status)
        val action: ImageButton = v.findViewById(R.id.dl_action)
        val more: ImageButton = v.findViewById(R.id.dl_more)
    }

    private inner class DownloadAdapter : RecyclerView.Adapter<DownloadHolder>() {
        var items: List<Downloads.Item> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DownloadHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))).also {
                // The bar in the accent chosen (2.6), your own included.
                it.progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(parent.context))
            }

        override fun onBindViewHolder(h: DownloadHolder, position: Int) {
            val d = items[position]
            val mime = d.mime ?: UrlUtils.mimeFromName(d.fileName) ?: ""
            val video = d.kind == Downloads.KIND_HLS || mime.startsWith("video")
            h.icon.setImageResource(
                when {
                    video -> R.drawable.ic_movie
                    mime.startsWith("image") -> R.drawable.ic_image
                    mime.startsWith("audio") -> R.drawable.ic_headphones
                    PageActions.isSavedPage(d) -> R.drawable.ic_offline
                    // What kind of file it is (2.6), not just "a download".
                    com.swiftbrowser.docs.Docs.kindOf(d.fileName, d.mime) != null -> R.drawable.ic_documents
                    d.fileName.endsWith(".zip", true) || d.fileName.endsWith(".rar", true) || d.fileName.endsWith(".7z", true) -> R.drawable.ic_folder
                    d.fileName.endsWith(".apk", true) -> R.drawable.ic_update
                    else -> R.drawable.ic_download
                },
            )
            h.name.text = d.fileName
            val pct = d.percent
            h.progress.visibility = if (d.status == Downloads.DONE) View.GONE else View.VISIBLE
            h.progress.isIndeterminate = (d.status == Downloads.RUNNING && pct < 0) || d.status == Downloads.CONVERTING
            // Moves smoothly to the new value instead of jumping.
            if (pct >= 0) h.progress.setProgress(pct, h.progress.progress < pct)
            h.status.text = statusText(d)
            h.status.setTextColor(if (d.status == Downloads.FAILED) Ui.danger(this@DownloadsActivity) else Ui.textSecondary(this@DownloadsActivity))
            when (d.status) {
                Downloads.RUNNING, Downloads.QUEUED -> {
                    h.action.visibility = View.VISIBLE
                    h.action.setImageResource(R.drawable.ic_pause)
                    h.action.contentDescription = "Pause"
                    h.action.setOnClickListener { Downloads.pause(d.id) }
                }
                Downloads.PAUSED, Downloads.FAILED -> {
                    h.action.visibility = View.VISIBLE
                    h.action.setImageResource(if (d.status == Downloads.FAILED) R.drawable.ic_refresh else R.drawable.ic_play)
                    h.action.contentDescription = if (d.status == Downloads.FAILED) "Try again" else "Resume"
                    h.action.setOnClickListener { Downloads.resume(d.id) }
                }
                else -> h.action.visibility = View.GONE
            }
            h.itemView.setOnClickListener { if (d.status == Downloads.DONE) open(d) }
            h.itemView.setOnLongClickListener {
                menu(d)
                true
            }
            h.more.setOnClickListener { menu(d) }
        }
    }

    /** When it was downloaded: the time today, else the day (2.6). */
    private fun dateText(time: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return when {
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) ->
                android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(time))
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) ->
                java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(time))
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(time))
        }
    }

    private fun statusText(d: Downloads.Item): String = when (d.status) {
        Downloads.DONE -> listOfNotNull(
            com.swiftbrowser.docs.Docs.kindOf(d.fileName, d.mime)?.label, UrlUtils.formatBytes(d.done), dateText(d.created),
        ).joinToString(" · ")
        Downloads.QUEUED -> if (d.error == Downloads.WAITING_FOR_WIFI) "Waiting for Wi-Fi (it's a big file)" else "Waiting to start…"
        Downloads.PAUSED -> "Paused · ${UrlUtils.formatBytes(d.done)}${if (d.percent >= 0) " (${d.percent}%)" else ""}"
        Downloads.FAILED -> "Didn't finish: ${d.error ?: "something went wrong"}. Tap ↻ to try again."
        Downloads.CONVERTING -> "Saving as MP4…"
        else -> buildString {
            if (d.percent >= 0) append("${d.percent}% · ")
            append(UrlUtils.formatBytes(d.done))
            if (d.total > 0 && d.kind != Downloads.KIND_HLS) append(" of ").append(UrlUtils.formatBytes(d.total))
            if (d.bytesPerSecond > 0) append(" · ").append(UrlUtils.formatBytes(d.bytesPerSecond)).append("/s")
        }
    }

    private fun open(d: Downloads.Item) {
        if (VpnUi.isSetupFile(d)) {
            VpnUi.addDownloaded(this, d)
            return
        }
        if (PageActions.isSavedPage(d) || com.swiftbrowser.docs.Docs.kindOf(d.fileName, d.mime) != null && File(d.filePath).exists()) {
            // Documents (pages saved as PDF among them) open in Swift's own viewer (2.4).
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_FILE, d.filePath)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }
        val intent = openFileIntent(this, d)
        if (intent == null) {
            Messages.show(this, "The file isn't there any more")
            return
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Messages.show(this, "No app on this phone can open this file")
        }
    }

    private fun shareFile(d: Downloads.Item) {
        openFileIntent(this, d)?.let { view ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = view.type
                putExtra(Intent.EXTRA_STREAM, view.data)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, "Share"))
            } catch (_: Exception) {
            }
        }
    }

    private fun menu(d: Downloads.Item) {
        val done = d.status == Downloads.DONE
        val running = d.status == Downloads.RUNNING || d.status == Downloads.QUEUED || d.status == Downloads.CONVERTING
        val actions = ArrayList<Sheets.Action>()
        if (done) {
            actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { open(d) }
            actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { shareFile(d) }
            actions += Sheets.Action(R.drawable.ic_folder, "Where", primary = true) { showInFolder(d) }
        }
        actions += Sheets.Action(R.drawable.ic_link, "Copy", primary = true) { copyText(this, d.url) }
        if (!running) actions += Sheets.Action(R.drawable.ic_close, "Remove from list (keep the file)") { removeFromList(d, deleteFile = false) }
        actions += if (running) {
            Sheets.Action(R.drawable.ic_close, "Cancel download", danger = true) { removeFromList(d, deleteFile = true) }
        } else {
            Sheets.Action(R.drawable.ic_delete, "Delete the file", danger = true) { removeFromList(d, deleteFile = true) }
        }
        val mime = d.mime ?: UrlUtils.mimeFromName(d.fileName) ?: ""
        val kind = when {
            d.kind == Downloads.KIND_HLS || mime.startsWith("video") -> R.drawable.ic_movie
            mime.startsWith("image") -> R.drawable.ic_image
            mime.startsWith("audio") -> R.drawable.ic_headphones
            PageActions.isSavedPage(d) -> R.drawable.ic_offline
            else -> R.drawable.ic_download
        }
        Sheets.actions(this, d.fileName, statusText(d), actions, iconRes = kind)
    }

    /** Opens the phone's file manager at the download's folder, or says where it is. */
    private fun showInFolder(d: Downloads.Item) {
        val dir = File(d.filePath).parentFile ?: return
        // 2.7: in Swift's own Files screen.
        if (dir.isDirectory) {
            FilesActivity.open(this, dir)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(d.fileName)
            .setMessage("Saved in ${Downloads.describe(dir)}\n\n${dir.path}")
            .setPositiveButton(android.R.string.ok, null)
            .showImmersive()
    }

    companion object {
        fun openFileIntent(ctx: Context, d: Downloads.Item): Intent? {
            val file = File(d.filePath)
            if (!file.exists()) return null
            // Never a file:// link (Android refuses them): Swift's shared folders, or a copy (2.6).
            val uri = com.swiftbrowser.docs.Docs.shareUri(ctx, file) ?: return null
            val mime = UrlUtils.mimeFromName(d.fileName) ?: d.mime ?: "*/*"
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
    val activity = ctx as? android.app.Activity
    if (activity is MainActivity) activity.snack("Copied")
    else if (activity != null) Messages.show(activity, "Copied")
    else android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}
