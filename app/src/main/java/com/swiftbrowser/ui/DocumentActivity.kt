package com.swiftbrowser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.lang.ref.WeakReference

/**
 * A document on a screen of its own (2.8), not in a tab: the viewer page full screen, over the
 * screen it was opened from (Documents, Files, Downloads, the browser, another app). Its bar is the
 * page's own; Back closes the page's panels first, then this screen, which returns to where the
 * document was opened from.
 *
 * The page talks to Swift as it does in a tab, through a [Tab] of its own that is never in the tab
 * list (Docs answers it). The document keeps its own colours; only the viewer's bars follow Swift's.
 */
class DocumentActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var gecko: GeckoView
    private lateinit var spinner: ProgressBar
    private lateinit var findBar: LinearLayout
    private lateinit var findText: EditText
    private lateinit var findCount: TextView

    private var session: GeckoSession? = null
    private var tab: Tab? = null
    private var docId: String? = null
    private var incognito = false
    private var canGoBack = false

    /** Opened for another app or a home screen shortcut through the browser: Back leaves Swift. */
    private var leaveSwift = false

    /** The page's engine process crashed once already: a second crash shows the message instead. */
    private var revived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.color(this, R.color.page_bg)
        buildViews()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        SystemBars.keepHidden(this)
        if (!start(intent)) finish()
    }

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.color(this@DocumentActivity, R.color.page_bg)) }
        gecko = GeckoView(this)
        root.addView(gecko, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Until the viewer page is there (the engine may still be starting).
        spinner = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(Ui.accent(this@DocumentActivity)) }
        root.addView(spinner, FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER))
        buildFindBar()
        setContentView(root)
    }

    /** What the intent asks for: a document Swift knows (its id), or a file another app sends. */
    private fun start(i: Intent): Boolean {
        leaveSwift = i.getBooleanExtra(EXTRA_LEAVE, false)
        incognito = i.getBooleanExtra(EXTRA_INCOGNITO, false)
        val id = when (i.action) {
            Intent.ACTION_VIEW -> {
                val data = i.data ?: return false
                Docs.register(this, data, null, i.type, incognito = false).also {
                    if (it == null) Messages.show(this, "Swift can't show this kind of file")
                }
            }
            Docs.ACTION_OPEN_DOC -> i.getStringExtra(Docs.EXTRA_DOC_ID)?.takeIf { Docs.find(it) != null }.also {
                if (it == null) Messages.show(this, "Swift doesn't have this document any more")
            }
            else -> i.getStringExtra(EXTRA_ID)
        } ?: return false
        docId = id
        Docs.whenViewerReady { if (!isDestroyed) load(id) }
        return true
    }

    private fun load(id: String) {
        // "?screen": the page knows it is on this screen (a Back arrow in its bar).
        val url = Docs.viewerUrl(id)?.replace("/docs/view.html#", "/docs/view.html?screen#") ?: return
        val doc = Docs.find(id)
        val profile = Profiles.currentId
        val t = Tab(nextTabId--, profile, url, doc?.name ?: "Document", null, null, System.currentTimeMillis(), false, incognito)
        tab = t
        hosts[t] = WeakReference(this)
        openSession(t, url)
    }

    private fun openSession(t: Tab, url: String) {
        val builder = GeckoSessionSettings.Builder()
        if (!t.incognito) builder.contextId(Profiles.contextId(t.profileId))
        val s = GeckoSession(builder.usePrivateMode(t.incognito).viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build())
        s.navigationDelegate = navigation
        s.contentDelegate = content
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                spinner.visibility = View.GONE
            }
        }
        s.selectionActionDelegate = BasicSelectionActionDelegate(this)
        t.session = s
        Helper.attachSession(t, s)
        s.open(Engine.runtime)
        gecko.setSession(s)
        session = s
        s.loadUri(url)
    }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@DocumentActivity.canGoBack = canGoBack
        }

        // The viewer stays on its page; a link in the document opens in the browser.
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val uri = request.uri
            if (Docs.isViewerUrl(uri) || uri.startsWith("about:") || uri.startsWith("blob:") || uri.startsWith("data:")) {
                return GeckoResult.allow()
            }
            if (UrlUtils.isHttp(uri)) openInBrowser(uri)
            return GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            if (UrlUtils.isHttp(uri)) openInBrowser(uri)
            return null
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) = pageGone()

        override fun onKill(session: GeckoSession) = pageGone()
    }

    /** The page's engine process ended: the document loads again once; a second time, it says so. */
    private fun pageGone() {
        val t = tab ?: return
        L.w("Document viewer's page process ended")
        session?.let { s ->
            gecko.releaseSession()
            s.close()
        }
        session = null
        if (revived || isFinishing) {
            Messages.show(this, "The document stopped working. Open it again to read it.")
            return
        }
        revived = true
        spinner.visibility = View.VISIBLE
        openSession(t, t.url)
    }

    /** A link from the document: in a new tab of the browser, which comes to the front. */
    private fun openInBrowser(url: String) {
        TabManager.newTab(url, incognito = incognito)
        startActivity(Intent(this, MainActivity::class.java))
    }

    // ------------------------------------------------------------------ Back

    private fun back() {
        when {
            findBar.visibility == View.VISIBLE -> closeFind()
            // A panel, menu or slideshow of the page: the page's own history step closes it.
            canGoBack -> session?.goBack()
            else -> close()
        }
    }

    /** Leaves the document: to the screen it was opened from, or out of Swift for another app. */
    private fun close() {
        if (leaveSwift) moveTaskToBack(true)
        finish()
    }

    /** What the viewer page asks of this screen (Docs routes it here for its tab). */
    fun onPageAction(action: String) {
        when (action) {
            "find" -> openFind()
            "close" -> close()
            "documents" -> {
                startActivity(Intent(this, DocumentsActivity::class.java))
                finish()
            }
        }
    }

    // ------------------------------------------------------------------ find (Word, text and the rest)

    private fun buildFindBar() {
        val surface = Palette.color(this, R.color.toolbar_bg)
        val text = Palette.color(this, R.color.text_primary)
        val muted = Palette.color(this, R.color.text_secondary)
        findText = EditText(this).apply {
            hint = "Find in document"
            setTextColor(text)
            setHintTextColor(muted)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(Ui.dp(this@DocumentActivity, 16), 0, Ui.dp(this@DocumentActivity, 8), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = find(s?.toString().orEmpty(), backwards = false, again = false)
            })
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    find(this.text.toString(), backwards = false, again = true)
                    true
                } else {
                    false
                }
            }
        }
        findCount = TextView(this).apply {
            setTextColor(muted)
            textSize = 13f
            setPadding(Ui.dp(this@DocumentActivity, 4), 0, Ui.dp(this@DocumentActivity, 4), 0)
        }
        fun button(icon: Int, label: String, click: () -> Unit) = ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
            setImageDrawable(Ui.icon(this@DocumentActivity, icon, text))
            contentDescription = label
            setOnClickListener { click() }
        }
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            elevation = Ui.dpf(this@DocumentActivity, 4f)
            visibility = View.GONE
            val size = Ui.dp(this@DocumentActivity, 44)
            addView(findText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(findCount)
            addView(button(R.drawable.ic_up, "Previous") { find(findText.text.toString(), backwards = true, again = true) }, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_down, "Next") { find(findText.text.toString(), backwards = false, again = true) }, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_close, "Close") { closeFind() }, LinearLayout.LayoutParams(size, size))
        }
        root.addView(findBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56), Gravity.TOP))
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findText.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(findText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        findText.setText("")
        findCount.text = ""
        session?.finder?.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(findText.windowToken, 0)
    }

    private fun find(text: String, backwards: Boolean, again: Boolean) {
        val finder = session?.finder ?: return
        if (text.isEmpty()) {
            finder.clear()
            findCount.text = ""
            return
        }
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DIM_PAGE
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
        finder.find(if (again) null else text, flags).accept { r ->
            if (r == null || isFinishing) return@accept
            findCount.text = if (r.found) "${r.current}/${r.total}" else "0/0"
        }
    }

    // ------------------------------------------------------------------ storage (saving to Downloads)

    private var afterStorage: (() -> Unit)? = null

    private val storageAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        val run = afterStorage
        afterStorage = null
        if (granted) run?.invoke() else Messages.show(this, "Swift needs storage access to save to Downloads")
    }

    /** [action] once Swift may write to Downloads (asked the first time). */
    fun withStorage(action: () -> Unit) {
        if (com.swiftbrowser.download.Downloads.hasStoragePermission(this)) {
            action()
        } else {
            afterStorage = action
            storageAsk.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onResume() {
        super.onResume()
        NightLight.attach(this)
        session?.setActive(true)
    }

    override fun onPause() {
        session?.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        tab?.let { t ->
            hosts.remove(t)
            Helper.detachTab(t)
            t.session = null
        }
        session?.let { s ->
            gecko.releaseSession()
            s.close()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "doc_id"
        const val EXTRA_INCOGNITO = "incognito"
        const val EXTRA_LEAVE = "leave_swift"

        /** Ids of the viewer's tabs: below zero, so they never meet a real tab's. */
        private var nextTabId = -1L

        private val hosts = HashMap<Tab, WeakReference<DocumentActivity>>()

        /** The screen showing [tab]'s document, when it is one of these (not a tab of the browser). */
        fun hostOf(tab: Tab): DocumentActivity? = hosts[tab]?.get()

        /** Opens document [id] over [from]. [leaveSwift]: Back goes out of Swift (another app, a shortcut). */
        fun open(from: Context, id: String, incognito: Boolean = false, leaveSwift: Boolean = false) {
            val i = Intent(from, DocumentActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_INCOGNITO, incognito)
                .putExtra(EXTRA_LEAVE, leaveSwift)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }
    }
}
