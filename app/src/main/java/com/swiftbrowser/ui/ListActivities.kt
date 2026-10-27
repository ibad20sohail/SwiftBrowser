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
import androidx.core.content.FileProvider
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
        // In red: it removes everything at once (after asking).
        val clearAll = android.text.SpannableString("Clear all").apply {
            setSpan(android.text.style.ForegroundColorSpan(Ui.danger(this@HistoryActivity)), 0, length, 0)
        }
        toolbar.menu.add(0, 1, 0, clearAll).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            Dialogs.confirm(
                this, "Clear all history?",
                "Every page in ${Profiles.current().name}'s history is removed. This can't be undone.",
                "Clear all", danger = true,
            ) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SwiftApp.db.clearHistory(Profiles.currentId) }
                    load()
                    Messages.show(this@HistoryActivity, "History cleared")
                }
            }
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
    private var rows: List<BookmarkRow> = emptyList()
    private val hiddenIds = HashSet<Long>()

    private val adapter = RowAdapter<BookmarkRow>(
        bind = { h, r ->
            h.set(R.drawable.ic_star, UrlUtils.readableTitle(r.title, r.url), UrlUtils.siteName(r.url))
            h.setFavicon(r.url)
            h.setAction(R.drawable.ic_more, "More") { rowMenu(r) }
        },
        onClick = { openUrl(it.url) },
        onLongClick = { rowMenu(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Bookmarks"
        toolbar.subtitle = Profiles.current().name
        list.adapter = adapter
        enableSwipe({ true }) { vh -> adapter.items.getOrNull(vh.bindingAdapterPosition)?.let { delete(it) } }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            rows = withContext(Dispatchers.IO) { SwiftApp.db.bookmarks(Profiles.currentId) }
            render()
        }
    }

    private fun render() {
        val shown = rows.filter { it.id !in hiddenIds }
        adapter.items = shown
        showEmpty(shown.isEmpty(), "No bookmarks yet", "Tap the star in the ≡ menu to keep a page here.", R.drawable.ic_star_border)
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit bookmark")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val url = UrlUtils.toUrlOrSearch(address.text.toString()).takeIf { UrlUtils.isHttp(it) } ?: r.url
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SwiftApp.db.updateBookmark(r.id, name.text.toString().trim(), url) }
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun rowMenu(r: BookmarkRow) {
        Sheets.actions(
            this, UrlUtils.readableTitle(r.title, r.url), UrlUtils.siteName(r.url),
            listOf(
                Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { openUrl(r.url, newTab = true) },
                Sheets.Action(R.drawable.ic_edit, "Edit", primary = true) { editBookmark(r) },
                Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(r.url) },
                Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copyText(this, r.url) },
                Sheets.Action(R.drawable.ic_delete, "Delete bookmark", danger = true) { delete(r) },
            ),
            com.swiftbrowser.core.Favicons.cached(r.url), fullAddress = r.url,
        )
    }
}

class DownloadsActivity : ListActivity() {
    private val adapter = DownloadAdapter()
    private val listener: () -> Unit = { refresh() }
    private val hiddenIds = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Downloads"
        toolbar.subtitle = Downloads.describe(Downloads.directory())
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

    private fun refresh() {
        adapter.items = Downloads.all().filter { it.id !in hiddenIds }
        showEmpty(
            adapter.items.isEmpty(), "No downloads yet",
            "Hold a link, picture or video and choose Download. On pages with videos, the download button appears next to the address.",
            R.drawable.ic_download,
        )
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
            DownloadHolder(Palette.paint(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)))

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

    private fun statusText(d: Downloads.Item): String = when (d.status) {
        Downloads.DONE -> "${UrlUtils.formatBytes(d.done)} · Done"
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
        if (PageActions.isSavedPage(d)) {
            // PDFs (pages saved for offline reading among them) open in Swift's own viewer.
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
        val storage = android.os.Environment.getExternalStorageDirectory().path
        if (dir.path.startsWith(storage)) {
            val docId = "primary:" + dir.path.removePrefix(storage).trimStart('/')
            val uri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
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
            val uri = try {
                FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            } catch (_: Exception) {
                return null
            }
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
