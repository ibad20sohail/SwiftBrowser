package com.swiftbrowser.ui

import android.Manifest
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
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.BrowserUi
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
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

class MainActivity : AppCompatActivity(), BrowserUi {

    override val activity: AppCompatActivity get() = this

    private lateinit var prefs: Prefs
    lateinit var gecko: GeckoView
        private set
    private lateinit var root: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var profileChip: TextView
    private lateinit var urlEdit: EditText
    private lateinit var urlIcon: ImageView
    private lateinit var urlClear: ImageButton
    private lateinit var videoBtn: View
    private lateinit var shieldIcon: ImageView
    private lateinit var shieldBadge: TextView
    private lateinit var tabsCount: TextView
    private lateinit var progress: ProgressBar
    private lateinit var gestureLayer: GestureFrameLayout
    private lateinit var crashView: View
    private lateinit var suggestionsList: RecyclerView
    private lateinit var hud: TextView
    private lateinit var speedChip: TextView
    private lateinit var findBar: View
    private lateinit var findEdit: EditText
    private lateinit var findCount: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val suggestionAdapter = RowAdapter<Suggestion>(::bindSuggestion, ::onSuggestion)
    private var suggestJob: Job? = null
    private var editing = false
    private var fullscreenTab: Tab? = null
    private var volumeAccumulator = 0f
    private var lastBlocked: Pair<String, Long>? = null

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
            if (r.data?.getBooleanExtra(EXTRA_NEW_TAB, false) == true) TabManager.newTab(url) else openInCurrent(url)
        }

    private val intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val pending = pendingActivityResult ?: return@registerForActivityResult
            pendingActivityResult = null
            if (r.resultCode == Activity.RESULT_OK) pending.complete(r.data ?: Intent())
            else pending.completeExceptionally(IllegalStateException("Cancelled"))
        }

    private val adListener: () -> Unit = { handler.post { updateShield() } }

    private val current: Tab? get() = TabManager.current

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = SwiftApp.prefs
        bindViews()
        setupToolbar()
        setupFindBar()
        setupGestures()

        gecko.setActivityContextDelegate { this }
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
        AdBlock.addListener(adListener)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        val tab = TabManager.ensureCurrent()
        onTabSelected(tab)
        onTabsChanged()
        if (savedInstanceState == null) handleIntent(intent)
        showCrashReportIfAny()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        current?.session?.let { if (it.isOpen) it.setActive(true) }
    }

    override fun onStop() {
        super.onStop()
        TabManager.flushSaves()
        if (!isChangingConfigurations) current?.session?.let { if (it.isOpen) it.setActive(false) }
    }

    override fun onDestroy() {
        AdBlock.removeListener(adListener)
        if (TabManager.ui === this) TabManager.ui = null
        if (Engine.activityDelegate != null) Engine.activityDelegate = null
        gecko.releaseSession()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Engine.runtime.configurationChanged(newConfig)
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        profileChip = findViewById(R.id.profile_chip)
        urlEdit = findViewById(R.id.url_edit)
        urlIcon = findViewById(R.id.url_icon)
        urlClear = findViewById(R.id.url_clear)
        videoBtn = findViewById(R.id.video_btn)
        shieldIcon = findViewById(R.id.shield_icon)
        shieldBadge = findViewById(R.id.shield_badge)
        tabsCount = findViewById(R.id.tabs_count)
        progress = findViewById(R.id.progress)
        gestureLayer = findViewById(R.id.gesture_layer)
        gecko = findViewById(R.id.gecko)
        crashView = findViewById(R.id.crash_view)
        suggestionsList = findViewById(R.id.suggestions)
        hud = findViewById(R.id.hud)
        speedChip = findViewById(R.id.speed_chip)
        findBar = findViewById(R.id.find_bar)
        findEdit = findViewById(R.id.find_edit)
        findCount = findViewById(R.id.find_count)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
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
            TabManager.newTab(url)
        }
    }

    // ------------------------------------------------------------------ toolbar & address bar

    private fun setupToolbar() {
        profileChip.setOnClickListener { Sheets.profiles(this) }
        findViewById<View>(R.id.shield_btn).setOnClickListener { Sheets.shield(this) }
        findViewById<View>(R.id.tabs_btn).setOnClickListener { Sheets.tabs(this) }
        findViewById<View>(R.id.menu_btn).setOnClickListener { Sheets.menu(this) }
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
        urlEdit.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(urlEdit.text.toString())
                true
            } else {
                false
            }
        }
        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!editing) return
                urlClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refreshSuggestions(s?.toString() ?: "")
            }
        })
        urlClear.setOnClickListener { urlEdit.setText("") }
    }

    fun startEditing() {
        if (fullscreenTab != null) return
        editing = true
        val tab = current
        val text = when {
            tab == null || tab.isBlank -> ""
            else -> UrlUtils.googleQuery(tab.url) ?: tab.url
        }
        urlEdit.setText(text)
        urlEdit.selectAll()
        urlIcon.setImageResource(R.drawable.ic_search)
        suggestionsList.visibility = View.VISIBLE
        urlClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (!urlEdit.hasFocus()) urlEdit.requestFocus()
        showKeyboard(urlEdit)
        refreshSuggestions("")
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        suggestJob?.cancel()
        suggestionsList.visibility = View.GONE
        urlClear.visibility = View.GONE
        hideKeyboard()
        if (!gecko.requestFocus()) root.requestFocus()
        updateUrlBar()
    }

    private fun navigate(text: String) {
        val url = UrlUtils.toUrlOrSearch(text)
        if (url.isBlank()) return
        stopEditing()
        openInCurrent(url)
    }

    fun openInCurrent(url: String) {
        val tab = current ?: TabManager.newTab(url).also { return }
        TabManager.load(tab, url)
        gecko.requestFocus()
    }

    private fun refreshSuggestions(text: String) {
        suggestJob?.cancel()
        val pid = Profiles.currentId
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
            if (text.isNotBlank()) {
                delay(180)
                val google = withContext(Dispatchers.IO) { Suggestions.google(text) }
                    .filter { !it.equals(text, ignoreCase = true) }
                    .take(5)
                    .map { Suggestion(Suggestion.Kind.SEARCH, it, UrlUtils.searchUrl(it)) }
                suggestionAdapter.items = head + google + local
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
        urlEdit.setText(query ?: UrlUtils.display(url))
        urlIcon.setImageResource(
            when {
                tab == null || tab.isBlank || query != null -> R.drawable.ic_search
                tab.secure -> R.drawable.ic_lock
                else -> R.drawable.ic_globe
            },
        )
    }

    private fun updateProgress() {
        val tab = current
        if (tab == null || !tab.loading || fullscreenTab != null) {
            progress.visibility = View.INVISIBLE
        } else {
            progress.visibility = View.VISIBLE
            progress.progress = tab.progress.coerceIn(5, 100)
        }
    }

    fun updateShield() {
        val tab = current
        val level = AdBlock.levelFor(tab?.url)
        shieldIcon.setImageResource(if (level == AdLevel.OFF) R.drawable.ic_shield_off else R.drawable.ic_shield)
        shieldIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (level == AdLevel.OFF) R.color.shield_off else R.color.shield_on),
        )
        val badge = tab?.badge
        if (level != AdLevel.OFF && !badge.isNullOrBlank() && badge != "0") {
            shieldBadge.text = badge
            shieldBadge.visibility = View.VISIBLE
        } else {
            shieldBadge.visibility = View.GONE
        }
    }

    private fun updateProfileChip() {
        val p = Profiles.current()
        profileChip.text = Profiles.initial(p)
        profileChip.backgroundTintList = ColorStateList.valueOf(p.color)
        profileChip.contentDescription = "Profile: ${p.name}"
    }

    // ------------------------------------------------------------------ BrowserUi

    override fun onTabSelected(tab: Tab) {
        val session = tab.session
        if (session != null && gecko.session !== session) gecko.setSession(session)
        crashView.visibility = if (tab.crashed) View.VISIBLE else View.GONE
        if (fullscreenTab != null && fullscreenTab !== tab) exitFullscreen()
        closeFind()
        updateAll()
        if (tab.isBlank && tab.parentId == null && !isFinishing) {
            handler.post { if (current === tab && tab.isBlank) startEditing() }
        } else if (editing) {
            stopEditing()
        }
    }

    override fun onTabsChanged() {
        val n = TabManager.profileTabs().size
        tabsCount.text = if (n > 99) ":D" else n.toString()
        updateProfileChip()
    }

    override fun onTabUpdated(tab: Tab) {
        if (tab !== current) return
        updateUrlBar()
        updateProgress()
        updateShield()
        videoBtn.visibility = if (tab.media.isNotEmpty() && fullscreenTab == null) View.VISIBLE else View.GONE
        if (!tab.isBlank && editing && urlEdit.text.isEmpty() && tab.loading) stopEditing()
    }

    private fun updateAll() {
        val tab = current ?: return
        onTabUpdated(tab)
        onTabsChanged()
    }

    override fun releaseSession(session: GeckoSession) {
        if (gecko.session === session) gecko.releaseSession()
    }

    override fun onTabCrashed(tab: Tab) {
        if (tab === current) crashView.visibility = View.VISIBLE
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
            updateShield()
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(s: GeckoSession) = sheet.dismiss()
        }
        sheet.show()
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
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val link = element.linkUri
        val src = element.srcUri
        if (link != null) {
            actions += "Open in new tab" to { TabManager.newTab(link, parent = tab) }
            actions += "Open in background tab" to {
                TabManager.newTab(link, parent = tab, select = false)
                snack("Opened in a background tab")
            }
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
        fullscreenTab = tab
        stopEditing()
        toolbar.visibility = View.GONE
        progress.visibility = View.GONE
        findBar.visibility = View.GONE
        videoBtn.visibility = View.GONE
        hideSystemBars()
        if (prefs.autoLandscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        gestureLayer.gesturesEnabled = prefs.videoGestures
        updateKeepScreenOn()
        showSpeedChip()
    }

    private fun exitFullscreen() {
        if (fullscreenTab == null) return
        fullscreenTab = null
        toolbar.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        gestureLayer.gesturesEnabled = false
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        hud.visibility = View.GONE
        speedChip.visibility = View.GONE
        updateKeepScreenOn()
        updateAll()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs (e.g. playback speed) bring the system bars back on Android 7; hide them again.
        if (hasFocus && fullscreenTab != null) hideSystemBars()
    }

    override fun onVideoFullscreen(tab: Tab, width: Long, height: Long) {
        // Portrait videos (e.g. phone recordings) stay upright.
        if (fullscreenTab === tab && prefs.autoLandscape && height > width && width > 0) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideSpeedChip = Runnable { speedChip.visibility = View.GONE }

    private fun setupGestures() {
        speedChip.setOnClickListener {
            current?.let { Sheets.speed(this, it) }
        }
        gestureLayer.listener = object : GestureFrameLayout.Listener {
            override fun onSwipe(leftSide: Boolean, delta: Float) {
                if (leftSide) changeBrightness(delta) else changeVolume(delta)
            }

            override fun onSwipeEnd() {
                handler.removeCallbacks(hideHud)
                handler.postDelayed(hideHud, 700)
            }

            override fun onTap() {
                if (fullscreenTab != null) showSpeedChip()
            }
        }
    }

    private fun showSpeedChip() {
        val tab = current ?: return
        speedChip.text = "Speed ${formatSpeed(tab.speed)}"
        speedChip.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_speed), null, null, null)
        speedChip.visibility = View.VISIBLE
        handler.removeCallbacks(hideSpeedChip)
        handler.postDelayed(hideSpeedChip, 3000)
    }

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
            fullscreenTab != null -> tab?.session?.exitFullScreen() ?: exitFullscreen()
            findBar.visibility == View.VISIBLE -> closeFind()
            editing -> stopEditing()
            tab == null -> finish()
            tab.canGoBack && tab.session != null -> TabManager.goBack(tab)
            tab.parentId != null && TabManager.tabs.any { it.id == tab.parentId } -> TabManager.close(tab)
            else -> moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------------ helpers

    fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val bar = Snackbar.make(root, text, if (action != null) 4000 else Snackbar.LENGTH_SHORT)
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
        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"
    }
}
