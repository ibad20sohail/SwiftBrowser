package com.swiftbrowser.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Crash
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.SiteSettings
import com.swiftbrowser.core.TopSites
import com.swiftbrowser.core.Updates
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.BrowserUi
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabGroup
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.tabs.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), BrowserUi {

    override val activity: AppCompatActivity get() = this

    private lateinit var prefs: Prefs
    lateinit var gecko: SwiftGeckoView
        private set

    /** The bar over selected text (2.6). */
    private val selectionMenu by lazy { SelectionMenu(this) }
    private lateinit var root: FrameLayout
    private lateinit var appBar: View
    private lateinit var toolbar: LinearLayout
    private lateinit var profileChip: TextView
    private lateinit var incognitoIcon: ImageView
    private lateinit var urlBox: View
    private lateinit var urlEdit: EditText
    private lateinit var urlIcon: ImageView
    private lateinit var urlMic: ImageButton
    private lateinit var urlScan: ImageButton
    private lateinit var urlClear: ImageButton
    private lateinit var videoBtn: View
    private lateinit var tabsCount: TextView
    private lateinit var menuBtn: ImageButton
    private lateinit var homeBtn: ImageButton

    /** Kept here: arranging the address bar (2.2) takes it out of the toolbar for a moment. */
    private lateinit var tabsBtn: View
    private lateinit var progress: ProgressBar
    private lateinit var gestureLayer: GestureFrameLayout
    private lateinit var home: HomeView
    private lateinit var crashView: View
    private lateinit var pageCover: View
    private lateinit var suggestionsList: RecyclerView
    private lateinit var ptrIndicator: ImageView
    private lateinit var hud: TextView
    private lateinit var findBar: View
    private lateinit var findEdit: EditText
    private lateinit var findCount: TextView
    /** The tab grid: made the first time it opens (2.3), so starting Swift doesn't build it. */
    private var switcherView: TabSwitcherView? = null
    private val switcher: TabSwitcherView
        get() = switcherView ?: (findViewById<android.view.ViewStub>(R.id.tab_switcher_stub).inflate() as TabSwitcherView).also {
            switcherView = it
            Palette.paint(it)
            setupSwitcher(it)
        }

    /** The tab grid is open (asking doesn't make it). */
    private val switcherOpen: Boolean get() = switcherView?.isOpen == true
    private lateinit var startup: View
    private lateinit var tabSwipe: TabSwipe

    /** The ⋮ menu; built once, when the app is idle after starting. */
    private val quickMenu by lazy { QuickMenu(this, root) }

    private val handler = Handler(Looper.getMainLooper())
    private val suggestionAdapter = SuggestionAdapter({ bottomBar }, ::onSuggestion, ::fillSuggestion)
    private var suggestJob: Job? = null
    private var editing = false
    private var fullscreenTab: Tab? = null
    private var volumeAccumulator = 0f
    private var lastBlocked: Pair<String, Long>? = null

    /** Tabs the screen has shown; a tab not in here is new and gets the "new tab" animation. */
    private val seenTabs = HashSet<Long>()

    // What the toolbar currently shows, so repeated updates (progress, ad counter) touch no views.
    private var shownUrlText: String? = null
    private var shownUrlIcon = 0
    private var shownLoading = false
    private var styledIncognito: Boolean? = null
    private var homeShownFor: Tab? = null

    /** Reloaded once more after its reload for a theme change has finished, see onCreate. */
    private var themeReloadTab: Tab? = null
    private var themeReloadStarted = false

    // Toolbar that slides away while scrolling down a page (top or bottom of the screen).
    private var toolbarPx = 0
    private var bottomBar = false
    private var settleAnim: ValueAnimator? = null

    /** Which way the finger last moved the toolbar: -1 away, +1 back. */
    private var fingerDirection = 0
    private var toolbarShown = 0f
    private var toolbarWanted = true
    private var lastScrollY = -1f
    private var lastUserScrollAt = 0L

    // Toolbar contents are redrawn in batches, and not while the toolbar is hidden.
    private var toolbarStale = false
    private var toolbarRefreshPosted = false
    private val toolbarRefresh = Runnable {
        toolbarRefreshPosted = false
        if (toolbarWanted && fullscreenTab == null) refreshToolbarNow() else toolbarStale = true
    }
    private var scrollRun = 0f

    // When the toolbar appears or goes, the engine moves the page by its height so that the content
    // stays where it is. That move arrives with a later scroll update, possibly after some of the
    // user's own scrolling, and must not be taken for it (or the toolbar flips back and forth).
    private var expectedShift = 0f
    private var shiftExpectedUntil = 0L
    private var toolbarToggledAt = 0L

    // Pull to refresh.
    private var ptrRefreshing = false
    private var ptrStartedAt = 0L
    private var ptrSpin: ObjectAnimator? = null

    private var pendingPermission: ((Boolean) -> Unit)? = null
    private var pendingFiles: ((List<Uri>?) -> Unit)? = null
    private var pendingActivityResult: GeckoResult<Intent>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cb = pendingPermission
            pendingPermission = null
            cb?.invoke(result.isNotEmpty() && result.values.all { it })
        }

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = pendingFiles
            pendingFiles = null
            if (r.resultCode != Activity.RESULT_OK || r.data == null) {
                cb?.invoke(null)
                return@registerForActivityResult
            }
            val data = r.data!!
            val uris = ArrayList<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            cb?.invoke(uris)
        }

    /** Results from the history, bookmarks and settings screens: a URL to open. */
    private val openUrlLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val url = r.data?.getStringExtra(EXTRA_URL) ?: return@registerForActivityResult
            if (r.data?.getBooleanExtra(EXTRA_NEW_TAB, false) == true) TabManager.newTab(url, incognito = false)
            else openInCurrent(url)
        }

    private val intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val pending = pendingActivityResult ?: return@registerForActivityResult
            pendingActivityResult = null
            if (r.resultCode == Activity.RESULT_OK) pending.complete(r.data ?: Intent())
            else pending.completeExceptionally(IllegalStateException("Cancelled"))
        }

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) navigate(text)
        }

    private val current: Tab? get() = TabManager.current

    // ------------------------------------------------------------------ lifecycle

    /** The browser on this screen is built (2.7: just after the start screen is shown, see [build]). */
    private var built = false

    /** Swift's start screen while the browser is built behind it (2.7). */
    private var startScreen: StartScreen? = null

    /** What waits for the start screen to have gone (2.7), so its glide stays smooth. */
    private val afterStart = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Swift)
        Looks.apply(this)
        appliedColors = Palette.signature
        super.onCreate(savedInstanceState)
        prefs = SwiftApp.prefs
        // Rebuilt (a dark mode switch, a change of colours): at once, as before.
        if (savedInstanceState != null) {
            build(savedInstanceState)
            return
        }
        // Android shows no screen of its own while Swift starts (2.7, Theme.Swift.Launch): Swift's
        // start screen is drawn first, in your colours, and the browser is built right after it is
        // on screen.
        val start = StartScreen(this).also { startScreen = it }
        setContentView(start.view)
        // The status bar (until full screen hides it) in the same colour.
        window.statusBarColor = Palette.color(this, R.color.page_bg)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        applySystemBars()
        val buildNow = Runnable { if (!isFinishing && !isDestroyed) build(null) }
        afterNextFrame { buildNow.run() }
        // Not drawn while the screen is off, say: built anyway.
        handler.postDelayed(buildNow, 1500)
    }

    /**
     * [action] once the next frame of this screen is on the display (2.7): the start screen before
     * the browser is built, the browser's first frame before the glide (so it doesn't jump).
     */
    private fun afterNextFrame(action: () -> Unit) {
        window.addOnFrameMetricsAvailableListener(object : android.view.Window.OnFrameMetricsAvailableListener {
            private var done = false

            override fun onFrameMetricsAvailable(w: android.view.Window, m: android.view.FrameMetrics, dropped: Int) {
                if (done) return
                done = true
                handler.post {
                    window.removeOnFrameMetricsAvailableListener(this)
                    action()
                }
            }
        }, handler)
    }

    private fun build(savedInstanceState: Bundle?) {
        if (built) return
        setContentView(R.layout.activity_main)
        Palette.paint(findViewById<View>(android.R.id.content))
        toolbarPx = resources.getDimensionPixelSize(R.dimen.toolbar_height)
        bindViews()
        applyToolbarPosition()
        setupToolbar()
        setupFindBar()
        setupGestures()
        setupPullToRefresh()
        setupHome()
        setupTabSwipe()

        gecko.setActivityContextDelegate { this }
        // The toolbar floats over the page and slides away; the page is told how much of it is hidden.
        gecko.setDynamicToolbarMaxHeight(toolbarPx)
        applyToolbarShown(toolbarPx.toFloat())

        TabManager.ui = this
        Engine.activityDelegate = GeckoRuntime.ActivityDelegate { pending ->
            val result = GeckoResult<Intent>()
            pendingActivityResult = result
            try {
                intentSenderLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } catch (e: Exception) {
                pendingActivityResult = null
                result.completeExceptionally(e)
            }
            result
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        TabManager.tabs.forEach { seenTabs.add(it.id) }
        val had = TabManager.current
        val tab = TabManager.ensureCurrent()
        seenTabs.add(tab.id)
        // When it had to choose the tab, ensureCurrent told this screen already (2.6: not twice).
        if (had === tab) onTabSelected(tab)
        onTabsChanged()
        if (savedInstanceState == null) handleIntent(intent)
        SwiftApp.messageAfterThemeChange?.let { m ->
            SwiftApp.messageAfterThemeChange = null
            root.post { if (!isFinishing) snack(m, long = true) }
        }
        if (SwiftApp.reloadAfterThemeChange) {
            SwiftApp.reloadAfterThemeChange = false
            // Sites that pick their colours when the page loads follow on reload. Google notices the
            // change during that load and only uses it from the next one, so it gets a second reload.
            current?.let {
                if (!it.isHome && UrlUtils.isHttp(it.url)) {
                    TabManager.reload(it)
                    if (UrlUtils.site(it.url)?.startsWith("google.") == true) themeReloadTab = it
                }
            }
        }
        // Full screen first: the dialogs below keep it (see Immersive).
        applySystemBars()
        SystemBars.keepHidden(this) { fullscreenTab == null }
        showCrashReportIfAny()
        showProblemIfAny()
        showStartupUntilReady()
        // The menu was open when the screen restarted for a dark mode switch: it stays open.
        if (savedInstanceState?.getBoolean(STATE_MENU_OPEN) == true) {
            root.post { if (!isFinishing) quickMenu.open(animate = false) }
        }
        whenStarted { Looper.myQueue().addIdleHandler {
            quickMenu
            // The tab grid next, a few seconds on (2.6), so the first tap on the tabs button doesn't build it.
            handler.postDelayed({
                Looper.myQueue().addIdleHandler {
                    if (!isFinishing && switcherView == null) switcher
                    false
                }
            }, 3000)
            false
        } }
        com.swiftbrowser.engine.DataSaver.listener = { active ->
            if (active && prefs.dataSaver) snack("On mobile data: pages load without pictures (Data saver)")
        }
        com.swiftbrowser.engine.Power.listener = {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) announceBattery()
        }
        setupEdgeSwipe()
        // A newer Swift on GitHub? Asked at most once a day, a while after starting.
        handler.postDelayed({
            if (!isFinishing) Updates.checkNow(prefs, force = false) { r -> offerUpdate(r) }
        }, 15_000)
        if (savedInstanceState == null) whenStarted { if (!isFinishing) WhatsNew.showOnStart(this) }
        // Housekeeping once the app has settled: the weekly backup, and the cache when the phone is
        // almost full.
        handler.postDelayed({ if (!isFinishing) startupChores() }, 20_000)
        Downloads.addListener(updateDownloadListener)
        Downloads.all().filter { VpnUi.isSetupFile(it) }.mapTo(setupFilesSeen) { it.id }
        Downloads.addListener(setupFileListener)
        built = true
        startScreen?.let { start ->
            // Back on top of the browser just built, until that has been drawn once; then the logo
            // glides into the home page's (or the start screen fades, when a site is open).
            findViewById<android.view.ViewGroup>(android.R.id.content).addView(start.view)
            afterNextFrame {
                if (isDestroyed) return@afterNextFrame
                start.finish(home.logoMark.takeIf { home.visibility == View.VISIBLE }) {
                    startScreen = null
                    afterStart.forEach { it() }
                    afterStart.clear()
                }
            }
        }
        // Started (and resumed) while the start screen showed: what those do, now.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) started()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) resumed()
    }

    /** [action] once the start screen has gone (at once without one). */
    private fun whenStarted(action: () -> Unit) {
        if (startScreen == null) action() else afterStart += action
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // While the start screen shows, the browser handles it once built.
        if (built) handleIntent(intent) else setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MENU_OPEN, built && quickMenu.isOpen)
    }

    /** Pages of other tabs are let go a while after Swift leaves the screen (see [onStop]). */
    private val freeBackgroundTabs = Runnable {
        L.d("In the background for a while: keeping only the open (or playing) tab loaded")
        TabManager.suspendBackgroundTabs()
    }

    private fun startupChores() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { com.swiftbrowser.core.Storage.freeSpaceIfLow(this@MainActivity) }
            if (freed > 0) snack("Your phone is almost full: Swift freed ${UrlUtils.formatBytes(freed)} of saved files", long = true)
            withContext(Dispatchers.IO) { com.swiftbrowser.core.Backup.autoBackupIfDue(this@MainActivity) }
            // Update APKs stay in the download folder after installing (about 150 MB each).
            val oldApks = withContext(Dispatchers.IO) { com.swiftbrowser.core.Updates.deleteOldApks(this@MainActivity) }
            if (oldApks > 0) snack("Deleted the old update file (${UrlUtils.formatBytes(oldApks)})")
            // 2.2: Proton's free VPN files stop working after a year unless extended.
            // A dialog (2.6): the reminder is too long for a message at the bottom.
            com.swiftbrowser.vpn.Vpn.expiringToRemind()?.let { loc ->
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Your VPN location needs renewing")
                    .setMessage(VpnUi.expiryText(loc))
                    .setPositiveButton("Open Proton") { _, _ -> TabManager.newTab(com.swiftbrowser.vpn.Vpn.PROTON_DOWNLOADS, incognito = false) }
                    .setNegativeButton("Later", null)
                    .showImmersive()
            }
        }
    }

    // ------------------------------------------------------------------ address bar buttons (2.2)

    /** The buttons next to the address as arranged in Settings (ToolbarButtons). */
    private var toolbarLayout: ToolbarButtons.Layout? = null

    /** Buttons made for the address bar when chosen (new tab, reload, share, bookmark, VPN, scan). */
    private val extraButtons = HashMap<String, ImageButton>()

    /** The page whose bookmark star the toolbar shows (asked of the database once per page). */
    private var bookmarkShownFor: String? = null

    private fun toolbarView(key: String): View = when (key) {
        "profile" -> profileChip.parent as View
        "home" -> homeBtn
        "tabs" -> tabsBtn
        "menu" -> menuBtn
        else -> extraButtons.getOrPut(key) { toolbarButton(key) }
    }

    private fun toolbarButton(key: String): ImageButton {
        val b = ToolbarButtons.button(key)
        return ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
            setImageResource(b.icon)
            contentDescription = b.label
            imageTintList = ColorStateList.valueOf(iconColor())
            layoutParams = LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt())
            Ui.pressable(this, 0.88f)
            setOnClickListener { toolbarAction(key) }
        }
    }

    private fun toolbarAction(key: String) {
        val tab = current ?: return
        when (key) {
            "new_tab" -> TabManager.newTab(incognito = tab.incognito)
            "reload" -> if (tab.loading) tab.session?.stop() else TabManager.reload(tab)
            "share" -> if (UrlUtils.isHttp(tab.url)) share(tab.url)
            "bookmark" -> if (UrlUtils.isHttp(tab.url)) toggleBookmark(tab)
            "vpn" -> showVpnSheet()
            "scan" -> scanQr()
        }
    }

    /** Puts the chosen buttons on their sides of the address: at start, and after Settings changed them. */
    private fun applyToolbarButtons() {
        val l = ToolbarButtons.layout()
        if (toolbarLayout?.encode() == l.encode()) return
        toolbarLayout = l
        for (v in listOf(profileChip.parent as View, homeBtn, tabsBtn, menuBtn) + extraButtons.values) toolbar.removeView(v)
        var at = 0
        for (key in l.left) toolbar.addView(toolbarView(key), at++)
        for (key in l.right) toolbar.addView(toolbarView(key))
        for (v in toolbarButtons()) v.visibility = if (editing) View.GONE else View.VISIBLE
        bookmarkShownFor = null
        updateToolbarExtras()
    }

    private fun toolbarButtons(): List<View> = toolbarLayout?.let { l -> (l.left + l.right).map { toolbarView(it) } }.orEmpty()

    /** Reload turns into stop while loading, the star fills on a bookmarked page, VPN takes the accent while on. */
    private fun updateToolbarExtras() {
        if (extraButtons.isEmpty()) return
        val tab = current ?: return
        val normal = iconColor()
        for ((key, b) in extraButtons) {
            var tint = normal
            when (key) {
                "reload" -> {
                    Motion.swapIcon(b, if (tab.loading) R.drawable.ic_close else R.drawable.ic_refresh)
                    b.contentDescription = if (tab.loading) "Stop" else "Reload"
                }
                "share" -> {
                    val ok = UrlUtils.isHttp(tab.url)
                    b.isEnabled = ok
                    b.alpha = if (ok) 1f else 0.4f
                }
                "bookmark" -> {
                    val page = "${tab.profileId} ${tab.url}"
                    if (bookmarkShownFor != page) {
                        bookmarkShownFor = page
                        b.tag = UrlUtils.isHttp(tab.url) && SwiftApp.db.isBookmarked(tab.profileId, tab.url)
                    }
                    val on = b.tag == true
                    b.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
                    if (on) tint = color(Looks.accent.primary)
                    b.alpha = if (UrlUtils.isHttp(tab.url)) 1f else 0.4f
                }
                "vpn" -> if (com.swiftbrowser.vpn.Vpn.state == com.swiftbrowser.vpn.Vpn.State.ON) tint = color(Looks.accent.primary)
            }
            // Only when it changed (2.6): this runs on every page update.
            if (b.imageTintList?.defaultColor != tint) b.imageTintList = ColorStateList.valueOf(tint)
        }
    }

    /** Adds or removes the page's bookmark (the menu's star and the toolbar's). */
    fun toggleBookmark(tab: Tab) {
        val pid = tab.profileId
        val url = tab.url
        if (SwiftApp.db.isBookmarked(pid, url)) {
            TabManager.io.execute {
                val row = SwiftApp.db.bookmark(pid, url)
                SwiftApp.db.removeBookmark(pid, url)
                // Undo (2.6) puts it back as it was: its name, folder and date.
                if (row != null) runOnUiThread {
                    snack("Bookmark removed", "Undo") {
                        TabManager.io.execute { SwiftApp.db.importBookmark(pid, row.url, row.title, row.created, row.folder) }
                        if (current?.url == url) extraButtons["bookmark"]?.tag = true
                        bookmarkShownFor = null
                        updateToolbarExtras()
                    }
                }
            }
            extraButtons["bookmark"]?.tag = false
        } else {
            val title = tab.title
            TabManager.io.execute { SwiftApp.db.addBookmark(pid, url, title) }
            snack("Bookmarked")
            extraButtons["bookmark"]?.tag = true
        }
        bookmarkShownFor = "$pid $url"
        updateToolbarExtras()
    }

    // ------------------------------------------------------------------ QR codes (2.2)

    private val qrScan = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        r.contents?.let { onScanned(it) }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScan()
        } else {
            snack("Swift needs the camera to scan QR codes", "Settings", long = true) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    /** The camera, for a QR code: a web address opens, other text can be searched, copied or shared. */
    fun scanQr() {
        stopEditing()
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchScan()
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchScan() {
        try {
            qrScan.launch(
                com.journeyapps.barcodescanner.ScanOptions()
                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setPrompt("Point the camera at a QR code")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .setCaptureActivity(QrScanActivity::class.java),
            )
        } catch (_: Exception) {
            snack("The camera can't be opened")
        }
    }

    private fun onScanned(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val wifi = QrCodes.wifi(text)
        when {
            wifi != null -> {
                val actions = ArrayList<Sheets.Action>()
                if (wifi.password.isNotEmpty()) actions += Sheets.Action(R.drawable.ic_copy, "Copy password", primary = true) { copy(wifi.password) }
                actions += Sheets.Action(R.drawable.ic_wifi, "Wi-Fi settings", primary = true) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
                Sheets.actions(
                    this, "Wi-Fi: ${wifi.name}",
                    if (wifi.password.isEmpty()) "No password" else "Password: ${wifi.password}",
                    actions, iconRes = R.drawable.ic_wifi,
                )
            }
            UrlUtils.isHttp(text) -> {
                val tab = current
                if (tab != null && (tab.showsHome || tab.isBlank)) openInCurrent(text) else TabManager.newTab(text, incognito = tab?.incognito == true)
            }
            else -> Sheets.actions(
                this, "Text from the QR code", text.take(300),
                listOf(
                    Sheets.Action(R.drawable.ic_search, "Search", primary = true) { navigate(text) },
                    Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copy(text) },
                    Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(text) },
                ),
                iconRes = R.drawable.ic_qr,
            )
        }
    }

    // ------------------------------------------------------------------ VPN (2.1)

    /** Android's one-time "allow VPN" question. */
    private val vpnPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        VpnUi.onPermission(this, it.resultCode == RESULT_OK)
    }
    private var shownVpnState: com.swiftbrowser.vpn.Vpn.State? = null
    private val vpnListener: () -> Unit = { onVpnChanged() }

    /** Setup files (.conf) already finished: each new one is offered as a VPN location once. */
    private val setupFilesSeen = HashSet<Long>()
    private val setupFileListener: () -> Unit = {
        for (d in Downloads.all()) {
            if (!VpnUi.isSetupFile(d) || !setupFilesSeen.add(d.id)) continue
            snack("Downloaded ${d.fileName}", "Add to VPN", long = true) { VpnUi.addDownloaded(this, d) }
        }
    }

    fun showVpnSheet() {
        VpnUi.sheet(this, vpnPermission) {
            openUrlLauncher.launch(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TOPIC, "VPN"))
        }
    }

    private fun onVpnChanged() {
        val vpn = com.swiftbrowser.vpn.Vpn
        val state = vpn.state
        val before = shownVpnState
        shownVpnState = state
        updateVpnBadge()
        updateToolbarExtras()
        if (before == null || before == state) return
        when (state) {
            com.swiftbrowser.vpn.Vpn.State.ON -> {
                val where = vpn.current()?.let { "${it.flag} ${it.countryName}" } ?: ""
                TabManager.onOnline()
                val note = vpn.switchNote
                vpn.switchNote = null
                if (note != null) {
                    snack(note, "Reload", long = true) { current?.let { TabManager.reload(it) } }
                } else {
                    snack("VPN on · $where", "Reload") { current?.let { TabManager.reload(it) } }
                }
            }
            com.swiftbrowser.vpn.Vpn.State.FAILED -> if (vpn.wanted) {
                // 2.2: another location (the same country first) is one tap away.
                // With automatic switching on, every other location was tried already.
                val spare = if (vpn.autoSwitch) null else vpn.spareFor(vpn.current())
                if (spare != null) {
                    val first = vpn.error?.substringBefore(". ")?.trimEnd('.')?.plus(".") ?: "The VPN isn't connected."
                    snack(first, "Try ${spare.server}", long = true) { VpnUi.turnOn(this, vpnPermission, spare) }
                } else {
                    snack(vpn.error ?: "The VPN isn't connected", "VPN") { showVpnSheet() }
                }
            }
            else -> Unit
        }
    }

    /** "VPN" at the end of the address while it is on (or should be); tapping it opens the VPN sheet. */
    private val vpnBadge by lazy { findViewById<TextView>(R.id.vpn_badge) }
    private var vpnBadgeShown: Any? = null

    private fun updateVpnBadge() {
        val badge = vpnBadge
        val vpn = com.swiftbrowser.vpn.Vpn
        val (show, fill, ink, text) = when {
            editing -> Quad(false, 0, 0, "")
            vpn.state == com.swiftbrowser.vpn.Vpn.State.ON -> Quad(true, Looks.accent.container, Looks.accent.onContainer, "VPN")
            vpn.wanted && vpn.state == com.swiftbrowser.vpn.Vpn.State.CONNECTING -> Quad(true, R.color.surface_alt, R.color.text_secondary, "VPN…")
            vpn.wanted -> Quad(true, R.color.danger_container, R.color.on_danger_container, "VPN off")
            else -> Quad(false, 0, 0, "")
        }
        Motion.pop(badge, show)
        if (!show) return
        // Asked on every key typed: only redone when it changed (2.6).
        val key = listOf(fill, ink, text, Looks.accent, VpnUi.label())
        if (key == vpnBadgeShown) return
        vpnBadgeShown = key
        badge.text = text
        badge.setTextColor(color(ink))
        badge.background = Ui.shape(this, color(fill), 12)
        badge.contentDescription = VpnUi.label()
        badge.setOnClickListener { showVpnSheet() }
        // Holding it: switch country straight away (2.3).
        badge.setOnLongClickListener {
            Haptics.tick(it)
            VpnUi.quickSwitch(this, vpnPermission) { showVpnSheet() }
            true
        }
    }

    private data class Quad(val show: Boolean, val fill: Int, val ink: Int, val text: String)

    /** The accent this screen was drawn with: a new one (chosen in Settings) redraws it. */
    private var appliedColors: String? = null

    override fun onStart() {
        super.onStart()
        if (built) started()
    }

    private fun started() {
        if (appliedColors != null && appliedColors != Palette.signature) {
            recreate()
            return
        }
        Looks.sync()
        Looks.watchClock(true)
        handler.removeCallbacks(freeBackgroundTabs)
        // Settings → Look & screen may have moved the address bar.
        if (bottomBar != prefs.toolbarAtBottom) applyToolbarPosition()
        // Settings → Browsing may have changed the search engine, Look & screen the address bar's buttons.
        urlEdit.hint = com.swiftbrowser.core.SearchEngine.current.hint
        applyToolbarButtons()
        bookmarkShownFor = null
        updateToolbarExtras()
        if (home.visibility == View.VISIBLE) current?.let { home.show(it.incognito) }
        // Settings may have changed the text size for all sites; the site on screen may have its own.
        current?.let { Engine.showTextScaleFor(UrlUtils.site(it.url)) }
        Diagnostics.setOnScreen(true)
        com.swiftbrowser.media.PlaybackService.setOnScreen(true)
        if (!switcherOpen) current?.session?.let { if (it.isOpen) it.setActive(true) }
    }

    override fun onResume() {
        super.onResume()
        if (built) resumed()
    }

    private fun resumed() {
        // The battery saver started (or ended) while Swift was away: said now (2.3).
        announceBattery()
        NightLight.attach(this)
        // A change of theme rebuilds this screen behind Settings and leaves it started, so coming
        // back doesn't pass through onStart: colours chosen after that are caught here.
        if (appliedColors != null && appliedColors != Palette.signature) recreate()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!built) return
        // Android is short of memory (2.3): pictures that can be loaded again go first.
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) home.releasePictures()
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && level != TRIM_MEMORY_UI_HIDDEN) PreviewLoader.trim()
    }

    override fun onPause() {
        // A fresh picture for the tab switcher, also kept for the next start.
        if (built) current?.let { captureThumbnail(it, null) }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!built) return
        Looks.watchClock(false)
        Diagnostics.setOnScreen(false)
        com.swiftbrowser.media.PlaybackService.setOnScreen(false)
        TabManager.flushSaves()
        com.swiftbrowser.engine.DataUsage.sample()
        com.swiftbrowser.engine.DataUsage.save()
        if (!isChangingConfigurations) {
            // A playing page stays active so its video or music goes on (keep playing in the
            // background): an active page also keeps the engine's page process from being closed.
            val keepPlaying = prefs.keepPlaying && current?.playing == true
            current?.session?.let { if (it.isOpen && !keepPlaying) it.setActive(false) }
            AdBlock.updateInBackground()
            // A short trip to another app keeps everything; after a minute, other tabs let go of
            // their pages so Android is less likely to close Swift on a 2 GB phone.
            handler.removeCallbacks(freeBackgroundTabs)
            handler.postDelayed(freeBackgroundTabs, 60_000)
        }
        updateKeepScreenOn()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!built) {
            super.onDestroy()
            return
        }
        com.swiftbrowser.vpn.Vpn.removeListener(vpnListener)
        ptrSpin?.cancel()
        AdBlock.removeListener(adListener)
        Downloads.removeListener(updateDownloadListener)
        Downloads.removeListener(setupFileListener)
        if (TabManager.ui === this) TabManager.ui = null
        if (Engine.activityDelegate != null) Engine.activityDelegate = null
        home.listener = null
        switcherView?.listener = null
        gestureLayer.pullListener = null
        gestureLayer.fingerListener = null
        settleAnim?.cancel()
        com.swiftbrowser.engine.DataSaver.listener = null
        com.swiftbrowser.engine.Power.listener = null
        gestureLayer.edgeListener = null
        gestureLayer.listener = null
        gestureLayer.page = null
        gecko.releaseSession()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Engine.runtime.configurationChanged(newConfig)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        appBar = findViewById(R.id.app_bar)
        toolbar = findViewById(R.id.toolbar)
        profileChip = findViewById(R.id.profile_chip)
        incognitoIcon = findViewById(R.id.incognito_icon)
        urlBox = findViewById(R.id.url_box)
        urlEdit = findViewById(R.id.url_edit)
        urlIcon = findViewById(R.id.url_icon)
        urlIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        urlIcon.contentDescription = "Site settings"
        // 18 dp to look at, 48 dp to tap (2.6).
        (urlIcon.parent as View).post {
            val r = android.graphics.Rect()
            urlIcon.getHitRect(r)
            val grow = Ui.dp(this, 15)
            r.inset(-grow, -grow)
            (urlIcon.parent as View).touchDelegate = android.view.TouchDelegate(r, urlIcon)
        }
        // The site's icon (or the warning that it isn't secure) opens that site's settings.
        urlIcon.setOnClickListener {
            val tab = current
            val site = tab?.takeIf { !editing && !it.showsHome && UrlUtils.isHttp(it.url) }?.let { UrlUtils.site(it.url) }
            if (site == null) startEditing() else Sheets.siteSettings(this, site, tab)
        }
        urlMic = findViewById(R.id.url_mic)
        urlScan = findViewById(R.id.url_scan)
        urlClear = findViewById(R.id.url_clear)
        videoBtn = findViewById(R.id.video_btn)
        tabsCount = findViewById(R.id.tabs_count)
        menuBtn = findViewById(R.id.menu_btn)
        homeBtn = findViewById(R.id.home_btn)
        tabsBtn = findViewById(R.id.tabs_btn)
        progress = findViewById(R.id.progress)
        gestureLayer = findViewById(R.id.gesture_layer)
        gecko = findViewById(R.id.gecko)
        home = findViewById(R.id.home)
        crashView = findViewById(R.id.crash_view)
        pageCover = findViewById(R.id.page_cover)
        suggestionsList = findViewById(R.id.suggestions)
        ptrIndicator = findViewById(R.id.ptr_indicator)
        hud = findViewById(R.id.hud)
        findBar = findViewById(R.id.find_bar)
        findEdit = findViewById(R.id.find_edit)
        findCount = findViewById(R.id.find_count)
        startup = findViewById(R.id.startup)
        findViewById<ImageView>(R.id.startup_logo).setImageDrawable(Ui.logo(this))
        // Drawn above the pull-to-refresh circle, without a shadow on the page.
        appBar.elevation = dp(8f)
        appBar.outlineProvider = null
        com.swiftbrowser.vpn.Vpn.addListener(vpnListener)
        shownVpnState = com.swiftbrowser.vpn.Vpn.state
        updateVpnBadge()
        // The toolbar's buttons give the same small press as every button in the app.
        for (v in listOf<View>(homeBtn, tabsBtn, menuBtn, videoBtn, profileChip.parent as View)) Ui.pressable(v, 0.88f)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val tabId = intent.getLongExtra(EXTRA_TAB_ID, Long.MIN_VALUE)
        if (tabId != Long.MIN_VALUE) {
            intent.removeExtra(EXTRA_TAB_ID)
            TabManager.tabs.firstOrNull { it.id == tabId }?.let { t ->
                switcherView?.close()
                if (t !== current) TabManager.select(t)
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_ARRANGE_MENU, false)) {
            intent.removeExtra(EXTRA_ARRANGE_MENU)
            stopEditing()
            switcherView?.close()
            root.post { if (!isFinishing) quickMenu.open(arrange = true) }
            return
        }
        if (intent.action in SHORTCUTS && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            val action = intent.action
            intent.action = Intent.ACTION_MAIN
            stopEditing()
            switcherView?.close()
            quickMenu.close()
            when (action) {
                ACTION_NEW_TAB -> TabManager.newTab(incognito = false)
                ACTION_INCOGNITO -> TabManager.newTab(incognito = true)
                // Hold Swift's icon, or the widget (2.7).
                ACTION_DOCUMENTS -> openDocuments()
                ACTION_FILES -> FilesActivity.open(this)
                // The widget's bar and the New tab tile (2.6): a new tab (or the empty one open), ready to type.
                ACTION_SEARCH -> {
                    val open = current
                    if (open == null || open.incognito || !open.isBlank) TabManager.newTab(incognito = false)
                    root.post { if (!isFinishing) startEditing() }
                }
                ACTION_SCAN -> root.post { if (!isFinishing) scanQr() }
                ACTION_VPN -> root.post { if (!isFinishing) showVpnSheet() }
            }
            return
        }
        // 2.5: a document's home screen shortcut, or one of the last documents on Swift's icon.
        if (intent.action == Docs.ACTION_OPEN_DOC) {
            val docId = intent.getStringExtra(Docs.EXTRA_DOC_ID)
            intent.action = Intent.ACTION_MAIN
            stopEditing()
            switcherView?.close()
            quickMenu.close()
            if (docId == null || SwiftApp.db.document(docId) == null) snack("Swift doesn't have this document any more") else Docs.reopen(docId, backTo = Docs.BACK_APP)
            return
        }
        intent.getStringExtra(EXTRA_OPEN_FILE)?.let { path ->
            intent.removeExtra(EXTRA_OPEN_FILE)
            // Only Swift's own downloads (another app could otherwise make Swift show its private files).
            val d = Downloads.all().firstOrNull { it.filePath == path } ?: return
            stopEditing()
            switcherView?.close()
            if (!Docs.open(this, Uri.fromFile(java.io.File(path)), d.fileName, d.mime, d.url.takeIf { UrlUtils.isHttp(it) }, backTo = Docs.BACK_DOWNLOADS)) openFileInTab(path)
            return
        }
        // 2.4: a document from another app ("Open with Swift"), shown in Swift's viewer.
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && (data.scheme == "content" || data.scheme == "file")) {
            stopEditing()
            switcherView?.close()
            quickMenu.close()
            // Back closes it and returns to the app that asked (2.6).
            if (!Docs.open(this, data, null, intent.type, backTo = Docs.BACK_APP)) snack("Swift can't show this kind of file")
            return
        }
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value ?: UrlUtils.toUrlOrSearch(text)
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { UrlUtils.searchUrl(it) }
            else -> null
        }
        // Other apps can send Swift only web addresses (not file:, content: or javascript: ones).
        if (!url.isNullOrBlank() && UrlUtils.isHttp(url)) {
            stopEditing()
            switcherView?.close()
            TabManager.newTab(url, incognito = false)
        }
    }

    // ------------------------------------------------------------------ toolbar & address bar

    private fun setupToolbar() {
        profileChip.setOnClickListener { Sheets.profiles(this) }
        tabsBtn.setOnClickListener { openTabSwitcher() }
        tabsBtn.setOnLongClickListener {
            tabButtonMenu()
            true
        }
        menuBtn.setOnClickListener {
            stopEditing()
            quickMenu.open()
        }
        homeBtn.setOnClickListener { goHome() }
        videoBtn.setOnClickListener { current?.let { Sheets.videos(this, it) } }
        findViewById<View>(R.id.crash_reload).setOnClickListener {
            crashView.visibility = View.GONE
            current?.let { TabManager.revive(it) }
        }

        suggestionsList.layoutManager = LinearLayoutManager(this).apply { reverseLayout = bottomBar }
        suggestionsList.adapter = suggestionAdapter
        suggestionsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        // Start editing only on a real tap. (Android hands focus back to the only focusable
        // view when it is cleared, so focus changes alone can't be trusted.)
        root.isFocusableInTouchMode = true
        urlEdit.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> addressHeld = false
                // A hold opened the address menu instead.
                android.view.MotionEvent.ACTION_UP -> if (!editing && !addressHeld) startEditing()
            }
            false
        }
        // Whatever the keyboard's action key is called (Go, Search, Done, Enter), it searches.
        urlEdit.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) navigate(urlEdit.text.toString())
            true
        }
        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Typed at the end, or typed over the part the completion added (it is selected).
                typedAtEnd = !completing && s != null && count > 0 && start + count == s.length &&
                    (count > before || start == completedAt)
                if (!completing) completedAt = -1
            }

            override fun afterTextChanged(s: Editable?) {
                if (!editing || completing) return
                updateUrlButtons()
                val typed = s?.toString() ?: ""
                if (typedAtEnd && s != null) complete(s)
                // The first row is what Go opens: the completed address when there is one.
                refreshSuggestions(typed, s?.toString() ?: typed)
            }
        })
        urlClear.setOnClickListener { urlEdit.setText("") }
        // Holding the address (not while typing): copy it, or paste an address and go.
        urlEdit.setOnLongClickListener {
            if (editing) return@setOnLongClickListener false
            addressHeld = true
            addressMenu()
            true
        }
        urlMic.setOnClickListener { voiceSearch() }
        urlScan.setOnClickListener { scanQr() }
    }

    /** The address bar was held: letting go doesn't start typing. */
    private var addressHeld = false

    /** Sites the address completes to as it's typed (2.6), most visited first. */
    private var completionHosts: List<String> = emptyList()
    private var completing = false
    private var typedAtEnd = false

    /** Where the completion's added part starts, while it is there; -1 otherwise. */
    private var completedAt = -1

    private fun loadCompletions() {
        val pid = Profiles.currentId
        lifecycleScope.launch {
            completionHosts = withContext(Dispatchers.IO) {
                try {
                    val db = SwiftApp.db
                    (db.visitedUrls(pid, 400) + db.bookmarks(pid).map { it.url })
                        .mapNotNull { UrlUtils.host(it)?.removePrefix("www.") }
                        .distinct()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    /**
     * Typing "you" completes it to "youtube.com" (2.6), the added part selected: Go opens the site,
     * typing on replaces it, and backspace takes it away. Not while the keyboard is still composing a word.
     */
    private fun complete(s: Editable) {
        val typed = s.toString()
        if (typed.length < 2 || typed.any { it == ' ' || it == '/' || it == ':' || it == '?' }) return
        if (android.view.inputmethod.BaseInputConnection.getComposingSpanStart(s) != -1) return
        if (urlEdit.selectionStart != typed.length || urlEdit.selectionEnd != typed.length) return
        val lower = typed.lowercase()
        val host = completionHosts.firstOrNull { it.length > lower.length && it.startsWith(lower) } ?: return
        completing = true
        try {
            s.append(host.substring(typed.length))
            urlEdit.setSelection(typed.length, s.length)
            completedAt = typed.length
        } finally {
            completing = false
        }
    }

    private fun addressMenu() {
        val tab = current ?: return
        Haptics.hold(urlBox)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotEmpty() && it.length < 2000 }
        // Reader view's and the document viewer's own addresses aren't ones to copy or share.
        val page = !tab.showsHome && !tab.isBlank && !Helper.isReaderUrl(tab.url) && !Docs.isViewerUrl(tab.url)
        val actions = ArrayList<Sheets.Action>()
        if (clip != null) {
            val isAddress = UrlUtils.searchQuery(UrlUtils.toUrlOrSearch(clip)) == null
            actions += Sheets.Action(if (isAddress) R.drawable.ic_open_new else R.drawable.ic_search, if (isAddress) "Paste and go" else "Paste and search", primary = true) { navigate(clip) }
        }
        if (page) {
            actions += Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copy(tab.url) }
            actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(tab.url) }
        }
        actions += Sheets.Action(R.drawable.ic_edit, if (page) "Edit" else "Type", primary = true) { startEditing() }
        Sheets.actions(
            this,
            if (page) tab.displayTitle else if (clip != null) "Copied text" else null,
            if (page) UrlUtils.siteName(tab.url) else clip?.take(80),
            actions,
            icon = if (page) com.swiftbrowser.core.Favicons.cached(tab.url) else null,
            fullAddress = if (page) tab.url else clip,
        )
    }

    fun startEditing(fromHomeSearch: Boolean = false) {
        if (fullscreenTab != null) return
        val wasEditing = editing
        editing = true
        showToolbar()
        if (!wasEditing) setToolbarEditing(true)
        val tab = current
        val text = when {
            tab == null || tab.isBlank || tab.homeShown -> ""
            else -> UrlUtils.searchQuery(tab.url) ?: tab.url
        }
        urlBox.visibility = View.VISIBLE
        completing = true
        urlEdit.setText(text)
        completing = false
        urlEdit.selectAll()
        if (!wasEditing) loadCompletions()
        setUrlIcon(com.swiftbrowser.core.SearchEngine.current.icon)
        if (!wasEditing) {
            suggestionsList.alpha = 0f
            suggestionsList.animate().alpha(1f).setDuration(180).start()
        }
        suggestionsList.visibility = View.VISIBLE
        updateUrlButtons()
        if (!urlEdit.hasFocus()) urlEdit.requestFocus()
        showKeyboard(urlEdit)
        refreshSuggestions("")
        if (fromHomeSearch && !wasEditing && home.visibility == View.VISIBLE) slideSearchIntoBar()
    }

    /**
     * The home page's search box moves into the address bar: the bar starts where the box is and
     * slides to its place, while the box fades away. Only views of the app move (no page drawing).
     */
    private fun slideSearchIntoBar() {
        val box = home.searchBox
        val from = IntArray(2)
        val to = IntArray(2)
        box.getLocationOnScreen(from)
        urlBox.getLocationOnScreen(to)
        val dy = (from[1] + box.height / 2f) - (to[1] + urlBox.height / 2f)
        if (abs(dy) < dp(8f)) return
        // The bar is drawn outside the toolbar while it travels.
        toolbar.clipChildren = false
        (appBar as android.view.ViewGroup).clipChildren = false
        urlBox.animate().cancel()
        urlBox.translationY = dy
        urlBox.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.8f)).withEndAction {
            toolbar.clipChildren = true
            (appBar as android.view.ViewGroup).clipChildren = true
        }.start()
        box.animate().cancel()
        box.animate().alpha(0f).translationY(-dy / 3f).setDuration(160).start()
    }

    /** The home page's search box comes back (Back from typing on the home page). */
    private fun restoreHomeSearch() {
        val box = home.searchBox
        if (box.alpha == 1f && box.translationY == 0f) return
        box.animate().cancel()
        box.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /**
     * While typing, the address takes the whole toolbar: the buttons around it fade away and the
     * field widens, and they come back after. A short animation of the toolbar's own views.
     */
    private fun setToolbarEditing(on: Boolean) {
        // Buttons fade, the field's width follows; the field itself never fades (it may be sliding in).
        val transition = android.transition.TransitionSet().apply {
            addTransition(android.transition.Fade(android.transition.Fade.OUT))
            addTransition(android.transition.ChangeBounds())
            addTransition(android.transition.Fade(android.transition.Fade.IN).excludeTarget(urlBox, true))
            duration = 170
            ordering = android.transition.TransitionSet.ORDERING_TOGETHER
        }
        android.transition.TransitionManager.beginDelayedTransition(toolbar, transition)
        for (v in toolbarButtons()) v.visibility = if (on) View.GONE else View.VISIBLE
        if (on) videoBtn.visibility = View.GONE else refreshToolbarNow()
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        setToolbarEditing(false)
        urlBox.animate().cancel()
        urlBox.translationY = 0f
        suggestJob?.cancel()
        suggestionsList.visibility = View.GONE
        restoreHomeSearch()
        updateUrlButtons()
        hideKeyboard()
        if (!gecko.requestFocus()) root.requestFocus()
        shownUrlText = null
        shownUrlIcon = 0
        current?.let { updateHome(it) }
        updateUrlBar()
    }

    private fun updateUrlButtons() {
        updateVpnBadge()
        val empty = urlEdit.text.isNullOrEmpty()
        Motion.pop(urlClear, editing && !empty)
        Motion.pop(urlMic, editing && empty)
        Motion.pop(urlScan, editing && empty)
    }

    private fun navigate(text: String) {
        val url = UrlUtils.toUrlOrSearch(text)
        if (url.isBlank()) return
        stopEditing()
        openInCurrent(url)
    }

    /** A file Swift saved (a page as PDF): the engine shows it. */
    fun openFileInTab(path: String) {
        stopEditing()
        switcherView?.close()
        TabManager.newTab(android.net.Uri.fromFile(java.io.File(path)).toString(), incognito = false)
    }

    override fun showMessage(text: String, action: String?, long: Boolean, onAction: (() -> Unit)?) =
        snack(text, action, long, onAction)

    fun openInCurrent(url: String) {
        val tab = current
        if (tab == null) {
            TabManager.newTab(url)
            return
        }
        TabManager.load(tab, url)
        gecko.requestFocus()
    }

    fun voiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        if (intent.resolveActivity(packageManager) == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Voice search isn't available")
                .setMessage("Voice search uses Google's speech recognition, which comes with the Google app. Install or enable the Google app on this phone to use it.")
                .setPositiveButton(android.R.string.ok, null)
                .showImmersive()
            return
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            snack("Couldn't start voice search")
        }
    }

    private fun refreshSuggestions(text: String, full: String = text) {
        suggestJob?.cancel()
        val pid = Profiles.currentId
        val google = prefs.searchSuggestions
        suggestJob = lifecycleScope.launch {
            // A pause in typing first (2.6): fast typing doesn't look up every letter.
            if (text.isNotBlank()) delay(70)
            val head = ArrayList<Suggestion>()
            if (full.isNotBlank()) {
                val target = UrlUtils.toUrlOrSearch(full)
                head += if (UrlUtils.searchQuery(target) != null) {
                    Suggestion(Suggestion.Kind.SEARCH, full, target)
                } else {
                    Suggestion(Suggestion.Kind.GO, full, target)
                }
            }
            if (text.isBlank()) copiedLink()?.let { head += Suggestion(Suggestion.Kind.CLIPBOARD, "Link you copied", it) }
            val tabs = openTabMatches(text)
            val local = withContext(suggestThread) { Suggestions.local(pid, text) }
                .filter { s -> tabs.none { it.url == s.url } }
            fun groups(fromEngine: List<Suggestion>) = listOf(
                SuggestionAdapter.Group(null, head),
                SuggestionAdapter.Group("Search suggestions", fromEngine),
                SuggestionAdapter.Group("Open tabs", tabs),
                SuggestionAdapter.Group("Bookmarks", local.filter { it.kind == Suggestion.Kind.BOOKMARK }),
                SuggestionAdapter.Group(if (text.isBlank()) "Recent sites" else "History", local.filter { it.kind == Suggestion.Kind.HISTORY }),
            )
            suggestionAdapter.show(groups(emptyList()), text)
            if (google && text.isNotBlank()) {
                delay(150)
                val fromGoogle = withContext(suggestThread) { Suggestions.fromEngine(text) }
                    .filter { !it.equals(text, ignoreCase = true) }
                    .take(5)
                    .map { Suggestion(Suggestion.Kind.SEARCH, it, UrlUtils.searchUrl(it)) }
                suggestionAdapter.show(groups(fromGoogle), text)
            }
        }
    }

    /** A web address on the clipboard (not the page that is open), offered when the address bar opens. */
    private fun copiedLink(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (_: Exception) {
            null
        } ?: return null
        if (text.isEmpty() || text.length > 2000 || text.contains(' ') || text.contains('\n')) return null
        val url = when {
            UrlUtils.isHttp(text) -> text
            BARE_ADDRESS.matches(text) -> "https://$text"
            else -> return null
        }
        return url.takeIf { it != current?.url }
    }

    /** Open tabs (other than this one) whose title or address has the typed words: "Switch to tab". */
    private fun openTabMatches(text: String): List<Suggestion> {
        val q = text.trim()
        if (q.length < 2) return emptyList()
        val here = current
        return TabManager.tabs.asSequence()
            .filter { it !== here && it.incognito == (here?.incognito ?: false) && !it.isBlank }
            .filter { it.displayTitle.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .take(3)
            .map { Suggestion(Suggestion.Kind.TAB, it.displayTitle, it.url, it.id) }
            .toList()
    }

    /** The arrow on a suggestion: it goes into the address box to keep typing. */
    private fun fillSuggestion(s: Suggestion) {
        val t = if (s.kind == Suggestion.Kind.SEARCH || s.kind == Suggestion.Kind.GO) s.title else s.url
        urlEdit.setText(t)
        urlEdit.setSelection(urlEdit.text.length)
    }

    private fun onSuggestion(s: Suggestion) {
        stopEditing()
        if (s.kind == Suggestion.Kind.TAB) {
            TabManager.tabs.firstOrNull { it.id == s.tabId }?.let {
                TabManager.select(it)
                return
            }
        }
        openInCurrent(s.url)
    }

    private fun updateUrlBar() {
        if (editing) return
        val tab = current
        val url = tab?.url
        val query = UrlUtils.searchQuery(url)
        val reader = Helper.isReaderUrl(url)
        val document = Docs.isViewerUrl(url)
        val saved = url?.startsWith("file://") == true
        val blocked = AdBlock.isBlockedPage(url)
        val text = when {
            blocked -> "Page blocked · " + UrlUtils.siteName(AdBlock.blockedPageTarget(url))
            reader -> "Reader view · ${tab?.title.orEmpty()}"
            document -> tab?.title?.takeIf { it.isNotBlank() && it != "Document" } ?: "Document"
            saved -> (if (tab?.backTo == Docs.BACK_FILES) "File · " else "Saved page · ") +
                Uri.decode(url.orEmpty().substringAfterLast('/')).removeSuffix(".pdf")
            // While reading, just the site (the whole address shows when the bar is tapped).
            else -> query ?: UrlUtils.siteName(url)
        }
        if (text != shownUrlText) {
            shownUrlText = text
            urlEdit.setText(text)
        }
        val page = tab != null && !tab.isBlank && query == null && !reader && !saved && !blocked && !document
        // A secure site shows its own icon; only a site that isn't secure gets a warning.
        val favicon = if (page && tab!!.secure) com.swiftbrowser.core.Favicons.cached(url) else null
        if (favicon != null) {
            if (favicon !== shownFavicon) {
                shownFavicon = favicon
                shownUrlIcon = -1
                urlIcon.imageTintList = null
                urlIcon.setImageBitmap(favicon)
            }
            urlIcon.contentDescription = "Site settings"
            return
        }
        shownFavicon = null
        val icon = when {
            blocked -> R.drawable.ic_shield
            reader -> R.drawable.ic_reader
            document -> R.drawable.ic_documents
            saved -> R.drawable.ic_offline
            !page -> com.swiftbrowser.core.SearchEngine.current.icon
            // Only a site opened over plain http gets the warning (an https page that failed to
            // load isn't "not secure").
            UrlUtils.scheme(url) == "http" -> R.drawable.ic_not_secure
            else -> R.drawable.ic_globe
        }
        urlIcon.contentDescription = if (icon == R.drawable.ic_not_secure) "Not secure. Site settings" else if (page) "Site settings" else null
        if (icon != shownUrlIcon) setUrlIcon(icon)
    }

    private var shownFavicon: android.graphics.Bitmap? = null

    private fun setUrlIcon(res: Int) {
        shownUrlIcon = res
        shownFavicon = null
        urlIcon.setImageResource(res)
        urlIcon.imageTintList = when {
            com.swiftbrowser.core.SearchEngine.isLogo(res) -> null
            res == R.drawable.ic_not_secure -> ColorStateList.valueOf(Ui.danger(this))
            else -> ColorStateList.valueOf(iconColor())
        }
    }

    /** The tab whose loading the bar shows, and whether it is gliding to the end before fading. */
    private var progressTab: Tab? = null
    private var progressEnding = false
    private var progressAnim: android.animation.ObjectAnimator? = null

    /**
     * The loading bar glides forward instead of jumping, and when the page is done it runs to the
     * end and fades. Switching tabs or opening the home page hides it at once.
     */
    private fun updateProgress() {
        val tab = current
        val show = tab != null && tab.loading && fullscreenTab == null && !tab.showsHome
        if (!show) {
            val finished = tab != null && tab === progressTab && !tab.loading && fullscreenTab == null && !tab.showsHome
            progressTab = null
            if (progress.visibility != View.VISIBLE || progressEnding) return
            if (finished) {
                progressEnding = true
                glideProgress(100, 160)
                progress.animate().alpha(0f).setStartDelay(160).setDuration(Motion.NORMAL).withEndAction { resetProgress() }.start()
            } else {
                resetProgress()
            }
            return
        }
        if (tab !== progressTab || progressEnding) {
            // A new load (or another tab): start from the beginning.
            resetProgress()
            progressTab = tab
            progress.visibility = View.VISIBLE
        }
        glideProgress(tab!!.progress.coerceIn(5, 100) * 10, 260)
    }

    private fun glideProgress(target: Int, ms: Long) {
        if (target <= progress.progress && target != 1000) return
        progressAnim?.cancel()
        // While the page loads, the bar is set at once (2.6): gliding redrew the window every frame
        // while the phone was busiest. Only its run to the end glides.
        if (target < 1000) {
            progress.progress = target
            return
        }
        progressAnim = android.animation.ObjectAnimator.ofInt(progress, "progress", progress.progress, target).apply {
            duration = ms
            interpolator = Motion.standard
            start()
        }
    }

    private fun resetProgress() {
        progressAnim?.cancel()
        progress.animate().cancel()
        progressEnding = false
        progress.visibility = View.INVISIBLE
        progress.alpha = 1f
        progress.progress = 0
    }

    private fun updateProfileChip() {
        home.updateProfile()
        val p = Profiles.current()
        profileChip.text = Profiles.initial(p)
        profileChip.backgroundTintList = ColorStateList.valueOf(p.color)
        profileChip.contentDescription = "Profile: ${p.name}"
    }

    /** Incognito tabs get a dark toolbar whatever the theme, like in Chrome. */
    private fun applyIncognitoStyle(incognito: Boolean) {
        if (styledIncognito == incognito) return
        styledIncognito = incognito
        val bg = color(if (incognito) R.color.incognito_toolbar else R.color.toolbar_bg)
        toolbar.setBackgroundColor(bg)
        findViewById<View>(R.id.toolbar_divider).setBackgroundColor(color(if (incognito) R.color.incognito_url else R.color.divider))
        window.statusBarColor = bg
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !incognito && !night
        urlBox.background = ContextCompat.getDrawable(this, R.drawable.bg_url)?.mutate()?.also {
            it.setTint(color(if (incognito) R.color.incognito_url else R.color.surface_alt))
        }
        val text = color(if (incognito) R.color.incognito_text else R.color.text_primary)
        urlEdit.setTextColor(text)
        urlEdit.setHintTextColor(iconColor())
        val icons = ColorStateList.valueOf(iconColor())
        menuBtn.imageTintList = icons
        homeBtn.imageTintList = icons
        urlClear.imageTintList = icons
        urlScan.imageTintList = icons
        // The find bar and the suggestions in the same colours (2.6).
        findBar.setBackgroundColor(bg)
        findEdit.background = ContextCompat.getDrawable(this, R.drawable.bg_url)?.mutate()?.also {
            it.setTint(color(if (incognito) R.color.incognito_url else R.color.surface_alt))
        }
        findEdit.setTextColor(text)
        findEdit.setHintTextColor(iconColor())
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(findEdit, icons)
        findCount.setTextColor(iconColor())
        for (id in intArrayOf(R.id.find_prev, R.id.find_next, R.id.find_close)) findViewById<ImageView>(id).imageTintList = icons
        suggestionsList.setBackgroundColor(color(if (incognito) R.color.incognito_bg else R.color.toolbar_bg))
        suggestionAdapter.incognito = incognito
        styledTabCount = null
        styleTabCount()
        profileChip.visibility = if (incognito) View.GONE else View.VISIBLE
        incognitoIcon.visibility = if (incognito) View.VISIBLE else View.GONE
        shownUrlIcon = 0
        bookmarkShownFor = null
        updateToolbarExtras()
    }

    private fun iconColor(): Int =
        color(if (current?.incognito == true) R.color.incognito_hint else R.color.text_secondary)

    // ------------------------------------------------------------------ home page

    private fun setupHome() {
        home.listener = object : HomeView.Listener {
            override fun onSearchBoxClicked() = startEditing(fromHomeSearch = true)

            override fun onVoiceSearch() = voiceSearch()

            override fun onTileClicked(url: String) = openInCurrent(url)

            override fun onTileLongClicked(tile: TopSites.Tile, anchor: View) = tileMenu(tile)

            override fun onAddTile() = editTile(null)

            override fun onStoryClicked(story: com.swiftbrowser.news.Story) = openInCurrent(story.url)

            override fun onStoryLongClicked(story: com.swiftbrowser.news.Story) = storyMenu(story)

            override fun onNewsSettings() {
                openUrlLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TOPIC, "NEWS"))
            }

            override fun onContinueClicked(item: com.swiftbrowser.media.Unfinished.Item) {
                // A document opens in Swift's viewer, where it was left (2.4).
                item.doc?.let { Docs.reopen(it.id, from = current); return }
                openInCurrent(item.url)
                // An article opens in reader view again, where it was left.
                if (!item.video) current?.readerOnLoad = item.url
            }

            override fun onContinueLongClicked(item: com.swiftbrowser.media.Unfinished.Item) = continueMenu(item)

            override fun onDocumentsTile() = openDocuments()

            override fun onProfileClicked() = Sheets.profiles(this@MainActivity)
        }
    }

    /**
     * Sites set to "Open articles in reader view" (2.3): each page once (going back to it from
     * reader view stays on it), not the front page, and nothing said when a page has no article.
     */
    private fun autoReader(tab: Tab, attempt: Int = 0) {
        val url = tab.url
        if (!UrlUtils.isHttp(url) || Helper.isReaderUrl(url) || !SiteSettings.autoReader(UrlUtils.site(url))) return
        val path = Uri.parse(url).path
        if (path.isNullOrEmpty() || path == "/") return
        val page = url.substringBefore('#')
        if (page in tab.autoReaderDone) return
        tab.quietReader = true
        if (Helper.startReader(tab)) {
            tab.autoReaderDone.add(page)
            return
        }
        tab.quietReader = false
        // The page's helper hasn't connected yet when it has just finished loading: a moment later.
        if (attempt < 5) handler.postDelayed({ if (current === tab && tab.url == url && !tab.loading) autoReader(tab, attempt + 1) }, 400)
    }

    /** Says once that the battery saver started, and that it ended if that was said. */
    private fun announceBattery() {
        val power = com.swiftbrowser.engine.Power
        if (power.saving && !power.noticeShown) {
            power.noticeShown = true
            snack("Battery low: Swift is saving power", "Settings") {
                openUrlLauncher.launch(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TOPIC, "SPEED"))
            }
        } else if (!power.saving && power.noticeShown) {
            power.noticeShown = false
            snack("Battery saver off: Swift is back to full speed")
        }
    }

    /** Holding a card in the home page's "Continue" (2.3). */
    private fun continueMenu(item: com.swiftbrowser.media.Unfinished.Item) {
        item.doc?.let { doc ->
            val actions = listOf(
                Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { Docs.reopen(doc.id, from = current) },
                Sheets.Action(R.drawable.ic_share, "Share", primary = true) { Docs.handOver(this, doc, share = true) },
                Sheets.Action(R.drawable.ic_close, "Remove from Continue", danger = true) { home.removeContinue(item) },
            )
            Sheets.actions(this, doc.name, item.caption, actions)
            return
        }
        val actions = listOf(
            Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) {
                val tab = TabManager.newTab(item.url, incognito = false)
                if (!item.video) tab.readerOnLoad = item.url
            },
            Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(item.url) },
            Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copy(item.url) },
            Sheets.Action(R.drawable.ic_close, "Remove from Continue", danger = true) { home.removeContinue(item) },
        )
        Sheets.actions(this, item.title.ifBlank { item.url }, item.caption, actions, com.swiftbrowser.core.Favicons.cached(item.url))
    }

    /** Holding a story in the home page's news. */
    private fun storyMenu(story: com.swiftbrowser.news.Story) {
        val actions = listOf(
            Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { TabManager.newTab(story.url, incognito = false) },
            Sheets.Action(R.drawable.ic_incognito, "Incognito", primary = true) { TabManager.newTab(story.url, incognito = true) },
            Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(story.url) },
            Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copy(story.url) },
            Sheets.Action(R.drawable.ic_block, "Hide stories from ${story.source}", danger = true) {
                com.swiftbrowser.news.News.hideSource(story.source)
                home.refreshNews()
                snack("Stories from ${story.source} are hidden", "Undo") {
                    prefs.newsHidden = prefs.newsHidden - story.source
                    home.refreshNews()
                }
            },
        )
        Sheets.actions(this, story.title, story.source, actions, com.swiftbrowser.core.Favicons.cached(story.sourceUrl ?: story.url))
    }

    /** Holding a home page shortcut. */
    private fun tileMenu(tile: TopSites.Tile) {
        val pid = Profiles.currentId
        val actions = ArrayList<Sheets.Action>()
        actions += Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { TabManager.newTab(tile.url, incognito = false) }
        actions += Sheets.Action(R.drawable.ic_incognito, "Incognito", primary = true) { TabManager.newTab(tile.url, incognito = true) }
        actions += Sheets.Action(R.drawable.ic_edit, "Edit", primary = true) { editTile(tile) }
        if (TopSites.pinned(pid).firstOrNull()?.url != tile.url) {
            actions += Sheets.Action(R.drawable.ic_up, "Move to the start") {
                TopSites.moveToStart(pid, tile)
                home.refreshTiles()
            }
        }
        actions += Sheets.Action(R.drawable.ic_delete, "Remove", primary = true, danger = true) {
            // Also kept out of the most visited sites, or it would come straight back.
            val before = TopSites.pinned(pid)
            val wasHidden = tile.key in prefs.hiddenTiles
            if (tile.pinned) TopSites.unpin(pid, tile)
            prefs.hiddenTiles = prefs.hiddenTiles + tile.key
            home.refreshTiles()
            snack("Removed ${tile.label}", "Undo") {
                if (tile.pinned) TopSites.setPinned(pid, before)
                if (!wasHidden) prefs.hiddenTiles = prefs.hiddenTiles - tile.key
                home.refreshTiles()
            }
        }
        Sheets.actions(this, tile.label, UrlUtils.siteName(tile.url), actions, com.swiftbrowser.core.Favicons.cached(tile.url), fullAddress = tile.url)
    }

    /** Adds a shortcut ([old] null) or changes one; changed shortcuts stay where they are. */
    private fun editTile(old: TopSites.Tile?) {
        val pad = dp(20f).toInt()
        val name = EditText(this).apply {
            hint = "Name"
            setSingleLine()
            setText(old?.label.orEmpty())
        }
        val address = EditText(this).apply {
            hint = "Address, e.g. youtube.com"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(old?.url ?: current?.url?.takeIf { UrlUtils.isHttp(it) }.orEmpty())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8f).toInt(), pad, 0)
            addView(name)
            addView(address)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) "Add a shortcut" else "Edit shortcut")
            .setView(box)
            .setPositiveButton(if (old == null) "Add" else "Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        // A wrong address keeps the dialog open with what was typed (2.6), saying what's wrong.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val url = UrlUtils.toUrlOrSearch(address.text.toString())
            if (!UrlUtils.isHttp(url) || UrlUtils.searchQuery(url) != null && !address.text.contains('.')) {
                address.error = "That isn't a web address"
                address.requestFocus()
                return@setOnClickListener
            }
            dialog.dismiss()
            val pid = Profiles.currentId
            if (old == null) TopSites.addPinned(pid, url, name.text.toString().trim())
            else TopSites.editPinned(pid, old, url, name.text.toString().trim())
            home.refreshTiles()
            // Its real icon (the site's big app icon, else its favicon), not just a letter.
            UrlUtils.host(url)?.let { host ->
                com.swiftbrowser.core.Favicons.onPageIcon(url, url.substringBefore("://") + "://" + host + "/apple-touch-icon.png")
            }
        }
    }

    private fun updateHome(tab: Tab) {
        val show = tab.showsHome && fullscreenTab == null
        if (show) {
            if (home.visibility != View.VISIBLE || homeShownFor !== tab) {
                home.show(tab.incognito)
                home.scrollToTop()
                homeShownFor = tab
                if (!editing) {
                    home.searchBox.alpha = 1f
                    home.searchBox.translationY = 0f
                }
            }
            home.visibility = View.VISIBLE
        } else {
            if (home.visibility == View.VISIBLE) home.releasePictures()
            home.visibility = View.GONE
            homeShownFor = null
        }
        val urlVisibility = if (show && !editing) View.INVISIBLE else View.VISIBLE
        if (urlBox.visibility != urlVisibility) urlBox.visibility = urlVisibility
    }

    /** The home button: the home page over the tab's page; Back returns to the page. */
    private fun goHome() {
        val tab = current ?: return
        stopEditing()
        closeFind()
        if (tab.showsHome) {
            home.scrollToTop()
            return
        }
        tab.homeShown = true
        // The page underneath needs no drawing (unless it is playing and should go on).
        if (!(prefs.keepPlaying && tab.playing)) tab.session?.let { if (it.isOpen) it.setActive(false) }
        showToolbar()
        updateHome(tab)
        home.animate().cancel()
        home.alpha = 0f
        home.translationY = dp(24f)
        home.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
        refreshToolbarNow()
    }

    private fun leaveHome(tab: Tab) {
        tab.homeShown = false
        tab.session?.let { if (it.isOpen) it.setActive(true) }
        updateHome(tab)
        refreshToolbarNow()
    }

    // ------------------------------------------------------------------ tab switcher

    private fun setupSwitcher(s: TabSwitcherView) {
        s.listener = object : TabSwitcherView.Listener {
            override fun onTabChosen(tab: Tab) {
                if (tab !== current) TabManager.select(tab)
                s.close(pageRect())
            }

            override fun onNewTab(incognito: Boolean) {
                s.close()
                TabManager.newTab(incognito = incognito)
            }

            override fun onNewTabInGroup(group: TabGroup) {
                s.close()
                TabManager.newTabInGroup(group)
            }

            override fun onSwitcherClosed() {
                current?.session?.let { if (it.isOpen) it.setActive(true) }
            }
        }
    }

    /** Holding the tab button: the quick things to do with tabs. */
    private fun tabButtonMenu() {
        val tab = current ?: return
        Haptics.hold(tabsBtn)
        val actions = listOf(
            Sheets.Action(R.drawable.ic_add, "New tab", primary = true) { TabManager.newTab(incognito = false) },
            Sheets.Action(R.drawable.ic_incognito, "Incognito tab", primary = true) { TabManager.newTab(incognito = true) },
            Sheets.Action(R.drawable.ic_reopen, "Recent tabs", primary = true) { Sheets.recentlyClosed(this) },
            Sheets.Action(R.drawable.ic_close, "Close tab", primary = true, danger = true) { TabManager.close(tab, undoable = true) },
        )
        Sheets.actions(this, "Tabs", "${TabManager.tabs.count { it.incognito == tab.incognito }} open", actions)
    }

    fun openTabSwitcher() {
        if (switcherOpen) return
        quickMenu.close()
        stopEditing()
        closeFind()
        val tab = current
        switcher.open(tab?.incognito == true, pageRect())
        if (tab == null) return
        captureThumbnail(tab) {
            if (switcherOpen) {
                switcher.refreshTab(tab)
                // Nothing on the page needs drawing while the grid covers it.
                if (current === tab) tab.session?.let { if (it.isOpen) it.setActive(false) }
            }
        }
    }

    /** Where the page (or home page) is on the screen, in the tab grid's coordinates. */
    private fun pageRect(): android.graphics.Rect {
        val bar = toolbarShown.roundToInt()
        val at = IntArray(2)
        val grid = IntArray(2)
        root.getLocationInWindow(at)
        switcher.getLocationInWindow(grid)
        val dy = at[1] - grid[1]
        return android.graphics.Rect(0, dy + if (bottomBar) 0 else bar, root.width, dy + root.height - if (bottomBar) bar else 0)
    }

    private var thumbnailTries = 0

    private val thumbnailRunnable: Runnable = Runnable {
        val tab = current ?: return@Runnable
        if (tab.loading || switcherOpen || fullscreenTab != null) return@Runnable
        // Reading the screen back holds up drawing for a moment; wait until scrolling stops.
        if (SystemClock.uptimeMillis() - lastUserScrollAt < 1500 && thumbnailTries++ < 10) {
            handler.postDelayed(thumbnailRunnable, 1500)
            return@Runnable
        }
        captureThumbnail(tab, null)
    }

    /** Pictures for the tab grid are taken once a page has settled after loading. */
    /** Each tab's last picture after a page load: which address, and when (2.6). */
    private val thumbnailTaken = HashMap<Long, Pair<String, Long>>()

    private fun scheduleThumbnail(tab: Tab) {
        if (tab.isHome) return
        // The same page again within a minute (a reload, a link to a part of it): the picture is
        // recent enough, and taking one costs a full-screen copy (2.6).
        val now = android.os.SystemClock.elapsedRealtime()
        val last = thumbnailTaken[tab.id]
        if (last != null && last.first == tab.url && now - last.second < 60_000) return
        thumbnailTaken[tab.id] = tab.url to now
        thumbnailTries = 0
        handler.removeCallbacks(thumbnailRunnable)
        handler.postDelayed(thumbnailRunnable, 2000)
    }

    private fun captureThumbnail(tab: Tab, done: (() -> Unit)?) {
        if (tab.session == null || tab.isHome || gecko.session !== tab.session) {
            done?.invoke()
            return
        }
        Thumbnails.capture(gecko, tab, gecko.height - toolbarShown.roundToInt(), done)
    }

    // ------------------------------------------------------------------ BrowserUi

    /** Sites already offered "Show images" on mobile data (Data saver), this run. */
    private val imagesOffered = HashSet<String>()

    private fun offerImages(tab: Tab) {
        if (!com.swiftbrowser.engine.DataSaver.active || tab.showsHome || !UrlUtils.isHttp(tab.url)) return
        val site = UrlUtils.site(tab.url) ?: return
        if (!imagesOffered.add(site)) return
        snack("Pictures are off on mobile data (Data saver)", "Show", long = true) {
            com.swiftbrowser.engine.DataSaver.showImagesOn(site)
            // Once the engine has the new setting.
            handler.postDelayed({ if (current === tab) TabManager.reload(tab) }, 400)
        }
    }

    // ------------------------------------------------------------------ continue watching

    private var resumeChip: View? = null
    private var resumeChipFor: Tab? = null
    private var resumeChipPage: String? = null

    /** "Continue from 42:10": stays until used, closed, or the video gets there by itself. */
    override fun showResumeChip(tab: Tab, position: String, onResume: () -> Unit) {
        if (tab !== current) return
        hideResumeChip(null)
        resumeChipFor = tab
        resumeChipPage = tab.url.substringBefore('#')
        val pad = dp(14f).toInt()
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, 0, dp(4f).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(color(Looks.accent.primary))
            }
            elevation = dp(6f)
            addView(TextView(this@MainActivity).apply {
                text = "▶  Continue from $position"
                textSize = 15f
                setTextColor(color(Looks.accent.onPrimary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, dp(6f).toInt(), 0)
            })
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(color(Looks.accent.onPrimary))
                background = null
                contentDescription = "Close"
                setOnClickListener { hideResumeChip(null) }
            }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))
            setOnClickListener {
                onResume()
                hideResumeChip(null)
            }
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(48f).toInt(), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(24f).toInt() + if (bottomBar) toolbarPx else 0
        root.addView(chip, lp)
        chip.alpha = 0f
        chip.translationY = dp(16f)
        chip.animate().alpha(1f).translationY(0f).setDuration(200).start()
        resumeChip = chip
        // Not for ever: after two minutes the offer goes.
        handler.postDelayed(hideChipLater, 120_000)
    }

    private val hideChipLater = Runnable { hideResumeChip(null) }

    override fun hideResumeChip(tab: Tab?) {
        if (tab != null && tab !== resumeChipFor) return
        handler.removeCallbacks(hideChipLater)
        val chip = resumeChip ?: return
        resumeChip = null
        resumeChipFor = null
        resumeChipPage = null
        chip.animate().alpha(0f).translationY(dp(16f)).setDuration(150).withEndAction { root.removeView(chip) }.start()
    }

    override fun onTabSelected(tab: Tab) {
        selectionMenu.dismiss()
        if (resumeChipFor != null && resumeChipFor !== tab) hideResumeChip(null)
        // What this tab's page asked while it wasn't on screen (an alert, a sign-in) shows now (2.6).
        tab.session?.let { s -> root.post { if (current === tab) com.swiftbrowser.tabs.SessionFactory.prompts.showWaiting(s) } }
        Engine.showTextScaleFor(UrlUtils.site(tab.url))
        val isNew = seenTabs.add(tab.id)
        syncSession(tab)
        crashView.visibility = if (tab.crashed) View.VISIBLE else View.GONE
        if (fullscreenTab != null && fullscreenTab !== tab) exitFullscreen()
        closeFind()
        if (editing) stopEditing()
        cancelPullRefresh()
        lastScrollY = -1f
        shownLoading = tab.loading
        showToolbar()
        applyIncognitoStyle(tab.incognito)
        updateAll()
        if (isNew) {
            animateNewTab(tab)
        } else if (tab.wokeUp) {
            tab.wokeUp = false
            tabSwipe.coverUntilDrawn(tab)
        }
    }

    /** Shows the tab's engine session in the page view (or nothing for the home page). */
    private fun syncSession(tab: Tab) {
        val session = tab.session
        if (session != null) {
            if (session.selectionActionDelegate !== selectionMenu) session.selectionActionDelegate = selectionMenu
            if (gecko.session !== session) gecko.setSession(session)
        } else if (gecko.session != null) {
            gecko.releaseSession()
        }
    }

    override fun onTabsChanged() {
        // While an incognito tab is open, the engine's own messages aren't logged (2.7).
        com.swiftbrowser.core.Health.quiet = TabManager.tabs.any { it.incognito }
        val n = (if (current?.incognito == true) TabManager.incognitoTabs() else TabManager.profileTabs()).size
        tabsCount.text = if (n > 99) ":D" else n.toString()
        styleTabCount()
        // How many, for screen readers too (2.6).
        val group = current?.let { TabManager.groupOf(it) }
        tabsBtn.contentDescription = "$n ${if (n == 1) "tab" else "tabs"}" + if (group != null) " (group ${TabManager.groupTitle(group)})" else ""
        updateProfileChip()
        if (switcherOpen) switcher.refresh()
    }

    private var styledTabCount: Int? = null

    /** The tab button takes the colour of the open tab's group, so you can tell you're in one. */
    private fun styleTabCount() {
        val tab = current
        val group = tab?.let { TabManager.groupOf(it) }
        val incognito = tab?.incognito == true
        val key = (group?.color ?: -1) * 2 + if (incognito) 1 else 0
        if (key == styledTabCount) return
        styledTabCount = key
        val text = when {
            group != null -> color(TabGroup.colorRes(group.color))
            incognito -> color(R.color.incognito_text)
            else -> color(R.color.text_primary)
        }
        tabsCount.setTextColor(text)
        tabsCount.background = tabCountBackground(this, if (group != null || incognito) text else color(R.color.text_secondary))
        // Its spoken label (how many tabs) is set in onTabsChanged.
    }

    override fun onTabUpdated(tab: Tab) {
        if (tab !== current) return
        syncSession(tab)
        val pageStarted = tab.loading && !shownLoading
        if (pageStarted) {
            selectionMenu.dismiss()
            if (themeReloadTab === tab) themeReloadStarted = true
            // A new page: bring the toolbar back like Chrome does.
            lastScrollY = -1f
            showToolbar()
            if (ptrRefreshing) finishPullRefresh()
        }
        if (!tab.loading && shownLoading) {
            scheduleThumbnail(tab)
            val wanted = tab.readerOnLoad
            if (wanted != null) {
                tab.readerOnLoad = null
                if (UrlUtils.site(tab.url) == UrlUtils.site(wanted) && !Helper.isReaderUrl(tab.url)) Helper.startReader(tab)
            } else {
                autoReader(tab)
            }
            if (themeReloadTab === tab && themeReloadStarted) {
                themeReloadTab = null
                handler.postDelayed({ if (current === tab) TabManager.reload(tab) }, 500)
            }
        }
        Engine.showTextScaleFor(UrlUtils.site(tab.url))
        if (tab.loading != shownLoading || pageStarted) {
            com.swiftbrowser.engine.DataSaver.onSiteShown(UrlUtils.site(tab.url))
            if (!tab.loading) offerImages(tab)
        }
        shownLoading = tab.loading
        updateHome(tab)
        if (resumeChipFor != null && (resumeChipFor !== tab || resumeChipPage != tab.url.substringBefore('#'))) hideResumeChip(null)
        if (pageStarted) refreshToolbarNow() else requestToolbarRefresh()
        if (switcherOpen) switcher.refreshTab(tab)
    }

    /**
     * Progress, the ad counter and the address change many times while a page loads or scrolls.
     * Every toolbar redraw makes the app's window draw a frame, which competes with the page for
     * the GPU and holds up touch handling, so they are drawn at most about 6 times a second.
     */
    private fun requestToolbarRefresh() {
        if (toolbarRefreshPosted) return
        toolbarRefreshPosted = true
        handler.postDelayed(toolbarRefresh, 160)
    }

    private fun refreshToolbarNow() {
        val tab = current ?: return
        toolbarStale = false
        updateUrlBar()
        updateProgress()
        val video = if (tab.media.isNotEmpty() && fullscreenTab == null && !tab.showsHome && !editing) View.VISIBLE else View.GONE
        Motion.pop(videoBtn, video == View.VISIBLE)
        updateToolbarExtras()
    }

    private fun updateAll() {
        val tab = current ?: return
        shownUrlText = null
        shownUrlIcon = 0
        onTabUpdated(tab)
        refreshToolbarNow()
        onTabsChanged()
    }

    override fun onTabOpenedInBackground(tab: Tab) {
        seenTabs.add(tab.id)
        bumpTabCount()
        snack("Opened in a new tab", "Switch") { TabManager.select(tab) }
    }

    override fun onTabsClosed(tabs: List<Tab>) {
        val text = when {
            tabs.size > 1 -> "${tabs.size} tabs closed"
            tabs.firstOrNull()?.incognito == true -> "Incognito tab closed"
            else -> "Tab closed"
        }
        // Many tabs at once (a group, the inactive tabs): more time to take it back.
        snack(text, "Undo", long = tabs.size > 1) { TabManager.undoClose() }
    }

    override fun releaseSession(session: GeckoSession) {
        if (gecko.session === session) gecko.releaseSession()
    }

    override fun onTabCrashed(tab: Tab) {
        if (tab === current) {
            Motion.fadeIn(crashView, fromDy = 0)
            showToolbar()
        }
    }

    override fun onPlayingChanged(tab: Tab) {
        if (tab !== current) return
        updateKeepScreenOn()
    }

    /**
     * The screen stays on only while the open tab is really playing a video: not while it is
     * paused or has ended (even in full screen), and not for sound alone.
     */
    private fun updateKeepScreenOn() {
        val tab = current
        val on = tab != null && tab.playing && (fullscreenTab === tab || tab.media.isNotEmpty() || tab.videoPlaying)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun requestAndroidPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callback(true)
            return
        }
        pendingPermission?.invoke(false)
        pendingPermission = callback
        permissionLauncher.launch(missing.toTypedArray())
    }

    override fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, callback: (List<Uri>?) -> Unit) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        pendingFiles?.invoke(null)
        pendingFiles = callback
        try {
            fileLauncher.launch(intent)
        } catch (e: Exception) {
            pendingFiles = null
            callback(null)
        }
    }

    override fun showExtensionPopup(session: GeckoSession) {
        val sheet = BottomSheetDialog(this)
        val view = GeckoView(this)
        val height = (resources.displayMetrics.heightPixels * 0.75).toInt()
        sheet.setContentView(view, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, height))
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.isDraggable = false
        view.setSession(session)
        sheet.setOnDismissListener {
            view.releaseSession()
            session.close()
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(s: GeckoSession) = sheet.dismiss()
        }
        sheet.showImmersive()
    }

    // ------------------------------------------------------------------ toolbar that hides while scrolling

    /**
     * The address bar at the top (the page moves down under it) or at the bottom (it covers the
     * bottom of the page, which the engine leaves free; the page itself never moves).
     */
    private fun applyToolbarPosition() {
        bottomBar = prefs.toolbarAtBottom
        val gravity = if (bottomBar) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
        (appBar.layoutParams as FrameLayout.LayoutParams).gravity = gravity
        appBar.layoutParams = appBar.layoutParams
        val edge = if (bottomBar) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
        for (id in intArrayOf(R.id.toolbar_divider, R.id.progress)) {
            val v = findViewById<View>(id)
            (v.layoutParams as FrameLayout.LayoutParams).gravity = edge
            v.layoutParams = v.layoutParams
        }
        // Views that fill the page area leave the toolbar's room at its end of the screen.
        for (v in listOf<View>(home, crashView, pageCover, findViewById(R.id.swipe_peek), suggestionsList)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = if (bottomBar) 0 else toolbarPx
            lp.bottomMargin = if (bottomBar) toolbarPx else 0
            v.layoutParams = lp
        }
        // With the bar below, the best suggestion is the one next to it.
        (suggestionsList.layoutManager as? LinearLayoutManager)?.reverseLayout = bottomBar
        applyToolbarShown(toolbarShown)
    }

    private fun applyToolbarShown(shown: Float) {
        toolbarShown = shown
        if (shown >= toolbarPx) hiddenBySwipe = false
        if (bottomBar) {
            appBar.translationY = toolbarPx - shown
            gestureLayer.translationY = 0f
        } else {
            appBar.translationY = shown - toolbarPx
            gestureLayer.translationY = shown
        }
        // The engine lays pages out for a fully shown toolbar (see setDynamicToolbarMaxHeight); a
        // negative clipping moves elements fixed to the bottom of the screen down by the hidden part.
        gecko.setVerticalClipping((shown - toolbarPx).roundToInt())
    }

    private fun showToolbar() = setToolbarWanted(true)

    /** The document viewer's reading mode (2.5): Swift's toolbar goes with the viewer's own bar. */
    fun setDocumentReading(tab: Tab, on: Boolean, byScrolling: Boolean) {
        if (tab !== current || isFinishing || editing) return
        // Scrolling hides it only for those who let the toolbar hide on scroll; a tap always does.
        if (on && byScrolling && !prefs.hideToolbarOnScroll) return
        if (on) hideToolbar() else showToolbar()
    }

    private fun hideToolbar() = setToolbarWanted(false)

    /**
     * The toolbar snaps in and out in a single frame. Sliding it would redraw the app's window and
     * move the page's surface on every frame of the slide, right while the page is scrolling.
     */
    private fun setToolbarWanted(show: Boolean) {
        // In full screen the toolbar stays away; leaving full screen brings it back.
        if (fullscreenTab != null) return
        val target = if (show) toolbarPx.toFloat() else 0f
        if (toolbarWanted == show && toolbarShown == target) return
        settleAnim?.cancel()
        toolbarWanted = show
        applyToolbarShown(target)
        val now = SystemClock.uptimeMillis()
        // Only a toolbar at the top moves the page, which the engine then makes up for.
        expectedShift = if (bottomBar) 0f else if (show) toolbarPx.toFloat() else -toolbarPx.toFloat()
        shiftExpectedUntil = now + 1000
        toolbarToggledAt = now
        scrollRun = 0f
        if (show && toolbarStale) refreshToolbarNow()
    }

    private fun canAutoHideToolbar(): Boolean {
        val tab = current ?: return false
        return prefs.hideToolbarOnScroll && !editing && fullscreenTab == null && !ptrRefreshing &&
            findBar.visibility != View.VISIBLE && !switcherOpen && !tab.showsHome && !tab.crashed
    }

    override fun onPageScroll(tab: Tab, scrollY: Float, byUser: Boolean) {
        if (tab !== current || fullscreenTab != null) return
        if (byUser) lastUserScrollAt = SystemClock.uptimeMillis()
        val last = lastScrollY
        lastScrollY = scrollY
        if (hiddenBySwipe && !toolbarWanted) {
            // Hidden by a swipe: the page's own shift right after doesn't bring it back; scrolling does
            // (any scroll when the bar doesn't hide while scrolling, else as usual: scrolling back up).
            if (!byUser || SystemClock.uptimeMillis() - swipeHiddenAt < 500) return
            if (!prefs.hideToolbarOnScroll) {
                if (followsFinger()) settleToolbar(true) else showToolbar()
                return
            }
        }
        if (followsFinger()) {
            // Back at the top after a fling upwards (not the engine making up for the toolbar's own move).
            if (!toolbarWanted && scrollY <= toolbarPx / 2f && last >= 0f && scrollY < last && !gestureLayer.fingerDown &&
                settleAnim == null && SystemClock.uptimeMillis() - lastToolbarMoveAt > 400
            ) {
                settleToolbar(true)
            }
            return
        }
        if (!toolbarWanted && (scrollY <= toolbarPx / 2f || !canAutoHideToolbar())) {
            // Back at the top of the page (or hiding isn't wanted now): show the toolbar.
            scrollRun = 0f
            showToolbar()
            return
        }
        if (last < 0f) return
        var dy = scrollY - last
        if (expectedShift != 0f) {
            if (SystemClock.uptimeMillis() > shiftExpectedUntil) {
                expectedShift = 0f
            } else if (dy * expectedShift > 0f && abs(dy) >= abs(expectedShift) / 2) {
                // The engine's move, plus whatever the user scrolled in the same frame.
                dy -= expectedShift
                expectedShift = 0f
            }
        }
        if (!byUser || dy == 0f || !canAutoHideToolbar()) return
        // Right after the toolbar moved, let the page settle before it may move again.
        if (SystemClock.uptimeMillis() - toolbarToggledAt < TOOLBAR_SETTLE_MS) return
        if ((dy > 0f) != (scrollRun > 0f)) scrollRun = 0f
        scrollRun += dy
        // A deliberate scroll is needed, not a nudge: about a finger's width down to hide, a bit
        // less up to bring it back. Hiding only well below the top, so the page's move can't take
        // it back to where the toolbar would show again.
        if (toolbarWanted && scrollRun > dp(64f) && scrollY > toolbarPx * 2) {
            hideToolbar()
        } else if (!toolbarWanted && scrollRun < -dp(48f)) {
            showToolbar()
        }
    }

    private fun followsFinger() = prefs.toolbarFollowsScroll

    /**
     * Chrome-like: the toolbar slides by exactly as much as the finger moves the page, and only
     * while the page itself scrolls. It is never left half-way: see [onFingerUp].
     */
    private fun onFingerMove(dy: Float) {
        if (!followsFinger() || !canAutoHideToolbar() || !gecko.touchScrollsPage()) return
        // Only once the page is really scrolling, not while the finger is still deciding.
        if (SystemClock.uptimeMillis() - lastUserScrollAt > 300) return
        val target = (toolbarShown + dy).coerceIn(0f, toolbarPx.toFloat())
        if (target == toolbarShown) return
        settleAnim?.cancel()
        settleAnim = null
        fingerDirection = if (dy < 0) -1 else 1
        moveToolbar(target)
    }

    /** The finger let go half-way: the toolbar finishes the way it was going. */
    private fun onFingerUp() {
        if (!followsFinger() || toolbarShown <= 0f || toolbarShown >= toolbarPx) return
        settleToolbar(fingerDirection > 0)
    }

    private var lastToolbarMoveAt = 0L

    private fun moveToolbar(shown: Float) {
        lastToolbarMoveAt = SystemClock.uptimeMillis()
        val wasShown = toolbarWanted
        toolbarWanted = shown > 0f
        applyToolbarShown(shown)
        if (toolbarWanted && !wasShown && toolbarStale) refreshToolbarNow()
    }

    /** A short, straight slide to fully shown or fully hidden. */
    private fun settleToolbar(show: Boolean) {
        val target = if (show) toolbarPx.toFloat() else 0f
        settleAnim?.cancel()
        if (toolbarShown == target) {
            settleAnim = null
            return
        }
        val distance = abs(target - toolbarShown) / toolbarPx
        settleAnim = ValueAnimator.ofFloat(toolbarShown, target).apply {
            duration = (140 * distance).toLong().coerceAtLeast(50)
            interpolator = LinearInterpolator()
            addUpdateListener { moveToolbar(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (settleAnim === animation) settleAnim = null
                }
            })
            start()
        }
    }

    override fun onToolbarRequest(tab: Tab, show: Boolean) {
        if (tab !== current) return
        if (show) showToolbar() else if (canAutoHideToolbar()) hideToolbar()
    }

    // ------------------------------------------------------------------ pull to refresh

    private val ptrTimeout = Runnable { finishPullRefresh() }

    private fun setupPullToRefresh() {
        gestureLayer.page = gecko
        gestureLayer.fingerListener = object : GestureFrameLayout.FingerListener {
            override fun onFingerMove(dy: Float) = this@MainActivity.onFingerMove(dy)
            override fun onFingerUp() = this@MainActivity.onFingerUp()
        }
        gestureLayer.pullListener = object : GestureFrameLayout.PullListener {
            override fun canPull(): Boolean {
                val tab = current ?: return false
                return prefs.pullToRefresh && !ptrRefreshing && fullscreenTab == null && !editing && !switcherOpen &&
                    findBar.visibility != View.VISIBLE && tab.session != null && !tab.showsHome &&
                    toolbarWanted && toolbarShown >= toolbarPx
            }

            override fun onPull(distance: Float) {
                val offset = pullOffset(distance)
                val trigger = dp(64f)
                ptrIndicator.animate().cancel()
                ptrIndicator.visibility = View.VISIBLE
                ptrIndicator.translationY = ptrHiddenY() + offset
                ptrIndicator.rotation = offset * 2.2f
                ptrIndicator.alpha = (offset / trigger).coerceIn(0.2f, 1f)
                val scale = 0.6f + 0.4f * (offset / trigger).coerceAtMost(1f)
                ptrIndicator.scaleX = scale
                ptrIndicator.scaleY = scale
                ptrIndicator.imageTintList = ColorStateList.valueOf(
                    color(if (offset >= trigger) Looks.accent.primary else R.color.text_secondary),
                )
            }

            override fun onPullEnd(distance: Float) {
                if (pullOffset(distance) >= dp(64f)) startPullRefresh() else hidePullIndicator()
            }
        }
    }

    /** The finger moves twice as far as the circle, up to a limit. */
    private fun pullOffset(distance: Float) = (distance * 0.5f).coerceAtMost(dp(110f))

    /** Circle tucked under the bottom edge of the toolbar. */
    private fun ptrHiddenY() = (if (bottomBar) 0 else toolbarPx) - dp(16f) - dp(40f)

    private fun startPullRefresh() {
        val tab = current ?: return hidePullIndicator()
        ptrRefreshing = true
        ptrStartedAt = System.currentTimeMillis()
        ptrIndicator.imageTintList = ColorStateList.valueOf(color(Looks.accent.primary))
        ptrIndicator.animate().translationY(ptrHiddenY() + dp(56f)).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()
        ptrSpin?.cancel()
        ptrSpin = ObjectAnimator.ofFloat(ptrIndicator, View.ROTATION, ptrIndicator.rotation, ptrIndicator.rotation + 360f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        handler.removeCallbacks(ptrTimeout)
        handler.postDelayed(ptrTimeout, 6000)
        shownLoading = false
        TabManager.reload(tab)
    }

    /** The reload started: let the spinner turn a little longer, then put it away. */
    private fun finishPullRefresh() {
        if (!ptrRefreshing) return
        val wait = (600 - (System.currentTimeMillis() - ptrStartedAt)).coerceAtLeast(0)
        handler.removeCallbacks(ptrTimeout)
        handler.postDelayed({
            ptrRefreshing = false
            hidePullIndicator()
        }, wait)
    }

    private fun cancelPullRefresh() {
        handler.removeCallbacks(ptrTimeout)
        ptrRefreshing = false
        ptrSpin?.cancel()
        ptrSpin = null
        ptrIndicator.animate().cancel()
        ptrIndicator.visibility = View.GONE
    }

    private fun hidePullIndicator() {
        if (ptrIndicator.visibility != View.VISIBLE) return
        ptrIndicator.animate().cancel()
        ptrIndicator.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(160).withEndAction {
            ptrSpin?.cancel()
            ptrSpin = null
            if (!ptrRefreshing) ptrIndicator.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------------ new tab animation

    private fun animateNewTab(tab: Tab) {
        bumpTabCount()
        if (tab.isHome) {
            home.animate().cancel()
            home.alpha = 0f
            home.translationY = dp(40f)
            home.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            // The page view can't be animated itself; fade a cover off it instead.
            pageCover.setBackgroundColor(color(if (tab.incognito) R.color.incognito_bg else R.color.page_bg))
            pageCover.animate().cancel()
            pageCover.alpha = 1f
            pageCover.translationY = dp(24f)
            pageCover.visibility = View.VISIBLE
            pageCover.animate().alpha(0f).translationY(0f).setStartDelay(80).setDuration(300).withEndAction {
                pageCover.visibility = View.GONE
            }.start()
        }
    }

    private fun bumpTabCount() = Motion.bump(tabsCount, 1.35f)

    // ------------------------------------------------------------------ pop-ups, redirects, external apps

    override fun onBlocked(tab: Tab, url: String, popup: Boolean) {
        if (tab !== current || isFinishing) return
        val now = System.currentTimeMillis()
        lastBlocked?.let { (u, t) -> if (u == url && now - t < 1500) return }
        lastBlocked = url to now
        val host = UrlUtils.host(url) ?: url
        val text = if (popup) "Blocked a pop-up to $host" else "Blocked a redirect to $host"
        snack(text, "Open") {
            if (popup) TabManager.newTab(url, parent = tab) else TabManager.load(tab, url)
        }
    }

    override fun onExternalApp(tab: Tab, url: String) {
        if (tab !== current) return
        val intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (e: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val resolved = packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && UrlUtils.isHttp(fallback)) TabManager.load(tab, fallback)
            else snack("No app on this phone can open this link")
            return
        }
        val app = resolved.loadLabel(packageManager)
        MaterialAlertDialogBuilder(this)
            .setTitle("Open in $app?")
            .setMessage("This page wants to open another app.")
            .setPositiveButton("Open") { _, _ ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    snack("Couldn't open the app")
                }
            }
            .setNegativeButton("Stay here", null)
            .showImmersive()
    }

    // ------------------------------------------------------------------ downloads

    override fun onExternalResponse(tab: Tab, response: WebResponse) {
        val disposition = response.headers["Content-Disposition"]
        val mime = response.headers["Content-Type"]?.substringBefore(';')?.trim()
        val name = UrlUtils.fileName(response.uri, disposition, mime)
        val size = response.headers["Content-Length"]?.toLongOrNull() ?: -1
        val start = {
            withStorage {
                Downloads.startFromResponse(tab.profileId, response, name, mime, tab.url, tab.incognito)
                snack("Downloading $name", "View") { openDownloads() }
            }
        }
        // 2.4: documents can be opened in Swift's viewer (once downloaded), or online for an exact view.
        val kind = Docs.kindOf(name, mime)
        if (kind != null) {
            documentPrompt(tab, response, name, mime, size, kind, start)
            return
        }
        if (!prefs.confirmDownloads) {
            start()
            closeIfOnlyDownload(tab, response.uri)
            return
        }
        var started = false
        MaterialAlertDialogBuilder(this)
            .setTitle("Download file?")
            .setMessage(name + if (size > 0) "\n${UrlUtils.formatBytes(size)}" else "")
            .setPositiveButton("Download") { _, _ ->
                started = true
                start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (!started) {
                    try {
                        response.body?.close()
                    } catch (_: Exception) {
                    }
                }
                closeIfOnlyDownload(tab, response.uri)
            }
            .showImmersive()
    }

    /** A document link: Open (downloaded, then shown in Swift), Download, or Exact view online for Office files. */
    private fun documentPrompt(tab: Tab, response: WebResponse, name: String, mime: String?, size: Long, kind: Docs.Kind, download: () -> Unit) {
        var chosen = false
        val office = kind in setOf(Docs.Kind.WORD, Docs.Kind.SHEET, Docs.Kind.SLIDES, Docs.Kind.OLD_WORD, Docs.Kind.OLD_SLIDES)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("Open this document?")
            .setMessage(name + "\n" + listOfNotNull(kind.label, size.takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) }).joinToString(" · ") +
                "\n\nSwift shows it here. Nothing is kept unless you choose Save a copy or Download.")
            .setPositiveButton("Open") { _, _ ->
                chosen = true
                // 2.5: read into Swift's cache, not Downloads; the viewer's Save a copy keeps it.
                snack("Opening $name…")
                Docs.openFromWeb(this, tab, response, name, mime) { ok -> if (!ok && !isFinishing) snack("Couldn't open $name") }
            }
            .setNegativeButton("Download") { _, _ ->
                chosen = true
                download()
            }
        if (office && UrlUtils.isHttp(response.uri)) {
            // Microsoft's viewer shows the file exactly; it gets the link (only for files on the web).
            b.setNeutralButton("Exact view") { _, _ ->
                TabManager.newTab(Docs.EXACT_VIEW + Uri.encode(response.uri), parent = tab.takeIf { it in TabManager.tabs }, incognito = tab.incognito)
            }
        }
        b.setOnDismissListener {
            if (!chosen) {
                try {
                    response.body?.close()
                } catch (_: Exception) {
                }
            }
            closeIfOnlyDownload(tab, response.uri)
        }.showImmersive()
    }

    /**
     * A tab opened just for a file (a link to a download, a file opened from another app) shows
     * nothing; it closes, so it doesn't ask to download again every time Swift starts.
     */
    private fun closeIfOnlyDownload(tab: Tab, fileUrl: String) {
        if (tab.canGoBack || tab !in TabManager.tabs) return
        if (tab.url != fileUrl && !tab.isBlank) return
        L.d("Closing a tab that only led to a download")
        TabManager.close(tab)
    }

    /** Asks for storage access once; downloads go to the app's own folder if refused. */
    fun withStorage(action: () -> Unit) {
        if (Downloads.hasStoragePermission(this)) {
            action()
        } else {
            requestAndroidPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { action() }
        }
    }

    /** Downloads a link or media file found on the page. */
    fun downloadUrl(tab: Tab, url: String, nameHint: String? = null) {
        if (url.startsWith("blob:") || url.startsWith("mediasource:")) {
            snack("This video is streamed in pieces. Use the download button in the address bar.")
            return
        }
        if (url.startsWith("data:") || !UrlUtils.isHttp(url)) {
            snack("This link can't be downloaded")
            return
        }
        Helper.lookupHeaders(url) { found ->
            val headers = HashMap(found)
            if (!headers.containsKey("referer") && UrlUtils.isHttp(tab.url)) headers["referer"] = tab.url
            if (url.contains(".m3u8", ignoreCase = true)) {
                Sheets.downloadHls(this, tab, url, headers)
                return@lookupHeaders
            }
            withStorage {
                val name = nameHint ?: UrlUtils.fileName(url, null, null)
                Downloads.startUrl(tab.profileId, url, name, UrlUtils.mimeFromName(name), headers, tab.url, tab.incognito)
                snack("Downloading $name", "View") { openDownloads() }
            }
        }
    }

    fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    /** Documents (2.4): what was opened in Swift and the phone's documents. */
    fun openDocuments() {
        quickMenu.close()
        startActivity(Intent(this, DocumentsActivity::class.java))
    }

    fun openList(cls: Class<out Activity>) {
        openUrlLauncher.launch(Intent(this, cls))
    }

    // ------------------------------------------------------------------ context menu

    override fun onContextMenu(tab: Tab, element: GeckoSession.ContentDelegate.ContextElement) {
        // On a full-screen video a held finger means double speed, not this menu.
        if (fullscreenTab != null && prefs.videoTaps) return
        val actions = ArrayList<Sheets.Action>()
        val link = element.linkUri
        val src = element.srcUri
        val isImage = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
        val isMedia = element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ||
            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO
        val mediaWord = if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO) "sound" else "video"
        if (link != null) {
            // The main things to do with a link are the big buttons; a picture inside the link
            // gets its own rows below.
            actions += Sheets.Action(R.drawable.ic_open_new, "New tab", primary = true) { TabManager.newTab(link, parent = tab) }
            actions += Sheets.Action(R.drawable.ic_tab, "Background", primary = true) { TabManager.newTab(link, parent = tab, select = false) }
            if (!tab.incognito) actions += Sheets.Action(R.drawable.ic_incognito, "Incognito", primary = true) { TabManager.newTab(link, parent = tab, incognito = true) }
            actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(link) }
            actions += Sheets.Action(R.drawable.ic_copy, "Copy link") { copy(link) }
            element.textContent?.trim()?.takeIf { it.isNotEmpty() && it != link }?.let { text ->
                actions += Sheets.Action(R.drawable.ic_text_size, "Copy link text") { copy(text) }
            }
            actions += Sheets.Action(R.drawable.ic_download, "Download link") { downloadUrl(tab, link) }
        }
        if (src != null) {
            val main = link == null
            when {
                isImage -> {
                    actions += Sheets.Action(R.drawable.ic_image, if (main) "Open" else "Open picture", primary = main) { TabManager.newTab(src, parent = tab) }
                    actions += Sheets.Action(R.drawable.ic_download, if (main) "Save" else "Save picture", primary = main) { downloadUrl(tab, src) }
                    if (main && UrlUtils.isHttp(src)) actions += Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(src) }
                    actions += Sheets.Action(R.drawable.ic_link, if (main) "Copy" else "Copy picture link", primary = main) { copy(src) }
                }
                isMedia -> {
                    actions += Sheets.Action(R.drawable.ic_download, if (main) "Save" else "Save $mediaWord", primary = main) { downloadUrl(tab, src) }
                    if (UrlUtils.isHttp(src)) actions += Sheets.Action(R.drawable.ic_movie, if (main) "Open" else "Open $mediaWord", primary = main) { TabManager.newTab(src, parent = tab) }
                    actions += Sheets.Action(R.drawable.ic_link, if (main) "Copy" else "Copy $mediaWord link", primary = main) { copy(src) }
                }
            }
        }
        if (actions.isEmpty()) return
        Haptics.hold(gecko)
        // What it is (the link's words, a picture's description), then its address.
        val title = (element.textContent ?: element.title ?: element.altText)?.trim()?.replace(Regex("\\s+"), " ")?.take(140)
            ?.takeIf { it.isNotEmpty() && it != link }
            ?: when (element.type) {
                GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> "Picture"
                GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> "Video"
                GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> "Sound"
                else -> null
            }
        val target = link ?: src
        Sheets.actions(
            this,
            title,
            target?.let { UrlUtils.siteName(it) },
            actions,
            icon = if (isImage && src != null) null else target?.let { com.swiftbrowser.core.Favicons.cached(it) },
            fullAddress = target?.takeIf { !it.startsWith("data:") },
            preview = src.takeIf { isImage },
        )
    }

    fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        snack("Copied")
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ full-screen video

    override fun onFullScreen(tab: Tab, fullScreen: Boolean) {
        if (tab !== current) return
        if (fullScreen) enterFullscreen(tab) else exitFullscreen()
    }

    private fun enterFullscreen(tab: Tab) {
        quickMenu.close()
        stopEditing()
        cancelPullRefresh()
        applyToolbarShown(0f)
        fullscreenTab = tab
        appBar.visibility = View.GONE
        // The video gets the whole screen: no room is kept for the toolbar, as in Firefox. Without
        // this the page stays laid out below a toolbar-high empty strip.
        gecko.setDynamicToolbarMaxHeight(0)
        gecko.setVerticalClipping(0)
        findBar.visibility = View.GONE
        videoBtn.visibility = View.GONE
        hideSystemBars()
        if (prefs.autoLandscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        gestureLayer.setVideoMode(true, swipes = prefs.videoGestures, taps = prefs.videoTaps)
        updateKeepScreenOn()
    }

    private fun exitFullscreen() {
        if (fullscreenTab == null) return
        fullscreenTab = null
        appBar.visibility = View.VISIBLE
        gecko.setDynamicToolbarMaxHeight(toolbarPx)
        toolbarWanted = false
        showToolbar()
        applySystemBars()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        gestureLayer.setVideoMode(false, swipes = false, taps = false)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        hud.visibility = View.GONE
        updateKeepScreenOn()
        updateAll()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Full screen browsing (menu or Settings): Android's status and navigation bars are hidden; a
     * swipe in from the top or bottom edge shows them for a moment over the page.
     */
    fun applySystemBars() {
        // A full-screen video keeps them hidden either way.
        if (fullscreenTab != null) return
        if (prefs.fullScreen) hideSystemBars()
        else WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs (e.g. playback speed) bring the system bars back on Android 7; hide them again.
        // Also follows a change made in Settings.
        if (!hasFocus) return
        if (fullscreenTab != null) hideSystemBars() else applySystemBars()
    }

    override fun onVideoFullscreen(tab: Tab, width: Long, height: Long) {
        // Portrait videos (e.g. phone recordings) stay upright.
        if (fullscreenTab === tab && prefs.autoLandscape && height > width && width > 0) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private val hideHud = Runnable {
        hud.animate().cancel()
        hud.animate().alpha(0f).setDuration(150).withEndAction { hud.visibility = View.GONE }.start()
    }

    private fun setupGestures() {
        gestureLayer.listener = object : GestureFrameLayout.Listener {
            override fun onSwipe(leftSide: Boolean, delta: Float) {
                if (leftSide) changeBrightness(delta) else changeVolume(delta)
            }

            override fun onSwipeEnd() {
                handler.removeCallbacks(hideHud)
                handler.postDelayed(hideHud, 700)
            }

            override fun onDoubleTap(leftSide: Boolean) {
                val tab = fullscreenTab ?: return
                val step = if (leftSide) -10 else 10
                val now = SystemClock.uptimeMillis()
                // Taps in a row add up, as in YouTube: +10, +20, +30 s...
                skipRun = if (now - lastSkipAt < 1200 && (skipRun > 0) == (step > 0)) skipRun + step else step
                lastSkipAt = now
                Helper.seek(tab, step.toDouble())
                showHud(if (leftSide) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward, "${if (skipRun > 0) "+" else "−"}${abs(skipRun)} s")
                handler.postDelayed(hideHud, 700)
            }

            override fun onHold(start: Boolean) {
                val tab = fullscreenTab
                if (tab != null) Helper.holdSpeed(tab, start)
                if (start && tab != null) {
                    showHud(R.drawable.ic_fast_forward, "2×")
                } else {
                    handler.removeCallbacks(hideHud)
                    hud.visibility = View.GONE
                }
            }
        }
    }

    private var skipRun = 0
    private var lastSkipAt = 0L

    fun formatSpeed(rate: Double): String =
        (if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString().trimEnd('0')) + "×"

    private fun tinted(res: Int) = ContextCompat.getDrawable(this, res)?.mutate()?.also { it.setTint(0xFFFFFFFF.toInt()) }

    private fun changeBrightness(delta: Float) {
        val lp = window.attributes
        var b = lp.screenBrightness
        if (b < 0) b = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        b = (b + delta * 1.2f).coerceIn(0.02f, 1f)
        lp.screenBrightness = b
        window.attributes = lp
        showHud(R.drawable.ic_brightness, "${(b * 100).toInt()}%")
    }

    private fun changeVolume(delta: Float) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeAccumulator += delta * max * 1.2f
        var cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val steps = volumeAccumulator.toInt()
        if (steps != 0) {
            cur = (cur + steps).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, cur, 0)
            volumeAccumulator -= steps
        }
        showHud(R.drawable.ic_volume, "${if (max > 0) cur * 100 / max else 0}%")
    }

    private fun showHud(icon: Int, text: String) {
        hud.text = text
        hud.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(icon), null, null, null)
        if (hud.visibility != View.VISIBLE || hud.alpha < 1f) {
            hud.animate().cancel()
            if (hud.visibility != View.VISIBLE) hud.alpha = 0f
            hud.visibility = View.VISIBLE
            hud.animate().alpha(1f).setDuration(120).start()
        }
        handler.removeCallbacks(hideHud)
    }

    // ------------------------------------------------------------------ find in page

    private fun setupFindBar() {
        findEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                find(s?.toString() ?: "", backwards = false, again = false)
            }
        })
        findEdit.setOnEditorActionListener { _, _, _ ->
            find(findEdit.text.toString(), backwards = false, again = true)
            true
        }
        findViewById<View>(R.id.find_next).setOnClickListener { find(findEdit.text.toString(), false, true) }
        findViewById<View>(R.id.find_prev).setOnClickListener { find(findEdit.text.toString(), true, true) }
        findViewById<View>(R.id.find_close).setOnClickListener { closeFind() }
    }

    fun openFind() {
        if (current?.session == null) return
        selectionMenu.dismiss()
        stopEditing()
        showToolbar()
        Motion.fadeIn(findBar, fromDy = 0, duration = Motion.SHORT)
        findEdit.setText("")
        findCount.text = ""
        findEdit.requestFocus()
        showKeyboard(findEdit)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        Motion.fadeOut(findBar, toDy = 0, duration = 120)
        current?.session?.finder?.clear()
        hideKeyboard()
    }

    private fun find(text: String, backwards: Boolean, again: Boolean) {
        val finder = current?.session?.finder ?: return
        if (text.isEmpty()) {
            finder.clear()
            findCount.text = ""
            return
        }
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
        finder.find(if (again) null else text, flags).accept({ r ->
            if (r != null) findCount.text = if (r.total > 0) "${r.current}/${r.total}" else "0/0"
        }, { _ -> })
    }

    // ------------------------------------------------------------------ back button

    private fun handleBack() {
        val tab = current
        when {
            quickMenu.isOpen -> quickMenu.onBack()
            switcherOpen -> if (!switcher.back()) switcher.close(pageRect())
            fullscreenTab != null -> tab?.session?.exitFullScreen() ?: exitFullscreen()
            findBar.visibility == View.VISIBLE -> closeFind()
            editing -> stopEditing()
            tab == null -> finish()
            tab.homeShown -> leaveHome(tab)
            tab.canGoBack && tab.session != null -> TabManager.goBack(tab)
            tab.leftHome && !tab.isHome -> TabManager.returnHome(tab)
            tab.parentId != null && TabManager.tabs.any { it.id == tab.parentId } -> TabManager.close(tab)
            // A document opened from Documents or Downloads returns there; one another app sent
            // closes and Android goes back to that app (2.6).
            tab.backTo != null -> {
                val to = tab.backTo
                TabManager.close(tab)
                when (to) {
                    Docs.BACK_DOCUMENTS -> openDocuments()
                    Docs.BACK_DOWNLOADS -> openDownloads()
                    // 2.7: back to the folder it was opened from.
                    Docs.BACK_FILES -> FilesActivity.open(this)
                    else -> moveTaskToBack(true)
                }
            }
            else -> moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------------ start-up screen

    /** Hides the start-up screen once the ad blocker is ready. */
    private val adListener: () -> Unit = { handler.post { if (startupDone()) hideStartup() } }

    private val hideStartupRunnable = Runnable { hideStartup() }

    /**
     * When the app starts, the open page waits for the engine and the ad blocker, so that no ads
     * slip through before its filters are loaded. Meanwhile the logo and a spinner are shown instead
     * of an empty page. The home page needs neither, so it shows straight away.
     */
    private fun showStartupUntilReady() {
        val tab = current
        if (tab == null || tab.isHome || startupDone()) return
        startup.visibility = View.VISIBLE
        findViewById<android.widget.ImageView>(R.id.startup_logo)?.setImageDrawable(Ui.logo(this))
        // In the accent chosen (2.6), your own included.
        findViewById<android.widget.ProgressBar>(R.id.startup_spinner)?.indeterminateTintList = ColorStateList.valueOf(Ui.accent(this))
        // The tab's last picture, if there is one, instead of the logo: the page seems to be back
        // at once, with a small note at the bottom until it really loads.
        Thumbnails.load(tab) { bmp ->
            if (bmp != null && startup.visibility == View.VISIBLE && startup.alpha == 1f) showStartupPicture(bmp)
        }
        AdBlock.addListener(adListener)
        // Pages stop waiting after this long too (see TabManager), so the screen does as well.
        handler.postDelayed(hideStartupRunnable, 8000)
    }

    private fun showStartupPicture(bmp: android.graphics.Bitmap) {
        val box = startup as FrameLayout
        for (i in 0 until box.childCount) box.getChildAt(i).visibility = View.GONE
        box.addView(TopCropImageView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageBitmap(bmp)
            alpha = 0.85f
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        box.addView(TextView(this).apply {
            text = "Loading… the ad blocker is starting"
            textSize = 13f
            setTextColor(color(Looks.accent.onPrimary))
            val pad = dp(14f).toInt()
            setPadding(pad, dp(8f).toInt(), pad, dp(8f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18f)
                setColor(color(Looks.accent.primary))
            }
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(24f).toInt() })
        // Below the toolbar, which stays usable meanwhile.
        (startup.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = if (bottomBar) 0 else toolbarPx
            bottomMargin = if (bottomBar) toolbarPx else 0
        }
        startup.elevation = dp(4f)
        startup.requestLayout()
    }

    private fun startupDone() = AdBlock.ready || AdBlock.global == AdLevel.OFF

    private fun hideStartup() {
        AdBlock.removeListener(adListener)
        handler.removeCallbacks(hideStartupRunnable)
        if (startup.visibility != View.VISIBLE) return
        startup.animate().alpha(0f).setDuration(200).withEndAction { startup.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ swipe the toolbar to switch tabs

    private fun setupTabSwipe() {
        val host = object : TabSwipe.Host {
            override fun canSwipeTabs(rawX: Float, rawY: Float): Boolean {
                val tab = current ?: return false
                if (editing || fullscreenTab != null || switcherOpen || quickMenu.isOpen) return false
                if (startup.visibility == View.VISIBLE || findBar.visibility == View.VISIBLE) return false
                if (!toolbarWanted || toolbarShown < toolbarPx || TabManager.swipeOrder(tab).size < 2) return false
                val at = IntArray(2)
                appBar.getLocationOnScreen(at)
                return rawX >= at[0] && rawX < at[0] + appBar.width && rawY >= at[1] && rawY < at[1] + appBar.height
            }

            override fun neighbourTab(next: Boolean): Tab? {
                val tab = current ?: return null
                val order = TabManager.swipeOrder(tab)
                val i = order.indexOf(tab)
                return if (i < 0) null else order.getOrNull(if (next) i + 1 else i - 1)
            }

            override fun switchToTab(tab: Tab) {
                if (tab !== current && tab in TabManager.tabs) TabManager.select(tab)
            }

            override fun backgroundOf(tab: Tab): Int =
                color(if (tab.incognito) R.color.incognito_bg else if (tab.isHome) R.color.home_bg else R.color.page_bg)
        }
        tabSwipe = TabSwipe(host, findViewById(R.id.swipe_peek), findViewById(R.id.swipe_peek_image), findViewById(R.id.swipe_peek_title))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!built) return super.dispatchTouchEvent(ev)
        // An address bar swipe takes the rest of its gesture.
        if (barSwipeTaken) {
            // Taken early on the bar (see barSwipe): it still hides the bar once long enough.
            barSwipe(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) barSwipeTaken = false
            return true
        }
        if (barSwipe(ev)) {
            barSwipeTaken = true
            // The page, the address bar and a tab swipe that may have started let go of the touch.
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            tabSwipe.onTouchEvent(cancel)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return when (tabSwipe.onTouchEvent(ev)) {
            TabSwipe.Result.PASS -> super.dispatchTouchEvent(ev)
            TabSwipe.Result.STARTED -> {
                // The address bar and buttons under the finger let go of the touch.
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                true
            }
            TabSwipe.Result.CONSUMED -> true
        }
    }

    // ------------------------------------------------------------------ address bar swipes

    /** 1: a swipe on the bar may hide it; 2: a swipe in from the screen's edge may show it. */
    private var barSwipeMode = 0
    private var barSwipeTaken = false
    private var barSwipeX = 0f
    private var barSwipeY = 0f

    /** The bar was hidden by a swipe (only scrolling, or a swipe from the edge, brings it back). */
    private var hiddenBySwipe = false
    private var swipeHiddenAt = 0L

    /**
     * Settings → Look & screen → Address bar swipes: swiping up on the address bar hides it (down
     * when it is at the bottom), only while it shows; swiping down from the top edge of the screen
     * (up from the bottom edge) shows it again, only while it is hidden. Sideways swipes stay tab
     * swipes. True when [ev] completes such a swipe.
     */
    private fun barSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                barSwipeMode = 0
                val tab = current ?: return false
                val mode = prefs.barSwipes
                if (mode == 0 || editing || fullscreenTab != null || switcherOpen || quickMenu.isOpen || tab.showsHome ||
                    startup.visibility == View.VISIBLE || findBar.visibility == View.VISIBLE
                ) {
                    return false
                }
                barSwipeX = ev.rawX
                barSwipeY = ev.rawY
                barSwipeMode = when {
                    mode and Prefs.SWIPE_HIDE != 0 && toolbarWanted && toolbarShown >= toolbarPx && within(appBar, ev) -> 1
                    mode and Prefs.SWIPE_SHOW != 0 && toolbarShown <= 0f && atScreenEdge(ev) -> 2
                    else -> 0
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (barSwipeMode == 0) return false
                val dx = ev.rawX - barSwipeX
                val dy = ev.rawY - barSwipeY
                // Hiding goes away from the bar's edge of the screen, showing towards the page.
                val along = when (barSwipeMode) {
                    1 -> if (bottomBar) dy else -dy
                    else -> if (bottomBar) -dy else dy
                }
                val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                if ((abs(dx) > slop && abs(dx) > abs(dy)) || along < -slop) {
                    // Sideways (a tab swipe) or the other way: not an address bar swipe.
                    barSwipeMode = 0
                    return false
                }
                if (along > dp(28f) && abs(dy) > abs(dx) * 1.5f) {
                    val hide = barSwipeMode == 1
                    barSwipeMode = 0
                    hiddenBySwipe = hide
                    if (hide) swipeHiddenAt = SystemClock.uptimeMillis()
                    if (followsFinger()) settleToolbar(!hide) else setToolbarWanted(!hide)
                    Haptics.tick(appBar)
                    return true
                }
                // On the bar, a clear move up is already the swipe: the address lets go of the touch, so
                // a slow swipe doesn't also open its long-press menu.
                if (barSwipeMode == 1 && !barSwipeTaken && along > slop && abs(dy) > abs(dx) * 1.5f) return true
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> barSwipeMode = 0
        }
        return false
    }

    private fun within(v: View, ev: MotionEvent): Boolean {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return ev.rawX >= at[0] && ev.rawX < at[0] + v.width && ev.rawY >= at[1] && ev.rawY < at[1] + v.height
    }

    /** A thin strip at the page's top edge (bottom edge when the address bar is at the bottom). */
    private fun atScreenEdge(ev: MotionEvent): Boolean {
        val page = findViewById<View>(R.id.content)
        val at = IntArray(2)
        page.getLocationOnScreen(at)
        val strip = dp(32f)
        return if (bottomBar) ev.rawY > at[1] + page.height - strip && ev.rawY <= at[1] + page.height
        else ev.rawY >= at[1] && ev.rawY < at[1] + strip
    }

    override fun onPageDrawn(tab: Tab, contentful: Boolean) {
        if (tab === current) tabSwipe.onPageDrawn(tab, contentful)
    }

    // ------------------------------------------------------------------ updates

    fun offerUpdate(r: Updates.Release) {
        snack("Swift ${r.version} is available", "Update") { confirmUpdate(r) }
    }

    /** What's new, then the download; Android's installer opens when it is done. */
    fun confirmUpdate(r: Updates.Release) {
        val size = if (r.size > 0) " (${UrlUtils.formatBytes(r.size)})" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Update to Swift ${r.version}?")
            .setMessage(r.notes.ifBlank { "A new version of Swift is available." }.take(1500) +
                "\n\nThe update$size downloads now; then Android asks to install it. Tabs and settings are kept.")
            .setPositiveButton("Download") { _, _ ->
                withStorage {
                    val item = Downloads.startUrl(Profiles.currentId, r.apkUrl, r.apkName, "application/vnd.android.package-archive", emptyMap(), null)
                    Updates.downloadId = item.id
                    updateVersion = r.version
                    updateBarHidden = false
                    showUpdateProgress()
                }
            }
            .setNegativeButton("Later", null)
            .showImmersive()
    }

    private var updateVersion = ""
    private var updateBar: Messages.Handle? = null
    /** "Hide" was tapped: the progress doesn't come back until the download ends. */
    private var updateBarHidden = false

    /** While Swift's own update downloads: how far it got, at the bottom of the screen. */
    private fun showUpdateProgress() {
        val item = Updates.downloadId.takeIf { it >= 0 }?.let { Downloads.get(it) }
        if (item == null || item.status == Downloads.DONE || item.status == Downloads.FAILED) {
            updateBar?.dismiss()
            updateBar = null
            if (item?.status == Downloads.FAILED) snack("The update didn't download: ${item.error ?: "no connection"}", "Downloads") { openDownloads() }
            return
        }
        val pct = item.percent
        val text = "Downloading Swift $updateVersion… " + if (pct >= 0) "$pct%" else UrlUtils.formatBytes(item.done)
        val fraction = if (pct >= 0) pct / 100f else null
        val bar = updateBar
        if (bar != null && bar.isShowing) {
            bar.setText(text)
            bar.setProgress(fraction)
        } else if (!updateBarHidden) {
            updateBar = Messages.sticky(this, text, "Hide", messageOffset()) { updateBarHidden = true }
            updateBar?.setProgress(fraction)
        }
    }

    private val updateDownloadListener: () -> Unit = {
        if (Updates.downloadId >= 0 && updateBar != null) showUpdateProgress()
        val id = Updates.downloadId
        val item = if (id >= 0) Downloads.get(id) else null
        if (item != null && item.status == Downloads.DONE) {
            Updates.downloadId = -1
            updateBar?.dismiss()
            updateBar = null
            DownloadsActivity.openFileIntent(this, item)?.let { intent ->
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    snack("Open the downloaded update from Downloads to install it")
                }
            }
        }
    }

    // ------------------------------------------------------------------ swipe from the edges

    private lateinit var edgeArrow: ImageView

    /** Swiping in from the left edge of a page goes back, from the right edge forward. */
    private fun setupEdgeSwipe() {
        val size = dp(44f).toInt()
        edgeArrow = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_ptr)
            Palette.paint(this)
            elevation = dp(4f)
            val pad = dp(10f).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(edgeArrow, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START))
        gestureLayer.edgeListener = object : GestureFrameLayout.EdgeListener {
            override fun canEdgeSwipe(fromLeft: Boolean): Boolean {
                val tab = current ?: return false
                return prefs.edgeSwipe && fullscreenTab == null && !editing && !tab.showsHome && tab.session != null &&
                    (if (fromLeft) tab.canGoBack else tab.canGoForward)
            }

            override fun onEdgeSwipe(fromLeft: Boolean, distance: Float) {
                val trigger = dp(96f)
                val progress = (distance / trigger).coerceIn(0f, 1f)
                val travel = size + dp(20f)
                edgeArrow.animate().cancel()
                edgeArrow.visibility = View.VISIBLE
                edgeArrow.setImageResource(if (fromLeft) R.drawable.ic_back else R.drawable.ic_forward)
                edgeArrow.translationX = if (fromLeft) -size + progress * travel else root.width - progress * travel
                edgeArrow.alpha = 0.35f + 0.65f * progress
                edgeArrow.imageTintList = ColorStateList.valueOf(color(if (progress >= 1f) Looks.accent.primary else R.color.text_secondary))
            }

            override fun onEdgeSwipeEnd(fromLeft: Boolean, distance: Float) {
                val tab = current
                if (distance >= dp(96f) && tab != null) {
                    if (fromLeft) TabManager.goBack(tab) else TabManager.goForward(tab)
                }
                val out = if (fromLeft) -size.toFloat() else root.width.toFloat()
                edgeArrow.animate().translationX(out).alpha(0f).setDuration(160).withEndAction {
                    edgeArrow.visibility = View.GONE
                }.start()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    fun snack(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        Messages.show(this, text, action.takeIf { onAction != null }, long = long, bottomOffset = messageOffset(), onAction = onAction)
    }

    /** Messages sit above the address bar when it is at the bottom and showing. */
    private fun messageOffset(): Int =
        if (bottomBar && appBar.visibility == View.VISIBLE) toolbarShown.roundToInt() else 0

    private fun showKeyboard(view: View) {
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun color(res: Int) = Palette.color(this, res)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun showCrashReportIfAny() {
        val report = Crash.pending(this) ?: return
        val text = report.take(3500)
        val view = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Swift closed unexpectedly last time")
            .setView(scroll)
            .setPositiveButton("Copy report") { _, _ ->
                copy(report)
                Crash.clear(this)
            }
            .setNegativeButton("Dismiss") { _, _ -> Crash.clear(this) }
            .showImmersive()
        L.d("Showed crash report")
    }

    /** The previous run ended badly (not by a crash in the app's own code, see above). */
    private fun showProblemIfAny() {
        val problem = Diagnostics.problem ?: return
        val detail = Diagnostics.problemDetail
        Diagnostics.clearProblem()
        val (title, text) = when (problem) {
            Diagnostics.Problem.CLOSED_BY_ANDROID -> "Swift was closed while you used it" to
                "Android closed Swift while it was on screen. On a 2 GB phone that almost always means the phone ran out of memory " +
                "(very heavy pages such as Google's AI answers can do that).\n\nTo make it less likely: keep fewer tabs in memory " +
                "(Settings → Speed & memory), close tabs you don't need, and close other apps.\n\nIf it keeps happening, send a report."
            Diagnostics.Problem.ENGINE_CRASH -> "Swift's engine crashed" to
                "The Firefox engine inside Swift crashed last time. " + (Diagnostics.plainReason(detail)?.let { "$it\n\n" } ?: "\n\n") +
                (detail?.let { "Details: $it\n\n" } ?: "") + "Sending a report (with the last 3 days' logs) helps find out why."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Send report") { _, _ -> shareProblemReport() }
            .showImmersive()
    }

    /** The problem report with this run's settings; see Diagnostics. */
    fun shareProblemReport() = Diagnostics.shareReport(this)

    /** For the video sheet: picks a quality of an HLS stream, then downloads it. */
    fun startHlsDownload(tab: Tab, playlistUrl: String, headers: Map<String, String>, quality: String?) {
        withStorage {
            val base = tab.title.ifBlank { UrlUtils.host(tab.url) ?: "video" }
            val title = if (quality != null) "$base $quality" else base
            Downloads.startHls(tab.profileId, playlistUrl, title, headers, tab.url, tab.incognito)
            snack("Downloading video", "View") { openDownloads() }
        }
    }

    companion object {
        private const val TOOLBAR_SETTLE_MS = 300L

        /** "example.com/page": an address without its https:// (made once, 2.6). */
        private val BARE_ADDRESS = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/\\S*)?$", RegexOption.IGNORE_CASE)

        /** Address bar look-ups, one at a time (2.6): what fast typing replaced is skipped, not piled up. */
        private val suggestThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "suggest")
        }.asCoroutineDispatcher()
        private const val STATE_MENU_OPEN = "menu_open"

        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"

        /** Opens this tab (from the playback notification). */
        const val EXTRA_TAB_ID = "tab_id"

        /** Opens this file in a new tab (a page saved as PDF, from Downloads). */
        const val EXTRA_OPEN_FILE = "open_file"

        /** From Settings → Look & screen: open the menu ready to arrange (2.2). */
        const val EXTRA_ARRANGE_MENU = "arrange_menu"

        /** App icon shortcuts (res/xml/shortcuts.xml, 2.3). */
        const val ACTION_NEW_TAB = "com.swiftbrowser.action.NEW_TAB"
        const val ACTION_INCOGNITO = "com.swiftbrowser.action.INCOGNITO"
        const val ACTION_SCAN = "com.swiftbrowser.action.SCAN"

        /** The home screen widget's search bar and the New tab tile (2.6). */
        const val ACTION_SEARCH = "com.swiftbrowser.action.SEARCH"

        /** Hold Swift's icon, or the widget: Documents and Files (2.7). */
        const val ACTION_DOCUMENTS = "com.swiftbrowser.action.DOCUMENTS"
        const val ACTION_FILES = "com.swiftbrowser.action.FILES"

        /** From the VPN quick settings tile, when a location or Android's permission is needed. */
        const val ACTION_VPN = "com.swiftbrowser.action.VPN"
        private val SHORTCUTS = setOf(ACTION_NEW_TAB, ACTION_INCOGNITO, ACTION_SCAN, ACTION_VPN, ACTION_SEARCH, ACTION_DOCUMENTS, ACTION_FILES)

        /** The square outline around the tab count, in [color]. */
        fun tabCountBackground(ctx: Context, color: Int) = android.graphics.drawable.GradientDrawable().apply {
            val density = ctx.resources.displayMetrics.density
            setStroke((2 * density).roundToInt(), color)
            cornerRadius = 4 * density
        }
    }
}
