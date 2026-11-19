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
import com.swiftbrowser.tabs.TabRules
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

    /** The bar over selected text (2.6). 3.0: let go of in onDestroy (the pages keep it otherwise, and this screen with it). */
    private val selectionMenuLazy = lazy { SelectionMenu(this) }
    private val selectionMenu by selectionMenuLazy
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
    private lateinit var ptrIndicator: LoadingIndicator
    private lateinit var hud: TextView
    private lateinit var findBar: View

    /** 3.0.12: the frame around [findBar] that rides with the keyboard ([applyBottomShift]). */
    private lateinit var findBarBox: View
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
    private lateinit var tabSwipe: TabSwipe

    /** The ⋮ menu; built once, when the app is idle after starting. */
    private val quickMenuLazy = lazy { QuickMenu(this, root) }
    private val quickMenu by quickMenuLazy

    /** The menu is made and open (asked without making it). */
    private val menuOpen: Boolean get() = quickMenuLazy.isInitialized() && quickMenu.isOpen

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

    /**
     * 3.0.4: the strip of the open tab's group by the address bar (GroupStrip), made the first time
     * it's needed. While the tab is in a group and has a page, the strip's height is part of the bar
     * ([barPx]): the engine keeps room for both, and both hide on scroll.
     */
    private var groupStrip: GroupStrip? = null
    private var stripPx = 0

    /** The strip's height the open tab wants; it becomes [stripPx] as that tab's page goes on screen. */
    private var stripWanted = 0

    /** The address bar and, while there is one, the group strip: the engine's dynamic toolbar. */
    private val barPx: Int get() = toolbarPx + stripPx
    private val toolbarDivider: View by lazy { findViewById(R.id.toolbar_divider) }

    /** Which way the finger last moved the toolbar: -1 away, +1 back. */
    private var fingerDirection = 0
    private var toolbarShown = 0f

    /** 3.0.12: how far from its place the bottom of the screen is still drawn while the keyboard comes or goes. */
    private var bottomShift = 0f

    /** 3.0.12: this screen was started from the launcher (its task's first intent), see [backStaysInSwift]. */
    private var launcherStart = false
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

    // Pull to refresh.
    private var ptrRefreshing = false

    /** 3.0.3: the pull, or the swipe from an edge, has gone far enough to act (a tick once, as it gets there). */
    private var pullArmed = false
    private var edgeArmed = false
    private var ptrStartedAt = 0L

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
            // From Swift's own Files.
            FilePick.picked(data)?.let {
                cb?.invoke(it)
                return@registerForActivityResult
            }
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
            // 3.0: heard words only open web pages (see UrlUtils.toUrlOrSearch).
            if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) navigate(text, trusted = false)
        }

    private val current: Tab? get() = TabManager.current

    /** 3.0: between onStart and onStop. */
    private var visibleToUser = false

    override val onScreen: Boolean get() = visibleToUser

    override val pageOnScreen: Boolean
        get() = built && visibleToUser && !switcherOpen && current?.showsHome == false

    /** 3.0: the open tab's page is made active (drawn) only when it can be seen ([pageOnScreen]). */
    private fun activateIfSeen() {
        current?.session?.let { if (it.isOpen && pageOnScreen) it.setActive(true) }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The browser on this screen is built (2.7: just after the start screen is shown, see [build]). */
    private var built = false

    /** Swift's start screen while the browser is built behind it (2.7). */
    private var startScreen: StartScreen? = null

    /** What waits for the start screen to have gone (2.7), so its glide stays smooth. */
    private val afterStart = ArrayList<() -> Unit>()

    /**
     * 3.0.13: Swift opened on a site, without its start screen: what waits for the start screen waits
     * for the browser's first frame instead (the windows of what's new, the menu built ahead).
     */
    private var waitsForFirstFrame = false

    /**
     * 3.0.5: something asked for Swift while its start screen showed (a link, a shortcut, a
     * notification). Android restoring a closed Swift also has saved state, which alone would mean
     * "nothing new to open": this says there is.
     */
    private var intentWaits = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Swift)
        Looks.apply(this)
        appliedColors = Palette.signature
        super.onCreate(savedInstanceState)
        prefs = SwiftApp.prefs
        launcherStart = intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        if (android.os.Build.VERSION.SDK_INT >= 31) handOverStartScreen()
        // Rebuilt (a dark mode switch, a change of colours): at once, as before. 3.0.5: only in a Swift
        // that is already running. When Android closed it in the background it comes back with saved
        // state too, and that is a real start, with the start screen.
        if (savedInstanceState != null && SwiftApp.browserBuilt) {
            build(savedInstanceState)
            return
        }
        // 3.0.13: Swift opening on a site shows the browser at once, without the start screen and its
        // loader (the page waits for the ad blocker under Swift's own colour, see showStartupUntilReady).
        val opening = TabManager.tabToOpen()
        if (!StartRules.startScreen(openingLink(intent, savedInstanceState), openingNewTab(intent, savedInstanceState), tabOpening(intent, opening)?.let { !it.showsHome })) {
            waitsForFirstFrame = true
            build(savedInstanceState)
            return
        }
        // Android shows no screen of its own while Swift starts (2.7, Theme.Swift.Launch): Swift's
        // start screen is drawn first, in your colours, and the browser is built right after it is
        // on screen. 3.0.13: only when Swift opens on the home page.
        val start = StartScreen(this).also { startScreen = it }
        setContentView(start.view)
        // 3.0.5: with the home page open, nothing would start the ad blocker until a page is asked for.
        AdBlock.wakeUp()
        // The status bar (until full screen hides it) in the same colour.
        window.statusBarColor = Palette.color(this, R.color.page_bg)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        applySystemBars()
        val buildNow = Runnable { if (!isFinishing && !isDestroyed) build(savedInstanceState) }
        afterNextFrame { buildNow.run() }
        // Not drawn while the screen is off, say: built anyway.
        handler.postDelayed(buildNow, 1500)
    }

    /** 3.0.13: another app hands Swift a web address or a search at this start: it opens as a page. */
    private fun openingLink(i: Intent?, saved: Bundle?): Boolean {
        if (i == null || saved != null || i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        return when (i.action) {
            Intent.ACTION_VIEW -> i.dataString?.let { UrlUtils.isHttp(it) } == true
            Intent.ACTION_SEND -> !i.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
            Intent.ACTION_WEB_SEARCH -> !i.getStringExtra("query").isNullOrBlank()
            else -> false
        }
    }

    /** 3.0.13: a shortcut, the widget or a tile asks for a new tab or a search: the home page. */
    private fun openingNewTab(i: Intent?, saved: Bundle?): Boolean =
        i != null && saved == null && i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
            i.action in setOf(ACTION_NEW_TAB, ACTION_INCOGNITO, ACTION_SEARCH)

    /** 3.0.13: the tab this start shows: the one a notification asks for, or the one open last. */
    private fun tabOpening(i: Intent?, opening: Tab?): Tab? {
        val id = i?.getLongExtra(EXTRA_TAB_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (id == Long.MIN_VALUE) return opening
        return TabManager.tabs.firstOrNull { it.id == id } ?: opening
    }

    /**
     * 3.0.12: Android 12+ shows a start screen of its own first when Swift is opened from the home
     * screen. values-v31 makes it like Swift's (its background, and the logo in the same place and
     * size), so Swift's start screen simply takes over. From Android 13 the next start's one is also
     * in Swift's own look (light or dark), whatever the phone's. (Not handed over with a fade:
     * Android waits for Swift to take it, and Swift is busy building the browser right then, so it
     * stayed up to 2 s with nothing said, instead of Swift's "Starting the browser...".)
     */
    @androidx.annotation.RequiresApi(31)
    private fun handOverStartScreen() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            splashScreen.setSplashScreenTheme(if (prefs.darkMode) R.style.Theme_Swift_Launch_Dark else R.style.Theme_Swift_Launch_Light)
        }
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
        // 3.0.13: a real start of Swift (not this screen made again for a change of look, say).
        val realStart = !(savedInstanceState != null && SwiftApp.browserBuilt)
        setContentView(R.layout.activity_main)
        Palette.paint(findViewById<View>(android.R.id.content))
        bindViews()
        toolbarPx = resources.getDimensionPixelSize(R.dimen.toolbar_height)
        applyToolbarPosition()
        setupToolbar()
        setupFindBar()
        setupGestures()
        setupPullToRefresh()
        setupHome()
        setupTabSwipe()

        gecko.setActivityContextDelegate { this }
        // The toolbar floats over the page and slides away; the page is told how much of it is hidden.
        gecko.setDynamicToolbarMaxHeight(barPx)
        applyToolbarShown(barPx.toFloat())

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
        val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        }
        onBackPressedDispatcher.addCallback(this, back)
        // 3.0.12: when Back would only send Swift away, Android 14+ does it with its home-screen preview.
        PredictiveBack.follow(this, back) { backStaysInSwift() }

        // 2.8: documents have a screen of their own; their tabs from before go once (the documents
        // keep their place, in Documents and Continue).
        if (!prefs.sp.getBoolean(PREF_DOC_TABS_MOVED, false)) {
            prefs.sp.edit().putBoolean(PREF_DOC_TABS_MOVED, true).apply()
            TabManager.tabs.filter { Docs.isViewerUrl(it.url) }.takeIf { it.isNotEmpty() }?.let { TabManager.closeTabs(it) }
        }
        TabManager.tabs.forEach { seenTabs.add(it.id) }
        val had = TabManager.current
        val tab = TabManager.ensureCurrent()
        seenTabs.add(tab.id)
        // When it had to choose the tab, ensureCurrent told this screen already (2.6: not twice).
        if (had === tab) onTabSelected(tab)
        onTabsChanged()
        if (savedInstanceState == null || intentWaits) handleIntent(intent)
        intentWaits = false
        SwiftApp.messageAfterThemeChange?.let { m ->
            SwiftApp.messageAfterThemeChange = null
            root.post { if (!isFinishing) snack(m, long = true) }
        }
        if (SwiftApp.reloadAfterThemeChange) {
            SwiftApp.reloadAfterThemeChange = false
            // Sites that pick their colours when the page loads follow on reload. Google notices the
            // change during that load and only uses it from the next one, so it gets a second reload.
            // 3.0: never a page that plays (or played) a video, or is in full screen: it would start over.
            current?.let {
                if (!it.isHome && UrlUtils.isHttp(it.url) && !it.playing && !it.videoPlaying && !it.fullscreen) {
                    TabManager.reload(it)
                    themeReloading = true
                    if (UrlUtils.site(it.url)?.startsWith("google.") == true) themeReloadTab = it
                }
            }
        }
        // 3.0: a page that was in full screen when this screen was rebuilt (a change of colours) stays in it.
        current?.let { if (it.fullscreen && it.session?.isOpen == true) onFullScreen(it, true) }
        // Full screen first: the dialogs below keep it (see Immersive).
        applySystemBars()
        SystemBars.keepHidden(this) { fullscreenTab == null }
        showCrashReportIfAny()
        showProblemIfAny()
        showStartupUntilReady(realStart)
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
        // A newer Swift on GitHub? 3.0.9: asked at each start, a few seconds in, and offered in a window
        // (a message at the bottom was easy to miss), at each start until it is installed.
        // 3.0.11: not while Swift's VPN is still connecting (its block makes every request fail; it took
        // 5-6 s at a fresh start in a problem report): asked once the block lifts, for up to a minute.
        var updateWaits = 0
        val updateCheck = object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (com.swiftbrowser.engine.Engine.networkBlocked && updateWaits++ < 30) {
                    handler.postDelayed(this, 2_000)
                    return
                }
                Updates.checkNow(prefs, force = false) { r -> if (startWindowsDone) offerUpdate(r) else updateFound = r }
            }
        }
        handler.postDelayed(updateCheck, 4_000)
        if (savedInstanceState == null) whenStarted {
            if (isFinishing) return@whenStarted
            // 3.0.8: a Google backup Android put back (Swift installed again, a new phone) is offered
            // first; what's new waits for the next start. 3.0.9: a newer Swift only when neither shows.
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { com.swiftbrowser.core.GoogleBackup.toOffer(this@MainActivity) }
                if (isFinishing) return@launch
                val shown = if (found != null) {
                    BackupUi.offerOnStart(this@MainActivity, found)
                    true
                } else {
                    WhatsNew.showOnStart(this@MainActivity)
                }
                startWindowsDone = true
                if (shown) updateOffered = true else (updateFound ?: Updates.remembered(prefs))?.let { offerUpdate(it) }
            }
        }
        // Housekeeping once the app has settled: the weekly backup, and the cache when the phone is
        // almost full.
        handler.postDelayed({ if (!isFinishing) startupChores() }, 20_000)
        Downloads.addListener(updateDownloadListener)
        Downloads.addListener(downloadRingListener)
        showDownloadRing()
        Downloads.all().filter { VpnUi.isSetupFile(it) }.mapTo(setupFilesSeen) { it.id }
        Downloads.addListener(setupFileListener)
        Downloads.all().filter { chromeFileKind(it) != null }.mapTo(chromeFilesSeen) { it.id }
        Downloads.addListener(chromeFileListener)
        built = true
        SwiftApp.browserBuilt = true
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
        // 3.0.13: opened on a site without the start screen: what waited for it follows the first frame.
        if (waitsForFirstFrame) {
            afterNextFrame {
                waitsForFirstFrame = false
                if (isDestroyed) return@afterNextFrame
                afterStart.forEach { it() }
                afterStart.clear()
            }
        }
        // Started (and resumed) while the start screen showed: what those do, now.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) started()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) resumed()
    }

    /** [action] once the start screen has gone (at once without one; 3.0.13: after the first frame when it was skipped). */
    private fun whenStarted(action: () -> Unit) {
        if (startScreen == null && !waitsForFirstFrame) action() else afterStart += action
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // While the start screen shows, the browser handles it once built.
        if (built) {
            handleIntent(intent)
        } else {
            setIntent(intent)
            intentWaits = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MENU_OPEN, built && quickMenu.isOpen)
    }

    private fun startupChores() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { com.swiftbrowser.core.Storage.freeSpaceIfLow(this@MainActivity) }
            if (freed > 0) snack("Your phone is almost full: Swift freed ${UrlUtils.formatBytes(freed)} of saved files", long = true)
            // 3.0: history over a year old goes, once a week at most.
            withContext(Dispatchers.IO) { SwiftApp.db.pruneHistoryIfDue(prefs) }
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
                    // 3.0: done at Proton already: another year, from here.
                    .setNeutralButton("I extended it") { _, _ -> VpnUi.markExtended(this@MainActivity, loc) }
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
            layoutParams = LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.toolbar_button_width), dp(48f).toInt())
            Ui.pressable(this, Motion.PRESS_ICON)
            // The ripple of the bar it joins.
            if (barRippleDark == true) background = Ui.rippleBorderless(this@MainActivity, Ui.RIPPLE_ON_DARK)
            // 3.0.3: holding it shows its name.
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, b.label)
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
                    b.alpha = if (ok) 1f else Ui.DISABLED_ALPHA
                }
                "bookmark" -> {
                    val page = "${tab.profileId} ${tab.url}"
                    if (bookmarkShownFor != page) {
                        bookmarkShownFor = page
                        b.tag = UrlUtils.isHttp(tab.url) && SwiftApp.db.isBookmarked(tab.profileId, tab.url)
                    }
                    val on = b.tag == true
                    setBarIcon(b, if (on) R.drawable.ic_star else R.drawable.ic_star_border, if (UrlUtils.isHttp(tab.url)) 1f else Ui.DISABLED_ALPHA)
                    if (on) tint = barAccent()
                }
                "vpn" -> {
                    val on = com.swiftbrowser.vpn.Vpn.state == com.swiftbrowser.vpn.Vpn.State.ON
                    // 3.0.4: and its key filled, as Swift's icons are when on.
                    setBarIcon(b, OnIcons.of(R.drawable.ic_vpn, on))
                    if (on) tint = barAccent()
                }
            }
            // Only when it changed (2.6): this runs on every page update.
            if (b.imageTintList?.defaultColor != tint) b.imageTintList = ColorStateList.valueOf(tint)
        }
    }

    /** How solid each of the bar's icons was last set to be (see [setBarIcon]). */
    private val barIconAlpha = HashMap<View, Float>()

    /**
     * [b] shows [res] at [restAlpha]. A new icon comes in with a short fade; this runs on every page
     * update, so nothing is touched while nothing changes.
     */
    private fun setBarIcon(b: ImageButton, res: Int, restAlpha: Float = 1f) {
        val shown = b.getTag(R.id.motion_icon) as? Int
        if (shown == res) {
            if (barIconAlpha[b] != restAlpha) {
                barIconAlpha[b] = restAlpha
                b.alpha = restAlpha
            }
            return
        }
        b.setTag(R.id.motion_icon, res)
        b.setImageResource(res)
        barIconAlpha[b] = restAlpha
        if (shown == null || !b.isShown) {
            b.alpha = restAlpha
            return
        }
        b.alpha = 0f
        Motion.anim(b, Motion.ICON, Motion.enter).alpha(restAlpha).start()
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
            // 3.0.12: its name, address and folder can be changed straight away, as Chrome offers.
            snack("Bookmarked", "Edit") { editNewBookmark(pid, url) }
            extraButtons["bookmark"]?.tag = true
        }
        bookmarkShownFor = "$pid $url"
        updateToolbarExtras()
    }

    /** 3.0.12: the "Bookmarked" message's Edit: the bookmark just made (read after its saving, on the same thread). */
    private fun editNewBookmark(pid: Long, url: String) {
        TabManager.io.execute {
            val row = SwiftApp.db.bookmark(pid, url)
            runOnUiThread {
                if (row == null || isFinishing) return@runOnUiThread
                BookmarkEditor.edit(this, row, pid) {
                    // A changed address may leave this page without its star.
                    bookmarkShownFor = null
                    updateToolbarExtras()
                }
            }
        }
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

    /** The camera, for a QR code: a web address can be opened, other text searched, either copied or shared. */
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
        // 3.0: every code first shows what it holds, a web address included (where it really
        // goes, in big letters); only web pages open, never javascript:, data: or file: addresses.
        val address = if (wifi == null) UrlUtils.addressOf(text, trusted = false) else null
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
            address != null -> Sheets.actions(
                this, UrlUtils.siteName(address).ifBlank { address }, "Link from the QR code",
                listOf(
                    Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) {
                        val tab = current
                        if (tab != null && (tab.showsHome || tab.isBlank)) openInCurrent(address) else TabManager.newTab(address, incognito = tab?.incognito == true)
                    },
                    Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copy(text) },
                    Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(text) },
                ),
                fullAddress = address, iconRes = R.drawable.ic_qr,
            )
            else -> Sheets.actions(
                // Tapping it shows the whole text.
                this, "Text from the QR code", if (text.length > 40) text.take(40).trimEnd() + "…" else text,
                listOf(
                    // Always a search, whatever the text looks like.
                    Sheets.Action(R.drawable.ic_search, "Search", primary = true) {
                        stopEditing()
                        openInCurrent(UrlUtils.searchUrl(text))
                    },
                    Sheets.Action(R.drawable.ic_copy, "Copy", primary = true) { copy(text) },
                    Sheets.Action(R.drawable.ic_share, "Share", primary = true) { share(text) },
                ),
                fullAddress = text.take(2000), iconRes = R.drawable.ic_qr,
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

    /** The Google account's photo on the profile button follows sign-ins (3.0). */
    private val accountListener: () -> Unit = { updateProfileChip() }

    /** Setup files (.conf) already finished: each new one is offered as a VPN location once. */
    /**
     * 3.0.11: Google Takeout's .zip (named takeout-….zip) or a passwords export (a .csv with "password"
     * in its name, as Google's and Chrome's are) finished downloading: offered to bring into Swift, once.
     */
    private val chromeFilesSeen = HashSet<Long>()
    private val chromeFileListener: () -> Unit = {
        for (d in Downloads.all()) {
            val kind = chromeFileKind(d) ?: continue
            if (!chromeFilesSeen.add(d.id)) continue
            snack("Downloaded ${d.fileName}", "Bring into Swift", long = true) {
                val uri = android.net.Uri.fromFile(java.io.File(d.filePath))
                if (kind == "zip") ChromeImportUi.takeout(this, uri) else ChromeImportUi.passwords(this, uri, download = d)
            }
        }
    }

    private fun chromeFileKind(d: Downloads.Item): String? {
        if (d.status != Downloads.DONE) return null
        val name = d.fileName.lowercase()
        return when {
            name.startsWith("takeout") && name.endsWith(".zip") -> "zip"
            name.endsWith(".csv") && "password" in name -> "csv"
            else -> null
        }
    }

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
            // 3.0.11: switching to another location is always automatic (it was a setting), so every
            // other location was tried already: the VPN sheet is the way on.
            com.swiftbrowser.vpn.Vpn.State.FAILED -> if (vpn.wanted) {
                snack(vpn.error ?: "The VPN isn't connected", "VPN") { showVpnSheet() }
            }
            // 3.0.7: it turned itself off (no internet, or no location answered): why, once; pages
            // that waited for it load now.
            com.swiftbrowser.vpn.Vpn.State.OFF -> vpn.error?.let { why ->
                TabManager.onOnline()
                snack(why, "VPN", long = true) { showVpnSheet() }
            }
            else -> Unit
        }
    }

    /** "VPN" at the end of the address while it is on (or should be); tapping it opens the VPN sheet. */
    private val vpnBadge by lazy { findViewById<TextView>(R.id.vpn_badge) }
    private var vpnBadgeShown: Any? = null

    /** The colours the badge has, and its fade from one state's to the next. */
    private var vpnBadgeFill = 0
    private var vpnBadgeInk = 0
    private var vpnBadgeAnim: ValueAnimator? = null

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
        // Coming into sight it pops; a change while it is there cross-fades (never both at once: they share its animation).
        val appearing = show && (badge.visibility != View.VISIBLE || badge.getTag(R.id.motion_hiding) == true)
        Motion.pop(badge, show)
        if (!show) {
            // Hiding stops a change of its words on the way: they are the ones shown next time.
            (badge.getTag(R.id.motion_text) as? CharSequence)?.let {
                badge.text = it
                badge.setTag(R.id.motion_text, null)
            }
            return
        }
        // Asked on every key typed: only redone when it changed (2.6).
        val key = listOf(fill, ink, text, Looks.accent, VpnUi.label())
        if (key == vpnBadgeShown) return
        vpnBadgeShown = key
        val toFill = color(fill)
        val toInk = color(ink)
        val shape = badge.background as? android.graphics.drawable.GradientDrawable ?: Ui.shape(this, toFill, 12).also { badge.background = it }
        vpnBadgeAnim?.cancel()
        vpnBadgeAnim = null
        if (appearing || vpnBadgeFill == 0 || !Motion.enabled(this)) {
            badge.setTag(R.id.motion_text, null)
            badge.text = text
            shape.setColor(toFill)
            badge.setTextColor(toInk)
        } else {
            Motion.swapText(badge, text)
            val fromFill = vpnBadgeFill
            val fromInk = vpnBadgeInk
            vpnBadgeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.SHORT
                interpolator = Motion.standard
                addUpdateListener {
                    val t = it.animatedFraction.toDouble()
                    shape.setColor(BarTones.blend(fromFill, toFill, t))
                    badge.setTextColor(BarTones.blend(fromInk, toInk, t))
                }
                start()
            }
        }
        vpnBadgeFill = toFill
        vpnBadgeInk = toInk
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
        visibleToUser = true
        if (built) started()
    }

    private fun started() {
        if (appliedColors != null && appliedColors != Palette.signature) {
            recreate()
            return
        }
        Looks.sync()
        Looks.watchClock(true)
        // 3.0.4: the home screen widget catches up with the look ("Dark at night" may have turned meanwhile).
        SearchWidget.refresh(this)
        // Settings → Look & screen may have moved the address bar.
        if (bottomBar != prefs.toolbarAtBottom) applyToolbarPosition()
        // 3.0.4: ... or turned websites' colours in it on or off.
        barKey = null
        updateBarColours(animate = false)
        // 3.0.4: Settings → Browsing & tabs may have turned the group strip on or off.
        updateGroupStrip()
        // Settings → Browsing may have changed the search engine, Look & screen the address bar's buttons.
        urlEdit.hint = com.swiftbrowser.core.SearchEngine.current.hint
        applyToolbarButtons()
        bookmarkShownFor = null
        updateToolbarExtras()
        if (home.visibility == View.VISIBLE) current?.let { home.show(it.incognito) }
        // Settings may have changed the text size for all sites; the site on screen may have its own.
        current?.let { Engine.showTextScaleFor(UrlUtils.site(it.url), it.incognito) }
        Diagnostics.setOnScreen(true)
        com.swiftbrowser.media.PlaybackService.setOnScreen(true)
        // 3.0: only when it can be seen (not under the home page either).
        activateIfSeen()
        // 3.0: the open tab's page process ended while Swift was away: its page loads again now.
        current?.let { TabManager.reviveOnReturn(it) }
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
        visibleToUser = false
        if (!built) return
        Looks.watchClock(false)
        Diagnostics.setOnScreen(false)
        com.swiftbrowser.media.PlaybackService.setOnScreen(false)
        // 3.0: the open tab was in use until now (idle tabs and Inactive tabs count from here).
        current?.let { TabManager.leftTab(it) }
        TabManager.flushSaves()
        com.swiftbrowser.engine.DataUsage.sample()
        com.swiftbrowser.engine.DataUsage.save()
        if (!isChangingConfigurations) {
            // A playing page stays active so its video or music goes on (keep playing in the
            // background): an active page also keeps the engine's page process from being closed.
            val keepPlaying = prefs.keepPlaying && current?.playing == true
            current?.session?.let { if (it.isOpen && !keepPlaying) it.setActive(false) }
            // 3.0.2: other tabs let go of their pages a minute after none of Swift's screens shows
            // (TabManager.inSight), not a minute after this one is covered by Settings or the player;
            // 3.0.11: so do the ad blocker's list updates (they were started here).
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
        // 3.0.13: the ad blocker's Load now message belongs to this screen.
        loadNowPill?.dismiss()
        loadNowPill = null
        com.swiftbrowser.core.GoogleAccounts.removeListener(accountListener)
        AdBlock.removeListener(adListener)
        // 3.0: a tab being left lets its page rest now (its picture's answer would come to a screen that's gone).
        leavingDone?.invoke()
        leavingDone = null
        leavingTab = null
        // 3.0: the pages keep no hold on this screen (rebuilt for a change of theme, say).
        if (selectionMenuLazy.isInitialized()) {
            val menu = selectionMenu
            for (t in TabManager.tabs) t.session?.let { if (it.selectionActionDelegate === menu) it.selectionActionDelegate = null }
        }
        Downloads.removeListener(updateDownloadListener)
        Downloads.removeListener(downloadRingListener)
        Downloads.removeListener(setupFileListener)
        Downloads.removeListener(chromeFileListener)
        if (TabManager.ui === this) TabManager.ui = null
        if (Engine.activityDelegate != null) Engine.activityDelegate = null
        home.listener = null
        switcherView?.listener = null
        gestureLayer.pullListener = null
        gestureLayer.fingerListener = null
        settleAnim?.cancel()
        barAnim?.cancel()
        vpnBadgeAnim?.cancel()
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
        // 18 dp to look at (in a 24 dp box), 48 dp to tap (2.6).
        (urlIcon.parent as View).post {
            val r = android.graphics.Rect()
            urlIcon.getHitRect(r)
            val grow = Ui.dp(this, 12)
            r.inset(-grow, -grow)
            (urlIcon.parent as View).touchDelegate = android.view.TouchDelegate(r, urlIcon)
        }
        // The site's icon (or the warning that it isn't secure) opens that site's settings.
        urlIcon.setOnClickListener {
            val tab = current
            val site = tab?.takeIf { !editing && !it.showsHome && UrlUtils.isHttp(it.url) }?.let { UrlUtils.site(it.url) }
            if (site == null) startEditing() else Sheets.siteSettings(this, site, tab)
        }
        Ui.pressable(urlIcon, Motion.PRESS_ICON, morph = false)
        urlMic = findViewById(R.id.url_mic)
        urlScan = findViewById(R.id.url_scan)
        urlClear = findViewById(R.id.url_clear)
        for (v in listOf<View>(urlMic, urlScan, urlClear)) Ui.pressable(v, Motion.PRESS_ICON, morph = false)
        videoBtn = findViewById(R.id.video_btn)
        tabsCount = findViewById(R.id.tabs_count)
        menuBtn = findViewById(R.id.menu_btn)
        homeBtn = findViewById(R.id.home_btn)
        tabsBtn = findViewById(R.id.tabs_btn)
        progress = findViewById(R.id.progress)
        // 3.0.12: rounded, with a gap before the rest of the way (ProgressLine), like Swift's other bars.
        ProgressLine.apply(progress)
        gestureLayer = findViewById(R.id.gesture_layer)
        gecko = findViewById(R.id.gecko)
        home = findViewById(R.id.home)
        crashView = findViewById(R.id.crash_view)
        pageCover = findViewById(R.id.page_cover)
        suggestionsList = findViewById(R.id.suggestions)
        ptrIndicator = findViewById(R.id.ptr_indicator)
        hud = findViewById(R.id.hud)
        // As the player's own gesture readout.
        hud.typeface = Ui.MEDIUM
        findBar = findViewById(R.id.find_bar)
        findBarBox = findViewById(R.id.find_bar_box)
        findEdit = findViewById(R.id.find_edit)
        findCount = findViewById(R.id.find_count)
        // 3.0.12: the bottom of the screen rides with the keyboard (see applyBottomShift).
        BottomGlide(root) { applyBottomShift(it) }
        // Swift's own font on every Android (it takes the phone's place by itself only below Android 9).
        Ui.size(urlEdit, Ui.Type.BODY)
        Ui.size(findEdit, Ui.Type.BODY)
        Ui.size(vpnBadge, Ui.Type.SMALL)
        Ui.pressable(vpnBadge, Motion.PRESS_BUTTON)
        AppFont.cut(Ui.Type.SMALL.sp, Ui.Weight.BOLD)?.let { tabsCount.typeface = it }
        // The find box as Swift's other search boxes: a 20 dp icon.
        ContextCompat.getDrawable(this, R.drawable.ic_find)?.mutate()?.let {
            val size = Ui.dp(this, 20)
            it.setBounds(0, 0, size, size)
            findEdit.setCompoundDrawablesRelative(it, null, null, null)
        }
        findBar.foreground = findLine
        // The crashed tab in the toolkit's look: an icon in a soft circle and the title style.
        findViewById<ImageView>(R.id.crash_icon).apply {
            val inset = Ui.dp(this@MainActivity, 18)
            background = Ui.circle(Ui.accentContainer(this@MainActivity))
            setImageDrawable(Ui.icon(this@MainActivity, R.drawable.ic_error, Ui.accent(this@MainActivity)))
            setPadding(inset, inset, inset, inset)
        }
        Ui.style(findViewById<TextView>(R.id.crash_title), Ui.Type.TITLE)
        // Drawn above the pull-to-refresh circle, without a shadow on the page.
        appBar.elevation = dp(8f)
        appBar.outlineProvider = null
        com.swiftbrowser.vpn.Vpn.addListener(vpnListener)
        com.swiftbrowser.core.GoogleAccounts.addListener(accountListener)
        shownVpnState = com.swiftbrowser.vpn.Vpn.state
        updateVpnBadge()
        // The toolbar's buttons give the same small press as every button in the app.
        for (v in listOf<View>(homeBtn, tabsBtn, menuBtn, videoBtn, profileChip.parent as View)) Ui.pressable(v, Motion.PRESS_ICON)
        // 3.0.3: holding an icon-only button shows its name (the tab button keeps its own hold menu).
        for (v in listOf<View>(homeBtn, menuBtn, videoBtn)) {
            v.contentDescription?.let { androidx.appcompat.widget.TooltipCompat.setTooltipText(v, it) }
        }
        // 3.0.4: the address box and the find box keep one shape each, tinted in the bar's colours (paintBar).
        urlBox.background = urlShape
        findEdit.background = findShape
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
                // Shortcuts and widgets from before 2.9: documents are in Files now.
                ACTION_DOCUMENTS -> FilesActivity.open(this)
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
            // A shortcut made before 2.8 (newer ones open the viewer's screen directly): Back leaves Swift.
            if (docId == null || SwiftApp.db.document(docId) == null) snack("Swift doesn't have this document any more") else Docs.reopen(this, docId, leaveSwift = true)
            return
        }
        intent.getStringExtra(EXTRA_OPEN_FILE)?.let { path ->
            intent.removeExtra(EXTRA_OPEN_FILE)
            // Only Swift's own downloads (another app could otherwise make Swift show its private files).
            val d = Downloads.all().firstOrNull { it.filePath == path } ?: return
            stopEditing()
            switcherView?.close()
            if (!Docs.open(this, Uri.fromFile(java.io.File(path)), d.fileName, d.mime, d.url.takeIf { UrlUtils.isHttp(it) })) openFileInTab(path)
            return
        }
        // 2.4: a document from another app sent here ("Open with Swift" goes to the viewer's own
        // screen since 2.8, DocumentActivity): shown there, and Back leaves Swift.
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && (data.scheme == "content" || data.scheme == "file")) {
            stopEditing()
            switcherView?.close()
            quickMenu.close()
            val id = Docs.register(this, data, null, intent.type)
            if (id == null) snack("Swift can't show this kind of file") else Docs.reopen(this, id, leaveSwift = true)
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
            // 3.0: not again when Swift is opened from the recent apps (Android hands it the same link).
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
            stopEditing()
            switcherView?.close()
            // 3.0: a tab already showing exactly this address is shown instead of a new one (tabs piled
            // up); a new one closes when Back leaves its first page, and Android returns to that app.
            val open = TabManager.profileTabs().firstOrNull { TabRules.sameAddress(it.url, url) }
            if (open != null) {
                if (open !== current) TabManager.select(open)
                // 3.0.5: with the home page over it the link would seem to do nothing.
                if (open.homeShown) leaveHome(open)
            } else {
                TabManager.newTab(url, incognito = false).backTo = Docs.BACK_APP
            }
        }
    }

    // ------------------------------------------------------------------ toolbar & address bar

    private fun setupToolbar() {
        profileChip.setOnClickListener { Sheets.profiles(this) }
        tabsBtn.setOnClickListener { tabsTapped() }
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
        // Rows change at once while typing; only the search engine's late results move them (refreshSuggestions).
        suggestionsList.itemAnimator = null
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
        // 3.0.5: the bar's width changes as its buttons come and go: whether the address fades is looked at again.
        urlEdit.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (!editing && right - left != oldRight - oldLeft) Ui.fadeTextEnd(urlEdit, on = true)
        }
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
            // 3.0: copied text only goes to web pages; "javascript:", "data:" or "file:" text is searched for.
            val isAddress = UrlUtils.addressOf(clip, trusted = false) != null
            actions += Sheets.Action(if (isAddress) R.drawable.ic_open_new else R.drawable.ic_search, if (isAddress) "Paste and go" else "Paste and search", primary = true) { navigate(clip, trusted = false) }
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
        // 3.0.4: typing goes with Swift's own colours (the suggestions below are in them), not a website's.
        updateBarColours(animate = true)
        showToolbar()
        // 3.0.4: the group strip makes way for the suggestions (its room stays: the page doesn't move).
        showGroupStrip()
        if (!wasEditing) setToolbarEditing(true)
        val tab = current
        val text = when {
            tab == null || tab.isBlank || tab.homeShown -> ""
            else -> UrlUtils.searchQuery(tab.url) ?: tab.url
        }
        urlBox.visibility = View.VISIBLE
        // 3.0.8: never half faded in (it may have been coming in as the home page scrolled).
        urlBox.alpha = 1f
        // 3.0.5: the whole address plainly while typing: no fading at its end.
        Ui.fadeTextEnd(urlEdit, on = false)
        completing = true
        urlEdit.setText(text)
        completing = false
        urlEdit.selectAll()
        if (!wasEditing) loadCompletions()
        setUrlIcon(com.swiftbrowser.core.SearchEngine.current.icon)
        if (!wasEditing) {
            suggestionsList.animate().cancel()
            suggestionsList.alpha = 0f
            Motion.anim(suggestionsList, Motion.PAGE_MS, Motion.page).alpha(1f).withLayer().start()
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
        Motion.anim(urlBox, Motion.PAGE_MS, Motion.page).translationY(0f).withEndAction {
            toolbar.clipChildren = true
            (appBar as android.view.ViewGroup).clipChildren = true
        }.start()
        box.animate().cancel()
        Motion.anim(box, Motion.PAGE_MS, Motion.page).alpha(0f).translationY(-dy / 3f).start()
    }

    /** The home page's search box comes back (Back from typing on the home page). */
    private fun restoreHomeSearch() {
        val box = home.searchBox
        if (box.alpha == 1f && box.translationY == 0f) return
        box.animate().cancel()
        Motion.anim(box, Motion.PAGE_MS, Motion.page).alpha(1f).translationY(0f).start()
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
            duration = Motion.PAGE_MS
            interpolator = Motion.page
            ordering = android.transition.TransitionSet.ORDERING_TOGETHER
        }
        android.transition.TransitionManager.beginDelayedTransition(toolbar, transition)
        for (v in toolbarButtons()) v.visibility = if (on) View.GONE else View.VISIBLE
        if (on) videoBtn.visibility = View.GONE else refreshToolbarNow()
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        // 3.0.4: back to the website's colour, if it has one.
        updateBarColours(animate = true)
        showGroupStrip()
        setToolbarEditing(false)
        urlBox.animate().cancel()
        urlBox.translationY = 0f
        suggestJob?.cancel()
        if (suggestionsList.visibility == View.VISIBLE) Motion.fadeOut(suggestionsList, toDy = 0, duration = Motion.PAGE_OUT_MS)
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

    /** [trusted]: typed into the address bar (see UrlUtils.toUrlOrSearch). */
    private fun navigate(text: String, trusted: Boolean = true) {
        val url = UrlUtils.toUrlOrSearch(text, trusted)
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
                .setMessage("Voice search uses Google's speech recognition, which comes with the Google app. Install or enable the Google app on your phone to use it.")
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
        // 3.0: what's typed in an incognito tab never goes to the search engine, nor a web address
        // being typed or pasted (it can say where you are, and who: links carry sign-in codes).
        val google = prefs.searchSuggestions && current?.incognito != true && !UrlUtils.looksLikeAddress(text)
        suggestJob = lifecycleScope.launch {
            // A pause in typing first (2.6): fast typing doesn't look up every letter.
            if (text.isNotBlank()) delay(70)
            val head = ArrayList<Suggestion>()
            if (full.isNotBlank()) {
                val target = UrlUtils.toUrlOrSearch(full)
                head += if (UrlUtils.addressOf(full) == null) {
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
            suggestionsList.itemAnimator = null
            suggestionAdapter.show(groups(emptyList()), text)
            if (google && text.isNotBlank()) {
                delay(150)
                val fromGoogle = withContext(suggestThread) { Suggestions.fromEngine(text) }
                    .filter { !it.equals(text, ignoreCase = true) }
                    .take(5)
                    .map { Suggestion(Suggestion.Kind.SEARCH, it, UrlUtils.searchUrl(it)) }
                // They arrive late: the rows they push down move instead of jumping, this once.
                suggestionsList.itemAnimator = Motion.listAnimator()
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

    /**
     * Open tabs (other than this one) whose title or address has the typed words: "Switch to tab".
     * 3.0: a page open in two tabs is offered once, and not when it's the page of this tab.
     */
    private fun openTabMatches(text: String): List<Suggestion> {
        val q = text.trim()
        if (q.length < 2) return emptyList()
        val here = current
        val herePage = here?.url?.let { UrlUtils.stripFragment(it) }
        return TabManager.tabs.asSequence()
            .filter { it !== here && it.incognito == (here?.incognito ?: false) && !it.isBlank }
            .filter { it.displayTitle.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .filter { UrlUtils.stripFragment(it.url) != herePage }
            .distinctBy { UrlUtils.stripFragment(it.url) }
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
        // 3.0.8: over the home page the bar stands in for its search box: the search hint and engine,
        // not the address of the page under it.
        val onHome = tab?.showsHome == true
        val url = if (onHome) null else tab?.url
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
            // 3.0: a site name too long for the bar shows its end, where it says whose site it is.
            else -> query ?: UrlUtils.siteName(url).let { if (UrlUtils.isHttp(url)) fitSiteName(it) else it }
        }
        if (text != shownUrlText) {
            shownUrlText = text
            urlEdit.setText(text)
            // 3.0.5: text too long for the bar fades out at its end (its own check; nothing changes while it fits).
            Ui.fadeTextEnd(urlEdit, on = true)
        }
        val page = tab != null && !onHome && !tab.isBlank && query == null && !reader && !saved && !blocked && !document
        // A secure site shows its own icon; only a site that isn't secure gets a warning.
        val favicon = if (page && tab!!.secure) com.swiftbrowser.core.Favicons.cached(url) else null
        if (favicon != null) {
            if (favicon !== shownFavicon) {
                shownFavicon = favicon
                shownUrlIcon = -1
                urlIcon.imageTintList = null
                urlIcon.setImageBitmap(favicon)
                updateNotSecureBacking()
                fadeInUrlIcon()
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
            // 3.0: with search words, the logo of the engine whose page it is.
            !page -> (com.swiftbrowser.core.SearchEngine.ofResults(url)?.takeIf { query != null } ?: com.swiftbrowser.core.SearchEngine.current).icon
            // Only a site opened over plain http gets the warning (an https page that failed to
            // load isn't "not secure").
            UrlUtils.scheme(url) == "http" -> R.drawable.ic_not_secure
            else -> R.drawable.ic_globe
        }
        urlIcon.contentDescription = if (icon == R.drawable.ic_not_secure) "Not secure. Site settings" else if (page) "Site settings" else null
        if (icon != shownUrlIcon) setUrlIcon(icon)
    }

    private var shownFavicon: android.graphics.Bitmap? = null

    /** The text box's width the site name was last fitted to (3.0); 0 until it's laid out. */
    private var urlFitWidth = 0
    private var urlFitWatched = false

    /**
     * 3.0: [site] as it fits the address bar: "…" and its end when it's too long, so
     * "accounts.google.com.verify-session.example.net" can't hide its real owner past the edge.
     */
    private fun fitSiteName(site: String): String {
        if (!urlFitWatched) {
            urlFitWatched = true
            // The bar narrows and widens as its buttons come and go (typing, the tab grid, turning
            // the phone): the name is fitted again to the width it ends up with.
            urlEdit.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
                val width = right - left - urlEdit.totalPaddingLeft - urlEdit.totalPaddingRight
                if (width == urlFitWidth) return@addOnLayoutChangeListener
                urlFitWidth = width
                if (!editing) v.post {
                    shownUrlText = null
                    updateUrlBar()
                }
            }
        }
        val width = urlFitWidth.takeIf { it > 0 } ?: (urlEdit.width - urlEdit.totalPaddingLeft - urlEdit.totalPaddingRight)
        if (width <= 0) return site
        // Measured once per site and width (the bar is updated many times while a page loads).
        lastFit?.let { (s, w, fitted) -> if (s == site && w == width) return fitted }
        val paint = urlEdit.paint
        return UrlUtils.hostEnd(site) { paint.measureText(it) <= width }.also { lastFit = Triple(site, width, it) }
    }

    private var lastFit: Triple<String, Int, String>? = null

    private fun setUrlIcon(res: Int) {
        val changed = res != shownUrlIcon
        shownUrlIcon = res
        shownFavicon = null
        urlIcon.setImageResource(res)
        urlIcon.imageTintList = urlIconTint(res)
        updateNotSecureBacking()
        if (changed) fadeInUrlIcon()
    }

    /** The address's icon changed: it comes in with a short fade (a security mark doesn't spin, see [Motion.swapIcon]). */
    private fun fadeInUrlIcon() {
        if (!urlIcon.isShown) return
        urlIcon.animate().cancel()
        urlIcon.alpha = 0f
        Motion.anim(urlIcon, Motion.ICON, Motion.enter).alpha(1f).start()
    }

    /** Whether the open lock has its circle behind it now. */
    private var notSecureBacked = false

    /** On Swift's own bar the open lock sits on a soft red circle, as "VPN off" does; on a website's colour it stays in the bar's red. */
    private fun updateNotSecureBacking() {
        val backed = shownFavicon == null && shownUrlIcon == R.drawable.ic_not_secure && barShown?.site != true
        if (backed == notSecureBacked) return
        notSecureBacked = backed
        urlIcon.background = if (backed) Ui.circle(color(R.color.danger_container)) else null
    }

    /** The address's icon [res] in the bar's colours (3.0.4: a website's too); a search engine's logo as it is. */
    private fun urlIconTint(res: Int): ColorStateList? = when {
        com.swiftbrowser.core.SearchEngine.isLogo(res) -> null
        res == R.drawable.ic_not_secure && barShown?.site != true -> ColorStateList.valueOf(color(R.color.on_danger_container))
        res == R.drawable.ic_not_secure -> ColorStateList.valueOf(barShown?.danger ?: Ui.danger(this))
        else -> ColorStateList.valueOf(iconColor())
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
                // 3.0.12: the whole way (1000 of 1000; it said 100, a tenth, so the line faded wherever it was).
                glideProgress(1000, 160)
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
        // Read before a photo first replaces it.
        val circle = chipCircle
        // The Google account's photo when the profile is signed in (3.0), otherwise its coloured letter.
        val photo = com.swiftbrowser.core.GoogleAccounts.picture(this, p.id)
        if (photo != null) {
            profileChip.text = ""
            profileChip.backgroundTintList = null
            // Drawn at 30 dp in the middle of the button's place (44 x 48 dp), like its circle (bg_profile_chip).
            profileChip.background = android.graphics.drawable.InsetDrawable(photo, dp(7f).toInt(), dp(9f).toInt(), dp(7f).toInt(), dp(9f).toInt())
        } else {
            profileChip.text = Profiles.initial(p)
            if (profileChip.background !== circle) profileChip.background = circle
            profileChip.backgroundTintList = ColorStateList.valueOf(p.color)
        }
        val account = com.swiftbrowser.core.GoogleAccounts.get(p.id)
        profileChip.contentDescription = "Profile: ${p.name}" + (account?.let { ", signed in as ${it.email}" } ?: "")
    }

    /** The profile button's own circle, kept while a Google photo takes its place (3.0). */
    private val chipCircle by lazy { profileChip.background }

    /** Incognito tabs get a dark toolbar whatever the theme, like in Chrome. */
    private fun applyIncognitoStyle(incognito: Boolean) {
        if (styledIncognito == incognito) return
        styledIncognito = incognito
        // 3.0: the keyboard learns nothing typed in incognito: no suggestions, nothing for its
        // dictionary or history (Gboard and others honour this on Android 7 too).
        for (field in listOf(urlEdit, findEdit)) {
            val options = field.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
            field.imeOptions = if (incognito) options or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else options
            val type = field.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS.inv()
            field.inputType = if (incognito) type or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else type
        }
        // The suggestions in the same colours (2.6). 3.0.4: the bar's, the find bar's and the status
        // bar's go with the bar's colours (updateBarColours): incognito's, Swift's or a website's.
        suggestionsList.setBackgroundColor(color(if (incognito) R.color.incognito_bg else R.color.toolbar_bg))
        suggestionAdapter.incognito = incognito
        styledTabCount = null
        profileChip.visibility = if (incognito) View.GONE else View.VISIBLE
        incognitoIcon.visibility = if (incognito) View.VISIBLE else View.GONE
        shownUrlIcon = 0
        bookmarkShownFor = null
        updateBarColours(animate = true)
        styleTabCount()
        updateToolbarExtras()
    }

    /** The bar's icons and hints, in its colours as they are now (3.0.4: a website's too). */
    private fun iconColor(): Int =
        barShown?.icon ?: color(if (current?.incognito == true) R.color.incognito_hint else R.color.text_secondary)

    /** 3.0.4: the accent's bits on the bar (a filled star, the VPN on, the loading line), readable on its colour. */
    private fun barAccent(): Int = barShown?.accent ?: Ui.accent(this)

    // 3.0.11: incognito pages may always appear in screenshots and recent apps (it was a setting, off at
    // first), so the browser's screen no longer keeps itself out of them.

    // ------------------------------------------------------------------ 3.0.4: the bar's colours

    /** The colours the bar shows now (on their way to new ones while they change). */
    private var barShown: BarColours? = null

    /** What the bar's colours were last worked out from: the same again changes nothing. */
    private var barKey: Any? = null
    private var barAnim: ValueAnimator? = null

    /** Whether the status bar's icons are dark (on a light colour), as last set. */
    private var statusIconsDark: Boolean? = null

    /** The address box's and the find box's shapes, tinted in the bar's colours. */
    private val urlShape by lazy { ContextCompat.getDrawable(this, R.drawable.bg_url)!!.mutate() }
    private val findShape by lazy { ContextCompat.getDrawable(this, R.drawable.bg_url)!!.mutate() }

    private val findButtons by lazy { listOf<ImageView>(findViewById(R.id.find_prev), findViewById(R.id.find_next), findViewById(R.id.find_close)) }

    /** The find bar's top hairline, in the bar's colours (its foreground). */
    private val findLine by lazy {
        android.graphics.drawable.GradientDrawable().apply { setSize(-1, Ui.dp(this@MainActivity, 1).coerceAtMost(2)) }
    }

    /** Whether the bar's buttons have a dark bar's ripple; null until first painted. */
    private var barRippleDark: Boolean? = null

    /** Light ripples on a dark bar, also in the light look (a website's dark colour, incognito): only when that changes. */
    private fun paintBarRipples(dark: Boolean) {
        barRippleDark = dark
        val tint = if (dark) Ui.RIPPLE_ON_DARK else null
        val buttons = listOf<View>(menuBtn, homeBtn, tabsBtn, videoBtn, urlMic, urlScan, urlClear) + findButtons + extraButtons.values
        for (b in buttons) b.background = Ui.rippleBorderless(this, tint)
    }

    /**
     * The website's colour for the bar now (its theme-color, fit for the bar: BarTones.forBar), or null
     * for Swift's own: not in incognito, while typing an address or in full screen, nor on Swift's own
     * pages (the home page, reader view, documents, saved pages, the crash view; an error page says no
     * colour, see TabDelegates.onPageStop). 3.0.11: always on (no longer a setting).
     */
    private fun siteColourFor(tab: Tab?): Int? {
        if (tab == null || tab.incognito || editing || fullscreenTab != null) return null
        if (tab.showsHome || tab.crashed || !UrlUtils.isHttp(tab.url)) return null
        val c = tab.siteColour ?: return null
        return BarTones.forBar(c, color(R.color.toolbar_bg), Palette.isNight(this))
    }

    /** The bar's colours for [tab], with the website's colour [site] (null: Swift's own, or incognito's). */
    private fun barColoursFor(tab: Tab?, site: Int?): BarColours {
        val incognito = tab?.incognito == true
        val accent = Ui.accent(this)
        val danger = Ui.danger(this)
        if (site != null) return BarColours.forSite(site, accent, danger)
        pictureBarInk(tab)?.let { return BarColours.overPicture(it, color(R.color.surface_alt), danger) }
        return if (incognito) {
            BarColours(
                color(R.color.incognito_toolbar), color(R.color.incognito_url), color(R.color.incognito_text),
                color(R.color.incognito_hint), color(R.color.incognito_url), accent, danger,
            )
        } else {
            BarColours(
                color(R.color.toolbar_bg), color(R.color.surface_alt), color(R.color.text_primary),
                color(R.color.text_secondary), color(R.color.divider), accent, danger,
            )
        }
    }

    /**
     * 3.0.5: the colour of the bar's buttons while the home page's picture runs on behind the bar:
     * the page at its top with a picture. Null for a solid bar: typing an address, scrolled down, and
     * everywhere else.
     */
    private fun pictureBarInk(tab: Tab?): Int? {
        if (tab == null || !tab.showsHome || tab.incognito || tab.crashed || editing || fullscreenTab != null || !home.atTop) return null
        return home.barInk()
    }

    /** The status bar's colour for the bar's [bar]: over a see-through bar, as the top of the picture is (it can't show the picture itself). */
    /** 3.0.12: the colour of Android's bottom bar under the address bar: the bar's, solid (see-through over the home picture). */
    private fun navColour(bar: Int): Int =
        if (android.graphics.Color.alpha(bar) == 255) bar
        else androidx.core.graphics.ColorUtils.compositeColors(bar, color(R.color.toolbar_bg))

    private fun statusColour(bar: Int): Int =
        if (android.graphics.Color.alpha(bar) == 255) bar
        else androidx.core.graphics.ColorUtils.compositeColors(bar, home.pictureTopColour() ?: color(R.color.toolbar_bg))

    /**
     * The bar takes the colours it should have now: a website's, Swift's or incognito's. A change
     * fades in [BAR_COLOUR_MS] ([animate]: a tab switch, another page, typing an address), only the
     * bar's own views redrawn; at once with Android's animations off. Cheap when nothing changed: it
     * comes with every update of the open tab.
     */
    private fun updateBarColours(animate: Boolean) {
        if (!::gecko.isInitialized) return
        val tab = current
        val site = siteColourFor(tab)
        val key = listOf(site, tab?.incognito == true, editing, tab?.showsHome == true, tab?.crashed == true, fullscreenTab != null, pictureBarInk(tab), home.barPicture)
        if (key == barKey) return
        barKey = key
        val target = barColoursFor(tab, site)
        // The status bar's icons dark or light at once (changing them redraws the system's bars).
        val status = statusColour(target.bar)
        // 3.0.12: Swift's card in the recent apps screen in the bar's colour (told once, at the new colour).
        Looks.paintTaskCard(this, status)
        val dark = Contrast.ratio(BarTones.BLACK, status) > Contrast.ratio(BarTones.WHITE, status)
        if (dark != statusIconsDark) {
            statusIconsDark = dark
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = dark
        }
        val from = barShown
        barAnim?.cancel()
        barAnim = null
        // 3.0.5: nothing left to see through (the picture was taken away): no fade over what is underneath.
        val nothingBehind = from != null && android.graphics.Color.alpha(from.bar) < 255 && home.barPicture == null
        if (from == null || from == target || !animate || nothingBehind || !Motion.enabled(this) || !appBar.isShown) {
            paintBar(target)
            return
        }
        barAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BAR_COLOUR_MS
            interpolator = Motion.standard
            addUpdateListener { paintBar(BarColours.between(from, target, it.animatedFraction)) }
            start()
        }
    }

    /** Paints the bar and what belongs to it in [c]: also each step while its colours change. */
    private fun paintBar(c: BarColours) {
        barShown = c
        toolbar.setBackgroundColor(c.bar)
        toolbarDivider.setBackgroundColor(c.line)
        window.statusBarColor = statusColour(c.bar)
        // 3.0.12: Android's own bottom bar: under the address bar in its colour; under a page, Swift's background.
        Looks.paintNavigationBar(this, if (bottomBar) navColour(c.bar) else color(R.color.page_bg))
        // 3.0.5: the picture's share behind a bar that isn't solid; nothing to draw behind a solid one.
        val behind = if (android.graphics.Color.alpha(c.bar) < 255) home.barPicture else null
        if (appBar.background !== behind) appBar.background = behind
        urlShape.setTint(c.pill)
        urlEdit.setTextColor(c.text)
        urlEdit.setHintTextColor(c.icon)
        val icons = ColorStateList.valueOf(c.icon)
        for (b in listOf(menuBtn, homeBtn, urlClear, urlScan, urlMic)) b.imageTintList = icons
        if (shownFavicon == null && shownUrlIcon > 0) urlIcon.imageTintList = urlIconTint(shownUrlIcon)
        updateNotSecureBacking()
        if (c.dark != barRippleDark) paintBarRipples(c.dark)
        // The accent's bits: itself, or the bar's text colour on a website's colour close to it.
        val accent = ColorStateList.valueOf(c.accent)
        progress.progressTintList = accent
        ((videoBtn as android.view.ViewGroup).getChildAt(0) as? ImageView)?.imageTintList = accent
        (menuBtn.foreground as? ProgressRing)?.setColour(c.accent)
        updateToolbarExtras()
        styleTabCount()
        // The find bar in the same colours (2.6).
        findBar.setBackgroundColor(c.bar)
        findLine.setColor(c.line)
        findShape.setTint(c.pill)
        findEdit.setTextColor(c.text)
        findEdit.setHintTextColor(c.icon)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(findEdit, icons)
        styleFindCount()
        for (b in findButtons) b.imageTintList = icons
        groupStrip?.paint(c)
    }

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

            override fun onHomeBarChanged() = updateBarColours(animate = true)

            override fun onHomeSearchAway(away: Boolean) = homeSearchAway(away)

            override fun onNewsSettings() {
                openUrlLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_TOPIC, "NEWS"))
            }

            override fun onContinueClicked(item: com.swiftbrowser.media.Unfinished.Item) {
                // A document opens in Swift's viewer, where it was left (2.4); a video or song from the
                // phone in Swift's player, which goes on from there (2.9).
                item.doc?.let { Docs.reopen(this@MainActivity, it.id); return }
                item.localPath?.let { PlayerActivity.open(this@MainActivity, java.io.File(it)); return }
                openInCurrent(item.url)
                // An article opens in reader view again, where it was left.
                if (!item.video) current?.readerOnLoad = item.url
            }

            override fun onContinueLongClicked(item: com.swiftbrowser.media.Unfinished.Item) = continueMenu(item)

            override fun onProfileClicked() = Sheets.profiles(this@MainActivity)
        }
    }

    /**
     * Sites set to "Open articles in reader view" (2.3): each page once (going back to it from
     * reader view stays on it), not the front page, and nothing said when a page has no article.
     */
    private fun autoReader(tab: Tab, attempt: Int = 0) {
        val url = tab.url
        if (!UrlUtils.isHttp(url) || Helper.isReaderUrl(url) || !SiteSettings.autoReader(UrlUtils.site(url), tab.incognito)) return
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
        item.localPath?.let { path ->
            Sheets.actions(this, item.title, item.caption, listOf(
                Sheets.Action(R.drawable.ic_play, "Play", primary = true) { PlayerActivity.open(this, java.io.File(path)) },
                Sheets.Action(R.drawable.ic_folder, "Show in Files", primary = true) { FilesActivity.open(this, java.io.File(path).parentFile) },
                Sheets.Action(R.drawable.ic_close, "Remove from Continue", danger = true) { home.removeContinue(item) },
            ))
            return
        }
        item.doc?.let { doc ->
            val actions = listOf(
                Sheets.Action(R.drawable.ic_open_new, "Open", primary = true) { Docs.reopen(this, doc.id) },
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
        // 3.0.13: the home page shown over a page still waiting under its cover: the cover (drawn
        // above the home page) goes, or the home page would be hidden under it.
        if (show && coverWaitsForPaint === tab) stopCoverWait()
        if (show) {
            // 3.0.10: back on it while it was leaving: it stays.
            val wasLeaving = homeLeaving != null || home.leaving
            if (wasLeaving) {
                handler.removeCallbacks(homeLeaveTimeout)
                homeLeaving = null
                home.leaving = false
                home.animate().cancel()
                home.alpha = 1f
            }
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
        } else if (home.visibility == View.VISIBLE && homeLeaving == null && !home.leaving) {
            homeShownFor = null
            if (built && tab === current && Motion.enabled(this) && !switcherOpen) {
                // 3.0.10: a site opened from it: the home page stays until the site has drawn something
                // (it vanished at once, leaving a white page while the site loaded), then fades.
                homeLeaving = tab
                home.leaving = true
                // A site just asked for (the tab may not count as loading yet: its session is made first);
                // Back to a page already there fades at once.
                val opening = tab.loading || System.currentTimeMillis() - tab.userNavAt < 2000
                if (opening) handler.postDelayed(homeLeaveTimeout, HOME_LEAVE_MAX_MS) else fadeHomeAway()
            } else {
                hideHomeNow()
            }
        }
        // 3.0.8: on the home page the address bar shows only once its search box has scrolled away.
        val urlVisibility = if (show && !editing && !home.searchAway) View.INVISIBLE else View.VISIBLE
        if (urlBox.visibility != urlVisibility) {
            urlBox.animate().cancel()
            urlBox.alpha = 1f
            urlBox.visibility = urlVisibility
        }
        // 3.0.4: the group strip goes with the page (not over the home page).
        showGroupStrip()
        // 3.0.4: and so does a website's colour (the home page has Swift's).
        updateBarColours(animate = true)
    }

    /**
     * 3.0.8: the home page's search box scrolled out of sight ([away]) or back: the address bar comes in
     * with a short fade in its place (and goes again), so searching stays one tap away.
     */
    private fun homeSearchAway(away: Boolean) {
        val tab = current ?: return
        if (!tab.showsHome || editing || fullscreenTab != null) return
        refreshToolbarNow()
        urlBox.animate().cancel()
        if (away) {
            if (urlBox.visibility != View.VISIBLE) {
                urlBox.alpha = 0f
                urlBox.visibility = View.VISIBLE
            }
            Motion.anim(urlBox, Motion.SHORT).alpha(1f).start()
        } else if (urlBox.visibility == View.VISIBLE) {
            Motion.anim(urlBox, Motion.SHORT).alpha(0f).withEndAction {
                if (!editing && current?.showsHome == true && !home.searchAway) urlBox.visibility = View.INVISIBLE
                urlBox.alpha = 1f
            }.start()
        }
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
        // 3.0: its page was in use until now.
        TabManager.leftTab(tab)
        // The page underneath needs no drawing (unless it is playing and should go on). 3.0: its
        // picture for the tab grid is taken first.
        val keepPlaying = prefs.keepPlaying && tab.playing
        captureThumbnail(tab) {
            if (!keepPlaying && tab.homeShown) tab.session?.let { if (it.isOpen) it.setActive(false) }
        }
        showToolbar()
        updateHome(tab)
        home.animate().cancel()
        home.alpha = 0f
        home.translationY = dp(24f)
        Motion.anim(home, Motion.PAGE_MS, Motion.page).alpha(1f).translationY(0f).withLayer().start()
        refreshToolbarNow()
    }

    private fun leaveHome(tab: Tab) {
        tab.homeShown = false
        activateIfSeen()
        updateHome(tab)
        refreshToolbarNow()
    }

    // ------------------------------------------------------------------ tab switcher

    private fun setupSwitcher(s: TabSwitcherView) {
        s.listener = object : TabSwitcherView.Listener {
            override fun onTabChosen(tab: Tab) {
                val switched = tab !== current
                if (switched) TabManager.select(tab)
                s.close(pageRect())
                // 3.0.3: once, the quicker way to do that (it was there all along, hardly found).
                if (switched && !prefs.hintBarSwipe && TabManager.swipeOrder(tab).size >= 2) {
                    prefs.hintBarSwipe = true
                    handler.postDelayed({ if (!isFinishing) snack("Tip: swipe the address bar sideways to go to the next or previous tab", long = true) }, 700)
                }
            }

            override fun onNewTab(incognito: Boolean) {
                s.close()
                TabManager.newTab(incognito = incognito)
            }

            override fun onNewTabInGroup(group: TabGroup) {
                s.close()
                TabManager.newTabInGroup(group)
            }

            // 3.0: not a page under the home page (pageOnScreen).
            override fun onSwitcherClosed() {
                activateIfSeen()
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
        val bar = visibleBarPx()
        val at = IntArray(2)
        val grid = IntArray(2)
        root.getLocationInWindow(at)
        switcher.getLocationInWindow(grid)
        val dy = at[1] - grid[1]
        return android.graphics.Rect(0, dy + if (bottomBar) 0 else bar, root.width, dy + root.height - if (bottomBar) bar else 0)
    }

    /**
     * 3.0: the tab being left while its picture for the tab grid is taken: the page view keeps its
     * page until then, and the new tab's goes on screen after ([syncSession]). Pictures were taken
     * after every page load before, each a full-screen read-back that held up the page's drawing.
     */
    private var leavingTab: Tab? = null

    /** What lets the left tab's page rest once its picture is taken (TabManager.select). */
    private var leavingDone: (() -> Unit)? = null

    override fun onLeavingTab(tab: Tab, done: () -> Unit) {
        val s = tab.session
        // Only a page on screen: the tab grid took its picture as it opened, and out of sight or under
        // the home page there is nothing to take.
        if (s == null || !s.isOpen || gecko.session !== s || tab.showsHome || switcherOpen || !visibleToUser || leavingTab != null) {
            done()
            return
        }
        // 3.0.13: nor a page playing a video: its picture comes out one colour and is thrown away
        // (TabRules.looksBlank), yet it costs a whole-screen copy from the graphics chip, which on a
        // 2 GB phone is short of memory just then, and the switch waited up to 0.4 s for it.
        if (tab.playing && tab.videoPlaying) {
            done()
            return
        }
        leavingTab = tab
        leavingDone = done
        val finish = object : Runnable {
            override fun run() {
                if (leavingTab !== tab) return
                handler.removeCallbacks(this)
                leavingTab = null
                leavingDone = null
                done()
                if (isDestroyed) return
                current?.let { syncSession(it) }
                if (coverWaitsForLeaving) {
                    coverWaitsForLeaving = false
                    // 3.0.11: and only once the new tab's page has drawn (stopCoverWait).
                    if (coverWaitsForPaint == null) fadePageCover()
                }
            }
        }
        // Not for long, whatever the engine does; the picture is shrunk and saved after.
        handler.postDelayed(finish, 400)
        Thumbnails.capture(gecko, tab, gecko.height - toolbarShown.roundToInt(), taken = { finish.run() })
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
            background = Ui.ripple(this@MainActivity, android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24f)
                setColor(color(Looks.accent.primary))
            }, 24)
            elevation = dp(6f)
            Ui.pressable(this, Motion.PRESS_BUTTON)
            addView(TextView(this@MainActivity).apply {
                text = "Continue from $position"
                Ui.size(this, Ui.Type.BODY, keepFont = true)
                setTextColor(color(Looks.accent.onPrimary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, dp(6f).toInt(), 0)
                playIcon(this, color(Looks.accent.onPrimary))
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
        lp.bottomMargin = dp(24f).toInt() + if (bottomBar) barPx else 0
        root.addView(chip, lp)
        chip.alpha = 0f
        chip.translationY = dp(16f)
        Motion.anim(chip, Motion.DIALOG, Motion.enter).alpha(1f).translationY(0f).start()
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
        Motion.anim(chip, Motion.SHORT, Motion.exit).alpha(0f).translationY(dp(16f)).withEndAction { root.removeView(chip) }.start()
    }

    /** The play mark before a chip's words, in the words' colour. */
    private fun playIcon(tv: TextView, ink: Int) {
        val size = dp(18f).toInt()
        val mark = Ui.icon(this, R.drawable.ic_play, ink)?.apply { setBounds(0, 0, size, size) }
        tv.setCompoundDrawablesRelative(mark, null, null, null)
        tv.compoundDrawablePadding = dp(6f).toInt()
    }

    override fun onTabSelected(tab: Tab) {
        selectionMenu.dismiss()
        // 3.0.9: a woken tab's picture, waiting for its page, doesn't stay over another tab's.
        tabSwipe.onTabShown(tab)
        // 3.0.11: nor does a new tab's cover (animateNewTab sets it again for another new tab below).
        if (coverWaitsForPaint != null && coverWaitsForPaint !== tab) stopCoverWait()
        // 3.0.10: the menu stays open under its sheets, but not over another tab (one reopened from a sheet).
        if (menuOpen && quickMenu.openedFor !== tab) quickMenu.close()
        updateSitePlayerChip()
        if (resumeChipFor != null && resumeChipFor !== tab) hideResumeChip(null)
        // What this tab's page asked while it wasn't on screen (an alert, a sign-in) shows now (2.6).
        tab.session?.let { s -> root.post { if (current === tab) com.swiftbrowser.tabs.SessionFactory.prompts.showWaiting(s) } }
        Engine.showTextScaleFor(UrlUtils.site(tab.url), tab.incognito)
        // 3.0.7: Lite mode's engine-wide parts (GIFs, fonts) as the site on screen has it.
        Engine.showLiteFor(UrlUtils.site(tab.url))
        val isNew = seenTabs.add(tab.id)
        // 3.0.4: its group strip, or none: the bar's height for its page is known before the page is shown.
        updateGroupStrip()
        syncSession(tab)
        crashView.visibility = if (tab.crashed) View.VISIBLE else View.GONE
        val wasFullscreen = fullscreenTab
        if (wasFullscreen != null && wasFullscreen !== tab) {
            exitFullscreen()
            // 3.0: its page leaves full screen too, so it isn't still in it (with the bars back) when you return.
            wasFullscreen.session?.let { if (it.isOpen && wasFullscreen.fullscreen) it.exitFullScreen() }
        }
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
        } else if (!tab.showsHome && TabManager.waitsForAdBlocker(tab)) {
            // 3.0.13: its page waits for the ad blocker (Swift is starting): Swift's colour until it is
            // drawn, not last time's picture (it showed, then the colour, then the page).
            tab.wokeUp = false
            showStartCover(tab)
        } else if (tab.wokeUp) {
            tab.wokeUp = false
            tabSwipe.coverUntilDrawn(tab)
        }
        updateAdBlockWait()
    }

    /** Shows the tab's engine session in the page view (or nothing for the home page). */
    private fun syncSession(tab: Tab) {
        // 3.0: not while the tab being left has its picture taken: this follows then (onLeavingTab).
        if (leavingTab != null) return
        val session = tab.session
        // 3.0.4: with room for its group strip (or without) before its page is in the view, so the
        // page is laid out once (the view hands the height to the page it gets). The page leaving goes
        // first (as setSession would let go of it), so it isn't laid out again for a bar it won't have.
        if (stripPx != stripWanted && gecko.session != null && gecko.session !== session) gecko.releaseSession()
        applyStripHeight()
        // 3.0: only an open one: a pop-up's is opened by the engine just after its tab is shown.
        if (session != null && session.isOpen) {
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
        // 3.0.4: the group's tabs came, went, moved, or it was renamed or recoloured.
        updateGroupStrip()
        if (switcherOpen) switcher.refresh()
    }

    private var styledTabCount: Long? = null

    /** 3.0.4: the tab count's own outline, recoloured in place (also while the bar's colours change). */
    private var tabCountShape: android.graphics.drawable.GradientDrawable? = null

    /**
     * The tab button takes the colour of the open tab's group, so you can tell you're in one. 3.0.4:
     * otherwise the bar's colours (a website's too), and on a website's colour the group's only where
     * it stands out.
     */
    private fun styleTabCount() {
        val tab = current
        val group = tab?.let { TabManager.groupOf(it) }
        val incognito = tab?.incognito == true
        val bar = barShown
        val plain = bar?.text ?: color(if (incognito) R.color.incognito_text else R.color.text_primary)
        val text = group?.let { g ->
            val c = color(TabGroup.colorRes(g.color))
            if (bar != null && bar.site) BarTones.onBar(c, bar.bar, bar.text) else c
        } ?: plain
        val outline = if (group != null || incognito) text else bar?.icon ?: color(R.color.text_secondary)
        val key = (text.toLong() shl 32) or (outline.toLong() and 0xFFFFFFFFL)
        if (key == styledTabCount) return
        styledTabCount = key
        tabsCount.setTextColor(text)
        val shape = tabCountShape ?: tabCountBackground(this, outline).also { tabCountShape = it }
        shape.setStroke((2 * resources.displayMetrics.density).roundToInt(), outline)
        if (tabsCount.background !== shape) tabsCount.background = shape
        // Its spoken label (how many tabs) is set in onTabsChanged.
    }

    override fun onTabUpdated(tab: Tab) {
        // 3.0.13: a page that waited for the ad blocker under Swift's colour now loads: from now on the
        // cover gives up after a while, as a new tab's does (not while it waits, however long that is).
        if (tab === coverWaitsForPaint && !coverLimitRunning && !TabManager.isRestoring(tab)) {
            coverLimitRunning = true
            handler.removeCallbacks(coverPaintTimeout)
            handler.postDelayed(coverPaintTimeout, START_PAGE_LIMIT_MS)
        }
        if (tab !== current) {
            // 3.0.4: another tab of the open tab's group has a new title or page: its chip follows.
            if (tab.groupId != null && tab.groupId == current?.groupId) showGroupStrip()
            return
        }
        // 3.0.4: a new tab of a group that leaves its home page for a page makes room for the strip
        // first (syncSession), and the strip follows the page's title.
        updateGroupStrip()
        syncSession(tab)
        updateSitePlayerChip()
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
        Engine.showTextScaleFor(UrlUtils.site(tab.url), tab.incognito)
        // 3.0.7: Lite mode's engine-wide parts (GIFs, fonts) as the site on screen has it.
        Engine.showLiteFor(UrlUtils.site(tab.url))
        if (tab.loading != shownLoading || pageStarted) {
            com.swiftbrowser.engine.DataSaver.onSiteShown(UrlUtils.site(tab.url))
            if (!tab.loading) offerImages(tab)
        }
        shownLoading = tab.loading
        updateHome(tab)
        if (resumeChipFor != null && (resumeChipFor !== tab || resumeChipPage != tab.url.substringBefore('#'))) hideResumeChip(null)
        if (pageStarted) refreshToolbarNow() else requestToolbarRefresh()
        if (switcherOpen) switcher.refreshTab(tab)
        // 3.0.13: Load now only while the open page waits for the ad blocker.
        if (loadNowPill != null || !startupDone()) updateAdBlockWait()
        if (unfilteredUntil != 0L && tab.badge == "!") checkUnfiltered()
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
            updateGroupStrip()
            // 3.0.4: the crash view is Swift's: Swift's colours.
            updateBarColours(animate = true)
        }
    }

    /** 3.0.4: the open tab's page said its colour (or that it has none): the bar takes it. */
    override fun onSiteColour(tab: Tab) {
        if (tab === current) updateBarColours(animate = true)
    }

    override fun onPlayingChanged(tab: Tab) {
        // 3.0.4: the tab grid's speakers follow any tab's sound (and its mute).
        if (switcherOpen) switcher.refreshSound(tab)
        if (tab !== current) return
        updateKeepScreenOn()
        updateSitePlayerChip()
    }

    // ------------------------------------------------------------------ Swift's player for site videos (2.9)

    private var sitePlayerChip: View? = null

    /** The video a site plays, as Swift's player can take it: its stream (HLS) first, else the biggest video file. */
    private fun siteVideo(tab: Tab): com.swiftbrowser.media.MediaItem? =
        tab.media.lastOrNull { it.kind == com.swiftbrowser.media.MediaItem.Kind.HLS || it.kind == com.swiftbrowser.media.MediaItem.Kind.DASH }
            ?: tab.media.filter { it.kind == com.swiftbrowser.media.MediaItem.Kind.FILE && !it.mime.startsWith("audio") }.maxByOrNull { it.size }

    /**
     * With "Swift player for website videos" on, a playing video gets a small button that opens it
     * in Swift's own player (smoother on older phones, with gestures and subtitles).
     */
    private fun updateSitePlayerChip() {
        val tab = current
        // Once a video has played on the page (it stays offered while the page is open).
        val wanted = prefs.sitePlayer && tab != null && !tab.showsHome && (tab.videoPlaying || fullscreenTab === tab) && siteVideo(tab) != null
        if (!wanted) {
            sitePlayerChip?.let { chip ->
                sitePlayerChip = null
                Motion.anim(chip, Motion.SHORT, Motion.exit).alpha(0f).withEndAction { root.removeView(chip) }.start()
            }
            return
        }
        if (sitePlayerChip != null) return
        val chip = TextView(this).apply {
            text = "Swift player"
            Ui.size(this, Ui.Type.LABEL)
            setTextColor(color(Looks.accent.onPrimary))
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
            background = Ui.ripple(this@MainActivity, android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(color(Looks.accent.primary))
            }, 20)
            elevation = dp(2f)
            contentDescription = "Play this video in Swift's player"
            playIcon(this, color(Looks.accent.onPrimary))
            Ui.pressable(this, Motion.PRESS_BUTTON)
            setOnClickListener { playInSwiftPlayer() }
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40f).toInt(), android.view.Gravity.TOP or android.view.Gravity.END)
        lp.topMargin = sitePlayerChipTop()
        lp.marginEnd = dp(16f).toInt()
        root.addView(chip, lp)
        chip.alpha = 0f
        Motion.anim(chip, Motion.DIALOG, Motion.enter).alpha(1f).start()
        sitePlayerChip = chip
    }

    /** How far the Swift player button is from the top: under the bar, or from the screen's edge in full screen. */
    private fun sitePlayerChipTop() = dp(16f).toInt() + if (bottomBar || fullscreenTab != null) 0 else barPx

    /**
     * Hands the site's video to Swift's player, with what the site needs (where it was shown, the
     * browser's name). [chosen]: one picked in "Videos on this page" (3.0.1).
     */
    fun playInSwiftPlayer(chosen: com.swiftbrowser.media.MediaItem? = null) {
        val tab = current ?: return
        val video = chosen ?: siteVideo(tab) ?: return
        Helper.lookupHeaders(video.url) { found ->
            val headers = HashMap<String, String>()
            video.headers.forEach { (k, v) -> headers[k] = v }
            found.forEach { (k, v) -> headers[k] = v }
            if (headers.keys.none { it.equals("referer", true) }) headers["Referer"] = video.frameUrl.ifBlank { video.pageUrl }
            // The same browser name the page saw (3.0: a newer Firefox version when that's chosen).
            // 3.0.1: a desktop tab's video goes with the desktop browser name, as its page did.
            if (headers.keys.none { it.equals("user-agent", true) }) headers["User-Agent"] = com.swiftbrowser.engine.UserAgent.forTab(tab.desktop)
            // The page stops, so the two don't play at once.
            tab.mediaSession?.pause()
            PlayerActivity.openStream(this, video.url, tab.title.ifBlank { null }, headers, tab.incognito, video.kind == com.swiftbrowser.media.MediaItem.Kind.DASH)
        }
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

    override fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, camera: Boolean, callback: (List<Uri>?) -> Unit) {
        // Swift's Files, unless turned off; a page asking for a new photo goes to Android's, where the camera is.
        val intent = if (prefs.uploadFromFiles && !camera) FilePick.intent(this, mimeTypes, multiple) else Intent(Intent.ACTION_GET_CONTENT).apply {
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
        // 3.0.5: the home page's picture also fills the bar's room, at whichever end that is.
        home.setBarReach(if (bottomBar) 0 else toolbarPx, if (bottomBar) toolbarPx else 0)
        val edge = if (bottomBar) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
        for (id in intArrayOf(R.id.toolbar_divider, R.id.progress)) {
            val v = findViewById<View>(id)
            (v.layoutParams as FrameLayout.LayoutParams).gravity = edge
            v.layoutParams = v.layoutParams
        }
        // Views that fill the page area leave the toolbar's room at its end of the screen. 3.0.4: the
        // home page, the crash view and the suggestions show without the group strip; the covers
        // over the page leave its room too (placePageCovers).
        for (v in listOf<View>(home, crashView, suggestionsList)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = if (bottomBar) 0 else toolbarPx
            lp.bottomMargin = if (bottomBar) toolbarPx else 0
            v.layoutParams = lp
        }
        placePageCovers()
        placeGroupStrip()
        // With the bar below, the best suggestion is the one next to it.
        (suggestionsList.layoutManager as? LinearLayoutManager)?.reverseLayout = bottomBar
        // 3.0.12: nothing at the bottom carries the keyboard's glide on to the bar's new end.
        if (!bottomBar) suggestionsList.translationY = 0f
        applyToolbarShown(toolbarShown)
        // 3.0.12: Android's bottom bar takes the colour of what is now above it.
        barShown?.let { paintBar(it) }
    }

    /** The new tab's cover and the next tab's picture (swiping the bar) cover the page: below the bar and the strip. */
    private fun placePageCovers() {
        for (v in listOf<View>(pageCover, findViewById(R.id.swipe_peek))) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val top = if (bottomBar) 0 else barPx
            val bottom = if (bottomBar) barPx else 0
            if (lp.topMargin == top && lp.bottomMargin == bottom) continue
            lp.topMargin = top
            lp.bottomMargin = bottom
            v.layoutParams = lp
        }
    }

    /**
     * [shown]: how much of the bar shows, 0 to [barPx]. 3.0.4: the address bar and the group strip
     * move as one: at the top the bar goes first and the strip after it, at the bottom the same way
     * down; the page (at the top) or the engine's clipping (both) follow the whole height.
     */
    private fun applyToolbarShown(shown: Float) {
        toolbarShown = shown
        val bar = barPx
        if (shown >= bar) hiddenBySwipe = false
        if (bottomBar) {
            // 3.0.12: plus how far the keyboard's coming or going still leaves it from its place.
            appBar.translationY = bar - shown + bottomShift
            gestureLayer.translationY = 0f
        } else {
            appBar.translationY = shown - bar
            gestureLayer.translationY = shown
        }
        groupStrip?.translationY = appBar.translationY
        // The engine lays pages out for a fully shown toolbar (see setDynamicToolbarMaxHeight); a
        // negative clipping moves elements fixed to the bottom of the screen down by the hidden part.
        gecko.setVerticalClipping((shown - bar).roundToInt())
    }

    /**
     * 3.0.12: the keyboard (or Android's bars) moved the bottom of the screen ([BottomGlide]): what sits
     * at the bottom is drawn [y] from its new place, easing to 0, so it rides up with the keyboard and
     * down again instead of jumping: the find bar always, and with the bar at the bottom the address
     * bar, the group strip on it and the suggestions over it; the tab grid's own bars too.
     */
    private fun applyBottomShift(y: Float) {
        bottomShift = y
        findBarBox.translationY = y
        if (bottomBar) {
            appBar.translationY = barPx - toolbarShown + y
            groupStrip?.translationY = appBar.translationY
            suggestionsList.translationY = y
        }
        switcherView?.setBottomShift(y)
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
        val target = if (show) barPx.toFloat() else 0f
        if (toolbarWanted == show && toolbarShown == target) return
        settleAnim?.cancel()
        toolbarWanted = show
        applyToolbarShown(target)
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
                settleToolbar(true)
                return
            }
        }
        // Back at the top after a fling upwards (not the engine making up for the toolbar's own move).
        if (!toolbarWanted && scrollY <= barPx / 2f && last >= 0f && scrollY < last && !gestureLayer.fingerDown &&
            settleAnim == null && SystemClock.uptimeMillis() - lastToolbarMoveAt > 400
        ) {
            settleToolbar(true)
        }
    }

    /**
     * Chrome-like: the toolbar slides by exactly as much as the finger moves the page, and only
     * while the page itself scrolls. It is never left half-way: see [onFingerUp].
     */
    private fun onFingerMove(dy: Float) {
        if (!canAutoHideToolbar() || !gecko.touchScrollsPage()) return
        // Only once the page is really scrolling, not while the finger is still deciding.
        if (SystemClock.uptimeMillis() - lastUserScrollAt > 300) return
        val target = (toolbarShown + dy).coerceIn(0f, barPx.toFloat())
        if (target == toolbarShown) return
        settleAnim?.cancel()
        settleAnim = null
        fingerDirection = if (dy < 0) -1 else 1
        moveToolbar(target)
    }

    /** The finger let go half-way: the toolbar finishes the way it was going. */
    private fun onFingerUp() {
        if (toolbarShown <= 0f || toolbarShown >= barPx) return
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
        val target = if (show) barPx.toFloat() else 0f
        settleAnim?.cancel()
        if (toolbarShown == target) {
            settleAnim = null
            return
        }
        val distance = abs(target - toolbarShown) / barPx
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

    // ------------------------------------------------------------------ 3.0.4: the group strip

    /** What the group strip does for the open tab now (TabRules.strip). */
    private fun stripState(): TabRules.Strip {
        val tab = current ?: return TabRules.Strip.NONE
        return TabRules.strip(
            enabled = prefs.groupStrip,
            grouped = TabManager.groupOf(tab) != null,
            hasPage = !tab.isHome,
            fullScreen = fullscreenTab != null,
            pageShown = !tab.showsHome && !tab.crashed,
            typing = editing,
        )
    }

    /**
     * The open tab's group as a strip of its tabs by the address bar (Settings → Browsing & tabs).
     * Its room is part of the bar while the tab is in a group and has a page, also while the strip
     * makes way for the home page, the crash view or typing an address: the page isn't laid out
     * again for those. Cheap when nothing changed (it comes with every update of the open tab).
     */
    private fun updateGroupStrip() {
        // The bar's views are there from the start of build (what TabManager says comes after).
        if (!::gecko.isInitialized) return
        stripWanted = if (stripState() == TabRules.Strip.NONE) 0 else dp(STRIP_DP)
        // Not while the view still shows another tab's page (the tab being left, for its picture):
        // the new page's room comes with it (syncSession).
        if (leavingTab == null && (gecko.session == null || gecko.session === current?.session)) applyStripHeight()
        showGroupStrip()
    }

    /** The bar's room for the engine with the strip's ([stripWanted]); the bar stays shown or hidden as it was. */
    private fun applyStripHeight() {
        if (stripPx == stripWanted) return
        stripPx = stripWanted
        placePageCovers()
        if (fullscreenTab == null) {
            settleAnim?.cancel()
            settleAnim = null
            gecko.setDynamicToolbarMaxHeight(barPx)
            applyToolbarShown(if (toolbarWanted) barPx.toFloat() else 0f)
        }
        showGroupStrip()
    }

    /** Shows the strip with the open tab's group as it is now, or hides it. */
    private fun showGroupStrip() {
        if (!::gecko.isInitialized) return
        // Its room is still to be made (or taken away) with the next page: it stays as it is until then.
        if (stripPx != stripWanted) return
        val tab = current
        val group = tab?.let { TabManager.groupOf(it) }
        if (tab == null || group == null || stripPx == 0 || stripState() != TabRules.Strip.SHOWN) {
            // No line under the empty bar over the home page (its address box is hidden there).
            toolbarDivider.visibility = if (home.visibility == View.VISIBLE && !editing) View.INVISIBLE else View.VISIBLE
            groupStrip?.let { if (it.visibility != View.GONE && !stripLeaving) fadeStripAway(it) }
            return
        }
        // The strip's own hairline is at the page's edge; the bar's would be between the two.
        toolbarDivider.visibility = View.INVISIBLE
        val strip = groupStrip ?: makeGroupStrip()
        strip.show(group, TabManager.tabsIn(group), tab)
        if (strip.visibility != View.VISIBLE || stripLeaving) {
            stripLeaving = false
            strip.animate().cancel()
            if (strip.visibility != View.VISIBLE) {
                strip.translationY = appBar.translationY
                strip.alpha = 0f
                strip.visibility = View.VISIBLE
            }
            Motion.anim(strip, Motion.SHORT, Motion.enter).alpha(1f).withLayer().start()
        }
    }

    /** The strip is fading away: for the bar's height it has gone already (see [visibleBarPx]). */
    private var stripLeaving = false

    private fun fadeStripAway(strip: GroupStrip) {
        stripLeaving = true
        strip.animate().cancel()
        Motion.anim(strip, Motion.PAGE_OUT_MS, Motion.exit).alpha(0f).withLayer().withEndAction {
            stripLeaving = false
            strip.visibility = View.GONE
            strip.alpha = 1f
        }.start()
    }

    private fun makeGroupStrip(): GroupStrip {
        val strip = GroupStrip(this)
        strip.listener = object : GroupStrip.Listener {
            override fun onTabChosen(tab: Tab) {
                if (tab !== current && tab in TabManager.tabs) TabManager.select(tab)
            }

            // Its usual menu, as when its card is held in the tab grid.
            override fun onTabHeld(tab: Tab) = TabMenu.show(this@MainActivity, tab)

            override fun onNewTab(group: TabGroup) {
                TabManager.newTabInGroup(group)
            }
        }
        // Above the page's covers, as the bar is, and without a shadow.
        strip.elevation = appBar.elevation
        strip.outlineProvider = null
        strip.visibility = View.GONE
        val content = appBar.parent as android.view.ViewGroup
        content.addView(strip, content.indexOfChild(appBar) + 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(STRIP_DP)))
        groupStrip = strip
        placeGroupStrip()
        return strip
    }

    /** The strip on the page's side of the bar: below it at the top of the screen, above it at the bottom. */
    private fun placeGroupStrip() {
        val strip = groupStrip ?: return
        val lp = strip.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (bottomBar) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
        lp.topMargin = if (bottomBar) 0 else toolbarPx
        lp.bottomMargin = if (bottomBar) toolbarPx else 0
        strip.layoutParams = lp
        strip.setLineAtTop(bottomBar)
        strip.translationY = appBar.translationY
        // 3.0.4: in the bar's colours and style (a pill of its own when the bar floats).
        barShown?.let { strip.paint(it) }
    }

    /** How much of the bar shows (px): the group strip's room counts only while the strip itself shows. */
    private fun visibleBarPx(): Int {
        val hiddenStrip = if (stripPx > 0 && (groupStrip?.visibility != View.VISIBLE || stripLeaving)) stripPx else 0
        return (toolbarShown.roundToInt() - hiddenStrip).coerceAtLeast(0)
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
                    toolbarWanted && toolbarShown >= barPx
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
                // 3.0.12: a still shape turned by the finger, grey until a let-go would reload.
                ptrIndicator.still = true
                ptrIndicator.colour = color(if (offset >= trigger) Looks.accent.primary else R.color.text_secondary)
                if (offset >= trigger && !pullArmed) Haptics.tick(ptrIndicator)
                pullArmed = offset >= trigger
            }

            override fun onPullEnd(distance: Float) {
                pullArmed = false
                if (pullOffset(distance) >= dp(64f)) startPullRefresh() else hidePullIndicator()
            }
        }
    }

    /** The finger moves twice as far as the circle, up to a limit. */
    private fun pullOffset(distance: Float) = (distance * 0.5f).coerceAtMost(dp(110f))

    /** Circle tucked under the bottom edge of the toolbar (3.0.4: of the group strip below it). */
    private fun ptrHiddenY() = (if (bottomBar) 0 else barPx) - dp(16f) - dp(40f)

    private fun startPullRefresh() {
        val tab = current ?: return hidePullIndicator()
        ptrRefreshing = true
        ptrStartedAt = System.currentTimeMillis()
        ptrIndicator.colour = color(Looks.accent.primary)
        Motion.anim(ptrIndicator, Motion.SHORT).translationY(ptrHiddenY() + dp(56f)).scaleX(1f).scaleY(1f).alpha(1f).start()
        // 3.0.12: the shape moves (changes form and turns by itself) until the reload has started.
        ptrIndicator.still = false
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
        ptrIndicator.animate().cancel()
        ptrIndicator.visibility = View.GONE
    }

    private fun hidePullIndicator() {
        if (ptrIndicator.visibility != View.VISIBLE) return
        ptrIndicator.animate().cancel()
        Motion.anim(ptrIndicator, Motion.SHORT, Motion.exit).scaleX(0.3f).scaleY(0.3f).alpha(0f).withEndAction {
            if (!ptrRefreshing) ptrIndicator.visibility = View.GONE
        }.start()
    }

    // ------------------------------------------------------------------ new tab animation

    private fun animateNewTab(tab: Tab) {
        bumpTabCount()
        if (tab.isHome) {
            home.animate().cancel()
            home.alpha = 0f
            home.translationY = dp(24f)
            Motion.anim(home, Motion.PAGE_MS, Motion.page).alpha(1f).translationY(0f).withLayer().start()
        } else {
            // The page view can't be animated itself; fade a cover off it instead.
            pageCover.setBackgroundColor(color(if (tab.incognito) R.color.incognito_bg else R.color.page_bg))
            pageCover.animate().cancel()
            pageCover.alpha = 1f
            pageCover.translationY = 0f
            pageCover.visibility = View.VISIBLE
            // 3.0: while the tab left has its picture taken (onLeavingTab), the view still shows that
            // tab's page: the cover stays whole until the new tab's page is in it.
            coverWaitsForLeaving = leavingTab != null
            // 3.0.11: and until the new page has really drawn, not for a set time: the cover used to fade
            // at once and the engine's own colour (grey in the dark look) showed until the site's first
            // paint. Not for ever: a page that never paints gives the cover up after NEW_TAB_COVER_MAX_MS.
            coverWaitsForPaint = tab
            coverPaintSeen = false
            // A page still loading must draw some of its content (its first frame is only the engine's
            // background); one that loaded behind, opened in the background, is there with its first frame.
            coverNeedsContent = tab.loading
            handler.removeCallbacks(coverPaintTimeout)
            // 3.0.13: a page that first waits for the ad blocker (a link from another app as Swift
            // starts) gets its time limit once it loads (onTabUpdated): it ran out while it waited.
            coverLimitRunning = !TabManager.isRestoring(tab)
            if (coverLimitRunning) handler.postDelayed(coverPaintTimeout, NEW_TAB_COVER_MAX_MS)
        }
    }

    /**
     * 3.0.13: [tab]'s page waits for the ad blocker as Swift starts (it used to be under the start-up
     * screen with its loader): its area shows Swift's own colour, as a new tab's does, under the bar
     * with its loading line, until the page is drawn. No time limit while it waits ([onTabUpdated]
     * starts one once the page loads); Load now comes as a message ([updateAdBlockWait]).
     */
    private fun showStartCover(tab: Tab) {
        pageCover.setBackgroundColor(color(if (tab.incognito) R.color.incognito_bg else R.color.page_bg))
        pageCover.animate().cancel()
        pageCover.alpha = 1f
        pageCover.translationY = 0f
        pageCover.visibility = View.VISIBLE
        coverWaitsForLeaving = false
        coverWaitsForPaint = tab
        coverPaintSeen = false
        coverNeedsContent = true
        handler.removeCallbacks(coverPaintTimeout)
        coverLimitRunning = !TabManager.isRestoring(tab)
        if (coverLimitRunning) handler.postDelayed(coverPaintTimeout, START_PAGE_LIMIT_MS)
    }

    /** 3.0.11: the new tab whose page the cover waits for (see [animateNewTab]); null when it doesn't wait. */
    private var coverWaitsForPaint: Tab? = null
    private var coverPaintSeen = false
    private var coverNeedsContent = true
    private val coverPaintTimeout = Runnable { stopCoverWait() }

    /** 3.0.13: the cover's time limit runs (a start's cover gets one only once its page loads). */
    private var coverLimitRunning = false

    /**
     * 3.0.11: the waited-for tab's page drew something. The engine says so before the picture reaches
     * the screen (by over a second on a slow GPU), so, as the start-up screen does, the cover goes once
     * the page can be read back from the screen, or after START_PAGE_DRAW_MS whatever happens.
     */
    private fun coverPagePainted(tab: Tab) {
        if (tab !== coverWaitsForPaint || coverPaintSeen) return
        coverPaintSeen = true
        handler.removeCallbacks(coverPaintTimeout)
        handler.postDelayed(coverPaintTimeout, START_PAGE_DRAW_MS)
        if (tab.session?.isOpen == true && gecko.session === tab.session) {
            gecko.capturePixels().accept({ picture ->
                picture?.recycle()
                if (coverWaitsForPaint === tab) stopCoverWait()
            }, { _ -> if (coverWaitsForPaint === tab) stopCoverWait() })
        } else {
            stopCoverWait()
        }
    }

    /** 3.0.11: the cover waits no more for the new tab's page: it fades, unless the tab left is still having its picture taken. */
    private fun stopCoverWait() {
        handler.removeCallbacks(coverPaintTimeout)
        if (coverWaitsForPaint == null) return
        coverWaitsForPaint = null
        coverLimitRunning = false
        if (!coverWaitsForLeaving && !isDestroyed) fadePageCover()
    }

    /** 3.0.10: the tab whose site, opened from the home page, the home page waits for before fading. */
    private var homeLeaving: Tab? = null
    private val homeLeaveTimeout = Runnable { fadeHomeAway() }

    private fun fadeHomeAway() {
        handler.removeCallbacks(homeLeaveTimeout)
        if (homeLeaving == null) return
        homeLeaving = null
        home.animate().cancel()
        Motion.anim(home, Motion.PAGE_MS, Motion.exit).alpha(0f).withEndAction {
            if (home.leaving && homeLeaving == null) hideHomeNow()
        }.start()
    }

    private fun hideHomeNow() {
        handler.removeCallbacks(homeLeaveTimeout)
        homeLeaving = null
        home.animate().cancel()
        if (home.visibility == View.VISIBLE) home.releasePictures()
        home.visibility = View.GONE
        home.alpha = 1f
        home.leaving = false
        // The line under the bar comes back with the page.
        showGroupStrip()
    }

    /** 3.0: the new tab's cover fades once the tab left has its picture (see [animateNewTab]). */
    private var coverWaitsForLeaving = false

    private fun fadePageCover() {
        Motion.anim(pageCover, Motion.PAGE_MS, Motion.page).alpha(0f).translationY(0f).withLayer().withEndAction {
            pageCover.visibility = View.GONE
        }.start()
    }

    private fun bumpTabCount() = Motion.bump(tabsCount, 1.2f)

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

    private fun torrentAdded() = snack("Added to your downloads", "View") { openDownloads() }

    /** A .torrent file a page sent: read (it is small), then its panel. */
    private fun readTorrent(tab: Tab, response: WebResponse) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    response.body?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            // No torrent file is this big: something else.
                            if (out.size() > 20 * 1024 * 1024) return@use null
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            closeIfOnlyDownload(tab, response.uri)
            // 3.0: from an incognito tab, the panel says the torrent isn't incognito.
            if (bytes == null) snack("Couldn't read this torrent file") else TorrentSheet.file(this@MainActivity, bytes, tab.incognito) { torrentAdded() }
        }
    }

    override fun onExternalApp(tab: Tab, url: String) {
        if (tab !== current) return
        // 2.9.2: magnet links are torrents, which Swift downloads itself.
        if (url.startsWith("magnet:", ignoreCase = true)) {
            TorrentSheet.magnet(this, url, tab.incognito) { torrentAdded() }
            return
        }
        val intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (e: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        // 3.0: as in Chrome, a page can't hand another app a way into Swift's own files: no
        // permissions to read or write what a link points to, nothing attached, and no content: or
        // file: links at all (they could reach the Private folder, downloads, problem reports).
        intent.flags = intent.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            ).inv()
        intent.clipData = null
        if (UrlUtils.isLocalFile(intent.scheme)) {
            L.w("A page's link to an app pointed at a file on the phone: not opened")
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && UrlUtils.isHttp(fallback)) TabManager.load(tab, fallback) else snack("This link can't be opened")
            return
        }
        val resolved = packageManager.resolveActivity(intent, 0)
        if (resolved == null) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && UrlUtils.isHttp(fallback)) TabManager.load(tab, fallback)
            else snack("No app on your phone can open this link")
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
        // 2.9.2: a .torrent file opens the torrent panel instead of being saved.
        if (mime == "application/x-bittorrent" || name.endsWith(".torrent", ignoreCase = true)) {
            readTorrent(tab, response)
            return
        }
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
            NotificationAccess.askOnce(this)
        } else {
            requestAndroidPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                action()
                // 3.0.6: a download's progress and "Downloaded" are notifications (Android 13+ asks first).
                NotificationAccess.askOnce(this)
            }
        }
    }

    /** Downloads a link or media file found on the page. */
    fun downloadUrl(tab: Tab, url: String, nameHint: String? = null, again: Boolean = false) {
        if (url.startsWith("blob:") || url.startsWith("mediasource:")) {
            snack("This video is streamed in pieces. Use the download button in the address bar.")
            return
        }
        if (url.startsWith("data:") || !UrlUtils.isHttp(url)) {
            snack("This link can't be downloaded")
            return
        }
        if (!again && sameDownload(tab, url) { downloadUrl(tab, url, nameHint, again = true) }) return
        Helper.lookupHeaders(url) { found ->
            val headers = HashMap(found)
            if (!headers.containsKey("referer") && UrlUtils.isHttp(tab.url)) headers["referer"] = tab.url
            // 3.0.1: the browser name the page saw (a desktop tab's is the desktop one).
            if (headers.keys.none { it.equals("user-agent", true) }) headers["user-agent"] = com.swiftbrowser.engine.UserAgent.forTab(tab.desktop)
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

    /**
     * 3.0.1: [url] was downloaded before in [tab]'s profile (incognito or not). Still going: Downloads
     * shows it instead of a second copy. Finished, with its file still there: Show or Download again
     * ([again]). True when that was handled here; false: download as usual.
     */
    fun sameDownload(tab: Tab, url: String, again: () -> Unit): Boolean {
        val item = Downloads.findSame(url, tab.profileId, tab.incognito) ?: return false
        val there = item.filePath.isNotEmpty() && java.io.File(item.filePath).exists()
        return when (com.swiftbrowser.download.DownloadRules.again(item.status, there)) {
            com.swiftbrowser.download.DownloadRules.Again.NEW -> false
            com.swiftbrowser.download.DownloadRules.Again.BUSY -> {
                snack("${item.fileName} is already downloading", "View") { openDownloads() }
                true
            }
            com.swiftbrowser.download.DownloadRules.Again.DONE -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Downloaded before")
                    .setMessage("${item.fileName} is already in your downloads.")
                    .setPositiveButton("Download again") { _, _ -> again() }
                    .setNeutralButton("Show") { _, _ -> openDownloads() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showImmersive()
                true
            }
        }
    }

    fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
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
            // 3.0.11: a look at the page in a sheet, without a tab.
            if (UrlUtils.isHttp(link)) actions += Sheets.Action(R.drawable.ic_visibility, "Preview") { LinkPreview.show(this, tab, link) }
            actions += Sheets.Action(R.drawable.ic_copy, "Copy link") { copy(link) }
            element.textContent?.trim()?.takeIf { it.isNotEmpty() && it != link }?.let { text ->
                actions += Sheets.Action(R.drawable.ic_link_text, "Copy link text") { copy(text) }
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
                    // 3.0.11: find where else it is, what it shows (only a picture on the web has an address to send).
                    if (UrlUtils.isHttp(src)) actions += Sheets.Action(R.drawable.ic_search, "Search this picture") {
                        TabManager.newTab(com.swiftbrowser.core.SearchEngine.current.pictureSearchUrl(src), parent = tab)
                    }
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
        if (Messages.saysCopied) snack("Copied")
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
        // The Swift player button moves with the bars (2.9).
        sitePlayerChip?.let { chip ->
            (chip.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = sitePlayerChipTop()
                chip.layoutParams = lp
            }
        }
        updateSitePlayerChip()
    }

    private fun enterFullscreen(tab: Tab) {
        // 3.0.11: Swift no longer turns the screen itself ("Turn full-screen videos sideways" is gone):
        // the phone's own auto-rotate decides.
        coverFullscreenSwitch(turns = false)
        quickMenu.close()
        stopEditing()
        cancelPullRefresh()
        applyToolbarShown(0f)
        fullscreenTab = tab
        appBar.visibility = View.GONE
        // 3.0.4: the group strip goes too (its room with the bar's, below).
        updateGroupStrip()
        // The video gets the whole screen: no room is kept for the toolbar, as in Firefox. Without
        // this the page stays laid out below a toolbar-high empty strip.
        gecko.setDynamicToolbarMaxHeight(0)
        gecko.setVerticalClipping(0)
        findBar.visibility = View.GONE
        videoBtn.visibility = View.GONE
        hideSystemBars()
        gestureLayer.setVideoMode(true, swipes = prefs.videoGestures, taps = prefs.videoTaps)
        updateKeepScreenOn()
    }

    private fun exitFullscreen() {
        handler.removeCallbacks(fullscreenBackFallback)
        if (fullscreenTab == null) return
        coverFullscreenSwitch(turns = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        fullscreenTab = null
        appBar.visibility = View.VISIBLE
        // 3.0.4: with the group strip's room again when the tab is in a group (it shows with updateAll, below).
        stripWanted = if (stripState() == TabRules.Strip.NONE) 0 else dp(STRIP_DP)
        stripPx = stripWanted
        placePageCovers()
        gecko.setDynamicToolbarMaxHeight(barPx)
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

    // ------------------------------------------------------------------ 3.0.10: full screen through black

    // A view in the page's own box (where the new tab's cover is), not over the window: with the
    // page's surface filling the screen, neither a drawing over the window nor a view added to it showed.
    private var fullscreenCover: View? = null
    private var coveredSize = 0L
    private val liftFullscreenCover = Runnable { fadeFullscreenCover() }
    private val fullscreenSizeWatch = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val size = (v.width.toLong() shl 32) or v.height.toLong()
        if (size != coveredSize) {
            coveredSize = size
            // The window has its new size: the page gets a moment to draw in it.
            handler.removeCallbacks(liftFullscreenCover)
            handler.postDelayed(liftFullscreenCover, (FULLSCREEN_SETTLE_MS * animationScale()).toLong())
        }
    }

    /**
     * Into or out of full screen: black over the screen while the phone turns and the page lays
     * itself out again at the new size (that jumped in view), then the video fades in.
     */
    private fun coverFullscreenSwitch(turns: Boolean) {
        if (!Motion.enabled(this) || !built) return
        val decor = window.decorView
        val host = pageCover.parent as? android.view.ViewGroup ?: return
        val cover = fullscreenCover ?: View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also {
            host.addView(it, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreenCover = it
        }
        cover.animate().cancel()
        cover.animate().alpha(1f).setDuration(90).start()
        coveredSize = (decor.width.toLong() shl 32) or decor.height.toLong()
        decor.removeOnLayoutChangeListener(fullscreenSizeWatch)
        decor.addOnLayoutChangeListener(fullscreenSizeWatch)
        handler.removeCallbacks(liftFullscreenCover)
        // Lifted after the new size (above), or after this long at most (shorter when the phone doesn't turn).
        val most = if (turns) FULLSCREEN_COVER_MAX_MS else FULLSCREEN_COVER_STILL_MS
        handler.postDelayed(liftFullscreenCover, (most * animationScale()).toLong())
    }

    /** The phone's animation speed (1: normal), so the cover's waits keep step with its fades. */
    private fun animationScale(): Float =
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceIn(0f, 10f)

    private fun fadeFullscreenCover() {
        handler.removeCallbacks(liftFullscreenCover)
        window.decorView.removeOnLayoutChangeListener(fullscreenSizeWatch)
        val cover = fullscreenCover ?: return
        cover.animate().cancel()
        cover.animate().alpha(0f).setDuration(220).setInterpolator(Motion.standard).withEndAction {
            if (fullscreenCover === cover) {
                (cover.parent as? android.view.ViewGroup)?.removeView(cover)
                fullscreenCover = null
            }
        }.start()
    }

    // ------------------------------------------------------------------ 3.0.10: the dark/light picture

    /** The page reloads in the new colours (onCreate after a dark/light switch): the picture waits for it. */
    private var themeReloading = false
    private var themePictureFade: (() -> Unit)? = null
    private val themePictureTimeout = Runnable { releaseThemePicture() }

    /** The page view and where it is in the window, for the picture taken before a switch (null: no page in sight). */
    fun pageForPicture(): Pair<SwiftGeckoView, android.graphics.Rect>? {
        if (!::gecko.isInitialized || gecko.session == null || home.visibility == View.VISIBLE || gecko.visibility != View.VISIBLE) return null
        if (gecko.width <= 0 || gecko.height <= 0) return null
        val at = IntArray(2)
        gecko.getLocationInWindow(at)
        return gecko to android.graphics.Rect(at[0], at[1], at[0] + gecko.width, at[1] + gecko.height)
    }

    /** ThemeFade's picture fades once the reloaded page is drawn (or after a moment); false: no reload to wait for. */
    fun afterPageRedrawn(fade: () -> Unit): Boolean {
        if (!themeReloading) return false
        themeReloading = false
        themePictureFade = fade
        handler.postDelayed(themePictureTimeout, THEME_PICTURE_MAX_MS)
        return true
    }

    private fun releaseThemePicture() {
        handler.removeCallbacks(themePictureTimeout)
        val fade = themePictureFade ?: return
        themePictureFade = null
        fade()
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
        // 3.0.10: back from a sheet opened from the menu (it stays open under them).
        if (menuOpen) quickMenu.refresh()
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
                showHud(if (leftSide) R.drawable.ic_fast_rewind else R.drawable.ic_fast_forward, "${if (skipRun > 0) "+" else "−"}${abs(skipRun)} s", if (leftSide) -1 else 1)
                handler.postDelayed(hideHud, 700)
            }

            override fun onHold(start: Boolean) {
                val tab = fullscreenTab
                if (tab != null) Helper.holdSpeed(tab, start)
                if (start && tab != null) {
                    showHud(R.drawable.ic_fast_forward, "2×")
                } else {
                    handler.removeCallbacks(hideHud)
                    if (hud.visibility == View.VISIBLE) hideHud.run()
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

    /** A double tap's readout on its side of the screen, where the eyes are; the rest in the middle (as the player's). */
    private fun placeHud(side: Int) {
        val lp = hud.layoutParams as? FrameLayout.LayoutParams ?: return
        val gravity = when {
            side < 0 -> android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            side > 0 -> android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            else -> android.view.Gravity.CENTER
        }
        if (lp.gravity == gravity) return
        lp.gravity = gravity
        lp.marginStart = if (side != 0) dp(48f).toInt() else 0
        lp.marginEnd = if (side != 0) dp(48f).toInt() else 0
        hud.layoutParams = lp
    }

    private fun showHud(icon: Int, text: String, side: Int = 0) {
        placeHud(side)
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
                dimFindButtons()
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
        for (b in findButtons) Ui.pressable(b, Motion.PRESS_ICON, morph = false)
        dimFindButtons()
    }

    /** Previous and Next look unavailable with nothing typed (they still answer, as before). */
    private fun dimFindButtons() {
        val alpha = if (findEdit.text.isNullOrEmpty()) Ui.DISABLED_ALPHA else 1f
        findButtons[0].alpha = alpha
        findButtons[1].alpha = alpha
    }

    /** The count in the bar's soft colour; "0/0" (nothing found) in its red. */
    private fun styleFindCount() {
        findCount.setTextColor(if (findCount.text.toString() == "0/0") barShown?.danger ?: Ui.danger(this) else barShown?.icon ?: color(R.color.text_secondary))
    }

    fun openFind() {
        if (current?.session == null) return
        selectionMenu.dismiss()
        stopEditing()
        showToolbar()
        Motion.fadeIn(findBar, fromDy = 16, duration = Motion.SHORT)
        findEdit.setText("")
        findCount.text = ""
        styleFindCount()
        findEdit.requestFocus()
        showKeyboard(findEdit)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        Motion.fadeOut(findBar, toDy = 0, duration = Motion.PAGE_OUT_MS)
        current?.session?.finder?.clear()
        hideKeyboard()
    }

    private fun find(text: String, backwards: Boolean, again: Boolean) {
        val finder = current?.session?.finder ?: return
        if (text.isEmpty()) {
            finder.clear()
            findCount.text = ""
            styleFindCount()
            return
        }
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
        finder.find(if (again) null else text, flags).accept({ r ->
            if (r != null) {
                findCount.text = if (r.total > 0) "${r.current}/${r.total}" else "0/0"
                styleFindCount()
            }
        }, { _ -> })
    }

    // ------------------------------------------------------------------ back button

    /**
     * 3.0.12: whether [handleBack] has something to do in Swift (everything but its last step,
     * leaving Swift), for [PredictiveBack]. Kept to the same order and tests as handleBack.
     */
    private fun backStaysInSwift(): Boolean {
        // Android sends Swift to the background on its own only when this screen was started from the
        // launcher; started any other way (a link from another app, a shortcut) its Back would close
        // the browser instead, so Swift's own Back stays (it sends Swift to the background too).
        if (!launcherStart || !isTaskRoot) return true
        val tab = current ?: return true
        // The menu isn't made just to ask (it is made the first time it opens).
        return (quickMenuLazy.isInitialized() && quickMenu.isOpen) || switcherOpen || fullscreenTab != null ||
            findBar.visibility == View.VISIBLE || editing ||
            tab.homeShown || TabManager.isRestoring(tab) || (tab.canGoBack && tab.session != null) ||
            (tab.leftHome && !tab.isHome) ||
            (tab.parentId != null && !tab.pinned && TabManager.tabs.any { it.id == tab.parentId }) ||
            (tab.backTo != null && !tab.pinned)
    }

    private fun handleBack() {
        val tab = current
        when {
            quickMenu.isOpen -> quickMenu.onBack()
            switcherOpen -> if (!switcher.back()) switcher.close(pageRect())
            fullscreenTab != null -> leaveFullscreenOnBack(tab)
            findBar.visibility == View.VISIBLE -> closeFind()
            editing -> stopEditing()
            tab == null -> finish()
            tab.homeShown -> leaveHome(tab)
            // 3.0: its page is still to be restored (waiting for the ad blocker at start): it can't go
            // back yet, and closing it or leaving Swift would lose it.
            TabManager.isRestoring(tab) -> Unit
            tab.canGoBack && tab.session != null -> TabManager.goBack(tab)
            tab.leftHome && !tab.isHome -> TabManager.returnHome(tab)
            // 3.0.4: not a pinned one (it leaves Swift instead, below).
            tab.parentId != null && !tab.pinned && TabManager.tabs.any { it.id == tab.parentId } -> TabManager.close(tab)
            // A document opened from Documents or Downloads returns there; one another app sent
            // closes and Android goes back to that app (2.6).
            tab.backTo != null && !tab.pinned -> {
                val to = tab.backTo
                TabManager.close(tab)
                when (to) {
                    Docs.BACK_DOCUMENTS -> FilesActivity.open(this)
                    Docs.BACK_DOWNLOADS -> openDownloads()
                    // 2.7: back to the folder it was opened from.
                    Docs.BACK_FILES -> FilesActivity.open(this)
                    else -> moveTaskToBack(true)
                }
            }
            else -> moveTaskToBack(true)
        }
    }

    /**
     * Back in full screen: the page is asked to leave it, and says so (onFullScreen). 3.0: a page
     * that isn't in full screen any more (its process ended and it was loaded again) sends no answer,
     * so the screen leaves full screen itself; so it does if the page doesn't answer in 2 s.
     */
    private fun leaveFullscreenOnBack(tab: Tab?) {
        val s = tab?.session
        if (tab == null || tab !== fullscreenTab || !tab.fullscreen || s == null || !s.isOpen) {
            exitFullscreen()
            return
        }
        s.exitFullScreen()
        handler.removeCallbacks(fullscreenBackFallback)
        handler.postDelayed(fullscreenBackFallback, 2000)
    }

    /** 3.0: the page didn't answer Back in full screen (see [leaveFullscreenOnBack]); taken off by [exitFullscreen]. */
    private val fullscreenBackFallback = Runnable {
        val tab = fullscreenTab ?: return@Runnable
        L.w("Tab ${tab.id}: its page didn't leave full screen; leaving it anyway")
        tab.fullscreen = false
        exitFullscreen()
    }

    // ------------------------------------------------------------------ start-up screen

    /** The ad blocker got ready (or its settings changed: it may have been switched off). */
    private val adListener: () -> Unit = { handler.post { if (!isDestroyed) onAdBlockerChanged() } }

    private fun onAdBlockerChanged() {
        // 3.0.13: Load now goes once the ad blocker is ready; the page's cover goes when it is drawn.
        updateAdBlockWait()
        if (!startupDone()) return
        AdBlock.removeListener(adListener)
        if (!AdBlock.ready) return
        // 3.0: a page that loaded before it was ready (Load now, or one opened in the first seconds)
        // says so. uBlock Origin's sign for it ("!") can come a moment later.
        unfilteredUntil = SystemClock.uptimeMillis() + 10_000
        checkUnfiltered()
        handler.postDelayed({ checkUnfiltered() }, 1500)
    }

    /** 3.0: until when (uptime) uBlock Origin's "!" on the open page is said: just after it got ready. */
    private var unfilteredUntil = 0L

    /** Tabs already told that their page's ads weren't blocked. */
    private val unfilteredSaid = HashSet<Long>()

    /** The open tab's page loaded before the ad blocker was ready: uBlock Origin marks it "!" (requests it never saw). */
    private fun checkUnfiltered() {
        if (SystemClock.uptimeMillis() > unfilteredUntil) {
            unfilteredUntil = 0L
            return
        }
        val tab = current ?: return
        if (tab.badge != "!" || tab.showsHome || !UrlUtils.isHttp(tab.url) || AdBlock.levelFor(tab.url) == AdLevel.OFF) return
        if (!unfilteredSaid.add(tab.id)) return
        snack("Ads weren't blocked on this page", "Reload", long = true) { if (tab in TabManager.tabs) TabManager.reload(tab) }
    }

    // 3.0.13: the start-up screen with its loader is gone. A site's page that waits for the ad blocker
    // as Swift starts shows Swift's own colour under the bar and its loading line (showStartCover),
    // and after 8 s a message offers to load it at once.

    /** 3.0.13: "The ad blocker is still starting · Load now", while it shows. */
    private var loadNowPill: Messages.Handle? = null
    private val showLoadNow = Runnable { addLoadNow() }

    /**
     * When the app starts, the open page waits for the engine and the ad blocker, so that no ads
     * slip through before its filters are loaded. 3.0.13: the browser shows meanwhile (no start-up
     * screen any more): the page's area in Swift's colour until the page is drawn ([showStartCover];
     * at a real start also for a page that no longer waits but hasn't been drawn yet), the bar with its
     * loading line, and Load now after 8 s ([updateAdBlockWait]). The home page needs none of it.
     */
    private fun showStartupUntilReady(realStart: Boolean) {
        val tab = current
        if (realStart && tab != null && !tab.showsHome && !pageAlreadyThere(tab) && coverWaitsForPaint !== tab) showStartCover(tab)
        if (!startupDone()) AdBlock.addListener(adListener)
        updateAdBlockWait()
    }

    /** 3.0.5: its page is still there from before (Swift's screen was closed, not Swift): nothing to wait for. */
    private fun pageAlreadyThere(tab: Tab) = tab.session?.isOpen == true && tab.restoreToken == 0 && !tab.loading

    /**
     * 3.0.13: while the open page waits for the ad blocker (Swift is starting), 8 s after Swift started
     * a message offers to load it at once, without ad blocking; it goes once the wait is over. Counted
     * from the start of Swift's process, so a screen made again or a tab chosen later keeps the clock.
     * Another message can take its place for a while: it comes back 4 s later if the page still waits.
     */
    private fun updateAdBlockWait() {
        val tab = current
        val waits = tab != null && !tab.showsHome && TabManager.waitsForAdBlocker(tab) && !startupDone()
        if (!waits) {
            handler.removeCallbacks(showLoadNow)
            loadNowPill?.dismiss()
            loadNowPill = null
            return
        }
        if (loadNowPill?.isShowing == true) return
        val replaced = loadNowPill != null
        loadNowPill = null
        val waited = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        val due = if (replaced) LOAD_NOW_AGAIN_MS else (LOAD_NOW_AFTER_MS - waited).coerceAtLeast(0L)
        handler.removeCallbacks(showLoadNow)
        if (due == 0L) addLoadNow() else handler.postDelayed(showLoadNow, due)
    }

    /** 3.0: 8 s on, the waiting page can be loaded at once, without the ad blocker. */
    private fun addLoadNow() {
        val tab = current ?: return
        if (isFinishing || tab.showsHome || !TabManager.waitsForAdBlocker(tab) || startupDone()) return
        if (loadNowPill?.isShowing == true) return
        loadNowPill = Messages.sticky(this, "The ad blocker is still starting", "Load now", messageOffset()) { loadNow() }
    }

    /** 3.0: the waiting pages load now (a page that let ads through says so once the ad blocker is ready). */
    private fun loadNow() {
        handler.removeCallbacks(showLoadNow)
        loadNowPill?.dismiss()
        loadNowPill = null
        AdBlock.loadWaitingNow()
        updateAdBlockWait()
    }

    private fun startupDone() = AdBlock.ready || AdBlock.global == AdLevel.OFF

    // ------------------------------------------------------------------ swipe the toolbar to switch tabs

    private fun setupTabSwipe() {
        val host = object : TabSwipe.Host {
            override fun canSwipeTabs(rawX: Float, rawY: Float): Boolean {
                val tab = current ?: return false
                if (editing || fullscreenTab != null || switcherOpen || quickMenu.isOpen) return false
                if (findBar.visibility == View.VISIBLE) return false
                if (!toolbarWanted || toolbarShown < barPx || TabManager.swipeOrder(tab).size < 2) return false
                // 3.0.4: on the bar itself.
                val at = IntArray(2)
                toolbar.getLocationOnScreen(at)
                return rawX >= at[0] && rawX < at[0] + toolbar.width && rawY >= at[1] && rawY < at[1] + toolbar.height
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
                    findBar.visibility == View.VISIBLE
                ) {
                    return false
                }
                barSwipeX = ev.rawX
                barSwipeY = ev.rawY
                barSwipeMode = when {
                    mode and Prefs.SWIPE_HIDE != 0 && toolbarWanted && toolbarShown >= barPx && within(toolbar, ev) -> 1
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
                    settleToolbar(!hide)
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
        // 3.0.10: the site opened from the home page has something on screen: the home page fades now.
        if (contentful && tab === homeLeaving) fadeHomeAway()
        // 3.0.10: the page reloaded in the new colours is there: the picture of the old screen fades.
        if (contentful && tab === current) releaseThemePicture()
        // 3.0.11: a new tab's page drew: its cover can go (once it is on the screen). 3.0.13: and the
        // cover of a page that waited for the ad blocker as Swift started.
        if (contentful || !coverNeedsContent) coverPagePainted(tab)
    }

    // ------------------------------------------------------------------ updates

    /** 3.0.9: the start's own windows (the Google backup's offer, what's new) have had their turn. */
    private var startWindowsDone = false

    /** 3.0.9: a newer Swift found before they had. */
    private var updateFound: Updates.Release? = null

    /** 3.0.9: a newer Swift is offered once a start. */
    private var updateOffered = false

    /** 3.0.9: in a window, once a start; not while it is downloading already. */
    private fun offerUpdate(r: Updates.Release) {
        if (updateOffered || isFinishing) return
        // After the window about the last run's problem, not over it.
        if (problemShowing) {
            updateFound = r
            return
        }
        val coming = Updates.downloadId.takeIf { it >= 0 }?.let { Downloads.get(it) }
        if (coming != null && coming.status != Downloads.DONE && coming.status != Downloads.FAILED) return
        updateOffered = true
        confirmUpdate(r)
    }

    /** What's new, then the download; Android's installer opens when it is done. */
    fun confirmUpdate(r: Updates.Release) {
        val size = if (r.size > 0) " (${UrlUtils.formatBytes(r.size)})" else ""
        // 3.0.9: downloaded before but not installed (the installer was left): straight to it.
        val ready = Downloads.all().firstOrNull { it.status == Downloads.DONE && it.fileName == r.apkName && java.io.File(it.filePath).isFile }
        MaterialAlertDialogBuilder(this)
            .setTitle("Swift ${r.version} is available")
            .setMessage(com.swiftbrowser.core.ReleaseNotes.plain(r.notes).ifBlank { "A new version of Swift is available." }.take(1500) +
                if (ready != null) "\n\nIt has downloaded already. Tabs and settings are kept." else "\n\nThe update$size downloads now; then Android asks to install it. Tabs and settings are kept.")
            .setPositiveButton(if (ready != null) "Install" else "Download") { _, _ ->
                if (ready != null) {
                    Updates.whenGenuine(this, ready) {
                        DownloadsActivity.openFileIntent(this, ready)?.let { intent ->
                            try {
                                startActivity(intent)
                            } catch (_: Exception) {
                                snack("Open the downloaded update from Downloads to install it")
                            }
                        }
                    }
                    return@setPositiveButton
                }
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

    // ------------------------------------------------------------------ 3.0.3: the tab button and the menu's ring

    private val openGridAfterTap = Runnable { openTabSwitcher() }
    private var tabsTappedAt = 0L

    /**
     * A tap on the tab button opens the grid; two in a row go back to the tab you were on before (and
     * again, back here). With such a tab, a single tap waits a moment to see whether a second follows.
     */
    private fun tabsTapped() {
        val now = SystemClock.uptimeMillis()
        val back = TabManager.previousTab()
        if (back != null && now - tabsTappedAt < TAB_DOUBLE_TAP_MS) {
            handler.removeCallbacks(openGridAfterTap)
            tabsTappedAt = 0L
            Haptics.tick(tabsBtn)
            TabManager.select(back)
            return
        }
        tabsTappedAt = now
        handler.removeCallbacks(openGridAfterTap)
        if (back == null) openTabSwitcher() else handler.postDelayed(openGridAfterTap, TAB_DOUBLE_TAP_MS)
    }

    private val downloadRing by lazy { ProgressRing(this).also { it.setColour(barAccent()) } }
    private val downloadRingListener: () -> Unit = { showDownloadRing() }

    /** While downloads run, a ring round the menu button shows how far they have come (torrents too). */
    private fun showDownloadRing() {
        val running = Downloads.all().filter { it.status == Downloads.RUNNING || it.status == Downloads.QUEUED || it.status == Downloads.CONVERTING }
        if (running.isEmpty()) {
            downloadRing.fraction = null
            menuBtn.foreground = null
            return
        }
        val known = running.map { it.percent }.filter { it >= 0 }
        downloadRing.fraction = if (known.size == running.size) known.average().toFloat() / 100f else -1f
        if (menuBtn.foreground !== downloadRing) {
            // 3.0.4: the accent, or the bar's text colour on a website's colour close to it.
            downloadRing.setColour(barAccent())
            menuBtn.foreground = downloadRing
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
            // 3.0: only a genuine Swift update (signed like this one) goes to Android's installer.
            Updates.whenGenuine(this, item) {
                DownloadsActivity.openFileIntent(this, item)?.let { intent ->
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        snack("Open the downloaded update from Downloads to install it")
                    }
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
                if (progress >= 1f && !edgeArmed) Haptics.tick(edgeArrow)
                edgeArmed = progress >= 1f
            }

            override fun onEdgeSwipeEnd(fromLeft: Boolean, distance: Float) {
                edgeArmed = false
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

    /** 3.0.12: a message with Undo; [commit] runs once it has gone (however it went) unless Undo was tapped. */
    fun undoSnack(text: String, undo: () -> Unit, commit: () -> Unit) {
        var undone = false
        Messages.show(this, text, "Undo", bottomOffset = messageOffset(), onDismiss = { if (!undone) commit() }) {
            undone = true
            undo()
        }
    }

    /** Messages sit above the address bar when it is at the bottom and showing (3.0.4: and the group strip above it). */
    private fun messageOffset(): Int =
        if (bottomBar && appBar.visibility == View.VISIBLE) visibleBarPx() else 0

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

    /** 3.0.3: whole pixels, for view sizes. */
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showCrashReportIfAny() {
        val report = Crash.pending(this) ?: return
        val text = report.take(3500)
        val view = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            Ui.size(this, Ui.Type.SMALL, keepFont = true)
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
    /** 3.0.9: the window about the last run's problem is coming or open: a newer Swift waits for it. */
    private var problemShowing = false

    private fun showProblemIfAny() {
        val problem = Diagnostics.problem ?: return
        val detail = Diagnostics.problemDetail
        Diagnostics.clearProblem()
        problemShowing = true
        // 3.0.8: once Android's log of that run's end is read (a moment at most), which can tell that
        // its drawing ran out of graphics memory; the crash's own notes only said "abort in libc.so".
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { com.swiftbrowser.core.Health.awaitEndOfPreviousRun(3000) }
            if (!isFinishing && !isDestroyed) showProblem(problem, detail, com.swiftbrowser.core.Health.endedOutOfGraphicsMemory)
        }
    }

    private fun showProblem(problem: Diagnostics.Problem, detail: String?, outOfGraphicsMemory: Boolean) {
        val reason = if (outOfGraphicsMemory) {
            "It ran out of graphics memory (Android's drawing), most likely with a long or heavy video and little memory left."
        } else {
            Diagnostics.plainReason(detail)
        }
        val (title, text) = when (problem) {
            Diagnostics.Problem.CLOSED_BY_ANDROID -> "Swift was closed while you used it" to
                // 3.0.11: not "keep fewer tabs in memory": with memory low Swift keeps only the open one
                // loaded already, and it happened with one tab loaded (a long video) in a problem report.
                "Android closed Swift while it was on screen. On a phone with little memory that almost always means it ran out of memory: " +
                "a long video on a heavy site, or very heavy pages such as Google's AI answers, can do that.\n\n" +
                "To make it less likely: close other apps and the tabs you don't need.\n\nIf it keeps happening, send a report."
            Diagnostics.Problem.ENGINE_CRASH -> "Swift's engine crashed" to
                "The Firefox engine inside Swift crashed last time. " + (reason?.let { "$it\n\n" } ?: "\n\n") +
                (detail?.let { "Details: $it\n\n" } ?: "") + "Sending a report (with the last 3 days' logs) helps find out why."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Send report") { _, _ -> shareProblemReport() }
            .showImmersive()
            .setOnDismissListener {
                problemShowing = false
                if (startWindowsDone) updateFound?.let { offerUpdate(it) }
            }
    }

    /** The problem report with this run's settings; see Diagnostics. */
    fun shareProblemReport() = Diagnostics.shareReport(this)

    /** For the video sheet: picks a quality of an HLS stream, then downloads it (3.0: with its separate [audio], if any). */
    fun startHlsDownload(tab: Tab, playlistUrl: String, headers: Map<String, String>, quality: String?, audio: String? = null) {
        withStorage {
            val base = tab.title.ifBlank { UrlUtils.host(tab.url) ?: "video" }
            val title = if (quality != null) "$base $quality" else base
            Downloads.startHls(tab.profileId, playlistUrl, title, headers, tab.url, tab.incognito, audio)
            snack("Downloading video", "View") { openDownloads() }
        }
    }

    companion object {
        /** 3.0.3: how long a tap on the tab button waits for a second one (Android's own double-tap time). */
        private const val TAB_DOUBLE_TAP_MS = 300L


        /** 3.0.4: the group strip's height (dp). */
        private const val STRIP_DP = 40

        /** 3.0.4: how long the bar's colours take to change (a website's colour coming or going). */
        private const val BAR_COLOUR_MS = 200L

        /** 3.0: how long (from Swift's start) a page waits for the ad blocker before Load now is offered. */
        private const val LOAD_NOW_AFTER_MS = 8000L

        /** 3.0.13: Load now again after another message took its place, if the page still waits. */
        private const val LOAD_NOW_AGAIN_MS = 4000L

        /** 3.0.5: how long a start's page cover waits for a page that is loading before it lets go of it. */
        private const val START_PAGE_LIMIT_MS = 12_000L

        /** 3.0.5: the longest a cover waits for a painted page to reach the screen (see coverPagePainted). */
        private const val START_PAGE_DRAW_MS = 1500L

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

        /** 3.0.10: full screen's black: kept this long after the window's new size, this long at most. */
        private const val FULLSCREEN_SETTLE_MS = 250L
        private const val FULLSCREEN_COVER_MAX_MS = 900L
        private const val FULLSCREEN_COVER_STILL_MS = 350L

        /** 3.0.10: the old screen's picture waits this long at most for the page reloaded in the new colours. */
        private const val THEME_PICTURE_MAX_MS = 1500L

        /** 3.0.10: the home page waits this long at most for the site opened from it to draw. */
        private const val HOME_LEAVE_MAX_MS = 1500L

        /** 3.0.11: a new tab's cover waits this long at most for its page to draw (a slow site, a page that never paints). */
        private const val NEW_TAB_COVER_MAX_MS = 6000L

        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"

        /** Opens this tab (from the playback notification). */
        const val EXTRA_TAB_ID = "tab_id"

        /** Opens this file in a new tab (a page saved as PDF, from Downloads). */
        const val EXTRA_OPEN_FILE = "open_file"

        /** From Settings → Look & screen: open the menu ready to arrange (2.2). */

        /** App icon shortcuts (res/xml/shortcuts.xml, 2.3). */
        private const val PREF_DOC_TABS_MOVED = "doc_tabs_moved"
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
