package com.swiftbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.DocIcon
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.files.Archives
import com.swiftbrowser.files.FileItem
import com.swiftbrowser.files.FileJobs
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileOpener
import com.swiftbrowser.files.FileOps
import com.swiftbrowser.files.FileProblem
import com.swiftbrowser.files.FileThumbs
import com.swiftbrowser.files.PlainAccess
import com.swiftbrowser.files.Transfers
import com.swiftbrowser.files.Volumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * Files (2.7): a file manager in the browser, as in ZArchiver. The phone's storage, the SD card,
 * Download and Swift's downloads; folders and files with small pictures, sorted as chosen, hidden
 * files on request, and search in a folder and below. Hold a row to select; Copy, Move (and Paste
 * in another folder), Rename, Delete, Share, Compress (ZIP, with a password if wanted, or 7z) and
 * Details. Archives (ZIP, 7z, RAR, tar and the rest) open like folders: extract all of one or some
 * of it, or open a single file. Long jobs run in the background (FileJobs) with their progress here
 * and in the notification shade. Back: clears a search, then the selection, then goes up a folder,
 * then closes. Works as the first screen of its own (Swift's browser needn't be open).
 */
class FilesActivity : ListActivity() {
    /** A row: a file or folder on the phone ([file]) or in the archive being looked at ([entry]). */
    private class Row(
        val key: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val time: Long,
        val count: Int,
        val kind: FileKind,
        val file: File?,
        val entry: Archives.Entry?,
        val where: String?,
    )

    /** What waits to be put in a folder: files to copy or move, or an archive to extract there. */
    private sealed class Clip {
        class Files(val files: List<File>, val move: Boolean) : Clip()
        class Extract(val archive: File, val index: Archives.Index?, val select: Set<String>?, val base: String) : Clip()
    }

    private val sp get() = SwiftApp.prefs.sp
    private val sort get() = sp.getInt(PREF_SORT, FileOps.SORT_NAME)
    private val showHidden get() = sp.getBoolean(PREF_HIDDEN, false)

    private var dir: File = Volumes.internalRoot()
    private var index: Archives.Index? = null
    private var inArchive = ""
    private var items: List<FileItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private var searchRows: List<Row>? = null
    private var unreadable = false
    private var loading = false
    private var loaded = false
    private var searching = false

    /** The screen opened without access to the files: they load once it is given. */
    private var needAccess = false
    private val selected = LinkedHashMap<String, Row>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private val positions = HashMap<String, Int>()
    private val adapter = Adapter()
    private var thumbPx = 0

    /** Where Swift saves downloads (its own folder when it may not use the phone's Download). */
    private val swiftDownloads: File by lazy { Downloads.directory() }

    private lateinit var pathScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var jobStrip: LinearLayout
    private lateinit var jobText: TextView
    private lateinit var jobBar: ProgressBar
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var pasteButton: MaterialButton
    private lateinit var actionBar: LinearLayout
    private lateinit var bottom: LinearLayout

    private var progressDialog: AlertDialog? = null
    private var progressFor: FileJobs.Job? = null
    private var progressName: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressDetail: TextView? = null
    private var questionShown: FileJobs.Question? = null
    private var opening: Messages.Handle? = null

    // ------------------------------------------------------------------ permissions

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            needAccess = false
            openFolder(dir)
        } else {
            render()
        }
    }

    private var afterWrite: (() -> Unit)? = null
    private val askWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val run = afterWrite
        afterWrite = null
        if (granted) run?.invoke() else say("Swift needs access to your files to change them")
    }

    private var cardAsked: Volumes.Volume? = null
    private var afterCard: (() -> Unit)? = null
    private var cardTriedPicker = false
    private val askCard: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val v = cardAsked ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK && Volumes.onAccessResult(this, v, r.data)) {
            val run = afterCard
            afterCard = null
            run?.invoke()
        } else if (r.resultCode == RESULT_OK) {
            afterCard = null
            Dialogs.info(this, "Choose the SD card itself", "Swift needs the whole SD card, not a folder in it. Try again and pick the card at the top.")
        } else if (!cardTriedPicker) {
            // The short question was refused or isn't there: Android's folder picker instead.
            cardTriedPicker = true
            try {
                askCard.launch(Volumes.pickerIntent(v))
            } catch (_: Exception) {
                afterCard = null
                say(Transfers.CARD_TEXT, long = true)
            }
        } else {
            afterCard = null
            say("Swift can only read the SD card until you allow it to change files there", long = true)
        }
    }

    private fun hasAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private var hasAsked = false

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: "Allow access to manage all files" in Android's settings.
            val app = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            try {
                startActivity(app)
            } catch (_: Exception) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) || !hasAsked) {
            hasAsked = true
            askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // "Don't ask again" was ticked: Android's settings for Swift.
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** Runs [action] once Swift may change files (asking Android first if needed). */
    private fun withWriteAccess(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted || Build.VERSION.SDK_INT > 28) {
            action()
        } else {
            afterWrite = action
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Runs [action] once Swift may change [files] (on an SD card, Android is asked once, for good). */
    private fun withChanges(files: Collection<File>, action: () -> Unit) = withWriteAccess {
        val v = Volumes.cardWithoutAccess(this, files)
        if (v == null) {
            action()
            return@withWriteAccess
        }
        Dialogs.confirm(
            this, "Allow changes on the SD card",
            "Android lets Swift change files on the SD card only once you allow it. On the next screen, tap Allow (or choose the SD card and tap Select).",
            "Continue",
        ) {
            cardAsked = v
            afterCard = action
            cardTriedPicker = false
            try {
                askCard.launch(Volumes.accessIntent(this, v))
            } catch (e: Exception) {
                L.w("No way to ask for the card", e)
                afterCard = null
                say(Transfers.CARD_TEXT, long = true)
            }
        }
    }

    // ------------------------------------------------------------------ the screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbPx = Ui.dp(this, 44)
        toolbar.title = "Files"
        toolbar.setNavigationOnClickListener { if (selected.isNotEmpty()) clearSelection() else finish() }
        toolbar.setOnMenuItemClickListener {
            onMenu(it.itemId)
            true
        }
        search.visibility = View.VISIBLE
        search.hint = "Search in this folder"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = scheduleSearch()
        })
        buildChrome()
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        onBackPressedDispatcher.addCallback(this, back)
        Volumes.refresh(this)
        dir = startFolder()
        if (hasAccess()) {
            openFolder(dir)
        } else {
            needAccess = true
            render()
            if (Build.VERSION.SDK_INT < 30) requestAccess()
        }
    }

    private fun startFolder(): File {
        intent.getStringExtra(EXTRA_FOLDER)?.let { p ->
            val f = File(p)
            if (f.isDirectory) return f
            f.parentFile?.takeIf { it.isDirectory }?.let { return it }
        }
        sp.getString(PREF_LAST, null)?.let { p -> File(p).takeIf { it.isDirectory } }?.let { return it }
        return Volumes.internalRoot()
    }

    private val jobListener: () -> Unit = { onJobsChanged() }

    override fun onStart() {
        super.onStart()
        visible = WeakReference(this)
        FileJobs.addScreen(jobListener)
        onJobsChanged()
    }

    override fun onStop() {
        FileJobs.removeScreen(jobListener)
        if (visible?.get() === this) visible = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's settings with access now, or a card put in.
        if (needAccess && hasAccess()) {
            needAccess = false
            Volumes.refresh(this)
            openFolder(dir)
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        opening?.dismiss()
        super.onDestroy()
    }

    /** Short of memory, or out of sight: the small pictures go (they are made again when needed). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) FileThumbs.trim()
    }

    /** Back: a search, then the selection, then up a folder (or out of an archive), then the screen. */
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val idx = index
            when {
                search.text.isNotEmpty() -> search.setText("")
                selected.isNotEmpty() -> clearSelection()
                idx != null && inArchive.isNotEmpty() -> showArchiveFolder(inArchive.substringBeforeLast('/', ""))
                idx != null -> leaveArchive()
                upFolder() != null -> openFolder(upFolder()!!)
                else -> finish()
            }
        }
    }

    /** The folder above, while still on the same storage. */
    private fun upFolder(): File? {
        val v = Volumes.of(this, dir) ?: return null
        if (dir.path == v.root.path) return null
        return dir.parentFile
    }

    private fun buildChrome() {
        val box = list.parent as ViewGroup
        val column = box.parent as LinearLayout
        crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 12), 0, Ui.dp(this@FilesActivity, 12), 0)
        }
        pathScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(crumbs)
        }
        column.addView(pathScroll, column.indexOfChild(box), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Palette.color(this@FilesActivity, R.color.sheet_bg))
        }
        bottom.addView(Ui.divider(this))
        jobStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 16), Ui.dp(this@FilesActivity, 10), Ui.dp(this@FilesActivity, 16), Ui.dp(this@FilesActivity, 10))
            background = Ui.ripple(this@FilesActivity, null, 0)
            visibility = View.GONE
            setOnClickListener { FileJobs.running?.let { showProgress(it) } }
        }
        jobText = Ui.text(this, Ui.Type.LABEL, "").apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        jobBar = horizontalBar()
        jobStrip.addView(jobText)
        jobStrip.addView(jobBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)).apply { topMargin = Ui.dp(this@FilesActivity, 8) })
        bottom.addView(jobStrip)

        pasteBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 16), Ui.dp(this@FilesActivity, 6), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 6))
            visibility = View.GONE
        }
        pasteText = Ui.text(this, Ui.Type.BODY, "", lines = 2)
        pasteBar.addView(pasteText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pasteBar.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setOnClickListener {
                clip = null
                updateChrome()
            }
        })
        pasteButton = MaterialButton(this).apply { setOnClickListener { paste() } }
        pasteBar.addView(pasteButton)
        bottom.addView(pasteBar)

        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@FilesActivity, 4), Ui.dp(this@FilesActivity, 2), Ui.dp(this@FilesActivity, 4), Ui.dp(this@FilesActivity, 2))
            visibility = View.GONE
        }
        bottom.addView(actionBar)
        column.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Palette.paint(bottom)
    }

    private fun horizontalBar(): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = ContextCompat.getDrawable(this@FilesActivity, R.drawable.progress_rounded)
        progressTintList = ColorStateList.valueOf(Ui.accent(this@FilesActivity))
        indeterminateTintList = ColorStateList.valueOf(Ui.accent(this@FilesActivity))
    }

    /** A button of the bar at the bottom: an icon over a short word. */
    private fun barButton(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit): View = LinearLayout(this).apply {
        val ctx = this@FilesActivity
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 6))
        background = Ui.ripple(ctx, null, 12)
        contentDescription = label
        isClickable = true
        isFocusable = true
        val color = if (danger) Ui.danger(ctx) else Ui.text(ctx)
        addView(ImageView(ctx).apply {
            setImageDrawable(Ui.icon(ctx, icon, color))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
        addView(Ui.text(ctx, Ui.Type.CAPTION, label, color, lines = 1).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2), 0)
        })
        setOnClickListener { onClick() }
    }

    private fun setActions(buttons: List<View>) {
        actionBar.removeAllViews()
        for (b in buttons) actionBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ------------------------------------------------------------------ folders

    /** Shows [to] (leaving an archive or a search), remembered for next time. */
    private fun openFolder(to: File, remember: Boolean = true) {
        rememberPosition()
        index = null
        inArchive = ""
        dir = to
        selected.clear()
        searchRows = null
        if (search.text.isNotEmpty()) search.setText("")
        if (remember) sp.edit().putString(PREF_LAST, to.path).apply()
        load()
    }

    private fun rememberPosition() {
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        val key = index?.let { it.file.path + "!" + inArchive } ?: dir.path
        positions[key] = lm.findFirstVisibleItemPosition()
    }

    private fun restorePosition() {
        val key = index?.let { it.file.path + "!" + inArchive } ?: dir.path
        val p = positions[key] ?: 0
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p.coerceAtLeast(0), 0)
    }

    /** Reads the folder off the main thread (a big one takes a moment on a slow phone). */
    private fun load(keepScroll: Boolean = false) {
        if (!hasAccess()) {
            render()
            return
        }
        loadJob?.cancel()
        val target = dir
        val hidden = showHidden
        val order = sort
        loading = true
        if (!keepScroll) showLoadingSoon("Opening the folder…")
        renderPath()
        updateChrome()
        loadJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { FileOps.list(target, hidden, order) }
            if (target != dir || index != null) return@launch
            loading = false
            loaded = true
            unreadable = found == null
            items = found.orEmpty()
            rows = items.map { rowOf(it) }
            render()
            if (!keepScroll) restorePosition()
            if (searchRows != null) runSearch()
        }
    }

    private fun rowOf(it: FileItem) = Row(it.file.path, it.name, it.isDir, it.size, it.time, it.count, it.kind, it.file, null, it.where)

    private fun rowOf(e: Archives.Entry, idx: Archives.Index, where: String? = null) =
        Row("a:" + e.path, e.name, e.isDir, e.size, e.time, if (e.isDir) idx.count(e.path) else -1, FileKind.of(e.name, e.isDir), null, e, where)

    private val showLoading = Runnable {
        if (loading) {
            empty.removeAllViews()
            empty.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ProgressBar(this@FilesActivity).apply { indeterminateTintList = ColorStateList.valueOf(Ui.accent(this@FilesActivity)) })
                addView(Ui.text(this@FilesActivity, Ui.Type.BODY_SECONDARY, loadingText).apply { setPadding(0, Ui.dp(this@FilesActivity, 12), 0, 0) })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            empty.visibility = View.VISIBLE
            adapter.set(emptyList())
        }
    }
    private var loadingText = ""

    private fun showLoadingSoon(text: String) {
        loadingText = text
        list.removeCallbacks(showLoading)
        list.postDelayed(showLoading, 250)
    }

    private fun render() {
        list.removeCallbacks(showLoading)
        val shown = searchRows ?: if (index != null) archiveRows() else rows
        adapter.set(shown)
        renderPath()
        updateChrome()
        when {
            !hasAccess() && index == null -> showNoAccess()
            loading && shown.isEmpty() -> {}
            searchRows != null -> showEmpty(shown.isEmpty() && !searching, "Nothing found", "No file or folder here has “${search.text.toString().trim()}” in its name.", R.drawable.ic_search)
            index != null -> showEmpty(shown.isEmpty(), "Nothing in here", null, R.drawable.ic_archive)
            unreadable -> showEmpty(true, "Swift can't open this folder", "Android keeps it private, or it is no longer there.", R.drawable.ic_folder)
            else -> showEmpty(shown.isEmpty(), "This folder is empty", if (!showHidden) "Hidden files (names starting with a dot) aren't shown." else null, R.drawable.ic_folder)
        }
    }

    private fun showNoAccess() {
        empty.removeAllViews()
        val state = Ui.emptyState(
            this, R.drawable.ic_folder, "See your files",
            if (Build.VERSION.SDK_INT >= 30) "Allow Swift to manage all files to see and change your folders here." else "Allow Swift to see your files to list your folders, pictures, videos and downloads here.",
        )
        state.addView(MaterialButton(this).apply {
            text = "Allow access"
            setOnClickListener { requestAccess() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@FilesActivity, 18)
        })
        empty.addView(state, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.visibility = View.VISIBLE
    }

    /** The folder's place, tappable part by part: "Phone storage › Download › Swift". */
    private fun renderPath() {
        crumbs.removeAllViews()
        val parts = ArrayList<Pair<String, () -> Unit>>()
        val v = Volumes.of(this, dir)
        if (v != null) {
            parts += v.name to { openFolder(v.root) }
            var at = v.root
            for (p in dir.path.removePrefix(v.root.path).split('/').filter { it.isNotEmpty() }) {
                at = File(at, p)
                val target = at
                parts += p to { openFolder(target) }
            }
        } else {
            parts += folderTitle(dir) to { openFolder(dir) }
        }
        val idx = index
        if (idx != null) {
            parts += idx.file.name to { showArchiveFolder("") }
            var at = ""
            for (p in inArchive.split('/').filter { it.isNotEmpty() }) {
                at = if (at.isEmpty()) p else "$at/$p"
                val target = at
                parts += p to { showArchiveFolder(target) }
            }
        }
        parts.forEachIndexed { i, (label, go) ->
            if (i > 0) {
                crumbs.addView(ImageView(this).apply {
                    setImageDrawable(Ui.icon(this@FilesActivity, R.drawable.ic_chevron_right, Ui.textSecondary(this@FilesActivity)))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)))
            }
            val last = i == parts.size - 1
            crumbs.addView(Ui.text(this, Ui.Type.LABEL, label, if (last) Ui.text(this) else Ui.accent(this), lines = 1).apply {
                setPadding(Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 8))
                background = Ui.ripple(this@FilesActivity, null, 12)
                maxWidth = Ui.dp(this@FilesActivity, 200)
                ellipsize = TextUtils.TruncateAt.MIDDLE
                if (!last) setOnClickListener { go() }
            })
        }
        pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** The name a folder goes by: "Phone storage" and "SD card" for their tops. */
    private fun folderTitle(f: File): String {
        Volumes.all(this).firstOrNull { it.root.path == f.path }?.let { return it.name }
        if (f.path == swiftDownloads.path && Volumes.of(this, f) == null) return "Swift's downloads"
        return f.name.ifEmpty { f.path }
    }

    // ------------------------------------------------------------------ the toolbar and the bars

    private fun updateChrome() {
        val idx = index
        val selecting = selected.isNotEmpty()
        val iconColor = Ui.text(this)
        toolbar.menu.clear()
        val hint = if (idx != null) "Search in this archive" else "Search in this folder"
        if (search.hint != hint) search.hint = hint
        if (selecting) {
            toolbar.title = if (selected.size == 1) "1 selected" else "${selected.size} selected"
            toolbar.subtitle = null
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = "Leave selection"
            addMenu(MENU_SELECT_ALL, "Select all", R.drawable.ic_select_all, iconColor)
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.navigationContentDescription = "Back"
            if (idx != null) {
                toolbar.title = if (inArchive.isEmpty()) idx.file.name else inArchive.substringAfterLast('/')
                toolbar.subtitle = "${idx.format.label} · " + FileJobs.items(idx.entries.count { !it.isDir }).replace("item", "file") + if (idx.encrypted) " · locked" else ""
                addMenu(MENU_SORT, "Sort", R.drawable.ic_sort, iconColor)
                toolbar.menu.add(0, MENU_SELECT_ALL, 11, "Select all")
                toolbar.menu.add(0, MENU_DETAILS, 12, "About this archive")
            } else {
                toolbar.title = folderTitle(dir)
                toolbar.subtitle = when {
                    searching -> "Searching…"
                    searchRows != null -> FileJobs.items(searchRows!!.size) + " found" + if (searchRows!!.size >= MAX_FOUND) " (the first ones)" else ""
                    loading -> null
                    else -> FileJobs.items(rows.size)
                }
                addMenu(MENU_PLACES, "Places", R.drawable.ic_sd_card.takeIf { Volumes.all(this).any { it.removable } } ?: R.drawable.ic_phone, iconColor)
                addMenu(MENU_NEW_FOLDER, "New folder", R.drawable.ic_folder_add, iconColor)
                addMenu(MENU_SORT, "Sort", R.drawable.ic_sort, iconColor)
                toolbar.menu.add(0, MENU_HIDDEN, 10, if (showHidden) "Hide hidden files" else "Show hidden files")
                toolbar.menu.add(0, MENU_SELECT_ALL, 11, "Select all")
                toolbar.menu.add(0, MENU_REFRESH, 12, "Refresh")
                toolbar.menu.add(0, MENU_DETAILS, 13, "About this folder")
            }
        }
        // The bar at the bottom: what to do with the selection, or with the archive.
        when {
            selecting && idx != null -> {
                val one = selected.values.singleOrNull()?.takeIf { !it.isDir }
                setActions(listOfNotNull(
                    barButton(R.drawable.ic_unarchive, "Extract") { extractSelected(named = true) },
                    barButton(R.drawable.ic_folder, "Extract to…") { extractSelectedTo() },
                    one?.let { r -> barButton(R.drawable.ic_open_new, "Open") { openEntry(r.entry!!) } },
                    barButton(R.drawable.ic_info, "Details") { entryDetails(selected.values.toList()) },
                ))
                actionBar.visibility = View.VISIBLE
            }
            selecting -> {
                setActions(listOf(
                    barButton(R.drawable.ic_copy, "Copy") { setClip(selectedFiles(), move = false) },
                    barButton(R.drawable.ic_move, "Move") { setClip(selectedFiles(), move = true) },
                    barButton(R.drawable.ic_share, "Share") { FileOpener.share(this, selectedFiles()) },
                    barButton(R.drawable.ic_delete, "Delete", danger = true) { delete(selectedFiles()) },
                    barButton(R.drawable.ic_more, "More") { moreSheet() },
                ))
                actionBar.visibility = View.VISIBLE
            }
            idx != null -> {
                setActions(listOf(
                    barButton(R.drawable.ic_unarchive, "Extract all") { extract(idx.file, idx, null, "", besideArchive(idx.file), named = true) },
                    barButton(R.drawable.ic_folder, "Extract to…") { clipExtract(idx.file, idx, null, "") },
                ))
                actionBar.visibility = View.VISIBLE
            }
            else -> actionBar.visibility = View.GONE
        }
        val c = clip
        if (c != null && !selecting && idx == null) {
            pasteBar.visibility = View.VISIBLE
            when (c) {
                is Clip.Files -> {
                    val what = if (c.files.size == 1) "“${c.files[0].name}”" else FileJobs.items(c.files.size)
                    pasteText.text = (if (c.move) "Move " else "Copy ") + what + " here?"
                    pasteButton.text = if (c.move) "Move here" else "Paste here"
                }
                is Clip.Extract -> {
                    pasteText.text = "Extract “${c.archive.name}” here?"
                    pasteButton.text = "Extract here"
                }
            }
        } else {
            pasteBar.visibility = View.GONE
        }
        updateBottom()
    }

    /** The area at the bottom only shows when one of its bars does. */
    private fun updateBottom() {
        val any = jobStrip.visibility == View.VISIBLE || pasteBar.visibility == View.VISIBLE || actionBar.visibility == View.VISIBLE
        bottom.visibility = if (any) View.VISIBLE else View.GONE
    }

    /** A message at the bottom, above the bars there. */
    private fun say(text: CharSequence, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        if (isFinishing) return
        bottom.post {
            if (isFinishing) return@post
            val offset = if (bottom.visibility == View.VISIBLE) bottom.height else 0
            Messages.show(this, text, action, long = long, bottomOffset = offset, onAction = onAction)
        }
    }

    private fun addMenu(id: Int, title: String, icon: Int, color: Int) {
        toolbar.menu.add(0, id, id, title).apply {
            setIcon(Ui.icon(this@FilesActivity, icon, color))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun onMenu(id: Int) {
        val idx = index
        when (id) {
            MENU_PLACES -> places()
            MENU_NEW_FOLDER -> newFolder()
            MENU_SORT -> chooseSort()
            MENU_HIDDEN -> {
                sp.edit().putBoolean(PREF_HIDDEN, !showHidden).apply()
                load(keepScroll = true)
            }
            MENU_SELECT_ALL -> {
                for (r in searchRows ?: if (idx != null) archiveRows() else rows) selected[r.key] = r
                adapter.notifyDataSetChanged()
                updateChrome()
            }
            MENU_REFRESH -> load(keepScroll = true)
            MENU_DETAILS -> if (idx != null) archiveDetails(idx) else details(listOf(dir))
        }
    }

    private fun chooseSort() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(FileOps.sortLabels.toTypedArray(), sort) { d, which ->
                sp.edit().putInt(PREF_SORT, which).apply()
                d.dismiss()
                items = FileOps.sorted(items, which)
                rows = items.map { rowOf(it) }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** The phone's storage, the SD card, Download and Swift's downloads, with their free space. */
    private fun places() {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Places"))
        lateinit var sheet: com.google.android.material.bottomsheet.BottomSheetDialog
        fun place(icon: Int, title: String, sub: String?, to: File) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 10), Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 10))
                minimumHeight = Ui.dp(this@FilesActivity, 60)
                background = Ui.ripple(this@FilesActivity, null, 0)
                addView(Ui.iconBadge(this@FilesActivity, icon, 40), LinearLayout.LayoutParams(Ui.dp(this@FilesActivity, 40), Ui.dp(this@FilesActivity, 40)).apply {
                    marginEnd = Ui.dp(this@FilesActivity, 16)
                })
                addView(LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(this@FilesActivity, Ui.Type.BODY, title, lines = 1))
                    if (!sub.isNullOrBlank()) addView(Ui.text(this@FilesActivity, Ui.Type.CAPTION, sub, lines = 1))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    sheet.dismiss()
                    openFolder(to)
                }
            })
        }
        for (v in Volumes.refresh(this)) {
            val (free, total) = v.space()
            val sub = if (free >= 0 && total > 0) "${UrlUtils.formatBytes(free)} free of ${UrlUtils.formatBytes(total)}" else null
            place(if (v.removable) R.drawable.ic_sd_card else R.drawable.ic_phone, v.name, sub, v.root)
        }
        place(R.drawable.ic_download, "Download", "Phone storage › Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        val swift = swiftDownloads
        place(R.drawable.ic_download, "Swift's downloads", Downloads.describe(swift), swift)
        if (Volumes.all(this).any { it.removable }) col.addView(Sheets.note(this, "Swift reads the SD card straight away. The first time you change something there, Android asks you to allow it."))
        sheet = Sheets.sheet(this, col)
        sheet.showImmersive()
    }

    // ------------------------------------------------------------------ archives

    private fun openArchive(f: File, password: String? = null) {
        loadJob?.cancel()
        rememberPosition()
        loading = true
        showLoadingSoon("Reading “${f.name}”…")
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Archives.read(f, password) { !isActive } } }
            loading = false
            result.onSuccess { idx ->
                L.d("Opened an archive (${idx.format.label}, ${idx.entries.size} entries)")
                // Its folder is the one on show (it may have been found by a search below).
                dir = besideArchive(f)
                index = idx
                inArchive = ""
                selected.clear()
                searchRows = null
                if (search.text.isNotEmpty()) search.setText("")
                render()
                list.scrollToPosition(0)
                if (idx.unsafe > 0) say("${idx.unsafe} files in it have names that point outside its folder: Swift leaves them out", long = true)
            }.onFailure { e ->
                render()
                when (e) {
                    is CancellationException -> {}
                    is Archives.NeedPassword -> askPassword(f.name, e.wrong, onCancel = {}) { pw -> openArchive(f, pw) }
                    is FileProblem -> say(e.message ?: "Swift couldn't open this archive", long = true)
                    else -> say("Swift couldn't open this archive")
                }
            }
        }
    }

    private fun archiveRows(): List<Row> {
        val idx = index ?: return emptyList()
        // Sorted as folders are: each entry stands in as a file item for it.
        val of = java.util.IdentityHashMap<FileItem, Archives.Entry>()
        for (e in idx.children(inArchive)) {
            of[FileItem(File(e.path), e.name, e.isDir, e.size, e.time, -1, FileKind.of(e.name, e.isDir))] = e
        }
        return FileOps.sorted(of.keys.toList(), sort).map { rowOf(of[it]!!, idx) }
    }

    private fun showArchiveFolder(path: String) {
        rememberPosition()
        inArchive = path
        selected.clear()
        if (search.text.isNotEmpty()) search.setText("")
        searchRows = null
        render()
        restorePosition()
    }

    private fun leaveArchive() {
        val idx = index ?: return
        index = null
        inArchive = ""
        selected.clear()
        dir = besideArchive(idx.file)
        load()
    }

    /**
     * The folder an archive's files go to by default: the one it is in (an archive opened from
     * inside another one is in Swift's cache: the folder on show then).
     */
    private fun besideArchive(f: File): File = f.parentFile?.takeIf { !it.path.startsWith(cacheDir.path) } ?: dir

    /** One file from the archive: taken out into Swift's cache, then opened. */
    private fun openEntry(e: Archives.Entry) {
        val idx = index ?: return
        // A folder of its own for each file: two files of the same name in different folders of the archive don't mix.
        val folder = File(cacheDir, "docs/archive/" + Docs.idFor(idx.file.path + "|" + idx.file.lastModified() + "|" + e.path).take(16))
        val target = File(folder, Archives.outputName(e.name))
        opening?.dismiss()
        opening = Messages.sticky(this, "Opening “${e.name}”…", "Cancel") { FileJobs.active.firstOrNull { it.kind == FileJobs.Kind.OPEN }?.cancel() }
        FileJobs.start(this, FileJobs.Kind.OPEN, "Opening ${e.name}", onDone = { job -> visible?.get()?.entryOpened(job) }) { w ->
            if (!(target.isFile && (e.size < 0 || target.length() == e.size))) {
                w.replaceAll()
                Archives.extract(w, idx.file, idx, setOf(e.path), e.parent, folder, PlainAccess)
                if (!target.isFile) throw FileProblem("Swift couldn't take “${e.name}” out of the archive.")
            }
            // Dated today: Swift's cache keeps what was opened lately for a week.
            target.setLastModified(System.currentTimeMillis())
            w.result(target)
        }
    }

    private fun entryOpened(job: FileJobs.Job) {
        opening?.dismiss()
        opening = null
        val f = job.result
        when {
            job.message == "Cancelled" -> {}
            job.failed || f == null -> say(job.message ?: "Swift couldn't open this file", long = true)
            Archives.formatOf(f.name) != null -> openArchive(f)
            FileOpener.open(this, f) -> finish()
        }
    }

    private fun extractSelected(named: Boolean) {
        val idx = index ?: return
        val select = selected.values.mapNotNull { it.entry?.path }.toSet()
        clearSelection()
        extract(idx.file, idx, select, inArchive, besideArchive(idx.file), named)
    }

    private fun extractSelectedTo() {
        val idx = index ?: return
        val select = selected.values.mapNotNull { it.entry?.path }.toSet()
        clearSelection()
        clipExtract(idx.file, idx, select, inArchive)
    }

    /** "Extract to…": the archive waits while the user goes to the folder, then taps Extract here. */
    private fun clipExtract(archive: File, idx: Archives.Index?, select: Set<String>?, base: String) {
        clip = Clip.Extract(archive, idx, select, base)
        if (index != null) leaveArchive() else updateChrome()
        say("Go to the folder you want, then tap Extract here")
    }

    /**
     * Extracts into [into]: in a folder named after the archive when [named] (merged with one
     * there already), otherwise straight in.
     */
    private fun extract(archive: File, idx: Archives.Index?, select: Set<String>?, base: String, into: File, named: Boolean) {
        val dest = if (named) {
            File(into, Archives.outputName(Archives.baseName(archive.name))).let { if (it.exists() && !it.isDirectory) FileOps.freeName(it) else it }
        } else {
            into
        }
        withChanges(listOf(dest)) {
            val access = Volumes.accessFor(this, dest) ?: return@withChanges
            val job = FileJobs.start(this, FileJobs.Kind.EXTRACT, "Extracting ${archive.name}", onDone = ::doneHere) { w ->
                Archives.extract(w, archive, idx, select, base, dest, access)
                w.result(dest)
            }
            showProgress(job)
        }
    }

    // ------------------------------------------------------------------ selection

    private fun toggle(r: Row) {
        if (selected.remove(r.key) == null) selected[r.key] = r
        val pos = adapter.rows.indexOfFirst { it.key == r.key }
        if (pos >= 0) adapter.notifyItemChanged(pos)
        if (selected.size <= 1) adapter.notifyDataSetChanged()
        updateChrome()
    }

    private fun clearSelection() {
        selected.clear()
        adapter.notifyDataSetChanged()
        updateChrome()
    }

    private fun selectedFiles(): List<File> = selected.values.mapNotNull { it.file }

    private fun setClip(files: List<File>, move: Boolean) {
        if (files.isEmpty()) return
        clip = Clip.Files(files, move)
        clearSelection()
        say("Go to the folder you want, then tap " + if (move) "Move here" else "Paste here")
    }

    private fun moreSheet() {
        val rowsNow = selected.values.toList()
        val files = selectedFiles()
        if (files.isEmpty()) return
        val actions = ArrayList<Sheets.Action>()
        val one = files.singleOrNull()
        if (one != null) actions += Sheets.Action(R.drawable.ic_edit, "Rename") { rename(one) }
        actions += Sheets.Action(R.drawable.ic_archive, "Compress") { compress(files) }
        if (files.all { it.isFile && Archives.formatOf(it.name) != null }) {
            actions += Sheets.Action(R.drawable.ic_unarchive, if (files.size == 1) "Extract to folder “${Archives.baseName(files[0].name)}”" else "Extract each to its own folder") {
                clearSelection()
                files.forEach { extract(it, null, null, "", it.parentFile ?: dir, named = true) }
            }
        }
        if (one != null && one.isFile) actions += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { FileOpener.openWith(this, one) }
        actions += Sheets.Action(R.drawable.ic_info, "Details") { details(files) }
        actions += Sheets.Action(R.drawable.ic_select_all, "Select all") { onMenu(MENU_SELECT_ALL) }
        Sheets.actions(this, if (rowsNow.size == 1) rowsNow[0].name else FileJobs.items(rowsNow.size), null, actions, iconRes = R.drawable.ic_more)
    }

    // ------------------------------------------------------------------ opening

    private fun open(r: Row) {
        val f = r.file
        val e = r.entry
        when {
            e != null && e.isDir -> {
                if (search.text.isNotEmpty()) search.setText("")
                showArchiveFolder(e.path)
            }
            e != null -> openEntry(e)
            f == null -> {}
            r.isDir -> openFolder(f)
            Archives.formatOf(r.name) != null -> openArchive(f)
            FileOpener.open(this, f) -> finish()
        }
    }

    /** Everything that can be done with one row (its ⋮ button). */
    private fun itemSheet(r: Row) {
        val f = r.file
        if (f == null) {
            entrySheet(r)
            return
        }
        val actions = ArrayList<Sheets.Action>()
        val archive = !r.isDir && Archives.formatOf(r.name) != null
        when {
            r.isDir -> {
                actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { openFolder(f) }
                actions += Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { setClip(listOf(f), move = false) }
                actions += Sheets.Action(R.drawable.ic_move, "Move", primary = true) { setClip(listOf(f), move = true) }
                actions += Sheets.Action(R.drawable.ic_archive, "Compress", primary = true) { compress(listOf(f)) }
            }
            archive -> {
                actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { openArchive(f) }
                actions += Sheets.Action(R.drawable.ic_unarchive, "Extract", primary = true) { extract(f, null, null, "", f.parentFile ?: dir, named = true) }
                actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { FileOpener.share(this, listOf(f)) }
                actions += Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { setClip(listOf(f), move = false) }
                actions += Sheets.Action(R.drawable.ic_unarchive, "Extract here") { extract(f, null, null, "", f.parentFile ?: dir, named = false) }
                actions += Sheets.Action(R.drawable.ic_folder, "Extract to another folder…") { clipExtract(f, null, null, "") }
                actions += Sheets.Action(R.drawable.ic_move, "Move") { setClip(listOf(f), move = true) }
            }
            else -> {
                val apk = r.kind == FileKind.APK
                actions += Sheets.Action(if (apk) R.drawable.ic_apk else R.drawable.ic_open_new, if (apk) "Install" else "Open", primary = true) { open(r) }
                actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { FileOpener.share(this, listOf(f)) }
                actions += Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { setClip(listOf(f), move = false) }
                actions += Sheets.Action(R.drawable.ic_move, "Move", primary = true) { setClip(listOf(f), move = true) }
                actions += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { FileOpener.openWith(this, f) }
                actions += Sheets.Action(R.drawable.ic_archive, "Compress") { compress(listOf(f)) }
            }
        }
        actions += Sheets.Action(R.drawable.ic_edit, "Rename") { rename(f) }
        actions += Sheets.Action(R.drawable.ic_info, "Details") { details(listOf(f)) }
        actions += Sheets.Action(R.drawable.ic_delete, "Delete", danger = true) { delete(listOf(f)) }
        Sheets.actions(this, r.name, subtitleOf(r), actions, iconRes = r.kind.icon)
    }

    private fun entrySheet(r: Row) {
        val e = r.entry ?: return
        val idx = index ?: return
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { open(r) }
        actions += Sheets.Action(R.drawable.ic_unarchive, "Extract", primary = true) { extract(idx.file, idx, setOf(e.path), inArchive, besideArchive(idx.file), named = true) }
        actions += Sheets.Action(R.drawable.ic_folder, "Extract to…", primary = true) { clipExtract(idx.file, idx, setOf(e.path), inArchive) }
        actions += Sheets.Action(R.drawable.ic_info, "Details", primary = true) { entryDetails(listOf(r)) }
        Sheets.actions(this, r.name, subtitleOf(r), actions, iconRes = r.kind.icon)
    }

    // ------------------------------------------------------------------ changing files

    private fun paste() {
        when (val c = clip) {
            is Clip.Files -> {
                val dest = dir
                if (c.files.any { it.isDirectory && FileOps.isInside(dest, it) }) {
                    say("A folder can't go inside itself")
                    return
                }
                if (c.move && c.files.all { it.parentFile?.path == dest.path }) {
                    say("They're in this folder already")
                    return
                }
                withChanges(if (c.move) c.files + dest else listOf(dest)) {
                    clip = null
                    updateChrome()
                    val what = if (c.files.size == 1) c.files[0].name else FileJobs.items(c.files.size)
                    val kind = if (c.move) FileJobs.Kind.MOVE else FileJobs.Kind.COPY
                    val job = FileJobs.start(this, kind, "${kind.doing} $what", onDone = ::doneHere) { w -> Transfers.copy(w, c.files, dest, c.move) }
                    showProgress(job)
                }
            }
            is Clip.Extract -> {
                clip = null
                updateChrome()
                extract(c.archive, c.index, c.select, c.base, dir, named = c.select == null)
            }
            null -> {}
        }
    }

    private fun delete(files: List<File>) {
        if (files.isEmpty()) return
        val title = if (files.size == 1) "Delete “${files[0].name}”?" else "Delete ${files.size} items?"
        val folders = files.count { it.isDirectory }
        val message = when {
            files.size == 1 && folders == 1 -> "The folder goes with everything in it. Swift can't bring it back."
            files.size == 1 -> "Swift can't bring it back."
            folders > 0 -> "Folders go with everything in them. Swift can't bring them back."
            else -> "Swift can't bring them back."
        }
        Dialogs.confirm(this, title, message, "Delete", danger = true) {
            withChanges(files) {
                clearSelection()
                val what = if (files.size == 1) files[0].name else FileJobs.items(files.size)
                val job = FileJobs.start(this, FileJobs.Kind.DELETE, "Deleting $what", onDone = ::doneHere) { w -> Transfers.delete(w, files) }
                showProgress(job)
            }
        }
    }

    private fun rename(f: File) {
        Sheets.askText(this, "Rename", "Name", f.name, words = false) { input ->
            var name = FileOps.cleanName(input)
            if (name.isEmpty() || name == f.name) return@askText
            // The ending stays when it was left out ("photo" for "photo.jpg").
            val ext = FileKind.extension(f.name)
            if (f.isFile && ext.isNotEmpty() && !name.contains('.')) name = "$name.$ext"
            val to = File(f.parentFile, name)
            if (to.exists() && !to.path.equals(f.path, ignoreCase = true)) {
                say("There's already something called “$name” here")
                return@askText
            }
            withChanges(listOf(f)) {
                clearSelection()
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        val access = Volumes.accessFor(this@FilesActivity, f) ?: return@withContext false
                        access.rename(f, name).also { done ->
                            if (done) {
                                FileOps.forget(this@FilesActivity, f)
                                FileOps.scan(this@FilesActivity, listOf(to.path))
                            }
                        }
                    }
                    if (ok) {
                        say("Renamed to “$name”")
                        load(keepScroll = true)
                    } else {
                        say("Swift couldn't rename “${f.name}”")
                    }
                }
            }
        }
    }

    private fun newFolder() {
        val parent = dir
        Sheets.askText(this, "New folder", "Folder name", "New folder", words = false) { input ->
            val name = FileOps.cleanName(input)
            if (name.isEmpty()) return@askText
            val f = File(parent, name)
            if (f.exists()) {
                say("There's already something called “$name” here")
                return@askText
            }
            withChanges(listOf(f)) {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { Volumes.accessFor(this@FilesActivity, f)?.mkdir(f) == true }
                    if (ok) {
                        load(keepScroll = true)
                        say("Made the folder “$name”")
                    } else {
                        say("Swift couldn't make a folder here")
                    }
                }
            }
        }
    }

    /** Compress: a name, ZIP or 7z, and a password for a ZIP if wanted. */
    private fun compress(files: List<File>) {
        if (files.isEmpty()) return
        val parent = files[0].parentFile ?: dir
        val base = if (files.size == 1) {
            files[0].let { if (it.isDirectory) it.name else it.name.substringBeforeLast('.', it.name) }
        } else {
            folderTitle(parent).takeIf { it != "Phone storage" && !it.startsWith("SD card") } ?: "Archive"
        }
        var output = Archives.Output.ZIP
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 20), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 20), 0)
        }
        val nameInput = EditText(this).apply {
            setText(base)
            hint = "Name"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            selectAll()
        }
        box.addView(nameInput)
        val password = EditText(this).apply {
            hint = "Password (if you want one)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val note = Ui.text(this, Ui.Type.CAPTION, "With a password the ZIP is locked with AES-256. 7-Zip, WinRAR and phones open it; Windows' own unzip can't.").apply {
            setPadding(0, Ui.dp(this@FilesActivity, 4), 0, 0)
        }
        val chips = Sheets.chips(this, Archives.Output.entries.map { it.label }, 0) { i ->
            output = Archives.Output.entries[i]
            val zip = output == Archives.Output.ZIP
            password.visibility = if (zip) View.VISIBLE else View.GONE
            note.text = if (zip) "With a password the ZIP is locked with AES-256. 7-Zip, WinRAR and phones open it; Windows' own unzip can't."
            else "7z makes smaller archives, but takes longer. Swift makes them without a password."
        }.apply { setPadding(0, Ui.dp(this@FilesActivity, 8), 0, 0) }
        box.addView(chips)
        box.addView(password)
        box.addView(note)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (files.size == 1) "Compress “${files[0].name}”" else "Compress ${files.size} items")
            .setView(box)
            .setPositiveButton("Compress") { _, _ ->
                val name = FileOps.cleanName(nameInput.text.toString()).ifEmpty { "Archive" }
                val full = if (name.endsWith(".${output.ext}", ignoreCase = true)) name else "$name.${output.ext}"
                val pw = password.text.toString().takeIf { output == Archives.Output.ZIP && it.isNotEmpty() }
                val dest = File(parent, full)
                withChanges(listOf(dest)) {
                    clearSelection()
                    val access = Volumes.accessFor(this, dest) ?: return@withChanges
                    val chosen = output
                    val job = FileJobs.start(this, FileJobs.Kind.COMPRESS, "Compressing into $full", onDone = ::doneHere) { w ->
                        val target = w.resolve(dest, false) ?: return@start
                        Archives.compress(w, files, target, chosen, pw, access)
                    }
                    showProgress(job)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        Palette.paint(box)
        nameInput.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ------------------------------------------------------------------ details

    private fun detailRow(col: LinearLayout, label: String, value: String): TextView {
        col.addView(Ui.text(this, Ui.Type.CAPTION, label).apply { setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 24), 0) })
        val v = Ui.text(this, Ui.Type.BODY, value).apply {
            setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 2), Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 4))
            setTextIsSelectable(true)
        }
        col.addView(v)
        return v
    }

    private fun longDate(t: Long) = DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)

    /** Name, place, size (a folder's summed meanwhile), date and what it holds. */
    private fun details(files: List<File>) {
        if (files.isEmpty()) return
        val col = Sheets.column(this)
        val one = files.singleOrNull()
        col.addView(Sheets.header(this, one?.let { folderTitle(it) } ?: FileJobs.items(files.size), one?.let { FileKind.of(it.name, it.isDirectory).label }))
        val v = Volumes.of(this, files[0])
        // "Phone storage › Download"; nothing for a storage's top folder itself.
        val place = files[0].parentFile?.takeIf { p -> v == null || v.contains(p) }?.let { p ->
            v?.let { it.name + FileOps.relative(it.root, p).let { r -> if (r.isEmpty()) "" else " › $r" } } ?: p.path
        }
        if (place != null) detailRow(col, "Where", place)
        if (one != null) detailRow(col, "Full path", one.path)
        val sizeView = detailRow(col, "Size", if (one != null && one.isFile) bytesText(one.length()) else "Counting…")
        val holds = if (files.any { it.isDirectory }) detailRow(col, "Holds", "Counting…") else null
        if (one != null) detailRow(col, "Changed", longDate(one.lastModified()))
        if (v != null) {
            val (free, total) = v.space()
            if (free >= 0) detailRow(col, "Free on ${v.name}", "${UrlUtils.formatBytes(free)} of ${UrlUtils.formatBytes(total)}")
        }
        val sheet = Sheets.sheet(this, col)
        if (files.any { it.isDirectory } || files.size > 1) {
            val job = lifecycleScope.launch {
                val t = withContext(Dispatchers.IO) {
                    FileOps.measure(files, { !isActive }) { now -> sizeView.post { sizeView.text = "Counting… " + UrlUtils.formatBytes(now.bytes) } }
                }
                sizeView.text = bytesText(t.bytes)
                holds?.text = listOf(
                    if (t.files == 1) "1 file" else "${t.files} files",
                    if (t.folders - files.count { it.isDirectory } == 1) "1 folder" else "${(t.folders - files.count { it.isDirectory }).coerceAtLeast(0)} folders",
                ).joinToString(", ")
            }
            sheet.setOnDismissListener { job.cancel() }
        }
        sheet.showImmersive()
    }

    private fun bytesText(n: Long) = if (n < 1024) UrlUtils.formatBytes(n) else "${UrlUtils.formatBytes(n)} (${String.format(java.util.Locale.getDefault(), "%,d", n)} bytes)"

    private fun archiveDetails(idx: Archives.Index) {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, idx.file.name, "${idx.format.label} archive"))
        detailRow(col, "Holds", "${idx.entries.count { !it.isDir }} files, ${idx.entries.count { it.isDir }} folders")
        detailRow(col, "Unpacked", UrlUtils.formatBytes(idx.totalSize))
        detailRow(col, "Packed", UrlUtils.formatBytes(idx.file.length()))
        if (idx.encrypted || idx.password != null) detailRow(col, "Password", "Locked with a password")
        detailRow(col, "Full path", idx.file.path)
        Sheets.sheet(this, col).showImmersive()
    }

    private fun entryDetails(rowsNow: List<Row>) {
        val col = Sheets.column(this)
        val one = rowsNow.singleOrNull()?.entry
        col.addView(Sheets.header(this, one?.name ?: FileJobs.items(rowsNow.size), one?.let { FileKind.of(it.name, it.isDir).label }))
        if (one != null) {
            detailRow(col, "In the archive", index?.file?.name + " › " + one.path.replace("/", " › "))
            if (!one.isDir) detailRow(col, "Size", if (one.size >= 0) bytesText(one.size) else "Not known until it is taken out")
            if (one.time > 0) detailRow(col, "Changed", longDate(one.time))
            if (one.encrypted) detailRow(col, "Password", "Locked with a password")
        }
        if (one == null || one.isDir) {
            val idx = index
            val files = rowsNow.mapNotNull { it.entry }.flatMap { e -> if (e.isDir) idx?.filesUnder(e.path).orEmpty() else listOf(e) }
            detailRow(col, "Holds", if (files.size == 1) "1 file" else "${files.size} files")
            detailRow(col, "Unpacked", UrlUtils.formatBytes(files.sumOf { it.size.coerceAtLeast(0) }))
        }
        Sheets.sheet(this, col).showImmersive()
    }

    // ------------------------------------------------------------------ search

    private val searchSoon = Runnable { runSearch() }

    private fun scheduleSearch() {
        list.removeCallbacks(searchSoon)
        if (search.text.isNullOrBlank()) {
            searchJob?.cancel()
            searching = false
            if (searchRows != null) {
                searchRows = null
                render()
            }
            return
        }
        list.postDelayed(searchSoon, 300)
    }

    /** In this folder and below (or in the archive's folder and below), nearest first. */
    private fun runSearch() {
        val q = search.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        searchJob?.cancel()
        selected.clear()
        val idx = index
        if (idx != null) {
            searchRows = idx.search(inArchive, q, MAX_FOUND).map { e ->
                rowOf(e, idx, e.parent.removePrefix(inArchive).trim('/').replace("/", " › "))
            }
            render()
            return
        }
        val from = dir
        val hidden = showHidden
        searching = true
        updateChrome()
        searchJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { FileOps.search(from, q, hidden, MAX_FOUND) { !isActive } }
            searching = false
            if (from != dir || index != null) return@launch
            searchRows = found.map { rowOf(it) }
            render()
            list.scrollToPosition(0)
        }
    }

    // ------------------------------------------------------------------ jobs

    /** A job started here is over: the folder shows what changed, and the outcome is said. */
    private fun doneHere(job: FileJobs.Job) {
        val screen = visible?.get() ?: return
        screen.jobDone(job)
    }

    private fun jobDone(job: FileJobs.Job) {
        if (progressFor === job) {
            progressDialog?.dismiss()
            progressDialog = null
            progressFor = null
        }
        if (isFinishing) return
        if (index == null && (dir.path in job.touched || job.touched.isEmpty())) load(keepScroll = true)
        val text = job.message ?: return
        val result = job.result
        if (!job.failed && job.kind == FileJobs.Kind.EXTRACT && result != null && result.isDirectory && result.path != dir.path) {
            say(text, "Show") { openFolder(result) }
        } else {
            say(text, long = job.failed)
        }
    }

    private fun onJobsChanged() {
        val jobs = FileJobs.active.filter { it.kind != FileJobs.Kind.OPEN }
        val job = jobs.firstOrNull { it.running } ?: jobs.firstOrNull()
        if (job == null) {
            jobStrip.visibility = View.GONE
            updateBottom()
        } else {
            jobStrip.visibility = View.VISIBLE
            val more = if (jobs.size > 1) "  (+${jobs.size - 1})" else ""
            jobText.text = job.title + more + "  ·  " + FileJobs.detail(job).ifBlank { "Waiting…" }
            setBar(jobBar, job)
            updateBottom()
        }
        progressFor?.let { j -> if (j.finished) {
            progressDialog?.dismiss()
            progressDialog = null
            progressFor = null
        } else updateProgress(j) }
        val asking = FileJobs.asking
        val q = asking?.question
        if (q != null && q !== questionShown && !isFinishing) showQuestion(q)
    }

    private fun setBar(bar: ProgressBar, job: FileJobs.Job) {
        val indeterminate = job.fraction < 0 || job.counting
        if (bar.isIndeterminate != indeterminate) bar.isIndeterminate = indeterminate
        if (!indeterminate) bar.progress = (job.fraction * 1000).toInt()
    }

    /** The job's progress in a window: the file, how far, Hide (it goes on) and Cancel. */
    private fun showProgress(job: FileJobs.Job) {
        if (isFinishing || job.finished) return
        progressDialog?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 24), 0)
        }
        val name = Ui.text(this, Ui.Type.BODY, job.current).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val bar = horizontalBar()
        val detail = Ui.text(this, Ui.Type.CAPTION, FileJobs.detail(job))
        box.addView(name)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)).apply {
            topMargin = Ui.dp(this@FilesActivity, 12)
            bottomMargin = Ui.dp(this@FilesActivity, 8)
        })
        box.addView(detail)
        progressName = name
        progressBar = bar
        progressDetail = detail
        progressFor = job
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(job.title)
            .setView(box)
            .setPositiveButton("Hide", null)
            .setNegativeButton("Cancel") { _, _ -> job.cancel() }
            .showImmersive()
            .also { d ->
                d.setOnDismissListener {
                    if (progressDialog === d) {
                        progressDialog = null
                        progressFor = null
                    }
                }
            }
        updateProgress(job)
    }

    private fun updateProgress(job: FileJobs.Job) {
        progressName?.text = job.current.ifBlank { if (job.counting) "Getting ready…" else "" }
        progressDetail?.text = FileJobs.detail(job)
        progressBar?.let { setBar(it, job) }
    }

    /** A job asks: a name already taken, or an archive's password. */
    private fun showQuestion(q: FileJobs.Question) {
        questionShown = q
        if (q.password) {
            askPassword(q.name, q.wrong, onCancel = { q.reply(null) }) { pw -> q.reply(pw) }
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 4), Ui.dp(this@FilesActivity, 24), 0)
        }
        box.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, "There's already ${if (q.isFolder) "a folder" else "a file"} called “${q.name}” in “${q.folder}”."))
        val all = CheckBox(this).apply {
            text = "Do the same for the rest"
            setPadding(Ui.dp(this@FilesActivity, 4), 0, 0, 0)
        }
        box.addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@FilesActivity, 8) })
        Palette.paint(box)
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("“${q.name}” is already there")
            .setView(box)
            .setPositiveButton("Replace") { _, _ -> q.reply(FileJobs.Answer(FileJobs.Choice.REPLACE, all.isChecked)) }
            .setNegativeButton("Keep both") { _, _ -> q.reply(FileJobs.Answer(FileJobs.Choice.KEEP_BOTH, all.isChecked)) }
            .setNeutralButton("Skip") { _, _ -> q.reply(FileJobs.Answer(FileJobs.Choice.SKIP, all.isChecked)) }
            .setCancelable(false)
            .showImmersive()
        Dialogs.markDanger(d)
    }

    /** Asks for an archive's password ([wrong]: the last one didn't work). */
    private fun askPassword(archive: String, wrong: Boolean, onCancel: () -> Unit, onOk: (String) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 20), Ui.dp(this@FilesActivity, 4), Ui.dp(this@FilesActivity, 20), 0)
        }
        val input = EditText(this).apply {
            hint = "Password"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, if (wrong) "That password didn't work. Check it and try again." else "“$archive” is locked with a password.").apply {
            setPadding(Ui.dp(this@FilesActivity, 4), 0, Ui.dp(this@FilesActivity, 4), Ui.dp(this@FilesActivity, 4))
        })
        box.addView(input)
        box.addView(CheckBox(this).apply {
            text = "Show the password"
            setOnCheckedChangeListener { _, on ->
                input.inputType = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.setSelection(input.text.length)
            }
        })
        Palette.paint(box)
        var answered = false
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(if (wrong) "Wrong password" else "Password needed")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                answered = true
                val pw = input.text.toString()
                if (pw.isEmpty()) onCancel() else onOk(pw)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                answered = true
                onCancel()
            }
            .showImmersive()
        d.setOnDismissListener { if (!answered) onCancel() }
        input.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ------------------------------------------------------------------ the list

    private fun subtitleOf(r: Row): String {
        val parts = ArrayList<String>(4)
        if (r.entry?.encrypted == true) parts += "Locked"
        if (r.isDir) {
            if (r.count >= 0) parts += FileJobs.items(r.count)
        } else if (r.size >= 0) {
            parts += UrlUtils.formatBytes(r.size)
        }
        dateText(r.time)?.let { parts += it }
        if (!r.where.isNullOrEmpty()) parts += "in " + r.where
        return parts.joinToString(" · ")
    }

    private val calendar: Calendar = Calendar.getInstance()
    private val thisYear = calendar.get(Calendar.YEAR)

    private fun dateText(t: Long): String? {
        if (t <= 0) return null
        if (DateUtils.isToday(t)) return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME)
        calendar.timeInMillis = t
        val year = if (calendar.get(Calendar.YEAR) == thisYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or year)
    }

    private inner class Holder(
        val root: LinearLayout, val icon: ImageView, val check: ImageView, val title: TextView, val subtitle: TextView, val more: ImageButton,
    ) : RecyclerView.ViewHolder(root) {
        var thumb: FileThumbs.Request? = null
        var marked = false
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var rows: List<Row> = emptyList()
            private set

        fun set(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@FilesActivity
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
                minimumHeight = Ui.dp(ctx, 64)
                setBackgroundResource(RowHolder.selectableBackground(ctx))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val frame = FrameLayout(ctx)
            val icon = ImageView(ctx).apply {
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(icon, FrameLayout.LayoutParams(thumbPx, thumbPx))
            val check = ImageView(ctx).apply {
                setImageDrawable(Ui.icon(ctx, R.drawable.ic_check_circle, Ui.accent(ctx)))
                background = Ui.circle(Palette.color(ctx, R.color.page_bg))
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(check, FrameLayout.LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, -Ui.dp(ctx, 4), -Ui.dp(ctx, 4))
            })
            frame.clipChildren = false
            root.clipChildren = false
            root.addView(frame, LinearLayout.LayoutParams(thumbPx, thumbPx))
            // The middle of a long name goes, so its ending (.jpg, .zip) stays in sight.
            val title = Ui.text(ctx, Ui.Type.BODY, "").apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val subtitle = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1).apply { ellipsize = TextUtils.TruncateAt.END }
            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 4), 0)
                addView(title)
                addView(subtitle)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = ImageButton(ctx).apply {
                setImageDrawable(Ui.icon(ctx, R.drawable.ic_more, Ui.textSecondary(ctx)))
                contentDescription = "More"
                setBackgroundResource(RowHolder.selectableBackground(ctx))
            }
            root.addView(more, LinearLayout.LayoutParams(Ui.dp(ctx, 44), Ui.dp(ctx, 44)))
            return Holder(root, icon, check, title, subtitle, more)
        }

        override fun onBindViewHolder(h: Holder, position: Int) = bind(h, rows[position])

        override fun onViewRecycled(h: Holder) {
            h.thumb?.cancel()
            h.thumb = null
        }
    }

    private fun bind(h: Holder, r: Row) {
        val ctx = this
        h.title.text = r.name
        h.subtitle.text = subtitleOf(r)
        h.thumb?.cancel()
        h.thumb = null
        h.icon.tag = r.key
        val doc = if (r.kind == FileKind.DOCUMENT) Docs.kindOf(r.name, null) else null
        when {
            r.isDir -> plainIcon(h.icon, R.drawable.ic_folder, Ui.onAccentContainer(ctx), Ui.accentContainer(ctx))
            doc != null -> {
                h.icon.background = Ui.shape(ctx, Palette.color(ctx, R.color.surface_alt), 10)
                h.icon.scaleType = ImageView.ScaleType.CENTER
                h.icon.setPadding(0, 0, 0, 0)
                h.icon.imageTintList = null
                h.icon.setImageDrawable(DocIcon(doc, Ui.dp(ctx, 30)))
            }
            else -> plainIcon(h.icon, r.kind.icon, Ui.textSecondary(ctx), Palette.color(ctx, R.color.surface_alt))
        }
        val f = r.file
        if (f != null && !r.isDir) {
            val key = r.key
            if (FileThumbs.has(r.kind)) {
                h.thumb = FileThumbs.load(ctx, f, r.time, r.kind, thumbPx) { bmp ->
                    if (h.icon.tag != key) return@load
                    h.icon.imageTintList = null
                    h.icon.setPadding(0, 0, 0, 0)
                    h.icon.scaleType = ImageView.ScaleType.CENTER_CROP
                    h.icon.setImageBitmap(bmp)
                }
            }
        }
        val isSelected = r.key in selected
        if (isSelected != h.marked) {
            h.marked = isSelected
            if (isSelected) h.root.background = Ui.ripple(ctx, ColorDrawable(Ui.accentContainer(ctx)), 0)
            else h.root.setBackgroundResource(RowHolder.selectableBackground(ctx))
        }
        h.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        h.more.visibility = if (selected.isEmpty()) View.VISIBLE else View.INVISIBLE
        h.root.contentDescription = r.name + ", " + (if (r.isDir) "folder" else r.kind.label) + if (isSelected) ", selected" else ""
        h.root.setOnClickListener { if (selected.isNotEmpty()) toggle(r) else open(r) }
        h.root.setOnLongClickListener {
            Haptics.hold(it)
            toggle(r)
            true
        }
        h.more.setOnClickListener { itemSheet(r) }
    }

    private fun plainIcon(v: ImageView, res: Int, tint: Int, fill: Int) {
        v.background = Ui.shape(this, fill, 10)
        v.scaleType = ImageView.ScaleType.CENTER
        v.setPadding(0, 0, 0, 0)
        v.imageTintList = null
        v.setImageDrawable(Ui.icon(this, res, tint))
    }

    companion object {
        /** The folder to open (a path). */
        const val EXTRA_FOLDER = "folder"

        private const val PREF_SORT = "files_sort"
        private const val PREF_HIDDEN = "files_hidden"
        private const val PREF_LAST = "files_last_folder"
        private const val MAX_FOUND = 300

        private const val MENU_PLACES = 1
        private const val MENU_NEW_FOLDER = 2
        private const val MENU_SORT = 3
        private const val MENU_SELECT_ALL = 4
        private const val MENU_HIDDEN = 20
        private const val MENU_REFRESH = 21
        private const val MENU_DETAILS = 22

        /** Files to copy or move (or an archive to extract) once the user is in the right folder; kept while Swift runs. */
        private var clip: Clip? = null

        /** The Files screen on show, which jobs report to when they end. */
        private var visible: WeakReference<FilesActivity>? = null

        /** The Files screen at [folder] (where it was left when null). */
        fun intent(ctx: Context, folder: File? = null): Intent =
            Intent(ctx, FilesActivity::class.java).apply { folder?.let { putExtra(EXTRA_FOLDER, it.path) } }

        /** Opens the Files screen at [folder], or where it was left. */
        fun open(ctx: Context, folder: File? = null) {
            val i = intent(ctx, folder)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
