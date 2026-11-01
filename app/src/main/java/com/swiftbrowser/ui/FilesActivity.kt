package com.swiftbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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
import androidx.recyclerview.widget.GridLayoutManager
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
import com.swiftbrowser.files.Favourites
import com.swiftbrowser.files.FileIndex
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Locale

/**
 * Files (2.7): a file manager in the browser, as in ZArchiver. The phone's storage, the SD card,
 * Download and Swift's downloads; folders and files with small pictures, sorted as chosen, hidden
 * files on request, and search in a folder and below. Hold a row to select; Copy, Move (and Paste
 * in another folder), Rename, Delete, Share, Compress (ZIP, with a password if wanted, or 7z) and
 * Details. Archives (ZIP, 7z, RAR, tar and the rest) open like folders: extract all of one or some
 * of it, or open a single file. Long jobs run in the background (FileJobs) with their progress here
 * and in the notification shade. Works as the first screen of its own (Swift's browser needn't be open).
 *
 * 2.8: a start page, as in Files by Google: the phone's storage and SD card with how full they are,
 * Images, Videos, Music, Documents, Archives, APKs and Downloads from all over the phone (FileIndex),
 * favourite folders and recent files. Folders and categories show as a list or as a grid of tiles
 * with their pictures; selected rows and tiles show a tick; the top keeps two buttons (the rest is
 * under ⋮), so the folder's name fits. Back goes one step at a time: a search, the selection, an
 * archive, up a folder to the top of the storage, the start page, then out of Files.
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

    /** A section's title on the start page, with a link on the right ("Show all"). */
    private class Header(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

    /** A storage on the start page and how full it is (-1: not measured yet). */
    private class StorageCard(val volume: Volumes.Volume, val free: Long, val total: Long)

    /** The start page's categories, with their numbers once counted. */
    private class CategoryGrid(val counts: Map<FileIndex.Category, FileIndex.Summary>?)

    /** A line of help on the start page. */
    private class Hint(val text: String)

    /** What the start page shows ([counts] and [recent] come a moment after the rest). */
    private class HomeData(
        val storages: List<StorageCard>,
        val favourites: List<Row>,
        val counts: Map<FileIndex.Category, FileIndex.Summary>?,
        val recent: List<Row>?,
    )

    private val sp get() = SwiftApp.prefs.sp
    private val sort get() = sp.getInt(PREF_SORT, FileOps.SORT_NAME)
    private val categorySort get() = sp.getInt(PREF_CATEGORY_SORT, FileOps.SORT_DATE)
    private val showHidden get() = sp.getBoolean(PREF_HIDDEN, false)

    /** Tiles instead of rows (2.8), remembered. */
    private var grid = false

    /** The start page is on show (an archive opened from it shows over it). */
    private var home = false

    /** A category's files are on show (Images, Downloads…), or an archive opened from them. */
    private var category: FileIndex.Category? = null
    private var dir: File = Volumes.internalRoot()
    private var index: Archives.Index? = null
    private var inArchive = ""
    private var listed: List<FileItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private var rowsBytes = 0L
    private var searchRows: List<Row>? = null
    private var unreadable = false
    private var loading = false
    private var searching = false

    /** Changes with every move to another place: what a slow read brings back for an old place is dropped. */
    private var placeGen = 0

    /** The screen opened without access to the files: they load once it is given. */
    private var needAccess = false
    private val selected = LinkedHashMap<String, Row>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var homeJob: Job? = null

    /** When the start page's numbers were last read; [homeDirty]: something may have changed since. */
    private var homeLoadedAt = 0L
    private var homeDirty = false

    /** A file to bring into sight once its folder is read ("Show in folder"). */
    private var focusPath: String? = null
    private val adapter = Adapter()
    private var thumbPx = 0
    private var gridCols = 3
    private var tilePx = 0
    private var tileIconPad = 0

    /** Whether the list on show is drawn as tiles, and how many file rows it has (for "Select all"). */
    private var tiles = false
    private var shownRowCount = 0
    private var menuShown = ""
    private var barShown = ""
    private var navClose: Boolean? = null

    private val secondaryTint by lazy { ColorStateList.valueOf(Ui.textSecondary(this)) }
    private val accentTint by lazy { ColorStateList.valueOf(Ui.accent(this)) }
    private val dangerTint by lazy { ColorStateList.valueOf(Ui.danger(this)) }
    private val onContainerTint by lazy { ColorStateList.valueOf(Ui.onAccentContainer(this)) }

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

    private fun dp(v: Int) = Ui.dp(this, v)

    // ------------------------------------------------------------------ permissions

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) accessGiven() else render()
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
        thumbPx = dp(44)
        // 2.8: tiles about 112 dp wide: three across a phone held upright, more on a wide screen.
        val widthDp = minOf(resources.configuration.screenWidthDp, Ui.MAX_CONTENT_WIDTH)
        gridCols = (widthDp / 112).coerceIn(3, 6)
        tilePx = dp((widthDp - 16) / gridCols - 16)
        tileIconPad = (tilePx * 0.3f).toInt()
        grid = sp.getBoolean(PREF_GRID, false)
        toolbar.title = "Files"
        // 2.8: the ← at the top goes back a step, like Android's Back (it closed Files from any folder).
        toolbar.setNavigationOnClickListener { if (selected.isNotEmpty()) clearSelection() else onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener {
            onMenu(it.itemId)
            true
        }
        search.visibility = View.VISIBLE
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = scheduleSearch()
        })
        buildChrome()
        // One list for rows, tiles and the start page: rows and the start page's parts take the whole width.
        val lm = GridLayoutManager(this, 1)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (tiles && adapter.items.getOrNull(position) is Row) 1 else lm.spanCount
        }
        list.layoutManager = lm
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        // A screen of tiles and a few more, so going back and forth between list and grid makes few new ones.
        list.recycledViewPool.setMaxRecycledViews(TYPE_TILE, gridCols * 6)
        onBackPressedDispatcher.addCallback(this, back)
        Volumes.refresh(this)
        needAccess = !hasAccess()
        // Turned sideways: the same place. Otherwise the folder asked for, or where Files was left.
        val place = savedInstanceState?.getString(STATE_PLACE) ?: intent.getStringExtra(EXTRA_FOLDER) ?: sp.getString(PREF_LAST, null)
        // Without access Android hides the folders (they can't even be checked): there once it is given.
        if (needAccess) wanted = place
        goTo(place)
        if (needAccess && Build.VERSION.SDK_INT < 30) requestAccess()
    }

    /** Where to go once access to the files is given. */
    private var wanted: String? = null

    /** Access was given: the place asked for, or the one on show, now with its files. */
    private fun accessGiven() {
        needAccess = false
        Volumes.refresh(this)
        val place = wanted ?: placeKey()
        wanted = null
        goTo(place)
    }

    /**
     * Shows a remembered place: a folder's path (a file's own folder for a file's), a category
     * ("cat:images") or the start page (also when the folder is gone).
     */
    private fun goTo(place: String?) {
        val cat = place?.takeIf { it.startsWith(CATEGORY) }?.let { FileIndex.Category.of(it.removePrefix(CATEGORY)) }
        // "/sdcard/Download" is a link to the phone's storage: its real path, so Back can go up it a folder at a time.
        val path = place?.takeIf { it.startsWith("/") }?.let { File(it) }?.let { f ->
            if (Volumes.of(this, f) == null) runCatching { f.canonicalFile }.getOrDefault(f) else f
        }
        val folder = path?.takeIf { it.isDirectory } ?: path?.takeIf { it.isFile }?.parentFile
        when {
            cat != null -> openCategory(cat)
            folder != null -> openFolder(folder)
            else -> showHome()
        }
    }

    /** The place on show, as remembered: a folder's path, "cat:" and a category, or "home". */
    private fun placeKey(): String {
        val cat = category
        return when {
            home -> PLACE_HOME
            cat != null -> CATEGORY + cat.key
            else -> dir.path
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PLACE, placeKey())
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
        // Files may come and go while Swift is out of sight: the start page counts again on return.
        homeDirty = true
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's settings with access now, or a card put in.
        if (needAccess && hasAccess()) {
            accessGiven()
        } else if (home && homeDirty) {
            loadHome()
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

    /** The start page itself is on show (not a search on it, nor an archive opened from it). */
    private val onStartPage get() = home && index == null && searchRows == null

    /**
     * Back, one step at a time (2.8): a search, then the selection, then up in an archive and out of
     * it, then up a folder at a time to the top of its storage (also when Files opened deep in a
     * folder), then the start page, then out of Files.
     */
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val idx = index
            when {
                search.text.isNotEmpty() -> search.setText("")
                selected.isNotEmpty() -> clearSelection()
                idx != null && inArchive.isNotEmpty() -> showArchiveFolder(inArchive.substringBeforeLast('/', ""))
                idx != null -> leaveArchive()
                home -> finish()
                category != null -> showHome()
                else -> upFolder()?.let { openFolder(it) } ?: showHome()
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
            setPadding(Ui.dp(this@FilesActivity, 8), 0, Ui.dp(this@FilesActivity, 12), 0)
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

    // ------------------------------------------------------------------ places

    /** Before another place shows: where the list was is kept, and what belonged to the old place goes. */
    private fun leavePlace() {
        rememberPosition()
        placeGen++
        loadJob?.cancel()
        searchJob?.cancel()
        loading = false
        searching = false
        home = false
        category = null
        index = null
        inArchive = ""
        focusPath = null
        selected.clear()
        searchRows = null
        if (search.text.isNotEmpty()) search.setText("")
    }

    /** Shows [to] (leaving an archive, a category or a search), remembered for next time. */
    private fun openFolder(to: File, remember: Boolean = true) {
        leavePlace()
        dir = to
        if (remember) sp.edit().putString(PREF_LAST, to.path).apply()
        load()
    }

    /** The start page (2.8): storages, categories, favourite folders and recent files. */
    private fun showHome() {
        leavePlace()
        home = true
        sp.edit().putString(PREF_LAST, PLACE_HOME).apply()
        render()
        restorePosition()
        if (homeDirty || homeCache == null || SystemClock.elapsedRealtime() - homeLoadedAt > HOME_FRESH_MS) loadHome()
    }

    /** One category's files from all over the phone (2.8), newest first unless sorted otherwise. */
    private fun openCategory(cat: FileIndex.Category) {
        leavePlace()
        category = cat
        sp.edit().putString(PREF_LAST, CATEGORY + cat.key).apply()
        loadCategory()
    }

    private fun positionKey() = index?.let { it.file.path + "!" + inArchive } ?: placeKey()

    private fun rememberPosition() {
        val lm = list.layoutManager as? LinearLayoutManager ?: return
        // Still reading: what is on show belongs to the place before.
        if (loading || adapter.itemCount == 0) return
        positions[positionKey()] = lm.findFirstVisibleItemPosition()
    }

    private fun restorePosition() {
        val p = positions[positionKey()] ?: 0
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
        val gen = placeGen
        loading = true
        if (!keepScroll) showLoadingSoon("Opening the folder…")
        renderPath()
        updateChrome()
        loadJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { FileOps.list(target, hidden, order) }
            if (gen != placeGen) return@launch
            showRead(found, keepScroll)
        }
    }

    /** Reads a category from Android's media index, off the main thread. */
    private fun loadCategory(keepScroll: Boolean = false) {
        val cat = category ?: return
        if (!hasAccess()) {
            render()
            return
        }
        loadJob?.cancel()
        val order = categorySort
        val gen = placeGen
        val app = applicationContext
        loading = true
        if (!keepScroll) showLoadingSoon("Looking for files…")
        renderPath()
        updateChrome()
        loadJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val all = FileIndex.list(app, cat) { !isActive }
                // The index gives them newest first already.
                if (order == FileOps.SORT_DATE) all else FileOps.sorted(all, order)
            }
            if (gen != placeGen) return@launch
            showRead(found, keepScroll)
        }
    }

    /** A folder or a category was read: its rows show. */
    private fun showRead(found: List<FileItem>?, keepScroll: Boolean) {
        loading = false
        unreadable = found == null
        listed = found.orEmpty()
        rows = listed.map { rowOf(it) }
        rowsBytes = listed.sumOf { it.size.coerceAtLeast(0) }
        render()
        if (!keepScroll) restorePosition()
        focusPath?.let { p ->
            focusPath = null
            val i = rows.indexOfFirst { it.file?.path == p }
            if (i >= 0) (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(i, list.height / 3)
        }
        if (searchRows != null || (category != null && search.text.isNotBlank())) runSearch()
    }

    /** Reads the place on show again after a change, keeping where the list was. */
    private fun reload() {
        when {
            index != null -> {}
            home -> loadHome()
            category != null -> loadCategory(keepScroll = true)
            else -> load(keepScroll = true)
        }
    }

    /**
     * The start page's storages and favourites (quick), then its numbers and recent files (a moment
     * longer: one pass through Android's media index). What was read last time shows meanwhile.
     */
    private fun loadHome() {
        if (!hasAccess()) return
        homeJob?.cancel()
        homeLoadedAt = SystemClock.elapsedRealtime()
        homeDirty = false
        val app = applicationContext
        homeJob = lifecycleScope.launch {
            val (storages, favourites) = withContext(Dispatchers.IO) {
                val cards = Volumes.refresh(app).map { v -> v.space().let { (free, total) -> StorageCard(v, free, total) } }
                val favs = Favourites.paths().map { File(it) }.filter { it.isDirectory }
                    .map { rowOf(FileItem.of(it, count = true, where = FileIndex.placeOf(app, it.parentFile))) }
                cards to favs
            }
            val before = homeCache
            homeCache = HomeData(storages, favourites, before?.counts, before?.recent)
            if (onStartPage) render()
            val overview = withContext(Dispatchers.IO) { FileIndex.overview(app) { !isActive } }
            homeCache = HomeData(storages, favourites, overview.counts, overview.recent.map { rowOf(it) })
            if (onStartPage) render()
        }
    }

    private fun homeItems(): List<Any> {
        val data = homeCache
        val out = ArrayList<Any>()
        out += Header("Storage")
        if (data != null) out.addAll(data.storages) else Volumes.all(this).forEach { out += StorageCard(it, -1, -1) }
        out += Header("Categories")
        out += CategoryGrid(data?.counts)
        if (data != null) {
            out += Header("Favourites")
            if (data.favourites.isEmpty()) out += Hint("Keep the folders you use most here: tap ⋮ next to a folder, then Add to favourites.")
            else out.addAll(data.favourites)
        }
        val recent = data?.recent
        if (recent != null) {
            out += Header("Recent files", if (recent.size > HOME_RECENT) "Show all" else null) { openCategory(FileIndex.Category.RECENT) }
            if (recent.isEmpty()) out += Hint(FileIndex.Category.RECENT.hint) else out.addAll(recent.take(HOME_RECENT))
        }
        return out
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
        val access = hasAccess()
        val shown: List<Any> = when {
            !access && index == null -> emptyList()
            else -> searchRows ?: when {
                index != null -> archiveRows()
                home -> homeItems()
                else -> rows
            }
        }
        shownRowCount = if (onStartPage) 0 else shown.size
        applyLayout()
        adapter.set(shown)
        renderPath()
        updateChrome()
        val cat = category
        val q = search.text.toString().trim()
        when {
            !access && index == null -> showNoAccess()
            loading && shown.isEmpty() -> {}
            searchRows != null -> showEmpty(
                shown.isEmpty() && !searching, "Nothing found",
                when {
                    home -> "No file or folder on this phone has “$q” in its name."
                    cat != null -> "No file here has “$q” in its name."
                    else -> "No file or folder here has “$q” in its name."
                },
                R.drawable.ic_search,
            )
            index != null -> showEmpty(shown.isEmpty(), "Nothing in here", null, R.drawable.ic_archive)
            home -> empty.visibility = View.GONE
            cat != null -> showEmpty(shown.isEmpty(), cat.nothing, cat.hint, cat.icon)
            unreadable -> showEmpty(true, "Swift can't open this folder", "Android keeps it private, or it is no longer there.", R.drawable.ic_folder)
            else -> showEmpty(shown.isEmpty(), "This folder is empty", if (!showHidden) "Hidden files (names starting with a dot) aren't shown." else null, R.drawable.ic_folder)
        }
    }

    /** Rows or tiles (2.8): the start page is always a list; folders, categories, archives and searches follow the choice. */
    private fun applyLayout() {
        tiles = grid && !onStartPage
        val lm = list.layoutManager as? GridLayoutManager ?: return
        val span = if (tiles) gridCols else 1
        if (lm.spanCount != span) lm.spanCount = span
        val pad = if (tiles) dp(8) else 0
        if (list.paddingLeft != pad) list.setPadding(pad, list.paddingTop, pad, list.paddingBottom)
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

    /**
     * The place, tappable part by part: "⌂ › Phone storage › Download › Swift" (2.8: the house goes
     * to the start page, which has no path of its own).
     */
    private fun renderPath() {
        val idx = index
        if (home && idx == null) {
            pathScroll.visibility = View.GONE
            return
        }
        pathScroll.visibility = View.VISIBLE
        crumbs.removeAllViews()
        crumbs.addView(ImageView(this).apply {
            setImageDrawable(Ui.icon(this@FilesActivity, R.drawable.ic_home, Ui.accent(this@FilesActivity)))
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.ripple(this@FilesActivity, null, 12)
            contentDescription = "Files start page"
            setOnClickListener { showHome() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val parts = ArrayList<Pair<String, () -> Unit>>()
        val cat = category
        val v = Volumes.of(this, dir)
        when {
            home -> {}
            cat != null -> parts += cat.label to { leaveArchive() }
            v != null -> {
                parts += v.name to { openFolder(v.root) }
                var at = v.root
                for (p in dir.path.removePrefix(v.root.path).split('/').filter { it.isNotEmpty() }) {
                    at = File(at, p)
                    val target = at
                    parts += p to { openFolder(target) }
                }
            }
            else -> parts += folderTitle(dir) to { openFolder(dir) }
        }
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
            crumbs.addView(ImageView(this).apply {
                setImageDrawable(Ui.icon(this@FilesActivity, R.drawable.ic_chevron_right, Ui.textSecondary(this@FilesActivity)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)))
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

    private fun number(n: Int) = String.format(Locale.getDefault(), "%,d", n)

    private fun filesText(n: Int) = if (n == 1) "1 file" else "${number(n)} files"

    private fun updateChrome() {
        val idx = index
        val cat = category
        val selecting = selected.isNotEmpty()
        val hint = when {
            idx != null -> "Search in this archive"
            home -> "Search all your files"
            cat != null -> "Search in ${cat.label}"
            else -> "Search in this folder"
        }
        if (search.hint != hint) search.hint = hint
        updateMenu()
        if (navClose != selecting) {
            navClose = selecting
            toolbar.setNavigationIcon(if (selecting) R.drawable.ic_close else R.drawable.ic_back)
        }
        if (selecting) {
            toolbar.title = if (selected.size == 1) "1 selected" else "${number(selected.size)} selected"
            toolbar.subtitle = null
            toolbar.navigationContentDescription = "Leave selection"
        } else {
            toolbar.navigationContentDescription = if (onStartPage) "Close Files" else "Back"
            if (idx != null) {
                toolbar.title = if (inArchive.isEmpty()) idx.file.name else inArchive.substringAfterLast('/')
                toolbar.subtitle = "${idx.format.label} · " + FileJobs.items(idx.entries.count { !it.isDir }).replace("item", "file") + if (idx.encrypted) " · locked" else ""
            } else {
                toolbar.title = when {
                    home -> "Files"
                    cat != null -> cat.label
                    else -> folderTitle(dir)
                }
                toolbar.subtitle = when {
                    searching -> "Searching…"
                    searchRows != null -> FileJobs.items(searchRows!!.size) + " found" + if (searchRows!!.size >= MAX_FOUND) " (the first ones)" else ""
                    loading || home -> null
                    cat != null && rows.size >= FileIndex.LIST_MAX -> "The newest ${number(rows.size)} files"
                    cat != null -> filesText(rows.size) + if (rowsBytes > 0) " · " + UrlUtils.formatBytes(rowsBytes) else ""
                    else -> FileJobs.items(rows.size)
                }
            }
        }
        // The bar at the bottom: what to do with the selection, or with the archive. Its buttons act on
        // the selection as it is when tapped, so they are made again only when the bar itself changes
        // (2.8: not on every tap that selects one more).
        val oneFile = selected.values.singleOrNull()?.takeIf { !it.isDir }
        val bar = when {
            selecting && idx != null -> "entries:" + (oneFile != null)
            selecting -> "files"
            idx != null -> "archive:" + idx.file.path
            else -> ""
        }
        if (bar != barShown) {
            barShown = bar
            when {
                selecting && idx != null -> setActions(listOfNotNull(
                    barButton(R.drawable.ic_unarchive, "Extract") { extractSelected(named = true) },
                    barButton(R.drawable.ic_folder, "Extract to…") { extractSelectedTo() },
                    oneFile?.let { barButton(R.drawable.ic_open_new, "Open") { selected.values.singleOrNull()?.entry?.let { e -> openEntry(e) } } },
                    barButton(R.drawable.ic_info, "Details") { entryDetails(selected.values.toList()) },
                ))
                selecting -> setActions(listOf(
                    barButton(R.drawable.ic_copy, "Copy") { setClip(selectedFiles(), move = false) },
                    barButton(R.drawable.ic_move, "Move") { setClip(selectedFiles(), move = true) },
                    barButton(R.drawable.ic_share, "Share") { FileOpener.share(this, selectedFiles()) },
                    barButton(R.drawable.ic_delete, "Delete", danger = true) { delete(selectedFiles()) },
                    barButton(R.drawable.ic_more, "More") { moreSheet() },
                ))
                idx != null -> setActions(listOf(
                    barButton(R.drawable.ic_unarchive, "Extract all") { index?.let { i -> extract(i.file, i, null, "", besideArchive(i.file), named = true) } },
                    barButton(R.drawable.ic_folder, "Extract to…") { index?.let { i -> clipExtract(i.file, i, null, "") } },
                ))
                else -> actionBar.removeAllViews()
            }
        }
        actionBar.visibility = if (bar.isEmpty()) View.GONE else View.VISIBLE
        // Things wait to be put in a folder: only a folder can take them (not the start page or a category).
        val c = clip
        if (c != null && !selecting && idx == null && !home && cat == null) {
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

    /**
     * At most two buttons at the top (2.8: four left the folder's name cut short, "Phone st…"): grid
     * or list, and ⋮ for the rest. While selecting, "Select all" or "Clear selection" in words.
     * Made again only when they change (a tap that selects one more doesn't rebuild them).
     */
    private fun updateMenu() {
        val selecting = selected.isNotEmpty()
        val all = selecting && selected.size >= shownRowCount
        val key = when {
            selecting -> "select:$all"
            onStartPage -> "home"
            else -> "place:$grid"
        }
        if (key == menuShown) return
        menuShown = key
        toolbar.menu.clear()
        val color = Ui.text(this)
        when {
            selecting -> addTextAction(MENU_SELECT_ALL, if (all) "Clear selection" else "Select all")
            onStartPage -> addMenu(MENU_MORE, "More", R.drawable.ic_more, color)
            else -> {
                addMenu(MENU_VIEW, if (grid) "Show as a list" else "Show as a grid", if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view, color)
                addMenu(MENU_MORE, "More", R.drawable.ic_more, color)
            }
        }
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

    /** A button at the top in words, in the accent colour. */
    private fun addTextAction(id: Int, label: String) {
        val v = Ui.text(this, Ui.Type.LABEL, label, Ui.accent(this), lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.ripple(this@FilesActivity, null, 18)
            setOnClickListener { onMenu(id) }
        }
        toolbar.menu.add(0, id, id, label).apply {
            actionView = v
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun onMenu(id: Int) {
        when (id) {
            MENU_VIEW -> toggleGrid()
            MENU_MORE -> optionsSheet()
            MENU_SELECT_ALL -> if (selected.isNotEmpty() && selected.size >= shownRowCount) clearSelection() else selectAll()
        }
    }

    /** List or grid (2.8), remembered; the same file stays at the top. */
    private fun toggleGrid() {
        rememberPosition()
        grid = !grid
        sp.edit().putBoolean(PREF_GRID, grid).apply()
        applyLayout()
        adapter.notifyDataSetChanged()
        restorePosition()
        updateChrome()
    }

    private fun toggleHidden() {
        val show = !showHidden
        sp.edit().putBoolean(PREF_HIDDEN, show).apply()
        say(if (show) "Hidden files (names starting with a dot) show now" else "Hidden files aren't shown now")
        if (!home && category == null && index == null) load(keepScroll = true)
    }

    private fun hiddenAction() = Sheets.Action(
        if (showHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
        if (showHidden) "Hide hidden files" else "Show hidden files",
    ) { toggleHidden() }

    /** ⋮ at the top (2.8): what used to be buttons there, and the rest of the old menu. */
    private fun optionsSheet() {
        val idx = index
        val cat = category
        val sub = toolbar.subtitle?.toString()
        val actions = ArrayList<Sheets.Action>()
        when {
            idx != null -> {
                actions += Sheets.Action(R.drawable.ic_sort, "Sort", primary = true) { chooseSort() }
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_info, "About", primary = true) { archiveDetails(idx) }
                Sheets.actions(this, idx.file.name, sub, actions, iconRes = R.drawable.ic_archive)
            }
            home -> {
                if (searchRows != null) actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { if (searchRows != null) runSearch() else loadHome() }
                actions += hiddenAction()
                Sheets.actions(this, "Files", sub, actions, iconRes = R.drawable.ic_folder)
            }
            cat != null -> {
                actions += Sheets.Action(R.drawable.ic_sort, "Sort", primary = true) { chooseSort() }
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { loadCategory(keepScroll = true) }
                if (cat == FileIndex.Category.DOWNLOADS) {
                    for (f in FileIndex.downloadFolders()) actions += Sheets.Action(R.drawable.ic_folder, "Open the “${folderTitle(f)}” folder") { openFolder(f) }
                }
                Sheets.actions(this, cat.label, sub, actions, iconRes = cat.icon)
            }
            else -> {
                val here = dir
                val fav = Favourites.has(here)
                actions += Sheets.Action(R.drawable.ic_folder_add, "New folder", primary = true) { newFolder() }
                actions += Sheets.Action(R.drawable.ic_sort, "Sort", primary = true) { chooseSort() }
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { load(keepScroll = true) }
                actions += hiddenAction()
                actions += Sheets.Action(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "Remove from favourites" else "Add to favourites") { setFavourite(here, !fav) }
                actions += Sheets.Action(R.drawable.ic_info, "About this folder") { details(listOf(here)) }
                Sheets.actions(this, folderTitle(here), sub, actions, iconRes = R.drawable.ic_folder)
            }
        }
    }

    private fun chooseSort() {
        val forCategory = category != null && index == null
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(FileOps.sortLabels.toTypedArray(), if (forCategory) categorySort else sort) { d, which ->
                sp.edit().putInt(if (forCategory) PREF_CATEGORY_SORT else PREF_SORT, which).apply()
                d.dismiss()
                // An archive sorts as it shows; the folder or category under it keeps its order.
                if (index == null) {
                    listed = FileOps.sorted(listed, which)
                    rows = listed.map { rowOf(it) }
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** Adds [f] to the start page's favourites, or takes it off (2.8). */
    private fun setFavourite(f: File, on: Boolean) {
        if (on) Favourites.add(f) else Favourites.remove(f)
        say(if (on) "Added to favourites on the Files start page" else "Removed from favourites")
        homeDirty = true
        if (home) loadHome()
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
                placeGen++
                // Its folder is the one on show (it may have been found by a search below); from the
                // start page or a category, Back returns there instead.
                if (!home && category == null) dir = besideArchive(f)
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
                restorePosition()
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

    /** Out of the archive, back to where it was opened from: its folder, a category or the start page. */
    private fun leaveArchive() {
        val idx = index ?: return
        placeGen++
        index = null
        inArchive = ""
        selected.clear()
        when {
            home -> {
                render()
                restorePosition()
            }
            // Read again: something may have been extracted meanwhile.
            category != null -> loadCategory()
            else -> {
                dir = besideArchive(idx.file)
                load()
            }
        }
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

    /** Whether a hold selects (the start page's own rows open their ⋮ instead: there is nothing to select all of). */
    private val canSelect get() = !onStartPage

    private fun toggle(r: Row, position: Int = -1) {
        val was = selected.isNotEmpty()
        if (selected.remove(r.key) == null) selected[r.key] = r
        // Selecting starts or ends: every row shows or hides its tick.
        if (was != selected.isNotEmpty()) refreshSelection()
        val pos = if (position >= 0) position else adapter.items.indexOfFirst { it is Row && it.key == r.key }
        if (pos >= 0) adapter.notifyItemChanged(pos, PAYLOAD_TOGGLED)
        updateChrome()
    }

    /** Rows show their ticks again (not their pictures: those stay as they are). */
    private fun refreshSelection() = adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)

    private fun selectAll() {
        if (!canSelect) return
        for (it in adapter.items) if (it is Row) selected[it.key] = it
        refreshSelection()
        updateChrome()
    }

    private fun clearSelection() {
        if (selected.isNotEmpty()) {
            selected.clear()
            refreshSelection()
        }
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
        if (one != null && one.isFile && !rowsNow.singleOrNull()?.where.isNullOrEmpty()) actions += Sheets.Action(R.drawable.ic_folder, "Show in folder") { showInFolder(one) }
        if (one != null && one.isDirectory) {
            val fav = Favourites.has(one)
            actions += Sheets.Action(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "Remove from favourites" else "Add to favourites") { setFavourite(one, !fav) }
        }
        actions += Sheets.Action(R.drawable.ic_info, "Details") { details(files) }
        if (selected.size < shownRowCount) actions += Sheets.Action(R.drawable.ic_select_all, "Select all") { selectAll() }
        actions += Sheets.Action(R.drawable.ic_close, "Clear selection") { clearSelection() }
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

    /** The folder a file found elsewhere (a category, a search, recent files) is in, with the file in sight. */
    private fun showInFolder(f: File) {
        val parent = f.parentFile ?: return
        openFolder(parent)
        focusPath = f.path
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
                val fav = Favourites.has(f)
                actions += Sheets.Action(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "Remove from favourites" else "Add to favourites") { setFavourite(f, !fav) }
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
        // 2.8: a file found in a category, a search or recent files can be seen where it is.
        if (!r.isDir && !r.where.isNullOrEmpty()) actions += Sheets.Action(R.drawable.ic_folder, "Show in folder") { showInFolder(f) }
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
                        renamed(f, to)
                    } else {
                        say("Swift couldn't rename “${f.name}”")
                    }
                }
            }
        }
    }

    /**
     * After a rename: a folder reads itself again; a category or the start page changes the one row
     * (2.8: Android's media index learns the new name a moment later, so reading it now would lose it).
     */
    private fun renamed(from: File, to: File) {
        if (Favourites.has(from)) {
            Favourites.remove(from)
            Favourites.add(to)
        }
        homeDirty = true
        val swap = { r: Row -> if (r.file?.path == from.path) rowOf(FileItem.of(to, count = to.isDirectory, where = r.where)) else r }
        when {
            index != null -> {}
            home -> homeCache?.let { c ->
                homeCache = HomeData(c.storages, c.favourites.map(swap), c.counts, c.recent?.map(swap))
                if (onStartPage) render()
            }
            category != null -> {
                listed = listed.map { if (it.file.path == from.path) FileItem.of(to, where = it.where) else it }
                rows = rows.map(swap)
                searchRows = searchRows?.map(swap)
                render()
            }
            else -> load(keepScroll = true)
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

    private fun bytesText(n: Long) = if (n < 1024) UrlUtils.formatBytes(n) else "${UrlUtils.formatBytes(n)} (${String.format(Locale.getDefault(), "%,d", n)} bytes)"

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
                // What was selected among the results isn't on show any more: it mustn't be deleted unseen.
                selected.clear()
                render()
            }
            return
        }
        list.postDelayed(searchSoon, 300)
    }

    /**
     * In this folder and below (or in the archive's folder and below), nearest first; 2.8: in a
     * category, among its files; on the start page, every file on the phone (Android's media index).
     */
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
        if (category != null) {
            // Not read yet: searched once it is.
            if (loading) return
            searchRows = rows.filter { it.name.contains(q, ignoreCase = true) }
            render()
            list.scrollToPosition(0)
            return
        }
        val from = dir
        val hidden = showHidden
        val everywhere = home
        val gen = placeGen
        val app = applicationContext
        searching = true
        updateChrome()
        searchJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                if (everywhere) FileIndex.search(app, q, hidden, MAX_FOUND) { !isActive } else FileOps.search(from, q, hidden, MAX_FOUND) { !isActive }
            }
            searching = false
            if (gen != placeGen) return@launch
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
        homeDirty = true
        when {
            index != null -> {}
            home || category != null -> reload()
            dir.path in job.touched || job.touched.isEmpty() -> load(keepScroll = true)
        }
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

    /** A box as tall as it is wide: a tile's picture. */
    private class SquareBox(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    /**
     * A file or folder as a row or (2.8) a tile: [card] is what is tapped and marked when selected,
     * [box] holds the picture (a tile's shrinks a little when selected), [check] the tick shown while
     * selecting.
     */
    private class FileHolder(
        root: View, val tile: Boolean, val card: View, val box: View, val icon: ImageView, val check: ImageView,
        val title: TextView, val subtitle: TextView?, val more: ImageButton, val play: ImageView?,
    ) : RecyclerView.ViewHolder(root) {
        var thumb: FileThumbs.Request? = null
        var marked = false
        var boundKey: String? = null
        lateinit var folderFill: Drawable
        lateinit var fileFill: Drawable
        lateinit var plain: Drawable
        lateinit var markedFill: Drawable
    }

    private class HeaderHolder(v: View, val title: TextView, val action: TextView) : RecyclerView.ViewHolder(v)

    private class StorageHolder(v: View, val icon: ImageView, val name: TextView, val bar: ProgressBar, val detail: TextView) : RecyclerView.ViewHolder(v)

    private class CategoriesHolder(v: View, val counts: Map<FileIndex.Category, Pair<View, TextView>>) : RecyclerView.ViewHolder(v)

    private class HintHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Any> = emptyList()
            private set

        fun set(list: List<Any>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Row -> if (tiles) TYPE_TILE else TYPE_ROW
            is Header -> TYPE_HEADER
            is StorageCard -> TYPE_STORAGE
            is CategoryGrid -> TYPE_CATEGORIES
            else -> TYPE_HINT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            TYPE_ROW -> makeRow()
            TYPE_TILE -> makeTile()
            TYPE_HEADER -> makeHeader()
            TYPE_STORAGE -> makeStorage()
            TYPE_CATEGORIES -> makeCategories()
            else -> HintHolder(Ui.text(this@FilesActivity, Ui.Type.BODY_SECONDARY, "").apply {
                setPadding(dp(20), dp(2), dp(20), dp(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            when (val item = items[position]) {
                is Row -> {
                    val h = holder as FileHolder
                    // Only the selection changed: the tick and the marking, not the picture.
                    if (payloads.isNotEmpty() && h.boundKey == item.key) bindSelection(h, item, PAYLOAD_TOGGLED in payloads) else bind(h, item)
                }
                is Header -> bindHeader(holder as HeaderHolder, item)
                is StorageCard -> bindStorage(holder as StorageHolder, item)
                is CategoryGrid -> bindCategories(holder as CategoriesHolder, item)
                is Hint -> (holder as HintHolder).text.text = item.text
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is FileHolder) {
                holder.thumb?.cancel()
                holder.thumb = null
            }
        }
    }

    private fun rowAt(h: RecyclerView.ViewHolder): Row? = adapter.items.getOrNull(h.bindingAdapterPosition) as? Row

    private fun makeRow(): FileHolder {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            minimumHeight = dp(64)
            setBackgroundResource(RowHolder.selectableBackground(ctx))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val icon = ImageView(ctx).apply {
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(icon, LinearLayout.LayoutParams(thumbPx, thumbPx))
        // The middle of a long name goes, so its ending (.jpg, .zip) stays in sight.
        val title = Ui.text(ctx, Ui.Type.BODY, "").apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val subtitle = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1).apply { ellipsize = TextUtils.TruncateAt.END }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            addView(title)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val more = ImageButton(ctx).apply {
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_more, Ui.textSecondary(ctx)))
            contentDescription = "More"
            setBackgroundResource(RowHolder.selectableBackground(ctx))
        }
        root.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        // 2.8: a tick box in ⋮'s place while selecting (the same width: the name doesn't move).
        val check = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(check, LinearLayout.LayoutParams(dp(44), dp(44)))
        val h = FileHolder(root, false, root, icon, icon, check, title, subtitle, more, null)
        h.plain = root.background
        h.markedFill = Ui.ripple(ctx, ColorDrawable(Ui.accentContainer(ctx)), 0)
        return wire(h)
    }

    /** A tile (2.8): the picture (or the kind's icon) in a square, the name and ⋮ under it. */
    private fun makeTile(): FileHolder {
        val ctx = this
        val root = FrameLayout(ctx).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), 0, dp(2))
        }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val box = SquareBox(ctx)
        val icon = ImageView(ctx).apply {
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Videos are told from pictures by a small play mark.
        val play = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Ui.text(ctx))
            background = Ui.circle(Tones.withAlpha(Palette.color(ctx, R.color.page_bg), 0xD9))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(play, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
        // The tick box sits on a patch of the page's colour, so it shows on any picture.
        val check = ImageView(ctx).apply {
            background = Ui.shape(ctx, Tones.withAlpha(Palette.color(ctx, R.color.page_bg), 0xE6), 6)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(check, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        card.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = Ui.text(ctx, Ui.Type.BODY, "").apply {
            textSize = 13.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(2), 0, 0, 0)
        }
        line.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val more = ImageButton(ctx).apply {
            setImageDrawable(Ui.icon(ctx, R.drawable.ic_more, Ui.textSecondary(ctx)))
            contentDescription = "More"
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            background = Ui.rippleBorderless(ctx)
        }
        line.addView(more, LinearLayout.LayoutParams(dp(32), dp(36)))
        card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        val h = FileHolder(root, true, card, box, icon, check, title, null, more, play)
        h.plain = Ui.ripple(ctx, null, 16)
        h.markedFill = Ui.ripple(ctx, Ui.shape(ctx, Ui.accentContainer(ctx), 16), 16)
        card.background = h.plain
        return wire(h)
    }

    /** What a row or tile does when tapped, held or its ⋮ is tapped; and its two backgrounds. */
    private fun wire(h: FileHolder): FileHolder {
        val radius = if (h.tile) 14 else 10
        h.folderFill = Ui.shape(this, Ui.accentContainer(this), radius)
        h.fileFill = Ui.shape(this, Palette.color(this, R.color.surface_alt), radius)
        h.card.setOnClickListener {
            val r = rowAt(h) ?: return@setOnClickListener
            if (selected.isNotEmpty()) toggle(r, h.bindingAdapterPosition) else open(r)
        }
        h.card.setOnLongClickListener { v ->
            val r = rowAt(h) ?: return@setOnLongClickListener false
            if (canSelect) {
                Haptics.hold(v)
                toggle(r, h.bindingAdapterPosition)
            } else {
                itemSheet(r)
            }
            true
        }
        h.more.setOnClickListener { rowAt(h)?.let { itemSheet(it) } }
        return h
    }

    private fun bind(h: FileHolder, r: Row) {
        h.boundKey = r.key
        h.title.text = r.name
        h.subtitle?.text = subtitleOf(r)
        h.thumb?.cancel()
        h.thumb = null
        h.icon.tag = r.key
        setIcon(h, r)
        h.play?.visibility = if (r.kind == FileKind.VIDEO) View.VISIBLE else View.GONE
        val f = r.file
        if (f != null && !r.isDir && FileThumbs.has(r.kind)) {
            val key = r.key
            // An app's icon stays an icon on a tile, not a blown-up square.
            val apkOnTile = h.tile && r.kind == FileKind.APK
            h.thumb = FileThumbs.load(this, f, r.time, r.kind, if (h.tile) tilePx else thumbPx) { bmp ->
                if (h.icon.tag != key) return@load
                h.icon.imageTintList = null
                val p = if (apkOnTile) tileIconPad / 2 else 0
                h.icon.setPadding(p, p, p, p)
                h.icon.scaleType = if (apkOnTile) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                h.icon.setImageBitmap(bmp)
            }
        }
        bindSelection(h, r, false)
    }

    /** The kind's icon (a folder in the accent's soft colour, a document's own mark), until a picture comes. */
    private fun setIcon(h: FileHolder, r: Row) {
        val v = h.icon
        v.background = if (r.isDir) h.folderFill else h.fileFill
        val doc = if (r.kind == FileKind.DOCUMENT) Docs.kindOf(r.name, null) else null
        if (doc != null) {
            v.imageTintList = null
            v.scaleType = ImageView.ScaleType.CENTER
            v.setPadding(0, 0, 0, 0)
            v.setImageDrawable(DocIcon(doc, dp(if (h.tile) 44 else 30)))
            return
        }
        val pad = if (h.tile) tileIconPad else 0
        v.setPadding(pad, pad, pad, pad)
        v.scaleType = if (h.tile) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER
        v.setImageResource(if (r.isDir) R.drawable.ic_folder else r.kind.icon)
        v.imageTintList = if (r.isDir) onContainerTint else secondaryTint
    }

    /** The tick box while selecting (a small pop when tapped), and the selected row's or tile's marking. */
    private fun bindSelection(h: FileHolder, r: Row, toggled: Boolean) {
        val selecting = selected.isNotEmpty()
        val on = r.key in selected
        h.check.visibility = if (selecting) View.VISIBLE else View.GONE
        // A tile keeps ⋮'s room, so the grid's lines don't change height when selecting starts.
        h.more.visibility = if (!selecting) View.VISIBLE else if (h.tile) View.INVISIBLE else View.GONE
        if (selecting) {
            h.check.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)
            h.check.imageTintList = if (on) accentTint else secondaryTint
            if (toggled && on) Motion.bump(h.check, 1.2f)
        }
        if (on != h.marked) {
            h.marked = on
            h.card.background = if (on) h.markedFill else h.plain
        }
        if (h.tile) {
            val scale = if (on) 0.9f else 1f
            h.box.animate().cancel()
            if (toggled) {
                h.box.animate().scaleX(scale).scaleY(scale).setDuration(Motion.SHORT).setInterpolator(Motion.standard).start()
            } else {
                h.box.scaleX = scale
                h.box.scaleY = scale
            }
        }
        h.card.contentDescription = r.name + ", " + (if (r.isDir) "folder" else r.kind.label) + if (on) ", selected" else ""
    }

    // ------------------------------------------------------------------ the start page's parts

    /** The start page's cards: a soft outline on the card colour, as on the Documents screen's thumbnails. */
    private fun cardFill(): Drawable =
        Ui.ripple(this, Ui.shape(this, Palette.color(this, R.color.card_bg), Ui.R_MEDIUM, Palette.color(this, R.color.outline)), Ui.R_MEDIUM)

    private fun makeHeader(): HeaderHolder {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(12), dp(6))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val title = Ui.text(ctx, Ui.Type.SECTION, "")
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = Ui.text(ctx, Ui.Type.BODY, "", Ui.accent(ctx)).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundResource(RowHolder.selectableBackground(ctx))
        }
        row.addView(action)
        return HeaderHolder(row, title, action)
    }

    private fun bindHeader(h: HeaderHolder, item: Header) {
        h.title.text = item.text
        h.action.text = item.action.orEmpty()
        h.action.visibility = if (item.action != null) View.VISIBLE else View.GONE
        h.action.setOnClickListener { item.onAction?.invoke() }
    }

    /** A storage: its name, a bar of how full it is, and what is free; tapping opens its top folder. */
    private fun makeStorage(): StorageHolder {
        val ctx = this
        val outer = FrameLayout(ctx).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardFill()
            Ui.pressable(this, 0.98f)
        }
        val icon = Ui.iconBadge(ctx, R.drawable.ic_phone, 40)
        card.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val name = Ui.text(ctx, Ui.Type.SUBTITLE, "", lines = 1)
        // The bar's track is in the page's colours too (its drawable is made from XML).
        val bar = Palette.paint(horizontalBar())
        val detail = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
        texts.addView(name)
        texts.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(8)
            bottomMargin = dp(6)
        })
        texts.addView(detail)
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val h = StorageHolder(outer, icon, name, bar, detail)
        card.setOnClickListener { (adapter.items.getOrNull(h.bindingAdapterPosition) as? StorageCard)?.let { openFolder(it.volume.root) } }
        return h
    }

    private fun bindStorage(h: StorageHolder, s: StorageCard) {
        h.icon.setImageDrawable(Ui.icon(this, if (s.volume.removable) R.drawable.ic_sd_card else R.drawable.ic_phone, Ui.onAccentContainer(this)))
        h.name.text = s.volume.name
        val known = s.total > 0 && s.free >= 0
        val used = if (known) s.total - s.free else 0L
        h.bar.progress = if (known) (used * 1000 / s.total).toInt() else 0
        // Nearly full: the bar turns the warning colour.
        h.bar.progressTintList = if (known && s.free < s.total / 10) dangerTint else accentTint
        h.detail.text = if (known) "${UrlUtils.formatBytes(used)} used · ${UrlUtils.formatBytes(s.free)} free" else "Measuring…"
        (h.bar.parent.parent as View).contentDescription = s.volume.name +
            if (known) ", ${UrlUtils.formatBytes(used)} used, ${UrlUtils.formatBytes(s.free)} free of ${UrlUtils.formatBytes(s.total)}" else ""
    }

    /** Images, Videos, Music, Documents, Archives, APKs and Downloads: two (or three) cards to a line. */
    private fun makeCategories(): CategoriesHolder {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val counts = HashMap<FileIndex.Category, Pair<View, TextView>>()
        val perLine = if (minOf(resources.configuration.screenWidthDp, Ui.MAX_CONTENT_WIDTH) >= 480) 3 else 2
        for (line in FileIndex.Category.shown.chunked(perLine)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in line) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(8), dp(12))
                    background = cardFill()
                    Ui.pressable(this, 0.96f)
                    setOnClickListener { openCategory(c) }
                }
                card.addView(Ui.iconBadge(ctx, c.icon, 36), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                val count = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
                card.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(ctx, Ui.Type.SUBTITLE, c.label, lines = 1))
                    addView(count)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                counts[c] = card to count
                row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            // The last line keeps its cards the same width as the others.
            repeat(perLine - line.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return CategoriesHolder(col, counts)
    }

    private fun bindCategories(h: CategoriesHolder, g: CategoryGrid) {
        for ((c, views) in h.counts) {
            val s = g.counts?.get(c)
            val text = when {
                g.counts == null -> "Counting…"
                s == null || s.count == 0 -> "None"
                else -> "${number(s.count)} · ${UrlUtils.formatBytes(s.bytes)}"
            }
            views.second.text = text
            views.first.contentDescription = "${c.label}, $text"
        }
    }

    companion object {
        /** The folder to open (a path). */
        const val EXTRA_FOLDER = "folder"

        private const val PREF_SORT = "files_sort"
        private const val PREF_CATEGORY_SORT = "files_category_sort"
        private const val PREF_HIDDEN = "files_hidden"
        private const val PREF_GRID = "files_grid"
        private const val PREF_LAST = "files_last_folder"
        private const val STATE_PLACE = "place"
        private const val PLACE_HOME = "home"
        private const val CATEGORY = "cat:"
        private const val MAX_FOUND = 300

        /** Recent files on the start page itself ("Show all" lists the rest). */
        private const val HOME_RECENT = 6

        /** The start page counts again when shown after this long. */
        private const val HOME_FRESH_MS = 30_000L

        private const val MENU_SELECT_ALL = 4
        private const val MENU_VIEW = 5
        private const val MENU_MORE = 6

        private const val TYPE_ROW = 0
        private const val TYPE_TILE = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_STORAGE = 3
        private const val TYPE_CATEGORIES = 4
        private const val TYPE_HINT = 5

        /** Rows rebound for their selection only; the one tapped also pops its tick. */
        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_TOGGLED = "toggled"

        /** Files to copy or move (or an archive to extract) once the user is in the right folder; kept while Swift runs. */
        private var clip: Clip? = null

        /** The Files screen on show, which jobs report to when they end. */
        private var visible: WeakReference<FilesActivity>? = null

        /** Where each place's list was scrolled to, kept while Swift runs (2.8: Files opens again where it was). */
        private val positions = HashMap<String, Int>()

        /** The start page as last read, shown at once while it is read again. */
        private var homeCache: HomeData? = null

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
