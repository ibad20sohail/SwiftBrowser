package com.swiftbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.view.MotionEvent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.DocIcon
import com.swiftbrowser.docs.DocThumbs
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.files.Archives
import com.swiftbrowser.files.DragSelect
import com.swiftbrowser.files.Favourites
import com.swiftbrowser.files.FileIndex
import com.swiftbrowser.files.FileItem
import com.swiftbrowser.files.FileJobs
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileOpener
import com.swiftbrowser.files.FileOps
import com.swiftbrowser.files.FileProblem
import com.swiftbrowser.files.FileThumbs
import com.swiftbrowser.files.FilesSettings
import com.swiftbrowser.files.FolderSizes
import com.swiftbrowser.files.Parts
import com.swiftbrowser.files.PlainAccess
import com.swiftbrowser.files.PrivateFolder
import com.swiftbrowser.files.RecycleBin
import com.swiftbrowser.files.SpecialFolders
import com.swiftbrowser.files.StorageKinds
import com.swiftbrowser.files.Transfers
import com.swiftbrowser.files.TwoPanes
import com.swiftbrowser.files.Volumes
import com.swiftbrowser.media.LocalMedia
import com.swiftbrowser.media.Unfinished
import com.swiftbrowser.media.Watched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.ref.WeakReference
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
 *
 * 2.9: Delete moves things to the recycle bin (RecycleBin), with Undo; the bin is a place of its
 * own, with Restore and Delete forever. Private (PrivateFolder) is a folder inside Swift that opens
 * with the screen lock or a PIN and locks when Files is left. The start page gets recent documents
 * (Files is where documents are found now), and tools: Free up space (CleanerActivity), the
 * recycle bin, Private and Wi-Fi transfer (WifiTransferActivity). Options are in FilesSettings
 * (also in Swift's Settings), read again each time Files shows. Places slide in from the side
 * they're reached from, and deleted rows close up.
 *
 * 3.0.3: the start page has Continue (videos and songs on the phone left part-way in Swift's
 * player; the browser's Continue keeps the web's). The title bar and the path scroll away with the
 * list and the search box stays at the top. After a hold, sliding the finger selects the files it
 * passes. Videos' pictures show how much of them was watched, and a tick once watched to the end.
 *
 * 3.0.4: folders show what they hold ("12 items · 1.40 GB", measured off the main thread by
 * FolderSizes), and sorting by size sorts them by it too. Turned sideways on a wide screen, two
 * folders side by side (a button at the top): each side has its own path and list (Pane), the side
 * touched last has a thin accent line and is the one the title bar, the search box and the bars at
 * the bottom work on, and selected files copy or move straight to the other side.
 */
class FilesActivity : ListActivity() {
    /** Opened by a website's upload button (from Swift itself only: this screen can be started by other apps too): a tap gives the file to the page. */
    private val pickRequest: FilePick.Request? by lazy { if (callingActivity?.packageName == packageName) FilePick.requestIn(intent) else null }
    private val picking get() = pickRequest != null

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

    /**
     * A storage on the start page and how full it is (-1: not measured yet). 3.0.4: and [kinds], what
     * fills it (null until the media index was read).
     */
    private class StorageCard(val volume: Volumes.Volume, val free: Long, val total: Long, val kinds: StorageKinds.Sizes? = null)

    /** The start page's categories, with their numbers once counted. */
    private class CategoryGrid(val counts: Map<FileIndex.Category, FileIndex.Summary>?)

    /** 2.9: the Documents category's kinds to pick from (All, PDF, Word…), [chosen] being the one on show. */
    private class DocFilter(val labels: List<String>, val chosen: Int)

    /** Documents' kinds as people name them, each with the viewer's kinds it covers. */
    private enum class DocGroup(val label: String, val kinds: Set<Docs.Kind>) {
        PDF("PDF", setOf(Docs.Kind.PDF)),
        WORD("Word", setOf(Docs.Kind.WORD, Docs.Kind.OLD_WORD, Docs.Kind.ODT, Docs.Kind.RTF)),
        EXCEL("Excel", setOf(Docs.Kind.SHEET)),
        POWERPOINT("PowerPoint", setOf(Docs.Kind.SLIDES, Docs.Kind.OLD_SLIDES, Docs.Kind.ODP)),
        EBOOK("E-books", setOf(Docs.Kind.EPUB)),
        TEXT("Text", setOf(Docs.Kind.TEXT)),
    }

    /** A line of help on the start page. */
    private class Hint(val text: String)

    /** The start page's tools (2.9). */
    private enum class Tool(val label: String, val icon: Int) {
        CLEAN("Free up space", R.drawable.ic_files_clean),
        BIN("Recycle bin", R.drawable.ic_files_bin),
        PRIVATE("Private", R.drawable.ic_lock),
        TRANSFER("Wi-Fi transfer", R.drawable.ic_files_transfer),
        // 3.0.4: a computer's or a home storage's shared folders (NetworkActivity).
        NETWORK("Network folders", R.drawable.ic_files_network),
    }

    /** The tools' cards, with what the recycle bin holds once counted. */
    private class ToolGrid(val bin: RecycleBin.Summary?)

    /** 2.9: the documents opened in Swift lately, as small cards with their first page. */
    private class RecentDocs(val docs: List<Docs.Doc>)

    /** 3.0.3: videos and songs on the phone left part-way in Swift's player, as Continue's cards. */
    private class ContinueCards(val items: List<Unfinished.Item>)

    /**
     * What the start page shows ([counts] and [recent] come a moment after the rest). 3.0.3: a data
     * class, so a change to one part ([copy]) keeps the others; [continued]: Continue's cards.
     */
    private data class HomeData(
        val storages: List<StorageCard>,
        val favourites: List<Row>,
        val counts: Map<FileIndex.Category, FileIndex.Summary>?,
        val recent: List<Row>?,
        val bin: RecycleBin.Summary?,
        val docs: List<Docs.Doc>,
        val continued: List<Unfinished.Item>,
    )

    /**
     * 3.0.4: one side of the screen, with its own place, list and path: two sides show side by side on
     * a wide screen turned sideways, one otherwise. What the code below calls home, dir, rows,
     * selected… is the side being worked on's ([pane]): the side touched last ([chosen]), or for a
     * moment another one ([withPane]), whose slow read has just come back, say.
     */
    private inner class Pane(val side: Int) {
        lateinit var list: RecyclerView
        lateinit var empty: FrameLayout

        /** The list with its empty screen, and that as one side of the row of lists (touching it chooses the side). */
        lateinit var box: ViewGroup
        lateinit var frame: SideFrame
        lateinit var crumbs: LinearLayout
        lateinit var pathScroll: HorizontalScrollView
        lateinit var pathFrame: SideFrame

        /** The thin accent line at the top of the side worked on, while two show. */
        lateinit var mark: View
        val adapter = Adapter(this)

        /** On the screen now (the other side, while one folder shows, isn't). */
        var attached = false

        var home = false
        var category: FileIndex.Category? = null
        var dir: File = Volumes.internalRoot()
        var index: Archives.Index? = null
        var inArchive = ""
        var listed: List<FileItem> = emptyList()
        var rows: List<Row> = emptyList()
        var rowsBytes = 0L
        var searchRows: List<Row>? = null
        var unreadable = false
        var loading = false
        var searching = false
        var placeGen = 0
        val selected = LinkedHashMap<String, Row>()
        var loadJob: Job? = null
        var searchJob: Job? = null
        var focusPath: String? = null
        var inBin = false
        var binItems: Map<String, RecycleBin.Item> = emptyMap()
        var archiveReading: File? = null
        var tiles = false
        var shownRowCount = 0
        var pendingSlide = 0

        /** What the next showing of the list brings back was put back by Undo: its rows come in softly. */
        var undone = false
        var quietOnce = false
        var loadingText = ""
        var loadingLabel: TextView? = null
        var wanted: String? = null
        var sizing: Set<String> = emptySet()
        var sizeJob: Job? = null
        var touchedSinceShown = false

        /** What is typed in the search box for this side (the box shows the side worked on's). */
        var query = ""

        /** Where this side was while it wasn't on show (one folder showing), for when it comes back. */
        var pending: String? = null

        /** Where this side was last remembered to be, for Files to open there again. */
        var remembered: String? = null

        val showLoading = Runnable { if (attached) withPane(this@Pane) { showLoadingNow() } }
        val searchSoon = Runnable { if (attached) withPane(this@Pane) { runSearch() } }

        val inPrivate: Boolean get() = !home && !inBin && category == null && PrivateFolder.isInside(this@FilesActivity, dir)
    }

    /** 3.0.4: a side's part of the screen: touching it, or TalkBack going into it, makes it the side worked on. */
    private class SideFrame(ctx: Context, private val used: () -> Unit) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) used()
            return super.dispatchTouchEvent(ev)
        }

        override fun onRequestSendAccessibilityEvent(child: View, event: android.view.accessibility.AccessibilityEvent): Boolean {
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) used()
            return super.onRequestSendAccessibilityEvent(child, event)
        }
    }

    /** 3.0.4: the left side and the right one. */
    private val panes = arrayOf(Pane(TwoPanes.LEFT), Pane(TwoPanes.RIGHT))

    /** The side being worked on (see [withPane]), and the one touched last, which the title bar, the search box and the bars at the bottom show. */
    private var pane = panes[0]
    private var chosen = panes[0]

    /** 3.0.4: [p] is the side worked on from now: its list and empty screen are the ones the screen's shell knows. */
    private fun use(p: Pane) {
        pane = p
        list = p.list
        empty = p.empty
    }

    /**
     * 3.0.4: runs [block] for side [p] (its place, list and path), then goes back to the side worked
     * on before. Never across a wait: code that runs after a slow read comes back here first.
     */
    private inline fun <T> withPane(p: Pane, block: () -> T): T {
        val was = pane
        if (was === p) return block()
        use(p)
        try {
            return block()
        } finally {
            use(was)
        }
    }

    private val sp get() = SwiftApp.prefs.sp

    // 2.9: Files' options live in FilesSettings (Swift's Settings changes them too), read as they are now.
    private val sort get() = FilesSettings.sort
    private val categorySort get() = FilesSettings.categorySort
    private val showHidden get() = FilesSettings.showHidden

    /** Tiles instead of rows (2.8), remembered. */
    private var grid = false

    /** Small pictures in the lists (2.9: can be turned off in Settings). */
    private var thumbsOn = true

    /** The options as the list on show used them: another value means Settings changed them meanwhile. */
    private var settingsShown = ""

    /** The recycle bin's things are on show (2.9). 3.0.4: this and the rest of a place's state are the side worked on's (Pane). */
    private var inBin: Boolean
        get() = pane.inBin
        set(v) { pane.inBin = v }

    /** The bin's things by their rows' keys. */
    private var binItems: Map<String, RecycleBin.Item>
        get() = pane.binItems
        set(v) { pane.binItems = v }

    /**
     * Until when leaving Files is for a file of Private's opened in another screen (or Android's lock
     * question): Private then stays open a minute instead of locking at once. A moment only: a screen
     * that opens goes over Files straight away.
     */
    private var privateGraceUntil = 0L

    private fun keepPrivateOpen() {
        privateGraceUntil = SystemClock.elapsedRealtime() + 5_000
    }

    /** What to do once Android's screen-lock question is answered. */
    private var afterUnlock: (() -> Unit)? = null

    /** The start page is on show (an archive opened from it shows over it). */
    private var home: Boolean
        get() = pane.home
        set(v) { pane.home = v }

    /** A category's files are on show (Images, Downloads…), or an archive opened from them. */
    private var category: FileIndex.Category?
        get() = pane.category
        set(v) { pane.category = v }
    private var dir: File
        get() = pane.dir
        set(v) { pane.dir = v }
    private var index: Archives.Index?
        get() = pane.index
        set(v) { pane.index = v }
    private var inArchive: String
        get() = pane.inArchive
        set(v) { pane.inArchive = v }
    private var listed: List<FileItem>
        get() = pane.listed
        set(v) { pane.listed = v }
    private var rows: List<Row>
        get() = pane.rows
        set(v) { pane.rows = v }
    private var rowsBytes: Long
        get() = pane.rowsBytes
        set(v) { pane.rowsBytes = v }
    private var searchRows: List<Row>?
        get() = pane.searchRows
        set(v) { pane.searchRows = v }
    private var unreadable: Boolean
        get() = pane.unreadable
        set(v) { pane.unreadable = v }
    private var loading: Boolean
        get() = pane.loading
        set(v) { pane.loading = v }
    private var searching: Boolean
        get() = pane.searching
        set(v) { pane.searching = v }

    /** Changes with every move to another place: what a slow read brings back for an old place is dropped. */
    private var placeGen: Int
        get() = pane.placeGen
        set(v) { pane.placeGen = v }

    /** The screen opened without access to the files: they load once it is given. */
    private var needAccess = false
    private val selected get() = pane.selected
    private var loadJob: Job?
        get() = pane.loadJob
        set(v) { pane.loadJob = v }
    private var searchJob: Job?
        get() = pane.searchJob
        set(v) { pane.searchJob = v }
    private var homeJob: Job? = null

    /** When the start page's numbers were last read; [homeDirty]: something may have changed since. */
    private var homeLoadedAt = 0L
    private var homeDirty = false

    /** A file to bring into sight once its folder is read ("Show in folder"). */
    private var focusPath: String?
        get() = pane.focusPath
        set(v) { pane.focusPath = v }
    private val adapter get() = pane.adapter
    private var thumbPx = 0
    private var gridCols = 3
    private var tilePx = 0
    private var tileIconPad = 0

    /** Whether the list on show is drawn as tiles, and how many file rows it has (for "Select all"). */
    private var tiles: Boolean
        get() = pane.tiles
        set(v) { pane.tiles = v }
    private var shownRowCount: Int
        get() = pane.shownRowCount
        set(v) { pane.shownRowCount = v }
    private var menuShown = ""
    private var barShown = ""
    private var navClose: Boolean? = null

    private val secondaryTint by lazy { ColorStateList.valueOf(Ui.textSecondary(this)) }
    private val accentTint by lazy { ColorStateList.valueOf(Ui.accent(this)) }
    private val onContainerTint by lazy { ColorStateList.valueOf(Ui.onAccentContainer(this)) }

    /** Where Swift saves downloads (its own folder when it may not use the phone's Download). */
    private val swiftDownloads: File by lazy { Downloads.directory() }

    private val pathScroll get() = pane.pathScroll
    /**
     * The search box and the path, at the top of the page: they scroll away with the list (2.9.1).
     * 3.0.3: the title bar and the path do; the search box stays.
     */
    private lateinit var appBar: com.google.android.material.appbar.AppBarLayout
    /** How far [appBar] has gone up (0: all on show). */
    private var appBarOffset = 0
    private val crumbs get() = pane.crumbs
    private lateinit var jobStrip: LinearLayout
    private lateinit var jobText: TextView
    private lateinit var jobBar: ProgressBar
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var pasteButton: MaterialButton
    private lateinit var actionBar: LinearLayout
    private lateinit var bottom: LinearLayout

    private val progress = FileJobDialogs.Progress(this)
    private var questionShown: FileJobs.Question? = null
    private var opening: Messages.Handle? = null

    private fun dp(v: Int) = Ui.dp(this, v)

    // ------------------------------------------------------------------ permissions

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) accessGiven() else render()
    }

    /** Asking Android for the right to change files and the SD card (2.9: shared with the cleaner). */
    private val changes = ChangeAccess(this) { say(it, long = true) }

    /**
     * Android's own screen-lock question, before Private opens (2.9). 3.0: it no longer removes a PIN
     * Private has (a PIN keeps Private once chosen); only a reset of a forgotten PIN, after its day's
     * wait, does that, in [afterUnlock].
     */
    private val askUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val run = afterUnlock
        afterUnlock = null
        if (r.resultCode == RESULT_OK) {
            PrivateFolder.unlock()
            run?.invoke()
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

    /** Runs [action] once Swift may change [files] (on an SD card, Android is asked once, for good). */
    private fun withChanges(files: Collection<File>, action: () -> Unit) = changes.withChanges(files, action)

    // ------------------------------------------------------------------ the screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbPx = dp(Ui.ICON_BOX)
        // 3.0.4: two folders side by side on a wide screen turned sideways (the button at the top,
        // remembered); held upright, one: the side worked on last. The left side has the screen's own
        // list, the right side one made like it (buildChrome).
        val config = resources.configuration
        twoOffered = !picking && TwoPanes.offered(config.orientation == Configuration.ORIENTATION_LANDSCAPE, config.screenWidthDp)
        twoPanes = twoOffered && FilesSettings.twoPanes
        chosen = panes[TwoPanes.side(savedInstanceState?.getInt(STATE_SIDE, -1) ?: sp.getInt(PREF_SIDE, TwoPanes.LEFT))]
        panes[0].list = list
        panes[0].empty = empty
        chosen.attached = true
        otherPane().attached = twoPanes
        computeGrid()
        grid = FilesSettings.grid
        thumbsOn = FilesSettings.thumbnails
        settingsShown = settingsKey()
        readAnimationScale()
        if (savedInstanceState != null) {
            homeShown = true
            for (p in panes) p.quietOnce = true
        }
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
            // 3.0.4: what is typed is the search of the side worked on.
            override fun afterTextChanged(s: Editable?) {
                if (syncingSearch) return
                chosen.query = s?.toString().orEmpty()
                withPane(chosen) { scheduleSearch() }
            }
        })
        buildChrome()
        use(chosen)
        for (p in panes) setUpList(p)
        arrangePanes()
        // 3.0.3: videos' watched marks come from two small stores: read off the main thread now, so
        // the rows find them in memory.
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            LocalMedia.progress(app, "", -1)
            Watched.isFinished(app, "", -1)
        }
        onBackPressedDispatcher.addCallback(this, back)
        Volumes.refresh(this)
        needAccess = !hasAccess()
        // 2.9: things in the recycle bin past their time go for good (a quick look at its index).
        if (!needAccess) RecycleBin.purgeSoon(this, force = true)
        // 3.0: unfinished copies a closed Swift left go (said once).
        if (!needAccess && savedInstanceState == null) clearLeftParts()
        // Turned sideways: the same place. Otherwise the folder asked for, or where Files was left
        // (2.9: or the start page, as chosen in Settings). 3.0: a folder asked for by another app only
        // when it may be shown (trustedPlace), otherwise the start page.
        val asked = intent.getStringExtra(EXTRA_FOLDER)?.let { trustedPlace(it) ?: PLACE_HOME }
        val place = savedInstanceState?.getString(STATE_PLACE) ?: asked
            ?: if (!picking && FilesSettings.startAt == FilesSettings.START_LAST) sp.getString(PREF_LAST, null) else null
        // 3.0.4: the other side: where it was left (kept while one folder showed), or the same folder.
        val other = otherPane()
        val otherPlace = savedInstanceState?.getString(STATE_OTHER)
            ?: if (FilesSettings.startAt == FilesSettings.START_LAST) sp.getString(PREF_OTHER, null) else null
        other.pending = otherPlace
        other.remembered = otherPlace
        val places = TwoPanes.places(chosen.side, place, otherPlace, twoPanes)
        // Without access Android hides the folders (they can't even be checked): there once it is given.
        if (needAccess) wanted = place
        goTo(place)
        if (twoPanes) withPane(other) {
            val there = otherStart(places[other.side])
            if (needAccess) wanted = there
            goTo(there)
        }
        if (needAccess && Build.VERSION.SDK_INT < 30) requestAccess()
    }

    /**
     * 3.0: a place another app asked Files to open (Files' own icon can be started by any app, with
     * any folder): only on the phone's storage or a card, in Swift's download folder, or in Private
     * (which still asks to be unlocked). Never Swift's own data (its settings with Private's PIN, the
     * engine's profile), which Files could then copy or share; null for anything else.
     */
    private fun trustedPlace(raw: String): String? {
        if (!raw.startsWith("/")) return raw.takeIf { it == PLACE_HOME || it == PLACE_BIN || it.startsWith(CATEGORY) }
        val f = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        // Private and Swift's downloads as Files knows them (their real paths may be named otherwise).
        fun within(root: File): String? {
            val r = runCatching { root.canonicalPath }.getOrNull() ?: return null
            return when {
                f.path == r -> root.path
                f.path.startsWith("$r/") -> File(root, f.path.removePrefix("$r/")).path
                else -> null
            }
        }
        within(PrivateFolder.root(this))?.let { return it }
        within(swiftDownloads)?.let { return it }
        return when {
            AppFiles.isPrivate(this, f) -> null
            Volumes.of(this, f) != null -> f.path
            else -> null
        }
    }

    /** 3.0: unfinished copies a closed Swift left behind (see Parts) go, and that is said once. */
    private fun clearLeftParts() {
        if (FileJobs.active.isNotEmpty()) return
        val app = applicationContext
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Parts.clearLeftovers(app) }.getOrDefault(0) }
            if (n > 0) say("A copy was cut off when Swift closed. Its unfinished file was cleared away; the original wasn't touched.", long = true)
        }
    }

    /** Where to go once access to the files is given. */
    private var wanted: String?
        get() = pane.wanted
        set(v) { pane.wanted = v }

    /** Access was given: the place asked for, or the one on show, now with its files. */
    private fun accessGiven() {
        needAccess = false
        Volumes.refresh(this)
        clearLeftParts()
        for (p in shownPanes()) withPane(p) {
            val place = wanted ?: placeKey()
            wanted = null
            goTo(place)
        }
    }

    /**
     * Shows a remembered place: a folder's path (a file's own folder for a file's), a category
     * ("cat:images") or the start page (also when the folder is gone).
     */
    private fun goTo(place: String?) {
        val cat = place?.takeIf { it.startsWith(CATEGORY) }?.let { FileIndex.Category.of(it.removePrefix(CATEGORY)) }
        // 2.9: a folder in Private (a screen turned sideways, "Show in Files" from a file of it) asks to unlock over the start page.
        val private = place?.takeIf { it.startsWith("/") }?.let { File(it) }?.takeIf { PrivateFolder.isInside(this, it) }
        // "/sdcard/Download" is a link to the phone's storage: its real path, so Back can go up it a folder at a time.
        val path = place?.takeIf { it.startsWith("/") && private == null }?.let { File(it) }?.let { f ->
            if (Volumes.of(this, f) == null) runCatching { f.canonicalFile }.getOrDefault(f) else f
        }
        val folder = path?.takeIf { it.isDirectory } ?: path?.takeIf { it.isFile }?.parentFile
        when {
            place == PLACE_BIN -> openBin()
            private != null -> {
                showHome()
                openPrivate(private.takeIf { it.isDirectory } ?: private.parentFile)
            }
            cat != null -> openCategory(cat)
            folder != null -> openFolder(folder)
            else -> showHome()
        }
    }

    /** The place on show, as remembered: a folder's path, "cat:" and a category, "bin", or "home". */
    private fun placeKey(): String {
        val cat = category
        return when {
            home -> PLACE_HOME
            inBin -> PLACE_BIN
            cat != null -> CATEGORY + cat.key
            else -> dir.path
        }
    }

    /** A folder of Private's is on show (2.9). */
    private val inPrivate get() = pane.inPrivate

    /** Files' options as they are now, to tell when Settings changed them. */
    private fun settingsKey() = listOf(
        FilesSettings.grid, FilesSettings.showHidden, FilesSettings.thumbnails, FilesSettings.sort, FilesSettings.categorySort, FilesSettings.binDays,
        FilesSettings.twoPanes,
    ).joinToString("|")

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PLACE, withPane(chosen) { placeKey() })
        // 3.0.4: which side was worked on, and where the other one is (on show or not): turned upright,
        // the side worked on shows alone; turned back, both come back.
        outState.putInt(STATE_SIDE, chosen.side)
        otherPlaceKey()?.let { outState.putString(STATE_OTHER, it) }
    }

    private val jobListener: () -> Unit = { onJobsChanged() }

    private var started = false

    // ------------------------------------------------------------------ movement (2.9)

    /** Android's "Remove animations" (its animator scale at 0) turns Files' movement off too. */
    private var animationsOn = true

    /** How the next place comes in: from the right (deeper), the left (back up), or a short fade. */
    private var pendingSlide: Int
        get() = pane.pendingSlide
        set(v) { pane.pendingSlide = v }

    /** The start page has shown once (its cards come in one after another only then). */
    private var homeShown = false

    /** The screen was made again (turned sideways): what it shows first doesn't come in again. */
    private var quietOnce: Boolean
        get() = pane.quietOnce
        set(v) { pane.quietOnce = v }

    private fun readAnimationScale() {
        animationsOn = try {
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (_: Exception) {
            true
        }
    }

    /**
     * The list (one view, drawn through one layer while it moves: cheap on a slow graphics chip)
     * comes in from the side it was reached from, 3.0.5: the same change as anywhere in Swift
     * (Motion.pageIn), or, with no side to come from, fades and rises a little.
     */
    private fun slideList(direction: Int) {
        if (!animationsOn || direction == 0) return
        if (direction != SLIDE_FADE) {
            Motion.pageIn(list, direction)
            return
        }
        list.animate().cancel()
        list.alpha = 0f
        list.translationX = 0f
        list.translationY = Ui.dpf(this, 8f)
        list.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(Motion.PAGE_MS).setInterpolator(Motion.page).withLayer().start()
    }

    /** The start page's first cards, one after another (the few on screen, never a long list). */
    private fun stagger() {
        if (!animationsOn) return
        // Once the cards are laid out, just before they are first drawn (3.0.4: this side's list).
        val rv = list
        rv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                rv.viewTreeObserver.removeOnPreDrawListener(this)
                Motion.stagger(rv, step = 28, max = 8)
                return true
            }
        })
    }

    /** List ↔ grid (or anything else laid out anew: the sort order, two folders): the new layout fades in over a moment. */
    private fun crossFade(v: View = list, ms: Long = Motion.NORMAL) {
        if (!animationsOn) return
        v.animate().cancel()
        v.alpha = 0.15f
        Motion.anim(v, ms, Motion.standard).alpha(1f).withLayer().start()
    }

    override fun onStart() {
        super.onStart()
        visible = WeakReference(this)
        FileJobs.addScreen(jobListener)
        onJobsChanged()
        // 2.9: Private locks after a minute out of sight; if it was on show, it asks to be unlocked again.
        // 3.0.4: every side showing it goes to its start page first; then the side worked on asks.
        PrivateFolder.back()
        if (!PrivateFolder.isUnlocked && afterUnlock == null) {
            var again: File? = null
            for (p in shownPanes()) withPane(p) {
                if (inPrivate) {
                    if (p === chosen) again = dir
                    showHome()
                }
            }
            again?.let { openPrivate(it) }
        }
        if (started) {
            readAnimationScale()
            RecycleBin.purgeSoon(this)
            applySettings()
            for (p in shownPanes()) withPane(p) { pruneGone() }
            // 3.0.3: back from the player: the videos' pictures show how much is watched now.
            for (p in shownPanes()) p.adapter.notifyItemRangeChanged(0, p.adapter.itemCount, PAYLOAD_WATCHED)
        }
        started = true
    }

    override fun onStop() {
        FileJobs.removeScreen(jobListener)
        if (visible?.get() === this) visible = null
        // Files may come and go while Swift is out of sight: the start page counts again on return.
        homeDirty = true
        // 2.9: leaving Files locks Private at once; a file of it opened in another screen gives it a minute.
        if (!isChangingConfigurations) {
            if (SystemClock.elapsedRealtime() < privateGraceUntil) PrivateFolder.away() else PrivateFolder.lock()
            privateGraceUntil = 0L
        }
        super.onStop()
    }

    /** Settings changed Files' options while it was out of sight: the list follows them. */
    private fun applySettings() {
        val key = settingsKey()
        if (key == settingsShown) return
        settingsShown = key
        grid = FilesSettings.grid
        thumbsOn = FilesSettings.thumbnails
        menuShown = ""
        // 3.0.4: two folders turned on or off in Settings meanwhile; then each side follows the options.
        setTwoPanes(twoOffered && FilesSettings.twoPanes)
        for (p in shownPanes()) withPane(p) {
            if (index == null) {
                if (!home && category == null && !inBin) listed = sortedWithSizes(listed, sort)
                else if (category != null) listed = FileOps.sorted(listed, categorySort)
                rows = listed.map { rowOf(it) }
            }
            applyLayout()
            render()
            if (!home && category == null && !inBin && index == null) load(keepScroll = true)
        }
    }

    /**
     * Files deleted elsewhere while Files was out of sight (a picture deleted in the viewer, another
     * app) leave the list on show before anyone taps them, and Android's media index forgets them.
     */
    private fun pruneGone() {
        if (index != null || home || loading) return
        val shownNow = rows
        if (shownNow.isEmpty()) return
        val gen = placeGen
        val app = applicationContext
        val fromIndex = category != null
        val p = pane
        lifecycleScope.launch {
            val gone = withContext(Dispatchers.IO) {
                shownNow.mapNotNull { r -> r.file?.takeIf { !it.exists() } }.also { g -> if (fromIndex) FileOps.forgetPaths(app, g.map { it.path }) }
            }
            if (gen != p.placeGen || gone.isEmpty()) return@launch
            withPane(p) { dropRows(gone, quietly = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's settings with access now, or a card put in.
        if (needAccess && hasAccess()) {
            accessGiven()
        } else if (homeDirty && shownPanes().any { it.home }) {
            loadHome()
        }
    }

    override fun onDestroy() {
        progress.dismiss()
        opening?.dismiss()
        // 3.0.5: leaving Files lets go of its small pictures (up to 12 MB, kept while browsing before).
        if (isFinishing) FileThumbs.trim()
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
                // 3.0: an archive being read (a big tar.gz takes a while): the reading stops.
                archiveReading != null -> stopReadingArchive()
                query.isNotEmpty() -> clearQuery()
                selected.isNotEmpty() -> clearSelection()
                idx != null && inArchive.isNotEmpty() -> showArchiveFolder(inArchive.substringBeforeLast('/', ""))
                idx != null -> leaveArchive()
                home -> finish()
                category != null || inBin -> showHome()
                else -> upFolder()?.let { openFolder(it) } ?: showHome()
            }
        }
    }

    /** The folder above, while still on the same storage (or in Private). */
    private fun upFolder(): File? {
        if (PrivateFolder.isInside(this, dir)) return if (dir.path == PrivateFolder.root(this).path) null else dir.parentFile
        val v = Volumes.of(this, dir) ?: return null
        if (dir.path == v.root.path) return null
        return dir.parentFile
    }

    private fun buildChrome() {
        val box = panes[0].list.parent as ViewGroup
        val column = box.parent as LinearLayout
        // 2.9.1: the search box and the path belong to the top of the page: scrolling down takes them
        // away with the list (more room for files), and they come back once the list is at its top.
        // The list only moves by an offset as they go, so scrolling costs no new layout.
        // 3.0.3: the title bar stays where it is; scrolling down takes the search box away, and the path
        // (with Home) moves up under the title bar and stays there, as part of the header.
        val searchBox = search.parent as View
        val at = column.indexOfChild(searchBox)
        column.removeView(searchBox)
        column.removeView(box)
        // 3.0.4: the right side's list and empty screen, made as activity_list makes the left side's.
        panes[0].box = box
        val right = panes[1]
        right.list = RecyclerView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
            isFocusableInTouchMode = true
            // 3.0.5: the glow at its ends in the accent chosen, as the left side's has.
            Palette.glow(this)
        }
        right.empty = FrameLayout(this).apply { visibility = View.GONE }
        right.box = MaxWidthLayout(this).apply {
            addView(right.list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(right.empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        for (p in panes) {
            p.crumbs = PathRow.crumbs(this)
            p.pathScroll = PathRow.scroll(this, p.crumbs)
            // 3.0.4: a thin accent line along the top of the side worked on (two sides showing).
            p.mark = View(this).apply {
                setBackgroundColor(Ui.accent(this@FilesActivity))
                visibility = View.GONE
            }
            p.pathFrame = SideFrame(this) { setChosen(p) }.apply {
                addView(p.pathScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(p.mark, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP))
            }
            p.frame = SideFrame(this) { setChosen(p) }.apply {
                addView(p.box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        appBar = com.google.android.material.appbar.AppBarLayout(this).apply {
            background = null
            stateListAnimator = null
            elevation = 0f
            addOnOffsetChangedListener { _, offset -> appBarOffset = offset }
        }
        appBar.addView(searchBox, com.google.android.material.appbar.AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            scrollFlags = com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
        })
        // No scroll flags, so it stays once the search box has gone. (Made in code, AppBarLayout's
        // LayoutParams scroll by default: unlike from XML, the flag has to be cleared.) 3.0.4: the row
        // of paths: one side's, or both sides' side by side.
        appBar.addView(pathRow, com.google.android.material.appbar.AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)).apply { scrollFlags = 0 })
        // Only a list longer than the room under them takes them away (an empty or short folder keeps them).
        val topBehavior = object : com.google.android.material.appbar.AppBarLayout.Behavior() {
            override fun onStartNestedScroll(parent: androidx.coordinatorlayout.widget.CoordinatorLayout, child: com.google.android.material.appbar.AppBarLayout, directTargetChild: View, target: View, nestedScrollAxes: Int, type: Int) =
                (appBarOffset != 0 || listOverflows(target as? RecyclerView ?: list)) && super.onStartNestedScroll(parent, child, directTargetChild, target, nestedScrollAxes, type)
        }
        topBehavior.setDragCallback(object : com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback() {
            override fun canDrag(appBarLayout: com.google.android.material.appbar.AppBarLayout) = appBarOffset != 0 || listOverflows()
        })
        page = androidx.coordinatorlayout.widget.CoordinatorLayout(this)
        page.addView(appBar, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { behavior = topBehavior })
        // 3.0.4: the row of lists, one side's or both, under the paths (arrangePanes).
        page.addView(listRow, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        })
        column.addView(page, at, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Palette.color(this@FilesActivity, R.color.sheet_bg))
        }
        bottomBar = BottomBar(bottom)
        // 3.0.5: no line above it: its own colour against the page's says where it begins.
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
            Ui.steadyFigures(this)
        }
        jobBar = FileJobDialogs.bar(this)
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

    /** A button of the bar at the bottom: an icon over a short word. */
    private fun barButton(icon: Int, label: String, danger: Boolean = false, lines: Int = 1, onClick: () -> Unit): View =
        BottomBar.button(this, icon, label, danger, lines, onClick)

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
        stopSizing()
        loading = false
        searching = false
        home = false
        category = null
        inBin = false
        binItems = emptyMap()
        index = null
        inArchive = ""
        archiveReading = null
        focusPath = null
        selected.clear()
        searchRows = null
        clearQuery()
    }

    /** Shows [to] (leaving an archive, a category or a search), remembered for next time. */
    private fun openFolder(to: File, remember: Boolean = true) {
        // 2.9: Private's folders only once it is unlocked, and never remembered as where Files was left.
        val private = PrivateFolder.isInside(this, to)
        if (private && !PrivateFolder.isUnlocked) {
            openPrivate(to)
            return
        }
        // 2.9: a folder below comes in from the right, one above from the left.
        val from = dir.takeIf { !home && category == null && !inBin && index == null }
        pendingSlide = when {
            home || category != null || inBin -> SLIDE_FORWARD
            from == null -> SLIDE_FADE
            from.path == to.path -> 0
            to.path.startsWith(from.path + "/") -> SLIDE_FORWARD
            from.path.startsWith(to.path + "/") -> SLIDE_BACK
            else -> SLIDE_FADE
        }
        leavePlace()
        dir = to
        if (remember) rememberPlace(if (private) PLACE_HOME else to.path)
        if (private) PrivateFolder.tidyOrigins(this)
        load()
    }

    /** The recycle bin (2.9): what was deleted, newest first, with the days each has left. */
    private fun openBin() {
        pendingSlide = SLIDE_FORWARD
        leavePlace()
        inBin = true
        rememberPlace(PLACE_BIN)
        loadBin()
    }

    /** The start page (2.8): storages, categories, favourite folders and recent files. */
    private fun showHome() {
        pendingSlide = SLIDE_BACK
        leavePlace()
        home = true
        rememberPlace(PLACE_HOME)
        render()
        restorePosition()
        if (homeDirty || homeCache == null || SystemClock.elapsedRealtime() - homeLoadedAt > HOME_FRESH_MS) loadHome()
    }

    /** One category's files from all over the phone (2.8), newest first unless sorted otherwise. */
    private fun openCategory(cat: FileIndex.Category) {
        pendingSlide = SLIDE_FORWARD
        leavePlace()
        category = cat
        rememberPlace(CATEGORY + cat.key)
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
        showPageTop(p <= 0)
    }

    /**
     * The search box on show again, as at the top of the page (2.9.1); with [atTop] false, gone as
     * when scrolled down to where the list now is. (3.0.3: the path stays either way.)
     */
    private fun showPageTop(atTop: Boolean = true) {
        // 3.0.4: the search box is above both sides: only the side worked on moves it.
        if (pane !== chosen) return
        if ((appBarOffset == 0) != atTop) appBar.setExpanded(atTop, false)
    }

    /** The list [rv] goes on past the bottom of the screen (asked once a swipe starts, not as it moves). */
    private fun listOverflows(rv: RecyclerView = list): Boolean {
        if (rv.canScrollVertically(1) || rv.canScrollVertically(-1)) return true
        var bottom = 0
        for (i in 0 until rv.childCount) bottom = maxOf(bottom, rv.getChildAt(i).bottom)
        if (bottom == 0) return false
        return topInPage(rv) + bottom > page.height
    }

    /** 3.0.4: how far down the page (under the title bar) [v]'s top is now: a side's list sits in the row of lists. */
    private fun topInPage(v: View): Int {
        var y = 0
        var at: View = v
        while (at !== page) {
            y += at.top
            at = at.parent as? View ?: break
        }
        return y
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
        val p = pane
        loading = true
        if (!keepScroll) showLoadingSoon("Opening the folder…")
        renderPath()
        updateChrome()
        loadJob = lifecycleScope.launch {
            var found = withContext(Dispatchers.IO) { FileOps.list(target, hidden, order) }
            if (gen != p.placeGen) return@launch
            // 3.0.4: sorted by size, the folders wait a moment for theirs (most take less), then go by
            // them; any still being measured go last and take their places once all are known.
            if (order == FileOps.SORT_SIZE && found != null) {
                val folders = found.filter { it.isDir }.map { it.file.path }
                val sizing = withPane(p) { startSizing(folders) }
                if (sizing != null) {
                    withPane(p) { updateLoadingText("Measuring the folders…") }
                    withTimeoutOrNull(SIZE_WAIT_MS) { sizing.join() }
                    if (gen != p.placeGen) return@launch
                }
                val all = found
                found = withContext(Dispatchers.Default) { sortedWithSizes(all, order) }
            }
            withPane(p) { showRead(found, keepScroll) }
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
        val p = pane
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
            if (gen != p.placeGen) return@launch
            withPane(p) { showRead(found, keepScroll) }
        }
    }

    /** Reads the recycle bin off the main thread (its index on each storage). */
    private fun loadBin(keepScroll: Boolean = false) {
        if (!hasAccess()) {
            render()
            return
        }
        loadJob?.cancel()
        val gen = placeGen
        val app = applicationContext
        val p = pane
        loading = true
        if (!keepScroll) showLoadingSoon("Opening the recycle bin…")
        renderPath()
        updateChrome()
        loadJob = lifecycleScope.launch {
            val (items, found) = withContext(Dispatchers.IO) {
                val all = RecycleBin.list(app)
                all to all.map { b ->
                    val where = if (b.from == null) "a folder no longer known" else FileIndex.placeOf(app, b.home)
                    FileItem(b.file, b.name, b.isDir, b.size, b.deletedAt, if (b.isDir) b.files else -1, FileKind.of(b.name, b.isDir), where)
                }
            }
            if (gen != p.placeGen) return@launch
            withPane(p) {
                binItems = items.associateBy { it.file.path }
                showRead(found, keepScroll)
            }
        }
    }

    /** A folder or a category was read: its rows show. */
    private fun showRead(found: List<FileItem>?, keepScroll: Boolean) {
        loading = false
        if (!keepScroll) touchedSinceShown = false
        unreadable = found == null
        listed = found.orEmpty()
        rows = listed.map { rowOf(it) }
        rowsBytes = listed.sumOf { it.size.coerceAtLeast(0) }
        // 3.0.4: what is selected and no longer there (the other side deleted or moved it) is selected no more.
        if (selected.isNotEmpty() && searchRows == null) {
            val keys = rows.mapTo(HashSet()) { it.key }
            selected.keys.retainAll(keys)
        }
        render()
        if (!keepScroll) restorePosition()
        focusPath?.let { p ->
            focusPath = null
            val i = rows.indexOfFirst { it.file?.path == p }
            if (i >= 0) (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(i, list.height / 3)
        }
        if (searchRows != null || ((category != null || inBin) && query.isNotBlank())) runSearch()
    }

    /** Reads the place on show again after a change, keeping where the list was. */
    private fun reload() {
        when {
            index != null -> {}
            home -> loadHome()
            inBin -> loadBin(keepScroll = true)
            category != null -> loadCategory(keepScroll = true)
            else -> load(keepScroll = true)
        }
    }

    /**
     * Takes [files] off the list on show at once (2.9: deleted, or found gone), before the place is
     * read again; [quietly] when nothing else follows (the list isn't read again).
     */
    private fun dropRows(files: Collection<File>, quietly: Boolean = false) {
        val paths = files.map { it.path }.toHashSet()
        val keep = { r: Row -> r.file?.path !in paths }
        rows = rows.filter(keep)
        listed = listed.filter { it.file.path !in paths }
        rowsBytes = listed.sumOf { it.size.coerceAtLeast(0) }
        searchRows = searchRows?.filter(keep)
        paths.forEach { selected.remove(it) }
        homeCache?.let { c ->
            homeCache = c.copy(favourites = c.favourites.filter(keep), recent = c.recent?.filter(keep), continued = c.continued.filter { it.localPath == null || it.localPath !in paths })
        }
        render(removals = true)
        if (quietly) L.d("Files: ${paths.size} gone meanwhile, taken off the list")
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
            var docs: List<Docs.Doc> = emptyList()
            var continued: List<Unfinished.Item> = emptyList()
            val (storages, favourites, bin) = withContext(Dispatchers.IO) {
                // 3.0.4: with what fills each storage as measured last (while Swift runs), until it is again below.
                val cards = Volumes.refresh(app).map { v -> v.space().let { (free, total) -> StorageCard(v, free, total, StorageKinds.cached(v.root)) } }
                val favs = Favourites.paths().map { File(it) }.filter { it.isDirectory && !PrivateFolder.isInside(app, it) }
                    .map { rowOf(FileItem.of(it, count = true, where = FileIndex.placeOf(app, it.parentFile))) }
                docs = recentDocuments()
                continued = continueItems(app)
                Triple(cards, favs, RecycleBin.summaryNow(app))
            }
            val before = homeCache
            homeCache = HomeData(storages, favourites, before?.counts, before?.recent, bin, docs, continued)
            // 3.0.4: on each side showing its start page.
            renderHomes()
            // 3.0.4: what fills each storage (pictures, videos, music, documents), for its bar: one
            // quick question to Android's media index for each. Shown as it comes, kept for next time.
            val kinds = withContext(Dispatchers.IO) { storages.associate { it.volume.root.path to StorageKinds.measure(app, it.volume.root) } }
            homeCache?.let { c ->
                val fresh = c.storages.map { s -> kinds[s.volume.root.path]?.takeIf { it != s.kinds }?.let { StorageCard(s.volume, s.free, s.total, it) } ?: s }
                if (fresh.indices.any { fresh[it] !== c.storages[it] }) {
                    homeCache = c.copy(storages = fresh)
                    renderHomes()
                }
            }
            val overview = withContext(Dispatchers.IO) { FileIndex.overview(app) { !isActive } }
            // 3.0.3: what changed meanwhile (a card taken off, a folder deleted) stays as it is now.
            homeCache = homeCache?.copy(counts = overview.counts, recent = overview.recent.map { rowOf(it) })
            renderHomes()
        }
    }

    /** 3.0.3: Continue's cards: the phone's videos and songs left part-way in Swift's player. Off the main thread. */
    private fun continueItems(app: Context): List<Unfinished.Item> = try {
        Unfinished.forFiles(app)
    } catch (e: Exception) {
        L.w("Files: Continue couldn't be read", e)
        emptyList()
    }

    /** The documents opened in Swift lately (2.9), but not files deleted since. Off the main thread. */
    private fun recentDocuments(): List<Docs.Doc> = try {
        SwiftApp.db.recentDocuments(Profiles.currentId, HOME_DOCS).filter { d ->
            val u = Uri.parse(d.uri)
            !(u.scheme == "file" && !File(u.path.orEmpty()).exists())
        }
    } catch (e: Exception) {
        L.w("Files: recent documents couldn't be read", e)
        emptyList()
    }

    private fun homeItems(): List<Any> {
        val data = homeCache
        val out = ArrayList<Any>()
        out += Header("Storage")
        if (data != null) out.addAll(data.storages) else Volumes.all(this).forEach { out += StorageCard(it, -1, -1) }
        // 3.0.3: Storage, Categories, Tools, Favourites, Continue, Recent documents, Recent files.
        out += Header("Categories")
        out += CategoryGrid(data?.counts)
        // Picking a file for a website: only the ways to files (not the tools, Private or the bin).
        if (!picking) {
            out += Header("Tools")
            out += ToolGrid(data?.bin)
        }
        if (data != null) {
            if (!picking || data.favourites.isNotEmpty()) out += Header("Favourites")
            if (data.favourites.isNotEmpty()) out.addAll(data.favourites)
            else if (!picking) out += Hint("Keep the folders you use most here: tap ⋮ next to a folder, then Add to favourites.")
            // 3.0.3: videos and songs left part-way in Swift's player, to carry on (none: no heading either).
            if (!picking && data.continued.isNotEmpty()) {
                out += Header("Continue")
                out += ContinueCards(data.continued)
            }
            // 2.9: Files is where documents are found: the ones read lately, to carry on.
            if (!picking && data.docs.isNotEmpty()) {
                out += Header("Recent documents", "Show all") { openCategory(FileIndex.Category.DOCUMENTS) }
                out += RecentDocs(data.docs)
            }
        }
        val recent = data?.recent?.let { stillThere(it) }
        if (recent != null) {
            out += Header("Recent files", if (recent.size > HOME_RECENT) "Show all" else null) { openCategory(FileIndex.Category.RECENT) }
            if (recent.isEmpty()) out += Hint(FileIndex.Category.RECENT.hint) else out.addAll(recent.take(HOME_RECENT))
        }
        return out
    }

    /**
     * 2.9: the start page shows what it read last time at once, and files may have been deleted since
     * (in another app, or here): those leave before they are shown (a look at the few on show), and
     * Android's media index forgets them, so the next reading doesn't bring them back.
     */
    private fun stillThere(recent: List<Row>): List<Row> {
        val out = ArrayList<Row>(recent.size)
        val gone = ArrayList<String>()
        for (r in recent) {
            val f = r.file
            // Only the ones that will show are looked at (and one more, for "Show all").
            if (out.size > HOME_RECENT || f == null || f.exists()) out += r else gone += f.path
        }
        if (gone.isNotEmpty()) {
            homeCache?.let { c -> homeCache = c.copy(recent = c.recent?.filter { it.file?.path !in gone }) }
            val app = applicationContext
            lifecycleScope.launch(Dispatchers.IO) { FileOps.forgetPaths(app, gone) }
        }
        return out
    }

    private fun rowOf(it: FileItem) = Row(it.file.path, it.name, it.isDir, it.size, it.time, it.count, it.kind, it.file, null, it.where)

    private fun rowOf(e: Archives.Entry, idx: Archives.Index, where: String? = null) =
        Row("a:" + e.path, e.name, e.isDir, e.size, e.time, if (e.isDir) idx.count(e.path) else -1, FileKind.of(e.name, e.isDir), null, e, where)

    /** The loading screen, once a place takes more than a moment to read (3.0.4: each side its own, see Pane). */
    private fun showLoadingNow() {
        if (loading) {
            empty.removeAllViews()
            val label = Ui.text(this@FilesActivity, Ui.Type.BODY_SECONDARY, loadingText).apply {
                setPadding(0, Ui.dp(this@FilesActivity, 12), 0, 0)
                // 3.0.5: "Reading… 45%" counts up here.
                Ui.steadyFigures(this)
            }
            empty.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(LoadingIndicator(this@FilesActivity))
                addView(label)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            empty.visibility = View.VISIBLE
            adapter.set(emptyList())
            loadingLabel = label
        }
    }
    private val showLoading get() = pane.showLoading
    private var loadingText: String
        get() = pane.loadingText
        set(v) { pane.loadingText = v }

    /** 3.0: the loading screen's words, while it shows (a tar.gz's reading says how far it is). */
    private var loadingLabel: TextView?
        get() = pane.loadingLabel
        set(v) { pane.loadingLabel = v }

    private fun updateLoadingText(text: String) {
        loadingText = text
        loadingLabel?.takeIf { loading && it.isAttachedToWindow }?.text = text
    }

    /** 3.0: Back while an archive is read: the reading stops, and the folder stays. */
    private fun stopReadingArchive() {
        archiveReading = null
        loadJob?.cancel()
        loading = false
        render()
        restorePosition()
    }

    private fun showLoadingSoon(text: String) {
        loadingText = text
        list.removeCallbacks(showLoading)
        list.postDelayed(showLoading, 250)
    }

    private fun render(removals: Boolean = false) {
        list.removeCallbacks(showLoading)
        val access = hasAccess()
        val everything: List<Any> = when {
            !access && index == null -> emptyList()
            else -> searchRows ?: when {
                index != null -> archiveRows()
                home -> homeItems()
                // 2.9: a line at the top of the bin and of Private says how they work.
                inBin && rows.isNotEmpty() -> listOf(Hint("Things you delete stay here for ${com.swiftbrowser.files.BinClock.daysText(FilesSettings.binDays)}, then go for good. Tap one to restore it.")) + rows
                inPrivate && dir.path == PrivateFolder.root(this).path && rows.isNotEmpty() -> listOf(Hint(PRIVATE_HINT)) + rows
                category == FileIndex.Category.DOCUMENTS && rows.isNotEmpty() -> documentRows()
                else -> rows
            }
        }
        // Picking: only the files the page takes (and the folders to reach them by).
        val shown = pickRequest?.let { p -> everything.filter { it !is Row || it.isDir || FilePick.accepts(p, it.name) } } ?: everything
        shownRowCount = if (onStartPage) 0 else shown.count { it is Row }
        applyLayout()
        // Things put back (Undo) come back as gently as they left.
        val additions = pane.undone && !loading
        if (additions) pane.undone = false
        adapter.set(shown, removals, additions)
        if (shown.isEmpty()) showPageTop()
        // 2.9: a new place comes in from the side it was reached from; the start page's cards, the first time, one after another.
        if (!loading && pendingSlide != 0) {
            val slide = pendingSlide
            pendingSlide = 0
            when {
                // Turned sideways: the same place, without coming in again.
                quietOnce -> quietOnce = false
                onStartPage && !homeShown -> stagger()
                else -> slideList(slide)
            }
        }
        if (onStartPage) homeShown = true
        renderPath()
        updateChrome()
        val cat = category
        val q = query.trim()
        when {
            !access && index == null -> showNoAccess()
            loading && shown.isEmpty() -> {}
            searchRows != null -> showEmpty(
                shown.isEmpty() && !searching, "Nothing found",
                when {
                    home -> "No file or folder on your phone has “$q” in its name."
                    cat != null -> "No file here has “$q” in its name."
                    else -> "No file or folder here has “$q” in its name."
                },
                R.drawable.ic_search,
            )
            index != null -> showEmpty(shown.isEmpty(), "Nothing in here", null, R.drawable.ic_archive)
            home -> empty.visibility = View.GONE
            inBin -> showEmpty(shown.isEmpty(), "The recycle bin is empty", "Files you delete in Files stay here for ${com.swiftbrowser.files.BinClock.daysText(FilesSettings.binDays)}, so you can bring them back.", R.drawable.ic_files_bin, EmptyArt.BIN)
            inPrivate && dir.path == PrivateFolder.root(this).path -> showEmpty(
                shown.isEmpty(), "Nothing in Private yet",
                "Select files or folders anywhere in Files, then tap More → Move to Private. Only you can open them here.\n\n" + PrivateLock.UNINSTALL_TEXT,
                R.drawable.ic_lock, EmptyArt.PRIVATE,
            )
            cat != null -> showEmpty(shown.isEmpty(), cat.nothing, cat.hint, cat.icon)
            unreadable -> showEmpty(true, "Swift can't open this folder", "Android keeps it private, or it is no longer there.", R.drawable.ic_folder)
            else -> showEmpty(shown.isEmpty(), "This folder is empty", if (!showHidden) "Hidden files (names starting with a dot) aren't shown." else null, R.drawable.ic_folder, EmptyArt.FOLDER)
        }
        // 3.0.4: the folders on show are measured (FolderSizes).
        measureShown()
    }

    /**
     * 2.9: Files is where documents are found: their category picks a kind (PDF, Word, Excel,
     * PowerPoint, e-books, text), offering only the kinds the phone has. Newest first, as the rest.
     */
    private fun documentRows(): List<Any> {
        val groups = rows.map { r -> Docs.kindOf(r.name, null)?.let { k -> DocGroup.entries.firstOrNull { k in it.kinds } } }
        val present = DocGroup.entries.filter { it in groups }
        if (present.size < 2) return rows
        val chosen = present.firstOrNull { it == docGroup }
        val out = ArrayList<Any>(rows.size + 1)
        out += DocFilter(listOf("All") + present.map { it.label }, if (chosen == null) 0 else present.indexOf(chosen) + 1)
        if (chosen == null) out.addAll(rows) else rows.forEachIndexed { i, r -> if (groups[i] == chosen) out += r }
        return out
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
            EmptyArt.FOLDER,
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
            updatePathRow()
            return
        }
        pathScroll.visibility = View.VISIBLE
        updatePathRow()
        val parts = ArrayList<Pair<String, () -> Unit>>()
        val cat = category
        val v = Volumes.of(this, dir)
        when {
            home -> {}
            inBin -> parts += "Recycle bin" to { loadBin(keepScroll = true) }
            cat != null -> parts += cat.label to { leaveArchive() }
            inPrivate -> {
                val root = PrivateFolder.root(this)
                parts += "Private" to { openFolder(root) }
                var at = root
                for (p in dir.path.removePrefix(root.path).split('/').filter { it.isNotEmpty() }) {
                    at = File(at, p)
                    val target = at
                    parts += p to { openFolder(target) }
                }
            }
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
        PathRow.show(crumbs, pathScroll, R.drawable.ic_home, "Files start page", tooltip = false, onHome = { showHome() }, parts = parts)
    }

    /** 3.0.4: the row of paths shows while a side has one; with two sides, always (each keeps its half). */
    private fun updatePathRow() {
        val want = twoPanes || shownPanes().any { it.pathScroll.visibility == View.VISIBLE }
        val v = if (want) View.VISIBLE else View.GONE
        if (pathRow.visibility != v) pathRow.visibility = v
    }

    /** The name a folder goes by: "Phone storage" and "SD card" for their tops. */
    private fun folderTitle(f: File): String {
        Volumes.all(this).firstOrNull { it.root.path == f.path }?.let { return it.name }
        if (f.path == PrivateFolder.root(this).path) return "Private"
        if (f.path == swiftDownloads.path && Volumes.of(this, f) == null) return "Swift's downloads"
        return f.name.ifEmpty { f.path }
    }

    // ------------------------------------------------------------------ the toolbar and the bars

    private fun number(n: Int) = String.format(Locale.getDefault(), "%,d", n)

    private fun filesText(n: Int) = if (n == 1) "1 file" else "${number(n)} files"

    /** 3.0: the screen is kept out of screenshots and the recent-apps picture now (Private on show). */
    private var secured = false

    /** 3.0: while Private's files are on show, the screen stays out of screenshots and recent apps. */
    private fun updateSecure() {
        // 3.0.4: on either side.
        val want = shownPanes().any { it.inPrivate }
        if (want == secured) return
        secured = want
        SecureScreen.set(this, want)
    }

    /**
     * 3.0: a copy or move (or an extraction) waiting from Private goes once Private locks: it mustn't
     * take Private's files out unseen. True when one went.
     */
    private fun dropLockedClip(): Boolean {
        if (PrivateFolder.isUnlocked) return false
        val fromPrivate = when (val c = clip) {
            is Clip.Files -> c.files.any { PrivateFolder.isInside(this, it) }
            is Clip.Extract -> PrivateFolder.isInside(this, c.archive)
            null -> false
        }
        if (fromPrivate) clip = null
        return fromPrivate
    }

    /** The title bar, the search box's hint and the bars at the bottom: 3.0.4: the side worked on's, always (the one touched last). */
    private fun updateChrome() = withPane(chosen) { updateChromeNow() }

    private fun updateChromeNow() {
        updateSecure()
        dropLockedClip()
        val idx = index
        val cat = category
        val selecting = selected.isNotEmpty()
        val private = inPrivate
        val hint = when {
            idx != null -> "Search in this archive"
            home -> "Search all your files"
            inBin -> "Search in the recycle bin"
            cat != null -> "Search in ${cat.label}"
            private -> "Search in Private"
            else -> "Search in this folder"
        }
        if (search.hint != hint) search.hint = hint
        updateMenu()
        if (navClose != selecting) {
            navClose = selecting
            TitleBar.swapBackIcon(toolbar, if (selecting) R.drawable.ic_close else R.drawable.ic_back)
        }
        if (selecting) {
            toolbar.title = TitleBar.steady(if (selected.size == 1) "1 selected" else "${number(selected.size)} selected")
            toolbar.subtitle = null
            toolbar.navigationContentDescription = "Leave selection"
        } else {
            toolbar.navigationContentDescription = if (onStartPage) "Close Files" else "Back"
            if (idx != null) {
                toolbar.title = if (inArchive.isEmpty()) idx.file.name else inArchive.substringAfterLast('/')
                toolbar.subtitle = "${idx.format.label} · " + FileJobs.items(idx.entries.count { !it.isDir }).replace("item", "file") + if (idx.encrypted) " · locked" else ""
            } else {
                toolbar.title = when {
                    home && picking -> if (pickRequest?.multiple == true) "Choose files" else "Choose a file"
                    home -> "Files"
                    inBin -> "Recycle bin"
                    cat != null -> cat.label
                    else -> folderTitle(dir)
                }
                toolbar.subtitle = when {
                    searching -> "Searching…"
                    searchRows != null -> FileJobs.items(searchRows!!.size) + " found" + if (searchRows!!.size >= MAX_FOUND) " (the first ones)" else ""
                    loading || home -> null
                    inBin -> if (rows.isEmpty()) null else FileJobs.items(rows.size) + " · " + UrlUtils.formatBytes(rowsBytes)
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
        // 3.0.4: with two folders side by side, things go straight to the other side's folder, named by
        // which way it is ("Copy to the right"): the bar is made again when the side worked on changes.
        val two = twoPanes
        val toward = if (two) otherSideWord() else ""
        val bar = when {
            selecting && picking -> "pick"
            selecting && idx != null -> "entries:" + (oneFile != null) + ":$toward"
            selecting && inBin -> "bin"
            selecting && private -> "private:$toward"
            selecting -> "files:$toward"
            idx != null -> "archive:" + idx.file.path + ":$toward"
            else -> ""
        }
        if (bar != barShown) {
            barShown = bar
            when {
                bar == "pick" -> setActions(listOf(barButton(R.drawable.ic_check, "Choose") { finishPick(selectedFiles()) }))
                selecting && idx != null -> setActions(listOfNotNull(
                    barButton(R.drawable.ic_unarchive, "Extract") { extractSelected(named = true) },
                    if (two) barButton(R.drawable.ic_unarchive, "Extract to the\u00A0$toward", lines = 2) { extractSelectedTo(toOther = true) } else null,
                    barButton(R.drawable.ic_folder, "Extract to…") { extractSelectedTo() },
                    oneFile?.let { barButton(R.drawable.ic_open_new, "Open") { selected.values.singleOrNull()?.entry?.let { e -> openEntry(e) } } },
                    barButton(R.drawable.ic_info, "Details") { entryDetails(selected.values.toList()) },
                ))
                // 2.9: in the recycle bin, things go back or go for good.
                bar == "bin" -> setActions(listOf(
                    barButton(R.drawable.ic_files_restore, "Restore") { restoreFromBin(selectedBinItems()) },
                    barButton(R.drawable.ic_delete, "Delete forever", danger = true) { eraseFromBin(selectedBinItems()) },
                ))
                // 2.9: in Private, "Move out" first (3.0.4: after the other side, with two folders).
                bar.startsWith("private") -> setActions(listOfNotNull(
                    if (two) barButton(R.drawable.ic_copy, "Copy to the\u00A0$toward", lines = 2) { toOtherSide(selectedFiles(), move = false) } else null,
                    if (two) barButton(R.drawable.ic_move, "Move to the\u00A0$toward", lines = 2) { toOtherSide(selectedFiles(), move = true) } else null,
                    barButton(R.drawable.ic_move, "Move out") { moveOut(selectedFiles()) },
                    barButton(R.drawable.ic_share, "Share") { share(selectedFiles()) },
                    barButton(R.drawable.ic_delete, "Delete", danger = true) { delete(selectedFiles()) },
                    barButton(R.drawable.ic_more, "More") { moreSheet() },
                ))
                // 3.0.4: with two folders, Copy and Move are to the other side (to a folder gone to: More).
                selecting && two -> setActions(listOf(
                    barButton(R.drawable.ic_copy, "Copy to the\u00A0$toward", lines = 2) { toOtherSide(selectedFiles(), move = false) },
                    barButton(R.drawable.ic_move, "Move to the\u00A0$toward", lines = 2) { toOtherSide(selectedFiles(), move = true) },
                    barButton(R.drawable.ic_share, "Share") { FileOpener.share(this, selectedFiles()) },
                    barButton(R.drawable.ic_delete, "Delete", danger = true) { delete(selectedFiles()) },
                    barButton(R.drawable.ic_more, "More") { moreSheet() },
                ))
                selecting -> setActions(listOf(
                    barButton(R.drawable.ic_copy, "Copy") { setClip(selectedFiles(), move = false) },
                    barButton(R.drawable.ic_move, "Move") { setClip(selectedFiles(), move = true) },
                    barButton(R.drawable.ic_share, "Share") { FileOpener.share(this, selectedFiles()) },
                    barButton(R.drawable.ic_delete, "Delete", danger = true) { delete(selectedFiles()) },
                    barButton(R.drawable.ic_more, "More") { moreSheet() },
                ))
                idx != null -> setActions(listOfNotNull(
                    barButton(R.drawable.ic_unarchive, "Extract all") { index?.let { i -> extract(i.file, i, null, "", besideArchive(i.file), named = true) } },
                    if (two) barButton(R.drawable.ic_unarchive, "Extract to the\u00A0$toward", lines = 2) { index?.let { i -> extractToOther(i.file, i, null, "") } } else null,
                    barButton(R.drawable.ic_folder, "Extract to…") { index?.let { i -> clipExtract(i.file, i, null, "") } },
                ))
                else -> actionBar.removeAllViews()
            }
        }
        actionBar.visibility = if (bar.isEmpty()) View.GONE else View.VISIBLE
        // Things wait to be put in a folder: only a folder can take them (not the start page, a category or the bin).
        val c = clip
        if (c != null && !selecting && idx == null && !home && cat == null && !inBin) {
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
     * Made again only when they change (a tap that selects one more doesn't rebuild them). 3.0.4:
     * turned sideways on a wide screen (room for the name), Two folders before them.
     */
    private fun updateMenu() {
        val selecting = selected.isNotEmpty()
        val all = selecting && selected.size >= shownRowCount
        val two = if (!twoOffered) "" else if (twoPanes) ":two" else ":one"
        val key = when {
            picking -> if (selecting || onStartPage) "pick" else "pick:$grid"
            selecting -> "select:$all"
            onStartPage -> "home$two"
            else -> "place:$grid$two"
        }
        if (key == menuShown) return
        menuShown = key
        toolbar.menu.clear()
        val color = Ui.text(this)
        when {
            // Picking: only list or grid.
            picking -> if (!selecting && !onStartPage) addMenu(MENU_VIEW, if (grid) "Show as a list" else "Show as a grid", if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view, color)
            selecting -> addTextAction(MENU_SELECT_ALL, if (all) "Clear selection" else "Select all")
            onStartPage -> {
                addTwoPanesButton()
                addMenu(MENU_MORE, "More", R.drawable.ic_more, color)
            }
            else -> {
                addTwoPanesButton()
                addMenu(MENU_VIEW, if (grid) "Show as a list" else "Show as a grid", if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view, color)
                addMenu(MENU_MORE, "More", R.drawable.ic_more, color)
            }
        }
    }

    /** 3.0.4: "Two folders" (a hold says its name), in the accent colour while two show. */
    private fun addTwoPanesButton() {
        if (!twoOffered) return
        toolbar.menu.add(0, MENU_TWO, MENU_SELECT_ALL, "Two folders").apply {
            // 3.0.4: and its icon filled then.
            setIcon(Ui.icon(this@FilesActivity, OnIcons.of(R.drawable.ic_two_panes, twoPanes), if (twoPanes) Ui.accent(this@FilesActivity) else Ui.text(this@FilesActivity)))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            androidx.core.view.MenuItemCompat.setTooltipText(this, "Two folders")
            androidx.core.view.MenuItemCompat.setContentDescription(this, if (twoPanes) "Two folders, on" else "Two folders, off")
        }
    }

    /** The area at the bottom only shows when one of its bars does. */
    private fun updateBottom() {
        // 2.9: the bar slides up from the bottom edge when it comes, and back down when it goes.
        bottomBar.show(jobStrip.visibility == View.VISIBLE || pasteBar.visibility == View.VISIBLE || actionBar.visibility == View.VISIBLE, animationsOn)
    }

    private lateinit var bottomBar: BottomBar

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
        TitleBar.addTextAction(toolbar, id, label) { onMenu(id) }
    }

    private fun onMenu(id: Int) {
        when (id) {
            MENU_VIEW -> toggleGrid()
            MENU_MORE -> optionsSheet()
            MENU_TWO -> toggleTwoPanes()
            MENU_SELECT_ALL -> if (selected.isNotEmpty() && selected.size >= shownRowCount) clearSelection() else selectAll()
        }
    }

    /** List or grid (2.8), remembered; the same file stays at the top. */
    private fun toggleGrid() {
        // 3.0.4: on both sides.
        for (p in shownPanes()) withPane(p) { rememberPosition() }
        grid = !grid
        FilesSettings.grid = grid
        settingsShown = settingsKey()
        for (p in shownPanes()) withPane(p) {
            applyLayout()
            adapter.notifyDataSetChanged()
            restorePosition()
            crossFade()
        }
        updateChrome()
    }

    private fun toggleHidden() {
        val show = !showHidden
        FilesSettings.showHidden = show
        settingsShown = settingsKey()
        say(if (show) "Hidden files (names starting with a dot) show now" else "Hidden files aren't shown now")
        for (p in shownPanes()) withPane(p) { if (!home && category == null && index == null && !inBin) load(keepScroll = true) }
    }

    private fun hiddenAction() = Sheets.Action(
        if (showHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
        if (showHidden) "Hide hidden files" else "Show hidden files",
    ) { toggleHidden() }

    /**
     * 2.9: Swift's settings showing only the file manager's part. Back here, Files reads its
     * options again (onStart) and follows any change at once.
     */
    private fun settingsAction() = Sheets.Action(R.drawable.ic_settings, "Settings") {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TOPIC, "FILES").putExtra("only_topic", true))
    }

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
                // 2.9: the tools, here too.
                actions += Sheets.Action(R.drawable.ic_files_transfer, "Wi-Fi transfer", primary = true) { WifiTransferActivity.open(this) }
                actions += Sheets.Action(R.drawable.ic_files_clean, "Free up space", primary = true) { CleanerActivity.open(this) }
                actions += Sheets.Action(R.drawable.ic_files_bin, "Recycle bin") { openBin() }
                actions += Sheets.Action(R.drawable.ic_lock, "Private") { openPrivate() }
                actions += hiddenAction()
                actions += settingsAction()
                Sheets.actions(this, "Files", sub, actions, iconRes = R.drawable.ic_folder)
            }
            inBin -> {
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { loadBin(keepScroll = true) }
                if (rows.isNotEmpty()) actions += Sheets.Action(R.drawable.ic_delete, "Empty the bin", primary = true, danger = true) { emptyBin() }
                actions += Sheets.Action(R.drawable.ic_info, "About the recycle bin") {
                    Dialogs.info(
                        this, "Recycle bin",
                        "What you delete in Files waits here for ${com.swiftbrowser.files.BinClock.daysText(FilesSettings.binDays)}, then goes for good. It stays on the same storage (a hidden “${RecycleBin.FOLDER}” folder), " +
                            "so it still takes room there until then: empty the bin to free it now. Files in Private and in Swift's own folders don't come here.",
                    )
                }
                actions += settingsAction()
                Sheets.actions(this, "Recycle bin", sub, actions, iconRes = R.drawable.ic_files_bin)
            }
            cat != null -> {
                actions += Sheets.Action(R.drawable.ic_sort, "Sort", primary = true) { chooseSort() }
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { loadCategory(keepScroll = true) }
                if (cat == FileIndex.Category.DOWNLOADS) {
                    for (f in FileIndex.downloadFolders()) actions += Sheets.Action(R.drawable.ic_folder, "Open the “${folderTitle(f)}” folder") { openFolder(f) }
                }
                actions += Sheets.Action(R.drawable.ic_files_transfer, "Wi-Fi transfer") { WifiTransferActivity.open(this) }
                actions += settingsAction()
                Sheets.actions(this, cat.label, sub, actions, iconRes = cat.icon)
            }
            inPrivate -> {
                actions += Sheets.Action(R.drawable.ic_folder_add, "New folder", primary = true) { newFolder() }
                actions += Sheets.Action(R.drawable.ic_sort, "Sort", primary = true) { chooseSort() }
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_lock, "Lock now", primary = true) {
                    PrivateFolder.lock()
                    // 3.0.4: both sides leave it.
                    for (p in shownPanes()) withPane(p) { if (inPrivate) showHome() }
                    say("Private is locked")
                }
                actions += hiddenAction()
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh") { refreshFolder() }
                actions += Sheets.Action(R.drawable.ic_info, "About Private") {
                    Dialogs.info(
                        this, "Private",
                        "Files here are kept inside Swift: other apps, the Gallery and computers (Wi-Fi transfer too) can't see them. " +
                            // 3.0: a PIN, once chosen, is Private's lock even on a phone with a screen lock.
                            (if (!PrivateFolder.hasPin() && PrivateLock.hasScreenLock(this)) "Opening Private asks for your screen lock." else "Opening Private asks for its PIN (Swift's Settings can change it).") +
                            " It locks when you leave Files, or after a minute away, and the phone takes no screenshots of it.\n\n" + PrivateLock.UNINSTALL_TEXT,
                    )
                }
                actions += settingsAction()
                Sheets.actions(this, folderTitle(dir), sub, actions, iconRes = R.drawable.ic_lock)
            }
            else -> {
                val here = dir
                val fav = Favourites.has(here)
                actions += Sheets.Action(R.drawable.ic_folder_add, "New folder", primary = true) { newFolder() }
                actions += Sheets.Action(R.drawable.ic_sort, "Sort", primary = true) { chooseSort() }
                actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
                actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { refreshFolder() }
                actions += hiddenAction()
                actions += Sheets.Action(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "Remove from favourites" else "Add to favourites") { setFavourite(here, !fav) }
                actions += Sheets.Action(R.drawable.ic_files_transfer, "Wi-Fi transfer") { WifiTransferActivity.open(this) }
                actions += Sheets.Action(R.drawable.ic_info, "About this folder") { details(listOf(here)) }
                actions += settingsAction()
                Sheets.actions(this, folderTitle(here), sub, actions, iconRes = R.drawable.ic_folder)
            }
        }
    }

    private fun chooseSort() {
        val forCategory = category != null && index == null
        MaterialAlertDialogBuilder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(FileOps.sortLabels.toTypedArray(), if (forCategory) categorySort else sort) { d, which ->
                if (forCategory) FilesSettings.categorySort = which else FilesSettings.sort = which
                settingsShown = settingsKey()
                d.dismiss()
                // An archive sorts as it shows; the folder or category under it keeps its order. 3.0.4:
                // on each side the order is for (folders and archives, or categories); folders sorted
                // by size take their places as they are measured.
                for (p in shownPanes()) withPane(p) {
                    if ((category != null && index == null) != forCategory || (home && index == null) || inBin) return@withPane
                    if (index == null) {
                        listed = sortedWithSizes(listed, which)
                        rows = listed.map { rowOf(it) }
                        touchedSinceShown = false
                    }
                    render()
                    crossFade()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** Adds [f] to the start page's favourites, or takes it off (2.8). */
    private fun setFavourite(f: File, on: Boolean) {
        if (on) Favourites.add(f) else Favourites.remove(f)
        say(if (on) "Added to favourites on the Files start page" else "Removed from favourites")
        homeDirty = true
        if (shownPanes().any { it.home }) loadHome()
    }

    // ------------------------------------------------------------------ archives

    /** 3.0: the archive being read now (Back stops it), or null. */
    private var archiveReading: File?
        get() = pane.archiveReading
        set(v) { pane.archiveReading = v }

    private fun openArchive(f: File, password: String? = null) {
        loadJob?.cancel()
        rememberPosition()
        loading = true
        archiveReading = f
        val reading = "Reading “${f.name}”…"
        showLoadingSoon(reading)
        val p = pane
        val rv = list
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 3.0: a tar.gz is read through to be listed: how far, on the loading screen.
                    Archives.read(f, password, { !isActive }) { fraction ->
                        rv.post { withPane(p) { if (archiveReading === f) updateLoadingText("$reading ${(fraction * 100).toInt()}%") } }
                    }
                }
            }
            withPane(p) { archiveRead(f, result) }
        }
    }

    /** The archive [f] was read (or not): its entries show, on the side it was opened on. */
    private fun archiveRead(f: File, result: Result<Archives.Index>) {
        val p = pane
        run {
            loading = false
            archiveReading = null
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
                clearQuery()
                pendingSlide = SLIDE_FORWARD
                render()
                list.scrollToPosition(0)
                showPageTop()
                when {
                    idx.unsafe > 0 -> say("${idx.unsafe} files in it have names that point outside its folder: Swift leaves them out", long = true)
                    // 3.0: a very big archive lists only its first entries.
                    idx.capped -> say("This archive holds ${number(idx.total)} files and folders: only the first ${number(Archives.MAX_LISTED)} show here. Extract all still takes out every one.", long = true)
                }
            }.onFailure { e ->
                render()
                restorePosition()
                when (e) {
                    is CancellationException -> {}
                    is Archives.NeedPassword -> askPassword(f.name, e.wrong, onCancel = {}) { pw -> withPane(p) { openArchive(f, pw) } }
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
        pendingSlide = if (path.length > inArchive.length && path.startsWith(inArchive)) SLIDE_FORWARD else SLIDE_BACK
        inArchive = path
        selected.clear()
        clearQuery()
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
        pendingSlide = SLIDE_BACK
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
        val side = pane.side
        FileJobs.start(this, FileJobs.Kind.OPEN, "Opening ${e.name}", onDone = { job -> visible?.get()?.entryOpenedOn(side, job) }) { w ->
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

    /** 3.0.4: an archive's file came out for the side it was opened on (still on show; otherwise the side worked on). */
    private fun entryOpenedOn(side: Int, job: FileJobs.Job) {
        val p = panes[side].takeIf { it.attached } ?: chosen
        withPane(p) { entryOpened(job) }
    }

    private fun entryOpened(job: FileJobs.Job) {
        opening?.dismiss()
        opening = null
        val f = job.result
        when {
            job.cancelled -> {}
            job.failed || f == null -> say(job.message ?: "Swift couldn't open this file", long = true)
            Archives.formatOf(f.name) != null -> openArchive(f)
            inPrivate -> openPrivateFile(Row(f.path, f.name, false, f.length(), f.lastModified(), -1, FileKind.of(f.name), f, null, null), f)
            FileOpener.open(this, f) -> finish()
        }
    }

    private fun extractSelected(named: Boolean) {
        val idx = index ?: return
        val select = selected.values.mapNotNull { it.entry?.path }.toSet()
        clearSelection()
        extract(idx.file, idx, select, inArchive, besideArchive(idx.file), named)
    }

    private fun extractSelectedTo(toOther: Boolean = false) {
        val idx = index ?: return
        val select = selected.values.mapNotNull { it.entry?.path }.toSet()
        // 3.0.4: with two folders, straight into the other side's.
        if (toOther) {
            val dest = otherFolder() ?: return say(noOtherFolder())
            clearSelection()
            extract(idx.file, idx, select, inArchive, dest, named = false)
            return
        }
        clearSelection()
        clipExtract(idx.file, idx, select, inArchive)
    }

    /** 3.0.4: an archive (or some of it) into the other side's folder, as Extract here there would. */
    private fun extractToOther(archive: File, idx: Archives.Index?, select: Set<String>?, base: String) {
        val dest = otherFolder() ?: return say(noOtherFolder())
        extract(archive, idx, select, base, dest, named = select == null)
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
        val private = inPrivate
        if (one != null) actions += Sheets.Action(R.drawable.ic_edit, "Rename") { rename(one) }
        // 2.9: into Private from the phone's storage or a card; within Private, copying and moving between its folders.
        if (private) {
            actions += Sheets.Action(R.drawable.ic_copy, "Copy") { setClip(files, move = false) }
            actions += Sheets.Action(R.drawable.ic_move, "Move to another folder") { setClip(files, move = true) }
        } else {
            // 3.0.4: with two folders the bar's Copy and Move are to the other side; to a folder gone to, here.
            if (twoPanes) {
                actions += Sheets.Action(R.drawable.ic_copy, "Copy to another folder") { setClip(files, move = false) }
                actions += Sheets.Action(R.drawable.ic_move, "Move to another folder") { setClip(files, move = true) }
            }
            if (files.all { canGoPrivate(it) }) actions += Sheets.Action(R.drawable.ic_lock, "Move to Private") { moveToPrivate(files) }
        }
        actions += Sheets.Action(R.drawable.ic_archive, "Compress") { compress(files) }
        if (files.all { it.isFile && Archives.formatOf(it.name) != null }) {
            actions += Sheets.Action(R.drawable.ic_unarchive, if (files.size == 1) "Extract to folder “${Archives.baseName(files[0].name)}”" else "Extract each to its own folder") {
                clearSelection()
                files.forEach { extract(it, null, null, "", it.parentFile ?: dir, named = true) }
            }
        }
        if (one != null && one.isFile) actions += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWith(one) }
        if (one != null && one.isFile && !rowsNow.singleOrNull()?.where.isNullOrEmpty()) actions += Sheets.Action(R.drawable.ic_folder, "Show in folder") { showInFolder(one) }
        if (one != null && one.isDirectory && !private) {
            val fav = Favourites.has(one)
            actions += Sheets.Action(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "Remove from favourites" else "Add to favourites") { setFavourite(one, !fav) }
        }
        actions += Sheets.Action(R.drawable.ic_info, "Details") { details(files) }
        if (selected.size < shownRowCount) actions += Sheets.Action(R.drawable.ic_select_all, "Select all") { selectAll() }
        actions += Sheets.Action(R.drawable.ic_close, "Clear selection") { clearSelection() }
        Sheets.actions(this, if (rowsNow.size == 1) rowsNow[0].name else FileJobs.items(rowsNow.size), null, actions, iconRes = R.drawable.ic_more)
    }

    // ------------------------------------------------------------------ opening

    /** Gives [files] to the website that asked. */
    private fun finishPick(files: List<File>) {
        if (files.isEmpty()) return
        setResult(RESULT_OK, FilePick.result(files))
        finish()
    }

    private fun open(r: Row) {
        val f = r.file
        val e = r.entry
        when {
            picking && f != null -> if (r.isDir) openFolder(f) else finishPick(listOf(f))
            e != null && e.isDir -> {
                clearQuery()
                showArchiveFolder(e.path)
            }
            e != null -> openEntry(e)
            f == null -> {}
            // 2.9: in the bin, a tap shows what can be done (restore it, or delete it for good).
            inBin -> binSheet(r)
            r.isDir -> openFolder(f)
            Archives.formatOf(r.name) != null -> openArchive(f)
            inPrivate -> openPrivateFile(r, f)
            FileOpener.open(this, f) -> finish()
        }
    }

    /**
     * A file of Private's (2.9), opened without leaving a trace: documents in Swift's viewer as
     * incognito ones (not added to the recent documents), pictures, videos and music on their
     * screens, the rest in another app. Leaving Files for them gives Private a minute before it locks.
     */
    private fun openPrivateFile(r: Row, f: File) {
        // 3.0.4: never once Private has locked (no side should still show it then).
        if (!PrivateFolder.isUnlocked) {
            showHome()
            say("Private is locked")
            return
        }
        val name = f.name
        keepPrivateOpen()
        when {
            r.kind == FileKind.APK -> FileOpener.install(this, f)
            // 3.0: Swift's viewers are told it is Private's (kept out of screenshots and recent apps).
            Docs.kindOf(name, null) != null -> {
                if (!FileOpener.openDocumentSecurely(this, f, name)) {
                    privateGraceUntil = 0L
                    say("Swift can't show “$name”")
                }
            }
            MediaViewerActivity.kindOf(name) != null -> FileOpener.open(this, f, secure = true)
            else -> FileOpener.openWith(this, f)
        }
    }

    /** "Open with another app": from Private, Private stays open a minute meanwhile. */
    private fun openWith(f: File) {
        if (inPrivate) keepPrivateOpen()
        FileOpener.openWith(this, f)
    }

    private fun share(files: List<File>) {
        if (inPrivate) keepPrivateOpen()
        FileOpener.share(this, files)
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
        if (inBin) {
            binSheet(r)
            return
        }
        if (inPrivate) {
            privateSheet(r, f)
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
        // 3.0.4: with two folders, to the other side's.
        actions += otherSideActions(listOf(f))
        if (!r.isDir && !r.where.isNullOrEmpty()) actions += Sheets.Action(R.drawable.ic_folder, "Show in folder") { showInFolder(f) }
        actions += Sheets.Action(R.drawable.ic_edit, "Rename") { rename(f) }
        if (canGoPrivate(f)) actions += Sheets.Action(R.drawable.ic_lock, "Move to Private") { moveToPrivate(listOf(f)) }
        actions += Sheets.Action(R.drawable.ic_info, "Details") { details(listOf(f)) }
        actions += Sheets.Action(R.drawable.ic_delete, "Delete", danger = true) { delete(listOf(f)) }
        Sheets.actions(this, r.name, subtitleOf(r), actions, iconRes = r.kind.icon)
    }

    /** A file or folder in Private (2.9): open, share or move it out; it never goes to the recycle bin. */
    private fun privateSheet(r: Row, f: File) {
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { open(r) }
        if (!r.isDir) actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(listOf(f)) }
        actions += Sheets.Action(R.drawable.ic_move, "Move out", primary = true) { moveOut(listOf(f)) }
        actions += Sheets.Action(R.drawable.ic_archive, "Compress", primary = true) { compress(listOf(f)) }
        if (!r.isDir && Archives.formatOf(r.name) != null) actions += Sheets.Action(R.drawable.ic_unarchive, "Extract here") { extract(f, null, null, "", f.parentFile ?: dir, named = true) }
        if (!r.isDir) actions += Sheets.Action(R.drawable.ic_reopen, "Open with another app") { openWith(f) }
        actions += Sheets.Action(R.drawable.ic_move, "Move to another folder") { setClip(listOf(f), move = true) }
        actions += otherSideActions(listOf(f))
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
        // 3.0: Private locked meanwhile: what waited from it goes.
        if (dropLockedClip()) {
            updateChrome()
            say("Private is locked, so that was called off. Open Private to copy from it again.", long = true)
            return
        }
        when (val c = clip) {
            is Clip.Files -> transfer(c.files, dir, c.move, here = true) {
                clip = null
                updateChrome()
            }
            is Clip.Extract -> {
                clip = null
                updateChrome()
                extract(c.archive, c.index, c.select, c.base, dir, named = c.select == null)
            }
            null -> {}
        }
    }

    /**
     * Copies or moves [files] into [dest] as a file job, with its progress (and a question when a
     * name is taken there); [started] once it is under way. [here]: [dest] is the folder on show (a
     * paste), not the other side's (3.0.4).
     */
    private fun transfer(files: List<File>, dest: File, move: Boolean, here: Boolean, started: () -> Unit) {
        if (files.isEmpty()) return
        if (files.any { it.isDirectory && FileOps.isInside(dest, it) }) {
            say("A folder can't go inside itself")
            return
        }
        if (move && files.all { it.parentFile?.path == dest.path }) {
            say(if (here) "They're in this folder already" else "They're in that folder already")
            return
        }
        withChanges(if (move) files + dest else listOf(dest)) {
            started()
            val what = if (files.size == 1) files[0].name else FileJobs.items(files.size)
            val kind = if (move) FileJobs.Kind.MOVE else FileJobs.Kind.COPY
            val job = FileJobs.start(this, kind, "${kind.doing} $what", onDone = ::doneHere) { w -> Transfers.copy(w, files, dest, move) }
            showProgress(job)
        }
    }

    /**
     * Delete (2.9): to the recycle bin at once, with Undo in the message. Files the bin can't take
     * (in Private, in Swift's own folders, or with the bin turned off in Settings) are asked about
     * and erased; ones that couldn't go to the bin (no room, the card refused) are offered the same.
     */
    private fun delete(files: List<File>) {
        if (files.isEmpty()) return
        if (!FilesSettings.useRecycleBin || files.any { !RecycleBin.canHold(this, it) }) {
            erase(files)
            return
        }
        // 3.0: the bin at a card's top may need the card's access even when the files don't (Swift's own folder there).
        val p = pane
        withChanges(files + RecycleBin.binsFor(this, files)) {
            withPane(p) {
                clearSelection()
                // Off the list at once: the move itself is a rename, but a big folder is counted first.
                dropRows(files)
            }
            val side = p.side
            RecycleBin.move(this, files) { b -> (visible?.get() ?: this).binned(b, side) }
        }
    }

    private fun binned(b: RecycleBin.Batch, side: Int) {
        homeDirty = true
        if (isFinishing) return
        // Read again once the rows taken off have closed up (3.0.4: each side, which may show them too).
        list.postDelayed({ if (!isFinishing) reloadShown() }, if (animationsOn) Motion.SHORT + 100 else 0L)
        if (b.items.isNotEmpty()) {
            say("Moved ${RecycleBin.movedText(b.items)} to the recycle bin", "Undo") {
                RecycleBin.restore(this, b.items) { back, failed ->
                    homeDirty = true
                    // A category and the start page read Android's media index, which learns of them a moment later.
                    if (!isFinishing) list.postDelayed({
                        if (isFinishing) return@postDelayed
                        for (p in shownPanes()) p.undone = true
                        reloadShown()
                    }, if (shownPanes().any { it.home || it.category != null }) 900L else 0L)
                    when {
                        failed > 0 -> say("$failed couldn't be put back: they're still in the recycle bin", long = true)
                        // 3.0: put back under another name (the old one was taken meanwhile): said.
                        else -> RecycleBin.renamedText(b.items, back)?.let { say(it, long = true) }
                    }
                }
            }
        }
        if (b.failed.isNotEmpty()) {
            val failed = b.failed
            Dialogs.confirm(
                this, "Delete for good?",
                RecycleBin.couldntText(failed) + " Delete " + (if (failed.size == 1) "it" else "them") + " for good instead? Swift can't bring " + (if (failed.size == 1) "it" else "them") + " back.",
                "Delete", danger = true,
            ) { withPane(panes[side].takeIf { it.attached } ?: chosen) { eraseNow(failed) } }
        }
    }

    /** Deleting for good, asked first (what the bin can't take). */
    private fun erase(files: List<File>) {
        val title = if (files.size == 1) "Delete “${files[0].name}”?" else "Delete ${files.size} items?"
        val folders = files.count { it.isDirectory }
        val them = if (files.size == 1) "it" else "them"
        val why = when {
            inPrivate -> "Files in Private don't go to the recycle bin. "
            !FilesSettings.useRecycleBin -> ""
            else -> "These have no recycle bin (Swift's own folders). "
        }
        val message = why + when {
            files.size == 1 && folders == 1 -> "The folder goes with everything in it. Swift can't bring it back."
            folders > 0 -> "Folders go with everything in them. Swift can't bring them back."
            else -> "Swift can't bring $them back."
        }
        Dialogs.confirm(this, title, message, "Delete", danger = true) { eraseNow(files) }
    }

    private fun eraseNow(files: List<File>) {
        withChanges(files) {
            clearSelection()
            val what = if (files.size == 1) files[0].name else FileJobs.items(files.size)
            val job = FileJobs.start(this, FileJobs.Kind.DELETE, "Deleting $what", onDone = ::doneHere) { w -> Transfers.delete(w, files) }
            showProgress(job)
        }
    }

    // ------------------------------------------------------------------ the recycle bin (2.9)

    private fun selectedBinItems(): List<RecycleBin.Item> = selected.keys.mapNotNull { binItems[it] }

    private fun binText(b: RecycleBin.Item): String {
        val left = b.daysLeft()
        return when (left) {
            0 -> "goes today"
            1 -> "1 day left"
            else -> "$left days left"
        }
    }

    /** One thing in the bin: where it was, when it was deleted and when it goes; Restore or Delete forever. */
    private fun binSheet(r: Row) {
        val b = binItems[r.key] ?: return
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_files_restore, "Restore", primary = true) { restoreFromBin(listOf(b)) }
        actions += Sheets.Action(R.drawable.ic_delete, "Delete forever", primary = true, danger = true) { eraseFromBin(listOf(b)) }
        actions += Sheets.Action(R.drawable.ic_info, "Details", primary = true) { binDetails(b) }
        Sheets.actions(this, b.name, "Deleted ${dateText(b.deletedAt)} · ${binText(b)}", actions, iconRes = r.kind.icon)
    }

    private fun binDetails(b: RecycleBin.Item) {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, b.name, FileKind.of(b.name, b.isDir).label))
        detailRow(col, "Was in", if (b.from == null) "Not known (it goes back to the top of ${b.volume.name})" else FileIndex.placeOf(this, b.home))
        if (b.from != null) detailRow(col, "Full path", File(b.home, b.name).path)
        detailRow(col, "Deleted", longDate(b.deletedAt))
        detailRow(col, "Goes for good", DateUtils.formatDateTime(this, b.goesAt(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR) + " (" + binText(b) + ")")
        detailRow(col, "Size", bytesText(b.size))
        if (b.isDir) detailRow(col, "Holds", if (b.files == 1) "1 file" else "${number(b.files)} files")
        Sheets.sheet(this, col).showImmersive()
    }

    /** [items] leave the list on show at once, closing up (a lot of them at once: the list fades instead). */
    private fun dropFromBin(items: List<RecycleBin.Item>) {
        dropRows(items.map { it.file })
        if (items.size > MAX_ANIMATED) crossFade(ms = Motion.SHORT)
    }

    private fun restoreFromBin(items: List<RecycleBin.Item>) {
        if (items.isEmpty()) return
        withChanges(items.map { it.home } + items.map { it.holder }) {
            clearSelection()
            dropFromBin(items)
            RecycleBin.restore(this, items) { back, failed ->
                homeDirty = true
                if (isFinishing) return@restore
                // 3.0.4: the bin and the folders they went back to, on either side.
                reloadShown()
                val first = back.firstOrNull()
                // 3.0: one that came back under a new name (the old one was taken) says so.
                val renamed = RecycleBin.renamedText(items, back)
                when {
                    first == null -> say("Swift couldn't restore " + if (items.size == 1) "“${items[0].name}”" else "them", long = true)
                    failed > 0 -> say("Restored ${FileJobs.items(back.size)}. $failed couldn't be.", long = true)
                    else -> say(renamed?.let { if (back.size == 1) it else "Restored ${FileJobs.items(back.size)}. $it" } ?: ("Restored " + (if (back.size == 1) "“${first.name}”" else FileJobs.items(back.size))), "Show") {
                        if (first.isDirectory) openFolder(first) else showInFolder(first)
                    }
                }
            }
        }
    }

    private fun eraseFromBin(items: List<RecycleBin.Item>) {
        if (items.isEmpty()) return
        val title = if (items.size == 1) "Delete “${items[0].name}” for good?" else "Delete ${items.size} items for good?"
        Dialogs.confirm(this, title, "${UrlUtils.formatBytes(items.sumOf { it.size })} is freed. Swift can't bring " + (if (items.size == 1) "it" else "them") + " back.", "Delete forever", danger = true) {
            withChanges(items.map { it.holder }) {
                clearSelection()
                dropFromBin(items)
                RecycleBin.erase(this, items) { n ->
                    homeDirty = true
                    if (isFinishing) return@erase
                    reloadShown()
                    say(if (n == items.size) "Deleted " + (if (n == 1) "“${items[0].name}”" else FileJobs.items(n)) + " for good" else "Deleted $n. ${items.size - n} couldn't be.")
                }
            }
        }
    }

    private fun emptyBin() {
        val all = binItems.values.toList()
        if (all.isEmpty()) return
        Dialogs.confirm(
            this, "Empty the recycle bin?",
            "Everything in it (${FileJobs.items(all.size)}, ${UrlUtils.formatBytes(all.sumOf { it.size })}) is deleted for good.",
            "Empty the bin", danger = true,
        ) {
            withChanges(all.map { it.holder }) {
                clearSelection()
                dropFromBin(all)
                RecycleBin.empty(this) { n ->
                    homeDirty = true
                    if (isFinishing) return@empty
                    reloadShown()
                    say(if (n == 1) "Deleted 1 item for good" else "Deleted $n items for good")
                }
            }
        }
    }

    // ------------------------------------------------------------------ Private (2.9)

    /** Private's folder (or [to] in it), once it is unlocked. */
    private fun openPrivate(to: File? = null) {
        val root = PrivateFolder.root(this)
        val target = to?.takeIf { PrivateFolder.isInside(this, it) && it.isDirectory } ?: root
        // 3.0.4: on the side that asked, once unlocked (a PIN or Android's own question comes between).
        val p = pane
        unlockPrivate {
            if (!root.isDirectory) root.mkdirs()
            if (p.attached) withPane(p) { openFolder(target) } else openFolder(target)
        }
    }

    /**
     * Unlocks Private: the phone's screen lock (Android asks), or on a phone without one Swift's own
     * PIN (chosen the first time). What Private is, is said once before. 3.0: a PIN, once chosen,
     * keeps Private even after the phone gets a screen lock; the screen lock may then reset a
     * forgotten PIN, once its day's wait is over.
     */
    private fun unlockPrivate(then: () -> Unit) {
        if (PrivateFolder.isUnlocked) {
            then()
            return
        }
        PrivateLock.introThen(this) {
            val ok = {
                PrivateFolder.unlock()
                then()
            }
            when {
                PrivateFolder.hasPin() -> PrivateLock.askPin(
                    this,
                    onScreenLock = {
                        // The reset's wait is over: from now on the screen lock opens Private, not the PIN.
                        val asked = askScreenLock {
                            PrivateFolder.clearPin()
                            say("Private opens with your phone's screen lock now")
                            then()
                        }
                        if (!asked) say("Android's screen-lock question couldn't be shown. Choose a new PIN instead.", long = true)
                    },
                ) { ok() }
                askScreenLock(then) -> {}
                else -> PrivateLock.setUpPin(this) { ok() }
            }
        }
    }

    /** Android's screen-lock question, then [then] (Private unlocked); false when the phone can't ask it. */
    private fun askScreenLock(then: () -> Unit): Boolean {
        val question = (if (PrivateLock.hasScreenLock(this)) PrivateLock.credentialIntent(this) else null) ?: return false
        return try {
            afterUnlock = then
            keepPrivateOpen()
            askUnlock.launch(question)
            true
        } catch (e: Exception) {
            L.w("Private: Android's lock question couldn't be shown", e)
            afterUnlock = null
            privateGraceUntil = 0L
            false
        }
    }

    /** Whether [f] can be moved into Private: on the phone's storage or a card, not the bin or a storage's top. */
    private fun canGoPrivate(f: File): Boolean {
        val v = Volumes.of(this, f) ?: return false
        return f.path != v.root.path && !RecycleBin.isInBin(this, f)
    }

    /** Moves [files] into Private (a file job); a PIN is chosen first on a phone without a screen lock. */
    private fun moveToPrivate(files: List<File>) {
        val list = files.filter { canGoPrivate(it) }
        if (list.isEmpty()) return
        PrivateLock.introThen(this) {
            val go = {
                withChanges(list) {
                    clearSelection()
                    val what = FileJobs.items(list.size)
                    val job = FileJobs.start(this, FileJobs.Kind.MOVE, "Moving $what to Private", hideNames = true, onDone = ::doneHere) { w -> PrivateFolder.moveIn(w, list) }
                    showProgress(job)
                }
            }
            if (PrivateLock.hasScreenLock(this) || PrivateFolder.hasPin()) go() else PrivateLock.setUpPin(this) { go() }
        }
    }

    /** "Move out": back where it came from (when known), to Download, or to a folder the user goes to. */
    private fun moveOut(files: List<File>) {
        if (files.isEmpty()) return
        val origins = files.map { PrivateFolder.origin(this, it) }
        @Suppress("DEPRECATION")
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val actions = ArrayList<Sheets.Action>()
        if (origins.all { it != null }) {
            val places = origins.filterNotNull().distinctBy { it.path }
            val label = if (places.size == 1) "Back to “${FileIndex.placeOf(this, places[0])}”" else "Back where each came from"
            actions += Sheets.Action(R.drawable.ic_files_restore, label, primary = true) {
                moveOutTo(files.indices.groupBy({ origins[it]!! }, { files[it] }))
            }
        }
        actions += Sheets.Action(R.drawable.ic_download, "To Download", primary = true) { moveOutTo(mapOf(download to files)) }
        actions += Sheets.Action(R.drawable.ic_folder, "Choose a folder…", primary = true) {
            clip = Clip.Files(files, move = true)
            clearSelection()
            say("Go to the folder you want, then tap Move here")
        }
        val them = if (files.size == 1) "“${files[0].name}”" else FileJobs.items(files.size)
        Sheets.actions(this, "Move $them out of Private", "Other apps and the Gallery can see it again where it goes.", actions, iconRes = R.drawable.ic_move)
    }

    private fun moveOutTo(groups: Map<File, List<File>>) {
        val all = groups.values.flatten()
        withChanges(groups.keys) {
            clearSelection()
            val what = FileJobs.items(all.size)
            val job = FileJobs.start(this, FileJobs.Kind.MOVE, "Moving $what out of Private", hideNames = true, onDone = ::doneHere) { w ->
                for ((dest, list) in groups) {
                    if (!dest.isDirectory) {
                        val a = Volumes.accessFor(w.ctx, dest) ?: throw com.swiftbrowser.files.FileProblem(Transfers.CARD_TEXT)
                        if (!a.mkdirs(dest)) throw com.swiftbrowser.files.FileProblem("Swift couldn't make the folder “${dest.name}”.")
                    }
                    Transfers.copy(w, list, dest, move = true)
                }
                PrivateFolder.tidyOrigins(w.ctx)
                if (groups.size == 1) w.result(groups.keys.first())
                w.message("Moved " + (if (all.size == 1) "“${all[0].name}”" else FileJobs.items(all.size)) + " out of Private")
            }
            showProgress(job)
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
            // 3.0: only the same file in other capitals may have the name already (on the phone's storage
            // and cards, which ignore capitals); in Private "Photo.jpg" may be another file, never written over.
            if (to.exists() && !FileOps.sameFile(f, to)) {
                say("There's already something called “$name” here")
                return@askText
            }
            withChanges(listOf(f)) {
                clearSelection()
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        val access = Volumes.accessFor(this@FilesActivity, f) ?: return@withContext false
                        access.rename(f, name).also { done ->
                            // 3.0.4: a folder's size goes with its old name (measured again under the new one).
                            if (done && to.isDirectory) FolderSizes.changed(listOf(f.path))
                            // 2.9: Private's files were never in Android's media index, and mustn't be.
                            if (done && !PrivateFolder.isInside(this@FilesActivity, f)) {
                                FileOps.forget(this@FilesActivity, f)
                                // 3.0: every file of a renamed folder back in the Gallery, not the folder alone.
                                FileOps.scanTree(this@FilesActivity, to)
                            }
                        }
                    }
                    if (ok) {
                        // 3.0: a name with a dot first is hidden: said, so it doesn't seem to vanish.
                        say(if (name.startsWith(".") && !showHidden) "Renamed to “$name”. Names starting with a dot are hidden: ⋮ → Show hidden files shows them." else "Renamed to “$name”", long = name.startsWith("."))
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
        PrivateFolder.renamed(this, from, to)
        homeDirty = true
        val swap = { r: Row -> if (r.file?.path == from.path) rowOf(FileItem.of(to, count = to.isDirectory, where = r.where)) else r }
        homeCache?.let { c ->
            // 3.0.3: where a video was left is kept by its path: a renamed one leaves Continue.
            homeCache = c.copy(favourites = c.favourites.map(swap), recent = c.recent?.map(swap), continued = c.continued.filter { it.localPath != from.path })
        }
        // 3.0.4: on each side (the other may show the same place).
        for (p in shownPanes()) withPane(p) {
            when {
                index != null -> {}
                home -> if (onStartPage) render()
                category != null -> {
                    listed = listed.map { if (it.file.path == from.path) FileItem.of(to, where = it.where) else it }
                    rows = rows.map(swap)
                    searchRows = searchRows?.map(swap)
                    render()
                }
                else -> load(keepScroll = true)
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
                        // 3.0.4: on each side showing this folder.
                        for (p in shownPanes()) withPane(p) { if (!home && category == null && !inBin && index == null && dir.path == parent.path) load(keepScroll = true) }
                        say(if (name.startsWith(".") && !showHidden) "Made the hidden folder “$name”. ⋮ → Show hidden files shows it." else "Made the folder “$name”", long = name.startsWith("."))
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
            setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 8), Ui.dp(this@FilesActivity, 24), 0)
        }
        val nameInput = EditText(this).apply {
            setText(base)
            hint = "Name"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            selectAll()
            Ui.field(this)
        }
        box.addView(nameInput)
        val password = EditText(this).apply {
            hint = "Password (if you want one)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Ui.field(this)
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
        }.apply { setPadding(0, Ui.dp(this@FilesActivity, 8), 0, Ui.dp(this@FilesActivity, 8)) }
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
                    // 3.0: Archives.compress chooses the name: never one of the files being packed (see there).
                    val job = FileJobs.start(this, FileJobs.Kind.COMPRESS, "Compressing into $full", onDone = ::doneHere) { w ->
                        Archives.compress(w, files, dest, chosen, pw, access)
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
        val sizeView = detailRow(col, "Size", if (one != null && one.isFile) bytesText(one.length()) else "Counting…").also { Ui.steadyFigures(it) }
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

    private val searchSoon get() = pane.searchSoon

    /** What is typed in the search box, for the side worked on (3.0.4: each side keeps its own). */
    private var query: String
        get() = pane.query
        set(v) { pane.query = v }

    private fun scheduleSearch() {
        list.removeCallbacks(searchSoon)
        if (query.isBlank()) {
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
        val q = query.trim()
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
        if (category != null || inBin) {
            // Not read yet: searched once it is.
            if (loading) return
            searchRows = rows.filter { it.name.contains(q, ignoreCase = true) }
            render()
            list.scrollToPosition(0)
            showPageTop()
            return
        }
        val from = dir
        val hidden = showHidden
        val everywhere = home
        val gen = placeGen
        val app = applicationContext
        val p = pane
        searching = true
        updateChrome()
        searchJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                if (everywhere) FileIndex.search(app, q, hidden, MAX_FOUND) { !isActive } else FileOps.search(from, q, hidden, MAX_FOUND) { !isActive }
            }
            withPane(p) {
                searching = false
                if (gen != placeGen) return@launch
                searchRows = found.map { rowOf(it) }
                render()
                list.scrollToPosition(0)
                showPageTop()
            }
        }
    }

    // ------------------------------------------------------------------ jobs

    /** A job started here is over: the folder shows what changed, and the outcome is said. */
    private fun doneHere(job: FileJobs.Job) {
        val screen = visible?.get() ?: return
        screen.jobDone(job)
    }

    private fun jobDone(job: FileJobs.Job) {
        progress.doneWith(job)
        if (isFinishing) return
        homeDirty = true
        // 3.0.4: each side on show follows what changed (a copy to the other side changes both).
        var homeRead = false
        for (p in shownPanes()) withPane(p) {
            when {
                index != null -> {}
                home -> if (!homeRead) {
                    homeRead = true
                    loadHome()
                }
                category != null || inBin -> reload()
                dir.path in job.touched || job.touched.isEmpty() || job.result?.path == dir.path -> {
                    // 2.9: what was deleted or moved away closes up first (a folder of a few hundred at most is looked at), then the folder is read again.
                    val gone = if ((job.kind == FileJobs.Kind.DELETE || job.kind == FileJobs.Kind.MOVE) && animationsOn && rows.size <= 300) {
                        rows.mapNotNull { r -> r.file?.takeIf { !it.exists() } }
                    } else {
                        emptyList()
                    }
                    if (gone.isNotEmpty() && gone.size <= MAX_ANIMATED) {
                        dropRows(gone)
                        val gen = placeGen
                        list.postDelayed({ if (!isFinishing && p.attached && gen == p.placeGen) withPane(p) { load(keepScroll = true) } }, Motion.SHORT + 100)
                    } else {
                        load(keepScroll = true)
                    }
                }
            }
        }
        val text = job.message ?: return
        val result = job.result
        val show = !job.failed && (job.kind == FileJobs.Kind.EXTRACT || job.kind == FileJobs.Kind.MOVE) && result != null && result.isDirectory && result.path != dir.path
        when {
            // 2.9: moved into Private: there with Open (it asks to unlock first).
            show && result != null && PrivateFolder.isInside(this, result) -> say(text, "Open") { openPrivate(result) }
            show && result != null -> say(text, "Show") { openFolder(result) }
            else -> say(text, long = job.failed)
        }
    }

    private fun onJobsChanged() {
        val jobs = FileJobs.active.filter { it.kind != FileJobs.Kind.OPEN }
        val job = jobs.firstOrNull { it.running } ?: jobs.firstOrNull()
        if (job == null) {
            jobStrip.visibility = View.GONE
            updateBottom()
        } else {
            bottomBar.part(jobStrip, animationsOn)
            val more = if (jobs.size > 1) "  (+${jobs.size - 1})" else ""
            jobText.text = job.title + more + "  ·  " + FileJobs.detail(job).ifBlank { "Waiting…" }
            FileJobDialogs.setBar(jobBar, job)
            updateBottom()
        }
        progress.changed()
        val asking = FileJobs.asking
        val q = asking?.question
        if (q != null && q !== questionShown && !isFinishing) showQuestion(q)
    }

    /** The job's progress in a window: the file, how far, Hide (it goes on) and Cancel. */
    private fun showProgress(job: FileJobs.Job) = progress.show(job)

    /** A job asks: a name already taken, or an archive's password. */
    private fun showQuestion(q: FileJobs.Question) {
        questionShown = q
        if (q.password) {
            askPassword(q.name, q.wrong, onCancel = { q.reply(null) }) { pw -> q.reply(pw) }
            return
        }
        FileJobDialogs.askNameTaken(this, q)
    }

    /** Asks for an archive's password ([wrong]: the last one didn't work). */
    private fun askPassword(archive: String, wrong: Boolean, onCancel: () -> Unit, onOk: (String) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FilesActivity, 24), Ui.dp(this@FilesActivity, 4), Ui.dp(this@FilesActivity, 24), 0)
        }
        val input = EditText(this).apply {
            hint = "Password"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Ui.field(this)
        }
        box.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, if (wrong) "That password didn't work. Check it and try again." else "“$archive” is locked with a password.").apply {
            setPadding(0, 0, 0, Ui.dp(this@FilesActivity, 8))
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

    /** [subtitleOf], with a recycle bin row's last day (or two) in the warning colour. */
    private fun subtitleText(r: Row, owner: Pane): CharSequence {
        val text = subtitleOf(r, owner)
        val b = owner.binItems[r.key]?.takeIf { owner.inBin } ?: return text
        if (b.daysLeft() > 1) return text
        val going = binText(b)
        val at = text.indexOf(going)
        if (at < 0) return text
        return android.text.SpannableString(text).apply { setSpan(android.text.style.ForegroundColorSpan(Ui.danger(this@FilesActivity)), at, at + going.length, 0) }
    }

    private fun subtitleOf(r: Row, owner: Pane = pane): String {
        // 2.9: in the bin, when it was deleted and how long it stays, then where it was (a second line).
        owner.binItems[r.key]?.takeIf { owner.inBin }?.let { b ->
            val size = if (b.isDir) FileJobs.items(b.files).replace("item", "file") else UrlUtils.formatBytes(b.size)
            val deleted = if (DateUtils.isToday(b.deletedAt)) "today" else dateText(b.deletedAt)
            return "$size · deleted $deleted · ${binText(b)}\nfrom ${r.where}"
        }
        val parts = ArrayList<String>(4)
        if (r.entry?.encrypted == true) parts += "Locked"
        if (r.isDir) {
            if (r.count >= 0) parts += FileJobs.items(r.count)
            // 3.0.4: and what it holds once measured ("12 items · 1.40 GB").
            sizeText(r, owner)?.let { parts += it }
        } else if (r.size >= 0) {
            parts += UrlUtils.formatBytes(r.size)
        }
        dateText(r.time)?.let { parts += it }
        if (!r.where.isNullOrEmpty()) parts += "in " + r.where
        return parts.joinToString(" · ")
    }

    // 3.0.5: the same short date as Downloads.
    private val dates by lazy { ShortDates(this) }

    private fun dateText(t: Long): String? = dates.text(t)

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
        /** 3.0.4: a folder tile's size, under its icon. */
        val size: TextView? = null,
        /** 3.0.5: a row of the start page: a rounded piece of a group, as History's rows (GroupedRows); other rows lie flat on the page. */
        val onCard: Boolean = false,
    ) : RecyclerView.ViewHolder(root) {
        var thumb: FileThumbs.Request? = null
        var marked = false
        var boundKey: String? = null

        /** 3.0.3: a video's watched line and tick, over its picture (made the first time one is needed). */
        var marks: WatchMarks? = null

        /** 3.0.4: the standard folders' icons this row or tile has shown, by their mark, made once each (FolderIcons). */
        var folderIcons: Array<Drawable?>? = null
        lateinit var folderFill: Drawable
        lateinit var fileFill: Drawable
    }

    private class HeaderHolder(v: View, val title: TextView, val action: TextView) : RecyclerView.ViewHolder(v)

    /**
     * A storage's card. 3.0.4: [bar] split by what fills the storage, with [legend] under it: a dot,
     * a name and a size in each of [cells] ([dots] their dots), in [lines].
     */
    private class StorageHolder(
        v: View, val card: View, val icon: ImageView, val name: TextView, val bar: StorageBar, val detail: TextView,
        val legend: LinearLayout, val lines: List<View>, val cells: List<View>, val dots: List<View>, val texts: List<TextView>,
    ) : RecyclerView.ViewHolder(v)

    private class CategoriesHolder(v: View, val counts: Map<FileIndex.Category, Pair<View, TextView>>) : RecyclerView.ViewHolder(v)

    private class HintHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private class ToolsHolder(v: View, val captions: Map<Tool, Pair<View, TextView>>) : RecyclerView.ViewHolder(v)

    private class FilterHolder(val box: FrameLayout) : RecyclerView.ViewHolder(box) {
        var shown = ""
    }

    private class DocsHolder(v: View, val strip: LinearLayout) : RecyclerView.ViewHolder(v) {
        var shown = ""
    }

    /** A strip of document cards that scrolls sideways. */
    private fun makeRecentDocs(): DocsHolder {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(strip)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            Ui.fadeEdges(this)
        }
        return DocsHolder(scroll, strip)
    }

    /** How far a document was read: "Page 8 of 14", "48% read" (null before it was really begun). */
    private fun progressText(doc: Docs.Doc): String? = when {
        doc.place == null || doc.progress <= 0.02f -> null
        doc.kind == Docs.Kind.PDF && doc.total > 0 -> "Page ${doc.place} of ${doc.total}"
        doc.progress >= 0.97f -> "Read"
        else -> "${(doc.progress * 100).toInt()}% read"
    }

    /** The cards, made again only when the documents or how far they were read changed. */
    private fun bindRecentDocs(h: DocsHolder, item: RecentDocs) {
        val key = item.docs.joinToString("|") { it.id + ":" + it.progress }
        if (key == h.shown) return
        h.shown = key
        h.strip.removeAllViews()
        val w = dp(104)
        for (doc in item.docs) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(6))
                // 3.0.5: the press's corners around the document's picture (12 dp, 4 dp in), so they nest.
                background = Ui.ripple(this@FilesActivity, null, Ui.R_MEDIUM)
                Ui.pressable(this, Motion.PRESS_CARD)
                setOnClickListener { openDoc(doc) }
                setOnLongClickListener { v ->
                    Haptics.hold(v)
                    docSheet(doc)
                    true
                }
            }
            val thumb = ImageView(this).apply {
                // 3.0.5: a plain filled card (no outline): the page behind it is a step darker.
                background = Ui.shape(this@FilesActivity, Palette.color(this@FilesActivity, R.color.card_bg), Ui.R_SMALL)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER
                setImageDrawable(DocIcon(doc.kind, dp(40)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = doc.uri
            }
            card.addView(thumb, LinearLayout.LayoutParams(w, dp(132)))
            if (thumbsOn) {
                // From memory it comes during this call; one read later comes in softly.
                var binding = true
                DocThumbs.load(this, doc.uri, doc.kind, w) { bmp ->
                    if (thumb.tag != doc.uri) return@load
                    thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    thumb.setImageBitmap(bmp)
                    if (!binding) Motion.fadePicture(thumb)
                }
                binding = false
            }
            card.addView(Ui.text(this, Ui.Type.BODY, doc.name, lines = 2).apply {
                Ui.size(this, Ui.Type.CAPTION, keepFont = true)
                setPadding(dp(2), dp(6), dp(2), 0)
            }, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
            val progress = progressText(doc) ?: dateText(doc.opened)?.let { "Opened $it" }
            if (progress != null) {
                card.addView(Ui.text(this, Ui.Type.CAPTION, progress, lines = 1).apply { setPadding(dp(2), dp(2), dp(2), 0) }, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            card.contentDescription = doc.name + (progress?.let { ", $it" } ?: "")
            h.strip.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
    }

    /** Opens a document where it was left, in Swift's viewer. */
    private fun openDoc(doc: Docs.Doc) {
        try {
            Docs.reopen(this, doc.id)
        } catch (e: Exception) {
            L.w("Files: a recent document couldn't open", e)
            say("Swift can't open “${doc.name}” any more")
        }
    }

    /** Holding a recent document: open, share, see it in its folder, or take it off the list. */
    private fun docSheet(doc: Docs.Doc) {
        val u = Uri.parse(doc.uri)
        val file = if (u.scheme == "file") File(u.path.orEmpty()) else null
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { openDoc(doc) }
        actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { Docs.handOver(this, doc, share = true) }
        if (file != null && Volumes.of(this, file) != null) actions += Sheets.Action(R.drawable.ic_folder, "Show in folder", primary = true) { showInFolder(file) }
        actions += Sheets.Action(R.drawable.ic_close, "Remove from recent") {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { SwiftApp.db.forgetDocument(doc.id) } }
                homeCache?.let { c -> homeCache = c.copy(docs = c.docs.filter { it.id != doc.id }) }
                renderHomes()
            }
        }
        Sheets.actions(this, doc.name, progressText(doc) ?: doc.kind.label, actions, iconRes = R.drawable.ic_documents)
    }

    /** The kinds of documents as chips (made again only when they change). */
    private fun bindDocFilter(h: FilterHolder, f: DocFilter) {
        val key = f.labels.joinToString("|")
        if (key == h.shown) {
            // The same chips: the one tapped is ticked already (and its colour is fading); only one
            // chosen some other way needs ticking.
            ((h.box.getChildAt(0) as? HorizontalScrollView)?.getChildAt(0) as? ChipGroup)?.let { g ->
                val chip = g.getChildAt(f.chosen) as? Chip
                if (chip != null && !chip.isChecked) chip.isChecked = true
            }
            return
        }
        h.shown = key
        h.box.removeAllViews()
        val chips = Sheets.chipRow(this, f.labels, f.chosen, sideDp = Ui.MARGIN) { i ->
            val label = f.labels.getOrNull(i)
            docGroup = DocGroup.entries.firstOrNull { it.label == label }
            selected.clear()
            render()
            list.scrollToPosition(0)
            showPageTop()
        }
        h.box.addView(Palette.paint(chips), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ 3.0.3: Continue

    private class ContinueHolder(box: FrameLayout, val row: ContinueRow) : RecyclerView.ViewHolder(box)

    /** Continue's row: the browser home's cards, here with the phone's videos and songs (in a box, so the list's movement leaves the row's own alone). */
    private fun makeContinue(): ContinueHolder {
        val box = FrameLayout(this).apply {
            setPadding(0, dp(4), 0, dp(6))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lateinit var row: ContinueRow
        row = ContinueRow(this, ContinueRow.Part.FILES, { playContinue(it) }, { continueSheet(row, it) })
        box.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ContinueHolder(box, row)
    }

    /** Plays a card's video or song as Files plays one: in Swift's player, where it was left, its folder as the playlist. */
    private fun playContinue(item: Unfinished.Item) {
        val f = item.localPath?.let { File(it) } ?: return
        if (!f.isFile) {
            say("“${f.name}” isn't there any more")
            homeCache?.let { c -> homeCache = c.copy(continued = c.continued.filter { it.url != item.url }) }
            renderHomes(removals = true)
            return
        }
        if (FileOpener.open(this, f)) finish()
    }

    /** Holding a card: play it, see it in its folder, or take it off Continue (as on the browser's home page). */
    private fun continueSheet(row: ContinueRow, item: Unfinished.Item) {
        val f = item.localPath?.let { File(it) } ?: return
        Haptics.tick(list)
        Sheets.actions(this, item.title.ifBlank { f.name }, item.caption, listOf(
            Sheets.Action(R.drawable.ic_play, "Play", primary = true) { playContinue(item) },
            Sheets.Action(R.drawable.ic_folder, "Show in folder", primary = true) { showInFolder(f) },
            Sheets.Action(R.drawable.ic_close, "Remove from Continue", danger = true) { removeContinue(row, item) },
        ), iconRes = if (item.video) R.drawable.ic_movie else R.drawable.ic_music_note)
    }

    /**
     * Takes a card off Continue: it fades and the row closes up, with Undo; once the Undo is gone,
     * where it was left is forgotten (Unfinished.remove). The last one takes its heading with it.
     */
    private fun removeContinue(row: ContinueRow, item: Unfinished.Item) {
        val c = homeCache ?: return
        homeCache = c.copy(continued = c.continued.filter { it.url != item.url })
        row.remove(item) {
            // Undo: back on the start page, newest first as before.
            homeCache?.let { now ->
                if (now.continued.none { it.url == item.url }) homeCache = now.copy(continued = (now.continued + item).sortedByDescending { it.updated })
            }
            renderHomes()
        }
        // 3.0.4: the other side's start page, if on show, follows at once.
        renderHomes(others = true)
        if (homeCache?.continued.isNullOrEmpty() && onStartPage) render(removals = true)
    }

    // ------------------------------------------------------------------ 3.0.3: watched marks

    /**
     * A video's picture shows how much of it was watched in Swift's player, and a tick once it was
     * watched to its end: looked up by the path and size the row has (no disk access while binding).
     */
    private fun markWatched(owner: Pane, h: FileHolder, r: Row) {
        val path = r.file?.path?.takeIf { r.kind == FileKind.VIDEO && !owner.inBin }
        val share = path?.let { LocalMedia.progress(this, it, r.size) }
        val done = path != null && Watched.isFinished(this, path, r.size)
        if (share == null && !done) {
            if (h.icon.foreground != null) h.icon.foreground = null
            return
        }
        val marks = h.marks ?: WatchMarks(this, h.tile).also { h.marks = it }
        marks.set(share, done)
        if (h.icon.foreground !== marks) h.icon.foreground = marks
    }

    // ------------------------------------------------------------------ 3.0.3: sliding to select

    /** A finger is on the list (a hold may start a slide only while it is). */
    private var fingerDown = false

    /** Where the hold that began a slide was (a position in the list; -1: no slide), and where the finger is now. */
    private var slideFrom = -1
    private var slideTo = -1

    /** The slide selects (the hold selected its file) or unselects; whether it has reached another file yet. */
    private var slideSelects = true
    private var slideMoved = false

    /** What was selected as the slide began: files it leaves behind go back to that. */
    private var slideBefore: Set<String> = emptySet()
    private var slideX = 0f
    private var slideY = 0f

    /** A hold selected (or unselected) the file at [position]: sliding on does the same to the files the finger passes. */
    private fun armSlide(position: Int, selects: Boolean) {
        if (!fingerDown || position < 0) return
        slideFrom = position
        slideTo = position
        slideSelects = selects
        slideMoved = false
        slideBefore = HashSet(selected.keys)
    }

    private fun endSlide() {
        slideFrom = -1
        list.removeCallbacks(slideScroll)
    }

    /** Watches the list's touches: after a hold, the moves of the same touch select instead of scrolling. */
    private val slideTouch = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerDown = true
                    touchedSinceShown = true
                    endSlide()
                }
                // The hold selected: from now on the finger selects, and the list doesn't follow it.
                MotionEvent.ACTION_MOVE -> if (slideFrom >= 0) {
                    slideAt(e)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lifted()
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> slideAt(e)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lifted()
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    private fun slideAt(e: MotionEvent) {
        if (slideFrom < 0) return
        slideX = e.x
        slideY = e.y
        slideOver()
        // Near the top or bottom the list scrolls on by itself, a frame at a time.
        list.removeCallbacks(slideScroll)
        list.postOnAnimation(slideScroll)
    }

    /** The finger was lifted (or the touch was taken away). */
    private fun lifted() {
        fingerDown = false
        endSlide()
    }

    /** The part of the list on show, top and bottom in its own coordinates (the bars above can push its end below the screen). */
    private fun listShown(): Pair<Int, Int> = list.paddingTop to minOf(list.height, page.height - topInPage(list))

    /** The files from the held one to the one under the finger are as the slide makes them; those it left are as they were. */
    private fun slideOver() {
        if (slideFrom < 0) return
        val (top, bottom) = listShown()
        if (bottom <= top) return
        val left = list.paddingLeft.toFloat()
        val x = slideX.coerceIn(left, (list.width - list.paddingRight - 1).toFloat().coerceAtLeast(left))
        val y = slideY.coerceIn(top.toFloat(), (bottom - 1).toFloat())
        val under = list.findChildViewUnder(x, y) ?: return
        val to = list.getChildAdapterPosition(under)
        if (to == RecyclerView.NO_POSITION || to == slideTo) return
        val step = DragSelect.step(slideFrom, slideTo, to)
        slideTo = to
        val was = selected.isNotEmpty()
        var changed = false
        fun mark(p: Int, on: Boolean) {
            val r = adapter.items.getOrNull(p) as? Row ?: return
            if ((r.key in selected) == on) return
            if (on) selected[r.key] = r else selected.remove(r.key)
            adapter.notifyItemChanged(p, PAYLOAD_TOGGLED)
            changed = true
        }
        for (p in step.left) mark(p, (adapter.items.getOrNull(p) as? Row)?.let { it.key in slideBefore } == true)
        for (p in step.reached) mark(p, slideSelects)
        if (!changed) return
        // A light buzz as a run of files begins.
        if (!slideMoved) {
            slideMoved = true
            Haptics.tick(list)
        }
        if (was != selected.isNotEmpty()) refreshSelection()
        updateChrome()
    }

    /** Sliding near the top or bottom of the list: it scrolls on by itself, a little each frame, faster nearer the edge. */
    private val slideScroll = object : Runnable {
        override fun run() {
            if (slideFrom < 0) return
            val (top, bottom) = listShown()
            val step = DragSelect.edgeStep(slideY, top, bottom, dp(56), dp(12))
            if (step == 0 || !list.canScrollVertically(step)) return
            list.scrollBy(0, step)
            slideOver()
            list.postOnAnimation(this)
        }
    }

    // ------------------------------------------------------------------ 3.0.4: two folders side by side

    /** The button for two folders is offered (the screen sideways, wide enough), and two show now. */
    private var twoOffered = false
    private var twoPanes = false

    /** How wide one side's list is now (dp). */
    private var paneWidthDp = Ui.MAX_CONTENT_WIDTH

    /** The row of paths and the row of lists (one side's, or both with a line between), and the page under the title bar holding them. */
    private lateinit var pathRow: LinearLayout
    private lateinit var listRow: LinearLayout
    private lateinit var page: androidx.coordinatorlayout.widget.CoordinatorLayout

    /** The search box is being given a side's words (not typed): nothing is searched for that. */
    private var syncingSearch = false

    /** The sides on the screen: the one worked on, and the other while two show. */
    private fun shownPanes(): List<Pane> = if (twoPanes) panes.asList() else listOf(chosen)

    /** The side that isn't [p]. */
    private fun otherPane(p: Pane = chosen) = panes[TwoPanes.other(p.side)]

    /** The items of the list [h] is in. */
    private fun rowsOf(h: RecyclerView.ViewHolder): List<Any> = (h.bindingAdapter as? Adapter)?.items.orEmpty()

    /** Tiles about 112 dp wide (2.8): three across a phone held upright, more on a wide screen; 3.0.4: across a side. */
    private fun computeGrid() {
        paneWidthDp = TwoPanes.paneWidthDp(resources.configuration.screenWidthDp, twoPanes, Ui.MAX_CONTENT_WIDTH)
        gridCols = TwoPanes.gridColumns(paneWidthDp)
        tilePx = dp((paneWidthDp - 16) / gridCols - 16)
        tileIconPad = (tilePx * 0.3f).toInt()
    }

    /** The sides on show in the row of paths and the row of lists: one, or both with a thin line between. */
    private fun arrangePanes() {
        pathRow.removeAllViews()
        listRow.removeAllViews()
        shownPanes().sortedBy { it.side }.forEachIndexed { i, p ->
            if (i > 0) {
                pathRow.addView(sideLine(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                listRow.addView(sideLine(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            }
            pathRow.addView(p.pathFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            listRow.addView(p.frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        updateMarks()
        updatePathRow()
        // Moving the lists took their focus away: back to the side worked on, so the search box shows no cursor until tapped.
        chosen.list.requestFocus()
    }

    // 3.0.5: kept: nothing else tells two folders side by side apart; the outline colour, as the divider colour hardly shows on the settings' page colour.
    private fun sideLine() = View(this).apply { setBackgroundColor(Palette.color(this@FilesActivity, R.color.outline)) }

    /** The thin accent line along the top of the side worked on, while two show (it fades in as a side is chosen). */
    private fun updateMarks() {
        for (p in panes) {
            val on = twoPanes && p === chosen
            when {
                on && p.mark.visibility != View.VISIBLE -> {
                    p.mark.visibility = View.VISIBLE
                    if (animationsOn) {
                        p.mark.alpha = 0f
                        Motion.anim(p.mark, Motion.SHORT, Motion.enter).alpha(1f).start()
                    }
                }
                // Chosen again while its line was fading away: it comes back.
                on && p.mark.alpha != 1f -> Motion.anim(p.mark, Motion.SHORT, Motion.enter).alpha(1f).start()
                !on && p.mark.visibility != View.GONE ->
                    if (animationsOn) {
                        Motion.anim(p.mark, Motion.PAGE_OUT_MS, Motion.exit).alpha(0f).withEndAction {
                            p.mark.visibility = View.GONE
                            p.mark.alpha = 1f
                        }.start()
                    } else {
                        p.mark.visibility = View.GONE
                    }
            }
            // The side not worked on stays quieter, its path dimmed.
            val pathAlpha = if (twoPanes && p !== chosen) 0.6f else 1f
            if (p.pathScroll.alpha != pathAlpha) {
                if (animationsOn) Motion.anim(p.pathScroll, Motion.SHORT).alpha(pathAlpha).start() else p.pathScroll.alpha = pathAlpha
            }
        }
    }

    /**
     * The side touched last is the one the title bar, the search box and the bars at the bottom are
     * for, with its accent line; each side keeps its own selection and search meanwhile. A slide to
     * select stays on the side it began on.
     */
    private fun setChosen(p: Pane) {
        if (p === chosen || !p.attached || pane !== chosen) return
        endSlide()
        val was = chosen
        chosen = p
        use(p)
        syncSearchBox()
        updateMarks()
        // Files opens again with the side worked on where it was, and the other side where it was.
        val e = sp.edit().putInt(PREF_SIDE, p.side)
        p.remembered?.let { e.putString(PREF_LAST, it) }
        was.remembered?.let { e.putString(PREF_OTHER, it) }
        e.apply()
        updateChrome()
    }

    /** Where Files was left (2.9): 3.0.4: the side worked on's, the other side's apart. */
    private fun rememberPlace(key: String) {
        if (picking) return
        pane.remembered = key
        sp.edit().putString(if (pane === chosen) PREF_LAST else PREF_OTHER, key).apply()
    }

    /** The search box shows the side worked on's words (given, not typed: nothing is searched for that). */
    private fun syncSearchBox() {
        val q = chosen.query
        if (search.text.toString() == q) return
        syncingSearch = true
        search.setText(q)
        search.setSelection(q.length)
        syncingSearch = false
    }

    /** The side's search ends, as when its words are cleared by hand (the box too, for the side worked on). */
    private fun clearQuery() {
        if (query.isEmpty()) return
        query = ""
        if (pane === chosen) syncSearchBox()
        scheduleSearch()
    }

    /** Where the other side is (on show), or was left (not on show). */
    private fun otherPlaceKey(): String? = otherPane().let { o -> if (o.attached) withPane(o) { placeKey() } else o.pending }

    /** The other side's folder, when it can take files: not its start page, a category, the bin, nor inside an archive. */
    private fun otherFolder(): File? {
        if (!twoPanes) return null
        val o = otherPane()
        return TwoPanes.target(withPane(o) { placeKey() }, o.index != null)?.let { o.dir }
    }

    /** "Copy to the right" and "Move to the left": into the folder on show there, as a paste there would (the same job, questions and progress). */
    private fun toOtherSide(files: List<File>, move: Boolean) {
        if (files.isEmpty()) return
        val dest = otherFolder() ?: return say(noOtherFolder())
        transfer(files, dest, move, here = false) { clearSelection() }
    }

    /** The same in one thing's sheet, while two folders show. */
    private fun otherSideActions(files: List<File>): List<Sheets.Action> = if (!twoPanes) emptyList() else otherSideWord().let { toward ->
        listOf(
            Sheets.Action(R.drawable.ic_copy, "Copy to the $toward") { toOtherSide(files, move = false) },
            Sheets.Action(R.drawable.ic_move, "Move to the $toward") { toOtherSide(files, move = true) },
        )
    }

    /** Which way the other side is on the screen, "right" or "left" (the sides swap round in a right-to-left language). */
    private fun otherSideWord(): String =
        TwoPanes.toward(chosen.side, resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)

    /** Said when the other side shows no folder to put things in (its start page, a category, the bin, an archive). */
    private fun noOtherFolder() = "Open a folder on the ${otherSideWord()} first"

    /** The button at the top: two folders side by side, or one (remembered in FilesSettings). */
    private fun toggleTwoPanes() {
        FilesSettings.twoPanes = !FilesSettings.twoPanes
        settingsShown = settingsKey()
        setTwoPanes(twoOffered && FilesSettings.twoPanes)
    }

    /**
     * Two sides, or one. The other side comes back where it was left (the first time, at this side's
     * folder), or goes, kept as it is for when it comes back. The side worked on stays, its list made
     * again for its new width (tiles, the start page's cards).
     */
    private fun setTwoPanes(two: Boolean) {
        if (two == twoPanes) return
        val here = withPane(chosen) { placeKey() }
        val o = otherPane()
        if (!two) withPane(o) {
            o.pending = placeKey()
            hidePane()
        }
        twoPanes = two
        o.attached = two
        computeGrid()
        arrangePanes()
        remake(chosen)
        if (two) withPane(o) { goTo(otherStart(TwoPanes.places(chosen.side, here, o.pending, two = true)[o.side])) }
        updateChrome()
        crossFade(listRow)
    }

    /** Where the other side comes back to, but not into Private while it is locked (its start page then, with no question). */
    private fun otherStart(place: String?): String? =
        place?.takeUnless { it.startsWith("/") && PrivateFolder.isInside(this, File(it)) && !PrivateFolder.isUnlocked }

    /**
     * The side goes off the screen: what it was doing stops, and what it read goes (light on a 2 GB
     * phone: it is read again when it comes back, where it was, kept in [Pane.pending]).
     */
    private fun hidePane() {
        rememberPosition()
        placeGen++
        loadJob?.cancel()
        searchJob?.cancel()
        stopSizing()
        list.removeCallbacks(showLoading)
        list.removeCallbacks(searchSoon)
        loading = false
        searching = false
        selected.clear()
        listed = emptyList()
        rows = emptyList()
        searchRows = null
        binItems = emptyMap()
        index = null
        adapter.set(emptyList())
    }

    /** [p]'s list is made again for its new width (its tiles, the start page's cards), at the same place. */
    private fun remake(p: Pane) = withPane(p) {
        val lm = list.layoutManager as? LinearLayoutManager
        val at = lm?.findFirstVisibleItemPosition() ?: 0
        list.adapter = null
        list.recycledViewPool.clear()
        list.recycledViewPool.setMaxRecycledViews(TYPE_TILE, gridCols * 6)
        list.adapter = adapter
        applyLayout()
        if (at > 0) lm?.scrollToPositionWithOffset(at, 0)
    }

    /** Each side on show reads its place again (something changed that either may show); the start page once. */
    private fun reloadShown() {
        var homeRead = false
        for (p in shownPanes()) withPane(p) {
            if (home && index == null) {
                if (!homeRead) {
                    homeRead = true
                    loadHome()
                }
            } else {
                reload()
            }
        }
    }

    /** The start page again on each side showing it (what it shows is shared); [others]: not on the side worked on. */
    private fun renderHomes(removals: Boolean = false, others: Boolean = false) {
        for (p in shownPanes()) if (!others || p !== pane) withPane(p) { if (onStartPage) render(removals) }
    }

    // ------------------------------------------------------------------ 3.0.4: folder sizes

    /** The folders whose sizes are being measured now, and the work doing it (one folder at a time, off the main thread). */
    private var sizing: Set<String>
        get() = pane.sizing
        set(v) { pane.sizing = v }
    private var sizeJob: Job?
        get() = pane.sizeJob
        set(v) { pane.sizeJob = v }

    /** The list was touched since it showed: sorted by size, its folders no longer move once measured. */
    private var touchedSinceShown: Boolean
        get() = pane.touchedSinceShown
        set(v) { pane.touchedSinceShown = v }

    /**
     * 3.0.4: the folders on show are measured (FolderSizes), and each row fills in its size as it
     * comes: in folders on the phone, the SD card and Private, in search results and among the start
     * page's favourites. Not in an archive, nor in the recycle bin (its rows say what they hold).
     */
    private fun measureShown() {
        if (loading) return
        if (!hasAccess() || index != null || inBin) {
            stopSizing()
            return
        }
        startSizing(adapter.items.mapNotNull { (it as? Row)?.takeIf { r -> r.isDir && r.file != null && r.entry == null }?.key })
    }

    /**
     * Measures those of [folders] not measured lately, in their order; null when none needs it.
     * Each size shows as it comes ([sizeKnown]); once all are known, [sizesDone]. Already being
     * measured: that goes on.
     */
    private fun startSizing(folders: List<String>): Job? {
        val now = SystemClock.elapsedRealtime()
        val todo = folders.filter { !FolderSizes.fresh(it, now) }
        val running = sizeJob
        if (todo.isNotEmpty() && running?.isActive == true && sizing.containsAll(todo)) return running
        stopSizing()
        if (todo.isEmpty()) return null
        // What the job still has to do (one it has passed and that changed since is measured anew).
        val left = LinkedHashSet(todo)
        sizing = left
        val gen = placeGen
        val p = pane
        val job = lifecycleScope.launch {
            for (path in todo) {
                // Measured meanwhile (the folder above it was, or the other side's job): nothing to walk.
                var s = withContext(sizeThread) { if (FolderSizes.fresh(path)) FolderSizes.cached(path) else FolderSizes.measure(File(path)) { !isActive } }
                // Something in it changed while it was walked: once more.
                if (s == null && isActive) s = withContext(sizeThread) { FolderSizes.measure(File(path)) { !isActive } }
                left.remove(path)
                if (s != null) sizeKnown(path)
            }
            if (gen == p.placeGen) withPane(p) { sizesDone() }
        }
        sizeJob = job
        return job
    }

    private fun stopSizing() {
        sizeJob?.cancel()
        sizeJob = null
        sizing = emptySet()
    }

    /** 3.0.4: [path]'s size came: its row or tile shows it, and nothing else moves. */
    private fun sizeKnown(path: String) {
        // 3.0.4: on either side.
        for (p in shownPanes()) {
            val items = p.adapter.items
            for (i in items.indices) if ((items[i] as? Row)?.key == path) p.adapter.notifyItemChanged(i, PAYLOAD_SIZE)
        }
    }

    /**
     * 3.0.4: every folder on show has its size. Sorted by size, they take their places now, unless
     * the list was touched since it showed (rows mustn't move under a finger): then they stay as
     * they are until the folder is opened again.
     */
    private fun sizesDone() {
        if (sort != FileOps.SORT_SIZE || loading || home || category != null || inBin || index != null || searchRows != null) return
        if (touchedSinceShown || selected.isNotEmpty()) return
        val sorted = sortedWithSizes(listed, sort)
        if (sorted.map { it.file.path } == listed.map { it.file.path }) return
        listed = sorted
        rows = listed.map { rowOf(it) }
        render()
    }

    /** 3.0.4: [items] in [order]; by size, folders go by their sizes as known now (a copy: they mustn't change while it sorts). */
    private fun sortedWithSizes(items: List<FileItem>, order: Int): List<FileItem> {
        if (order != FileOps.SORT_SIZE) return FileOps.sorted(items, order)
        val known = FolderSizes.bytesFor(items.filter { it.isDir }.map { it.file.path })
        return FileOps.sorted(items, order) { known[it] }
    }

    /** "1.40 GB" (or "over 1.40 GB") for a folder's row once measured; null otherwise, and for an empty one. */
    private fun sizeText(r: Row, owner: Pane = pane): String? {
        val f = r.file ?: return null
        if (!r.isDir || r.entry != null || r.count == 0 || owner.inBin) return null
        return FolderSizes.cached(f.path)?.let { FolderSizes.text(it) }
    }

    /** A row's line under its name again; a tile's size under its icon, when it fits there. */
    private fun bindSize(owner: Pane, h: FileHolder, r: Row) {
        if (!h.tile) {
            h.subtitle?.text = subtitleOf(r, owner)
            return
        }
        val label = h.size ?: return
        val text = sizeText(r, owner)
        if (text != null && label.paint.measureText(text) <= tilePx - dp(12)) {
            if (label.text != text) label.text = text
            label.visibility = View.VISIBLE
        } else {
            label.visibility = View.GONE
        }
    }

    /** "Refresh" in a folder: read again, its folders measured again too. */
    private fun refreshFolder() {
        FolderSizes.changed(listOf(dir.path))
        load(keepScroll = true)
    }

    /**
     * One list for rows, tiles and the start page: rows and the start page's parts take the whole
     * width. 3.0.4: each side's list ([p]'s), made the same way.
     */
    private fun setUpList(p: Pane) {
        val rv = p.list
        val lm = GridLayoutManager(this, 1)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (p.tiles && p.adapter.items.getOrNull(position) is Row) 1 else lm.spanCount
        }
        rv.layoutManager = lm
        rv.adapter = p.adapter
        // 2.9: only removals (and the rows closing up after them) move, briefly; everything else changes in place.
        rv.itemAnimator = Motion.listAnimator()
        rv.setHasFixedSize(true)
        // A screen of tiles and a few more, so going back and forth between list and grid makes few new ones.
        rv.recycledViewPool.setMaxRecycledViews(TYPE_TILE, gridCols * 6)
        // 3.0.3: after a hold, sliding the finger selects (or unselects) the files it passes.
        rv.addOnItemTouchListener(slideTouch)
    }

    /** A side's list: [owner]'s rows, bound as that side has them (the other side may be the one worked on). */
    private inner class Adapter(val owner: Pane) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Any> = emptyList()
            private set

        /**
         * Shows [list]. With [removals] (2.9), when it is the list on show less a few things (deleted,
         * moved away), those close up one by one; otherwise (and for many at once) all show anew.
         */
        fun set(list: List<Any>, removals: Boolean = false, additions: Boolean = false) {
            // 3.0.3: a slide's positions belong to the list as it was (3.0.4: a slide is on the side touched).
            if (owner === chosen) endSlide()
            val old = items
            items = list
            val gone = old.size - list.size
            // The other way round: what Undo put back comes in between the rows that stayed.
            if (additions && animationsOn && -gone in 1..MAX_ANIMATED) {
                val added = ArrayList<Int>(-gone)
                var j = 0
                for (i in list.indices) {
                    if (j < old.size && keyOf(old[j]) == keyOf(list[i])) j++ else added += i
                }
                if (j == old.size && added.size == -gone) {
                    for (p in added) notifyItemInserted(p)
                    if (startPage) notifyItemRangeChanged(0, list.size, PAYLOAD_CORNERS)
                    return
                }
            }
            if (removals && animationsOn && gone in 1..MAX_ANIMATED) {
                val removed = ArrayList<Int>(gone)
                var j = 0
                for (i in old.indices) {
                    if (j < list.size && keyOf(old[i]) == keyOf(list[j])) j++ else removed += i
                }
                if (j == list.size && removed.size == gone) {
                    for (p in removed.asReversed()) notifyItemRemoved(p)
                    // 3.0.5: the start page's rows are grouped pieces: those left get their corners again.
                    if (startPage) notifyItemRangeChanged(0, list.size, PAYLOAD_CORNERS)
                    return
                }
            }
            notifyDataSetChanged()
        }

        /** 3.0.5: this side shows its start page (a search or an archive show their own rows). */
        private val startPage: Boolean get() = owner.home && owner.index == null && owner.searchRows == null

        /** 3.0.5: a start page row's group: rows next to each other go together, anything else ends a group. */
        private val groupOf: (Int) -> Any? = { i -> if (items[i] is Row) 0 else null }

        private fun keyOf(o: Any): Any = when (o) {
            is Row -> o.key
            is Hint -> "hint:" + o.text
            is Header -> "header:" + o.text + "|" + o.action
            is StorageCard -> "storage:" + o.volume.root.path
            is DocFilter -> "filter:" + o.labels.joinToString(",") + "|" + o.chosen
            is CategoryGrid -> "categories"
            is ToolGrid -> "tools"
            is RecentDocs -> "docs:" + o.docs.joinToString(",") { it.id }
            is ContinueCards -> "continue:" + o.items.joinToString(",") { it.url + ":" + it.progress }
            else -> o
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            // 3.0.5: on the start page, rows as grouped pieces on the settings' page; elsewhere flat rows.
            is Row -> if (owner.tiles) TYPE_TILE else if (startPage) TYPE_HOME_ROW else TYPE_ROW
            is Header -> TYPE_HEADER
            is StorageCard -> TYPE_STORAGE
            is CategoryGrid -> TYPE_CATEGORIES
            is ToolGrid -> TYPE_TOOLS
            is DocFilter -> TYPE_DOC_FILTER
            is RecentDocs -> TYPE_DOCS
            is ContinueCards -> TYPE_CONTINUE
            else -> TYPE_HINT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            TYPE_ROW -> makeRow()
            TYPE_HOME_ROW -> makeRow(onCard = true)
            TYPE_TILE -> makeTile()
            TYPE_HEADER -> makeHeader()
            TYPE_STORAGE -> makeStorage()
            TYPE_CATEGORIES -> makeCategories()
            TYPE_TOOLS -> makeTools()
            TYPE_DOCS -> makeRecentDocs()
            TYPE_CONTINUE -> makeContinue()
            TYPE_DOC_FILTER -> FilterHolder(FrameLayout(this@FilesActivity).apply {
                setPadding(0, dp(4), 0, dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            else -> HintHolder(Ui.text(this@FilesActivity, Ui.Type.BODY_SECONDARY, "").apply {
                setPadding(dp(Ui.MARGIN), dp(2), dp(Ui.MARGIN), dp(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            val item = items[position]
            // 3.0.3: only the videos' watched marks are asked for again: nothing else changes.
            if (payloads.isNotEmpty() && item !is Row && payloads.all { it == PAYLOAD_WATCHED || it == PAYLOAD_SIZE || it == PAYLOAD_CORNERS }) return
            when (item) {
                is Row -> {
                    val h = holder as FileHolder
                    // Only the selection changed: the tick and the marking, not the picture (3.0.3: or
                    // only how much of a video was watched).
                    if (payloads.isNotEmpty() && h.boundKey == item.key) {
                        if (PAYLOAD_WATCHED in payloads) markWatched(owner, h, item)
                        if (PAYLOAD_SIZE in payloads) bindSize(owner, h, item)
                        if (PAYLOAD_TOGGLED in payloads || PAYLOAD_SELECTION in payloads) bindSelection(owner, h, item, PAYLOAD_TOGGLED in payloads, fade = PAYLOAD_SELECTION in payloads)
                        if (PAYLOAD_CORNERS in payloads && h.onCard) GroupedRows.place(h.itemView, position, items.size, groupOf)
                    } else {
                        bind(owner, h, item)
                        if (h.onCard) GroupedRows.place(h.itemView, position, items.size, groupOf)
                    }
                }
                is Header -> bindHeader(holder as HeaderHolder, item, position == 0)
                is StorageCard -> bindStorage(holder as StorageHolder, item)
                is CategoryGrid -> bindCategories(holder as CategoriesHolder, item)
                is ToolGrid -> bindTools(holder as ToolsHolder, item)
                is DocFilter -> bindDocFilter(holder as FilterHolder, item)
                is RecentDocs -> bindRecentDocs(holder as DocsHolder, item)
                is ContinueCards -> (holder as ContinueHolder).row.show(item.items)
                is Hint -> (holder as HintHolder).text.let { t ->
                    t.text = item.text
                    // 3.0.5: lined up with the section headings and cards around it on the start page, with the rows elsewhere.
                    val x = dp(if (startPage) Ui.CONTENT_X else Ui.MARGIN)
                    if (t.paddingLeft != x) t.setPadding(x, t.paddingTop, x, t.paddingBottom)
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is FileHolder) {
                holder.thumb?.cancel()
                holder.thumb = null
                Motion.stopPicture(holder.icon)
            }
        }
    }

    /** The row [h] shows (3.0.4: in its own side's list). */
    private fun rowAt(h: RecyclerView.ViewHolder): Row? = (h.bindingAdapter as? Adapter)?.items?.getOrNull(h.bindingAdapterPosition) as? Row

    /** 3.0.4: the side [h] is on. */
    private fun paneOf(h: RecyclerView.ViewHolder): Pane? = (h.bindingAdapter as? Adapter)?.owner

    /**
     * A row of a folder: flat on the page, at the side margin. [onCard] (3.0.5): a row of the start
     * page: a rounded piece of a group at the side margin (GroupedRows), like History's rows and the
     * start page's cards, so everything on that page lines up under its heading.
     */
    private fun makeRow(onCard: Boolean = false): FileHolder {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ui.MARGIN), dp(8), dp(8), dp(8))
            minimumHeight = dp(Ui.ROW_TWO_LINES)
            if (!onCard) setBackgroundResource(RowHolder.selectableBackground(ctx))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (onCard) {
                    leftMargin = dp(Ui.MARGIN)
                    rightMargin = dp(Ui.MARGIN)
                }
            }
        }
        if (onCard) GroupedRows.prepare(root)
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
            background = Ui.rippleBorderless(ctx)
            Ui.pressable(this, Motion.PRESS_ICON, morph = false)
        }
        root.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        // 2.8: a tick box in ⋮'s place while selecting (the same width: the name doesn't move).
        val check = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(check, LinearLayout.LayoutParams(dp(44), dp(44)))
        // The start page's rows are never selected: they keep their own piece.
        if (!onCard) root.background = MarkFill.row(ctx)
        val h = FileHolder(root, false, root, icon, icon, check, title, subtitle, more, null, onCard = onCard)
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
            setPadding(dp(TILE_GAP), dp(TILE_GAP), dp(TILE_GAP), dp(2))
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
            background = Ui.shape(ctx, Tones.withAlpha(Palette.color(ctx, R.color.page_bg), 0xE6), Ui.R_XSMALL)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(check, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        // 3.0.4: a folder's size, in the room under its icon (only when it fits there).
        val size = Ui.text(ctx, Ui.Type.CAPTION, "", Ui.onAccentContainer(ctx)).apply {
            isSingleLine = true
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        box.addView(size, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(5) })
        card.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = Ui.text(ctx, Ui.Type.BODY, "").apply {
            Ui.size(this, Ui.Type.LABEL, keepFont = true)
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
            Ui.pressable(this, Motion.PRESS_ICON, morph = false)
        }
        line.addView(more, LinearLayout.LayoutParams(dp(32), dp(36)))
        card.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        val h = FileHolder(root, true, card, box, icon, check, title, null, more, play, size)
        card.background = MarkFill.tile(ctx, TILE_RADIUS)
        return wire(h)
    }

    /** What a row or tile does when tapped, held or its ⋮ is tapped; and its two backgrounds. */
    private fun wire(h: FileHolder): FileHolder {
        // 3.0.5: the one row icon holder, a rounded square (Ui.R_SMALL; rows had 10 dp and tiles 14 dp
        // corners). On a card its fill is a step off the card (surface_alt, as History's rows); flat on the
        // page (rows, tiles) it is the card colour, which shows on the settings' colour behind it. A
        // tile's picture nests in the tile's own corners (the tile shows when selected or pressed).
        val radius = if (h.tile) Corners.nested(TILE_RADIUS, TILE_GAP) else Ui.R_SMALL
        h.folderFill = Ui.shape(this, Ui.accentContainer(this), radius)
        h.fileFill = Ui.shape(this, Palette.color(this, if (h.onCard) R.color.surface_alt else R.color.card_bg), radius)
        // 3.0.4: a row's side is the one worked on once it is used (TalkBack's taps come without a touch).
        h.card.setOnClickListener {
            paneOf(h)?.let { setChosen(it) }
            val r = rowAt(h) ?: return@setOnClickListener
            when {
                // Picking several: only files can be chosen.
                selected.isNotEmpty() && picking && r.isDir -> {}
                selected.isNotEmpty() -> toggle(r, h.bindingAdapterPosition)
                else -> open(r)
            }
        }
        h.card.setOnLongClickListener { v ->
            paneOf(h)?.let { setChosen(it) }
            val r = rowAt(h) ?: return@setOnLongClickListener false
            // Picking: a hold starts choosing several, on files, where the page takes several.
            if (picking && (pickRequest?.multiple != true || r.isDir || !canSelect)) return@setOnLongClickListener true
            if (canSelect) {
                Haptics.hold(v)
                toggle(r, h.bindingAdapterPosition)
                // 3.0.3: sliding on from here does the same to the files the finger passes.
                if (!picking) armSlide(h.bindingAdapterPosition, r.key in selected)
            } else {
                Haptics.hold(v)
                itemSheet(r)
            }
            true
        }
        h.more.setOnClickListener {
            paneOf(h)?.let { setChosen(it) }
            rowAt(h)?.let { itemSheet(it) }
        }
        return h
    }

    private fun bind(owner: Pane, h: FileHolder, r: Row) {
        h.boundKey = r.key
        // 3.0.10: the words searched for marked, as History, Bookmarks and Downloads have them.
        val q = owner.query.trim()
        h.title.text = if (q.isEmpty()) r.name else markedText(r.name, q, Ui.accent(this))
        h.subtitle?.let { s ->
            // The bin's rows say where things were on a second line.
            val lines = if (owner.inBin) 2 else 1
            if (s.maxLines != lines) s.maxLines = lines
            s.text = subtitleText(r, owner)
        }
        if (h.tile) bindSize(owner, h, r)
        h.thumb?.cancel()
        h.thumb = null
        Motion.stopPicture(h.icon)
        h.icon.tag = r.key
        setIcon(h, r)
        h.play?.visibility = if (r.kind == FileKind.VIDEO) View.VISIBLE else View.GONE
        markWatched(owner, h, r)
        val f = r.file
        if (f != null && !r.isDir && thumbsOn && FileThumbs.has(r.kind)) {
            val key = r.key
            // An app's icon stays an icon on a tile, not a blown-up square.
            val apkOnTile = h.tile && r.kind == FileKind.APK
            // From memory it comes during this call; one made later comes in softly.
            var binding = true
            h.thumb = FileThumbs.load(this, f, r.time, r.kind, if (h.tile) tilePx else thumbPx) { bmp ->
                if (h.icon.tag != key) return@load
                h.icon.imageTintList = null
                val p = if (apkOnTile) tileIconPad / 2 else 0
                h.icon.setPadding(p, p, p, p)
                h.icon.scaleType = if (apkOnTile) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                h.icon.setImageBitmap(bmp)
                if (!binding) Motion.fadePicture(h.icon)
            }
            binding = false
        }
        bindSelection(owner, h, r, false)
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
        // 3.0.4: a storage's standard folders (DCIM, Download, Pictures…) show what they hold, with a
        // small mark inside the folder (in its own colours: no tint over it).
        val mark = if (r.isDir) r.file?.let { specialFolder(it.path) } else null
        val marked = mark?.let { folderIcon(h, it) }
        if (marked != null) {
            v.imageTintList = null
            v.setImageDrawable(marked)
            return
        }
        v.setImageResource(if (r.isDir) R.drawable.ic_folder else r.kind.icon)
        v.imageTintList = if (r.isDir) onContainerTint else secondaryTint
    }

    /** 3.0.4: the storages' top folders, as SpecialFolders takes them (worked out again only when the storages change). */
    private var markRoots: List<String> = emptyList()
    private var markVolumes: List<Volumes.Volume>? = null

    /** 3.0.4: the mark of the folder at [path] if it is one of a storage's standard folders. */
    private fun specialFolder(path: String): SpecialFolders.Mark? {
        val all = Volumes.all(this)
        if (all !== markVolumes) {
            markVolumes = all
            markRoots = all.map { it.root.path }
        }
        return SpecialFolders.of(path, markRoots)
    }

    /** 3.0.4: [mark]'s folder icon for [h], made the first time it shows one (a recycled row keeps it). */
    private fun folderIcon(h: FileHolder, mark: SpecialFolders.Mark): Drawable? {
        val icons = h.folderIcons ?: arrayOfNulls<Drawable>(SpecialFolders.Mark.entries.size).also { h.folderIcons = it }
        return icons[mark.ordinal] ?: FolderIcons.make(this, mark, Ui.onAccentContainer(this), Ui.accentContainer(this))?.also { icons[mark.ordinal] = it }
    }

    /** The tick box while selecting (a small pop when tapped), and the selected row's or tile's marking. */
    private fun bindSelection(owner: Pane, h: FileHolder, r: Row, toggled: Boolean, fade: Boolean = false) {
        val selecting = owner.selected.isNotEmpty()
        val on = r.key in owner.selected
        // 2.9: the tick boxes fade in as selecting starts and out as it ends (only the rows on screen
        // are bound, so it is a few). A tile keeps ⋮'s room, so the grid's lines don't change height.
        val noMore = if (h.tile) View.INVISIBLE else View.GONE
        Ticks.show(h.check, h.more, selecting, fade && animationsOn, noMore, if (picking) noMore else View.VISIBLE)
        if (selecting) {
            h.check.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)
            h.check.imageTintList = if (on) accentTint else secondaryTint
            if (toggled && on) {
                // 3.0.3: the bump stops every animation of the box, the fade-in above too, which left the
                // held file's box invisible: it shows at once, with its pop.
                h.check.alpha = 1f
                Motion.bump(h.check, 1.12f)
            }
        }
        if (on != h.marked) {
            h.marked = on
            // Fades over the row's own background, so a press on it isn't cut off.
            MarkFill.set(h.card, on, animate = (toggled || fade) && animationsOn)
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

    /** The start page's cards: 3.0.5: plain filled like every card, no outline (the page behind them is a step darker). */
    private fun cardFill(): Drawable =
        Ui.ripple(this, Ui.shape(this, Palette.color(this, R.color.card_bg), Ui.R_MEDIUM), Ui.R_MEDIUM)

    private fun makeHeader(): HeaderHolder {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 3.0.5: lined up with the first icon or text in the cards and rows under it, with the
            // one section rhythm (the taller "Show all" doesn't change it).
            setPadding(dp(Ui.CONTENT_X), dp(Ui.SECTION_ABOVE), dp(12), dp(Ui.SECTION_BELOW))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val title = Ui.text(ctx, Ui.Type.SECTION, "")
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = Ui.text(ctx, Ui.Type.LABEL, "", Ui.accent(ctx)).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = Ui.ripple(ctx, null, Ui.R_SMALL)
            Ui.pressable(this, Motion.PRESS_BUTTON, morph = false)
        }
        row.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = -dp(4)
            bottomMargin = -dp(4)
        })
        return HeaderHolder(row, title, action)
    }

    private fun bindHeader(h: HeaderHolder, item: Header, first: Boolean) {
        // The first one sits close under the search box, which leaves its own space.
        val top = dp(if (first) 8 else Ui.SECTION_ABOVE)
        if (h.itemView.paddingTop != top) h.itemView.setPadding(h.itemView.paddingLeft, top, h.itemView.paddingRight, h.itemView.paddingBottom)
        h.title.text = item.text
        h.action.text = item.action.orEmpty()
        h.action.visibility = if (item.action != null) View.VISIBLE else View.GONE
        h.action.setOnClickListener { item.onAction?.invoke() }
    }

    /**
     * A storage: its name, what is used and free, and a bar of how full it is; tapping opens its top
     * folder. 3.0.4: the bar goes across the card, split by what fills the storage (pictures,
     * videos, music, documents, the rest), with a small legend under it once that is known.
     */
    private fun makeStorage(): StorageHolder {
        val ctx = this
        val outer = FrameLayout(ctx).apply {
            setPadding(dp(Ui.MARGIN), dp(4), dp(Ui.MARGIN), dp(4))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.ROW_INSET), dp(14), dp(Ui.ROW_INSET), dp(14))
            background = cardFill()
            Ui.pressable(this, Motion.PRESS_CARD)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = Ui.iconBadge(ctx, R.drawable.ic_phone)
        head.addView(icon, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val name = Ui.text(ctx, Ui.Type.SUBTITLE, "", lines = 1)
        val detail = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
        texts.addView(name)
        texts.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        val bar = StorageBar(ctx)
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(14) })
        // The legend: a dot, a name and a size for each part that has anything, two (on a wide side
        // three) to a line.
        val perLine = if (paneWidthDp >= 480) 3 else 2
        val legend = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val lines = ArrayList<View>()
        val cells = ArrayList<View>()
        val dots = ArrayList<View>()
        val labels = ArrayList<TextView>()
        repeat((LEGEND_PARTS + perLine - 1) / perLine) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(perLine) {
                val dot = View(ctx).apply { background = Ui.circle(0) }
                val label = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1)
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                line.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                cells += cell
                dots += dot
                labels += label
            }
            legend.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            lines += line
        }
        card.addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        outer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val h = StorageHolder(outer, card, icon, name, bar, detail, legend, lines, cells, dots, labels)
        card.setOnClickListener { (rowsOf(h).getOrNull(h.bindingAdapterPosition) as? StorageCard)?.let { openFolder(it.volume.root) } }
        return h
    }

    /** 3.0.4: the storage bar's colours for pictures, videos, music and documents, and the rest: with the accent chosen (StorageColours). */
    private val kindColors by lazy { StorageColours.kinds(Ui.accent(this), Palette.color(this, R.color.card_bg), Palette.isNight(this)) }
    private val otherColor by lazy { StorageColours.other(Palette.color(this, R.color.card_bg), Palette.isNight(this)) }

    private fun bindStorage(h: StorageHolder, s: StorageCard) {
        h.icon.setImageDrawable(Ui.icon(this, if (s.volume.removable) R.drawable.ic_sd_card else R.drawable.ic_phone, Ui.onAccentContainer(this)))
        h.name.text = s.volume.name
        val known = s.total > 0 && s.free >= 0
        val used = if (known) s.total - s.free else 0L
        // Nearly full: what is left shows in the warning colour, and so does the line saying it.
        val full = known && s.free < s.total / 10
        val freeColor = if (full) Ui.danger(this) else Palette.color(this, R.color.surface_alt)
        val split = if (known) s.kinds?.let { StorageKinds.split(s.total, s.free, it) } else null
        when {
            split != null -> h.bar.show(split.parts(), intArrayOf(kindColors[0], kindColors[1], kindColors[2], kindColors[3], otherColor, freeColor))
            // Until what fills it is known: used and free, as before.
            known -> h.bar.show(longArrayOf(used, s.free), intArrayOf(Ui.accent(this), freeColor))
            else -> h.bar.show(longArrayOf(0, 1), intArrayOf(Ui.accent(this), freeColor))
        }
        h.detail.text = if (known) "${UrlUtils.formatBytes(used)} used · ${UrlUtils.formatBytes(s.free)} free" else "Measuring…"
        h.detail.setTextColor(if (full) Ui.danger(this) else Ui.textSecondary(this))
        val said = bindLegend(h, split, known)
        h.card.contentDescription = s.volume.name +
            (if (known) ", ${UrlUtils.formatBytes(used)} used, ${UrlUtils.formatBytes(s.free)} free of ${UrlUtils.formatBytes(s.total)}" else "") +
            (if (said.isNotEmpty()) ". $said" else "")
    }

    /**
     * 3.0.4: the legend under the bar: each part with anything in it (in the bar's order), or nothing
     * until [split] is known: its room is kept then ([known]: the storage's size is known, so the legend
     * is on its way), so nothing below moves when it comes. It fades in the first time it comes.
     * Returns what it says, for TalkBack.
     */
    private fun bindLegend(h: StorageHolder, split: StorageKinds.Split?, known: Boolean): String {
        if (split == null) {
            if (known) for (line in h.lines) line.visibility = View.VISIBLE
            h.legend.visibility = if (known) View.INVISIBLE else View.GONE
            return ""
        }
        val sizes = longArrayOf(split.pictures, split.videos, split.music, split.documents, split.other)
        val colors = intArrayOf(kindColors[0], kindColors[1], kindColors[2], kindColors[3], otherColor)
        val said = StringBuilder()
        var cell = 0
        for (i in sizes.indices) {
            if (sizes[i] <= 0 || cell >= h.cells.size) continue
            val size = UrlUtils.formatBytes(sizes[i])
            (h.dots[cell].background as? android.graphics.drawable.GradientDrawable)?.setColor(colors[i])
            h.texts[cell].text = android.text.SpannableString("${LEGEND_NAMES[i]}  $size").apply {
                // The size in the text's colour, the name a little softer.
                setSpan(android.text.style.ForegroundColorSpan(Ui.text(this@FilesActivity)), length - size.length, length, 0)
            }
            h.cells[cell].visibility = View.VISIBLE
            if (said.isNotEmpty()) said.append(", ")
            said.append(LEGEND_NAMES[i]).append(' ').append(size)
            cell++
        }
        for (i in cell until h.cells.size) h.cells[i].visibility = View.INVISIBLE
        // A line with nothing in it takes no room.
        val perLine = h.cells.size / h.lines.size
        for ((n, line) in h.lines.withIndex()) line.visibility = if (n * perLine < cell) View.VISIBLE else View.GONE
        if (h.legend.visibility != View.VISIBLE) {
            if (animationsOn && h.legend.isAttachedToWindow) Motion.fadeIn(h.legend, fromDy = 0) else h.legend.visibility = View.VISIBLE
        }
        return said.toString()
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
        // 3.0.4: as wide as a side is.
        val perLine = if (paneWidthDp >= 480) 3 else 2
        for (line in FileIndex.Category.shown.chunked(perLine)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in line) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(Ui.ROW_INSET), dp(12), dp(8), dp(12))
                    background = cardFill()
                    Ui.pressable(this, Motion.PRESS_CARD)
                    setOnClickListener { openCategory(c) }
                }
                card.addView(Ui.iconBadge(ctx, c.icon), LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(12) })
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

    /**
     * 2.9: Free up space, Recycle bin, Private and Wi-Fi transfer: one group of rows, matching
     * Favourites and Recent below (as cards two to a line, their names were cut off on a narrow phone).
     * Its name always shows; the caption at the row's end takes what room is left.
     */
    private fun makeTools(): ToolsHolder {
        val ctx = this
        val group = GroupLayout(ctx).apply {
            setPadding(dp(Ui.MARGIN), 0, dp(Ui.MARGIN), 0)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val captions = HashMap<Tool, Pair<View, TextView>>()
        for (t in Tool.entries) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.ROW_ONE_LINE)
                setPadding(dp(Ui.ROW_INSET), dp(8), dp(12), dp(8))
                setOnClickListener { onTool(t) }
            }
            Piece.attach(row, tappable = true)
            row.addView(Ui.iconBadge(ctx, t.icon), LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(14) })
            row.addView(Ui.text(ctx, Ui.Type.BODY, t.label, lines = 1), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val caption = Ui.text(ctx, Ui.Type.CAPTION, "", lines = 1).apply { gravity = Gravity.END }
            row.addView(caption, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            captions[t] = row to caption
            group.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return ToolsHolder(group, captions)
    }

    private fun bindTools(h: ToolsHolder, g: ToolGrid) {
        for ((t, views) in h.captions) {
            val text = when (t) {
                Tool.CLEAN -> "Big, twin and old files"
                Tool.BIN -> g.bin.let { b ->
                    when {
                        b == null -> "Counting…"
                        b.count == 0 -> "Empty"
                        else -> "${number(b.count)} · ${UrlUtils.formatBytes(b.bytes)}"
                    }
                }
                Tool.PRIVATE -> when {
                    PrivateFolder.isUnlocked -> "Open now"
                    FilesSettings.privateLockKind(this) == FilesSettings.LOCK_NONE -> "Set up a PIN"
                    else -> "Locked"
                }
                Tool.TRANSFER -> "Files to a computer"
                Tool.NETWORK -> com.swiftbrowser.files.net.NetServers.count(this).let { n -> if (n == 0) "On your Wi-Fi" else "$n saved" }
            }
            Motion.swapText(views.second, text)
            views.first.contentDescription = "${t.label}, $text"
        }
    }

    private fun onTool(t: Tool) {
        when (t) {
            Tool.CLEAN -> CleanerActivity.open(this)
            Tool.BIN -> openBin()
            Tool.PRIVATE -> openPrivate()
            Tool.TRANSFER -> WifiTransferActivity.open(this)
            Tool.NETWORK -> NetworkActivity.open(this)
        }
    }

    private fun bindCategories(h: CategoriesHolder, g: CategoryGrid) {
        for ((c, views) in h.counts) {
            val s = g.counts?.get(c)
            val text = when {
                g.counts == null -> "Counting…"
                s == null || s.count == 0 -> "None"
                else -> "${number(s.count)} · ${UrlUtils.formatBytes(s.bytes)}"
            }
            Motion.swapText(views.second, text)
            views.first.contentDescription = "${c.label}, $text"
        }
    }

    companion object {
        /** The folder to open (a path). */
        const val EXTRA_FOLDER = "folder"

        // 2.9: the options' keys are in FilesSettings; this is where Files was left.
        private const val PREF_LAST = "files_last_folder"

        /** 3.0.4: where the other side was left, and which side was worked on. */
        private const val PREF_OTHER = "files_last_other"
        private const val PREF_SIDE = "files_last_side"
        private const val STATE_PLACE = "place"
        private const val STATE_SIDE = "side"
        private const val STATE_OTHER = "other"
        private const val PLACE_HOME = "home"
        private const val PLACE_BIN = "bin"

        private const val PRIVATE_HINT = "Only you can open these, in Swift: other apps, the Gallery and computers can't see them. " +
            "Uninstalling Swift or clearing its data deletes them."
        private const val CATEGORY = "cat:"
        private const val MAX_FOUND = 300

        /** Recent files on the start page itself ("Show all" lists the rest). */
        private const val HOME_RECENT = 6

        /** The start page counts again when shown after this long. */
        private const val HOME_FRESH_MS = 30_000L

        private const val MENU_SELECT_ALL = 4
        private const val MENU_VIEW = 5
        private const val MENU_MORE = 6
        private const val MENU_TWO = 7

        private const val TYPE_ROW = 0
        private const val TYPE_TILE = 1
        private const val TYPE_HEADER = 2
        private const val TYPE_STORAGE = 3
        private const val TYPE_CATEGORIES = 4
        private const val TYPE_HINT = 5
        private const val TYPE_TOOLS = 6
        private const val TYPE_DOC_FILTER = 7
        private const val TYPE_DOCS = 8
        private const val TYPE_CONTINUE = 9

        /** 3.0.5: a start page row: a grouped piece; the kinds of holders don't change on a recycled row. */
        private const val TYPE_HOME_ROW = 10

        private const val SLIDE_FORWARD = 1
        private const val SLIDE_BACK = -1
        private const val SLIDE_FADE = 2

        /** 3.0.5: a grid tile's corners (dp), and how far in from them its picture sits. */
        private const val TILE_RADIUS = Ui.R_MEDIUM
        private const val TILE_GAP = 4

        /** More rows than this removed at once show anew instead of closing up one by one. */
        private const val MAX_ANIMATED = 30

        /** Recent documents on the start page. */
        private const val HOME_DOCS = 12

        /** 3.0.4: the storage bar's legend: its parts' names, in the bar's order (what is free isn't in it: the line above says it). */
        private val LEGEND_NAMES = arrayOf("Pictures", "Videos", "Music", "Documents", "Other")
        private const val LEGEND_PARTS = 5

        /** The kind of documents picked in their category (2.9), kept while Swift runs; null: all. */
        private var docGroup: DocGroup? = null

        /** Rows rebound for their selection only; the one tapped also pops its tick. */
        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_TOGGLED = "toggled"

        /** 3.0.3: rows rebound for their videos' watched marks only (back from the player). */
        private const val PAYLOAD_WATCHED = "watched"

        /** 3.0.4: rows rebound for a folder's size only, once measured. */
        private const val PAYLOAD_SIZE = "size"

        /** 3.0.5: the start page's rows given their corners again, after one of them went. */
        private const val PAYLOAD_CORNERS = "corners"

        /** 3.0.4: sorted by size, a folder waits this long at most for its folders' sizes before it shows. */
        private const val SIZE_WAIT_MS = 1_200L

        /** 3.0.4: folders are measured on a thread of their own, one at a time (a slow phone's storage does one thing best). */
        private val sizeThread by lazy { Background.single("folder-sizes").asCoroutineDispatcher() }

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


// ---------------------------------------------------------------------- what Files, Network folders and Free up space share

/** The bar at the bottom of a list screen: it slides up when it comes and back down when it goes. */
internal class BottomBar(private val bar: View) {
    /** It is sliding away (it shows until it is gone). */
    private var leaving = false

    fun show(any: Boolean, animate: Boolean) {
        val ctx = bar.context
        if (!animate || !bar.isLaidOut && !any) {
            leaving = false
            bar.animate().cancel()
            bar.translationY = 0f
            bar.visibility = if (any) View.VISIBLE else View.GONE
            return
        }
        if (any) {
            if (bar.visibility == View.VISIBLE && !leaving) return
            leaving = false
            bar.animate().cancel()
            if (bar.visibility != View.VISIBLE) {
                bar.visibility = View.VISIBLE
                bar.translationY = Ui.dpf(ctx, 72f)
            }
            Motion.anim(bar, Motion.NORMAL, Motion.enter).translationY(0f).start()
        } else if (bar.visibility == View.VISIBLE && !leaving) {
            leaving = true
            bar.animate().cancel()
            Motion.anim(bar, Motion.SHORT, Motion.exit).translationY(bar.height.toFloat().coerceAtLeast(Ui.dpf(ctx, 72f))).withEndAction {
                if (leaving) {
                    leaving = false
                    bar.visibility = View.GONE
                    bar.translationY = 0f
                }
            }.start()
        }
    }

    /** [part] of the bar comes in; while the bar itself is already up, with a short fade. */
    fun part(part: View, animate: Boolean) {
        if (part.visibility == View.VISIBLE) return
        if (animate && bar.visibility == View.VISIBLE && !leaving) {
            Motion.fadeIn(part, fromDy = 0, duration = Motion.SHORT)
        } else {
            part.visibility = View.VISIBLE
        }
    }

    companion object {
        /** A button of the bar: an icon over a short word. */
        fun button(ctx: Context, icon: Int, label: String, danger: Boolean = false, lines: Int = 1, onClick: () -> Unit): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 6))
            background = Ui.ripple(ctx, null, Ui.R_SMALL)
            contentDescription = label
            isClickable = true
            isFocusable = true
            Ui.pressable(this, Motion.PRESS_BUTTON, morph = false)
            val color = if (danger) Ui.danger(ctx) else Ui.text(ctx)
            addView(ImageView(ctx).apply {
                setImageDrawable(Ui.icon(ctx, icon, color))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)))
            addView(Ui.text(ctx, Ui.Type.CAPTION, label, color, lines = lines).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2), 0)
            })
            setOnClickListener { onClick() }
        }
    }
}

/** The place, part by part ("⌂ › Phone storage › Download"), each part as tall as the row. */
internal object PathRow {
    /** The row of parts. The first button's icon starts at the one side margin, like what is above and below it. */
    fun crumbs(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(ctx, Ui.MARGIN - 8), 0, Ui.dp(ctx, 12), 0)
    }

    /** The row of parts in a box that scrolls sideways and fades where more of it is hidden. */
    fun scroll(ctx: Context, crumbs: LinearLayout): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(crumbs)
        Ui.fadeEdges(this)
    }

    /** Shows [parts] (a name and where it goes; the last one is the place on show) after the [homeIcon] button. */
    fun show(crumbs: LinearLayout, scroll: HorizontalScrollView, homeIcon: Int, homeLabel: String, tooltip: Boolean, onHome: () -> Unit, parts: List<Pair<String, () -> Unit>>) {
        val ctx = crumbs.context
        crumbs.removeAllViews()
        crumbs.addView(ImageView(ctx).apply {
            setImageDrawable(Ui.icon(ctx, homeIcon, Ui.accent(ctx)))
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.ripple(ctx, null, Ui.R_SMALL)
            contentDescription = homeLabel
            if (tooltip) androidx.appcompat.widget.TooltipCompat.setTooltipText(this, homeLabel)
            setOnClickListener { onHome() }
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 40), ViewGroup.LayoutParams.MATCH_PARENT))
        parts.forEachIndexed { i, (label, go) ->
            crumbs.addView(ImageView(ctx).apply {
                setImageDrawable(Ui.icon(ctx, R.drawable.ic_chevron_right, Ui.textSecondary(ctx)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18)))
            val last = i == parts.size - 1
            crumbs.addView(Ui.text(ctx, Ui.Type.LABEL, label, if (last) Ui.text(ctx) else Ui.textSecondary(ctx), lines = 1).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0)
                background = Ui.ripple(ctx, null, Ui.R_SMALL)
                maxWidth = Ui.dp(ctx, 200)
                ellipsize = TextUtils.TruncateAt.MIDDLE
                if (!last) setOnClickListener { go() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
    }
}

/**
 * A row's or tile's background while selecting: its touch ripple over a fill that fades in and out
 * when it is selected (through the view's activated state, so no layer and nothing else redrawn;
 * a press in progress carries on). Flat rows stay flat, the fill a rounded shape inside the row.
 */
internal object MarkFill {
    private fun states(fill: Drawable) = android.graphics.drawable.StateListDrawable().apply {
        setEnterFadeDuration(Motion.ICON.toInt())
        setExitFadeDuration(Motion.ICON.toInt())
        addState(intArrayOf(android.R.attr.state_activated), fill)
        addState(intArrayOf(), ColorDrawable(0))
    }

    fun row(ctx: Context): Drawable {
        val side = Ui.dp(ctx, 8)
        val edge = Ui.dp(ctx, 2)
        return Ui.ripple(ctx, states(android.graphics.drawable.InsetDrawable(Ui.shape(ctx, Ui.accentContainer(ctx), Ui.R_SMALL), side, edge, side, edge)), 0)
    }

    fun tile(ctx: Context, radius: Int): Drawable =
        Ui.ripple(ctx, states(Ui.shape(ctx, Ui.accentContainer(ctx), radius)), radius)

    fun set(v: View, on: Boolean, animate: Boolean) {
        if (v.isActivated == on) return
        v.isActivated = on
        if (!animate) v.background?.jumpToCurrentState()
    }
}

/** A row's tick box, in place of its ⋮ while selecting. */
internal object Ticks {
    private val GOING = Any()

    /**
     * [selecting] or not: the tick [check] and ⋮ [more] as they should be; with [fade] the tick comes
     * or goes softly. [moreWhileSelecting]: a tile keeps ⋮'s room (INVISIBLE), a row gives it up (GONE).
     */
    fun show(check: View, more: View, selecting: Boolean, fade: Boolean, moreWhileSelecting: Int, moreOtherwise: Int = View.VISIBLE) {
        if (selecting) {
            if (check.tag === GOING) {
                check.animate().cancel()
                check.tag = null
                check.alpha = 1f
            }
            if (fade && check.visibility != View.VISIBLE) {
                check.alpha = 0f
                Motion.anim(check, Motion.SHORT, Motion.enter).alpha(1f).start()
            } else if (!fade && check.alpha != 1f) {
                check.animate().cancel()
                check.alpha = 1f
            }
            check.visibility = View.VISIBLE
            more.visibility = moreWhileSelecting
            return
        }
        when {
            // Fading away already: when it has gone, ⋮ takes its place (a row's name doesn't move meanwhile).
            fade && check.tag === GOING -> {}
            fade && check.visibility == View.VISIBLE -> {
                check.tag = GOING
                Motion.anim(check, Motion.PAGE_OUT_MS, Motion.exit).alpha(0f).withEndAction {
                    check.tag = null
                    check.alpha = 1f
                    check.visibility = View.GONE
                    more.visibility = moreOtherwise
                }.start()
            }
            else -> {
                if (check.tag === GOING || check.alpha != 1f) check.animate().cancel()
                check.tag = null
                check.alpha = 1f
                check.visibility = View.GONE
                more.visibility = moreOtherwise
            }
        }
    }
}

/** The title bars of Files, Network folders and Free up space. */
internal object TitleBar {
    /** A title whose digits don't change width as a number goes up. */
    fun steady(text: String): CharSequence = android.text.SpannableString(text).apply {
        setSpan(object : android.text.style.MetricAffectingSpan() {
            override fun updateMeasureState(p: android.text.TextPaint) { p.fontFeatureSettings = "tnum" }
            override fun updateDrawState(p: android.text.TextPaint) { p.fontFeatureSettings = "tnum" }
        }, 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** The button at the start of the title bar changes (back ↔ close) with a quick turn, keeping the bar's own colour. */
    fun swapBackIcon(toolbar: androidx.appcompat.widget.Toolbar, res: Int) {
        val button = (0 until toolbar.childCount).asSequence().map { toolbar.getChildAt(it) }
            .firstOrNull { it is ImageButton && it.drawable != null && it.drawable === toolbar.navigationIcon } as? ImageButton
        if (button == null) {
            toolbar.setNavigationIcon(res)
            return
        }
        button.imageTintList = ColorStateList.valueOf(Ui.text(toolbar.context))
        Motion.swapIcon(button, res)
    }

    /** A button of the title bar in words, in the accent colour. */
    fun addTextAction(toolbar: androidx.appcompat.widget.Toolbar, id: Int, label: String, onClick: () -> Unit): MenuItem {
        val ctx = toolbar.context
        val v = Ui.text(ctx, Ui.Type.LABEL, label, Ui.accent(ctx), lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
            background = Ui.ripple(ctx, null, 18)
            setOnClickListener { onClick() }
        }
        return toolbar.menu.add(0, id, id, label).apply {
            actionView = v
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }
}

/** What a file job shows: its bar, its window, and the question about a name that is already taken. */
internal object FileJobDialogs {
    /** A rounded bar with room for a thousand steps (a job's fraction, or a walk's). */
    fun bar(ctx: Context): ProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.progress_rounded)
        progressTintList = ColorStateList.valueOf(Ui.accent(ctx))
        indeterminateTintList = ColorStateList.valueOf(Ui.accent(ctx))
    }

    /** [job]'s progress on [bar]: a moving bar with no known end, a smooth slide with one. */
    fun setBar(bar: ProgressBar, job: FileJobs.Job) {
        val indeterminate = job.fraction < 0 || job.counting
        if (bar.isIndeterminate != indeterminate) bar.isIndeterminate = indeterminate
        if (!indeterminate) Motion.progress(bar, (job.fraction * 1000).toInt())
    }

    /** A job asks about a name already taken: Replace, Keep both, or Skip, maybe for the rest too. */
    fun askNameTaken(a: AppCompatActivity, q: FileJobs.Question) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), 0)
        }
        box.addView(Ui.text(a, Ui.Type.BODY_SECONDARY, "There's already ${if (q.isFolder) "a folder" else "a file"} called “${q.name}” in “${q.folder}”."))
        val all = CheckBox(a).apply {
            text = "Do the same for the rest"
            setPadding(Ui.dp(a, 4), 0, 0, 0)
        }
        box.addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(a, 8) })
        Palette.paint(box)
        val d = MaterialAlertDialogBuilder(a)
            .setTitle("“${q.name}” is already there")
            .setView(box)
            .setPositiveButton("Replace") { _, _ -> q.reply(FileJobs.Answer(FileJobs.Choice.REPLACE, all.isChecked)) }
            .setNegativeButton("Keep both") { _, _ -> q.reply(FileJobs.Answer(FileJobs.Choice.KEEP_BOTH, all.isChecked)) }
            .setNeutralButton("Skip") { _, _ -> q.reply(FileJobs.Answer(FileJobs.Choice.SKIP, all.isChecked)) }
            .setCancelable(false)
            .showImmersive()
        Dialogs.markDanger(d)
    }

    /** A job's progress in a window: the file, how far, Hide (it goes on) and Cancel. One screen's, at most one at a time. */
    class Progress(private val a: AppCompatActivity) {
        private var dialog: AlertDialog? = null
        private var job: FileJobs.Job? = null
        private var name: TextView? = null
        private var bar: ProgressBar? = null
        private var detail: TextView? = null

        fun show(j: FileJobs.Job) {
            if (a.isFinishing || j.finished) return
            dialog?.dismiss()
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), 0)
            }
            val nameView = Ui.text(a, Ui.Type.BODY, j.current).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val barView = FileJobDialogs.bar(a)
            val detailView = Ui.steadyFigures(Ui.text(a, Ui.Type.CAPTION, FileJobs.detail(j)))
            box.addView(nameView)
            box.addView(barView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 6)).apply {
                topMargin = Ui.dp(a, 12)
                bottomMargin = Ui.dp(a, 8)
            })
            box.addView(detailView)
            name = nameView
            bar = barView
            detail = detailView
            job = j
            dialog = MaterialAlertDialogBuilder(a)
                .setTitle(j.title)
                .setView(box)
                .setPositiveButton("Hide", null)
                .setNegativeButton("Cancel") { _, _ -> j.cancel() }
                .showImmersive()
                .also { d ->
                    d.setOnDismissListener {
                        if (dialog === d) {
                            dialog = null
                            job = null
                        }
                    }
                }
            update(j)
        }

        /** Something about the jobs changed: the window follows, or closes when its job is done. */
        fun changed() {
            val j = job ?: return
            if (j.finished) dismiss() else update(j)
        }

        private fun update(j: FileJobs.Job) {
            name?.text = j.current.ifBlank { if (j.counting) "Getting ready…" else "" }
            detail?.text = FileJobs.detail(j)
            bar?.let { setBar(it, j) }
        }

        /** The window closes if it is [j]'s. */
        fun doneWith(j: FileJobs.Job) {
            if (job === j) dismiss()
        }

        fun dismiss() {
            dialog?.dismiss()
            dialog = null
            job = null
        }
    }
}
