package com.swiftbrowser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.DocIcon
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.files.FileJobService
import com.swiftbrowser.files.FileJobs
import com.swiftbrowser.files.FileKind
import com.swiftbrowser.files.FileOpener
import com.swiftbrowser.files.FilesSettings
import com.swiftbrowser.files.net.NetAddress
import com.swiftbrowser.files.net.NetClient
import com.swiftbrowser.files.net.NetErrors
import com.swiftbrowser.files.net.NetFailure
import com.swiftbrowser.files.net.NetJobs
import com.swiftbrowser.files.net.NetPath
import com.swiftbrowser.files.net.NetProblem
import com.swiftbrowser.files.net.NetServer
import com.swiftbrowser.files.net.NetServers
import com.swiftbrowser.files.net.NetUri
import com.swiftbrowser.media.LocalPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * 3.0.4: Network folders (Files → Tools), as in MiXplorer: the shared folders of a computer or a home
 * storage on the same Wi-Fi, over SMB 2 and 3 (files.net). A computer is added by its address, with
 * a name to show and a user and password (or none: as a guest); tapping it connects (Cancel stops
 * waiting) and lists its shares, then folders and files with their size, date and icon, the path at
 * the top to go back up. Videos and music play straight from the share in Swift's player; pictures,
 * documents and other files are copied into Swift's cache and open in Swift's viewers. Hold to
 * select, then Copy to phone: into Download/Swift/<its name>/, folders too, in the background like
 * Files' own copies. Read-only on the share: nothing is uploaded, renamed or deleted there, and
 * computers aren't looked for on the network (their address is typed).
 */
class NetworkActivity : ListActivity() {
    /** A line of the list: a server, one of its shares, or a file or folder of a share. */
    private sealed class Item {
        class Server(val s: NetServer) : Item()
        class ShareRow(val share: NetClient.Share) : Item()
        class EntryRow(val e: NetClient.Entry) : Item()
    }

    /** The server on show (null: the list of servers), its share (null: its list of shares) and the folder in it. */
    private var server: NetServer? = null
    private var share: String? = null
    private var path = ""

    private var shares: List<NetClient.Share>? = null
    private var entries: List<NetClient.Entry>? = null
    private var problem: NetProblem? = null
    private var loading = false
    private var loadJob: Job? = null

    /** Changes with every move to another place: what a slow reading brings back for an old place is dropped. */
    private var placeGen = 0
    private var loadingText = ""

    /** Selected files and folders, by their path. */
    private val selected = LinkedHashMap<String, NetClient.Entry>()

    /** Servers removed but still undoable. */
    private val hiddenServers = HashSet<String>()

    private val adapter = Adapter()
    private lateinit var pathScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var jobStrip: LinearLayout
    private lateinit var jobText: TextView
    private lateinit var jobBar: ProgressBar
    private lateinit var actionBar: LinearLayout
    private var thumbPx = 0
    private var menuShown = ""
    private var navClose: Boolean? = null
    private var pendingSlide = 0

    private var opening: Messages.Handle? = null
    private var openingJob: FileJobs.Job? = null

    /** Jobs started here: they say how they went themselves. */
    private val mine = HashSet<Int>()

    /** The jobs running at the last look: one of Files' that ends meanwhile is told here (Files is behind). */
    private var seen: Map<Int, FileJobs.Job> = emptyMap()
    private var questionShown: FileJobs.Question? = null
    private var progressDialog: AlertDialog? = null
    private var progressFor: FileJobs.Job? = null
    private var progressName: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressDetail: TextView? = null

    /** What to copy once Android has answered about the storage. */
    private var pendingCopy: List<NetClient.Entry>? = null

    private val folderFill by lazy { Ui.shape(this, Ui.accentContainer(this), Ui.R_SMALL) }
    private val fileFill by lazy { Ui.shape(this, Palette.color(this, R.color.surface_alt), Ui.R_SMALL) }
    private val onContainerTint by lazy { ColorStateList.valueOf(Ui.onAccentContainer(this)) }
    private val secondaryTint by lazy { ColorStateList.valueOf(Ui.textSecondary(this)) }
    private val accentTint by lazy { ColorStateList.valueOf(Ui.accent(this)) }

    private fun dp(v: Int) = Ui.dp(this, v)

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Without it, Swift's downloads go to its own folder (Downloads.directory): the copy says where.
        pendingCopy?.let { list ->
            pendingCopy = null
            startCopy(list)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbPx = dp(44)
        toolbar.title = "Network folders"
        toolbar.setNavigationOnClickListener { if (selected.isNotEmpty()) clearSelection() else onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener {
            onMenu(it.itemId)
            true
        }
        list.adapter = adapter
        list.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            removeDuration = Motion.SHORT
            moveDuration = Motion.SHORT
            addDuration = Motion.SHORT
            changeDuration = 0
            supportsChangeAnimations = false
        }
        buildChrome()
        onBackPressedDispatcher.addCallback(this, back)
        // Turned sideways: the same place, read again.
        val s = savedInstanceState?.getString(STATE_SERVER)?.let { NetServers.get(this, it) }
        if (s != null) {
            server = s
            share = savedInstanceState.getString(STATE_SHARE)
            path = savedInstanceState.getString(STATE_PATH).orEmpty()
            load()
        } else {
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        server?.let { s ->
            outState.putString(STATE_SERVER, s.id)
            outState.putString(STATE_SHARE, share)
            outState.putString(STATE_PATH, path)
        }
    }

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

    override fun onDestroy() {
        progressDialog?.dismiss()
        opening?.dismiss()
        super.onDestroy()
    }

    /** Back, a step at a time: the waiting, the selection, up a folder, the share's list, the servers, out. */
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                loading && loadJob != null && shown().isEmpty() -> cancelLoad()
                selected.isNotEmpty() -> clearSelection()
                !up() -> finish()
            }
        }
    }

    // ------------------------------------------------------------------ the screen's parts

    private fun buildChrome() {
        val box = list.parent as ViewGroup
        val column = box.parent as LinearLayout
        crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        pathScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            addView(crumbs)
        }
        column.addView(pathScroll, column.indexOfChild(box), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Palette.color(this@NetworkActivity, R.color.sheet_bg))
        }
        bottom.addView(Ui.divider(this))
        jobStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = Ui.ripple(this@NetworkActivity, null, 0)
            visibility = View.GONE
            setOnClickListener { FileJobs.running?.let { showProgress(it) } }
        }
        jobText = Ui.text(this, Ui.Type.LABEL, "").apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        jobBar = horizontalBar()
        jobStrip.addView(jobText)
        jobStrip.addView(jobBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        bottom.addView(jobStrip)
        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            visibility = View.GONE
        }
        actionBar.addView(barButton(R.drawable.ic_download, "Copy to phone") { copyToPhone(selected.values.toList()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionBar.addView(barButton(R.drawable.ic_info, "Details") { details(selected.values.toList()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(actionBar)
        column.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Palette.paint(bottom)
    }

    private fun horizontalBar(): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = ContextCompat.getDrawable(this@NetworkActivity, R.drawable.progress_rounded)
        progressTintList = accentTint
        indeterminateTintList = accentTint
    }

    /** A button of the bar at the bottom: an icon over a short word (as in Files). */
    private fun barButton(icon: Int, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        val ctx = this@NetworkActivity
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(8), 0, dp(6))
        background = Ui.ripple(ctx, null, 12)
        contentDescription = label
        isClickable = true
        isFocusable = true
        addView(ImageView(ctx).apply {
            setImageDrawable(Ui.icon(ctx, icon, Ui.text(ctx)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(Ui.text(ctx, Ui.Type.CAPTION, label, Ui.text(ctx), lines = 1).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(2), dp(4), dp(2), 0)
        })
        setOnClickListener { onClick() }
    }

    /** A message at the bottom, above the bar there. */
    private fun say(text: CharSequence, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        if (isFinishing) return
        bottom.post {
            if (isFinishing) return@post
            val offset = if (bottom.visibility == View.VISIBLE) bottom.height else 0
            Messages.show(this, text, action, long = long, bottomOffset = offset, onAction = onAction)
        }
    }

    // ------------------------------------------------------------------ places

    private fun placeKey(): String = listOf(server?.id.orEmpty(), share.orEmpty(), path).joinToString("|")

    /** Before another place shows: what belonged to the old one goes, and where the list was is kept. */
    private fun leave(slide: Int) {
        val lm = list.layoutManager as? LinearLayoutManager
        if (lm != null && !loading && adapter.itemCount > 0) positions[placeKey()] = lm.findFirstVisibleItemPosition()
        loadJob?.cancel()
        loadJob = null
        loading = false
        placeGen++
        problem = null
        selected.clear()
        pendingSlide = slide
    }

    private fun showServers() {
        leave(SLIDE_BACK)
        server = null
        share = null
        path = ""
        shares = null
        entries = null
        render()
        restorePosition()
    }

    private fun openServer(s: NetServer) {
        leave(SLIDE_FORWARD)
        server = s
        // An address with a share (smb://nas/Films/2024) opens there; Back still goes up to the shares.
        share = s.share.ifEmpty { null }
        path = if (share != null) NetPath.clean(s.path) else ""
        // Opened before while Swift runs: that list at once, read again meanwhile.
        shares = if (share == null) cache[placeKey()]?.shares else null
        entries = if (share != null) cache[placeKey()]?.entries else null
        load()
    }

    private fun openShares() {
        leave(SLIDE_BACK)
        share = null
        path = ""
        entries = null
        shares = cache[placeKey()]?.shares
        load()
    }

    private fun openShare(name: String) {
        leave(SLIDE_FORWARD)
        share = name
        path = ""
        entries = cache[placeKey()]?.entries
        load()
    }

    private fun openFolder(p: String, slide: Int) {
        leave(slide)
        path = NetPath.clean(p)
        entries = cache[placeKey()]?.entries
        load()
    }

    /** One step up (the folder above, the share's top, its list of shares, the servers); false at the servers. */
    private fun up(): Boolean {
        if (server == null) return false
        val sh = share
        when {
            sh != null && path.isNotEmpty() -> openFolder(NetPath.parent(path), SLIDE_BACK)
            sh != null -> openShares()
            else -> showServers()
        }
        return true
    }

    private class Listing(val shares: List<NetClient.Share>?, val entries: List<NetClient.Entry>?)

    /**
     * Reads the place on show off the main thread: signing in first when there is no session yet
     * (with a spinner and Cancel, after a moment). A place read before shows at once meanwhile,
     * where it was left ([keepScroll]: read again where it is now, as Refresh does).
     */
    private fun load(keepScroll: Boolean = false) {
        val s = server ?: return render()
        loadJob?.cancel()
        val gen = ++placeGen
        val sh = share
        val p = path
        val key = placeKey()
        loading = true
        problem = null
        loadingText = if (NetClient.hasSession(s.id)) "Opening…" else "Connecting to ${s.title}…"
        render()
        // Read before: on show at once, where it was left; otherwise a spinner after a moment.
        val hadRows = shown().isNotEmpty()
        if (!hadRows) showLoadingSoon() else if (!keepScroll) restorePosition()
        val app = applicationContext
        loadJob = lifecycleScope.launch {
            val result: Any = withContext(Dispatchers.IO) {
                try {
                    if (sh == null) Listing(NetClient.shares(app, s), null) else Listing(null, NetClient.list(app, s, sh, p))
                } catch (e: NetFailure) {
                    e.problem
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetErrors.of(e, s.guest)
                }
            }
            if (gen != placeGen) return@launch
            loading = false
            loadJob = null
            if (result is Listing) {
                shares = result.shares ?: shares
                entries = result.entries ?: entries
                cache.remove(key)
                cache[key] = result
                while (cache.size > CACHED_PLACES) cache.remove(cache.keys.first())
                render()
                if (!hadRows) restorePosition()
            } else {
                onProblem(result as NetProblem)
            }
        }
    }

    /** Cancel while waiting: back where it was (the servers when it was still signing in). */
    private fun cancelLoad() {
        val s = server
        loadJob?.cancel()
        loadJob = null
        loading = false
        placeGen++
        if (s == null || !NetClient.hasSession(s.id)) showServers() else if (!up()) render()
    }

    private fun onProblem(p: NetProblem) {
        val s = server ?: return
        problem = p
        render()
        // A password to type (or typed wrong): asked at once.
        if (p == NetProblem.PASSWORD || p == NetProblem.LOGON) askPassword(s, wrong = p == NetProblem.LOGON)
    }

    private fun restorePosition() {
        val at = positions[placeKey()] ?: 0
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(at.coerceAtLeast(0), 0)
    }

    // ------------------------------------------------------------------ showing it

    private val showLoading = Runnable { if (loading && shown().isEmpty()) showSpinner() }

    private fun showLoadingSoon() {
        list.removeCallbacks(showLoading)
        list.postDelayed(showLoading, 250)
    }

    private fun showSpinner() {
        empty.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // 3.0.4: Swift's loading indicator, in the accent.
        col.addView(LoadingIndicator(this))
        col.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, loadingText).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), 0)
        })
        col.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            setOnClickListener { cancelLoad() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        Palette.paint(col)
        empty.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        empty.visibility = View.VISIBLE
    }

    /** The files and folders on show: hidden ones only when Files shows them too. */
    private fun shownEntries(): List<NetClient.Entry> = entries.orEmpty().filter { FilesSettings.showHidden || !it.hidden }

    private fun shown(): List<Item> {
        if (server == null) return NetServers.all(this).filter { it.id !in hiddenServers }.map { Item.Server(it) }
        if (problem != null) return emptyList()
        return if (share == null) shares.orEmpty().map { Item.ShareRow(it) } else shownEntries().map { Item.EntryRow(it) }
    }

    private fun render() {
        list.removeCallbacks(showLoading)
        val items = shown()
        adapter.set(items)
        // The place comes in from its side once it has rows (not its moment of waiting).
        if (pendingSlide != 0 && items.isNotEmpty()) {
            slideList(pendingSlide)
            pendingSlide = 0
        }
        updateChrome()
        renderEmpty(items.isEmpty())
    }

    /** The list comes in a little from the side it was reached from (one layer, a short move: light on the phone). */
    private fun slideList(direction: Int) {
        list.animate().cancel()
        list.alpha = 0f
        list.translationX = Ui.dpf(this, 24f) * direction
        list.animate().alpha(1f).translationX(0f).setDuration(Motion.SHORT).setInterpolator(Motion.enter).withLayer().start()
    }

    private fun renderEmpty(isEmpty: Boolean) {
        val s = server
        val p = problem
        when {
            s == null -> if (isEmpty) showState(
                R.drawable.ic_files_network, "No network folders yet",
                "Add the address of a computer or storage that shares folders.",
                listOf("Add a computer or storage" to { editSheet(null) }),
            ) else showEmpty(false, "")
            p != null -> showProblem(s, p)
            // Nothing from before while waiting (the spinner comes after a moment).
            loading -> if (isEmpty) empty.visibility = View.GONE else showEmpty(false, "")
            share == null -> showEmpty(isEmpty, "No shared folders", "${s.title} doesn't share any folders Swift may see.", R.drawable.ic_folder_shared)
            else -> showEmpty(
                isEmpty, "This folder is empty",
                if (!FilesSettings.showHidden && entries.orEmpty().any { it.hidden }) "Hidden files aren't shown (⋮ → Show hidden files)." else null,
                R.drawable.ic_folder,
            )
        }
    }

    /** An empty screen with buttons (the first one filled). */
    private fun showState(icon: Int, title: String, hint: String?, buttons: List<Pair<String, () -> Unit>>) {
        empty.removeAllViews()
        val state = Ui.emptyState(this, icon, title, hint)
        buttons.forEachIndexed { i, (label, run) ->
            val b = if (i == 0) MaterialButton(this) else MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle)
            b.text = label
            b.setOnClickListener { run() }
            state.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (i == 0) 18 else 4)
            })
        }
        Palette.paint(state)
        empty.addView(android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(state)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (empty.visibility != View.VISIBLE) Motion.fadeIn(empty, fromDy = 12)
    }

    /** What went wrong, in plain words, and what can be done about it. */
    private fun showProblem(s: NetServer, p: NetProblem) {
        val text = NetErrors.text(p, s.host, s.title, share.orEmpty())
        val again = "Try again" to { load() }
        val edit = "Edit ${s.title}" to { editSheet(s) }
        val byName = "Open a share by name" to { askShareName(s) }
        when (p) {
            NetProblem.PASSWORD, NetProblem.LOGON -> showState(R.drawable.ic_password, text, null, listOf("Sign in" to { askPassword(s, p == NetProblem.LOGON) }, edit))
            NetProblem.ACCOUNT, NetProblem.NO_GUESTS -> showState(R.drawable.ic_person, text, null, listOf(edit, again))
            NetProblem.UNREACHABLE -> showState(
                R.drawable.ic_wifi, text, "Check that the computer is on and awake, and that it shares folders. Its firewall may need to let file sharing in.",
                listOf(again, edit),
            )
            NetProblem.UNKNOWN_HOST -> showState(R.drawable.ic_wifi, text, null, listOf(edit, again))
            NetProblem.SMB1 -> showState(
                R.drawable.ic_not_secure, text, "Turn on SMB 2 or 3 in its settings, or update it. Swift doesn't use SMB1: anyone on the network could read what it sends.",
                listOf(again),
            )
            NetProblem.ACCESS, NetProblem.NO_SHARE_LIST -> showState(
                R.drawable.ic_lock, text, if (p == NetProblem.ACCESS) "Its owner can let you in, on the computer." else null,
                if (share == null) listOf(byName, again) else listOf(again),
            )
            NetProblem.NO_SHARE, NetProblem.NOT_FOUND -> showState(R.drawable.ic_folder, text, null, listOf("Go back" to { up() }, again))
            NetProblem.BUSY, NetProblem.OTHER -> showState(R.drawable.ic_error, text, null, listOf(again))
        }
    }

    private fun updateChrome() {
        val s = server
        val selecting = selected.isNotEmpty()
        if (navClose != selecting) {
            navClose = selecting
            toolbar.setNavigationIcon(if (selecting) R.drawable.ic_close else R.drawable.ic_back)
            toolbar.navigationContentDescription = if (selecting) "Leave selection" else "Back"
        }
        if (selecting) {
            toolbar.title = if (selected.size == 1) "1 selected" else "${selected.size} selected"
            toolbar.subtitle = null
        } else {
            val sh = share
            toolbar.title = when {
                s == null -> "Network folders"
                sh == null -> s.title
                path.isEmpty() -> sh
                else -> NetPath.name(path)
            }
            toolbar.subtitle = when {
                s == null -> null
                loading && shown().isEmpty() -> null
                sh == null -> s.address()
                entries != null && problem == null -> FileJobs.items(shownEntries().size)
                else -> null
            }
        }
        updateMenu()
        renderPath()
        actionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        updateBottom()
    }

    /** The place, part by part ("⌂ › Home PC › Films › 2024"): each part goes there. */
    private fun renderPath() {
        val s = server
        if (s == null) {
            pathScroll.visibility = View.GONE
            return
        }
        pathScroll.visibility = View.VISIBLE
        crumbs.removeAllViews()
        crumbs.addView(ImageView(this).apply {
            setImageDrawable(Ui.icon(this@NetworkActivity, R.drawable.ic_files_network, Ui.accent(this@NetworkActivity)))
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.ripple(this@NetworkActivity, null, 12)
            contentDescription = "Network folders"
            TooltipCompat.setTooltipText(this, "Network folders")
            setOnClickListener { showServers() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val parts = ArrayList<Pair<String, () -> Unit>>()
        parts += s.title to { openShares() }
        share?.let { sh ->
            parts += sh to { openFolder("", SLIDE_BACK) }
            var at = ""
            for (p in NetPath.segments(path)) {
                at = NetPath.join(at, p)
                val target = at
                parts += p to { openFolder(target, SLIDE_BACK) }
            }
        }
        parts.forEachIndexed { i, (label, go) ->
            crumbs.addView(ImageView(this).apply {
                setImageDrawable(Ui.icon(this@NetworkActivity, R.drawable.ic_chevron_right, Ui.textSecondary(this@NetworkActivity)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            val last = i == parts.size - 1
            crumbs.addView(Ui.text(this, Ui.Type.LABEL, label, if (last) Ui.text(this) else Ui.accent(this), lines = 1).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = Ui.ripple(this@NetworkActivity, null, 12)
                maxWidth = dp(200)
                ellipsize = TextUtils.TruncateAt.MIDDLE
                if (!last) setOnClickListener { go() }
            })
        }
        pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** Two buttons at most at the top: Add (servers) or Refresh, and ⋮; "Select all" in words while selecting. */
    private fun updateMenu() {
        val s = server
        val selecting = selected.isNotEmpty()
        val all = selecting && selected.size >= shownEntries().size
        val key = when {
            selecting -> "select:$all"
            s == null -> "servers"
            else -> "place"
        }
        if (key == menuShown) return
        menuShown = key
        toolbar.menu.clear()
        val color = Ui.text(this)
        when {
            selecting -> {
                val label = if (all) "Clear selection" else "Select all"
                val v = Ui.text(this, Ui.Type.LABEL, label, Ui.accent(this), lines = 1).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = Ui.ripple(this@NetworkActivity, null, 18)
                    setOnClickListener { onMenu(MENU_SELECT_ALL) }
                }
                toolbar.menu.add(0, MENU_SELECT_ALL, 0, label).apply {
                    actionView = v
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            s == null -> addMenu(MENU_ADD, "Add a computer or storage", R.drawable.ic_add, color)
            else -> {
                addMenu(MENU_REFRESH, "Refresh", R.drawable.ic_refresh, color)
                addMenu(MENU_MORE, "More", R.drawable.ic_more, color)
            }
        }
    }

    private fun addMenu(id: Int, title: String, icon: Int, color: Int) {
        toolbar.menu.add(0, id, id, title).apply {
            setIcon(Ui.icon(this@NetworkActivity, icon, color))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private fun onMenu(id: Int) {
        when (id) {
            MENU_ADD -> editSheet(null)
            MENU_REFRESH -> if (!loading) load(keepScroll = true)
            MENU_MORE -> moreSheet()
            MENU_SELECT_ALL -> if (selected.isNotEmpty() && selected.size >= shownEntries().size) clearSelection() else selectAll()
        }
    }

    /** ⋮ at the top of a server's places. */
    private fun moreSheet() {
        val s = server ?: return
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_refresh, "Refresh", primary = true) { if (!loading) load(keepScroll = true) }
        if (share != null && shownEntries().isNotEmpty()) actions += Sheets.Action(R.drawable.ic_select_all, "Select all", primary = true) { selectAll() }
        actions += Sheets.Action(R.drawable.ic_edit, "Edit ${s.title}", primary = true) { editSheet(s) }
        actions += Sheets.Action(
            if (FilesSettings.showHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
            if (FilesSettings.showHidden) "Hide hidden files" else "Show hidden files",
        ) {
            FilesSettings.showHidden = !FilesSettings.showHidden
            render()
        }
        if (share == null) actions += Sheets.Action(R.drawable.ic_folder_shared, "Open a share by name") { askShareName(s) }
        Sheets.actions(this, s.title, s.address(), actions, iconRes = R.drawable.ic_desktop)
    }

    // ------------------------------------------------------------------ the bar at the bottom and jobs

    private fun updateBottom() {
        val any = jobStrip.visibility == View.VISIBLE || actionBar.visibility == View.VISIBLE
        if (any == (bottom.visibility == View.VISIBLE)) return
        bottom.animate().cancel()
        if (any) {
            bottom.visibility = View.VISIBLE
            bottom.translationY = Ui.dpf(this, 56f)
            bottom.animate().translationY(0f).setDuration(Motion.SHORT).setInterpolator(Motion.enter).start()
        } else {
            bottom.visibility = View.GONE
            bottom.translationY = 0f
        }
    }

    private val jobListener: () -> Unit = { onJobsChanged() }

    private fun onJobsChanged() {
        val all = FileJobs.active
        openingJob?.let { j -> if (!j.finished) opening?.setProgress(j.fraction.takeIf { it >= 0f && !j.counting }) }
        val jobs = all.filter { it.kind != FileJobs.Kind.OPEN }
        val job = jobs.firstOrNull { it.running } ?: jobs.firstOrNull()
        if (job == null) {
            jobStrip.visibility = View.GONE
        } else {
            jobStrip.visibility = View.VISIBLE
            val more = if (jobs.size > 1) "  (+${jobs.size - 1})" else ""
            jobText.text = job.title + more + "  ·  " + FileJobs.detail(job).ifBlank { "Waiting…" }
            setBar(jobBar, job)
        }
        updateBottom()
        progressFor?.let { j ->
            if (j.finished) {
                progressDialog?.dismiss()
                progressDialog = null
                progressFor = null
            } else {
                progressName?.text = j.current.ifBlank { if (j.counting) "Getting ready…" else "" }
                progressDetail?.text = FileJobs.detail(j)
                progressBar?.let { setBar(it, j) }
            }
        }
        // Another screen's job (a copy in Files) that ended while this one is on show: said here.
        val now = all.associateBy { it.id }
        for ((id, j) in seen) {
            if (id !in now && j.finished && id !in mine && j.kind != FileJobs.Kind.OPEN) j.message?.let { say(it, long = j.failed) }
        }
        seen = now
        val q = FileJobs.asking?.question
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
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val name = Ui.text(this, Ui.Type.BODY, job.current).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val bar = horizontalBar()
        val detail = Ui.text(this, Ui.Type.CAPTION, FileJobs.detail(job))
        box.addView(name)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
        box.addView(detail)
        progressName = name
        progressBar = bar
        progressDetail = detail
        progressFor = job
        setBar(bar, job)
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
    }

    /** A job asks: a name already taken on the phone, or (one of Files') an archive's password. */
    private fun showQuestion(q: FileJobs.Question) {
        questionShown = q
        if (q.password) {
            val input = EditText(this).apply {
                hint = "Password"
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val box = LinearLayout(this).apply {
                setPadding(dp(20), dp(4), dp(20), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            Palette.paint(box)
            var answered = false
            val d = MaterialAlertDialogBuilder(this)
                .setTitle(if (q.wrong) "Wrong password" else "“${q.name}” is locked with a password")
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    answered = true
                    q.reply(input.text.toString().ifEmpty { null })
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    answered = true
                    q.reply(null)
                }
                .showImmersive()
            d.setOnDismissListener { if (!answered) q.reply(null) }
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
        }
        box.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, "There's already ${if (q.isFolder) "a folder" else "a file"} called “${q.name}” in “${q.folder}”."))
        val all = CheckBox(this).apply {
            text = "Do the same for the rest"
            setPadding(dp(4), 0, 0, 0)
        }
        box.addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
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

    // ------------------------------------------------------------------ servers

    /**
     * Adds a computer or storage ([old] null) or changes one: its address, a name to show, and who
     * signs in (no user name: as a guest), with the password kept only when asked to.
     */
    // The keyboard pushes the sheet up (Android 7 has no other way for a sheet's window).
    @Suppress("DEPRECATION")
    private fun editSheet(old: NetServer?) {
        val ctx = this
        val col = Sheets.column(ctx)
        col.addView(Sheets.header(ctx, if (old == null) "Add a computer or storage" else "Edit ${old.title}"))
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        fun field(hint: String, value: String, type: Int): EditText = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = type
            form.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun label(text: String) = form.addView(Ui.text(ctx, Ui.Type.SECTION, text).apply { setPadding(dp(4), dp(14), dp(4), 0) })
        label("Where it is")
        val address = field(
            "Its IP address or name, like 192.168.1.5", old?.address().orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val name = field("A name to show (if you like)", old?.name.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        label("Signing in")
        val user = field("User name (empty: as a guest)", old?.user.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val password = field(
            if (old?.remembers == true) "Password (unchanged if empty)" else "Password", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val domain = field("Domain or workgroup (if it has one)", old?.domain.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        form.addView(CheckBox(ctx).apply {
            text = "Show the password"
            setOnCheckedChangeListener { _, on ->
                password.inputType = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                password.setSelection(password.text.length)
            }
        })
        val remember = CheckBox(ctx).apply {
            text = "Remember the password"
            isChecked = old?.remembers ?: true
        }
        form.addView(remember)
        form.addView(Ui.noteBox(ctx, R.drawable.ic_wifi, "Works on the same Wi-Fi as the computer. Swift only reads its folders: it doesn't change or delete anything there."),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        val cancel = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply { text = "Cancel" }
        val save = MaterialButton(ctx).apply { text = if (old == null) "Add" else "Save" }
        buttons.addView(cancel)
        buttons.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        form.addView(buttons)
        col.addView(form)
        val dialog = Sheets.sheet(ctx, col)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val a = NetAddress.parse(address.text.toString())
            if (a == null) {
                address.error = "Type an IP address like 192.168.1.5, or a name like nas"
                address.requestFocus()
                return@setOnClickListener
            }
            // "WORKGROUP\anna" as Windows writes it, or a user named in the address (smb://anna@nas).
            val (inUser, typedUser) = NetAddress.splitUser(user.text.toString())
            val who = typedUser.ifEmpty { a.user }
            val dom = domain.text.toString().trim().ifEmpty { inUser.ifEmpty { a.domain } }
            val pw = password.text.toString().ifEmpty { a.password }.takeIf { it.isNotEmpty() }
            val s = NetServer(
                id = old?.id ?: NetServers.newId(),
                name = name.text.toString().trim(),
                host = a.host,
                port = a.port,
                share = a.share,
                path = a.path,
                user = who,
                domain = if (who.isEmpty()) "" else dom,
                sealed = old?.sealed.orEmpty(),
                added = old?.added ?: System.currentTimeMillis(),
            )
            dialog.dismiss()
            val app = applicationContext
            val keep = remember.isChecked
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { NetServers.save(app, s, pw, keep) }
                if (old == null || server?.id == saved.id) {
                    // New, or changed while on show: opened (signing in again) where its address says.
                    openServer(saved)
                } else {
                    render()
                    say("Saved")
                }
            }
        }
        dialog.showImmersive()
        if (old == null) {
            address.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    /** A server's ⋮ (or a hold on it). */
    private fun serverSheet(s: NetServer) {
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { openServer(s) }
        actions += Sheets.Action(R.drawable.ic_edit, "Edit", primary = true) { editSheet(s) }
        actions += Sheets.Action(R.drawable.ic_delete, "Remove", primary = true, danger = true) { removeServer(s) }
        if (s.remembers) actions += Sheets.Action(R.drawable.ic_key, "Forget the password") { forgetPassword(s) }
        Sheets.actions(this, s.title, serverSubtitle(s), actions, iconRes = R.drawable.ic_desktop)
    }

    private fun serverSubtitle(s: NetServer): String =
        s.address() + " · " + if (s.guest) "as a guest" else (if (s.domain.isNotEmpty()) s.domain + "\\" else "") + s.user

    private fun removeServer(s: NetServer) {
        val app = applicationContext
        removeLater(
            "server:${s.id}", "Removed ${s.title}",
            hide = {
                hiddenServers += s.id
                if (server == null) render()
            },
            restore = {
                hiddenServers -= s.id
                if (server == null) render()
            },
            commit = {
                hiddenServers -= s.id
                NetServers.remove(app, s.id)
            },
        )
    }

    private fun forgetPassword(s: NetServer) {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NetServers.save(app, s.copy(sealed = ""), null, remember = false)
                NetServers.forgetTyped(s.id)
            }
            say("Swift asks for the password of ${s.title} next time")
        }
    }

    /** Asks for [s]'s password ([wrong]: the last one didn't work), then signs in again. */
    private fun askPassword(s: NetServer, wrong: Boolean) {
        if (isFinishing) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val who = (if (s.domain.isNotEmpty()) s.domain + "\\" else "") + s.user
        box.addView(Ui.text(this, Ui.Type.BODY_SECONDARY, if (wrong) "Wrong user name or password. Type the password for $who again." else "Type the password for $who.").apply {
            setPadding(dp(4), 0, dp(4), dp(4))
        })
        val input = EditText(this).apply {
            hint = "Password"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(input)
        box.addView(CheckBox(this).apply {
            text = "Show the password"
            setOnCheckedChangeListener { _, on ->
                input.inputType = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.setSelection(input.text.length)
            }
        })
        val remember = CheckBox(this).apply {
            text = "Remember the password"
            isChecked = s.remembers || !wrong
        }
        box.addView(remember)
        Palette.paint(box)
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Sign in to ${s.title}")
            .setView(box)
            .setPositiveButton("Sign in") { _, _ ->
                val pw = input.text.toString()
                val keep = remember.isChecked
                val app = applicationContext
                lifecycleScope.launch {
                    val now = withContext(Dispatchers.IO) {
                        if (keep) NetServers.save(app, s, pw, true) else s.also { NetServers.useOnce(it.id, pw) }
                    }
                    if (server?.id == now.id) {
                        server = now
                        load()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Edit") { _, _ -> editSheet(s) }
            .showImmersive()
        input.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** A share typed by its name (a server that doesn't give its list). */
    private fun askShareName(s: NetServer) {
        Sheets.askText(this, "Open a share", "The share's name, like Films", "", words = false) { name ->
            val clean = NetPath.clean(name)
            val first = NetPath.segments(clean).firstOrNull() ?: return@askText
            if (server?.id != s.id) return@askText
            leave(SLIDE_FORWARD)
            share = first
            path = NetPath.segments(clean).drop(1).joinToString("/")
            entries = null
            load()
        }
    }

    // ------------------------------------------------------------------ files and folders

    private fun toggle(e: NetClient.Entry, position: Int) {
        val was = selected.isNotEmpty()
        if (selected.remove(e.path) == null) selected[e.path] = e
        if (was != selected.isNotEmpty()) adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)
        if (position >= 0) adapter.notifyItemChanged(position, PAYLOAD_TOGGLED)
        updateChrome()
    }

    private fun selectAll() {
        for (e in shownEntries()) selected[e.path] = e
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)
        updateChrome()
    }

    private fun clearSelection() {
        if (selected.isNotEmpty()) {
            selected.clear()
            adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)
        }
        updateChrome()
    }

    private fun item(e: NetClient.Entry) = NetJobs.Item(e.name, e.path, e.isDir, e.size, e.time)

    /** A folder opens; a video or song plays from the share; anything else is copied into the cache and opened. */
    private fun open(e: NetClient.Entry) {
        val s = server ?: return
        val sh = share ?: return
        if (e.isDir) {
            openFolder(e.path, SLIDE_FORWARD)
            return
        }
        val kind = PlayerActivity.kindOf(e.name)
        if (kind != null) {
            play(s, sh, e, kind)
            return
        }
        if (e.size > NetJobs.OPEN_MAX) {
            say("“${e.name}” is big (${UrlUtils.formatBytes(e.size)}). Copy it to the phone to open it.", "Copy") { copyToPhone(listOf(e)) }
            return
        }
        opening?.dismiss()
        val job = NetJobs.openCopy(this, s, sh, item(e)) { j -> visible?.get()?.opened(j) }
        mine += job.id
        openingJob = job
        opening = Messages.sticky(this, "Opening “${e.name}”…", "Cancel") { job.cancel() }
    }

    private fun opened(job: FileJobs.Job) {
        if (openingJob === job) {
            opening?.dismiss()
            opening = null
            openingJob = null
        }
        val f = job.result
        when {
            job.cancelled -> {}
            job.failed || f == null -> say(job.message ?: "Swift couldn't open this file", long = true)
            else -> FileOpener.open(this, f)
        }
    }

    /**
     * The folder's other videos (or songs) as the playlist, read from the share as they play; the
     * player shows over this screen, which Back returns to.
     */
    private fun play(s: NetServer, sh: String, e: NetClient.Entry, kind: PlayerActivity.Kind) {
        val same = shownEntries().filter { !it.isDir && PlayerActivity.kindOf(it.name) == kind }.ifEmpty { listOf(e) }
        val at = same.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
        // A folder of thousands: the 500 on each side are plenty (as for the phone's folders).
        val from = maxOf(0, at - 500)
        val part = same.subList(from, minOf(same.size, at + 501))
        val list = part.map { x ->
            LocalPlayback.Entry(NetUri.of(s.id, sh, x.path), x.name.substringBeforeLast('.').ifBlank { x.name }, kind == PlayerActivity.Kind.VIDEO)
        }
        LocalPlayback.load(this, list, at - from, C.TIME_UNSET)
        startActivity(PlayerActivity.showIntent(this))
    }

    private fun needsStorageAccess(): Boolean =
        Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    private fun copyToPhone(list: List<NetClient.Entry>) {
        if (list.isEmpty()) return
        if (needsStorageAccess()) {
            pendingCopy = list
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startCopy(list)
    }

    /** Into Download/Swift/<server's name>/, in the background: the bar below and the notification show how far. */
    private fun startCopy(list: List<NetClient.Entry>) {
        val s = server ?: return
        val sh = share ?: return
        clearSelection()
        val app = applicationContext
        val job = NetJobs.copyToPhone(this, s, sh, list.map { item(it) }) { j -> copied(app, j) }
        mine += job.id
    }

    private fun details(list: List<NetClient.Entry>) {
        val s = server ?: return
        val sh = share ?: return
        if (list.isEmpty()) return
        val lines = ArrayList<String>()
        val one = list.singleOrNull()
        if (one != null) {
            lines += if (one.isDir) "Folder" else "Size: ${bytesText(one.size)}"
            if (one.time > 0) lines += "Changed: " + DateUtils.formatDateTime(this, one.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
        } else {
            val files = list.filter { !it.isDir }
            val folders = list.size - files.size
            lines += listOfNotNull(
                files.size.takeIf { it > 0 }?.let { if (it == 1) "1 file" else "$it files" },
                folders.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
            ).joinToString(", ")
            if (files.isNotEmpty()) lines += "Files' size: ${bytesText(files.sumOf { it.size.coerceAtLeast(0) })}"
        }
        lines += "On: ${s.title} (${s.address().substringBefore('/')})"
        lines += "In: " + (listOf(sh) + NetPath.segments(NetPath.parent(list[0].path))).joinToString(" › ")
        Dialogs.info(this, one?.name ?: FileJobs.items(list.size), lines.joinToString("\n"))
    }

    private fun bytesText(n: Long) = if (n < 1024) UrlUtils.formatBytes(n) else "${UrlUtils.formatBytes(n)} (${String.format(java.util.Locale.getDefault(), "%,d", n)} bytes)"

    /** A file's or folder's ⋮. */
    private fun entrySheet(e: NetClient.Entry) {
        val actions = ArrayList<Sheets.Action>()
        val media = PlayerActivity.kindOf(e.name) != null
        actions += Sheets.Action(if (media) R.drawable.ic_play else R.drawable.ic_open_new, if (media) "Play" else "Open", primary = true) { open(e) }
        actions += Sheets.Action(R.drawable.ic_download, "Copy to phone", primary = true) { copyToPhone(listOf(e)) }
        actions += Sheets.Action(R.drawable.ic_select_all, "Select", primary = true) {
            val at = adapter.items.indexOfFirst { it is Item.EntryRow && it.e.path == e.path }
            toggle(e, at)
        }
        actions += Sheets.Action(R.drawable.ic_info, "Details", primary = true) { details(listOf(e)) }
        Sheets.actions(this, e.name, subtitleOf(e), actions, iconRes = if (e.isDir) R.drawable.ic_folder else FileKind.of(e.name).icon)
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

    private fun subtitleOf(e: NetClient.Entry): String =
        listOfNotNull(if (e.isDir) "Folder" else UrlUtils.formatBytes(e.size.coerceAtLeast(0)), dateText(e.time)).joinToString(" · ")

    // ------------------------------------------------------------------ the list

    /** A server, share, file or folder as a row, like Files' rows: its icon, name, a line under it, and ⋮ or a tick. */
    private class NetRow(
        root: View, val icon: ImageView, val title: TextView, val subtitle: TextView, val more: ImageButton, val check: ImageView,
    ) : RecyclerView.ViewHolder(root) {
        var marked = false
        lateinit var plain: Drawable
        lateinit var markedFill: Drawable
    }

    private inner class Adapter : RecyclerView.Adapter<NetRow>() {
        var items: List<Item> = emptyList()
            private set

        fun set(list: List<Item>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetRow = makeRow()

        override fun onBindViewHolder(holder: NetRow, position: Int) = bind(holder, items[position], false)

        override fun onBindViewHolder(holder: NetRow, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) bind(holder, items[position], false)
            else bindSelection(holder, items[position], payloads.contains(PAYLOAD_TOGGLED))
        }
    }

    private fun itemAt(h: RecyclerView.ViewHolder): Item? = adapter.items.getOrNull(h.bindingAdapterPosition)

    private fun makeRow(): NetRow {
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
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(icon, LinearLayout.LayoutParams(thumbPx, thumbPx))
        // The middle of a long name goes, so its ending (.mkv, .pdf) stays in sight.
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
            TooltipCompat.setTooltipText(this, "More")
            setBackgroundResource(RowHolder.selectableBackground(ctx))
        }
        root.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        val check = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(check, LinearLayout.LayoutParams(dp(44), dp(44)))
        val h = NetRow(root, icon, title, subtitle, more, check)
        h.plain = root.background
        h.markedFill = Ui.ripple(ctx, ColorDrawable(Ui.accentContainer(ctx)), 0)
        root.setOnClickListener {
            when (val item = itemAt(h)) {
                is Item.Server -> openServer(item.s)
                is Item.ShareRow -> openShare(item.share.name)
                is Item.EntryRow -> if (selected.isNotEmpty()) toggle(item.e, h.bindingAdapterPosition) else open(item.e)
                null -> {}
            }
        }
        root.setOnLongClickListener { v ->
            when (val item = itemAt(h)) {
                is Item.Server -> serverSheet(item.s)
                is Item.ShareRow -> openShare(item.share.name)
                is Item.EntryRow -> {
                    Haptics.hold(v)
                    toggle(item.e, h.bindingAdapterPosition)
                }
                null -> return@setOnLongClickListener false
            }
            true
        }
        more.setOnClickListener {
            when (val item = itemAt(h)) {
                is Item.Server -> serverSheet(item.s)
                is Item.ShareRow -> openShare(item.share.name)
                is Item.EntryRow -> entrySheet(item.e)
                null -> {}
            }
        }
        return h
    }

    private fun bind(h: NetRow, item: Item, toggled: Boolean) {
        val v = h.icon
        when (item) {
            is Item.Server -> {
                h.title.text = item.s.title
                h.subtitle.text = serverSubtitle(item.s)
                v.background = folderFill
                v.setImageResource(R.drawable.ic_desktop)
                v.imageTintList = onContainerTint
            }
            is Item.ShareRow -> {
                h.title.text = item.share.name
                h.subtitle.text = item.share.remark.ifEmpty { "Shared folder" }
                v.background = folderFill
                v.setImageResource(R.drawable.ic_folder_shared)
                v.imageTintList = onContainerTint
            }
            is Item.EntryRow -> {
                val e = item.e
                h.title.text = e.name
                h.subtitle.text = subtitleOf(e)
                val doc = if (e.isDir) null else Docs.kindOf(e.name, null)
                if (doc != null) {
                    v.background = fileFill
                    v.imageTintList = null
                    v.setImageDrawable(DocIcon(doc, dp(30)))
                } else {
                    v.background = if (e.isDir) folderFill else fileFill
                    v.setImageResource(if (e.isDir) R.drawable.ic_folder else FileKind.of(e.name).icon)
                    v.imageTintList = if (e.isDir) onContainerTint else secondaryTint
                }
            }
        }
        bindSelection(h, item, toggled)
    }

    /** The tick while selecting (a small pop when tapped), and the selected row's colour. */
    private fun bindSelection(h: NetRow, item: Item, toggled: Boolean) {
        val e = (item as? Item.EntryRow)?.e
        val selecting = selected.isNotEmpty() && e != null
        val on = e != null && e.path in selected
        h.check.visibility = if (selecting) View.VISIBLE else View.GONE
        h.more.visibility = if (selecting) View.GONE else View.VISIBLE
        if (selecting) {
            h.check.setImageResource(if (on) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)
            h.check.imageTintList = if (on) accentTint else secondaryTint
            if (toggled && on) Motion.bump(h.check, 1.2f)
        }
        if (on != h.marked) {
            h.marked = on
            h.itemView.background = if (on) h.markedFill else h.plain
        }
        val what = when (item) {
            is Item.Server -> "computer"
            is Item.ShareRow -> "shared folder"
            is Item.EntryRow -> if (item.e.isDir) "folder" else FileKind.of(item.e.name).label
        }
        h.itemView.contentDescription = h.title.text.toString() + ", " + what + if (on) ", selected" else ""
    }

    // ------------------------------------------------------------------ jobs ending

    private fun copyDone(job: FileJobs.Job) {
        val text = job.message ?: return
        val folder = job.result
        if (!job.failed && !job.cancelled && folder != null) say(text, "Show") { FilesActivity.open(this, folder) } else say(text, long = job.failed)
    }

    companion object {
        private const val STATE_SERVER = "net_server"
        private const val STATE_SHARE = "net_share"
        private const val STATE_PATH = "net_path"

        private const val MENU_ADD = 1
        private const val MENU_REFRESH = 2
        private const val MENU_MORE = 3
        private const val MENU_SELECT_ALL = 4

        private const val SLIDE_FORWARD = 1
        private const val SLIDE_BACK = -1

        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_TOGGLED = "toggled"

        /** Places read lately, shown at once when gone back to (and read again). */
        private const val CACHED_PLACES = 24
        private val cache = LinkedHashMap<String, Listing>()

        /** Where each place's list was scrolled to, while Swift runs. */
        private val positions = HashMap<String, Int>()

        /** The screen on show, which jobs report to when they end. */
        private var visible: WeakReference<NetworkActivity>? = null

        /** A copy to the phone ended: said on this screen, or (gone) by the notification, as Files' own. */
        private fun copied(app: Context, job: FileJobs.Job) {
            val screen = visible?.get()
            if (screen != null) {
                screen.copyDone(job)
            } else if (FileJobs.watched) {
                // FileJobs leaves the notification to a screen on show (Files), which doesn't know this job.
                FileJobService.notifyDone(app, job)
            }
        }

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, NetworkActivity::class.java))
        }
    }
}
