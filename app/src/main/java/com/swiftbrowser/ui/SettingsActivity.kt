package com.swiftbrowser.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * described once below and appears both on its topic's page and in search results. Settings for
 * experts wait under "Advanced" at the end of their topic (2.0).
 */
class SettingsActivity : AppCompatActivity() {

    private enum class Topic(val title: String, val icon: Int) {
        LOOK("Look & screen", R.drawable.ic_palette),
        NEWS("Home & news", R.drawable.ic_news),
        BROWSING("Browsing & tabs", R.drawable.ic_tab),
        ADBLOCK("Ad blocking", R.drawable.ic_shield),
        SPEED("Speed & memory", R.drawable.ic_speed),
        VIDEO("Video & downloads", R.drawable.ic_movie),
        PRIVACY("Privacy & backup", R.drawable.ic_lock),
        VPN("VPN", R.drawable.ic_vpn),
        STORAGE("Storage", R.drawable.ic_storage),
        ABOUT("About Swift", R.drawable.ic_info),
    }

    /** One setting: where it goes, words it can be found by, and how its row is built. */
    private class Def(
        val topic: Topic,
        val section: String?,
        val title: String,
        val keywords: String,
        val advanced: Boolean,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        prefs = SwiftApp.prefs
        val bg = color(R.color.settings_bg)
        window.statusBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
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
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this@SettingsActivity).apply {
            // The same size as the other screens' titles (2.6).
            textSize = 22f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
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
        titleView.text = t?.title ?: "Settings"
        val page = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            // On a turned phone the page doesn't stretch across the whole screen.
            addView(MaxWidthLayout(this@SettingsActivity).apply { addView(Palette.paint(if (t == null) mainPage() else topicPage(t))) })
        }
        val old = container.getChildAt(0)
        container.addView(page)
        // The search box waits for a tap rather than taking the focus (and a blinking cursor).
        container.isFocusableInTouchMode = true
        container.requestFocus()
        if (animate && old != null) {
            // The new page slides in over the old one, which slides a little and fades.
            val shift = dp(56).toFloat() * if (forward) 1 else -1
            page.alpha = 0f
            page.translationX = shift
            page.animate().alpha(1f).translationX(0f).setDuration(Motion.NORMAL).setInterpolator(Motion.standard).withLayer().start()
            old.animate().alpha(0f).translationX(-shift / 3).setDuration(Motion.SHORT).setInterpolator(Motion.exit).withLayer()
                .withEndAction { container.removeView(old) }.start()
        } else if (old != null) {
            container.removeView(old)
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(28))
    }

    private fun mainPage(): View {
        val col = column()
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val normal = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val search = EditText(this).apply {
            hint = "Search settings"
            textSize = 15f
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_secondary))
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_url)?.mutate()?.also {
                it.setTint(color(R.color.surface_alt))
            }
            setPadding(dp(14), 0, dp(14), 0)
            compoundDrawablePadding = dp(10)
            val icon = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.ic_search)?.mutate()?.also {
                it.setTint(color(R.color.text_secondary))
                it.setBounds(0, 0, dp(20), dp(20))
            }
            setCompoundDrawablesRelative(icon, null, null, null)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                normal.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
                results.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                showResults(results, q)
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        settingsSearch = search
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })

        val card = card()
        val topics = Section(card)
        for (t in Topic.entries) {
            topics.action(t.icon, t.title, { topicSummary(t) }, badge = true) { show(t) }
        }
        normal.addView(card)
        normal.addView(TextView(this).apply {
            text = "Swift ${BuildConfig.VERSION_NAME} · Firefox engine · uBlock Origin ${AdBlock.uboVersion}".trimEnd(' ', '·')
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(18), 0, 0)
        })
        col.addView(normal)
        col.addView(results)
        return col
    }

    private fun showResults(results: LinearLayout, query: String) {
        results.removeAllViews()
        resultRefreshers.clear()
        if (query.isEmpty()) return
        val q = query.lowercase()
        // Everyday words find the settings they are about ("battery" finds Lite mode and Data saver).
        val words = listOf(q) + SYNONYMS.filterKeys { q.contains(it) || it.startsWith(q) && q.length >= 3 }.values.flatten()
        val found = defs.filter { d ->
            val title = d.title.lowercase()
            words.any { w -> title.contains(w) || d.keywords.contains(w) || d.topic.title.lowercase().contains(w) }
        }
        // Each new word brings its results in with a short fade (2.3). No layer: the list can be
        // taller than the GPU's biggest picture.
        results.animate().cancel()
        results.alpha = 0.4f
        results.animate().alpha(1f).setDuration(Motion.SHORT).setInterpolator(Motion.enter).start()
        if (found.isEmpty()) {
            results.addView(Ui.emptyState(this, R.drawable.ic_search, "Nothing found for \"$query\"", "Try another word, such as \"dark\", \"ads\" or \"video\"."))
            return
        }
        for ((t, items) in found.groupBy { it.topic }) {
            results.addView(label(t.title))
            val card = card()
            val section = Section(card, resultRefreshers)
            items.forEach { it.build(section) }
            results.addView(card)
        }
    }

    private fun topicPage(t: Topic): View {
        val col = column()
        if (t == Topic.ABOUT) col.addView(aboutHeader())
        addDefs(col, defs.filter { it.topic == t && !it.advanced })
        // Settings for experts, folded away until asked for.
        val advanced = defs.filter { it.topic == t && it.advanced }
        if (advanced.isNotEmpty()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            addDefs(box, advanced)
            val chevron = ImageView(this).apply {
                setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_down, color(R.color.text_secondary)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = Ui.ripple(this@SettingsActivity, null, Ui.R_MEDIUM)
                contentDescription = "Advanced settings"
                addView(Ui.text(this@SettingsActivity, Ui.Type.SUBTITLE, "Advanced", color(R.color.text_secondary)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chevron, LinearLayout.LayoutParams(dp(22), dp(22)))
                setOnClickListener {
                    val open = box.visibility != View.VISIBLE
                    chevron.animate().rotation(if (open) 180f else 0f).setDuration(Motion.NORMAL).setInterpolator(Motion.standard).start()
                    if (open) Motion.fadeIn(box, fromDy = 12) else Motion.fadeOut(box, toDy = 0)
                }
            }
            col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            col.addView(box)
        }
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
                else -> "Lighter sites for this phone"
            },
            if (prefs.maxLiveTabs == 1 || Power.saving) "1 tab kept loaded" else "${prefs.maxLiveTabs} tabs kept loaded",
        ).joinToString(" · ")
        Topic.VIDEO -> listOfNotNull(
            if (prefs.keepPlaying) "Keeps playing in the background" else null,
            if (prefs.continueWatching) "Continue watching" else null,
            if (prefs.videoTaps) "Double-tap to skip" else null,
        ).joinToString(" · ").ifEmpty { "Players and downloads" }
        Topic.PRIVACY -> listOfNotNull(
            if (prefs.httpsOnly) "Secure connections" else null,
            "Clear history and data",
            "Back up",
        ).joinToString(" · ")
        Topic.VPN -> VpnUi.label().let { if (com.swiftbrowser.vpn.Vpn.locations().isEmpty()) "Change your country for free (Proton VPN)" else it }
        Topic.STORAGE -> usage?.let { "Swift uses ${UrlUtils.formatBytes(it.total)} · cache ${UrlUtils.formatBytes(it.cache)}" } ?: "Calculating…"
        Topic.ABOUT -> "Swift ${BuildConfig.VERSION_NAME} · Updates · Problem report"
    }

    // ------------------------------------------------------------------ all the settings

    private fun buildDefs(): List<Def> {
        val list = ArrayList<Def>()
        fun def(t: Topic, section: String?, title: String, keywords: String = "", advanced: Boolean = false, build: Section.() -> Unit) {
            list += Def(t, section, title, keywords, advanced, build)
        }

        // Look & screen
        def(Topic.LOOK, null, "Theme", "dark mode light night black colours evening") {
            action(R.drawable.ic_dark, "Theme", { themeText() }) { chooseTheme() }
        }
        def(Topic.LOOK, "Colours", "Colours", "color colour accent background black amoled navy warm blue green purple orange red teal pink indigo amber cyan brown graphite custom own personal") {
            custom(ColoursPanel.build(this@SettingsActivity))
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
            switch(R.drawable.ic_fullscreen, "Full screen", { "Hide the phone's bars at the top and bottom" }, checked = { prefs.fullScreen }) {
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
        def(Topic.LOOK, "Address bar", "Address bar buttons", "toolbar buttons home house profile tabs menu reload share bookmark new tab scan qr arrange customize") {
            action(R.drawable.ic_toolbar_buttons, "Address bar buttons", { toolbarButtonsText() }) { arrangeToolbar() }
        }
        def(Topic.LOOK, "Address bar", "Address bar swipes", "swipe up down hide show address bar toolbar gesture") {
            action(R.drawable.ic_gesture, "Address bar swipes", { barSwipesText(prefs.barSwipes) }) { chooseBarSwipes() }
        }
        def(Topic.LOOK, "Address bar", "Hide while scrolling", "toolbar address bar url bar top") {
            switch(R.drawable.ic_toolbar, "Hide while scrolling", { "More room for the page; scroll back a little to bring it back" }, checked = { prefs.hideToolbarOnScroll }) {
                prefs.hideToolbarOnScroll = it
            }
        }
        def(Topic.LOOK, "Address bar", "Bar follows your finger", "toolbar animation smooth chrome follow finger slides", advanced = true) {
            switch(
                R.drawable.ic_touch, "Bar follows your finger",
                { if (prefs.toolbarFollowsScroll) "It slides away and back as far as you scroll" else "Off: it hides and comes back in one go" },
                details = "On, the address bar slides away and back exactly as far as you scroll, and finishes with a short " +
                    "slide when you let go, as in Chrome. Off, it hides or comes back in one go after a clear scroll.\n\n" +
                    "Turn it off if scrolling feels less smooth with it.",
                enabled = { prefs.hideToolbarOnScroll },
                checked = { prefs.toolbarFollowsScroll },
            ) { prefs.toolbarFollowsScroll = it }
        }
        def(Topic.LOOK, "Menu", "Arrange the menu", "menu buttons order arrange move hide customize more pages") {
            action(R.drawable.ic_menu, "Arrange the menu", { "Hold any button in the menu to move it, or drag it to the top to hide it" }) { arrangeMenu() }
        }
        def(Topic.LOOK, "Menu", "Reset the menu", "menu buttons default reset order") {
            action(R.drawable.ic_update, "Reset the menu", { "Every button back where it started" }) { resetMenu() }
        }
        def(Topic.LOOK, "App icons", "Documents and Files in the app list", "icon launcher home screen app list documents files apps") {
            switch(
                R.drawable.ic_folder, "Documents and Files in the app list",
                { "Their own icons next to Swift's, to open them straight away" },
                checked = { AppIcons.shown(this@SettingsActivity) },
            ) { AppIcons.show(this@SettingsActivity, it) }
        }
        def(Topic.LOOK, "Feel", "Light vibration", "haptic vibrate feedback touch") {
            switch(R.drawable.ic_vibration, "Light vibration", { "A short buzz when you hold something or drag a tab" }, checked = { prefs.haptics }) {
                prefs.haptics = it
            }
        }

        // Home & news
        def(Topic.NEWS, null, "Show news", "news headlines stories home page feed") {
            switch(R.drawable.ic_news, "Show news", { "Headlines under the shortcuts on the home page" }, checked = { prefs.newsEnabled }) {
                prefs.newsEnabled = it
            }
        }
        def(Topic.NEWS, null, "Documents shortcut", "documents files pdf word excel powerpoint home shortcut") {
            switch(R.drawable.ic_documents, "Documents shortcut", { "Your PDFs and Office files, among the home page's shortcuts" }, checked = { prefs.homeDocuments }) {
                prefs.homeDocuments = it
            }
        }
        def(Topic.NEWS, null, "Continue watching and reading", "continue unfinished videos articles reading resume home") {
            switch(
                R.drawable.ic_reopen, "Continue watching and reading",
                { "Videos and articles you left half-way, on the home page" },
                checked = { prefs.homeContinue },
            ) {
                prefs.homeContinue = it
            }
        }
        def(Topic.NEWS, "News", "Stories with big pictures", "news pictures images cards big chrome compact layout") {
            switch(
                R.drawable.ic_image, "Stories with big pictures",
                { if (prefs.newsCards) "Cards with the picture across the top, as in Chrome" else "Off: compact rows with a small picture" },
                details = "Each story becomes a card: its picture across the top, then the site and the headline. Pictures come " +
                    "from the news sites themselves: Google News' headlines often have none, sites you add usually do. " +
                    "They load only as you scroll to them, and not on mobile data with Data saver.",
                checked = { prefs.newsCards },
            ) { prefs.newsCards = it }
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
            switch(R.drawable.ic_translate, "Offer to translate pages", { "Pages in another language offer to be translated, on this phone" }, checked = { Translate.offer }) {
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
        def(Topic.BROWSING, "Tabs", "Inactive tabs", "old tabs archive unused") {
            action(R.drawable.ic_history, "Inactive tabs", { inactiveText(prefs.inactiveDays) }) { chooseInactiveDays() }
        }
        def(Topic.BROWSING, "Reader view", "Reader view text", "font serif spacing lines reading") {
            action(R.drawable.ic_font, "Reader view text", { readerText() }) { Sheets.readerStyle(this@SettingsActivity) { refreshAll() } }
        }

        // Ad blocking
        def(Topic.ADBLOCK, "This week", "Blocked this week", "stats count numbers trackers blocked week") {
            custom(AdStatsCard.build(this@SettingsActivity))
        }
        def(Topic.ADBLOCK, "How much to block", "Blocking level", "ads off balanced high extreme ublock") {
            custom(levelChooser(refresh))
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
        def(Topic.ADBLOCK, "For experts", "uBlock Origin settings", "filters lists rules ublock dashboard", advanced = true) {
            action(R.drawable.ic_ublock, "uBlock Origin settings", { "The ad blocker's own settings: block lists and your own rules" }) {
                AdBlock.extension?.metaData?.optionsPageUrl?.let { openInBrowser(it) }
            }
        }
        def(Topic.ADBLOCK, "For experts", "Update block lists now", "ublock refresh filter lists", advanced = true) {
            action(R.drawable.ic_refresh, "Update block lists now", { "They also update by themselves every few days" }) {
                AdBlock.updateFilterLists()
                snack("Updating the block lists in the background")
            }
        }

        // Speed & memory
        def(Topic.SPEED, "How sites look", "Show sites as designed", "blur glass transparency animations new phone quality") {
            switch(
                R.drawable.ic_glass, "Show sites as designed",
                { "Blur effects and animations as sites made them (for newer phones)" },
                details = "To keep scrolling smooth on slow phones, Swift changes some sites a little: no glass blur behind " +
                    "see-through bars and badges, fewer decorative animations, and lighter YouTube and TheFlixer.\n\n" +
                    "Turn this on to see every site exactly as it was designed. It's on by itself on phones with 4 GB of " +
                    "memory or more; on this phone it's best left off.\n\nTurning it on turns Lite mode off.",
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
                details = "The smoothest scrolling on this phone: all sites without animations, shadows or blur. " +
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
        def(Topic.SPEED, "Data", "Battery saver", "battery low power charge saver percent level") {
            action(R.drawable.ic_battery, "Battery saver", {
                when {
                    !prefs.batterySaver -> "Off"
                    Power.saving -> "Saving battery now, until you charge"
                    else -> "Below ${prefs.batterySaverLevel}% battery: Lite mode, one tab kept loaded"
                }
            }) { chooseBatterySaver() }
        }
        def(Topic.SPEED, "Memory", "Tabs kept loaded", "memory ram live tabs") {
            action(R.drawable.ic_memory, "Tabs kept loaded", { liveTabsText(prefs.maxLiveTabs) }) { chooseLiveTabs() }
        }
        def(Topic.SPEED, "Drawing", "Show drawing speed", "fps frames meter", advanced = true) {
            switch(R.drawable.ic_speed, "Show drawing speed", { "A meter on pages showing how smoothly they draw" }, checked = { prefs.showDrawSpeed }) {
                prefs.showDrawSpeed = it
                Engine.setDrawSpeedMeter(it)
            }
        }

        // Video & downloads
        def(Topic.VIDEO, "Playing", "Keep playing in the background", "background play music audio screen off notification switch tabs") {
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
        def(Topic.VIDEO, "Playing", "Sleep timer", "stop music video sleep night minutes") {
            action(R.drawable.ic_timer, "Sleep timer", { SleepTimer.label() ?: "Off. Also in the ≡ menu and the playback notification." }) {
                Sheets.sleepTimer(this@SettingsActivity) { refreshAll() }
            }
        }
        def(Topic.VIDEO, "Playing", "Continue watching", "resume position film movie episode") {
            switch(R.drawable.ic_resume, "Continue watching", { "Offers to continue long videos where you left off" }, checked = { prefs.continueWatching }) {
                prefs.continueWatching = it
            }
        }
        def(Topic.VIDEO, "Playing", "Double-tap to skip", "seek forward back 10 seconds hold speed 2x") {
            switch(R.drawable.ic_touch, "Double-tap to skip", { "In full screen: double-tap a side to skip 10 seconds, hold for double speed" }, checked = { prefs.videoTaps }) {
                prefs.videoTaps = it
            }
        }
        def(Topic.VIDEO, "Playing", "Brightness and volume swipes", "gestures swipe") {
            switch(R.drawable.ic_gesture, "Brightness and volume swipes", { "In full screen: swipe up or down on the left or right half" }, checked = { prefs.videoGestures }) {
                prefs.videoGestures = it
            }
        }
        def(Topic.VIDEO, "Playing", "Rotate full-screen videos", "landscape orientation") {
            switch(R.drawable.ic_rotate, "Rotate full-screen videos", { "Full-screen videos turn sideways by themselves" }, checked = { prefs.autoLandscape }) {
                prefs.autoLandscape = it
            }
        }
        def(Topic.VIDEO, "Playing", "Play silent videos by themselves", "autoplay previews muted") {
            switch(R.drawable.ic_play, "Play silent videos by themselves", { "Like previews in YouTube's feed. Off scrolls smoother." }, checked = { prefs.autoplayMuted }) {
                prefs.autoplayMuted = it
            }
        }
        def(Topic.VIDEO, "Downloads", "Save videos as MP4", "hls m3u8 ts convert streams", advanced = true) {
            switch(R.drawable.ic_movie, "Save videos as MP4", { "Downloaded videos play on any phone or computer" }, checked = { prefs.hlsToMp4 }) {
                prefs.hlsToMp4 = it
            }
        }
        def(Topic.VIDEO, "Downloads", "Ask before downloading", "confirm download") {
            switch(R.drawable.ic_download, "Ask before downloading", { "Sites can't start a download without asking you" }, checked = { prefs.confirmDownloads }) {
                prefs.confirmDownloads = it
            }
        }
        def(Topic.VIDEO, "Downloads", "Big downloads wait for Wi-Fi", "mobile data cellular wifi only") {
            switch(
                R.drawable.ic_wifi, "Big downloads wait for Wi-Fi",
                { if (prefs.wifiOnlyDownloads) "Files over 50 MB start when you're on Wi-Fi" else "Downloads start on mobile data too" },
                checked = { prefs.wifiOnlyDownloads },
            ) {
                prefs.wifiOnlyDownloads = it
                Downloads.onNetworkChanged()
            }
        }
        def(Topic.VIDEO, "Downloads", "Download folder", "storage location where save sd card") {
            action(R.drawable.ic_folder, "Download folder", { downloadFolderText() }) {
                if (Downloads.hasStoragePermission(this@SettingsActivity)) chooseDownloadFolder() else askStorageAccess()
            }
        }

        // Privacy & storage
        def(Topic.PRIVACY, null, "Clear browsing data", "history cookies cache delete last hour day week time") {
            action(R.drawable.ic_delete, "Clear browsing data", { "The last hour, day, week or all of it: history, cookies, saved files and more" }) { clearData() }
        }
        def(Topic.PRIVACY, null, "When you quit", "quit exit close clear on exit") {
            action(R.drawable.ic_quit, "When you quit", { ClearData.quitSummary() }) { ClearData.chooseForQuit(this@SettingsActivity) { refreshAll() } }
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
        def(Topic.PRIVACY, null, "Secure DNS", "dns doh dns over https cloudflare quad9 google lookup private encrypted") {
            action(R.drawable.ic_globe, "Secure DNS", { secureDnsText() }) { chooseSecureDns() }
        }
        def(Topic.PRIVACY, null, "Reject cookie banners", "cookies consent gdpr reject banner") {
            switch(
                R.drawable.ic_cookie, "Reject cookie banners",
                { "Swift says no to cookie banners on the sites it knows" },
                details = "Firefox knows the cookie banners of many big sites and answers them with \"Reject\", so they " +
                    "neither show nor get your consent. On other sites, \"Hide cookie pop-ups\" (Ad blocking) hides them.",
                checked = { prefs.rejectCookieBanners },
            ) {
                prefs.rejectCookieBanners = it
                Engine.applyPrivacyPrefs()
            }
        }
        def(Topic.PRIVACY, "Passwords", "Offer to save passwords", "password login sign in autofill fill in remember") {
            switch(
                R.drawable.ic_password, "Offer to save passwords",
                { if (prefs.offerPasswords) "Swift asks after you sign in, and fills them in next time" else "Off: passwords aren't saved or filled in" },
                details = "Passwords are kept on this phone only, locked with a key that never leaves Android's secure " +
                    "storage. They aren't in backups, and incognito tabs never save one.",
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
        def(Topic.PRIVACY, "Backup", "Back up", "export save copy backup") {
            action(R.drawable.ic_download, "Back up", { "Settings, bookmarks, history, open tabs and site settings, to one file" }) { backUp() }
        }
        def(Topic.PRIVACY, "Backup", "Back up every week", "automatic backup weekly") {
            switch(
                R.drawable.ic_history, "Back up every week",
                { if (prefs.autoBackup) autoBackupText() else "Off: only when you tap Back up" },
                checked = { prefs.autoBackup },
            ) { prefs.autoBackup = it }
        }
        def(Topic.PRIVACY, "Backup", "Restore from a backup", "import restore backup new phone") {
            action(R.drawable.ic_update, "Restore from a backup", { "Adds a backup's contents to this phone, then restarts Swift" }) {
                chooseBackup()
            }
        }

        // VPN
        def(Topic.VPN, null, "VPN", "vpn proton wireguard country ip hide location change country") {
            custom(vpnCard(refresh))
        }
        def(Topic.VPN, "Locations", "VPN locations", "location server country proton") {
            custom(vpnLocations(refresh))
        }
        def(Topic.VPN, "Locations", "Test the locations", "test speed fastest working server ping answer") {
            action(R.drawable.ic_speed, "Test the locations", { vpnTestText() }) { testVpnLocations() }
        }
        def(Topic.VPN, "Locations", "Add a location", "add proton wireguard file conf setup") {
            action(R.drawable.ic_add, "Add a location", { "A Proton VPN setup file (.conf), one per server" }) { addVpnLocation() }
        }
        def(Topic.VPN, "Locations", "How to get free locations", "proton free account download wireguard") {
            action(R.drawable.ic_info, "How to get free locations", { "Free from Proton VPN, in a few minutes" }) { vpnHowTo() }
        }
        def(Topic.VPN, "Options", "Connect when Swift starts", "vpn automatic start") {
            switch(R.drawable.ic_vpn, "Connect when Swift starts", { "If it was on when you left Swift" }, checked = { com.swiftbrowser.vpn.Vpn.connectAtStart }) {
                com.swiftbrowser.vpn.Vpn.connectAtStart = it
            }
        }
        def(Topic.VPN, "Options", "Switch to another location", "automatic failover switch server not answering backup") {
            switch(
                R.drawable.ic_swap, "Switch to another location",
                { "If a server stops answering, Swift moves to another location by itself" },
                details = "Swift picks the same country first, and the fastest one from your last test. It tells you where " +
                    "it went. Off: the VPN stops and pages wait (if paused) until you pick another location.",
                checked = { com.swiftbrowser.vpn.Vpn.autoSwitch },
            ) { com.swiftbrowser.vpn.Vpn.autoSwitch = it }
        }
        def(Topic.VPN, "Options", "Pause pages if the VPN drops", "kill switch leak block vpn drops") {
            switch(
                R.drawable.ic_lock, "Pause pages if the VPN drops",
                { "Sites never see your own connection by accident" },
                details = "While the VPN is connecting, or if it stops (another VPN app started, or Android stopped it), " +
                    "pages wait instead of loading over your own connection. They load by themselves once it is connected " +
                    "again; turning the VPN off lets them load normally.",
                checked = { com.swiftbrowser.vpn.Vpn.pauseWhenDown },
            ) { com.swiftbrowser.vpn.Vpn.pauseWhenDown = it }
        }

        // Storage
        def(Topic.STORAGE, null, "Storage used", "space size phone memory") {
            custom(storageBreakdown(refresh))
        }
        def(Topic.STORAGE, "Free up space", "Clear the cache", "cache files space") {
            action(R.drawable.ic_delete, "Clear the cache", { usage?.let { "${UrlUtils.formatBytes(it.cache)} of files sites reuse" } }) { clearCache() }
        }
        def(Topic.STORAGE, "Free up space", "Data sites stored", "site storage sites space clear") {
            action(R.drawable.ic_globe, "Data sites stored", { usage?.let { "${UrlUtils.formatBytes(it.sites)} · see and clear each site" } }) { showSiteStorage() }
        }
        def(Topic.STORAGE, "Free up space", "Downloads kept inside Swift", "downloads inside app folder android data deleted uninstall") {
            action(R.drawable.ic_download, "Downloads kept inside Swift", { keptText() }) { moveKeptDownloads() }
        }
        def(Topic.STORAGE, "Free up space", "Clear sites you don't visit", "old unused sites 30 days") {
            action(R.drawable.ic_history, "Clear sites you don't visit", { "Everything of sites not visited for 30 days" }) { clearUnusedSites() }
        }

        // About
        def(Topic.ABOUT, null, "Check for updates", "new version github update") {
            action(R.drawable.ic_update, "Check for updates", { "Your tabs and settings stay when you update" }) { checkForUpdates() }
        }
        def(Topic.ABOUT, null, "Send a problem report", "crash bug report") {
            action(R.drawable.ic_report, "Send a problem report", { "Share what Swift noted about crashes, to help fix them" }) {
                Diagnostics.shareReport(this@SettingsActivity)
            }
        }
        return list
    }

    // ------------------------------------------------------------------ building blocks

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color(R.color.card_bg))
        }
        clipToOutline = true
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(color(Looks.accent.primary))
        setPadding(dp(16), dp(20), dp(16), dp(8))
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) }

    /** The logo, the version and what Swift is made of. */
    private fun aboutHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color(R.color.card_bg))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(14)
        }
        addView(ImageView(this@SettingsActivity).apply { setImageDrawable(Ui.logo(this@SettingsActivity)) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "Swift ${BuildConfig.VERSION_NAME}"
                textSize = 20f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(color(R.color.text_primary))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Built on Firefox, with uBlock Origin ${AdBlock.uboVersion}\nProfile: ${Profiles.current().name}"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        })
    }

    private inner class Section(val card: LinearLayout, val refresh: MutableList<() -> Unit> = refreshers) {
        fun add(view: View) {
            if (card.childCount > 0) {
                card.addView(View(this@SettingsActivity).apply { setBackgroundColor(color(R.color.divider)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(62) })
            }
            card.addView(view)
        }

        /** A row that opens something; its summary shows the current value. */
        /** [icon]: a row whose icon follows a setting (the search engine's logo). */
        fun action(iconRes: Int, title: String, summary: () -> String?, badge: Boolean = false, icon: (() -> Int)? = null, onClick: () -> Unit) {
            val (row, sub) = row(iconRes, title, summary(), badge)
            if (icon != null) {
                val image = row.getChildAt(0) as ImageView
                refresh += { setRowIcon(image, icon(), badge) }
            }
            row.isClickable = true
            row.setBackgroundResource(RowHolder.selectableBackground(this@SettingsActivity))
            row.setOnClickListener { onClick() }
            row.addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            refresh += { setSummary(sub, summary()) }
            add(row)
        }

        fun info(icon: Int, title: String, summary: () -> String?) {
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
            val (row, sub) = row(icon, title, summary())
            if (details != null) {
                row.addView(ImageButton(this@SettingsActivity).apply {
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
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
            }
            val sw = SlimSwitch(this@SettingsActivity)
            sw.setChecked(checked(), animate = false)
            row.addView(sw, LinearLayout.LayoutParams(dp(46), dp(32)))
            row.isClickable = true
            row.setBackgroundResource(RowHolder.selectableBackground(this@SettingsActivity))
            row.accessibilityDelegate = SwitchRowAccessibility(sw)
            row.setOnClickListener {
                if (!enabled()) return@setOnClickListener
                val on = !sw.isChecked
                sw.setChecked(on, animate = true)
                onChange(on)
                refreshAll()
            }
            val update = {
                sw.setChecked(checked(), animate = false)
                setSummary(sub, summary())
                val e = enabled()
                row.alpha = if (e) 1f else 0.5f
            }
            update()
            refresh += update
            add(row)
        }

        fun custom(view: View) = add(view)
    }

    private fun setSummary(sub: TextView, text: String?) {
        if (sub.text.toString() != text.orEmpty()) sub.text = text.orEmpty()
        sub.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * An icon in a grey circle (as in the ≡ menu; in the accent's soft colour for the main page's
     * topics), a title and a summary of up to three lines.
     */
    private fun row(icon: Int, title: String, summary: String?, badge: Boolean = false): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(10), dp(12), dp(10))
        }
        row.addView(ImageView(this).apply {
            background = Ui.circle(if (badge) Ui.accentContainer(this@SettingsActivity) else color(R.color.surface_alt))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setRowIcon(this, icon, badge)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTextColor(color(R.color.text_primary))
        })
        val sub = TextView(this).apply {
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        setSummary(sub, summary)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }
        val segments = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(color(R.color.surface_alt))
            }
        }
        val summary = TextView(this).apply {
            textSize = 13.5f
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        val views = AdLevel.entries.map { level ->
            TextView(this).apply {
                text = level.label
                textSize = 13.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setOnClickListener {
                    AdBlock.setGlobal(level)
                    refreshAll()
                }
                segments.addView(this, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
        }
        val update = {
            val current = AdBlock.global
            AdLevel.entries.forEachIndexed { i, level ->
                val on = level == current
                views[i].background = if (on) GradientDrawable().apply {
                    cornerRadius = dp(19).toFloat()
                    setColor(color(Looks.accent.container))
                } else null
                views[i].setTextColor(color(if (on) Looks.accent.onContainer else R.color.text_primary))
                views[i].isSelected = on
                views[i].contentDescription = "${level.label}${if (on) ", selected" else ""}"
            }
            summary.text = current.summary
        }
        update()
        target += update
        box.addView(segments, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Battery saver")
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.batterySaver = which > 0
                if (which > 0) prefs.batterySaverLevel = levels[which - 1]
                Power.update()
                d.dismiss()
                refreshAll()
                if (which > 0) snack("Lite mode, one tab kept loaded and no pictures on mobile data below ${levels[which - 1]}%, or with Android's battery saver")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun chooseTextSize() {
        val options = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
        val labels = options.map { textSizeText(it) }.toTypedArray()
        val checked = options.indexOfFirst { kotlin.math.abs(it - prefs.textScale) < 0.01f }
        MaterialAlertDialogBuilder(this)
            .setTitle("Text size")
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.textScale = options[which]
                Engine.setTextScale(options[which])
                d.dismiss()
                refreshAll()
                snack("Sites without a size of their own use ${textSizeText(options[which])}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
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

    private fun keptText(): String {
        val u = usage ?: return "Calculating…"
        // Only files in Swift's own folders count here; the downloads in the chosen folder are not "kept inside" (2.5).
        if (u.kept <= 0) return "None · your downloads are in ${Downloads.describe(Downloads.directory())}"
        return UrlUtils.formatBytes(u.kept) + " · deleted if Swift is uninstalled. Tap to move them out"
    }

    /** Moves what is in Swift's own folders to the download folder, or says there is nothing there. */
    private fun moveKeptDownloads() {
        val u = usage ?: return
        val dir = Downloads.directory()
        when {
            u.kept <= 0 -> snack("Nothing is kept inside Swift. Your downloads are in ${Downloads.describe(dir)}")
            !Downloads.hasStoragePermission(this) -> askStorageAccess()
            dir.path.contains("/Android/data/") -> snack("Your download folder is Swift's own folder. Choose another one first")
            else -> Dialogs.confirm(
                this, "Move them out of Swift?",
                "${UrlUtils.formatBytes(u.kept)} of downloads are in Swift's own folder and would be deleted if Swift is uninstalled. Move them to ${Downloads.describe(dir)}?",
                "Move",
            ) {
                snack("Moving…")
                Downloads.moveKeptTo(dir) { moved, failed ->
                    snack(if (failed > 0) "Moved $moved, $failed couldn't be moved" else "Moved $moved to ${Downloads.describe(dir)}")
                    computeStorage()
                }
            }
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Address bar swipes")
            .setSingleChoiceItems(barSwipeLabels().toTypedArray(), modes.indexOf(prefs.barSwipes).coerceAtLeast(0)) { d, which ->
                prefs.barSwipes = modes[which]
                d.dismiss()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
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
        MaterialAlertDialogBuilder(this)
            .setTitle("News from")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                prefs.newsCountry = if (which == 0) "" else codes[which - 1]
                com.swiftbrowser.news.News.clearMemory()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
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
                addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(16) })
                com.swiftbrowser.core.Favicons.load(feed) { bmp ->
                    if (bmp != null) {
                        badge.setImageBitmap(bmp)
                        badge.scaleType = ImageView.ScaleType.FIT_CENTER
                        val p = dp(8)
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
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = Ui.iconBadge(this, R.drawable.ic_vpn, 44)
        head.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val title = Ui.text(this, Ui.Type.TITLE, "")
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)
        val status = Ui.text(this, Ui.Type.BODY_SECONDARY, "").apply { setPadding(0, dp(10), 0, dp(12)) }
        box.addView(status)
        val button = com.google.android.material.button.MaterialButton(this)
        box.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        // After the VPN dropped: Reconnect above, and Turn off here.
        val off = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Turn off"
            setOnClickListener {
                com.swiftbrowser.vpn.Vpn.turnOff()
                snack("VPN off")
            }
        }
        box.addView(off, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
        val update = {
            val vpn = com.swiftbrowser.vpn.Vpn
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
            if (dropped && off.visibility != View.VISIBLE) Motion.fadeIn(off, fromDy = 6)
            if (!dropped && off.visibility == View.VISIBLE) Motion.fadeOut(off, toDy = 0)
            Motion.swapText(button, when {
                empty -> "How to get free locations"
                vpn.testing -> "Stop testing"
                dropped -> "Reconnect"
                vpn.wanted -> "Turn off"
                else -> "Turn on"
            })
            button.setOnClickListener {
                when {
                    empty -> vpnHowTo()
                    // Stopping a test: back to how it was (on, or off).
                    vpn.testing -> if (vpn.wanted) VpnUi.turnOn(this, vpnPermission) else vpn.turnOff()
                    dropped -> VpnUi.turnOn(this, vpnPermission)
                    vpn.wanted -> {
                        vpn.turnOff()
                        snack("VPN off")
                    }
                    else -> VpnUi.turnOn(this, vpnPermission)
                }
            }
        }
        update()
        target += update
        return box
    }

    /** The locations: tap one to use it (it connects at once when the VPN is on), ✕ removes it. */
    private fun vpnLocations(target: MutableList<() -> Unit>): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val update = {
            box.removeAllViews()
            val vpn = com.swiftbrowser.vpn.Vpn
            // By country (2.2: there can be several servers in one).
            val list = vpn.locations().sortedWith(compareBy({ it.countryName }, { it.server }))
            // With none yet, the card above says so.
            val chosen = vpn.current()?.id
            for (loc in list) {
                if (box.childCount > 0) box.addView(Ui.divider(this, 62))
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(14), dp(8), dp(4), dp(8))
                    background = Ui.ripple(this@SettingsActivity, null, 0)
                    contentDescription = loc.countryName + if (loc.id == chosen) ", chosen" else ""
                    addView(TextView(this@SettingsActivity).apply {
                        text = loc.flag
                        textSize = 24f
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
                    addView(LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(this@SettingsActivity, Ui.Type.BODY, loc.countryName))
                        addView(Ui.text(this@SettingsActivity, Ui.Type.CAPTION, loc.server + if (loc.id == chosen) " · in use" else ""))
                        // 2.2: how it did in the last test, and when it needs extending at Proton.
                        val result = VpnUi.testResult(loc)
                        val soon = loc.renewBy - System.currentTimeMillis() < 14L * 24 * 3600 * 1000
                        addView(Ui.text(
                            this@SettingsActivity, Ui.Type.CAPTION,
                            listOfNotNull(result, VpnUi.renewText(loc)).joinToString(" · "),
                            if (soon || result == "No answer") Ui.danger(this@SettingsActivity) else null,
                        ))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (loc.id == chosen) {
                        addView(ImageView(this@SettingsActivity).apply {
                            setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_check, Ui.accent(this@SettingsActivity)))
                        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(4) })
                    }
                    addView(ImageButton(this@SettingsActivity).apply {
                        setImageDrawable(Ui.icon(this@SettingsActivity, R.drawable.ic_close, color(R.color.text_secondary)))
                        background = Ui.rippleBorderless(this@SettingsActivity)
                        contentDescription = "Remove ${loc.countryName}"
                        setOnClickListener { removeVpnLocation(loc) }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    setOnClickListener {
                        vpn.choose(loc)
                        if (vpn.wanted) VpnUi.turnOn(this@SettingsActivity, vpnPermission, loc)
                        refreshAll()
                    }
                    setOnLongClickListener {
                        vpnLocationSheet(loc)
                        true
                    }
                })
            }
        }
        update()
        target += update
        return box
    }

    /** Held: connect, mark it extended at Proton (another year), or remove it. */
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
                Sheets.Action(R.drawable.ic_update, "I extended it", primary = true) {
                    vpn.markExtended(loc)
                    refreshAll()
                    snack("${loc.countryName}: ${VpnUi.renewText(vpn.locations().firstOrNull { it.id == loc.id } ?: loc).replaceFirstChar { it.lowercase() }}")
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

    private fun removeVpnLocation(loc: com.swiftbrowser.vpn.Vpn.Location) {
        val vpn = com.swiftbrowser.vpn.Vpn
        val sealed = vpn.sealedFile(this, loc)
        vpn.remove(this, loc)
        refreshAll()
        Messages.show(this, "Removed ${loc.countryName}", if (sealed != null) "Undo" else null) {
            if (sealed != null) vpn.restore(this, loc, sealed)
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
                        snack("No app on this phone can pick files")
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
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    (bytes?.takeIf { it.size < 64 * 1024 }?.let { String(it) }) to name
                } catch (_: Exception) {
                    null to null
                }
            }
            if (text == null) {
                snack("Couldn't read that file")
                return@launch
            }
            addVpnText(text, name)
        }
    }

    private fun addVpnText(text: String, name: String?) {
        try {
            val loc = com.swiftbrowser.vpn.Vpn.add(this, text, name)
            refreshAll()
            snack("Added ${loc.flag} ${loc.countryName}")
        } catch (e: IllegalArgumentException) {
            Messages.show(this, e.message ?: "That isn't a WireGuard setup file", long = true)
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
            textSize = 13f
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
        "off" -> "Off: your network can see which sites you look up"
        "quad9" -> "Quad9: site lookups are encrypted, known bad sites blocked"
        "google" -> "Google: site lookups are encrypted"
        else -> "Cloudflare: site lookups are encrypted"
    }

    /** DNS over HTTPS (2.2): who looks up sites' addresses, encrypted; your network's own when off. */
    private fun chooseSecureDns() {
        val keys = listOf("cloudflare", "quad9", "google", "off")
        val labels = arrayOf("Cloudflare (recommended)", "Quad9 (also blocks known bad sites)", "Google", "Off (your network's own)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Secure DNS")
            .setSingleChoiceItems(labels, keys.indexOf(prefs.secureDns).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                prefs.secureDns = keys[which]
                Engine.applySecureDns()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
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
            val s = Backup.summary(root)
            val d = MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle("Restore this backup?")
                .setMessage(
                    "Made on ${s.created}: ${s.profiles} ${if (s.profiles == 1) "profile" else "profiles"}, ${s.bookmarks} ${if (s.bookmarks == 1) "bookmark" else "bookmarks"}, " +
                        "${s.tabs} ${if (s.tabs == 1) "tab" else "tabs"} and ${s.history} ${if (s.history == 1) "history entry" else "history entries"}.\n\nThey are added to what is on this phone, its settings " +
                        "replace these, and Swift restarts.",
                )
                .setPositiveButton("Restore") { _, _ ->
                    Backup.restoreThen(root) { SwiftApp.restart(this@SettingsActivity) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
            d.setCanceledOnTouchOutside(false)
        }
    }

    private fun inactiveText(days: Int) = if (days <= 0) "Never" else "After $days days without opening them"

    private fun chooseInactiveDays() {
        val options = intArrayOf(7, 14, 21, 30, 0)
        val labels = options.map { if (it == 0) "Never" else "After $it days" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Move tabs to \"Inactive tabs\"")
            .setSingleChoiceItems(labels, options.indexOf(prefs.inactiveDays).coerceAtLeast(0)) { d, which ->
                prefs.inactiveDays = options[which]
                d.dismiss()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun checkForUpdates() {
        snack("Checking for updates…")
        Updates.latest { r ->
            when {
                r == null -> snack("Couldn't check for updates. Check your internet connection.")
                !Updates.isNewer(r.version, BuildConfig.VERSION_NAME) -> snack("Swift ${BuildConfig.VERSION_NAME} is the newest version")
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

    private fun liveTabsText(n: Int) = when (n) {
        1 -> "1 tab: least memory; others reload when opened"
        2 -> "2 tabs (best for a 2 GB phone)"
        in 3..4 -> "$n tabs: faster switching, more memory"
        else -> "$n tabs: a lot of memory"
    }

    private fun chooseLiveTabs() {
        val options = (1..Prefs.MAX_LIVE_TABS_LIMIT).toList()
        val labels = options.map { liveTabsText(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tabs kept loaded")
            .setSingleChoiceItems(labels, options.indexOf(prefs.maxLiveTabs)) { d, which ->
                prefs.maxLiveTabs = options[which]
                d.dismiss()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
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
        val text = days.joinToString("\n") { d ->
            val name = if (d.day == today) "Today" else try { show.format(parse.parse(d.day)!!) } catch (e: Exception) { d.day }
            "$name: ${UrlUtils.formatBytes(d.mobile)} mobile · ${UrlUtils.formatBytes(d.other)} Wi-Fi"
        }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Data used")
            .setMessage(text + "\n\nCounted by Swift while it runs, so your carrier's count may differ a little.")
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
            val n = withContext(Dispatchers.IO) { try { SwiftApp.db.logins(Profiles.currentId).size } catch (e: Exception) { 0 } }
            val text = when (n) {
                0 -> "None yet"
                1 -> "1 sign-in"
                else -> "$n sign-ins"
            }
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
                .setMessage("Anyone holding this phone could see your passwords. A screen lock (PIN, pattern or password) keeps them for you only.")
                .setPositiveButton("Show anyway") { _, _ -> then() }
                .setNegativeButton("Set a screen lock") { _, _ ->
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e: Exception) {
                        snack("Open Android's Settings → Security")
                    }
                }
                .showImmersive()
            return
        }
        afterUnlock = then
        try {
            unlock.launch(intent)
        } catch (e: Exception) {
            afterUnlock = null
            then()
        }
    }

    private fun clearCache() {
        Dialogs.confirm(
            this, "Free up space?",
            "Saved pictures and files (${UrlUtils.formatBytes(usage?.cache ?: 0)}) are deleted. Pages load them again when needed; logins and settings stay.",
            "Clear cache", danger = true,
        ) {
            Engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
            snack("Cache cleared")
            remeasureSoon()
        }
    }

    private fun computeStorage() {
        lifecycleScope.launch {
            usage = withContext(Dispatchers.IO) { com.swiftbrowser.core.Storage.measure(this@SettingsActivity) }
            refreshAll()
        }
    }

    /** Measures again a moment after the engine was asked to delete something. */
    private fun remeasureSoon() {
        lifecycleScope.launch {
            delay(1500)
            computeStorage()
        }
    }

    /** A bar of what Swift keeps on the phone, with each part and its size below it. */
    private fun storageBreakdown(refresh: MutableList<() -> Unit>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val total = TextView(this).apply {
            textSize = 22f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(color(R.color.text_primary))
        }
        box.addView(total)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(color(R.color.surface_alt))
            }
            clipToOutline = true
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
            topMargin = dp(10)
            bottomMargin = dp(6)
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(rows)
        val note = TextView(this).apply {
            textSize = 12.5f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(note)
        val colors = intArrayOf(
            R.color.group_blue, R.color.group_red, R.color.group_green, R.color.group_yellow,
            R.color.group_purple, R.color.group_cyan, R.color.group_pink, R.color.group_grey,
        )
        val update = {
            val u = usage
            total.text = if (u == null) "Calculating…" else "Swift uses ${UrlUtils.formatBytes(u.total)}"
            // Android's App info lists the cache on its own, outside its Total.
            note.text = (if (u == null) "" else "Android's App info shows ${UrlUtils.formatBytes(u.androidTotal)} for Swift, plus the cache on its own line. ") +
                "The app is mostly the Firefox engine and can't get smaller; the ad-block lists stay so blocking is fast. Old update files are deleted by themselves."
            bar.removeAllViews()
            rows.removeAllViews()
            if (u != null) {
                val parts = listOf(
                    "The app" to u.app, "Ad-block lists" to u.adBlock, "Data sites stored" to u.sites,
                    "Cache" to u.cache, "Engine data" to u.engine, "Tabs, history and pictures" to u.swift,
                    "Downloads inside Swift" to u.kept, "Other" to u.other,
                )
                parts.forEachIndexed { i, (label, bytes) ->
                    val c = color(colors[i])
                    if (bytes > 0 && u.total > 0) {
                        bar.addView(View(this).apply { setBackgroundColor(c) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, bytes.toFloat() / u.total))
                    }
                    rows.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, dp(5))
                        addView(View(this@SettingsActivity).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(c)
                            }
                        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
                        addView(TextView(this@SettingsActivity).apply {
                            text = label
                            textSize = 14.5f
                            setTextColor(color(R.color.text_primary))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@SettingsActivity).apply {
                            text = UrlUtils.formatBytes(bytes)
                            textSize = 14.5f
                            setTextColor(color(R.color.text_secondary))
                        })
                    })
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
                .setTitle("Data sites stored")
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

        /** Everyday words for what settings are about, for the search box. */
        private val SYNONYMS = mapOf(
            "battery" to listOf("battery saver", "lite", "data saver", "draw pages", "tabs kept", "sleep", "dark"),
            "music" to listOf("playing", "background", "sleep", "audio"),
            "song" to listOf("playing", "background", "sleep"),
            "sound" to listOf("audio", "volume", "sound", "autoplay", "playing"),
            "volume" to listOf("volume", "swipes"),
            "ads" to listOf("ad", "block", "ublock", "cookie", "pop-up"),
            "advert" to listOf("ad", "block", "ublock"),
            "popup" to listOf("pop-up", "cookie", "block"),
            "slow" to listOf("lite", "speed", "draw pages", "tabs kept", "designed"),
            "fast" to listOf("lite", "speed", "draw pages", "tabs kept"),
            "smooth" to listOf("lite", "draw pages", "toolbar", "designed"),
            "internet" to listOf("data", "wi-fi", "secure", "downloads"),
            "wifi" to listOf("wi-fi", "data"),
            "mobile" to listOf("data", "wi-fi"),
            "space" to listOf("storage", "cache", "download folder"),
            "memory" to listOf("tabs kept", "storage", "memory"),
            "font" to listOf("text size", "reader"),
            "big" to listOf("text size", "zoom"),
            "night" to listOf("dark"),
            "black" to listOf("dark", "colours"),
            "colour" to listOf("colours"),
            "color" to listOf("colours"),
            "background" to listOf("colours"),
            "theme" to listOf("theme", "colours"),
            "privacy" to listOf("clear", "secure", "cookie", "incognito", "site settings"),
            "password" to listOf("site settings", "clear"),
            "location" to listOf("site settings", "vpn"),
            "desktop" to listOf("site settings", "desktop"),
            "youtube" to listOf("playing", "background", "designed", "autoplay", "video"),
            "film" to listOf("continue", "video", "download"),
            "movie" to listOf("continue", "video", "download"),
            "save" to listOf("download", "backup", "data saver"),
            "phone" to listOf("backup", "restore", "storage"),
            "vibrat" to listOf("vibration"),
            "home" to listOf("address bar buttons", "address bar"),
            "button" to listOf("address bar buttons", "arrange the menu"),
            "toolbar" to listOf("address bar buttons", "address bar"),
            "menu" to listOf("arrange the menu", "reset the menu"),
            "dns" to listOf("secure dns"),
            "test" to listOf("test the locations"),
            "expir" to listOf("vpn locations"),
            "search" to listOf("search engine", "suggestions"),
            "google" to listOf("search engine", "suggestions"),
            "update" to listOf("updates", "filter lists"),
            "country" to listOf("vpn", "country"),
            "ip" to listOf("vpn"),
            "proton" to listOf("vpn"),
        )

        private const val STATE_TOPIC = "topic"
        private const val STATE_SCROLL = "scroll"
        private const val REQUEST_STORAGE = 41
    }
}
