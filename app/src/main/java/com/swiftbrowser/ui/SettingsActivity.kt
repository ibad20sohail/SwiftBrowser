package com.swiftbrowser.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Backup
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.Passwords
import com.swiftbrowser.core.SearchEngine
import com.swiftbrowser.core.SiteSettings
import com.swiftbrowser.engine.DataSaver
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.files.FilesSettings
import com.swiftbrowser.media.PlayerSettings
import com.swiftbrowser.torrent.TorrentSettings
import com.swiftbrowser.torrent.Torrents
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.Updates
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.engine.GpuInfo
import com.swiftbrowser.engine.Power
import com.swiftbrowser.media.PlaybackService
import com.swiftbrowser.media.SleepTimer
import com.swiftbrowser.tabs.TabManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.StorageController
import java.io.File
import kotlin.math.roundToInt

/**
 * Settings, in the look of the rest of the app: a short main page (search, the switches changed
 * most as tiles, and the topics with what is set in each), and a page per topic. Every setting is
 * described once below and appears both on its topic's page and in search results.
 */
class SettingsActivity : AppCompatActivity() {

    private enum class Topic(val title: String, val icon: Int) {
        LOOK("Look & screen", R.drawable.ic_palette),
        NEWS("Home & news", R.drawable.ic_news),
        BROWSING("Browsing & tabs", R.drawable.ic_tab),
        ADBLOCK("Ad blocking", R.drawable.ic_shield),
        SPEED("Speed, data & battery", R.drawable.ic_speed),
        VIDEO("Videos & music", R.drawable.ic_movie),
        DOWNLOADS("Downloads", R.drawable.ic_download),
        FILES("Files", R.drawable.ic_folder),
        PRIVACY("Privacy & security", R.drawable.ic_lock),
        VPN("VPN", R.drawable.ic_vpn),
        STORAGE("Storage & data", R.drawable.ic_storage),
        ABOUT("About Swift", R.drawable.ic_info),
    }

    /** One setting: where it goes, words it can be found by, and how its row is built. */
    private class Def(
        val topic: Topic,
        val section: String?,
        val title: String,
        val keywords: String,
        val build: Section.() -> Unit,
    )

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var container: FrameLayout
    private var topic: Topic? = null

    /** Bring the rows on screen up to date after something changed (one change can affect others). */
    private val refreshers = ArrayList<() -> Unit>()
    private val resultRefreshers = ArrayList<() -> Unit>()

    /** What Swift keeps on the phone; null until measured. */
    private var usage: com.swiftbrowser.core.Storage.Usage? = null
    private val networkChanged: () -> Unit = { refreshAll() }
    private val vpnChanged: () -> Unit = { refreshAll() }

    /** Android's one-time "allow VPN" question. */
    private val vpnPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        VpnUi.onPermission(this, it.resultCode == RESULT_OK)
    }

    /** A Proton VPN setup file (.conf) to add. */
    private val vpnFilePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addVpnFile(uri)
    }

    private val defs: List<Def> by lazy { buildDefs() }

    /**
     * 3.0: what each setting's rows say (titles and summaries), for search; read once, the first
     * time something is searched, without making the rows.
     */
    private val rowTexts: Map<Def, String> by lazy {
        val scratch = LinearLayout(this)
        defs.associateWith { d ->
            val text = StringBuilder()
            try {
                d.build(Section(scratch, ArrayList(), probe = text))
            } catch (e: Exception) {
                com.swiftbrowser.core.L.w("Settings search couldn't read ${d.title}", e)
            }
            text.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        prefs = SwiftApp.prefs
        val bg = color(R.color.settings_bg)
        window.statusBarColor = bg
        // 3.0.4: the page's colour is the window's own: the rows' pieces are drawn over it, and a slow
        // graphics chip doesn't fill the screen twice under them.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 3.0.5: the one title bar of every screen (see topBar: the same title as the lists' and Files' bars).
        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Ui.BAR_HEIGHT)))
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        SystemBars.keepHidden(this)
        // A topic to open straight away (the news' settings button), or the one open before.
        val saved = savedInstanceState?.getString(STATE_TOPIC) ?: intent.getStringExtra(EXTRA_TOPIC)
        show(Topic.entries.firstOrNull { it.name == saved }, animate = false)
        // Back where it was after the screen was redrawn (a new accent colour, light or dark).
        savedInstanceState?.getInt(STATE_SCROLL, 0)?.takeIf { it > 0 }?.let { y ->
            (container.getChildAt(0) as? ScrollView)?.let { page -> page.post { page.scrollTo(0, y) } }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // A search is cleared first (2.6).
                val box = settingsSearch
                when {
                    // 2.9: opened for one part only (Files' own settings): Back returns there.
                    topic != null && intent.getBooleanExtra(EXTRA_ONLY, false) -> finish()
                    topic != null -> show(null)
                    box != null && box.text.isNotEmpty() -> box.setText("")
                    else -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        topic?.let { outState.putString(STATE_TOPIC, it.name) }
        (container.getChildAt(container.childCount - 1) as? ScrollView)?.let { outState.putInt(STATE_SCROLL, it.scrollY) }
    }

    override fun onResume() {
        super.onResume()
        NightLight.attach(this)
        computeStorage()
        countPasswords()
        refreshAll()
        // Data saver says whether the phone is on mobile data right now.
        DataSaver.networkListeners += networkChanged
        com.swiftbrowser.vpn.Vpn.addListener(vpnChanged)
    }

    override fun onPause() {
        DataSaver.networkListeners -= networkChanged
        com.swiftbrowser.vpn.Vpn.removeListener(vpnChanged)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    fun openInBrowser(url: String) {
        setResult(RESULT_OK, Intent().putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_NEW_TAB, true))
        finish()
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        resultRefreshers.forEach { it() }
    }

    // ------------------------------------------------------------------ pages

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(16), 0)
        addView(ImageButton(this@SettingsActivity).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(color(R.color.text_primary))
            setBackgroundResource(borderless())
            contentDescription = "Back"
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            Ui.pressable(this, Motion.PRESS_ICON, morph = false)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this@SettingsActivity).apply {
            // The same size as the other screens' titles (2.6). 3.0.4: in bold. 3.0.5: the same as
            // the lists' and Files' bars (Swift.ListTitle, the scale's TITLE).
            Ui.size(this, Ui.Type.TITLE, keepFont = true)
            setTypeface(Ui.BOLD)
            setTextColor(color(R.color.text_primary))
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun show(t: Topic?, animate: Boolean = true) {
        val forward = t != null
        topic = t
        refreshers.clear()
        resultRefreshers.clear()
        hideKeyboard()
        setPageName(t?.title ?: "Settings", animate)
        val page = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            // 3.0.5: the glow at its ends in the accent chosen.
            Palette.glow(this)
            // On a turned phone the page doesn't stretch across the whole screen.
            addView(MaxWidthLayout(this@SettingsActivity).apply { addView(Palette.paint(if (t == null) mainPage() else topicPage(t))) })
        }
        // 3.0.4: the page opens with its large title; scrolled, it goes up under the bar, whose own
        // title fades in as it goes (and out again back at the top). A page too short to scroll keeps it.
        val big = pageTitle
        page.setOnScrollChangeListener { _, _, y, _, _ -> followTitle(big, y) }
        // 3.0.5: a page still on its way out (two quick taps) goes now, so none can stay behind the new one.
        while (container.childCount > 1) container.removeViewAt(0)
        val old = container.getChildAt(0)
        container.addView(page)
        // The search box waits for a tap rather than taking the focus (and a blinking cursor).
        container.isFocusableInTouchMode = true
        container.requestFocus()
        if (animate && old != null) {
            // 3.0.5: the same change as anywhere in Swift (Motion): the new page comes in with a fade
            // from the side it is reached from, over the old one, which fades where it is.
            Motion.pageIn(page, if (forward) Motion.FORWARD else Motion.BACK)
            Motion.pageOut(old) { container.removeView(old) }
        } else if (old != null) {
            container.removeView(old)
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // 3.0.5: the one side margin of every screen.
        setPadding(dp(Ui.MARGIN), dp(4), dp(Ui.MARGIN), dp(28))
    }

    /** 3.0.4: the large title of the page being made (see [largeTitle]), for [show] to follow as it scrolls. */
    private var pageTitle: TextView? = null

    /** 3.0.4: the page's large title, at its top. 3.0.5: lined up with the headings and row icons under it (and the lists' titles). */
    private fun largeTitle(col: LinearLayout, text: String) {
        val big = Ui.text(this, Ui.Type.HEADLINE, text, lines = 2).apply { setPadding(dp(Ui.ROW_INSET), dp(8), dp(Ui.ROW_INSET), dp(12)) }
        col.addView(big)
        pageTitle = big
    }

    /**
     * 3.0.4: the page scrolled to [y]: the large title fades as it goes up under the bar, and the bar's
     * own title fades in over the second half of the way.
     */
    private fun followTitle(big: View?, y: Int) {
        if (big == null || big.height <= 0) return
        val gone = ((y - big.top).toFloat() / big.height).coerceIn(0f, 1f)
        big.alpha = 1f - gone
        setBarTitle(((gone - 0.5f) * 2f).coerceIn(0f, 1f))
    }

    /** The name the bar's title takes once the page being left has faded it out. */
    private var comingTitle: String? = null

    /** A new page's name for the bar. If the old page's name showed there, it fades out with its page first. */
    private fun setPageName(name: String, animate: Boolean) {
        if (animate && titleView.alpha > 0f && Motion.enabled(this)) {
            comingTitle = name
            titleView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            Motion.anim(titleView, Motion.PAGE_OUT_MS, Motion.exit).alpha(0f).withEndAction {
                comingTitle?.let { titleView.text = it }
                comingTitle = null
            }.start()
        } else {
            comingTitle = null
            titleView.animate().cancel()
            titleView.text = name
            setBarTitle(0f)
        }
    }

    /** The bar's own title at [alpha]; TalkBack reads it only while it shows (the large title says it otherwise). */
    private fun setBarTitle(alpha: Float) {
        // Scrolled while the old name was still fading out: the new one, now.
        comingTitle?.let {
            titleView.animate().cancel()
            titleView.text = it
            comingTitle = null
        }
        if (titleView.alpha == alpha) return
        titleView.alpha = alpha
        titleView.importantForAccessibility = if (alpha > 0f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun mainPage(): View {
        val col = column()
        shownResults = null
        largeTitle(col, "Settings")
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val normal = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val search = EditText(this).apply {
            hint = "Search settings"
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            // 3.0.5: the one search box of every screen (3.0.4: in the card colour, like the rows under it).
            Ui.searchBox(this)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                val cleared = q.isEmpty() && normal.visibility != View.VISIBLE
                normal.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
                results.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                showResults(results, q)
                if (cleared) {
                    // The same short fade as results coming in, for the same reason without a layer.
                    normal.animate().cancel()
                    normal.alpha = 0.4f
                    Motion.anim(normal, Motion.SHORT, Motion.enter).alpha(1f).start()
                }
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        settingsSearch = search
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Ui.SEARCH_HEIGHT)).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })

        // The topics in the same order, in four groups rather than one long wall (About alone).
        var card = card()
        var topics = Section(card)
        normal.addView(card)
        for (t in Topic.entries) {
            topics.action(t.icon, t.title, { keepParts(topicSummary(t)) }, badge = true) { show(t) }
            if (t == Topic.BROWSING || t == Topic.FILES || t == Topic.STORAGE) {
                normal.addView(spacer())
                card = card()
                topics = Section(card)
                normal.addView(card)
            }
        }
        normal.addView(TextView(this).apply {
            text = "Swift ${BuildConfig.VERSION_NAME}"
            Ui.size(this, Ui.Type.CAPTION)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(18), 0, 0)
        })
        col.addView(normal)
        col.addView(results)
        return col
    }

    /** The settings the results show now; null with no results up. */
    private var shownResults: List<Def>? = null

    private fun showResults(results: LinearLayout, query: String) {
        if (query.isEmpty()) {
            results.removeAllViews()
            resultRefreshers.clear()
            shownResults = null
            return
        }
        // Everyday words find the settings they are about ("battery" finds Lite mode and Data saver).
        // 3.0: whole words, every word of the search, and what the rows say (SettingsSearch).
        val found = defs.filter { d -> SettingsSearch.matches(query, SettingsSearch.Entry(d.title, rowTexts[d].orEmpty(), d.keywords, d.topic.title)) }
        // Another letter that finds the same settings changes nothing (the colours panel and the ad
        // bars aren't made again, and nothing blinks).
        if (found.isNotEmpty() && found == shownResults) return
        // "Nothing found" again: only its words change.
        val quiet = found.isEmpty() && shownResults?.isEmpty() == true
        shownResults = found
        results.removeAllViews()
        resultRefreshers.clear()
        // Each new word brings its results in with a short fade (2.3). No layer: the list can be
        // taller than the GPU's biggest picture.
        if (!quiet) {
            results.animate().cancel()
            results.alpha = 0.4f
            Motion.anim(results, Motion.SHORT, Motion.enter).alpha(1f).start()
        }
        if (found.isEmpty()) {
            results.addView(Ui.emptyState(this, R.drawable.ic_search, "Nothing found for \"$query\"", "Try another word, such as \"dark\", \"ads\" or \"video\"."))
            return
        }
        for ((t, items) in found.groupBy { it.topic }) {
            // The first heading sits as close under the box as the topics do.
            results.addView(label(t.title).apply { if (results.childCount == 0) setPadding(paddingLeft, dp(4), paddingRight, paddingBottom) })
            val card = card()
            val section = Section(card, resultRefreshers)
            items.forEach { it.build(section) }
            results.addView(card)
        }
    }

    private fun topicPage(t: Topic): View {
        val col = column()
        largeTitle(col, t.title)
        if (t == Topic.ABOUT) col.addView(aboutHeader())
        addDefs(col, defs.filter { it.topic == t })
        return col
    }

    /** Settings as cards, one per section (with its name above). */
    private fun addDefs(col: LinearLayout, list: List<Def>) {
        var section: Section? = null
        var lastLabel: String? = null
        for (def in list) {
            if (section == null || def.section != lastLabel) {
                lastLabel = def.section
                if (def.section != null) col.addView(label(def.section)) else if (section != null) col.addView(spacer())
                val card = card()
                col.addView(card)
                section = Section(card)
            }
            def.build(section)
        }
    }

    /**
     * 3.0.1: each part of a topic's summary stays on one line ("Back up" was split as "Back" and
     * "up"); lines break only between the parts.
     */
    // 3.0.4: only short parts are kept together; a long one ("Change your country for free (Proton
    // VPN)") is longer than a line, and glued together it was cut wherever the line ended ("VP" "N)").
    private fun keepParts(summary: String) = summary.split(" · ").joinToString(" · ") { if (it.length <= 24) it.replace(' ', '\u00A0') else it }

    /** What is set in a topic, in a few words. */
    private fun topicSummary(t: Topic): String = when (t) {
        Topic.LOOK -> listOfNotNull(
            Looks.modeLabel(prefs.themeMode),
            Looks.accent.label,
            if (prefs.fullScreen) "Full screen" else null,
            if (prefs.toolbarAtBottom) "Bar at the bottom" else null,
        ).joinToString(" · ")
        Topic.NEWS -> if (prefs.newsEnabled) "News from ${com.swiftbrowser.news.News.countryName()}" else "News off"
        Topic.BROWSING -> listOfNotNull(
            SearchEngine.current.label,
            if (prefs.inactiveDays > 0) "Inactive tabs after ${prefs.inactiveDays} days" else null,
        ).joinToString(" · ")
        Topic.ADBLOCK -> {
            val n = AdBlock.overrides().size
            val week = com.swiftbrowser.adblock.AdStats.week()
            listOfNotNull(
                AdBlock.global.label,
                if (week > 0 && AdBlock.global != AdLevel.OFF) "${java.text.NumberFormat.getIntegerInstance().format(week)} blocked this week" else null,
                if (prefs.hideCookieNotices && AdBlock.global != AdLevel.OFF) "Cookie pop-ups hidden" else null,
                if (n > 0) "$n ${if (n == 1) "site" else "sites"} with their own level" else null,
            ).joinToString(" · ")
        }
        Topic.SPEED -> listOfNotNull(
            if (Power.saving) "Saving battery now" else null,
            when {
                prefs.liteMode || Power.saving -> "Lite mode"
                prefs.sitesAsDesigned -> "Sites as designed"
                else -> "Lighter sites"
            },
            if (prefs.maxLiveTabs == 1 || Power.saving) "1 tab ready to open" else "${prefs.maxLiveTabs} tabs ready to open",
        ).joinToString(" · ")
        Topic.VIDEO -> listOfNotNull(
            if (prefs.keepPlaying) "Keeps playing in the background" else null,
            if (prefs.continueWatching) "Continue watching" else null,
            if (prefs.videoTaps) "Double-tap to skip" else null,
        ).joinToString(" · ").ifEmpty { "Videos on websites and Swift's player" }
        Topic.DOWNLOADS -> listOfNotNull(
            if (prefs.confirmDownloads) "Asks first" else null,
            if (prefs.wifiOnlyDownloads) "Big ones wait for Wi-Fi" else null,
            "Torrents",
        ).joinToString(" · ")
        Topic.PRIVACY -> listOfNotNull(
            if (prefs.httpsOnly) "Secure connections" else null,
            "Passwords",
            "Site settings",
        ).joinToString(" · ")
        Topic.FILES -> "Folders, recycle bin, Private"
        Topic.VPN -> VpnUi.label().let { if (com.swiftbrowser.vpn.Vpn.locations().isEmpty()) "Change your country for free (Proton VPN)" else it }
        Topic.STORAGE -> (usage?.let { "Swift uses ${UrlUtils.formatBytes(it.total)} · " } ?: "") + "Clear data · Back up"
        Topic.ABOUT -> "Swift ${BuildConfig.VERSION_NAME} · Updates · Problem report · Licences"
    }

    // ------------------------------------------------------------------ all the settings

    private fun buildDefs(): List<Def> {
        val list = ArrayList<Def>()
        fun def(t: Topic, section: String?, title: String, keywords: String = "", build: Section.() -> Unit) {
            list += Def(t, section, title, keywords, build)
        }

        // Look & screen
        def(Topic.LOOK, null, "Theme", "dark mode light night black colours evening") {
            action(R.drawable.ic_dark, "Theme", { themeText() }) { chooseTheme() }
        }
        def(Topic.LOOK, "Colours", "Colours", "color colour accent background black amoled navy warm blue green purple orange red teal pink indigo amber cyan brown graphite custom own personal") {
            custom { ColoursPanel.build(this@SettingsActivity) }
        }
        def(Topic.LOOK, null, "Night light", "night light warm amber blue light eyes sleep evening") {
            action(R.drawable.ic_night_light, "Night light", {
                val mode = prefs.nightLight
                if (mode == NightLight.OFF) "Off: a warm screen at night, easier on the eyes"
                else "${if (mode == NightLight.AT_NIGHT) "7 pm to 7 am" else "Always"} · ${NightLight.strengthLabel(prefs.nightLightStrength).lowercase()}"
            }) { nightLightSheet() }
        }
        def(Topic.LOOK, null, "Text size", "font letters bigger smaller zoom") {
            action(R.drawable.ic_text_size, "Text size", { textSizeText(prefs.textScale) }) { chooseTextSize() }
        }
        def(Topic.LOOK, null, "Full screen", "status bar navigation bar immersive hide bars") {
            switch(R.drawable.ic_fullscreen, "Full screen", { "Hides your phone's bars at the top and bottom of the screen" }, checked = { prefs.fullScreen }) {
                prefs.fullScreen = it
                SystemBars.apply(this@SettingsActivity)
            }
        }
        def(Topic.LOOK, null, "Zoom on all sites", "pinch zoom") {
            switch(R.drawable.ic_zoom, "Zoom on all sites", { "Pinch to zoom, even on sites that turn it off" }, checked = { prefs.forceZoom }) {
                prefs.forceZoom = it
                Engine.setForceZoom(it)
            }
        }
        def(Topic.LOOK, "Address bar", "Address bar at the bottom", "toolbar bottom one hand reach position") {
            switch(R.drawable.ic_toolbar_bottom, "Address bar at the bottom", { "Easier to reach with one hand" }, checked = { prefs.toolbarAtBottom }) {
                prefs.toolbarAtBottom = it
            }
        }
        def(Topic.LOOK, "Address bar", "Website colours in the address bar", "theme color colour site website brand tint adaptive vivaldi address bar toolbar") {
            switch(R.drawable.ic_bar_colour, "Website colours in the address bar", { "The bar takes on each site's own colour" }, checked = { prefs.siteColours }) {
                prefs.siteColours = it
            }
        }
        def(Topic.LOOK, "Address bar", "Address bar buttons", "toolbar buttons home house profile tabs menu reload share bookmark new tab scan qr arrange customize") {
            action(R.drawable.ic_toolbar_buttons, "Address bar buttons", { toolbarButtonsText() }) { arrangeToolbar() }
        }
        def(Topic.LOOK, "Address bar", "Address bar swipes", "swipe up down hide show address bar toolbar gesture") {
            action(R.drawable.ic_gesture, "Address bar swipes", { barSwipesText(prefs.barSwipes) }) { chooseBarSwipes() }
        }
        def(Topic.LOOK, "Address bar", "Hide the bar while scrolling", "toolbar address bar url bar top") {
            switch(R.drawable.ic_toolbar, "Hide the bar while scrolling", { "More room for the page; scroll back a little to bring it back" }, checked = { prefs.hideToolbarOnScroll }) {
                prefs.hideToolbarOnScroll = it
            }
        }
        def(Topic.LOOK, "Address bar", "Bar follows your finger", "toolbar animation smooth chrome follow finger slides") {
            switch(
                R.drawable.ic_touch, "Bar follows your finger",
                { if (prefs.toolbarFollowsScroll) "It slides away and back as far as you scroll" else "Off: it hides and comes back in one go" },
                details = "On, the address bar slides away and back exactly as far as you scroll, and finishes with a short " +
                    "slide when you let go. Off, it hides or comes back in one go after a clear scroll.\n\n" +
                    "Turn it off if scrolling feels less smooth with it.",
                enabled = { prefs.hideToolbarOnScroll },
                checked = { prefs.toolbarFollowsScroll },
            ) { prefs.toolbarFollowsScroll = it }
        }
        def(Topic.LOOK, "Menu", "Arrange the menu", "menu buttons order arrange move hide customize more pages") {
            action(R.drawable.ic_menu, "Arrange the menu", { "Hold any button in the menu to move it, or drag it to the top to hide it" }) { arrangeMenu() }
        }
        def(Topic.LOOK, "Menu", "Reset the menu", "menu buttons default reset order") {
            action(R.drawable.ic_reset, "Reset the menu", { "Every button back where it started" }) { resetMenu() }
        }
        def(Topic.FILES, null, "Show Files in the app drawer", "icon launcher home screen app list drawer documents files apps own app") {
            switch(
                R.drawable.ic_folder, "Show Files in the app drawer",
                { "Its own app icon next to Swift's, to open it straight away" },
                checked = { AppIcons.shown(this@SettingsActivity) },
            ) { AppIcons.show(this@SettingsActivity, it) }
        }
        // 2.9: the file manager (FilesSettings).
        def(Topic.FILES, null, "Files opens at", "start page last folder open where") {
            action(R.drawable.ic_home, "Files opens at", { if (FilesSettings.startAt == FilesSettings.START_HOME) "Its start page" else "The last folder you were in" }) {
                val keys = listOf(FilesSettings.START_HOME, FilesSettings.START_LAST)
                choose("Files opens at", listOf("Its start page", "The last folder you were in"), keys.indexOf(FilesSettings.startAt)) { i ->
                    FilesSettings.startAt = keys[i]
                }
            }
        }
        def(Topic.FILES, null, "Upload from Swift's Files", "upload attach choose file picker website android default phone file manager") {
            switch(R.drawable.ic_upload, "Upload from Swift's Files", {
                if (prefs.uploadFromFiles) "A website's upload button opens Swift's Files" else "Off: a website's upload button opens your phone's own file picker"
            }, checked = { prefs.uploadFromFiles }) {
                prefs.uploadFromFiles = it
            }
        }
        def(Topic.FILES, null, "Show hidden files", "hidden dot files system") {
            switch(R.drawable.ic_visibility, "Show hidden files", { "Also shows files and folders normally kept out of sight" }, checked = { FilesSettings.showHidden }) {
                FilesSettings.showHidden = it
            }
        }
        def(Topic.FILES, null, "Show folders as tiles", "grid list view tiles") {
            switch(R.drawable.ic_grid_view, "Show folders as tiles", { if (FilesSettings.grid) "Tiles with pictures" else "Off: a simple list" }, checked = { FilesSettings.grid }) {
                FilesSettings.grid = it
            }
        }
        // 3.0.4: two folders next to each other with the phone sideways (also the button in Files' bar).
        def(Topic.FILES, null, "Two folders side by side", "two folders panes side by side dual split landscape sideways copy move") {
            switch(R.drawable.ic_two_panes, "Two folders side by side", {
                if (FilesSettings.twoPanes) "With your phone sideways, to copy and move between them" else "Off: one folder at a time"
            }, checked = { FilesSettings.twoPanes }) {
                FilesSettings.twoPanes = it
            }
        }
        def(Topic.FILES, null, "Sort folders by", "sort order name date size type") {
            action(R.drawable.ic_sort, "Sort folders by", { FilesSettings.sortLabels.getOrNull(FilesSettings.sort) ?: "" }) {
                choose("Sort folders by", FilesSettings.sortLabels, FilesSettings.sort) { i -> FilesSettings.sort = i }
            }
        }
        def(Topic.FILES, null, "Picture previews", "thumbnails previews pictures videos") {
            switch(R.drawable.ic_image, "Picture previews", { "Small pictures of photos and videos in the lists" }, checked = { FilesSettings.thumbnails }) {
                FilesSettings.thumbnails = it
            }
        }
        def(Topic.FILES, "Recycle bin", "Use the recycle bin", "recycle bin trash deleted restore undo") {
            switch(R.drawable.ic_files_bin, "Use the recycle bin", { if (FilesSettings.useRecycleBin) "Deleted files can be brought back for ${FilesSettings.binDays} days" else "Off: deleting is for good" }, checked = { FilesSettings.useRecycleBin }) {
                FilesSettings.useRecycleBin = it
            }
        }
        def(Topic.FILES, "Recycle bin", "Keep deleted files", "recycle bin days keep") {
            action(R.drawable.ic_history, "Keep deleted files", { "${FilesSettings.binDays} days, then they go for good" }) {
                choose("Keep deleted files", FilesSettings.BIN_DAY_CHOICES.map { "$it days" }, FilesSettings.BIN_DAY_CHOICES.indexOf(FilesSettings.binDays)) { i ->
                    val days = FilesSettings.BIN_DAY_CHOICES[i]
                    if (days >= FilesSettings.binDays) {
                        FilesSettings.setBinDays(this@SettingsActivity, days)
                    } else {
                        // 3.0: a shorter time deletes the older things for good at once: asked first, with how many and how much.
                        FilesSettings.binDue(this@SettingsActivity, days) { due ->
                            if (isFinishing) return@binDue
                            if (due.count == 0) {
                                FilesSettings.setBinDays(this@SettingsActivity, days)
                                refreshAll()
                                return@binDue
                            }
                            val what = if (due.count == 1) "1 item" else "${due.count} items"
                            Dialogs.confirm(
                                this@SettingsActivity, "Delete $what for good?",
                                "$what (${UrlUtils.formatBytes(due.bytes)}) " + (if (due.count == 1) "has" else "have") +
                                    " been in the recycle bin longer than $days days and will be deleted for good.",
                                "Delete", danger = true,
                            ) {
                                FilesSettings.setBinDays(this@SettingsActivity, days)
                                refreshAll()
                            }
                        }
                    }
                }
            }
        }
        def(Topic.FILES, "Recycle bin", "Empty the recycle bin", "empty recycle bin trash delete forever") {
            action(R.drawable.ic_delete, "Empty the recycle bin", { "Deletes everything in it for good" }) {
                Dialogs.confirm(this@SettingsActivity, "Empty the recycle bin?", "Everything in it is deleted for good. This can't be undone.", "Empty", danger = true) {
                    FilesSettings.emptyBin(this@SettingsActivity) { n -> snack(if (n > 0) "Deleted $n for good" else "The recycle bin was empty") }
                }
            }
        }
        def(Topic.FILES, "Private", "Private folder lock", "private folder lock pin screen lock forgot reset") {
            // 3.0: each state in words (it said "screen lock" with no screen lock and no PIN); a PIN keeps Private once chosen.
            action(R.drawable.ic_lock, "Private folder lock", {
                when (FilesSettings.privateLockKind(this@SettingsActivity)) {
                    FilesSettings.LOCK_PIN -> FilesSettings.privateResetLeft()?.let { left ->
                        if (left > 0) "Swift's PIN · a reset was asked for: it can be done in ${PrivateLock.duration(left)}"
                        else "Swift's PIN · a reset was asked for: open Private and tap Forgot PIN to choose a new one"
                    } ?: "Swift's PIN · tap to change it"
                    FilesSettings.LOCK_SCREEN -> "Your phone's screen lock"
                    else -> "Not set up yet: tap to choose a PIN"
                }
            }) {
                when (FilesSettings.privateLockKind(this@SettingsActivity)) {
                    FilesSettings.LOCK_SCREEN -> snack("Private opens with your phone's screen lock. Set or change it in Android's settings.")
                    // A PIN: change it (the old one first). None yet: choose one.
                    else -> FilesSettings.changePin(this@SettingsActivity)
                }
            }
        }
        def(Topic.LOOK, "Feel", "Light vibration", "haptic vibrate feedback touch") {
            switch(R.drawable.ic_vibration, "Light vibration", { "A short buzz when you hold something or drag a tab" }, checked = { prefs.haptics }) {
                prefs.haptics = it
            }
        }

        // Home & news
        def(Topic.NEWS, null, "Show news", "news headlines stories home page feed") {
            switch(R.drawable.ic_news, "Show news", { "Headlines on the home page" }, checked = { prefs.newsEnabled }) {
                prefs.newsEnabled = it
            }
        }
        def(Topic.NEWS, null, "Continue watching and reading", "continue unfinished videos articles reading resume home") {
            switch(
                R.drawable.ic_reopen, "Continue watching and reading",
                { "What you left half-way: web videos and articles on the home page, your own videos and music in Files" },
                checked = { prefs.homeContinue },
            ) {
                prefs.homeContinue = it
            }
        }
        // 3.0.2: the parts of the home page in another order (nothing is left out).
        def(Topic.NEWS, null, "Order of the home page", "order arrange move reorder home sections shortcuts continue news first") {
            action(R.drawable.ic_swap, "Order of the home page", {
                com.swiftbrowser.core.HomeOrder.parse(prefs.homeOrder).joinToString(" · ") { com.swiftbrowser.core.HomeOrder.label(it) }
            }) { chooseHomeOrder() }
        }
        // 3.0.4: a gradient or a picture of your own behind the home page.
        def(Topic.NEWS, null, "Background of the home page", "background wallpaper picture photo image gradient colour color home page opacity transparency clear fade dim") {
            action(R.drawable.ic_image, "Background of the home page", { HomeBackground.label(HomeBackground.current(this@SettingsActivity)) }) { chooseHomeBackground() }
        }
        def(Topic.NEWS, "News", "News country", "country region edition pakistan india uk us") {
            action(R.drawable.ic_globe, "Country", { newsCountryText() }) { chooseNewsCountry() }
        }
        def(Topic.NEWS, "News", "News topics", "topics sports tech business world entertainment health science") {
            action(R.drawable.ic_tune, "Topics", { newsTopicsText() }) { chooseNewsTopics() }
        }
        def(Topic.NEWS, "News", "Your news sites", "sites feed rss add own sources blogs") {
            action(R.drawable.ic_add, "Your news sites", { newsSitesText() }) { editNewsSites() }
        }
        def(Topic.NEWS, "News", "Hidden news sites", "hidden blocked sources sites show again") {
            action(R.drawable.ic_block, "Hidden sites", { newsHiddenText() }) { editHiddenNews() }
        }

        // Browsing & tabs
        def(Topic.BROWSING, "Search", "Search engine", "google duckduckgo bing startpage") {
            action(SearchEngine.current.icon, "Search engine", { SearchEngine.current.label }, icon = { SearchEngine.current.icon }) { chooseSearchEngine() }
        }
        def(Topic.BROWSING, "Search", "Search suggestions", "google suggest typing") {
            switch(R.drawable.ic_lightbulb, "Search suggestions", { "${SearchEngine.current.label} suggests searches as you type" }, checked = { prefs.searchSuggestions }) {
                prefs.searchSuggestions = it
            }
        }
        def(Topic.BROWSING, "Translate", "Offer to translate pages", "translate translation language foreign") {
            switch(R.drawable.ic_translate, "Offer to translate pages", { "Offers to translate pages in other languages; it all happens on your phone" }, checked = { Translate.offer }) {
                Translate.offer = it
            }
        }
        def(Topic.BROWSING, "Translate", "Translation languages", "translate languages downloaded delete space") {
            action(R.drawable.ic_translate, "Translation languages", { "Downloaded for translating; tap to delete some" }) {
                Translate.manage(this@SettingsActivity) { refreshAll() }
            }
        }
        def(Topic.BROWSING, "Pages", "Pull down to refresh", "reload swipe") {
            switch(R.drawable.ic_refresh, "Pull down to refresh", { "At the top of a page, drag down to reload it" }, checked = { prefs.pullToRefresh }) {
                prefs.pullToRefresh = it
            }
        }
        def(Topic.BROWSING, "Pages", "Swipe from the edges", "back forward gesture") {
            switch(R.drawable.ic_gesture, "Swipe from the edges", { "Swipe in from the left to go back, from the right to go forward" }, checked = { prefs.edgeSwipe }) {
                prefs.edgeSwipe = it
            }
        }
        def(
            Topic.BROWSING, "Pages", "Avoid \"browser too old\" messages",
            "tell newer user agent useragent firefox version browser out of date outdated old update upgrade unsupported not supported sites esr",
        ) {
            switch(
                R.drawable.ic_globe, "Avoid \"browser too old\" messages",
                { if (prefs.newerFirefoxVersion) "Sites are told Swift is a newer browser, so fewer say it's out of date" else "Off: sites are told Swift's real version" },
                details = "Swift uses the last browser version made for Android 7. Some sites refuse a version that old, " +
                    "or keep saying the browser is out of date. With this on, sites are told it's a newer version instead.\n\n" +
                    "A site may then use something Swift can't do and not work right; turn this off again if one does. " +
                    "It applies to pages you open from now on.",
                checked = { prefs.newerFirefoxVersion },
            ) {
                prefs.newerFirefoxVersion = it
                com.swiftbrowser.engine.UserAgent.applyToOpenTabs()
            }
        }
        // 3.0.4: a strip of the open group's tabs by the address bar (GroupStrip).
        def(Topic.BROWSING, "Tabs", "Group's tabs by the address bar", "group tabs strip bar switch chips") {
            switch(R.drawable.ic_tab, "Group's tabs by the address bar", {
                if (prefs.groupStrip) "In a group, its tabs in a strip by the bar, to switch between them" else "Off: switch through the tab grid"
            }, checked = { prefs.groupStrip }) {
                prefs.groupStrip = it
            }
        }
        def(Topic.BROWSING, "Tabs", "Inactive tabs", "old tabs archive unused") {
            action(R.drawable.ic_history, "Inactive tabs", { inactiveText(prefs.inactiveDays) }) { chooseInactiveDays() }
        }
        def(Topic.BROWSING, "Reader view", "Reader view text", "font serif spacing lines reading sepia page colour color dark light paper") {
            action(R.drawable.ic_font, "Reader view text", { readerText() }) { Sheets.readerStyle(this@SettingsActivity) { refreshAll() } }
        }

        // Ad blocking
        def(Topic.ADBLOCK, "This week", "Blocked this week", "stats count numbers trackers blocked week") {
            custom { AdStatsCard.build(this@SettingsActivity) }
        }
        def(Topic.ADBLOCK, "How much to block", "Blocking level", "ads off balanced high extreme ublock") {
            custom { levelChooser(refresh) }
        }
        def(Topic.ADBLOCK, "More", "Reject cookie pop-ups", "cookies consent gdpr say no banner banners") {
            switch(
                R.drawable.ic_cookie, "Reject cookie pop-ups",
                { "Answers \"Reject\" for you on the sites Swift knows" },
                details = "Swift knows the cookie pop-ups of many big sites and answers them with \"Reject\", so they " +
                    "neither show nor get your consent. On other sites, \"Hide cookie pop-ups\" hides them.",
                checked = { prefs.rejectCookieBanners },
            ) {
                prefs.rejectCookieBanners = it
                Engine.applyPrivacyPrefs()
            }
        }
        def(Topic.ADBLOCK, "More", "Hide cookie pop-ups", "cookies consent gdpr accept notices banner") {
            switch(
                R.drawable.ic_cookie, "Hide cookie pop-ups",
                { if (AdBlock.global == AdLevel.EXTREME) "Always on in Extreme" else "Hides \"Accept cookies\" notices on most sites" },
                enabled = { AdBlock.global != AdLevel.EXTREME && AdBlock.global != AdLevel.OFF },
                checked = { prefs.hideCookieNotices || AdBlock.global == AdLevel.EXTREME },
            ) {
                prefs.hideCookieNotices = it
                AdBlock.applyCookieNotices()
            }
        }
        def(Topic.ADBLOCK, "More", "Sites with their own level", "per site exceptions allow") {
            action(R.drawable.ic_shield, "Sites with their own level", { siteLevelsText() }) { showSiteLevels() }
        }
        def(Topic.ADBLOCK, "More", "Advanced ad blocking", "filters lists rules ublock origin settings dashboard") {
            action(R.drawable.ic_ublock, "Advanced ad blocking", { "The ad blocker's own page: what it blocks, and your own rules" }) {
                AdBlock.extension?.metaData?.optionsPageUrl?.let { openInBrowser(it) }
            }
        }
        def(Topic.ADBLOCK, "More", "Update the ad blocking now", "ublock refresh filter lists block") {
            action(R.drawable.ic_refresh, "Update the ad blocking now", { "It also updates by itself every few days" }) {
                AdBlock.updateFilterLists()
                snack("Updating the ad blocking in the background")
            }
        }

        // Speed & memory
        // 3.0.1: what the phone and Swift are doing now, measured when shown (no polling).
        def(Topic.SPEED, "Right now", "Memory and tabs", "memory ram free used tabs loaded crashes status now speed slow") {
            action(R.drawable.ic_memory, "Memory and tabs", { rightNowSummary() }) { showRightNow() }
        }
        def(Topic.SPEED, "Right now", "Smoothness meter", "fps frames meter drawing speed") {
            switch(R.drawable.ic_speed, "Smoothness meter", { "A small meter on pages showing how smoothly they move" }, checked = { prefs.showDrawSpeed }) {
                prefs.showDrawSpeed = it
                Engine.setDrawSpeedMeter(it)
            }
        }
        def(Topic.SPEED, "How sites look", "Show sites as designed", "blur glass transparency animations new phone quality") {
            switch(
                R.drawable.ic_glass, "Show sites as designed",
                { "Every effect and animation, as each site made it (for newer phones)" },
                details = "To keep scrolling smooth on older phones, Swift changes some sites a little: no frosted glass behind " +
                    "see-through bars and badges, fewer decorative animations, and lighter YouTube and TheFlixer.\n\n" +
                    "Turn this on to see every site exactly as it was designed. It's on by itself on phones with 4 GB of " +
                    "memory or more; on phones with less, it's best left off.\n\nTurning it on turns Lite mode off.",
                checked = { prefs.sitesAsDesigned },
            ) {
                PageActions.setSitesAsDesigned(null, it)
                // Pages keep styles they have already read until the engine starts again.
                confirmRestart(if (it) "Sites show exactly as designed after Swift restarts." else "Sites are lighter again after Swift restarts.")
            }
        }
        def(Topic.SPEED, "How sites look", "Lite mode", "smooth scrolling fast animations shadows blur") {
            switch(
                R.drawable.ic_lite, "Lite mode", { if (Power.saving && !prefs.liteMode) "On for now: saving battery" else "Plainer sites that scroll the smoothest" },
                details = "The smoothest scrolling on your phone: all sites without animations, shadows or frosted glass. " +
                    "Some sites look plainer. Stays on until you turn it off; also in the ≡ menu.",
                checked = { prefs.liteMode },
            ) { PageActions.setLite(null, it) }
        }
        def(Topic.SPEED, "Data", "Data saver", "mobile data images cellular save") {
            switch(
                R.drawable.ic_image, "Data saver",
                { if (DataSaver.active) "On mobile data now: pages load without pictures" else "On mobile data, pages load without pictures" },
                checked = { prefs.dataSaver },
            ) {
                prefs.dataSaver = it
                DataSaver.apply()
            }
        }
        def(Topic.SPEED, "Data", "Data used", "mobile data usage used megabytes per day month traffic") {
            action(R.drawable.ic_data_usage, "Data used", {
                val today = com.swiftbrowser.engine.DataUsage.mobileToday()
                val month = com.swiftbrowser.engine.DataUsage.mobileThisMonth()
                "On mobile data: ${UrlUtils.formatBytes(today)} today · ${UrlUtils.formatBytes(month)} this month"
            }) { showDataUsage() }
        }
        def(Topic.SPEED, "Battery", "Battery saver", "battery low power charge saver percent level") {
            action(R.drawable.ic_battery, "Battery saver", {
                when {
                    !prefs.batterySaver -> "Off"
                    Power.saving -> "Saving battery now, until you charge"
                    else -> "Below ${prefs.batterySaverLevel}% battery: Lite mode, one tab ready to open"
                }
            }) { chooseBatterySaver() }
        }
        def(Topic.SPEED, "Memory", "Tabs ready to open instantly", "memory ram live tabs kept loaded") {
            action(R.drawable.ic_memory, "Tabs ready to open instantly", { liveTabsText(prefs.maxLiveTabs) }) { chooseLiveTabs() }
        }

        // Videos & music: videos on websites, then Swift's player, so the two sets of gestures sit together.
        def(Topic.VIDEO, null, "Sleep timer", "stop music video sleep night minutes") {
            action(R.drawable.ic_timer, "Sleep timer", { SleepTimer.label() ?: "Off. Also in the ≡ menu and the playback notification." }) {
                Sheets.sleepTimer(this@SettingsActivity) { refreshAll() }
            }
        }
        def(Topic.VIDEO, "Videos on websites", "Keep playing in the background", "background play music audio screen off notification switch tabs") {
            switch(
                R.drawable.ic_headphones, "Keep playing in the background",
                { if (prefs.keepPlaying) "Music and videos go on in other tabs, other apps and with the screen off" else "Off: they pause when you leave their tab" },
                details = "On: a song or video keeps playing when you switch tabs, open another app or turn the screen off, " +
                    "with play and pause in the notifications. Sites that pause themselves when you leave them (like YouTube) " +
                    "keep playing too. One tab plays at a time.\n\nOff: it pauses as soon as its tab is out of view.",
                checked = { prefs.keepPlaying },
            ) {
                prefs.keepPlaying = it
                TabManager.applyKeepPlaying(it)
                PlaybackService.sync(this@SettingsActivity)
            }
        }
        def(Topic.VIDEO, "Videos on websites", "Open videos in Swift's player", "swift player for website videos native vlc smooth lag gestures play in swift player website streaming") {
            switch(
                R.drawable.ic_play, "Open videos in Swift's player",
                { if (prefs.sitePlayer) "A button on playing videos opens them in Swift's player: smoother, with gestures and subtitles" else "Off: videos play in the page" },
                checked = { prefs.sitePlayer },
            ) { prefs.sitePlayer = it }
        }
        def(Topic.VIDEO, "Videos on websites", "Continue watching", "resume position film movie episode") {
            switch(R.drawable.ic_resume, "Continue watching", { "Offers to continue long videos where you left off" }, checked = { prefs.continueWatching }) {
                prefs.continueWatching = it
            }
        }
        def(Topic.VIDEO, "Videos on websites", "Play silent videos by themselves", "autoplay previews muted") {
            switch(R.drawable.ic_volume_off, "Play silent videos by themselves", { "Like the previews on YouTube's home page. Off: smoother scrolling." }, checked = { prefs.autoplayMuted }) {
                prefs.autoplayMuted = it
            }
        }
        def(Topic.VIDEO, "Videos on websites", "Double-tap to skip", "seek forward back 10 seconds hold speed 2x") {
            switch(R.drawable.ic_touch, "Double-tap to skip", { "In full screen: double-tap a side to skip 10 seconds, hold for double speed" }, checked = { prefs.videoTaps }) {
                prefs.videoTaps = it
            }
        }
        def(Topic.VIDEO, "Videos on websites", "Brightness and volume swipes", "gestures swipe") {
            switch(R.drawable.ic_gesture, "Brightness and volume swipes", { "In full screen: swipe up or down on the left or right side" }, checked = { prefs.videoGestures }) {
                prefs.videoGestures = it
            }
        }
        def(Topic.VIDEO, "Videos on websites", "Turn full-screen videos sideways", "landscape orientation rotate") {
            switch(R.drawable.ic_rotate, "Turn full-screen videos sideways", { "They turn sideways by themselves" }, checked = { prefs.autoLandscape }) {
                prefs.autoLandscape = it
            }
        }
        // 2.9: Swift's video and music player (PlayerSettings).
        def(Topic.VIDEO, "Swift's player", "Swipes in the player", "player gestures swipe brightness volume seek vlc") {
            switch(R.drawable.ic_gesture, "Swipes in the player", { "Brightness on the left, volume on the right, sideways to move through the video" }, checked = { PlayerSettings.gestures }) {
                PlayerSettings.gestures = it
            }
        }
        def(Topic.VIDEO, "Swift's player", "Double-tap skips", "double tap skip seconds forward back") {
            action(R.drawable.ic_forward, "Double-tap skips", { "${PlayerSettings.doubleTapSeconds} seconds" }) {
                choose("Double-tap skips", PlayerSettings.DOUBLE_TAP_CHOICES.map { "$it seconds" }, PlayerSettings.DOUBLE_TAP_CHOICES.indexOf(PlayerSettings.doubleTapSeconds)) { i ->
                    PlayerSettings.doubleTapSeconds = PlayerSettings.DOUBLE_TAP_CHOICES[i]
                }
            }
        }
        def(Topic.VIDEO, "Swift's player", "Turning the screen", "rotation landscape portrait orientation auto") {
            action(R.drawable.ic_fullscreen, "Turning the screen", { PlayerSettings.rotationLabel(PlayerSettings.rotation) }) {
                choose("Turning the screen", PlayerSettings.ROTATIONS.map { PlayerSettings.rotationLabel(it) }, PlayerSettings.ROTATIONS.indexOf(PlayerSettings.rotation)) { i ->
                    PlayerSettings.rotation = PlayerSettings.ROTATIONS[i]
                }
            }
        }
        def(Topic.VIDEO, "Swift's player", "Keep music playing", "background music notification lock screen") {
            switch(R.drawable.ic_headphones, "Keep music playing", { "When you leave the player or turn the screen off, with controls in the notification" }, checked = { PlayerSettings.backgroundAudio }) {
                PlayerSettings.backgroundAudio = it
            }
        }
        def(Topic.VIDEO, "Swift's player", "Go on where you left off", "resume position continue player video music") {
            switch(R.drawable.ic_history, "Go on where you left off", { "Videos and long recordings start where you stopped" }, checked = { PlayerSettings.resume }) {
                PlayerSettings.resume = it
            }
        }
        def(Topic.VIDEO, "Swift's player", "Playing speed", "player speed default fast slow") {
            action(R.drawable.ic_speed, "Playing speed", { "Videos start at " + PlayerSettings.speedLabel(PlayerSettings.defaultSpeed) }) {
                choose("Playing speed", PlayerSettings.SPEEDS.map { PlayerSettings.speedLabel(it) }, PlayerSettings.SPEEDS.indexOfFirst { it == PlayerSettings.defaultSpeed }) { i ->
                    PlayerSettings.defaultSpeed = PlayerSettings.SPEEDS[i]
                }
            }
        }
        // 3.0.4: also in the player's ⋮ menu; changes what plays at once.
        def(Topic.VIDEO, "Swift's player", "Night sound", "night sound quiet loud compress compressor dynamic range volume voices explosions") {
            switch(R.drawable.ic_night_sound, "Night sound", {
                if (com.swiftbrowser.media.LocalPlayback.nightSoundOn) "Loud parts quieter, quiet voices louder, for watching at low volume" else "Off: the sound as the film has it"
            }, checked = { com.swiftbrowser.media.LocalPlayback.nightSoundOn }) {
                com.swiftbrowser.media.LocalPlayback.setNightSound(it)
            }
        }
        def(Topic.VIDEO, "Swift's player", "Subtitle size", "subtitles captions text size") {
            action(R.drawable.ic_subtitles, "Subtitle size", { "${PlayerSettings.subtitleSize}%" }) {
                choose("Subtitle size", PlayerSettings.SUBTITLE_SIZES.map { "$it%" }, PlayerSettings.SUBTITLE_SIZES.indexOf(PlayerSettings.subtitleSize)) { i ->
                    PlayerSettings.subtitleSize = PlayerSettings.SUBTITLE_SIZES[i]
                }
            }
        }
        // 3.0.1: the subtitles shown first, in the video and in subtitle files beside it.
        def(Topic.VIDEO, "Swift's player", "Subtitle language", "subtitles captions language urdu english arabic hindi") {
            action(R.drawable.ic_subtitles, "Subtitle language", {
                val lang = PlayerSettings.subtitleLanguage
                if (lang.isEmpty()) "Automatic: a subtitle file with the video's name, or English"
                else "${PlayerSettings.SUBTITLE_LANGUAGES.first { it.first == lang }.second} first, in the video and in subtitle files"
            }) {
                choose(
                    "Subtitle language", PlayerSettings.SUBTITLE_LANGUAGES.map { it.second },
                    PlayerSettings.SUBTITLE_LANGUAGES.indexOfFirst { it.first == PlayerSettings.subtitleLanguage },
                ) { i -> PlayerSettings.subtitleLanguage = PlayerSettings.SUBTITLE_LANGUAGES[i].first }
            }
        }
        def(Topic.VIDEO, "Swift's player", "Forget where videos were left", "positions resume forget clear continue") {
            action(R.drawable.ic_delete, "Forget where videos were left", { "Videos and songs start from the beginning again" }) {
                Dialogs.confirm(this@SettingsActivity, "Forget every place?", "Videos and songs will start from the beginning, and leave the home page's Continue row.", "Forget") {
                    PlayerSettings.clearPositions(this@SettingsActivity)
                    snack("Forgotten")
                }
            }
        }

        def(Topic.DOWNLOADS, null, "Ask before downloading", "confirm download") {
            switch(R.drawable.ic_download, "Ask before downloading", { "Sites can't start a download without asking you" }, checked = { prefs.confirmDownloads }) {
                prefs.confirmDownloads = it
            }
        }
        def(Topic.DOWNLOADS, null, "Big downloads wait for Wi-Fi", "mobile data cellular wifi only") {
            switch(
                R.drawable.ic_wifi, "Big downloads wait for Wi-Fi",
                { if (prefs.wifiOnlyDownloads) "Files over 50 MB start when you're on Wi-Fi" else "Downloads start on mobile data too" },
                checked = { prefs.wifiOnlyDownloads },
            ) {
                prefs.wifiOnlyDownloads = it
                Downloads.onNetworkChanged()
            }
        }
        def(Topic.DOWNLOADS, null, "Sort downloads into folders", "sort organise folders type videos music pictures documents apps") {
            switch(
                R.drawable.ic_folder, "Sort downloads into folders",
                { if (prefs.sortDownloads) "Videos, Music, Pictures, Documents and Apps in your download folder" else "Off: everything goes into the download folder" },
                checked = { prefs.sortDownloads },
            ) { prefs.sortDownloads = it }
        }
        // Downloads
        def(Topic.DOWNLOADS, null, "Save videos as MP4", "hls m3u8 ts convert streams") {
            switch(R.drawable.ic_movie, "Save videos as MP4", { "Downloaded videos play on any phone or computer" }, checked = { prefs.hlsToMp4 }) {
                prefs.hlsToMp4 = it
            }
        }
        def(Topic.DOWNLOADS, null, "Download folder", "storage location where save sd card") {
            action(R.drawable.ic_folder, "Download folder", { downloadFolderText() }) {
                if (Downloads.hasStoragePermission(this@SettingsActivity)) chooseDownloadFolder() else askStorageAccess()
            }
        }

        // 2.9.2: torrents (TorrentSettings). They always pause while Swift's VPN is on.
        def(Topic.DOWNLOADS, "Torrents", "Torrents at once", "torrents at once parallel queue magnet") {
            action(R.drawable.ic_torrent, "Torrents at once", { "${TorrentSettings.atOnce} at a time; the others wait their turn" }) {
                val choices = TorrentSettings.AT_ONCE_CHOICES
                choose("Torrents at once", choices.map { if (it == 1) "1 at a time" else "$it at a time" }, choices.indexOf(TorrentSettings.atOnce)) { i ->
                    TorrentSettings.atOnce = choices[i]
                    Torrents.applySettings()
                }
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "Torrent download speed", "torrent download speed limit bandwidth") {
            action(R.drawable.ic_download, "Download speed", { TorrentSettings.speedLabel(TorrentSettings.downLimitKb) }) {
                val choices = TorrentSettings.DOWN_CHOICES
                choose("Torrent download speed", choices.map { TorrentSettings.speedLabel(it) }, choices.indexOf(TorrentSettings.downLimitKb)) { i ->
                    TorrentSettings.downLimitKb = choices[i]
                    Torrents.applySettings()
                }
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "Torrent upload speed", "torrent upload sharing speed limit seeding") {
            action(R.drawable.ic_share, "Upload speed", { TorrentSettings.speedLabel(TorrentSettings.upLimitKb) + " (only while downloading: sharing stops when it's done)" }) {
                val choices = TorrentSettings.UP_CHOICES
                choose("Torrent upload speed", choices.map { TorrentSettings.speedLabel(it) }, choices.indexOf(TorrentSettings.upLimitKb)) { i ->
                    TorrentSettings.upLimitKb = choices[i]
                    Torrents.applySettings()
                }
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "Torrents on Wi-Fi only", "torrent wifi mobile data") {
            switch(R.drawable.ic_wifi, "Wi-Fi only", { if (TorrentSettings.wifiOnly) "On mobile data torrents wait for Wi-Fi" else "Torrents use mobile data too" }, checked = { TorrentSettings.wifiOnly }) {
                TorrentSettings.wifiOnly = it
                Torrents.applySettings()
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "Only while charging", "torrent charging charger battery heat") {
            switch(R.drawable.ic_battery, "Only while charging", { if (TorrentSettings.chargingOnly) "Torrents wait for the charger" else "Off: torrents run on battery too" }, checked = { TorrentSettings.chargingOnly }) {
                TorrentSettings.chargingOnly = it
                Torrents.applySettings()
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "At night only", "torrent night schedule time cheap data") {
            switch(R.drawable.ic_history, "At night only", { if (TorrentSettings.nightOnly) "Torrents run from 1 am to 7 am" else "Off: torrents run at any time" }, checked = { TorrentSettings.nightOnly }) {
                TorrentSettings.nightOnly = it
                Torrents.applySettings()
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "Pause on low battery", "torrent battery low pause") {
            switch(R.drawable.ic_battery, "Pause on low battery", { "Below 15% unless charging; they go on at 20%" }, checked = { TorrentSettings.batteryPause }) {
                TorrentSettings.batteryPause = it
                Torrents.applySettings()
            }
        }
        def(Topic.DOWNLOADS, "Torrents", "Find more sharers", "torrent extra trackers public peers faster start") {
            switch(R.drawable.ic_speed, "Find more sharers", { "Helps torrents with few people sharing start sooner (never used for private torrents)" }, checked = { TorrentSettings.extraTrackers }) {
                TorrentSettings.extraTrackers = it
            }
        }
        // 3.0: torrents' connections encrypted when they can be, or only encrypted ones.
        def(Topic.DOWNLOADS, "Torrents", "Protected connections", "torrent encryption encrypted privacy isp throttle obfuscation") {
            action(R.drawable.ic_lock, "Protected connections", { TorrentSettings.ENCRYPTION_CHOICES[if (TorrentSettings.encryptedOnly) 1 else 0] }) {
                choose("Protected connections", TorrentSettings.ENCRYPTION_CHOICES, if (TorrentSettings.encryptedOnly) 1 else 0) { i ->
                    TorrentSettings.encryptedOnly = i == 1
                    Torrents.applySettings()
                }
            }
        }

        // Privacy & security
        // 3.0.1: the protections at a glance, "Checked" only where Swift has seen them working.
        def(Topic.PRIVACY, null, "Security status", "security status protections check overview safe vpn dns phishing passwords") {
            action(R.drawable.ic_shield, "Security status", { "Ad blocking, private lookups, the VPN and more, at a glance" }) { showSecurityStatus() }
        }
        def(Topic.PRIVACY, null, "Use secure connections", "secure https only encrypted insecure http") {
            switch(
                R.drawable.ic_lock, "Use secure connections",
                { if (prefs.httpsOnly) "Sites open their secure version when they have one" else "Off: sites open the way the link says" },
                details = "Pages are sent protected whenever the site can do it, even from an old link, so nobody on " +
                    "the same Wi-Fi can read or change them. A site that can't still opens, and Swift shows an open " +
                    "lock next to its name.",
                checked = { prefs.httpsOnly },
            ) {
                prefs.httpsOnly = it
                Engine.applyPrivacyPrefs()
            }
        }
        def(Topic.PRIVACY, null, "Private site lookups", "secure dns doh dns over https cloudflare quad9 google lookup private encrypted provider isp") {
            action(R.drawable.ic_globe, "Private site lookups", { secureDnsText() }) { chooseSecureDns() }
        }
        // 3.0: Swift's engine gets no more security fixes (the last Firefox for Android 7).
        def(
            Topic.PRIVACY, "Security", "Safer browsing",
            "safer security secure safe javascript scripts jit compiler webassembly wasm attack attacks exploit hack hacked virus old engine protect",
        ) {
            switch(
                R.drawable.ic_safer, "Safer browsing",
                { if (prefs.saferBrowsing) "Harder for bad sites to attack your phone; some games and video players may not work" else "Off: sites run at full speed" },
                details = "Swift uses the last browser version made for Android 7, which doesn't get security fixes any more. " +
                    "Most attacks on older browsers go through the parts that make websites run fast.\n\n" +
                    "With this on, websites run without those parts: a little slower, and some games or video players may not " +
                    "work. It closes a common way older browsers are attacked. The ad blocker keeps its full speed.\n\n" +
                    "It starts when Swift starts again.",
                checked = { prefs.saferBrowsing },
            ) {
                prefs.saferBrowsing = it
                Engine.applySaferBrowsing()
                confirmRestart(if (it) "Websites run the safer way after Swift restarts." else "Websites run at full speed again after Swift restarts.")
            }
        }
        def(
            Topic.PRIVACY, "Incognito", "Allow screenshots in incognito",
            "screenshot screenshots screen capture shot record recording incognito private recent apps recents picture",
        ) {
            switch(
                R.drawable.ic_incognito, "Allow screenshots in incognito",
                { if (prefs.allowIncognitoScreenshots) "Incognito pages can appear in screenshots and in your recent apps" else "Off: incognito pages stay out of screenshots and your recent apps" },
                checked = { prefs.allowIncognitoScreenshots },
            ) {
                prefs.allowIncognitoScreenshots = it
                // At once, on the browser's screen behind this one.
                (TabManager.ui as? MainActivity)?.applySecureScreen()
            }
        }
        def(Topic.PRIVACY, "Passwords", "Offer to save passwords", "password login sign in autofill fill in remember") {
            switch(
                R.drawable.ic_password, "Offer to save passwords",
                { if (prefs.offerPasswords) "Swift asks after you sign in, and fills them in next time" else "Off: passwords aren't saved or filled in" },
                details = "Passwords stay on your phone only, locked safely by Android. " +
                    "They aren't in backups, and incognito tabs never save one.",
                checked = { prefs.offerPasswords },
            ) {
                prefs.offerPasswords = it
                Engine.runtime.settings.setLoginAutofillEnabled(it)
            }
        }
        def(Topic.PRIVACY, "Passwords", "Saved passwords", "password login sign in see show copy delete") {
            action(R.drawable.ic_key, "Saved passwords", { passwordsText }) {
                PasswordsUi.list(this@SettingsActivity, ::checkScreenLock) { countPasswords() }
            }
        }
        def(Topic.PRIVACY, "Passwords", "Never saved for", "password never sites") {
            action(R.drawable.ic_block, "Never saved for", {
                Passwords.neverSites().size.let { if (it == 0) "No sites" else if (it == 1) "1 site" else "$it sites" }
            }) { PasswordsUi.never(this@SettingsActivity) { refreshAll() } }
        }
        def(Topic.PRIVACY, "Sites", "Site settings", "desktop location sound permissions per site") {
            action(R.drawable.ic_tune, "Site settings", { siteSettingsText() }) { chooseSite() }
        }
        // 3.0: also here, where people look for it (it was only under Storage & data).
        def(Topic.PRIVACY, "Sites", "Clear browsing data", "history cookies cache delete last hour day week time") {
            action(R.drawable.ic_delete, "Clear browsing data", { "History, cookies, the cache and more, for the time you choose" }) { clearData() }
        }

        // VPN
        def(Topic.VPN, null, "VPN", "vpn proton wireguard country ip hide location change country") {
            custom { vpnCard(refresh) }
        }
        def(Topic.VPN, "Locations", "VPN locations", "location server country proton") {
            custom { vpnLocations(refresh) }
        }
        def(Topic.VPN, "Locations", "Test the locations", "test speed fastest working server ping answer") {
            action(R.drawable.ic_speed, "Test the locations", { vpnTestText() }) { testVpnLocations() }
        }
        def(Topic.VPN, "Locations", "Add a location", "add proton wireguard file conf setup") {
            action(R.drawable.ic_add, "Add a location", { "A setup file from Proton VPN, one for each location" }) { addVpnLocation() }
        }
        def(Topic.VPN, "Locations", "How to get free locations", "proton free account download wireguard") {
            action(R.drawable.ic_info, "How to get free locations", { "Free from Proton VPN, in a few minutes" }) { vpnHowTo() }
        }
        // 3.0: if it was on, it always comes back on now; this turns it on even when it was off.
        def(Topic.VPN, "Options", "Turn on the VPN every time Swift starts", "vpn automatic start always every time on") {
            switch(
                R.drawable.ic_vpn, "Turn on the VPN every time Swift starts",
                { "Even if it was off when you left Swift" },
                details = "If the VPN was on when Swift closed (an update, a crash, or Android closing it), Swift always turns " +
                    "it back on by itself, and pages wait until it's connected.\n\nWith this on, Swift also turns the VPN on " +
                    "when it was off.",
                checked = { com.swiftbrowser.vpn.Vpn.connectAtStart },
            ) {
                com.swiftbrowser.vpn.Vpn.connectAtStart = it
            }
        }
        def(Topic.VPN, "Options", "Switch to another location", "automatic failover switch server not answering backup") {
            switch(
                R.drawable.ic_swap, "Switch to another location",
                { "If a location stops working, Swift moves to another by itself" },
                details = "Swift picks the same country first, and the fastest one from your last test. It tells you where " +
                    "it went. Off: the VPN stops and pages wait (if paused) until you pick another location.",
                checked = { com.swiftbrowser.vpn.Vpn.autoSwitch },
            ) { com.swiftbrowser.vpn.Vpn.autoSwitch = it }
        }
        def(Topic.VPN, "Options", "Pause pages if the VPN drops", "kill switch leak block vpn drops") {
            switch(
                R.drawable.ic_lock, "Pause pages if the VPN drops",
                { "Sites never see your real location by accident" },
                details = "While the VPN is connecting, or if it stops (another VPN app started, or Android stopped it), " +
                    "pages wait instead of loading without it. They load by themselves once it is connected " +
                    "again; turning the VPN off lets them load normally.\n\nWhen Swift starts again with the VPN on (after " +
                    "an update or a crash), pages always wait until it's back, even with this off.",
                checked = { com.swiftbrowser.vpn.Vpn.pauseWhenDown },
            ) { com.swiftbrowser.vpn.Vpn.pauseWhenDown = it }
        }

        // Storage
        def(Topic.STORAGE, null, "Storage used", "space size phone memory") {
            custom { storageBreakdown(refresh) }
        }
        def(Topic.STORAGE, "Clear data", "Clear browsing data", "history cookies cache delete last hour day week time") {
            action(R.drawable.ic_delete, "Clear browsing data", { "The last hour, day, week, days you choose or all of it: history, cookies, the cache and more" }) { clearData() }
        }
        def(Topic.STORAGE, "Clear data", "When you quit", "quit exit close clear on exit") {
            action(R.drawable.ic_quit, "When you quit", { ClearData.quitSummary() }) { ClearData.chooseForQuit(this@SettingsActivity) { refreshAll() } }
        }
        def(Topic.STORAGE, "Free up space", "Clear the cache", "cache files space pictures") {
            action(R.drawable.ic_delete, "Clear the cache", { usage?.let { "${UrlUtils.formatBytes(it.cache)}: copies of pages' pictures and files" } }) { clearCache() }
        }
        def(Topic.STORAGE, "Free up space", "Data saved by sites", "site storage sites space clear data sites stored") {
            action(R.drawable.ic_globe, "Data saved by sites", { usage?.let { "${UrlUtils.formatBytes(it.sites)} · see and clear each site" } }) { showSiteStorage() }
        }
        def(Topic.STORAGE, "Free up space", "Clear sites you don't visit", "old unused sites 30 days") {
            action(R.drawable.ic_history, "Clear sites you don't visit", { "Everything of sites not visited for 30 days" }) { clearUnusedSites() }
        }
        def(Topic.STORAGE, "Backup", "Back up", "export save copy backup") {
            action(R.drawable.ic_download, "Back up", { "Settings, bookmarks, history, open tabs and site settings, to one file" }) { backUp() }
        }
        def(Topic.STORAGE, "Backup", "Back up every week", "automatic backup weekly") {
            switch(
                R.drawable.ic_history, "Back up every week",
                { if (prefs.autoBackup) autoBackupText() else "Off: only when you tap Back up" },
                checked = { prefs.autoBackup },
            ) { prefs.autoBackup = it }
        }
        def(Topic.STORAGE, "Backup", "Restore from a backup", "import restore backup new phone") {
            action(R.drawable.ic_restore_backup, "Restore from a backup", { "Adds a backup's contents to your phone, then restarts Swift" }) {
                chooseBackup()
            }
        }

        // About
        def(Topic.ABOUT, null, "Check for updates", "new version github update") {
            action(R.drawable.ic_update, "Check for updates", { "Your tabs and settings stay when you update" }) { checkForUpdates() }
        }
        // 3.0: it sends three days of Swift's log, not only crashes: said so, and shown first.
        def(Topic.ABOUT, null, "Send a problem report", "crash bug report log freeze") {
            action(R.drawable.ic_report, "Send a problem report", { "Crashes, freezes and a record of what Swift did in the last 3 days. You see it before it's sent." }) {
                Diagnostics.shareReport(this@SettingsActivity)
            }
        }
        // 3.0: what Swift is made of, and the licences it comes under.
        def(Topic.ABOUT, null, "Open-source licences", "licence license open source credits gpl mpl apache notice components libraries") {
            action(R.drawable.ic_info, "Open-source licences", { "The projects Swift is built from, and their licences" }) {
                Licences.show(this@SettingsActivity) { openInBrowser(it) }
            }
        }
        return list
    }

    // ------------------------------------------------------------------ building blocks

    /**
     * A section's rows. 3.0.4: as Android 16's settings have them, each row a rounded piece of its own,
     * a little apart (GroupLayout), instead of one card with lines between the rows.
     */
    private fun card(): LinearLayout = GroupLayout(this)

    // 3.0.4: group headings bolder (Colours, Address bar...). 3.0.5: the same as every list's headings
    // (the scale's SECTION), lined up with the icons of the rows under them, with the one section rhythm.
    private fun label(text: String) = Ui.text(this, Ui.Type.SECTION, text).apply {
        setPadding(dp(Ui.ROW_INSET), dp(Ui.SECTION_ABOVE), dp(Ui.ROW_INSET), dp(Ui.SECTION_BELOW))
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(Ui.GROUP_GAP)) }

    /** The logo, the version and what Swift is made of. */
    private fun aboutHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ui.ROW_INSET), dp(16), dp(Ui.ROW_INSET), dp(16))
        // 3.0.4: rounded like a row alone in its group.
        background = Ui.shape(this@SettingsActivity, color(R.color.card_bg), Ui.R_LARGE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(Ui.GROUP_GAP)
        }
        addView(ImageView(this@SettingsActivity).apply { setImageDrawable(Ui.logo(this@SettingsActivity)) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "Swift ${BuildConfig.VERSION_NAME}"
                Ui.size(this, Ui.Type.TITLE, keepFont = true)
                setTypeface(Ui.medium(Ui.Type.TITLE))
                setTextColor(color(R.color.text_primary))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Built on Firefox, with uBlock Origin ${AdBlock.uboVersion}\nProfile: ${Profiles.current().name}"
                Ui.size(this, Ui.Type.CAPTION)
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        })
    }

    /**
     * [probe] (3.0): no rows are made; the titles and summaries the rows would show are written
     * there instead, so search finds settings by what their rows say.
     */
    private inner class Section(val card: LinearLayout, val refresh: MutableList<() -> Unit> = refreshers, val probe: StringBuilder? = null) {
        /** For search: what a row would show. */
        private fun noted(title: String, summary: () -> String?): Boolean {
            val p = probe ?: return false
            p.append(title).append(" · ").append(summary().orEmpty()).append(" · ")
            return true
        }

        fun add(view: View) {
            // 3.0.4: each row is a piece of its own (no lines between them): GroupLayout gives it its
            // corners and the gap above it. A row that can be tapped has its piece already; a run of
            // rows (the VPN's locations) is no piece itself, its rows are.
            if (Piece.of(view) == null && view !is GroupLayout.Run) Piece.attach(view, tappable = false)
            card.addView(view)
        }

        /** A row that opens something; its summary shows the current value. */
        /** [icon]: a row whose icon follows a setting (the search engine's logo). */
        fun action(iconRes: Int, title: String, summary: () -> String?, badge: Boolean = false, icon: (() -> Int)? = null, onClick: () -> Unit) {
            if (noted(title, summary)) return
            val (row, sub) = row(iconRes, title, summary(), badge)
            if (icon != null) {
                val image = row.getChildAt(0) as ImageView
                refresh += { setRowIcon(image, icon(), badge) }
            }
            row.isClickable = true
            Piece.attach(row, tappable = true)
            row.setOnClickListener { onClick() }
            row.addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            refresh += { setSummary(sub, summary()) }
            add(row)
        }

        fun info(icon: Int, title: String, summary: () -> String?) {
            if (noted(title, summary)) return
            val (row, sub) = row(icon, title, summary())
            refresh += { setSummary(sub, summary()) }
            add(row)
        }

        fun switch(
            icon: Int,
            title: String,
            summary: () -> String?,
            details: String? = null,
            enabled: () -> Boolean = { true },
            checked: () -> Boolean,
            onChange: (Boolean) -> Unit,
        ) {
            if (noted(title, summary)) return
            val (row, sub) = row(icon, title, summary())
            if (details != null) {
                val info = ImageButton(this@SettingsActivity).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
                    setBackgroundResource(borderless())
                    contentDescription = "About $title"
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle(title)
                            .setMessage(details)
                            .setPositiveButton(android.R.string.ok, null)
                            .showImmersive()
                    }
                }
                row.addView(info, LinearLayout.LayoutParams(dp(36), dp(36)))
                // A near miss opens the explanation rather than flipping the switch beside it.
                Ui.touchArea(info, row)
            }
            val sw = SlimSwitch(this@SettingsActivity)
            sw.setChecked(checked(), animate = false)
            row.addView(sw, LinearLayout.LayoutParams(dp(46), dp(32)))
            row.isClickable = true
            Piece.attach(row, tappable = true)
            row.accessibilityDelegate = SwitchRowAccessibility(sw)
            row.setOnClickListener {
                if (!enabled()) return@setOnClickListener
                val on = !sw.isChecked
                sw.setChecked(on, animate = true)
                Haptics.tick(sw)
                onChange(on)
                refreshAll()
            }
            val update = {
                sw.setChecked(checked(), animate = false)
                setSummary(sub, summary())
                val e = enabled()
                // Not pressable while unavailable; its (i) button, a view of its own, still is.
                row.isEnabled = e
                // 3.0.3: a row that becomes available (or not) fades rather than jumps. 3.0.4: what is
                // in it, not its piece, which stays as solid as its neighbours.
                val a = if (e) 1f else Ui.DISABLED_ALPHA
                for (i in 0 until row.childCount) {
                    val c = row.getChildAt(i)
                    if (row.isAttachedToWindow && c.alpha != a) Motion.anim(c, Motion.SHORT).alpha(a).start() else c.alpha = a
                }
            }
            update()
            refresh += update
            add(row)
        }

        fun custom(view: View) {
            if (probe == null) add(view)
        }

        /** 3.0: a row of its own made only when shown (not while search reads what rows say). */
        fun custom(make: () -> View) {
            if (probe == null) add(make())
        }
    }

    private fun setSummary(sub: TextView, text: String?) {
        // A line that goes or comes changes at once (hidden, swapText doesn't fade); one that stays cross-fades.
        if (text.isNullOrEmpty()) sub.visibility = View.GONE
        Motion.swapText(sub, text.orEmpty())
        if (!text.isNullOrEmpty()) sub.visibility = View.VISIBLE
        // 3.0.5: the one row rhythm: a row with a summary is at least a two-line row, one without a one-line row.
        val row = sub.parent?.parent as? View ?: return
        val min = dp(RowRhythm.minHeight(!text.isNullOrEmpty()))
        if (row.minimumHeight != min) row.minimumHeight = min
    }

    /**
     * An icon in a soft holder (3.0.5: the one rounded square of every list; in the accent's soft
     * colour for the main page's topics), a title and a summary of up to three lines.
     */
    private fun row(icon: Int, title: String, summary: String?, badge: Boolean = false): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 3.0.5: 8 dp above and below (10 before), so a one-line row can be Ui.ROW_ONE_LINE tall with its 40 dp holder.
            setPadding(dp(Ui.ROW_INSET), dp(8), dp(12), dp(8))
        }
        row.addView(ImageView(this).apply {
            background = Ui.circle(if (badge) Ui.accentContainer(this@SettingsActivity) else color(R.color.surface_alt))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setRowIcon(this, icon, badge)
        }, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            // The regular weight, as the lists' row titles, in Swift's own font on every Android.
            Ui.size(this, Ui.Type.SUBTITLE, keepFont = true)
            typeface = Ui.typeface(Ui.Type.BODY)
            setTextColor(color(R.color.text_primary))
        })
        val sub = TextView(this).apply {
            Ui.size(this, Ui.Type.CAPTION)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setSummary(sub, summary)
        return row to sub
    }

    /** A row's icon in the text colour, or a search engine's logo in its own colours. */
    private fun setRowIcon(image: ImageView, icon: Int, badge: Boolean) {
        image.setImageResource(icon)
        image.imageTintList = if (SearchEngine.isLogo(icon)) {
            null
        } else {
            ColorStateList.valueOf(if (badge) Ui.onAccentContainer(this) else color(R.color.text_primary))
        }
    }

    /** The blocking levels side by side; the chosen one is explained below. */
    private fun levelChooser(target: MutableList<() -> Unit>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.ROW_INSET), dp(14), dp(Ui.ROW_INSET), dp(12))
        }
        val summary = TextView(this).apply {
            Ui.size(this, Ui.Type.LABEL, keepFont = true)
            typeface = Ui.typeface(Ui.Type.BODY)
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        val levels = AdLevel.entries
        val segments = Segmented(this, levels.map { it.label }, levels.indexOf(AdBlock.global)) { i ->
            AdBlock.setGlobal(levels[i])
            refreshAll()
        }
        val update = {
            val current = AdBlock.global
            segments.select(levels.indexOf(current), animate = true)
            Motion.swapText(summary, current.summary)
        }
        update()
        target += update
        box.addView(segments, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        box.addView(summary)
        return box
    }

    // ------------------------------------------------------------------ choices and actions

    private fun textSizeText(scale: Float) = "${(scale * 100).roundToInt()}%" + if (scale == 1f) " (normal)" else ""

    /** When the night light is on and how warm (2.3); the screen shows it at once. */
    private fun nightLightSheet() {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Night light", "A warm layer over Swift, pages and videos too, with less blue light."))
        col.addView(Sheets.label(this, "When"))
        val modes = listOf(NightLight.OFF, NightLight.AT_NIGHT, NightLight.ALWAYS)
        col.addView(Sheets.chips(this, listOf("Off", "7 pm to 7 am", "Always"), modes.indexOf(prefs.nightLight).coerceAtLeast(0)) { i ->
            prefs.nightLight = modes[i]
            NightLight.attach(this)
            NightLight.refreshAll()
            Looks.watchClock(true)
            refreshAll()
        })
        col.addView(Sheets.label(this, "How warm", top = 16))
        col.addView(Sheets.chips(this, listOf("Low", "Medium", "High"), prefs.nightLightStrength.coerceIn(1, 3) - 1) { i ->
            prefs.nightLightStrength = i + 1
            NightLight.refreshAll()
            refreshAll()
        })
        col.addView(Sheets.note(this, "Dialogs and Android's own screens stay as they are."))
        Sheets.sheet(this, col).showImmersive()
    }

    /** Off, or the level it starts at (2.3). */
    private fun chooseBatterySaver() {
        val levels = intArrayOf(15, 20, 30)
        val labels = arrayOf("Off") + levels.map { "Below $it% battery" }
        val checked = if (!prefs.batterySaver) 0 else levels.indexOf(prefs.batterySaverLevel).coerceAtLeast(1) + 1
        choose("Battery saver", labels.toList(), checked, then = { which ->
            if (which > 0) snack("Lite mode, one tab ready to open and no pictures on mobile data below ${levels[which - 1]}%, or with your phone's battery saver")
        }) { which ->
            prefs.batterySaver = which > 0
            if (which > 0) prefs.batterySaverLevel = levels[which - 1]
            Power.update()
        }
    }

    private fun chooseTextSize() {
        val options = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
        val labels = options.map { textSizeText(it) }
        val checked = options.indexOfFirst { kotlin.math.abs(it - prefs.textScale) < 0.01f }
        choose("Text size", labels, checked, then = { which ->
            snack("Sites without a size of their own use ${textSizeText(options[which])}")
        }) { which ->
            prefs.textScale = options[which]
            Engine.setTextScale(options[which])
        }
    }

    private fun chooseSearchEngine() {
        val engines = SearchEngine.entries
        val labels = engines.map {
            if (it != SearchEngine.CUSTOM) it.label else if (prefs.customSearchUrl.isBlank()) "Your own…" else "${it.label} (your own)"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(labels.toTypedArray(), engines.indexOf(SearchEngine.current)) { d, which ->
                d.dismiss()
                if (engines[which] == SearchEngine.CUSTOM) {
                    askCustomEngine()
                } else {
                    prefs.searchEngine = engines[which].key
                    refreshAll()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private val searchAddressHelp = "Search the site for any word, copy the address of its results page, and put %s where " +
        "the word was. For example: https://search.example.com/?q=%s"

    /** Your own search engine (2.6). */
    private fun askCustomEngine() {
        Sheets.askText(this, "Your search engine", "Address with %s for the words", prefs.customSearchUrl.ifBlank { "https://" }) { text ->
            val url = text.trim()
            if (!UrlUtils.isHttp(url) || "%s" !in url) {
                Dialogs.info(this, "Your search engine", searchAddressHelp)
                return@askText
            }
            prefs.customSearchUrl = url
            prefs.customSearchName = UrlUtils.siteName(url)
            prefs.searchEngine = SearchEngine.CUSTOM.key
            refreshAll()
        }
    }

    /**
     * Without storage access, downloads stay in Swift's own folder: they count as Swift's data and
     * are deleted with Swift. Says so, with a way to allow access.
     */
    private fun downloadFolderText(): String =
        if (Downloads.hasStoragePermission(this)) Downloads.describe(Downloads.directory())
        else "Inside Swift (deleted with Swift). Tap to let Swift save to your Download folder."

    private fun askStorageAccess() {
        Dialogs.confirm(
            this, "Save downloads to your phone's storage?",
            "Without storage access, downloads stay inside Swift: they take up Swift's space and are deleted if Swift is uninstalled. Allow access to save them in your Download folder instead.",
            "Allow",
        ) {
            if (androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                !prefs.sp.getBoolean("storage_asked", false)
            ) {
                prefs.sp.edit().putBoolean("storage_asked", true).apply()
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            } else {
                // Android won't ask again ("Don't ask again" was chosen): its settings page for Swift,
                // with what to do there said first (2.6: a message hid behind that page).
                Dialogs.confirm(this, "Allow storage in Android's settings", "Android's page for Swift opens: tap Permissions, then turn on Storage.", "Open") {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        refreshAll()
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            snack("New downloads go to ${Downloads.describe(Downloads.directory())}")
        }
    }

    private fun chooseDownloadFolder() {
        val phone = android.os.Environment.getExternalStorageDirectory()
        val options = ArrayList<Pair<String, String>>()
        options += "Phone storage › Download/Swift" to ""
        options += "Phone storage › Download" to android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
        options += "Phone storage › Movies" to android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES).path
        Downloads.sdCardDirectory()?.let { options += "SD card › Swift's folder (deleted with Swift)" to it.path }
        val current = prefs.downloadFolder
        val labels = options.map { it.first } + "Another folder…"
        val checked = options.indexOfFirst { it.second == current }.let { if (it < 0) options.size else it }
        MaterialAlertDialogBuilder(this)
            .setTitle("Download folder")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                if (which < options.size) {
                    setDownloadFolder(options[which].second)
                } else {
                    Sheets.askText(this, "Folder on the phone's storage", "For example Videos/Films", current.removePrefix(phone.path).trimStart('/')) { path ->
                        setDownloadFolder(File(phone, path.trim().trim('/')).path)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun setDownloadFolder(path: String) {
        prefs.downloadFolder = path
        val dir = Downloads.directory()
        refreshAll()
        if (path.isNotBlank() && dir.path != path) snack("Swift can't write there; downloads go to ${Downloads.describe(dir)}")
        else snack("New downloads go to ${Downloads.describe(dir)}")
    }

    /** The swipes as they work where the address bar is (mirrored when it is at the bottom). */
    private fun barSwipeLabels(): List<String> = if (prefs.toolbarAtBottom) {
        listOf("Swipe down on it to hide it", "Swipe up from the bottom edge to show it", "Both", "Off")
    } else {
        listOf("Swipe up on it to hide it", "Swipe down from the top edge to show it", "Both", "Off")
    }

    private fun barSwipesText(mode: Int): String = when (mode) {
        Prefs.SWIPE_HIDE -> barSwipeLabels()[0]
        Prefs.SWIPE_SHOW -> barSwipeLabels()[1]
        0 -> "Off"
        else -> if (prefs.toolbarAtBottom) "Swipe down to hide it, up from the bottom edge to show it"
        else "Swipe up to hide it, down from the top edge to show it"
    }

    private fun chooseBarSwipes() {
        val modes = intArrayOf(Prefs.SWIPE_HIDE, Prefs.SWIPE_SHOW, Prefs.SWIPE_HIDE or Prefs.SWIPE_SHOW, 0)
        choose("Address bar swipes", barSwipeLabels(), modes.indexOf(prefs.barSwipes).coerceAtLeast(0)) { which ->
            prefs.barSwipes = modes[which]
        }
    }

    private fun themeText() = when (prefs.themeMode) {
        Looks.NIGHT -> "Dark at night: dark from 7 pm to 7 am (now ${if (prefs.darkMode) "dark" else "light"})"
        Looks.DARK -> "Dark. Sites that can, turn dark too."
        else -> "Light"
    }

    private fun chooseTheme() {
        val modes = intArrayOf(Looks.LIGHT, Looks.DARK, Looks.NIGHT)
        val labels = arrayOf("Light", "Dark", "Dark at night")
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(labels, modes.indexOf(prefs.themeMode).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                // The screen restarts in the other colours if they change.
                Looks.setMode(modes[which])
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** The browser with its menu open, ready to arrange (2.2). */
    private fun arrangeMenu() {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ARRANGE_MENU, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun resetMenu() {
        val before = MenuButtons.layout()
        MenuButtons.save(MenuButtons.defaultLayout())
        Messages.show(this, "The menu is back as it started", "Undo") { MenuButtons.save(before) }
    }

    /** "Profile, Home · address · Tabs, Menu". */
    private fun toolbarButtonsText(): String {
        val l = ToolbarButtons.layout()
        fun names(keys: List<String>) = keys.joinToString(", ") { ToolbarButtons.button(it).label }
        return listOf(names(l.left), "address", names(l.right)).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    /** The address bar's buttons, arranged by dragging (2.2), in a sheet (a dialog leaves too little room). */
    private fun arrangeToolbar() {
        val editor = ToolbarEditor(this)
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Address bar buttons"))
        col.addView(editor)
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "Reset"
                setOnClickListener {
                    Dialogs.confirm(this@SettingsActivity, "Reset the address bar buttons?", "They go back to Swift's own choice and order.", "Reset") {
                        editor.reset()
                    }
                }
            })
            addView(com.google.android.material.button.MaterialButton(this@SettingsActivity).apply {
                text = "Done"
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        })
        dialog = Sheets.sheet(this, col)
        dialog.setOnDismissListener { refreshAll() }
        dialog.showImmersive()
    }

    private fun newsCountryText(): String =
        com.swiftbrowser.news.News.countryName() + if (prefs.newsCountry.isEmpty()) " (from your phone)" else ""

    private fun chooseNewsCountry() {
        val codes = com.swiftbrowser.news.News.countries.keys.toList()
        val labels = listOf("From your phone (${com.swiftbrowser.news.News.countries[com.swiftbrowser.news.News.country()]})") +
            codes.map { com.swiftbrowser.news.News.countries[it]!! }
        val checked = if (prefs.newsCountry.isEmpty()) 0 else codes.indexOf(prefs.newsCountry) + 1
        choose("News from", labels, checked) { which ->
            prefs.newsCountry = if (which == 0) "" else codes[which - 1]
            com.swiftbrowser.news.News.clearMemory()
        }
    }

    private fun newsTopicsText(): String {
        val topics = com.swiftbrowser.news.News.topics.filter { it.key in prefs.newsTopics || it.key == "top" }
        return topics.joinToString(", ") { if (it.key == "nation") com.swiftbrowser.news.News.countryName() else it.label }
    }

    private fun chooseNewsTopics() {
        val topics = com.swiftbrowser.news.News.topics.filter { it.key != "top" }
        val chosen = BooleanArray(topics.size) { topics[it].key in prefs.newsTopics }
        MaterialAlertDialogBuilder(this)
            .setTitle("Topics under Top stories")
            .setMultiChoiceItems(topics.map { if (it.key == "nation") "${com.swiftbrowser.news.News.countryName()} (your country)" else it.label }.toTypedArray(), chosen) { _, which, on ->
                chosen[which] = on
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.newsTopics = setOf("top") + topics.filterIndexed { i, _ -> chosen[i] }.map { it.key }
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun newsSitesText(): String {
        val n = prefs.newsFeeds.size
        return if (n == 0) "Add news sites you like; their stories get their own topic" else "$n ${if (n == 1) "site" else "sites"} · under \"Your sites\""
    }

    /** The user's news sites, each with a remove button, and a row to add another. */
    private fun editNewsSites() {
        val col = Sheets.column(this)
        col.addView(Sheets.header(this, "Your news sites", "Their stories have their own topic, \"Your sites\", on the home page."))
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        val feeds = prefs.newsFeeds
        if (feeds.isEmpty()) col.addView(Sheets.note(this, "No sites yet. Add the news sites you read most."))
        for (feed in feeds) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(24), 0, dp(12), 0)
                val badge = ImageView(this@SettingsActivity).apply {
                    setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_news, color(R.color.text_secondary)))
                    background = Ui.circle(color(R.color.surface_alt))
                    scaleType = ImageView.ScaleType.CENTER
                }
                addView(badge, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(16) })
                com.swiftbrowser.core.Favicons.load(feed) { bmp ->
                    if (bmp != null) {
                        badge.setImageBitmap(bmp)
                        // 3.0.5: a little dimmer in the dark look.
                        SoftPictures.apply(badge)
                        badge.scaleType = ImageView.ScaleType.FIT_CENTER
                        val p = dp(10)
                        badge.setPadding(p, p, p, p)
                    }
                }
                addView(Ui.text(this@SettingsActivity, Ui.Type.BODY, UrlUtils.siteName(feed), lines = 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageButton(this@SettingsActivity).apply {
                    setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_close, color(R.color.text_secondary)))
                    background = Ui.rippleBorderless(this@SettingsActivity)
                    contentDescription = "Remove ${UrlUtils.siteName(feed)}"
                    setOnClickListener {
                        dialog.dismiss()
                        prefs.newsFeeds = prefs.newsFeeds - feed
                        com.swiftbrowser.news.News.clearMemory()
                        refreshAll()
                        Messages.show(this@SettingsActivity, "Removed ${UrlUtils.siteName(feed)}", "Undo") {
                            prefs.newsFeeds = prefs.newsFeeds + feed
                            com.swiftbrowser.news.News.clearMemory()
                            refreshAll()
                        }
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
        }
        col.addView(Sheets.row(this, R.drawable.ic_add, "Add a site") {
            dialog.dismiss()
            addNewsSite()
        }.apply {
            setTextColor(Ui.accent(this@SettingsActivity))
            setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(this@SettingsActivity, R.drawable.ic_add, Ui.accent(this@SettingsActivity)), null, null, null)
        })
        dialog = Sheets.sheet(this, col)
        dialog.showImmersive()
    }

    /** Adds a news site by its address; its feed is found by itself (News.findFeed). */
    private fun addNewsSite() {
        Sheets.askText(this, "Add a news site", "For example dawn.com or bbc.com/news", "") { text ->
            val input = text.trim()
            if (input.isEmpty()) return@askText
            val name = UrlUtils.siteName(if (UrlUtils.isHttp(input)) input else "https://$input").ifEmpty { input }
            // Stays on screen while Swift looks (it can take a few seconds).
            val searching = Messages.sticky(this, "Looking for $name's news…")
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { com.swiftbrowser.news.News.findFeed(input) }
                searching?.dismiss()
                when {
                    found == null -> Messages.show(this@SettingsActivity, "Couldn't find news on $name. Check the address, or type the address of its news feed.", long = true)
                    found in prefs.newsFeeds -> snack("$name is already there")
                    else -> {
                        prefs.newsFeeds = prefs.newsFeeds + found
                        com.swiftbrowser.news.News.clearMemory()
                        refreshAll()
                        snack("Added $name. Its stories are under \"Your sites\".")
                    }
                }
            }
        }
    }

    private fun newsHiddenText(): String {
        val n = prefs.newsHidden.size
        return if (n == 0) "None. Hold a story to hide its site." else "$n ${if (n == 1) "site" else "sites"} hidden"
    }

    private fun editHiddenNews() {
        val hidden = prefs.newsHidden.sorted()
        if (hidden.isEmpty()) {
            snack("No hidden sites. Hold a story on the home page to hide its site.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tap a site to show it again")
            .setItems(hidden.toTypedArray()) { _, which ->
                prefs.newsHidden = prefs.newsHidden - hidden[which]
                com.swiftbrowser.news.News.clearMemory()
                refreshAll()
                snack("${hidden[which]} shows again")
            }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    // ------------------------------------------------------------------ VPN

    /** The VPN's state (and what sites see), with Turn on / Turn off. */
    private fun vpnCard(target: MutableList<() -> Unit>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.ROW_INSET), dp(16), dp(Ui.ROW_INSET), dp(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = VpnUi.StateMark(this)
        head.addView(mark.view, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(14) })
        val title = Ui.text(this, Ui.Type.TITLE, "")
        val status = Ui.text(this, Ui.Type.BODY_SECONDARY, "").apply { setPadding(0, dp(2), 0, 0) }
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)
        val button = com.google.android.material.button.MaterialButton(this)
        box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        // After the VPN dropped: Reconnect above, and Turn off here (3.0: after a start that didn't
        // bring it back, Try again and Continue without VPN).
        val off = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Turn off"
            // Hidden until it's needed: shown at first, it flashed and everything under it jumped as the page opened.
            visibility = View.GONE
            setOnClickListener { VpnUi.turnOff(this@SettingsActivity) }
        }
        box.addView(off, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
        val update = {
            val vpn = com.swiftbrowser.vpn.Vpn
            mark.update()
            // The words change with a quick cross-fade rather than a jump (2.3).
            Motion.swapText(title, if (vpn.testing) "Testing…" else when (vpn.state) {
                com.swiftbrowser.vpn.Vpn.State.ON -> "Connected"
                com.swiftbrowser.vpn.Vpn.State.CONNECTING -> "Connecting…"
                com.swiftbrowser.vpn.Vpn.State.FAILED -> if (vpn.wanted) "Not connected" else "Off"
                com.swiftbrowser.vpn.Vpn.State.OFF -> "Off"
            })
            Motion.swapText(status, VpnUi.status())
            val empty = vpn.locations().isEmpty()
            val dropped = VpnUi.dropped()
            // On the page's first filling (not on screen yet) it is simply there.
            if (dropped && off.visibility != View.VISIBLE) {
                if (box.isShown) Motion.fadeIn(off, fromDy = 6) else off.visibility = View.VISIBLE
            }
            if (!dropped && off.visibility == View.VISIBLE) Motion.fadeOut(off, toDy = 0)
            if (dropped) Motion.swapText(off, VpnUi.turnOffLabel())
            Motion.swapText(button, when {
                empty -> "How to get free locations"
                vpn.testing -> "Stop testing"
                dropped -> VpnUi.reconnectLabel()
                vpn.wanted -> "Turn off"
                else -> "Turn on"
            })
            button.setOnClickListener {
                when {
                    empty -> vpnHowTo()
                    // Stopping a test: back to how it was (on, or off).
                    vpn.testing -> if (vpn.wanted) VpnUi.turnOn(this, vpnPermission) else vpn.turnOff()
                    dropped -> VpnUi.turnOn(this, vpnPermission)
                    vpn.wanted -> VpnUi.turnOff(this)
                    else -> VpnUi.turnOn(this, vpnPermission)
                }
            }
        }
        update()
        target += update
        return box
    }

    /**
     * The locations: tap one to use it (it connects at once when the VPN is on). 3.0: ⋮ opens what
     * else can be done with it (I extended it, Proton's page, Remove), which was only behind a hold;
     * one that needs extending says "I extended it" on its row.
     */
    private fun vpnLocations(target: MutableList<() -> Unit>): View {
        // 3.0.4: each location a piece of the section's group, like the rows after it.
        val box = GroupLayout.Run(this)
        val rows = ArrayList<VpnRow>()
        val update = {
            val vpn = com.swiftbrowser.vpn.Vpn
            // By country (2.2: there can be several servers in one).
            val list = vpn.locations().sortedWith(compareBy({ it.countryName }, { it.server }))
            // With none yet, the card above says so.
            val chosen = vpn.current()?.id
            // Made again only when the locations change: a refresh doesn't cut off a tapped row's ripple.
            if (rows.map { it.loc.id } != list.map { it.id }) {
                box.removeAllViews()
                rows.clear()
                for (loc in list) rows += VpnRow(loc, chosen).also { box.addView(it.view) }
            } else {
                list.forEachIndexed { i, loc -> rows[i].bind(loc, chosen) }
            }
        }
        update()
        target += update
        return box
    }

    /** A location's row, kept while the location is there and filled in again as things change. */
    private inner class VpnRow(loc: com.swiftbrowser.vpn.Vpn.Location, chosen: String?) {
        var loc = loc
            private set
        private val country = Ui.text(this@SettingsActivity, Ui.Type.BODY, loc.countryName)
        private val server = Ui.text(this@SettingsActivity, Ui.Type.CAPTION, "")
        private val state = Ui.text(this@SettingsActivity, Ui.Type.CAPTION, "")
        private val plainState = state.currentTextColor

        // 3.0: a visible "I extended it" when it needs extending.
        private val extended = Ui.text(this@SettingsActivity, Ui.Type.LABEL, "I extended it", Ui.accent(this@SettingsActivity)).apply {
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
            background = Ui.ripple(this@SettingsActivity, null, Ui.R_XSMALL)
            setOnClickListener {
                VpnUi.markExtended(this@SettingsActivity, this@VpnRow.loc)
                refreshAll()
            }
        }
        private val check = ImageView(this@SettingsActivity).apply {
            setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_check, Ui.accent(this@SettingsActivity)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (loc.id == chosen) View.VISIBLE else View.GONE
        }
        private val more = ImageButton(this@SettingsActivity).apply {
            setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_more, color(R.color.text_secondary)))
            background = Ui.rippleBorderless(this@SettingsActivity)
            setOnClickListener { vpnLocationSheet(this@VpnRow.loc) }
        }
        val view = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.ROW_TWO_LINES)
            setPadding(dp(Ui.ROW_INSET), dp(8), dp(4), dp(8))
            Piece.attach(this, tappable = true)
            addView(TextView(this@SettingsActivity).apply {
                text = loc.flag
                Ui.size(this, Ui.Type.HEADLINE, keepFont = true)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(Ui.ICON_BOX), dp(Ui.ICON_BOX)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(country)
                addView(server)
                addView(state)
                addView(extended)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(check, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(4) })
            addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
            setOnClickListener {
                val vpn = com.swiftbrowser.vpn.Vpn
                vpn.choose(this@VpnRow.loc)
                if (vpn.wanted) VpnUi.turnOn(this@SettingsActivity, vpnPermission, this@VpnRow.loc)
                refreshAll()
            }
            setOnLongClickListener {
                vpnLocationSheet(this@VpnRow.loc)
                true
            }
        }

        init {
            bind(loc, chosen)
        }

        fun bind(fresh: com.swiftbrowser.vpn.Vpn.Location, chosen: String?) {
            loc = fresh
            // 2.2: how it did in the last test, and when it needs extending at Proton.
            val result = VpnUi.testResult(fresh)
            val soon = fresh.renewBy - System.currentTimeMillis() < 14L * 24 * 3600 * 1000
            // 3.0: its file can't be opened any more (Android's secure storage was reset).
            val unreadable = com.swiftbrowser.vpn.Vpn.isUnreadable(fresh)
            val line = if (unreadable) "Can't be opened any more: remove it and add it again" else listOfNotNull(result, VpnUi.renewText(fresh)).joinToString(" · ")
            val inUse = fresh.id == chosen
            // 3.0: TalkBack also says the server, the test and when it stops working.
            view.contentDescription = listOf(fresh.countryName, fresh.server + if (inUse) ", in use" else "", line).joinToString(", ")
            more.contentDescription = "More for ${fresh.countryName}"
            extended.contentDescription = "I extended ${fresh.countryName} at Proton"
            Motion.swapText(country, fresh.countryName)
            Motion.swapText(server, fresh.server + if (inUse) " · in use" else "")
            Motion.swapText(state, line)
            state.setTextColor(if (unreadable || soon || result == "No answer") Ui.danger(this@SettingsActivity) else plainState)
            extended.visibility = if (soon && !unreadable) View.VISIBLE else View.GONE
            Motion.pop(check, inUse)
        }
    }

    /** ⋮ or held: connect, mark it extended at Proton (another year), or remove it. */
    private fun vpnLocationSheet(loc: com.swiftbrowser.vpn.Vpn.Location) {
        val vpn = com.swiftbrowser.vpn.Vpn
        Sheets.actions(
            this, "${loc.flag} ${loc.countryName}", "${loc.server} · ${VpnUi.renewText(loc)}",
            listOf(
                Sheets.Action(R.drawable.ic_vpn, "Connect", primary = true) {
                    vpn.choose(loc)
                    VpnUi.turnOn(this, vpnPermission, loc)
                    refreshAll()
                },
                Sheets.Action(R.drawable.ic_extended, "I extended it", primary = true) {
                    VpnUi.markExtended(this, loc)
                    refreshAll()
                },
                Sheets.Action(R.drawable.ic_open_new, "Open Proton's page") { openInBrowser(com.swiftbrowser.vpn.Vpn.PROTON_DOWNLOADS) },
                Sheets.Action(R.drawable.ic_close, "Remove", danger = true) { removeVpnLocation(loc) },
            ),
            iconRes = R.drawable.ic_vpn,
        )
    }

    private fun vpnTestText(): String {
        val vpn = com.swiftbrowser.vpn.Vpn
        return when {
            vpn.testing -> vpn.testStep?.let { "Testing ${it.first} of ${it.second}…" } ?: "Testing…"
            vpn.testResults.isNotEmpty() -> "Done: the results are under each location"
            else -> "Which ones answer, and how fast"
        }
    }

    private fun testVpnLocations() {
        val vpn = com.swiftbrowser.vpn.Vpn
        if (vpn.testing) return
        if (vpn.locations().isEmpty()) {
            snack("Add a location first")
            return
        }
        val pause = vpn.wanted && vpn.pauseWhenDown
        Dialogs.confirm(
            this, "Test the locations?",
            "Swift connects to each location in turn to see which ones answer and how fast (up to 15 seconds each). " +
                "The VPN is off meanwhile" + (if (pause) ", so pages wait" else "") +
                (if (vpn.wanted) ", and it reconnects when it's done." else "."),
            "Test",
        ) { VpnUi.test(this, vpnPermission) }
    }

    /**
     * 3.0: the location in use, removed while the VPN is on, makes way for another (pages wait
     * meanwhile) instead of the VPN going off; Undo brings it back and connects to it again.
     */
    private fun removeVpnLocation(loc: com.swiftbrowser.vpn.Vpn.Location) {
        val vpn = com.swiftbrowser.vpn.Vpn
        val sealed = vpn.sealedFile(this, loc)
        val inUse = vpn.current()?.id == loc.id
        val wasOn = inUse && vpn.wanted
        val next = vpn.remove(this, loc)
        refreshAll()
        val text = when {
            next != null -> "Removed ${loc.countryName}: moving to ${next.flag} ${next.countryName}…"
            wasOn -> "Removed ${loc.countryName}: the VPN is off (no other location)"
            else -> "Removed ${loc.countryName}"
        }
        Messages.show(this, text, if (sealed != null) "Undo" else null, long = wasOn) {
            if (sealed != null) {
                vpn.restore(this, loc, sealed, inUse)
                if (wasOn) VpnUi.turnOn(this, vpnPermission, loc)
            }
            refreshAll()
        }
    }

    private fun addVpnLocation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Add a location")
            .setItems(arrayOf("Open a .conf file", "Paste the file's text")) { _, which ->
                if (which == 0) {
                    try {
                        vpnFilePicker.launch(arrayOf("*/*"))
                    } catch (_: Exception) {
                        snack("No app on your phone can pick files")
                    }
                } else {
                    pasteVpnText()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun addVpnFile(uri: android.net.Uri) {
        lifecycleScope.launch {
            val (text, name) = withContext(Dispatchers.IO) {
                try {
                    val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    // 3.0: only up to 64 KB is read (a video picked by mistake was read whole first).
                    val bytes = contentResolver.openInputStream(uri)?.use { com.swiftbrowser.core.Storage.readAtMost(it, 64 * 1024) }
                    bytes?.let { String(it) } to name
                } catch (_: Exception) {
                    null to null
                }
            }
            if (text == null) {
                snack("Couldn't read that file. A setup file is small and ends in .conf.")
                return@launch
            }
            if (addVpnText(text, name)) offerToDeleteVpnFile(uri, name)
        }
    }

    /** True when the location was added. */
    private fun addVpnText(text: String, name: String?): Boolean = try {
        val loc = com.swiftbrowser.vpn.Vpn.add(this, text, name)
        refreshAll()
        snack("Added ${loc.flag} ${loc.countryName}")
        true
    } catch (e: IllegalArgumentException) {
        Messages.show(this, e.message ?: "That isn't a VPN setup file", long = true)
        false
    }

    /**
     * 3.0: the file picked holds the location's private key in plain text, where any app with
     * storage access can read it; Swift keeps its own locked copy, so the file can go.
     */
    private fun offerToDeleteVpnFile(uri: android.net.Uri, name: String?) {
        Dialogs.confirm(
            this, "Delete the setup file?",
            "Swift keeps its own locked copy. ${name ?: "The file"} holds the location's key unprotected, and other apps " +
                "on your phone can read it: anyone with it can use your location.",
            "Delete", danger = true, cancel = "Keep",
        ) {
            lifecycleScope.launch {
                val deleted = withContext(Dispatchers.IO) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (_: Exception) {
                        false
                    }
                }
                snack(if (deleted) "Setup file deleted" else "Swift couldn't delete it: delete ${name ?: "it"} in Files")
            }
        }
    }

    /** For a file opened elsewhere: its text pasted in a box (several lines). */
    private fun pasteVpnText() {
        val input = EditText(this).apply {
            hint = "[Interface] … [Peer] …"
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Paste the setup file")
            .setView(box)
            .setPositiveButton("Add") { _, _ -> addVpnText(input.text.toString(), null) }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun vpnHowTo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Free locations from Proton VPN")
            .setMessage(
                "1. Make a free account at protonvpn.com (no card needed).\n\n" +
                    "2. At account.protonvpn.com, open Downloads, then WireGuard configuration.\n\n" +
                    "3. Give it a name, choose Android, pick a free server in the country you want, and tap Create.\n\n" +
                    "4. Tap Download: Swift offers \"Add to VPN\" when it's done (or tap the file in Swift's Downloads).\n\n" +
                    "One file is one server; add one per country you want. Keep the files private: they work like a password.",
            )
            .setPositiveButton("Open Proton") { _, _ -> openInBrowser("https://account.protonvpn.com/downloads") }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    private fun secureDnsText(): String = when (prefs.secureDns) {
        "off" -> "Off: your internet provider can see which sites you visit"
        "quad9" -> "Quad9: hides which sites you visit from your internet provider, and blocks known bad sites"
        "google" -> "Google: hides which sites you visit from your internet provider"
        else -> "Cloudflare: hides which sites you visit from your internet provider"
    }

    /** DNS over HTTPS (2.2): who looks up sites' addresses, encrypted; your network's own when off. */
    private fun chooseSecureDns() {
        val keys = listOf("cloudflare", "quad9", "google", "off")
        val labels = listOf("Cloudflare (recommended)", "Quad9 (also blocks known bad sites)", "Google", "Off (your internet provider's)")
        choose("Private site lookups", labels, keys.indexOf(prefs.secureDns).coerceAtLeast(0)) { which ->
            prefs.secureDns = keys[which]
            Engine.applySecureDns()
        }
    }

    private fun siteSettingsText(): String {
        val n = SiteSettings.sites().size
        return if (n == 0) "None yet. On a site, open ≡ and tap the sliders button next to the site's name." else "$n ${if (n == 1) "site" else "sites"}"
    }

    private fun chooseSite() {
        val sites = SiteSettings.sites()
        if (sites.isEmpty()) {
            snack("No site has its own settings yet")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Site settings")
            .setItems(sites.toTypedArray()) { _, which -> Sheets.siteSettings(this, sites[which], onChanged = { refreshAll() }) }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    private fun backUp() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    Backup.write(this@SettingsActivity)
                } catch (e: Exception) {
                    null
                }
            }
            snack(if (file == null) "Couldn't write the backup" else "Saved to ${Downloads.describe(file.parentFile ?: file)} › ${file.name}")
        }
    }

    /** Backups found in the download folders, newest first; the file picker finds the others. */
    private fun chooseBackup() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { Backup.found() }
            if (found.isEmpty()) {
                pickBackupFile()
                return@launch
            }
            val labels = found.map { "${it.name}\n${Downloads.describe(it.parentFile ?: it)}" } + "Choose another file…"
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Restore from a backup")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < found.size) confirmRestore(android.net.Uri.fromFile(found[which])) else pickBackupFile()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
        }
    }

    private fun pickBackupFile() {
        restorePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    /** 3.0.4: the picture for the home page's background, kept small in Swift's files. */
    private val homePicturePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        com.swiftbrowser.core.Background.single("home-picture").execute {
            val ok = HomeBackground.savePicture(this, uri)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    prefs.homeBackground = HomeBackground.PICTURE
                    snack("Your picture is behind the home page now")
                } else {
                    snack("Swift couldn't read that picture")
                }
                refreshAll()
            }
        }
    }

    /** 3.0.4: Swift's colour, a gradient, your accent colour or your own picture behind the home page. */
    private fun chooseHomeBackground() {
        val now = HomeBackground.current(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun row(key: String, swatch: android.graphics.drawable.Drawable?, icon: Int, label: String, pick: () -> Unit) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(22), dp(4), dp(16), dp(4))
                setBackgroundResource(RowHolder.selectableBackground(this@SettingsActivity))
                addView(android.widget.ImageView(this@SettingsActivity).apply {
                    if (swatch != null) setImageDrawable(swatch) else setImageDrawable(Ui.icon(this@SettingsActivity, icon, Palette.color(this@SettingsActivity, R.color.text_secondary)))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(Ui.text(this@SettingsActivity, Ui.Type.BODY, label).apply { setPadding(dp(16), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (key == now) addView(android.widget.ImageView(this@SettingsActivity).apply {
                    setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_check, Ui.accent(this@SettingsActivity)))
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                contentDescription = label + if (key == now) ", chosen" else ""
                setOnClickListener {
                    dialog?.dismiss()
                    pick()
                }
            })
        }
        fun choose(key: String) {
            prefs.homeBackground = key
            refreshAll()
        }
        // First, so it is in sight on a small phone; only with a picture in use.
        if (now == HomeBackground.PICTURE) list.addView(pictureClarity())
        row(HomeBackground.NONE, HomeBackground.swatch(this, HomeBackground.NONE), 0, HomeBackground.label(HomeBackground.NONE)) { choose(HomeBackground.NONE) }
        for (p in HomeBackground.PRESETS) row(p.key, HomeBackground.swatch(this, p.key), 0, p.label) { choose(p.key) }
        row(HomeBackground.ACCENT, HomeBackground.swatch(this, HomeBackground.ACCENT), 0, HomeBackground.label(HomeBackground.ACCENT)) { choose(HomeBackground.ACCENT) }
        if (HomeBackground.hasPicture(this)) {
            row(HomeBackground.PICTURE, null, R.drawable.ic_image, "Your picture") { choose(HomeBackground.PICTURE) }
            row("-", null, R.drawable.ic_image, "Another picture…") { homePicturePicker.launch(arrayOf("image/*")) }
            row("--", null, R.drawable.ic_delete, "Remove your picture") {
                HomeBackground.removePicture(this)
                refreshAll()
            }
        } else {
            row("-", null, R.drawable.ic_image, "Your own picture…") { homePicturePicker.launch(arrayOf("image/*")) }
        }
        list.addView(Ui.text(this, Ui.Type.CAPTION, "The less clear a picture is, the easier the shortcuts are to read. Not in incognito.").apply {
            setPadding(dp(22), dp(10), dp(22), dp(4))
        })
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Background of the home page")
            .setView(android.widget.ScrollView(this).apply {
                // Softly cut at the bottom when there is more to scroll to.
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(24))
                addView(list)
            })
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    /**
     * How clear the home page's picture is: a little picture of it with a heading on it, as the home
     * page will look, and a slider from softest (the page's colour laid over it) to the picture exactly
     * as it is. The home page takes it when it next shows.
     */
    private fun pictureClarity(): View {
        val shownAt = prefs.homePictureClarity
        val percent = Ui.steadyFigures(Ui.text(this, Ui.Type.LABEL, "$shownAt%"))
        var picture: android.graphics.drawable.Drawable? = null
        val sample = Ui.text(this, Ui.Type.HEADING, "Shortcuts")
        fun sampleInk() = HomeBackground.paint(sample, HomeBackground.inkOf(this, picture), Ui.text(this))
        val preview = FrameLayout(this).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Ui.R_SMALL).toFloat())
            }
            clipToOutline = true
            setBackgroundColor(Palette.color(this@SettingsActivity, R.color.home_bg))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(sample, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val slider = SwiftSlider(this, SwiftSlider.Style.PANEL).apply {
            max = 100
            progress = shownAt
            setColours(Ui.accent(this@SettingsActivity), Ui.accentContainer(this@SettingsActivity))
            contentDescription = "How clear the picture is"
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    percent.text = "$value%"
                    HomeBackground.showClarity(picture, value)
                    sampleInk()
                    prefs.homePictureClarity = value
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}

                override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
            })
        }
        HomeBackground.drawable(this, incognito = false) { late ->
            picture = late
            HomeBackground.showClarity(late, slider.progress)
            preview.background = late
            sampleInk()
        }?.let {
            picture = it
            preview.background = it
            sampleInk()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(12))
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(Ui.text(this@SettingsActivity, Ui.Type.BODY, "How clear the picture is"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(percent)
            })
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private val restorePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    private fun confirmRestore(uri: android.net.Uri) {
        lifecycleScope.launch {
            val root = withContext(Dispatchers.IO) { Backup.read(this@SettingsActivity, uri) }
            if (root == null) {
                snack("That file isn't a Swift backup")
                return@launch
            }
            // 3.0.1: a backup's format says which Swift can read it.
            if (Backup.isNewer(root)) {
                snack("That backup was made by a newer Swift. Update Swift, then restore it.")
                return@launch
            }
            val s = Backup.summary(root)
            val d = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Restore this backup?")
                .setMessage(
                    "Made on ${s.created}: ${s.profiles} ${if (s.profiles == 1) "profile" else "profiles"}, ${s.bookmarks} ${if (s.bookmarks == 1) "bookmark" else "bookmarks"}, " +
                        "${s.tabs} ${if (s.tabs == 1) "tab" else "tabs"} and ${s.history} ${if (s.history == 1) "history entry" else "history entries"}.\n\nThey are added to what is on your phone, its settings " +
                        "replace these, and Swift restarts. Passwords, the VPN and the Private folder are never in backups.",
                )
                .setPositiveButton("Restore") { _, _ ->
                    // 3.0: a backup that can't be restored changes nothing, and says so. 3.0.1: one
                    // that was says exactly what went in, then Swift restarts to load it.
                    Backup.restoreThen(this@SettingsActivity, root) { restored ->
                        if (isFinishing || isDestroyed) {
                            if (restored != null) SwiftApp.restart(this@SettingsActivity)
                        } else if (restored == null) {
                            Messages.show(this@SettingsActivity, "That backup couldn't be restored. Nothing on your phone was changed.", long = true)
                        } else {
                            MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("Backup restored")
                                .setMessage(Backup.restoredText(restored) + "\n\nSwift restarts to load it.")
                                .setPositiveButton("Restart Swift") { _, _ -> SwiftApp.restart(this@SettingsActivity) }
                                .setCancelable(false)
                                .showImmersive()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
            d.setCanceledOnTouchOutside(false)
        }
    }

    private fun inactiveText(days: Int) = if (days <= 0) "Never" else "After $days days without opening them"

    private fun chooseInactiveDays() {
        val options = intArrayOf(7, 14, 21, 30, 0)
        val labels = options.map { if (it == 0) "Never" else "After $it days" }
        choose("Move tabs to \"Inactive tabs\"", labels, options.indexOf(prefs.inactiveDays).coerceAtLeast(0)) { which ->
            prefs.inactiveDays = options[which]
        }
    }

    private fun checkForUpdates() {
        snack("Checking for updates…")
        Updates.latest { c ->
            // 3.0: Settings may have closed meanwhile (a dialog on a closed screen crashed Swift).
            if (isFinishing || isDestroyed) return@latest
            if (c.version != null) prefs.lastUpdateCheck = System.currentTimeMillis()
            val r = c.release
            when {
                // 3.0: the real reason (GitHub busy, the file not up yet), not always the connection.
                c.problem != null -> Messages.show(this, c.problem, long = true)
                r == null || !Updates.isNewer(r.version, BuildConfig.VERSION_NAME) -> snack("Swift ${BuildConfig.VERSION_NAME} is the newest version")
                else -> {
                    val size = if (r.size > 0) " (${UrlUtils.formatBytes(r.size)})" else ""
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Update to Swift ${r.version}?")
                        .setMessage(r.notes.ifBlank { "A new version of Swift is available." }.take(1500) +
                            "\n\nThe update$size downloads now; then Android asks to install it. Tabs and settings are kept.")
                        .setPositiveButton("Download") { _, _ ->
                            val item = Downloads.startUrl(Profiles.currentId, r.apkUrl, r.apkName, "application/vnd.android.package-archive", emptyMap(), null)
                            Updates.downloadId = item.id
                            snack("Downloading Swift ${r.version}… Android asks to install it when it's done.")
                        }
                        .setNegativeButton("Later", null)
                        .showImmersive()
                }
            }
        }
    }

    /** 3.0.2: the order of the home page's parts under the search box, moved up or down; nothing is left out. */
    private fun chooseHomeOrder() {
        var order = com.swiftbrowser.core.HomeOrder.parse(prefs.homeOrder)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        fun rebuild() {
            list.removeAllViews()
            order.forEachIndexed { i, part ->
                val name = com.swiftbrowser.core.HomeOrder.label(part)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(2), dp(4), dp(2))
                }
                row.addView(Ui.text(this, Ui.Type.BODY, "${i + 1}.  $name"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun arrow(icon: Int, what: String, by: Int, can: Boolean) = android.widget.ImageButton(this).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(Palette.color(this@SettingsActivity, R.color.text_primary))
                    setBackgroundResource(borderless())
                    contentDescription = "Move $name $what"
                    isEnabled = can
                    alpha = if (can) 1f else Ui.DISABLED_ALPHA
                    setOnClickListener {
                        order = com.swiftbrowser.core.HomeOrder.move(order, part, by)
                        prefs.homeOrder = com.swiftbrowser.core.HomeOrder.encode(order)
                        rebuild()
                    }
                }
                row.addView(arrow(R.drawable.ic_up, "up", -1, i > 0), LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(arrow(R.drawable.ic_down, "down", 1, i < order.size - 1), LinearLayout.LayoutParams(dp(48), dp(48)))
                list.addView(row)
            }
            list.addView(Ui.text(this, Ui.Type.CAPTION, "Under the search box. Continue shows only when there is something to go on with.").apply {
                setPadding(dp(16), dp(8), dp(16), dp(4))
            })
        }
        rebuild()
        MaterialAlertDialogBuilder(this)
            .setTitle("Order of the home page")
            .setView(list)
            .setPositiveButton("Done") { _, _ -> refreshAll() }
            .setOnDismissListener { refreshAll() }
            .showImmersive()
    }

    /** Settings → Privacy & security → Security status (3.0.1). The keystore is read off the main thread. */
    private fun showSecurityStatus() {
        lifecycleScope.launch {
            val profile = com.swiftbrowser.core.Profiles.currentId
            val passwords = withContext(Dispatchers.IO) {
                try {
                    com.swiftbrowser.core.Passwords.count(profile).readable
                } catch (_: Exception) {
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            val vpn = when (com.swiftbrowser.vpn.Vpn.state) {
                com.swiftbrowser.vpn.Vpn.State.ON -> com.swiftbrowser.core.SecurityStatus.Vpn.ON
                com.swiftbrowser.vpn.Vpn.State.CONNECTING -> com.swiftbrowser.core.SecurityStatus.Vpn.CONNECTING
                com.swiftbrowser.vpn.Vpn.State.FAILED -> com.swiftbrowser.core.SecurityStatus.Vpn.FAILED
                com.swiftbrowser.vpn.Vpn.State.OFF -> com.swiftbrowser.core.SecurityStatus.Vpn.OFF
            }
            val inputs = com.swiftbrowser.core.SecurityStatus.Inputs(
                adLevel = AdBlock.global,
                uboReady = AdBlock.ready,
                secureDns = prefs.secureDns,
                httpsOnly = prefs.httpsOnly,
                saferBrowsing = prefs.saferBrowsing,
                passwords = passwords,
                offerPasswords = prefs.offerPasswords,
                vpn = vpn,
                vpnServer = com.swiftbrowser.vpn.Vpn.current()?.server?.takeIf { vpn == com.swiftbrowser.core.SecurityStatus.Vpn.ON },
                locationSites = com.swiftbrowser.core.SiteSettings.locationAllowedCount(),
            )
            val rows = dialogColumn()
            for (line in com.swiftbrowser.core.SecurityStatus.lines(inputs)) rows.addView(securityRow(line))
            rows.addView(Ui.text(this@SettingsActivity, Ui.Type.CAPTION, com.swiftbrowser.core.SecurityStatus.LEGEND).apply { setPadding(0, dp(12), 0, 0) })
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Security status")
                .setView(scrolling(rows))
                .setPositiveButton("Close", null)
                .showImmersive()
        }
    }

    /** What a dialog of rows holds: a column lined up with the dialog's title. */
    private fun dialogColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(4), dp(24), dp(8))
    }

    private fun scrolling(content: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        Palette.glow(this)
        addView(content)
    }

    /** A protection: its name, what is known about it under that, and at the row's end how sure Swift is. */
    private fun securityRow(line: com.swiftbrowser.core.SecurityStatus.Line): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        // TalkBack reads a row as one line, as the words did when they were one text.
        isFocusable = true
        contentDescription = "${line.name}, ${line.state.word}. ${line.detail}"
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@SettingsActivity, Ui.Type.BODY, line.name))
            addView(Ui.text(this@SettingsActivity, Ui.Type.CAPTION, line.detail).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(stateLabel(line.state), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
    }

    /** Checked in the accent's soft colour, On on a grey, Off just in grey words. */
    private fun stateLabel(state: com.swiftbrowser.core.SecurityStatus.State): TextView {
        val (fill, ink) = when (state) {
            com.swiftbrowser.core.SecurityStatus.State.CHECKED -> Ui.accentContainer(this) to Ui.onAccentContainer(this)
            com.swiftbrowser.core.SecurityStatus.State.ON -> color(R.color.surface_alt) to color(R.color.text_primary)
            com.swiftbrowser.core.SecurityStatus.State.OFF -> null to color(R.color.text_secondary)
        }
        return Ui.text(this, Ui.Type.SMALL, state.word, ink).apply {
            if (fill != null) background = Ui.shape(this@SettingsActivity, fill, Ui.R_XSMALL)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
    }

    /** Settings → Speed & memory → Right now (3.0.1): cheap enough for every refresh of the list. */
    private fun rightNowSummary(): String {
        val (free, total, _) = com.swiftbrowser.core.RightNow.memory(this)
        return com.swiftbrowser.core.RightNow.summary(free, total, TabManager.tabs.count { it.session != null }, TabManager.tabs.size)
    }

    /** Measured now: Swift's own memory is read off the main thread. Null when Settings has closed meanwhile. */
    private suspend fun measureRightNow(): com.swiftbrowser.core.RightNow.Snapshot? {
        val app = applicationContext
        val (parts, crashes) = withContext(Dispatchers.IO) {
            com.swiftbrowser.core.RightNow.parts(app) to Diagnostics.engineCrashes(app).map { it.time }
        }
        if (isFinishing || isDestroyed) return null
        val (free, total, low) = com.swiftbrowser.core.RightNow.memory(this@SettingsActivity)
        val tabs = TabManager.tabs
        return com.swiftbrowser.core.RightNow.Snapshot(
            freeMb = free,
            totalMb = total,
            low = low,
            parts = parts,
            tabs = tabs.size,
            loaded = tabs.count { it.session != null },
            kept = tabs.count { it.kept },
            limitedBy = when {
                Power.saving -> "Battery saver is on"
                prefs.maxLiveTabs > 1 && Diagnostics.memoryLow() -> "Memory is low"
                else -> null
            },
            crashesThisWeek = com.swiftbrowser.core.RightNow.crashesSince(crashes, System.currentTimeMillis()),
            lastUnloaded = TabManager.lastUnloaded?.let {
                com.swiftbrowser.core.RightNow.unloadedText(it.count, System.currentTimeMillis() - it.time, it.why)
            },
            lastRun = when (Diagnostics.problem) {
                Diagnostics.Problem.CLOSED_BY_ANDROID -> "Last time, Android closed Swift while it was on screen, most likely to free memory."
                Diagnostics.Problem.ENGINE_CRASH -> "Last time, pages stopped working while Swift was on screen."
                null -> null
            },
        )
    }

    /** The details. Refresh measures again with the dialog left open, and what changed cross-fades where it is. */
    private fun showRightNow() {
        lifecycleScope.launch {
            val first = measureRightNow() ?: return@launch
            val rows = dialogColumn()
            bindRightNow(rows, com.swiftbrowser.core.RightNow.rows(first))
            val d = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Right now")
                .setView(scrolling(rows))
                .setPositiveButton("Refresh", null)
                .setNegativeButton("Close", null)
                .showImmersive()
            var measuring = false
            // Its own listener: the dialog's would close it.
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                if (measuring) return@setOnClickListener
                measuring = true
                lifecycleScope.launch {
                    val now = measureRightNow()
                    measuring = false
                    if (now != null && d.isShowing) {
                        bindRightNow(rows, com.swiftbrowser.core.RightNow.rows(now))
                        refreshAll()
                    }
                }
            }
            refreshAll()
        }
    }

    /** [rows] into [box]: in the rows already there when they are the same ones, otherwise made anew. */
    private fun bindRightNow(box: LinearLayout, rows: List<com.swiftbrowser.core.RightNow.Row>) {
        val same = box.childCount == rows.size && rows.indices.all { box.getChildAt(it).tag == rows[it].label }
        if (same) {
            rows.forEachIndexed { i, row ->
                val line = box.getChildAt(i) as LinearLayout
                Motion.swapText(line.getChildAt(line.childCount - 1) as TextView, row.value)
            }
            return
        }
        box.removeAllViews()
        for (row in rows) box.addView(rightNowRow(row))
    }

    /** A measure: what it is on the left, how much on the right; a sentence of its own across the row. */
    private fun rightNowRow(row: com.swiftbrowser.core.RightNow.Row): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        tag = row.label
        if (row.label == null) {
            addView(Ui.steadyFigures(Ui.text(this@SettingsActivity, Ui.Type.BODY_SECONDARY, row.value)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            addView(Ui.text(this@SettingsActivity, Ui.Type.BODY_SECONDARY, row.label))
            addView(Ui.steadyFigures(Ui.text(this@SettingsActivity, Ui.Type.BODY_SECONDARY, row.value, color(R.color.text_primary))).apply {
                gravity = Gravity.END
                setPadding(dp(16), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun liveTabsText(n: Int) = when (n) {
        1 -> "1 tab: uses the least memory; others load again when you open them"
        2 -> "2 tabs (best for phones with little memory)"
        in 3..4 -> "$n tabs: quicker to switch, uses more memory"
        else -> "$n tabs: uses a lot of memory"
    }

    /** A single choice from [labels] (2.9); [picked] then the rows refresh, then [then] (a message about it). */
    private fun choose(title: String, labels: List<String>, checked: Int, then: ((Int) -> Unit)? = null, picked: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                picked(which)
                d.dismiss()
                refreshAll()
                then?.invoke(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun chooseLiveTabs() {
        val options = (1..Prefs.MAX_LIVE_TABS_LIMIT).toList()
        val labels = options.map { liveTabsText(it) }
        choose("Tabs ready to open instantly", labels, options.indexOf(prefs.maxLiveTabs)) { which ->
            prefs.maxLiveTabs = options[which]
            // 3.0.1: a lower number applies now, not at the next tab switch.
            com.swiftbrowser.tabs.TabManager.trimLiveSessions()
        }
    }

    private fun confirmRestart(text: String) {
        Dialogs.confirm(this, "Restart Swift?", "$text Your tabs stay open.", "Restart now", cancel = "Later") { SwiftApp.restart(this) }
    }

    private fun siteLevelsText(): String {
        val n = AdBlock.overrides().size
        return if (n == 0) "None yet. Set one from ≡ → Ad blocker on a site." else "$n ${if (n == 1) "site" else "sites"}"
    }

    private fun showSiteLevels() {
        val entries = AdBlock.overrides().entries.toList()
        if (entries.isEmpty()) {
            snack("No sites have their own level yet")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Tap a site to reset it")
            .setItems(entries.map { "${it.key} — ${it.value.label}" }.toTypedArray()) { _, which ->
                AdBlock.setOverride(entries[which].key, null)
                refreshAll()
            }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    /** Data used per day (2.6), on mobile data and on Wi-Fi. */
    private fun showDataUsage() {
        val days = com.swiftbrowser.engine.DataUsage.recent().take(30)
        if (days.isEmpty()) {
            Dialogs.info(this, "Data used", "Nothing counted yet. Swift counts what it uses from now on.")
            return
        }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val show = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        // The day on the left, then mobile data and Wi-Fi in a column each.
        val table = android.widget.TableLayout(this).apply {
            setColumnStretchable(0, true)
            setColumnShrinkable(0, true)
        }
        fun amount(text: String) = Ui.steadyFigures(Ui.text(this, Ui.Type.CAPTION, text)).apply {
            gravity = Gravity.END
            setPadding(dp(12), 0, 0, 0)
        }
        for (day in days) {
            val name = if (day.day == today) "Today" else try { show.format(parse.parse(day.day)!!) } catch (e: Exception) { day.day }
            table.addView(android.widget.TableRow(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                addView(Ui.text(this@SettingsActivity, Ui.Type.BODY_SECONDARY, name, color(R.color.text_primary)))
                addView(amount("${UrlUtils.formatBytes(day.mobile)} mobile"))
                addView(amount("${UrlUtils.formatBytes(day.other)} Wi-Fi"))
            })
        }
        val rows = dialogColumn()
        rows.addView(table)
        rows.addView(Ui.text(this, Ui.Type.CAPTION, "Counted by Swift while it runs, so your carrier's count may differ a little.").apply { setPadding(0, dp(12), 0, 0) })
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Data used")
            .setView(scrolling(rows))
            .setPositiveButton("Close", null)
            .setNeutralButton("Reset") { _, _ ->
                com.swiftbrowser.engine.DataUsage.reset()
                refreshAll()
            }
            .showImmersive()
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setTextColor(Ui.danger(this))
    }

    /** By time (2.6): the last hour, 24 hours, 7 days, 4 weeks or all time, and what to clear. */
    private fun clearData() {
        ClearData.show(this) {
            countPasswords()
            remeasureSoon()
        }
    }

    private var passwordsText = "Sign-ins Swift fills in"

    private fun countPasswords() {
        lifecycleScope.launch {
            // 3.0: sign-ins that can't be read any more aren't counted as ones (the list didn't show them).
            val c = withContext(Dispatchers.IO) { try { Passwords.count(Profiles.currentId) } catch (e: Exception) { Passwords.Count(0, 0) } }
            val n = c.readable
            val text = listOfNotNull(
                when {
                    n == 0 && c.unreadable == 0 -> "None yet"
                    n == 0 -> null
                    n == 1 -> "1 sign-in"
                    else -> "$n sign-ins"
                },
                if (c.unreadable > 0) "${c.unreadable} can't be read any more" else null,
            ).joinToString(" · ")
            if (text != passwordsText) {
                passwordsText = text
                refreshAll()
            }
        }
    }

    private var settingsSearch: EditText? = null

    /** The screen lock check before saved passwords show (2.6); one check lasts a minute. */
    private var afterUnlock: (() -> Unit)? = null
    private var unlockedAt = 0L
    private val unlock = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val then = afterUnlock
        afterUnlock = null
        if (r.resultCode == RESULT_OK) {
            unlockedAt = android.os.SystemClock.elapsedRealtime()
            then?.invoke()
        }
    }

    private fun checkScreenLock(then: () -> Unit) {
        if (unlockedAt > 0 && android.os.SystemClock.elapsedRealtime() - unlockedAt < 60_000) {
            then()
            return
        }
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val intent = if (km?.isKeyguardSecure == true) {
            @Suppress("DEPRECATION")
            km.createConfirmDeviceCredentialIntent("Saved passwords", "Enter your screen lock to see them")
        } else {
            null
        }
        if (intent == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No screen lock")
                .setMessage("Anyone holding your phone could see your passwords. A screen lock (PIN, pattern or password) keeps them for you only.")
                .setPositiveButton("Set a screen lock") { _, _ ->
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e: Exception) {
                        snack("Open Android's Settings → Security")
                    }
                }
                .setNegativeButton("Show anyway") { _, _ -> then() }
                .showImmersive()
            return
        }
        afterUnlock = then
        try {
            unlock.launch(intent)
        } catch (e: Exception) {
            // 3.0: they show only after the screen lock (when asking for it couldn't start, they showed anyway).
            afterUnlock = null
            com.swiftbrowser.core.L.w("The screen lock question didn't start", e)
            Messages.show(this, "Swift couldn't ask for your screen lock, so your passwords stay hidden. Try again in a moment.", long = true)
        }
    }

    private fun clearCache() {
        Dialogs.confirm(
            this, "Clear the cache?",
            "The cache (${UrlUtils.formatBytes(usage?.cache ?: 0)}) is deleted: copies of pages' pictures and files that make them load faster. " +
                "Pages load them again when needed; sign-ins and settings stay.",
            "Clear", danger = true,
        ) {
            Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
            snack("Cache cleared")
            remeasureSoon()
        }
    }

    /**
     * 3.0: measured at most once a minute (walking the engine's profile, the cache and the data folder
     * takes seconds on your phone, and it ran on every visit to Settings), and anew after clearing.
     */
    private fun computeStorage(fresh: Boolean = false) {
        lifecycleScope.launch {
            usage = withContext(Dispatchers.IO) { com.swiftbrowser.core.Storage.measureRecent(this@SettingsActivity, fresh) }
            refreshAll()
        }
    }

    /** Measures again a moment after the engine was asked to delete something. */
    private fun remeasureSoon() {
        lifecycleScope.launch {
            delay(1500)
            computeStorage(fresh = true)
        }
    }

    /** A bar of what Swift keeps on the phone, with each part and its size below it. */
    private fun storageBreakdown(refresh: MutableList<() -> Unit>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.ROW_INSET), dp(16), dp(Ui.ROW_INSET), dp(12))
        }
        val total = TextView(this).apply {
            Ui.size(this, Ui.Type.HEADLINE, keepFont = true)
            setTypeface(Ui.medium(Ui.Type.HEADLINE))
            setTextColor(color(R.color.text_primary))
        }
        box.addView(total)
        val bar = StorageBar(this)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
            topMargin = dp(10)
            bottomMargin = dp(6)
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(rows)
        val note = TextView(this).apply {
            Ui.size(this, Ui.Type.CAPTION)
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(note)
        // What is yours to clear in the colours made from your accent; what can't get smaller in greys.
        val card = color(R.color.card_bg)
        val night = Palette.isNight(this)
        val yours = StorageColours.kinds(Ui.accent(this), card, night)
        val grey = StorageColours.other(card, night)
        val greys = IntArray(4) { androidx.core.graphics.ColorUtils.blendARGB(grey, card, it * 0.22f) }
        // Room for the rows while measuring, so they don't push everything down when they come.
        val reserved = dp(30) * 7
        var shown: List<Long>? = null
        val update: () -> Unit = update@{
            val u = usage
            val numbers = u?.let { listOf(it.app, it.adBlock, it.sites, it.cache, it.engine, it.swift, it.kept, it.other, it.total, it.androidTotal) }
            // The same numbers again (every refresh of the page): nothing to do.
            if (numbers != null && numbers == shown) return@update
            val first = shown == null && numbers != null
            shown = numbers
            rows.minimumHeight = if (u == null) reserved else 0
            total.text = if (u == null) "Calculating…" else "Swift uses ${UrlUtils.formatBytes(u.total)}"
            // Android's App info lists the cache on its own, outside its Total.
            note.text = (if (u == null) "" else "Android's App info shows ${UrlUtils.formatBytes(u.androidTotal)} for Swift, plus the cache on its own line. ") +
                "Most of the app is the browser itself and can't get smaller; the ad blocking files stay so blocking is fast. Old update files are deleted by themselves."
            rows.removeAllViews()
            if (u != null) {
                val parts = listOf(
                    Triple("The app", u.app, greys[0]), Triple("Ad blocking files", u.adBlock, greys[1]), Triple("Data saved by sites", u.sites, yours[0]),
                    Triple("Cache", u.cache, yours[1]), Triple("Browser files", u.engine, greys[2]), Triple("Tabs, history and pictures", u.swift, yours[2]),
                    Triple("Downloads inside Swift", u.kept, yours[3]), Triple("Other", u.other, greys[3]),
                )
                bar.show(LongArray(parts.size) { parts[it].second }, IntArray(parts.size) { parts[it].third })
                for ((label, bytes, c) in parts) {
                    // 2.8: only shown when there are some (the option to move them out is gone).
                    if (bytes <= 0 && label == "Downloads inside Swift") continue
                    rows.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, dp(5))
                        addView(View(this@SettingsActivity).apply { background = Ui.circle(c) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
                        addView(TextView(this@SettingsActivity).apply {
                            text = label
                            Ui.size(this, Ui.Type.BODY)
                            setTextColor(color(R.color.text_primary))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@SettingsActivity).apply {
                            text = UrlUtils.formatBytes(bytes)
                            Ui.size(this, Ui.Type.BODY_SECONDARY)
                            setTextColor(color(R.color.text_secondary))
                        })
                    })
                }
                // Measured while the page was open: the rows fade in where room was kept, and the bar grows from its start.
                if (first && box.isShown) {
                    Motion.fadeIn(rows, fromDy = 0)
                    bar.pivotX = if (bar.layoutDirection == View.LAYOUT_DIRECTION_RTL) bar.width.toFloat() else 0f
                    bar.scaleX = 0f
                    Motion.anim(bar, Motion.NORMAL, Motion.standard).scaleX(1f).start()
                }
            }
        }
        update()
        refresh += update
        return box
    }

    /** Sites that stored data, biggest first; tapping one clears it. */
    private fun showSiteStorage() {
        lifecycleScope.launch {
            val sites = withContext(Dispatchers.IO) { com.swiftbrowser.core.Storage.sites(this@SettingsActivity) }
            if (sites.isEmpty()) {
                snack("No site has stored anything")
                return@launch
            }
            val labels = sites.map { "${it.site} · ${UrlUtils.formatBytes(it.bytes)}" }.toTypedArray()
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Data saved by sites")
                .setItems(labels) { _, which ->
                    val site = sites[which]
                    Dialogs.confirm(
                        this@SettingsActivity, "Clear ${site.site}?",
                        "What it stored and its saved files (${UrlUtils.formatBytes(site.bytes)}) are deleted. You stay signed in.",
                        "Clear", danger = true,
                    ) {
                        com.swiftbrowser.core.Storage.clearSite(site.site)
                        snack("Cleared ${site.site}")
                        remeasureSoon()
                    }
                }
                .setNegativeButton("Close", null)
                .showImmersive()
        }
    }

    private fun clearUnusedSites() {
        Dialogs.confirm(
            this, "Clear sites you don't visit?",
            "Sites you haven't opened for 30 days lose everything they stored, cookies too, so you're signed out of them. Sites open in a tab stay.",
            "Clear", danger = true,
        ) {
            lifecycleScope.launch {
                val open = TabManager.tabs.mapNotNull { UrlUtils.site(it.url) }.toSet()
                val n = withContext(Dispatchers.IO) { com.swiftbrowser.core.Storage.clearUnused(this@SettingsActivity, 30, open) }
                snack(if (n == 0) "No unused sites found" else "Cleared $n ${if (n == 1) "site" else "sites"}")
                remeasureSoon()
            }
        }
    }

    private fun autoBackupText(): String {
        val last = prefs.lastAutoBackup
        return if (last <= 0) "The last two are kept in the download folder"
        else "Last one " + android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS)
    }

    private fun readerText() =
        (if (prefs.readerFont == "sans") "Sans-serif" else "Serif") + " · " +
            when (prefs.readerSpacing) {
                0 -> "tight lines"
                2 -> "loose lines"
                else -> "normal lines"
            } +
            // 3.0.4: and the page's colour when it isn't Swift's own.
            when (prefs.readerTheme) {
                "light" -> " · light page"
                "sepia" -> " · sepia page"
                "dark" -> " · dark page"
                else -> ""
            }

    // ------------------------------------------------------------------ helpers

    private fun snack(text: String) {
        Messages.show(this, text)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun borderless(): Int {
        val arr = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val res = arr.getResourceId(0, 0)
        arr.recycle()
        return res
    }

    private fun color(res: Int) = Palette.color(this, res)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Opens this topic (Topic's name) instead of the main page. */
        const val EXTRA_TOPIC = "topic"

        /** With [EXTRA_TOPIC]: only that part of the settings; Back leaves rather than showing the rest (2.9). */
        const val EXTRA_ONLY = "only_topic"

        // 3.0: the everyday words for search are in SettingsSearch.

        private const val STATE_TOPIC = "topic"
        private const val STATE_SCROLL = "scroll"
        private const val REQUEST_STORAGE = 41
    }
}
