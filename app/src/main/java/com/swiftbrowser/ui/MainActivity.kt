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
import com.google.android.material.snackbar.Snackbar
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Crash
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
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
    private lateinit var root: FrameLayout
    private lateinit var appBar: View
    private lateinit var toolbar: LinearLayout
    private lateinit var profileChip: TextView
    private lateinit var incognitoIcon: ImageView
    private lateinit var urlBox: View
    private lateinit var urlEdit: EditText
    private lateinit var urlIcon: ImageView
    private lateinit var urlMic: ImageButton
    private lateinit var urlClear: ImageButton
    private lateinit var videoBtn: View
    private lateinit var tabsCount: TextView
    private lateinit var menuBtn: ImageButton
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
    private lateinit var switcher: TabSwitcherView
    private lateinit var startup: View
    private lateinit var tabSwipe: TabSwipe

    /** The ⋮ menu; built once, when the app is idle after starting. */
    private val quickMenu by lazy { QuickMenu(this, root) }

    private val handler = Handler(Looper.getMainLooper())
    private val suggestionAdapter = RowAdapter<Suggestion>(::bindSuggestion, ::onSuggestion)
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

    // Toolbar that slides away while scrolling down a page.
    private var toolbarPx = 0
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest gives this screen the logo as its window while the app starts.
        setTheme(R.style.Theme_Swift)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = SwiftApp.prefs
        toolbarPx = resources.getDimensionPixelSize(R.dimen.toolbar_height)
        bindViews()
        setupToolbar()
        setupFindBar()
        setupGestures()
        setupPullToRefresh()
        setupHome()
        setupSwitcher()
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
        val tab = TabManager.ensureCurrent()
        seenTabs.add(tab.id)
        onTabSelected(tab)
        onTabsChanged()
        if (savedInstanceState == null) handleIntent(intent)
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
        showCrashReportIfAny()
        showProblemIfAny()
        showStartupUntilReady()
        applySystemBars()
        SystemBars.keepHidden(this) { fullscreenTab == null }
        // The menu was open when the screen restarted for a dark mode switch: it stays open.
        if (savedInstanceState?.getBoolean(STATE_MENU_OPEN) == true) {
            root.post { if (!isFinishing) quickMenu.open(animate = false) }
        }
        Looper.myQueue().addIdleHandler {
            quickMenu
            false
        }
        setupEdgeSwipe()
        // A newer Swift on GitHub? Asked at most once a day, a while after starting.
        handler.postDelayed({
            if (!isFinishing) Updates.checkNow(prefs, force = false) { r -> offerUpdate(r) }
        }, 15_000)
        Downloads.addListener(updateDownloadListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MENU_OPEN, quickMenu.isOpen)
    }

    override fun onStart() {
        super.onStart()
        Diagnostics.setOnScreen(true)
        if (!switcher.isOpen) current?.session?.let { if (it.isOpen) it.setActive(true) }
    }

    override fun onPause() {
        // A fresh picture for the tab switcher, also kept for the next start.
        current?.let { captureThumbnail(it, null) }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        Diagnostics.setOnScreen(false)
        TabManager.flushSaves()
        if (!isChangingConfigurations) {
            // A playing page stays active so its video or music goes on (background play).
            val keepPlaying = prefs.backgroundPlay && current?.playing == true
            current?.session?.let { if (it.isOpen && !keepPlaying) it.setActive(false) }
            AdBlock.updateInBackground()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ptrSpin?.cancel()
        AdBlock.removeListener(adListener)
        Downloads.removeListener(updateDownloadListener)
        if (TabManager.ui === this) TabManager.ui = null
        if (Engine.activityDelegate != null) Engine.activityDelegate = null
        home.listener = null
        switcher.listener = null
        gestureLayer.pullListener = null
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
        urlMic = findViewById(R.id.url_mic)
        urlClear = findViewById(R.id.url_clear)
        videoBtn = findViewById(R.id.video_btn)
        tabsCount = findViewById(R.id.tabs_count)
        menuBtn = findViewById(R.id.menu_btn)
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
        switcher = findViewById(R.id.tab_switcher)
        startup = findViewById(R.id.startup)
        // Drawn above the pull-to-refresh circle, without a shadow on the page.
        appBar.elevation = dp(8f)
        appBar.outlineProvider = null
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val tabId = intent.getLongExtra(EXTRA_TAB_ID, Long.MIN_VALUE)
        if (tabId != Long.MIN_VALUE) {
            intent.removeExtra(EXTRA_TAB_ID)
            TabManager.tabs.firstOrNull { it.id == tabId }?.let { t ->
                switcher.close()
                if (t !== current) TabManager.select(t)
            }
            return
        }
        intent.getStringExtra(EXTRA_OPEN_FILE)?.let { path ->
            intent.removeExtra(EXTRA_OPEN_FILE)
            openFileInTab(path)
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
        if (!url.isNullOrBlank()) {
            stopEditing()
            switcher.close()
            TabManager.newTab(url, incognito = false)
        }
    }

    // ------------------------------------------------------------------ toolbar & address bar

    private fun setupToolbar() {
        profileChip.setOnClickListener { Sheets.profiles(this) }
        findViewById<View>(R.id.tabs_btn).setOnClickListener { openTabSwitcher() }
        menuBtn.setOnClickListener {
            stopEditing()
            quickMenu.open()
        }
        videoBtn.setOnClickListener { current?.let { Sheets.videos(this, it) } }
        findViewById<View>(R.id.crash_reload).setOnClickListener {
            crashView.visibility = View.GONE
            current?.let { TabManager.revive(it) }
        }

        suggestionsList.layoutManager = LinearLayoutManager(this)
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
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && !editing) startEditing()
            false
        }
        // Whatever the keyboard's action key is called (Go, Search, Done, Enter), it searches.
        urlEdit.setOnEditorActionListener { _, _, event ->
            if (event == null || event.action == KeyEvent.ACTION_DOWN) navigate(urlEdit.text.toString())
            true
        }
        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!editing) return
                updateUrlButtons()
                refreshSuggestions(s?.toString() ?: "")
            }
        })
        urlClear.setOnClickListener { urlEdit.setText("") }
        urlMic.setOnClickListener { voiceSearch() }
    }

    fun startEditing() {
        if (fullscreenTab != null) return
        editing = true
        showToolbar()
        val tab = current
        val text = when {
            tab == null || tab.isBlank -> ""
            else -> UrlUtils.googleQuery(tab.url) ?: tab.url
        }
        urlBox.visibility = View.VISIBLE
        urlEdit.setText(text)
        urlEdit.selectAll()
        setUrlIcon(R.drawable.ic_google_g)
        suggestionsList.visibility = View.VISIBLE
        updateUrlButtons()
        if (!urlEdit.hasFocus()) urlEdit.requestFocus()
        showKeyboard(urlEdit)
        refreshSuggestions("")
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        suggestJob?.cancel()
        suggestionsList.visibility = View.GONE
        updateUrlButtons()
        hideKeyboard()
        if (!gecko.requestFocus()) root.requestFocus()
        shownUrlText = null
        shownUrlIcon = 0
        current?.let { updateHome(it) }
        updateUrlBar()
    }

    private fun updateUrlButtons() {
        val empty = urlEdit.text.isNullOrEmpty()
        urlClear.visibility = if (editing && !empty) View.VISIBLE else View.GONE
        urlMic.visibility = if (editing && empty) View.VISIBLE else View.GONE
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
        switcher.close()
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
                .show()
            return
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            snack("Couldn't start voice search")
        }
    }

    private fun refreshSuggestions(text: String) {
        suggestJob?.cancel()
        val pid = Profiles.currentId
        val google = prefs.searchSuggestions
        suggestJob = lifecycleScope.launch {
            val head = ArrayList<Suggestion>()
            if (text.isNotBlank()) {
                val target = UrlUtils.toUrlOrSearch(text)
                head += if (target.startsWith("https://www.google.com/search?")) {
                    Suggestion(Suggestion.Kind.SEARCH, text, target)
                } else {
                    Suggestion(Suggestion.Kind.GO, text, target)
                }
            }
            val local = withContext(Dispatchers.IO) { Suggestions.local(pid, text) }
            suggestionAdapter.items = head + local
            if (google && text.isNotBlank()) {
                delay(150)
                val fromGoogle = withContext(Dispatchers.IO) { Suggestions.google(text) }
                    .filter { !it.equals(text, ignoreCase = true) }
                    .take(5)
                    .map { Suggestion(Suggestion.Kind.SEARCH, it, UrlUtils.searchUrl(it)) }
                suggestionAdapter.items = head + fromGoogle + local
            }
        }
    }

    private fun bindSuggestion(h: RowHolder, s: Suggestion) {
        val icon = when (s.kind) {
            Suggestion.Kind.GO -> R.drawable.ic_globe
            Suggestion.Kind.SEARCH -> R.drawable.ic_search
            Suggestion.Kind.BOOKMARK -> R.drawable.ic_star
            Suggestion.Kind.HISTORY -> R.drawable.ic_history
        }
        val subtitle = when (s.kind) {
            Suggestion.Kind.SEARCH -> null
            Suggestion.Kind.GO -> s.url
            else -> UrlUtils.display(s.url)
        }
        h.set(icon, s.title, subtitle)
        h.setAction(R.drawable.ic_fill, "Edit") {
            val t = if (s.kind == Suggestion.Kind.SEARCH || s.kind == Suggestion.Kind.GO) s.title else s.url
            urlEdit.setText(t)
            urlEdit.setSelection(urlEdit.text.length)
        }
    }

    private fun onSuggestion(s: Suggestion) {
        stopEditing()
        openInCurrent(s.url)
    }

    private fun updateUrlBar() {
        if (editing) return
        val tab = current
        val url = tab?.url
        val query = UrlUtils.googleQuery(url)
        val reader = Helper.isReaderUrl(url)
        val saved = url?.startsWith("file://") == true
        val text = when {
            reader -> "Reader view · ${tab?.title.orEmpty()}"
            saved -> "Saved page · " + Uri.decode(url.orEmpty().substringAfterLast('/')).removeSuffix(".pdf")
            else -> query ?: UrlUtils.display(url)
        }
        if (text != shownUrlText) {
            shownUrlText = text
            urlEdit.setText(text)
        }
        val icon = when {
            reader -> R.drawable.ic_reader
            saved -> R.drawable.ic_offline
            tab == null || tab.isBlank || query != null -> R.drawable.ic_google_g
            tab.secure -> R.drawable.ic_lock
            else -> R.drawable.ic_globe
        }
        if (icon != shownUrlIcon) setUrlIcon(icon)
    }

    private fun setUrlIcon(res: Int) {
        shownUrlIcon = res
        urlIcon.setImageResource(res)
        urlIcon.imageTintList = if (res == R.drawable.ic_google_g) null else ColorStateList.valueOf(iconColor())
    }

    private fun updateProgress() {
        val tab = current
        if (tab == null || !tab.loading || fullscreenTab != null || tab.isHome) {
            if (progress.visibility != View.INVISIBLE) progress.visibility = View.INVISIBLE
        } else {
            if (progress.visibility != View.VISIBLE) progress.visibility = View.VISIBLE
            progress.progress = tab.progress.coerceIn(5, 100)
        }
    }

    private fun updateProfileChip() {
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
        urlClear.imageTintList = icons
        styledTabCount = null
        styleTabCount()
        profileChip.visibility = if (incognito) View.GONE else View.VISIBLE
        incognitoIcon.visibility = if (incognito) View.VISIBLE else View.GONE
        shownUrlIcon = 0
    }

    private fun iconColor(): Int =
        color(if (current?.incognito == true) R.color.incognito_hint else R.color.text_secondary)

    // ------------------------------------------------------------------ home page

    private fun setupHome() {
        home.listener = object : HomeView.Listener {
            override fun onSearchBoxClicked() = startEditing()

            override fun onVoiceSearch() = voiceSearch()

            override fun onTileClicked(url: String) = openInCurrent(url)

            override fun onTileLongClicked(tile: TopSites.Tile, anchor: View) {
                val menu = PopupMenu(this@MainActivity, anchor)
                menu.menu.add(0, 1, 0, "Open in new tab")
                menu.menu.add(0, 2, 1, "Open in incognito tab")
                menu.menu.add(0, 3, 2, "Remove")
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> TabManager.newTab(tile.url, incognito = false)
                        2 -> TabManager.newTab(tile.url, incognito = true)
                        3 -> {
                            prefs.hiddenTiles = prefs.hiddenTiles + tile.key
                            current?.let { home.show(it.incognito) }
                            snack("Removed ${tile.label}", "Undo") {
                                prefs.hiddenTiles = prefs.hiddenTiles - tile.key
                                current?.let { home.show(it.incognito) }
                            }
                        }
                    }
                    true
                }
                menu.show()
            }
        }
    }

    private fun updateHome(tab: Tab) {
        val show = tab.isHome && fullscreenTab == null
        if (show) {
            if (home.visibility != View.VISIBLE || homeShownFor !== tab) {
                home.show(tab.incognito)
                home.scrollToTop()
                homeShownFor = tab
            }
            home.visibility = View.VISIBLE
        } else {
            home.visibility = View.GONE
            homeShownFor = null
        }
        val urlVisibility = if (show && !editing) View.INVISIBLE else View.VISIBLE
        if (urlBox.visibility != urlVisibility) urlBox.visibility = urlVisibility
    }

    // ------------------------------------------------------------------ tab switcher

    private fun setupSwitcher() {
        switcher.listener = object : TabSwitcherView.Listener {
            override fun onTabChosen(tab: Tab) {
                if (tab !== current) TabManager.select(tab)
                switcher.close()
            }

            override fun onNewTab(incognito: Boolean) {
                switcher.close()
                TabManager.newTab(incognito = incognito)
            }

            override fun onNewTabInGroup(group: TabGroup) {
                switcher.close()
                TabManager.newTabInGroup(group)
            }

            override fun onSwitcherClosed() {
                current?.session?.let { if (it.isOpen) it.setActive(true) }
            }
        }
    }

    fun openTabSwitcher() {
        if (switcher.isOpen) return
        quickMenu.close()
        stopEditing()
        closeFind()
        val tab = current
        switcher.open(tab?.incognito == true)
        if (tab == null) return
        captureThumbnail(tab) {
            if (switcher.isOpen) {
                switcher.refreshTab(tab)
                // Nothing on the page needs drawing while the grid covers it.
                if (current === tab) tab.session?.let { if (it.isOpen) it.setActive(false) }
            }
        }
    }

    private var thumbnailTries = 0

    private val thumbnailRunnable: Runnable = Runnable {
        val tab = current ?: return@Runnable
        if (tab.loading || switcher.isOpen || fullscreenTab != null) return@Runnable
        // Reading the screen back holds up drawing for a moment; wait until scrolling stops.
        if (SystemClock.uptimeMillis() - lastUserScrollAt < 1500 && thumbnailTries++ < 10) {
            handler.postDelayed(thumbnailRunnable, 1500)
            return@Runnable
        }
        captureThumbnail(tab, null)
    }

    /** Pictures for the tab grid are taken once a page has settled after loading. */
    private fun scheduleThumbnail(tab: Tab) {
        if (tab.isHome) return
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

    override fun onTabSelected(tab: Tab) {
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
            if (gecko.session !== session) gecko.setSession(session)
        } else if (gecko.session != null) {
            gecko.releaseSession()
        }
    }

    override fun onTabsChanged() {
        val n = (if (current?.incognito == true) TabManager.incognitoTabs() else TabManager.profileTabs()).size
        tabsCount.text = if (n > 99) ":D" else n.toString()
        styleTabCount()
        updateProfileChip()
        if (switcher.isOpen) switcher.refresh()
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
        findViewById<View>(R.id.tabs_btn).contentDescription = if (group != null) "Tabs (group ${TabManager.groupTitle(group)})" else "Tabs"
    }

    override fun onTabUpdated(tab: Tab) {
        if (tab !== current) return
        syncSession(tab)
        val pageStarted = tab.loading && !shownLoading
        if (pageStarted) {
            if (themeReloadTab === tab) themeReloadStarted = true
            // A new page: bring the toolbar back like Chrome does.
            lastScrollY = -1f
            showToolbar()
            if (ptrRefreshing) finishPullRefresh()
        }
        if (!tab.loading && shownLoading) {
            scheduleThumbnail(tab)
            if (themeReloadTab === tab && themeReloadStarted) {
                themeReloadTab = null
                handler.postDelayed({ if (current === tab) TabManager.reload(tab) }, 500)
            }
        }
        shownLoading = tab.loading
        updateHome(tab)
        if (pageStarted) refreshToolbarNow() else requestToolbarRefresh()
        if (switcher.isOpen) switcher.refreshTab(tab)
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
        val video = if (tab.media.isNotEmpty() && fullscreenTab == null && !tab.isHome) View.VISIBLE else View.GONE
        if (videoBtn.visibility != video) videoBtn.visibility = video
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
            crashView.visibility = View.VISIBLE
            showToolbar()
        }
    }

    override fun onPlayingChanged(tab: Tab) {
        if (tab !== current) return
        updateKeepScreenOn()
    }

    private fun updateKeepScreenOn() {
        val on = fullscreenTab != null || current?.playing == true
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
        sheet.show()
    }

    // ------------------------------------------------------------------ toolbar that hides while scrolling

    private fun applyToolbarShown(shown: Float) {
        toolbarShown = shown
        appBar.translationY = shown - toolbarPx
        gestureLayer.translationY = shown
        // The engine lays pages out for a fully shown toolbar (see setDynamicToolbarMaxHeight); a
        // negative clipping moves elements fixed to the bottom of the screen down by the hidden part.
        gecko.setVerticalClipping((shown - toolbarPx).roundToInt())
    }

    private fun showToolbar() = setToolbarWanted(true)

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
        toolbarWanted = show
        applyToolbarShown(target)
        val now = SystemClock.uptimeMillis()
        expectedShift = if (show) toolbarPx.toFloat() else -toolbarPx.toFloat()
        shiftExpectedUntil = now + 1000
        toolbarToggledAt = now
        scrollRun = 0f
        if (show && toolbarStale) refreshToolbarNow()
    }

    private fun canAutoHideToolbar(): Boolean {
        val tab = current ?: return false
        return prefs.hideToolbarOnScroll && !editing && fullscreenTab == null && !ptrRefreshing &&
            findBar.visibility != View.VISIBLE && !switcher.isOpen && !tab.isHome && !tab.crashed
    }

    override fun onPageScroll(tab: Tab, scrollY: Float, byUser: Boolean) {
        if (tab !== current || fullscreenTab != null) return
        if (byUser) lastUserScrollAt = SystemClock.uptimeMillis()
        val last = lastScrollY
        lastScrollY = scrollY
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

    override fun onToolbarRequest(tab: Tab, show: Boolean) {
        if (tab !== current) return
        if (show) showToolbar() else if (canAutoHideToolbar()) hideToolbar()
    }

    // ------------------------------------------------------------------ pull to refresh

    private val ptrTimeout = Runnable { finishPullRefresh() }

    private fun setupPullToRefresh() {
        gestureLayer.page = gecko
        gestureLayer.pullListener = object : GestureFrameLayout.PullListener {
            override fun canPull(): Boolean {
                val tab = current ?: return false
                return prefs.pullToRefresh && !ptrRefreshing && fullscreenTab == null && !editing && !switcher.isOpen &&
                    findBar.visibility != View.VISIBLE && tab.session != null && !tab.isHome &&
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
                    color(if (offset >= trigger) R.color.accent else R.color.text_secondary),
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
    private fun ptrHiddenY() = toolbarPx - dp(16f) - dp(40f)

    private fun startPullRefresh() {
        val tab = current ?: return hidePullIndicator()
        ptrRefreshing = true
        ptrStartedAt = System.currentTimeMillis()
        ptrIndicator.imageTintList = ColorStateList.valueOf(color(R.color.accent))
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

    private fun bumpTabCount() {
        tabsCount.animate().cancel()
        tabsCount.scaleX = 1f
        tabsCount.scaleY = 1f
        tabsCount.animate().scaleX(1.4f).scaleY(1.4f).setDuration(130).withEndAction {
            tabsCount.animate().scaleX(1f).scaleY(1f).setDuration(170).start()
        }.start()
    }

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
            else snack("No app can open this link")
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
            .show()
    }

    // ------------------------------------------------------------------ downloads

    override fun onExternalResponse(tab: Tab, response: WebResponse) {
        val disposition = response.headers["Content-Disposition"]
        val mime = response.headers["Content-Type"]?.substringBefore(';')?.trim()
        val name = UrlUtils.fileName(response.uri, disposition, mime)
        val size = response.headers["Content-Length"]?.toLongOrNull() ?: -1
        val start = {
            withStorage {
                Downloads.startFromResponse(tab.profileId, response, name, mime, tab.url)
                snack("Downloading $name", "View") { openDownloads() }
            }
        }
        if (!prefs.confirmDownloads) {
            start()
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
            }
            .show()
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
                Downloads.startUrl(tab.profileId, url, name, UrlUtils.mimeFromName(name), headers, tab.url)
                snack("Downloading $name", "View") { openDownloads() }
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
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val link = element.linkUri
        val src = element.srcUri
        if (link != null) {
            actions += "Open in new tab" to { TabManager.newTab(link, parent = tab) }
            actions += "Open in background tab" to { TabManager.newTab(link, parent = tab, select = false) }
            if (!tab.incognito) actions += "Open in incognito tab" to { TabManager.newTab(link, parent = tab, incognito = true) }
            actions += "Copy link" to { copy(link) }
            actions += "Share link" to { share(link) }
            actions += "Download link" to { downloadUrl(tab, link) }
        }
        if (src != null) {
            when (element.type) {
                GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> {
                    actions += "Open image in new tab" to { TabManager.newTab(src, parent = tab) }
                    actions += "Download image" to { downloadUrl(tab, src) }
                    actions += "Copy image link" to { copy(src) }
                }
                GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO,
                GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> {
                    actions += "Download video" to { downloadUrl(tab, src) }
                    actions += "Copy video link" to { copy(src) }
                    if (UrlUtils.isHttp(src)) actions += "Open video in new tab" to { TabManager.newTab(src, parent = tab) }
                }
            }
        }
        if (actions.isEmpty()) return
        val title = (link ?: src ?: "").let { if (it.length > 120) it.take(117) + "…" else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
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

    private val hideHud = Runnable { hud.visibility = View.GONE }

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
        (if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString().trimEnd('0')) + "x"

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
        hud.visibility = View.VISIBLE
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
        stopEditing()
        showToolbar()
        findBar.visibility = View.VISIBLE
        findEdit.setText("")
        findCount.text = ""
        findEdit.requestFocus()
        showKeyboard(findEdit)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
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
            quickMenu.isOpen -> quickMenu.close()
            switcher.isOpen -> if (!switcher.back()) switcher.close()
            fullscreenTab != null -> tab?.session?.exitFullScreen() ?: exitFullscreen()
            findBar.visibility == View.VISIBLE -> closeFind()
            editing -> stopEditing()
            tab == null -> finish()
            tab.canGoBack && tab.session != null -> TabManager.goBack(tab)
            tab.leftHome && !tab.isHome -> TabManager.returnHome(tab)
            tab.parentId != null && TabManager.tabs.any { it.id == tab.parentId } -> TabManager.close(tab)
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
        AdBlock.addListener(adListener)
        // Pages stop waiting after this long too (see TabManager), so the screen does as well.
        handler.postDelayed(hideStartupRunnable, 8000)
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
                if (editing || fullscreenTab != null || switcher.isOpen || quickMenu.isOpen) return false
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = when (tabSwipe.onTouchEvent(ev)) {
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
                    snack("Downloading Swift ${r.version}…", "View") { openDownloads() }
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private val updateDownloadListener: () -> Unit = {
        val id = Updates.downloadId
        val item = if (id >= 0) Downloads.get(id) else null
        if (item != null && item.status == Downloads.DONE) {
            Updates.downloadId = -1
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
                return prefs.edgeSwipe && fullscreenTab == null && !editing && !tab.isHome && tab.session != null &&
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
                edgeArrow.imageTintList = ColorStateList.valueOf(color(if (progress >= 1f) R.color.accent else R.color.text_secondary))
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
        val bar = Snackbar.make(root, text, if (long) 8000 else if (action != null) 4000 else Snackbar.LENGTH_SHORT)
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.show()
    }

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

    private fun color(res: Int) = ContextCompat.getColor(this, res)

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
            .show()
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
                "The Firefox engine inside Swift crashed last time" + (detail?.let { ":\n$it" } ?: ".") +
                "\n\nSending a report helps find out why."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Send report") { _, _ -> shareProblemReport() }
            .show()
    }

    /** The problem report with this run's settings; see Diagnostics. */
    fun shareProblemReport() = Diagnostics.shareReport(this)

    /** For the video sheet: picks a quality of an HLS stream, then downloads it. */
    fun startHlsDownload(tab: Tab, playlistUrl: String, headers: Map<String, String>, quality: String?) {
        withStorage {
            val base = tab.title.ifBlank { UrlUtils.host(tab.url) ?: "video" }
            val title = if (quality != null) "$base $quality" else base
            Downloads.startHls(tab.profileId, playlistUrl, title, headers, tab.url)
            snack("Downloading video", "View") { openDownloads() }
        }
    }

    companion object {
        private const val TOOLBAR_SETTLE_MS = 300L
        private const val STATE_MENU_OPEN = "menu_open"

        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"

        /** Opens this tab (from the playback notification). */
        const val EXTRA_TAB_ID = "tab_id"

        /** Opens this file in a new tab (a page saved as PDF, from Downloads). */
        const val EXTRA_OPEN_FILE = "open_file"

        /** The square outline around the tab count, in [color]. */
        fun tabCountBackground(ctx: Context, color: Int) = android.graphics.drawable.GradientDrawable().apply {
            val density = ctx.resources.displayMetrics.density
            setStroke((2 * density).roundToInt(), color)
            cornerRadius = 4 * density
        }
    }
}
