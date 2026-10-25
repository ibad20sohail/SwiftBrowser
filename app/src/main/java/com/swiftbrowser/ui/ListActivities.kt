package com.swiftbrowser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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

/** Shared shell for the list screens (toolbar, optional search box, list, empty text). */
abstract class ListActivity : AppCompatActivity() {
    protected lateinit var toolbar: MaterialToolbar
    protected lateinit var list: RecyclerView
    protected lateinit var empty: TextView
    protected lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        toolbar = findViewById(R.id.list_toolbar)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.list_empty)
        search = findViewById(R.id.list_search)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setSubtitleTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        toolbar.setSubtitleTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.page_bg)
        list.layoutManager = LinearLayoutManager(this)
        SystemBars.keepHidden(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    /** An empty list: an icon, what is missing, and how to fill it. */
    protected fun showEmpty(isEmpty: Boolean, title: String, hint: String? = null, icon: Int = 0) {
        if (!isEmpty) {
            empty.visibility = View.GONE
            return
        }
        val text = android.text.SpannableStringBuilder(title)
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
        text.setSpan(android.text.style.RelativeSizeSpan(1.15f), 0, title.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)), 0, title.length, 0)
        if (!hint.isNullOrBlank()) text.append("\n\n").append(hint)
        empty.text = text
        val d = if (icon == 0) null else androidx.core.content.ContextCompat.getDrawable(this, icon)?.mutate()?.also {
            it.setTint(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
            val size = (56 * resources.displayMetrics.density).toInt()
            it.setBounds(0, 0, size, size)
        }
        empty.setCompoundDrawablesRelative(null, d, null, null)
        empty.compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
        if (empty.visibility != View.VISIBLE) {
            empty.visibility = View.VISIBLE
            empty.alpha = 0f
            empty.animate().alpha(1f).setDuration(180).start()
        }
    }

    protected fun openUrl(url: String, newTab: Boolean = false) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_NEW_TAB, newTab))
        finish()
    }
}

class HistoryActivity : ListActivity() {
    private val adapter = RowAdapter<HistoryRow>(
        bind = { h, r ->
            val ago = DateUtils.getRelativeTimeSpanString(r.lastVisit, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.set(R.drawable.ic_history, r.title.ifBlank { UrlUtils.display(r.url) }, "${UrlUtils.display(r.url)} • $ago")
            h.setAction(R.drawable.ic_close, "Remove") {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SwiftApp.db.deleteHistory(r.id) }
                    load()
                }
            }
        },
        onClick = { openUrl(it.url) },
        onLongClick = { r -> rowMenu(r.url) },
    )
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "History • ${Profiles.current().name}"
        toolbar.menu.add(0, 1, 0, "Clear all").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage("Clear all history of ${Profiles.current().name}?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SwiftApp.db.clearHistory(Profiles.currentId) }
                        load()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
            true
        }
        search.visibility = View.VISIBLE
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
        load()
    }

    private fun load() {
        val q = search.text.toString()
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { SwiftApp.db.history(Profiles.currentId, q) }
            adapter.items = rows
            if (q.isBlank()) {
                showEmpty(rows.isEmpty(), "No history yet", "Pages you open show up here, newest first.", R.drawable.ic_history)
            } else {
                showEmpty(rows.isEmpty(), "Nothing found", "No page in your history has \"${q.trim()}\" in its title or address.", R.drawable.ic_search)
            }
        }
    }

    private fun rowMenu(url: String) {
        val options = arrayOf("Open in new tab", "Copy link")
        MaterialAlertDialogBuilder(this).setTitle(UrlUtils.display(url)).setItems(options) { _, which ->
            when (which) {
                0 -> openUrl(url, newTab = true)
                1 -> copyText(this, url)
            }
        }.showImmersive()
    }
}

class BookmarksActivity : ListActivity() {
    private val adapter = RowAdapter<BookmarkRow>(
        bind = { h, r ->
            h.set(R.drawable.ic_star, r.title.ifBlank { UrlUtils.display(r.url) }, UrlUtils.display(r.url))
            h.setAction(R.drawable.ic_more, "More") { rowMenu(r) }
        },
        onClick = { openUrl(it.url) },
        onLongClick = { rowMenu(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Bookmarks • ${Profiles.current().name}"
        list.adapter = adapter
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { SwiftApp.db.bookmarks(Profiles.currentId) }
            adapter.items = rows
            showEmpty(rows.isEmpty(), "No bookmarks yet", "Tap the star in the ⋮ menu to keep a page here.", R.drawable.ic_star_border)
        }
    }

    private fun rowMenu(r: BookmarkRow) {
        val options = arrayOf("Open in new tab", "Edit", "Copy link", "Delete")
        MaterialAlertDialogBuilder(this).setTitle(r.title.ifBlank { r.url }).setItems(options) { _, which ->
            when (which) {
                0 -> openUrl(r.url, newTab = true)
                1 -> Sheets.askText(this, "Edit bookmark", "Title", r.title) { title ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SwiftApp.db.updateBookmark(r.id, title, r.url) }
                        load()
                    }
                }
                2 -> copyText(this, r.url)
                3 -> lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SwiftApp.db.deleteBookmark(r.id) }
                    load()
                }
            }
        }.showImmersive()
    }
}

class DownloadsActivity : ListActivity() {
    private val adapter = DownloadAdapter()
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar.title = "Downloads"
        toolbar.subtitle = Downloads.describe(Downloads.directory())
        list.adapter = adapter
        list.itemAnimator = null
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
        adapter.items = Downloads.all()
        showEmpty(
            adapter.items.isEmpty(), "No downloads yet",
            "Hold a link, picture or video and choose Download. On pages with videos, the download button appears next to the address.",
            R.drawable.ic_download,
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
            DownloadHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun onBindViewHolder(h: DownloadHolder, position: Int) {
            val d = items[position]
            val video = d.kind == Downloads.KIND_HLS || (d.mime ?: UrlUtils.mimeFromName(d.fileName) ?: "").startsWith("video")
            h.icon.setImageResource(if (video) R.drawable.ic_movie else R.drawable.ic_download)
            h.name.text = d.fileName
            val pct = d.percent
            h.progress.visibility = if (d.status == Downloads.DONE) View.GONE else View.VISIBLE
            h.progress.isIndeterminate = (d.status == Downloads.RUNNING && pct < 0) || d.status == Downloads.CONVERTING
            // Moves smoothly to the new value instead of jumping.
            if (pct >= 0) h.progress.setProgress(pct, h.progress.progress < pct)
            h.status.text = statusText(d)
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
                    h.action.contentDescription = "Resume"
                    h.action.setOnClickListener { Downloads.resume(d.id) }
                }
                else -> h.action.visibility = View.GONE
            }
            h.itemView.setOnClickListener { if (d.status == Downloads.DONE) open(d) }
            h.more.setOnClickListener { menu(d) }
        }
    }

    private fun statusText(d: Downloads.Item): String = when (d.status) {
        Downloads.DONE -> "${UrlUtils.formatBytes(d.done)} • Done"
        Downloads.QUEUED -> if (d.error == Downloads.WAITING_FOR_WIFI) "Waiting for Wi-Fi (big download)" else "Waiting…"
        Downloads.PAUSED -> "Paused • ${UrlUtils.formatBytes(d.done)}${if (d.percent >= 0) " (${d.percent}%)" else ""}"
        Downloads.FAILED -> "Failed: ${d.error ?: "unknown error"}"
        Downloads.CONVERTING -> "Converting to MP4…"
        else -> buildString {
            if (d.percent >= 0) append("${d.percent}% • ")
            append(UrlUtils.formatBytes(d.done))
            if (d.total > 0 && d.kind != Downloads.KIND_HLS) append(" of ").append(UrlUtils.formatBytes(d.total))
            if (d.bytesPerSecond > 0) append(" • ").append(UrlUtils.formatBytes(d.bytesPerSecond)).append("/s")
        }
    }

    private fun open(d: Downloads.Item) {
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
            Snackbar.make(list, "File not found", Snackbar.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Snackbar.make(list, "No app can open this file", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun menu(d: Downloads.Item) {
        val options = ArrayList<String>()
        if (d.status == Downloads.DONE) options += listOf("Open", "Share", "Show where it's saved")
        options += "Copy download link"
        options += if (d.status == Downloads.DONE) "Remove from list" else "Cancel download"
        options += "Delete file"
        MaterialAlertDialogBuilder(this).setTitle(d.fileName).setItems(options.toTypedArray()) { _, which ->
            when (options[which]) {
                "Open" -> open(d)
                "Share" -> openFileIntent(this, d)?.let { view ->
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
                "Show where it's saved" -> showInFolder(d)
                "Copy download link" -> copyText(this, d.url)
                "Remove from list" -> Downloads.remove(d.id, deleteFile = false)
                "Cancel download" -> Downloads.remove(d.id, deleteFile = true)
                "Delete file" -> MaterialAlertDialogBuilder(this)
                    .setMessage("Delete ${d.fileName}?")
                    .setPositiveButton("Delete") { _, _ -> Downloads.remove(d.id, deleteFile = true) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showImmersive()
            }
        }.showImmersive()
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
    android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}
