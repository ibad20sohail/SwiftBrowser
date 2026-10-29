package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The menu's buttons and how they are arranged: pages of 2 rows of 4 (2.3), in the order the user
 * put them (hold a button and drag it), and the ones they hid.
 */
object MenuButtons {
    class Button(val key: String, val icon: Int, val label: String)

    /** Every button, in the order a new menu has them. */
    val all = listOf(
        Button("new_tab", R.drawable.ic_add, "New tab"),
        Button("incognito", R.drawable.ic_incognito, "Incognito"),
        Button("bookmarks", R.drawable.ic_bookmarks, "Bookmarks"),
        Button("history", R.drawable.ic_history, "History"),
        Button("downloads", R.drawable.ic_download, "Downloads"),
        Button("documents", R.drawable.ic_documents, "Documents"),
        Button("reader", R.drawable.ic_reader, "Reader view"),
        Button("save", R.drawable.ic_offline, "Save offline"),
        Button("lite", R.drawable.ic_lite, "Lite mode"),
        Button("dark", R.drawable.ic_dark, "Dark mode"),
        Button("desktop", R.drawable.ic_desktop, "Desktop"),
        Button("fullscreen", R.drawable.ic_fullscreen, "Full screen"),
        Button("recent", R.drawable.ic_reopen, "Closed tabs"),
        Button("vpn", R.drawable.ic_vpn, "VPN"),
        Button("scan", R.drawable.ic_qr, "Scan QR"),
        Button("home_screen", R.drawable.ic_home_add, "Home screen"),
        Button("ad_blocker", R.drawable.ic_shield, "Ad blocking"),
        Button("speed", R.drawable.ic_speed, "Play speed"),
        Button("sleep", R.drawable.ic_timer, "Sleep timer"),
        Button("group", R.drawable.ic_layers, "Add to group"),
        Button("hide", R.drawable.ic_block, "Tap to hide"),
        Button("close_all", R.drawable.ic_close, "Close all tabs"),
        Button("text_size", R.drawable.ic_text_size, "Text size"),
        // 2.6
        Button("translate", R.drawable.ic_translate, "Translate"),
        Button("page_qr", R.drawable.ic_qr_code, "QR code"),
        Button("clear_data", R.drawable.ic_delete, "Clear data"),
        Button("quit", R.drawable.ic_quit, "Quit"),
    )

    /** The switches (they were tiles above the buttons until 2.2): tapping one leaves the menu open. */
    val switches = setOf("lite", "dark", "desktop", "fullscreen")

    const val COLUMNS = 4
    const val ROWS = 2
    const val PER_PAGE = COLUMNS * ROWS

    fun button(key: String) = all.first { it.key == key }

    /** The pages (each up to [PER_PAGE] keys) and the hidden buttons. */
    class Layout(val pages: MutableList<MutableList<String>>, val hidden: MutableList<String>) {
        fun pageOf(key: String) = pages.indexOfFirst { key in it }

        /** No empty pages, none too full (the extra ones move on to the next page), at least one page. */
        fun tidy() {
            var i = 0
            while (i < pages.size) {
                val page = pages[i]
                if (page.size > PER_PAGE) {
                    val extra = page.subList(PER_PAGE, page.size).toList()
                    page.subList(PER_PAGE, page.size).clear()
                    if (i + 1 < pages.size) pages[i + 1].addAll(0, extra) else pages.add(extra.toMutableList())
                }
                i++
            }
            pages.removeAll { it.isEmpty() }
            if (pages.isEmpty()) pages.add(mutableListOf())
        }

        fun encode(): String = pages.filter { it.isNotEmpty() }.joinToString("|") { it.joinToString(",") }
    }

    /** A new menu's layout: every button, in order, page after page. */
    fun defaultLayout(): Layout = Layout(all.map { it.key }.chunked(PER_PAGE).map { it.toMutableList() }.toMutableList(), mutableListOf())

    /** The menu as arranged; from before 2.2, the 7 chosen buttons first, then the switches, then the rest. */
    fun layout(): Layout {
        val prefs = SwiftApp.prefs
        val keys = all.map { it.key }
        val hidden = prefs.menuHidden.filter { it in keys }.distinct().toMutableList()
        val saved = prefs.menuLayout
        val seen = HashSet<String>(hidden)
        val pages = if (saved != null) {
            saved.split('|').map { page -> page.split(',').filter { it in keys && seen.add(it) }.toMutableList() }.toMutableList()
        } else {
            val chosen = keys.filter { it in prefs.menuButtons }.take(7)
            (chosen + listOf("lite", "dark", "desktop", "fullscreen") + keys).distinct()
                .onEach { seen.add(it) }.chunked(PER_PAGE).map { it.toMutableList() }.toMutableList()
        }
        // Buttons added in a later version go at the end.
        val missing = keys.filter { it !in seen }
        if (missing.isNotEmpty()) {
            if (pages.isEmpty()) pages.add(mutableListOf())
            pages.last().addAll(missing)
        }
        return Layout(pages, hidden).also { it.tidy() }
    }

    fun save(layout: Layout) {
        SwiftApp.prefs.menuLayout = layout.encode()
        SwiftApp.prefs.menuHidden = layout.hidden
    }
}

/**
 * The ≡ menu, sliding up from the bottom:
 *  - the page it is about (with its site settings, copy and Settings buttons), and the most used
 *    page actions as a row of icons,
 *  - the buttons, in pages of 2 rows of 4 swiped sideways (or picked with the dots). The switches
 *    among them (Lite mode, dark mode, desktop site, full screen) leave the menu open.
 * Holding a button a moment lifts it (2.3): drag it to another place, to the menu's edge for the
 * next page, or onto the "hide" strip that takes the header's place; it stays where it's let go.
 * Hidden buttons wait behind the "Hidden" tile on the last page. Swiping the menu down (or tapping
 * outside it) closes it.
 * It is built once and lives in the browser screen itself (a dialog would make a new window every
 * time, which takes a moment on this phone); opening it only updates what depends on the page.
 */
class QuickMenu(private val a: MainActivity, private val root: FrameLayout) {

    var isOpen = false
        private set

    private val scrim = View(a)
    private val panel = Panel()

    /** Everything under the handle; it scrolls when taller than the screen (a turned phone). */
    private val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = android.widget.ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content)
    }
    private val pager = Pager()
    private val dots = LinearLayout(a)

    private val headerIcon = ImageView(a)

    /** Opens the site's own settings (desktop version, sound, location, ad blocking, its data). */
    private val siteButton = ImageButton(a).apply {
        setImageResource(R.drawable.ic_tune)
        imageTintList = ColorStateList.valueOf(Palette.color(a, R.color.text_secondary))
        contentDescription = "Site settings"
        setBackgroundResource(borderless())
        setOnClickListener {
            val tab = TabManager.current ?: return@setOnClickListener
            val site = UrlUtils.site(tab.url) ?: return@setOnClickListener
            close()
            Sheets.siteSettings(a, site, tab)
        }
    }
    private val copyButton = ImageButton(a).apply {
        setImageResource(R.drawable.ic_copy)
        imageTintList = ColorStateList.valueOf(Palette.color(a, R.color.text_secondary))
        setBackgroundResource(borderless())
        contentDescription = "Copy address"
        setOnClickListener {
            val tab = TabManager.current ?: return@setOnClickListener
            if (UrlUtils.isHttp(tab.url)) {
                close()
                a.copy(tab.url)
            }
        }
    }
    private val headerTitle = TextView(a)
    private val headerSite = TextView(a)
    private val headerRow: View
    private val actionsRow: View

    private val forward: IconAction
    private val reload: IconAction
    private val bookmark: IconAction
    private val share: IconAction
    private val find: IconAction

    /** Every button by MenuButtons key, made once; moving one moves its view between the pages. */
    private val items = LinkedHashMap<String, Item>()
    private val grids = ArrayList<PageGrid>()
    private var layout = MenuButtons.layout()

    // Moving buttons (2.3): hold one, drag it, let go.
    private val ghost = DragGhost(root)
    private var dragKey: String? = null
    private var lastX = 0f
    private var lastY = 0f
    private var edge = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Where the held button was, for Undo after hiding it. */
    private var dragFrom: Pair<Int, Int>? = null

    /** Takes the header's place while a button is held: dropping it here hides it. */
    private val hideStrip = TextView(a)

    /** "Hold a button to move it": the first time, and when opened from Settings. */
    private val hint = TextView(a)

    /** The "Hidden (3)" tile on the last page, when buttons are hidden. */
    private val hiddenTile: TextView

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(color(R.color.sheet_bg))
            val r = dp(24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.elevation = dp(16).toFloat()
        panel.setPadding(0, dp(8), 0, dp(12))
        // Touches between the buttons stay in the menu instead of reaching the page.
        panel.isClickable = true
        panel.visibility = View.GONE

        // Grab handle: the menu can be swiped down.
        panel.addView(View(a).apply {
            background = GradientDrawable().apply {
                setColor(color(R.color.outline))
                cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        headerRow = header()
        // The hide strip lies over the header and the page actions while a button is held.
        val top = FrameLayout(a)
        val topColumn = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        topColumn.addView(headerRow)
        top.addView(topColumn)
        top.addView(hideStrip(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(14), dp(2), dp(14), dp(6))
        })
        content.addView(top)

        val actions = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
        forward = iconAction(actions, R.drawable.ic_forward, "Forward") { TabManager.goForward(it) }
        reload = iconAction(actions, R.drawable.ic_refresh, "Reload") { if (it.loading) it.session?.stop() else TabManager.reload(it) }
        bookmark = iconAction(actions, R.drawable.ic_star_border, "Bookmark") { a.toggleBookmark(it) }
        share = iconAction(actions, R.drawable.ic_share, "Share") { a.share(it.url) }
        find = iconAction(actions, R.drawable.ic_find, "Find in page") { a.openFind() }
        actionsRow = actions
        topColumn.addView(actions)
        content.addView(hint.apply {
            text = "Hold a button to move it. Drag it to the top to hide it."
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(color(Looks.accent.onContainer))
            background = Ui.shape(a, color(Looks.accent.container), 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(18), 0, dp(18), dp(8))
        })
        content.addView(View(a).apply { setBackgroundColor(color(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(18), 0, dp(18), dp(4))
            })

        // Pages of buttons, swiped sideways.
        for (b in MenuButtons.all) items[b.key] = item(b.key, b.icon, b.label, closes = b.key !in MenuButtons.switches)
        hiddenTile = TextView(a).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(6))
            compoundDrawablePadding = dp(8)
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_secondary))
            setBackgroundResource(borderless())
            minHeight = dp(88)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, roundIcon(R.drawable.ic_hide, false), null, null)
            Ui.pressable(this, 0.92f)
            setOnClickListener { hiddenSheet() }
        }
        content.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        content.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
        render(animate = false)
        pager.reset()

        scrim.setBackgroundColor(color(R.color.scrim))
        scrim.elevation = panel.elevation
        scrim.outlineProvider = null
        scrim.visibility = View.GONE
        scrim.setOnClickListener { close() }

        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(24)
        })
        // Turned while open: the menu takes the new screen's width (a phone's width when sideways).
        root.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL && isOpen) root.post {
                fitWidth()
                pager.goTo(pager.page)
            }
        }
    }

    /** What each button does. */
    private fun run(key: String, tab: Tab) {
        val prefs = SwiftApp.prefs
        when (key) {
            "new_tab" -> TabManager.newTab(incognito = false)
            "incognito" -> TabManager.newTab(incognito = true)
            "bookmarks" -> a.openList(BookmarksActivity::class.java)
            "history" -> a.openList(HistoryActivity::class.java)
            "downloads" -> a.openDownloads()
            "reader" -> PageActions.reader(a, tab)
            "save" -> PageActions.savePage(a, tab)
            "lite" -> PageActions.setLite(a, !prefs.liteMode)
            // The screen restarts in the other theme; MainActivity opens the menu again.
            "dark" -> Sheets.setDarkMode(!prefs.darkMode)
            "desktop" -> TabManager.setDesktop(tab, !tab.desktop)
            "fullscreen" -> {
                prefs.fullScreen = !prefs.fullScreen
                a.applySystemBars()
            }
            "recent" -> Sheets.recentlyClosed(a)
            "vpn" -> a.showVpnSheet()
            "scan" -> a.scanQr()
            "documents" -> a.openDocuments()
            "text_size" -> Sheets.textSize(a, tab)
            "home_screen" -> PageActions.addToHomeScreen(a, tab)
            "ad_blocker" -> Sheets.shield(a)
            "speed" -> Sheets.speed(a, tab)
            "sleep" -> Sheets.sleepTimer(a)
            "group" -> Sheets.chooseGroup(a, tab)
            "hide" -> if (AdBlock.pickElement(tab)) {
                a.snack("Tap the ad or banner you want gone, then Create. It stays hidden on this site.", long = true)
            } else {
                a.snack("The ad blocker is still starting on this page. Try again in a moment.")
            }
            "close_all" -> confirmCloseAll(tab)
            "translate" -> Translate.sheet(a, tab)
            "page_qr" -> PageActions.showQr(a, tab)
            "clear_data" -> ClearData.show(a)
            "quit" -> ClearData.quit(a)
        }
        if (key in MenuButtons.switches && isOpen) update(tab)
    }

    /**
     * Puts the buttons on their pages as [layout] says, the "Hidden" tile after the last one (when
     * any are hidden), and, while a button is held, an empty page at the end to start a new one.
     * [animate]: buttons that only moved on their page slide there.
     */
    private fun render(animate: Boolean) {
        val pages = layout.pages.map { page -> page.map { items.getValue(it).view as View }.toMutableList() }.toMutableList()
        if (layout.hidden.isNotEmpty()) {
            hiddenTile.text = "Hidden (${layout.hidden.size})"
            hiddenTile.contentDescription = "${layout.hidden.size} hidden buttons"
            if (pages.last().size < MenuButtons.PER_PAGE) pages.last().add(hiddenTile) else pages.add(mutableListOf(hiddenTile))
        }
        if (dragKey != null) pages.add(mutableListOf())
        val count = pages.size
        while (grids.size < count) PageGrid().also {
            grids += it
            pager.addPage(it)
        }
        while (grids.size > count) grids.removeAt(grids.lastIndex).also {
            it.removeAllViews()
            pager.removePage(it)
        }
        grids.forEachIndexed { i, grid -> grid.show(pages[i], animate) }
        if (dots.childCount != count) {
            dots.removeAllViews()
            repeat(count) { i ->
                dots.addView(View(a).apply {
                    contentDescription = "Page ${i + 1}"
                    setOnClickListener { pager.goTo(i) }
                }, LinearLayout.LayoutParams(dp(18), dp(20)))
            }
        }
        if (pager.page >= count) pager.goTo(count - 1) else showPage(pager.page)
    }

    /** [animate] false: shown at once, as when the screen restarts in the other theme. [arrange]: with the hint (from Settings). */
    fun open(animate: Boolean = true, arrange: Boolean = false) {
        val tab = TabManager.current ?: return
        if (isOpen) return
        isOpen = true
        // Arranged in another window of the app, or reset in Settings.
        val saved = MenuButtons.layout()
        if (saved.encode() != layout.encode() || saved.hidden != layout.hidden) {
            layout = saved
            render(animate = false)
        }
        fitWidth()
        scroller.scrollTo(0, 0)
        update(tab)
        pager.reset()
        // How to move buttons: when asked (Settings), and the first time after 2.3.
        val prefs = SwiftApp.prefs
        val showHint = arrange || !prefs.sp.getBoolean(HINT_SEEN, false)
        hint.visibility = if (showHint) View.VISIBLE else View.GONE
        if (showHint) prefs.sp.edit().putBoolean(HINT_SEEN, true).apply()
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        if (!animate) {
            scrim.alpha = 1f
            panel.translationY = 0f
            return
        }
        scrim.alpha = 0f
        panel.translationY = panelHeight()
        scrim.animate().alpha(1f).setDuration(180).start()
        panel.animate().translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
    }

    fun close() {
        if (!isOpen) return
        cancelDrag()
        isOpen = false
        scrim.animate().cancel()
        panel.animate().cancel()
        val h = panelHeight()
        val remaining = ((h - panel.translationY) / h).coerceIn(0.2f, 1f)
        scrim.animate().alpha(0f).setDuration((140 * remaining).toLong()).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationY(h).setDuration((140 * remaining).toLong()).setInterpolator(AccelerateInterpolator())
            .withLayer().withEndAction { panel.visibility = View.GONE }.start()
    }

    /** Back: lets go of a held button first, then closes. */
    fun onBack() {
        if (dragKey != null) cancelDrag() else close()
    }

    /** Refreshes what depends on the page; also after a switch changes something. */
    private fun update(tab: Tab) {
        val http = UrlUtils.isHttp(tab.url)
        val page = !tab.isHome && tab.session != null
        val inReader = Helper.isReaderUrl(tab.url)

        siteButton.visibility = if (UrlUtils.isHttp(tab.url) && UrlUtils.site(tab.url) != null) View.VISIBLE else View.GONE
        copyButton.visibility = if (http) View.VISIBLE else View.GONE
        headerTitle.text = if (tab.isHome) (if (tab.incognito) "Incognito tab" else "New tab") else tab.displayTitle
        headerSite.text = when {
            inReader -> "Reader view"
            com.swiftbrowser.docs.Docs.isViewerUrl(tab.url) -> "Document"
            http -> UrlUtils.host(tab.url)?.removePrefix("www.").orEmpty()
            tab.isHome -> "Swift"
            else -> UrlUtils.display(tab.url)
        }
        if (tab.isHome && !tab.incognito) {
            headerIcon.setImageDrawable(Ui.logo(a))
            headerIcon.imageTintList = null
        } else {
            headerIcon.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_globe)
            headerIcon.imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
        }
        if (http && !tab.incognito) {
            Favicons.load(tab.url) { icon ->
                if (icon != null && isOpen && TabManager.current === tab) {
                    headerIcon.imageTintList = null
                    headerIcon.setImageBitmap(icon)
                }
            }
        }

        forward.set(R.drawable.ic_forward, enabled = tab.canGoForward)
        reload.set(if (tab.loading) R.drawable.ic_close else R.drawable.ic_refresh, enabled = !tab.isBlank)
        val bookmarked = http && SwiftApp.db.isBookmarked(tab.profileId, tab.url)
        bookmark.set(if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border, enabled = http, active = bookmarked)
        bookmark.view.contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark"
        share.set(R.drawable.ic_share, enabled = http)
        find.set(R.drawable.ic_find, enabled = page)

        val prefs = SwiftApp.prefs
        items["lite"]?.set(R.drawable.ic_lite, "Lite mode", active = prefs.liteMode)
        items["dark"]?.set(R.drawable.ic_dark, "Dark mode", active = prefs.darkMode)
        items["desktop"]?.set(R.drawable.ic_desktop, "Desktop", active = tab.desktop, enabled = !tab.isHome)
        items["fullscreen"]?.set(R.drawable.ic_fullscreen, "Full screen", active = prefs.fullScreen)
        items["reader"]?.set(R.drawable.ic_reader, if (inReader) "Exit reader" else "Reader view", active = inReader, enabled = (http && page) || inReader)
        items["save"]?.set(R.drawable.ic_offline, "Save offline", enabled = http && page)
        items["home_screen"]?.set(R.drawable.ic_home_add, "Home screen", enabled = http)
        val level = AdBlock.levelFor(tab.url)
        val blocked = tab.badge?.takeIf { level != AdLevel.OFF && it.isNotBlank() && it != "0" }
        items["ad_blocker"]?.set(
            if (level == AdLevel.OFF) R.drawable.ic_shield_off else R.drawable.ic_shield,
            if (blocked != null) "$blocked blocked" else if (level == AdLevel.OFF) "Ads allowed" else "Ad blocker",
        )
        items["speed"]?.set(R.drawable.ic_speed, "Speed ${a.formatSpeed(tab.speed)}", active = tab.speed.toDouble() != 1.0)
        items["close_all"]?.set(R.drawable.ic_close, if (tab.incognito) "Close incognito" else "Close all tabs")
        items["group"]?.set(R.drawable.ic_layers, if (tab.groupId != null) "Move to group" else "Add to group")
        items["hide"]?.set(R.drawable.ic_block, "Tap to hide", enabled = http && page && AdBlock.global != AdLevel.OFF)
        val timer = com.swiftbrowser.media.SleepTimer.label()
        items["sleep"]?.set(R.drawable.ic_timer, timer ?: "Sleep timer", active = timer != null)
        val vpnOn = com.swiftbrowser.vpn.Vpn.state == com.swiftbrowser.vpn.Vpn.State.ON
        items["vpn"]?.set(R.drawable.ic_vpn, if (vpnOn) "VPN · " + (com.swiftbrowser.vpn.Vpn.current()?.country ?: "on") else "VPN", active = vpnOn)
    }

    /** Normal tabs close at once, with Undo (2.3); incognito ones ask first, as their data goes for good. */
    private fun confirmCloseAll(tab: Tab) {
        if (!tab.incognito) {
            TabManager.closeAll(false)
            return
        }
        Dialogs.confirm(a, "Close all incognito tabs?", "Their cookies and site data are deleted.", "Close all", danger = true) {
            TabManager.closeAll(true)
        }
    }

    /** On a turned phone the menu keeps a phone's width, centred. */
    private fun fitWidth() {
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        val max = dp(Ui.MAX_CONTENT_WIDTH)
        val w = if (root.width > max) max else ViewGroup.LayoutParams.MATCH_PARENT
        if (lp.width != w) {
            lp.width = w
            panel.layoutParams = lp
        }
    }

    /** The panel's height, measured on the first opening (before it has been laid out). */
    private fun panelHeight(): Float {
        if (panel.height > 0) return panel.height.toFloat()
        val w = (panel.layoutParams as FrameLayout.LayoutParams).width.let { if (it > 0) it else root.width }
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return panel.measuredHeight.toFloat()
    }

    private fun showPage(index: Int) {
        val argb = android.animation.ArgbEvaluator()
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            val target = color(if (i == index) Looks.accent.primary else R.color.outline)
            val shape = dot.getTag(R.id.motion_icon) as? GradientDrawable
            if (shape == null) {
                // A small dot in a finger-sized place.
                val fresh = GradientDrawable().apply {
                    this.shape = GradientDrawable.OVAL
                    setColor(target)
                }
                dot.setTag(R.id.motion_icon, fresh)
                dot.background = InsetDrawable(fresh, dp(6), dp(7), dp(6), dp(7))
                dot.scaleX = if (i == index) 1.3f else 1f
                dot.scaleY = dot.scaleX
                continue
            }
            // The page changed: the dots trade colours and sizes smoothly (2.3).
            val from = shape.color?.defaultColor ?: target
            if (from != target) {
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = Motion.NORMAL
                    addUpdateListener { shape.setColor(argb.evaluate(it.animatedFraction, from, target) as Int) }
                    start()
                }
            }
            val scale = if (i == index) 1.3f else 1f
            dot.animate().scaleX(scale).scaleY(scale).setDuration(Motion.NORMAL).setInterpolator(Motion.standard).start()
        }
    }

    // ------------------------------------------------------------------ moving buttons (2.3)

    private fun hideStrip(): View = hideStrip.apply {
        text = "Drop here to hide"
        textSize = 14f
        setTypeface(Ui.MEDIUM)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        compoundDrawablePadding = dp(10)
        visibility = View.INVISIBLE
        stripLit(false)
        // The icon and the words sit together in the middle (a start icon would hug the edge).
        addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l == oldR - oldL) return@addOnLayoutChangeListener
            val tv = v as TextView
            val content = tv.paint.measureText(tv.text.toString()) + tv.compoundDrawablePadding +
                (tv.compoundDrawablesRelative[0]?.intrinsicWidth ?: 0)
            val side = ((r - l - content) / 2).toInt().coerceAtLeast(dp(16))
            tv.post { tv.setPaddingRelative(side, 0, side, 0) }
        }
    }

    private fun stripLit(lit: Boolean) {
        val ink = color(if (lit) Looks.accent.onContainer else R.color.text_secondary)
        hideStrip.setTextColor(ink)
        hideStrip.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(a, R.drawable.ic_hide, ink), null, null, null)
        hideStrip.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color(if (lit) Looks.accent.container else R.color.surface_alt))
            setStroke(dp(2), color(if (lit) Looks.accent.primary else R.color.outline))
        }
        hideStrip.animate().scaleX(if (lit) 1.03f else 1f).scaleY(if (lit) 1.03f else 1f).setDuration(Motion.SHORT).start()
    }

    /** A button held long enough: it lifts under the finger, and the hide strip appears. */
    private fun startDrag(key: String, source: View) {
        val pageIndex = layout.pageOf(key)
        dragFrom = if (pageIndex >= 0) pageIndex to layout.pages[pageIndex].indexOf(key) else null
        dragKey = key
        // The hint keeps its place while the finger is down (nothing moves under it), then folds away.
        if (hint.visibility == View.VISIBLE) hint.visibility = View.INVISIBLE
        source.isPressed = false
        source.jumpDrawablesToCurrentState()
        ghost.start(source, lastX, lastY)
        source.visibility = View.INVISIBLE
        Haptics.hold(source)
        // The header makes way for the strip.
        hideStrip.alpha = 0f
        hideStrip.visibility = View.VISIBLE
        hideStrip.animate().alpha(1f).setDuration(Motion.SHORT).start()
        headerRow.animate().alpha(0f).setDuration(Motion.SHORT).start()
        actionsRow.animate().alpha(0f).setDuration(Motion.SHORT).start()
        render(animate = false)
    }

    private fun dragMove(x: Float, y: Float) {
        lastX = x
        lastY = y
        ghost.move(x, y)
        // Held at the menu's left or right edge: on to the page before or after.
        val at = IntArray(2)
        panel.getLocationOnScreen(at)
        val side = when {
            x < at[0] + dp(28) -> -1
            x > at[0] + panel.width - dp(28) -> 1
            else -> 0
        }
        if (side != edge) {
            edge = side
            handler.removeCallbacks(flip)
            if (side != 0) handler.postDelayed(flip, 550)
        }
        retarget(x, y)
    }

    private val flip = object : Runnable {
        override fun run() {
            val to = pager.page + edge
            if (dragKey == null || edge == 0 || to !in 0 until grids.size) return
            pager.goTo(to)
            Haptics.tick(panel)
            handler.postDelayed({ retarget(lastX, lastY) }, 260)
            // Still held there: one more page after a longer moment (not two at once).
            handler.postDelayed(this, 1200)
        }
    }

    /** Moves the held button to the place under the finger (on the page shown), or onto the hide strip. */
    private fun retarget(x: Float, y: Float) {
        val key = dragKey ?: return
        val overStrip = within(hideStrip, x, y)
        if (overStrip != (hideStrip.tag == true)) {
            hideStrip.tag = overStrip
            stripLit(overStrip)
            if (overStrip) Haptics.tick(hideStrip)
        }
        if (overStrip) {
            if (key in layout.hidden) return
            layout.pages.forEach { it.remove(key) }
            layout.hidden.add(key)
            render(animate = true)
            return
        }
        val pageIndex = pager.page
        val grid = grids.getOrNull(pageIndex) ?: return
        val place = grid.indexAt(x, y)
        if (place < 0) return
        while (layout.pages.size <= pageIndex) layout.pages.add(mutableListOf())
        val page = layout.pages[pageIndex]
        val now = page.indexOf(key)
        val to = place.coerceAtMost(if (now >= 0) page.size - 1 else page.size)
        if (now == to) return
        layout.hidden.remove(key)
        layout.pages.forEach { it.remove(key) }
        page.add(to.coerceAtMost(page.size), key)
        // A full page passes its last button on to the next one.
        var i = pageIndex
        while (i < layout.pages.size && layout.pages[i].size > MenuButtons.PER_PAGE) {
            val extra = layout.pages[i].removeAt(layout.pages[i].lastIndex)
            if (i + 1 >= layout.pages.size) layout.pages.add(mutableListOf())
            layout.pages[i + 1].add(0, extra)
            i++
        }
        render(animate = true)
    }

    /** Let go: it stays where it is (saved at once), or is hidden, with Undo. */
    private fun dragDrop() {
        val key = dragKey ?: return
        dragKey = null
        edge = 0
        handler.removeCallbacksAndMessages(null)
        val hidden = key in layout.hidden
        layout.tidy()
        MenuButtons.save(layout)
        endDragLook()
        render(animate = true)
        val view = items.getValue(key).view
        if (hidden) {
            ghost.drop(null) { view.visibility = View.VISIBLE }
            val from = dragFrom
            val label = MenuButtons.button(key).label
            a.snack("$label is hidden. It's under Hidden on the last page.", "Undo") {
                layout.hidden.remove(key)
                val p = from?.first?.coerceAtMost(layout.pages.size) ?: layout.pages.size
                while (layout.pages.size <= p) layout.pages.add(mutableListOf())
                layout.pages[p].add((from?.second ?: 0).coerceIn(0, layout.pages[p].size), key)
                layout.tidy()
                MenuButtons.save(layout)
                render(animate = true)
            }
        } else {
            view.visibility = View.INVISIBLE
            ghost.drop(view) { view.visibility = View.VISIBLE }
        }
        dragFrom = null
        Haptics.tick(panel)
    }

    /** The menu closed (or Back) while a button was held: it goes back where it was. */
    private fun cancelDrag() {
        val key = dragKey ?: return
        dragKey = null
        edge = 0
        handler.removeCallbacksAndMessages(null)
        layout = MenuButtons.layout()
        ghost.cancel()
        items.getValue(key).view.visibility = View.VISIBLE
        endDragLook()
        render(animate = false)
        dragFrom = null
    }

    private fun endDragLook() {
        if (hint.visibility == View.INVISIBLE) Motion.collapse(hint) { hint.visibility = View.GONE }
        hideStrip.tag = false
        stripLit(false)
        hideStrip.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction { hideStrip.visibility = View.INVISIBLE }.start()
        headerRow.animate().alpha(1f).setDuration(Motion.SHORT).start()
        actionsRow.animate().alpha(1f).setDuration(Motion.SHORT).start()
    }

    /** The hidden buttons, in a grid that scrolls: tap one to put it back (after the last button). */
    private fun hiddenSheet() {
        if (layout.hidden.isEmpty()) return
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "Hidden buttons", "Tap one to put it back in the menu."))
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        for (row in layout.hidden.toList().chunked(MenuButtons.COLUMNS)) {
            col.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), 0, dp(8), 0)
                for (key in row) {
                    val b = MenuButtons.button(key)
                    addView(TextView(a).apply {
                        text = b.label
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(2), dp(10), dp(2), dp(8))
                        compoundDrawablePadding = dp(8)
                        textSize = 12.5f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(color(R.color.text_primary))
                        setBackgroundResource(borderless())
                        setCompoundDrawablesRelativeWithIntrinsicBounds(null, roundIcon(b.icon, false), null, null)
                        contentDescription = "Put back ${b.label}"
                        Ui.pressable(this, 0.92f)
                        setOnClickListener {
                            dialog.dismiss()
                            putBack(key)
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                repeat(MenuButtons.COLUMNS - row.size) { addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    /** A hidden button back after the last one; the menu shows its page and it gives a little bounce. */
    private fun putBack(key: String) {
        layout.hidden.remove(key)
        if (layout.pages.last().size >= MenuButtons.PER_PAGE) layout.pages.add(mutableListOf())
        layout.pages.last().add(key)
        layout.tidy()
        MenuButtons.save(layout)
        render(animate = true)
        pager.goTo(layout.pageOf(key))
        val v = items.getValue(key).view
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.NORMAL).setInterpolator(Motion.overshoot).start()
    }

    /** The button under a point on the screen (on the page shown). */
    private fun keyAt(x: Float, y: Float): String? {
        val page = layout.pages.getOrNull(pager.page) ?: return null
        val grid = grids.getOrNull(pager.page) ?: return null
        if (!within(grid, x, y)) return null
        return page.getOrNull(grid.indexAt(x, y))
    }

    private fun within(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return x >= at[0] && x < at[0] + v.width && y >= at[1] && y < at[1] + v.height
    }

    // ------------------------------------------------------------------ the panel and its pages

    /**
     * The menu itself; a downward swipe anywhere on it drags it down and closes it. While a button
     * is held, the finger moves that button instead.
     */
    private inner class Panel : LinearLayout(a) {
        private val slop = ViewConfiguration.get(a).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var velocity: VelocityTracker? = null

        /** Lifts the button pressed if the finger stays put for a moment. */
        private val lift = Runnable {
            val key = pressedKey ?: return@Runnable
            if (!isOpen || dragKey != null) return@Runnable
            startDrag(key, items.getValue(key).view)
        }
        private var pressedKey: String? = null

        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            // The pages took the swipe (2.6): the finger moved on, so no button gets lifted.
            if (disallow) this@QuickMenu.handler.removeCallbacks(lift)
            super.requestDisallowInterceptTouchEvent(disallow)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (dragKey != null) {
                arrangeTouch(ev)
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    lastX = ev.rawX
                    lastY = ev.rawY
                    dragging = false
                    pressedKey = keyAt(ev.rawX, ev.rawY)
                    this@QuickMenu.handler.removeCallbacks(lift)
                    if (pressedKey != null) this@QuickMenu.handler.postDelayed(lift, HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> this@QuickMenu.handler.removeCallbacks(lift)
                MotionEvent.ACTION_MOVE -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    // Moved before the moment was up: a swipe (pages, closing) or nothing, not a lift.
                    if (hypot(ev.rawX - downX, ev.rawY - downY) > slop) this@QuickMenu.handler.removeCallbacks(lift)
                    val dy = ev.rawY - downY
                    // Down drags close the menu, unless its content is scrolled and should scroll back.
                    if (dy > slop && dy > abs(ev.rawX - downX) * 1.2f && scroller.scrollY == 0) {
                        startDrag(ev)
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (dragKey != null) {
                arrangeTouch(ev)
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A drag that starts on the panel's own background (not on a button).
                    downX = ev.rawX
                    downY = ev.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dy = ev.rawY - downY
                        if (dy > slop && dy > abs(ev.rawX - downX) * 1.2f) startDrag(ev) else return true
                    }
                    velocity?.addMovement(ev)
                    translationY = (ev.rawY - downY).coerceAtLeast(0f)
                    scrim.alpha = 1f - (translationY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val v = velocity
                        v?.addMovement(ev)
                        v?.computeCurrentVelocity(1000)
                        val flungDown = (v?.yVelocity ?: 0f) > ViewConfiguration.get(a).scaledMinimumFlingVelocity * 6
                        velocity?.recycle()
                        velocity = null
                        dragging = false
                        if (translationY > height * 0.25f || flungDown) {
                            close()
                        } else {
                            animate().translationY(0f).setDuration(150).start()
                            scrim.animate().alpha(1f).setDuration(150).start()
                        }
                    }
                }
            }
            return true
        }

        /** A held button follows the finger and lands where it's let go. */
        private fun arrangeTouch(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragMove(ev.rawX, ev.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragDrop()
            }
        }

        private fun startDrag(ev: MotionEvent) {
            dragging = true
            downY = ev.rawY
            animate().cancel()
            scrim.animate().cancel()
            velocity?.recycle()
            velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    /** Pages of buttons side by side; a swipe moves to the next or previous page and stops there. */
    private inner class Pager : HorizontalScrollView(a) {
        private val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        private var flung = false
        var page = 0
            private set

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        }

        fun addPage(page: View) {
            strip.addView(page, LinearLayout.LayoutParams(width.coerceAtLeast(0), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun removePage(page: View) = strip.removeView(page)

        fun reset() {
            page = 0
            scrollTo(0, 0)
            showPage(0)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Each page is as wide as the menu.
            val w = MeasureSpec.getSize(widthMeasureSpec)
            for (i in 0 until strip.childCount) strip.getChildAt(i).layoutParams.width = w
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw) post { scrollTo(page * w, 0) }
        }

        override fun fling(velocityX: Int) {
            flung = true
            val target = if (velocityX > 0) page + 1 else page - 1
            goTo(target)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
            val handled = super.onTouchEvent(ev)
            if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
                goTo(((scrollX + width / 2) / width.coerceAtLeast(1)))
            }
            return handled
        }

        fun goTo(target: Int) {
            page = target.coerceIn(0, (strip.childCount - 1).coerceAtLeast(0))
            smoothScrollTo(page * width, 0)
            showPage(page)
        }
    }

    /**
     * A page of buttons: up to 8 in 2 rows of 4, always 2 rows high so every page (and the menu)
     * keeps its height. Buttons that move on the page slide to their new place.
     */
    private inner class PageGrid : ViewGroup(a) {
        private val before = HashMap<View, IntArray>()
        private var cellW = 1
        private var cellH = 1

        init {
            setPadding(dp(6), 0, dp(6), 0)
            clipChildren = false
        }

        /** Shows [views] in this order. */
        fun show(views: List<View>, animate: Boolean) {
            if (childCount == views.size && views.indices.all { getChildAt(it) === views[it] }) return
            before.clear()
            if (animate) {
                for (i in 0 until childCount) getChildAt(i).let { if (it.width > 0) before[it] = intArrayOf(it.left, it.top) }
            }
            removeAllViews()
            for (v in views) {
                (v.parent as? ViewGroup)?.removeView(v)
                addView(v)
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            cellW = ((w - paddingLeft - paddingRight) / MenuButtons.COLUMNS).coerceAtLeast(1)
            // At least 88 dp, taller with large phone text (the names never get cut).
            var h = dp(88)
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val cellWidth = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(cellWidth, unspecified)
                h = maxOf(h, c.measuredHeight)
            }
            cellH = h
            val cellHeight = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(cellWidth, cellHeight)
            setMeasuredDimension(w, cellH * MenuButtons.ROWS)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = paddingLeft + (i % MenuButtons.COLUMNS) * cellW
                val y = (i / MenuButtons.COLUMNS) * cellH
                val old = before.remove(c)
                c.layout(x, y, x + cellW, y + cellH)
                if (old != null && (old[0] != x || old[1] != y)) {
                    c.translationX = (old[0] - x).toFloat()
                    c.translationY = (old[1] - y).toFloat()
                    c.animate().translationX(0f).translationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            before.clear()
        }

        /** The place (0–7) under a point on the screen, or -1 well outside this page. */
        fun indexAt(x: Float, y: Float): Int {
            val at = IntArray(2)
            getLocationOnScreen(at)
            val inX = x - at[0] - paddingLeft
            val inY = y - at[1]
            if (inY < -dp(24) || inY > height + dp(12)) return -1
            val col = (inX / cellW).toInt().coerceIn(0, MenuButtons.COLUMNS - 1)
            val row = (inY / cellH).toInt().coerceIn(0, MenuButtons.ROWS - 1)
            return row * MenuButtons.COLUMNS + col
        }
    }

    // ------------------------------------------------------------------ building

    /** The page the menu is about; the button copies its address. */
    private fun header(): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(10), dp(6))
        addView(FrameLayout(a).apply {
            background = GradientDrawable().apply {
                setColor(color(R.color.surface_alt))
                cornerRadius = dp(12).toFloat()
            }
            addView(headerIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(headerTitle.apply {
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(color(R.color.text_primary))
            })
            addView(headerSite.apply {
                textSize = 12.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(color(R.color.text_secondary))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(siteButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(copyButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(ImageButton(a).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            setBackgroundResource(borderless())
            contentDescription = "Settings"
            setOnClickListener {
                close()
                a.openList(SettingsActivity::class.java)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    /** An icon-only page action in the row under the header. */
    private inner class IconAction(val view: ImageButton) {
        private var icon = 0
        private var active = false

        fun set(icon: Int, enabled: Boolean = true, active: Boolean = false) {
            if (icon != this.icon || active != this.active) {
                this.icon = icon
                this.active = active
                view.setImageResource(icon)
                view.imageTintList = ColorStateList.valueOf(color(if (active) Looks.accent.primary else R.color.text_primary))
            }
            if (view.isEnabled != enabled) {
                view.isEnabled = enabled
                view.alpha = if (enabled) 1f else 0.35f
            }
        }
    }

    private fun iconAction(row: LinearLayout, icon: Int, label: String, action: (Tab) -> Unit): IconAction {
        val view = ImageButton(a).apply {
            contentDescription = label
            setBackgroundResource(borderless())
            scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(view, LinearLayout.LayoutParams(0, dp(48), 1f))
        val item = IconAction(view)
        item.set(icon)
        view.setOnClickListener {
            val tab = TabManager.current ?: return@setOnClickListener
            close()
            action(tab)
        }
        return item
    }

    /** A button: a round icon with its name below, all in one view so the menu stays light. */
    private inner class Item(val key: String, val view: TextView, val action: (Tab) -> Unit) {
        private var icon = 0
        private var active = false
        private var colors: android.animation.ValueAnimator? = null

        fun set(icon: Int, label: String, active: Boolean = false, enabled: Boolean = true) {
            if (icon == this.icon && active != this.active && view.isShown) {
                // A switch turned on or off: its circle takes the new colour smoothly (2.3).
                this.active = active
                val layers = view.compoundDrawablesRelative[1] as? LayerDrawable
                val circle = layers?.getDrawable(0) as? GradientDrawable
                val glyph = layers?.getDrawable(1)
                if (circle != null && glyph != null) {
                    val fromFill = color(if (active) R.color.surface_alt else Looks.accent.container)
                    val toFill = color(if (active) Looks.accent.container else R.color.surface_alt)
                    val fromInk = color(if (active) R.color.text_primary else Looks.accent.onContainer)
                    val toInk = color(if (active) Looks.accent.onContainer else R.color.text_primary)
                    val argb = android.animation.ArgbEvaluator()
                    colors?.cancel()
                    colors = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = Motion.NORMAL
                        interpolator = Motion.standard
                        addUpdateListener {
                            val f = it.animatedFraction
                            circle.setColor(argb.evaluate(f, fromFill, toFill) as Int)
                            glyph.setTint(argb.evaluate(f, fromInk, toInk) as Int)
                        }
                        start()
                    }
                    Motion.bump(view, 1.06f)
                } else {
                    view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, roundIcon(icon, active), null, null)
                }
            } else if (icon != this.icon || active != this.active) {
                colors?.cancel()
                this.icon = icon
                this.active = active
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, roundIcon(icon, active), null, null)
            }
            if (view.text.toString() != label) view.text = label
            // Screen readers hear whether a switch is on (2.6): its colour alone doesn't say.
            view.contentDescription = if (key in MenuButtons.switches) "$label, ${if (active) "on" else "off"}" else label
            if (view.isEnabled != enabled) {
                view.isEnabled = enabled
                // Fades rather than jumps (2.3).
                view.animate().alpha(if (enabled) 1f else 0.38f).setDuration(Motion.SHORT).start()
            }
        }
    }

    private fun item(key: String, icon: Int, label: String, closes: Boolean, action: ((Tab) -> Unit)? = null): Item {
        val view = TextView(a).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(6))
            compoundDrawablePadding = dp(8)
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_primary))
            setBackgroundResource(borderless())
        }
        // At least this tall, taller with large phone text (the name never gets cut).
        view.minHeight = dp(88)
        Ui.pressable(view, 0.92f)
        val item = Item(key, view, action ?: { tab -> run(key, tab) })
        item.set(icon, label)
        view.setOnClickListener {
            val tab = TabManager.current ?: return@setOnClickListener
            if (closes) close()
            item.action(tab)
        }
        // Holding is the menu's own (a shorter moment than Android's long press): it lifts the button.
        view.isLongClickable = false
        return item
    }

    private fun roundIcon(icon: Int, active: Boolean, size: Int = 48, fill: Int? = null): Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill ?: color(if (active) Looks.accent.container else R.color.surface_alt))
        }
        val glyph = ContextCompat.getDrawable(a, icon)!!.mutate()
        glyph.setTint(color(if (active) Looks.accent.onContainer else R.color.text_primary))
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerSize(0, dp(size), dp(size))
            setLayerSize(1, dp(size * 22 / 48), dp(size * 22 / 48))
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    private fun borderless(): Int {
        val arr = a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val res = arr.getResourceId(0, 0)
        arr.recycle()
        return res
    }

    private fun color(res: Int) = Palette.color(a, res)

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private companion object {
        /** How long a button is held before it lifts (Android's own long press is 500 ms). */
        const val HOLD_MS = 320L
        const val HINT_SEEN = "menu_move_hint_seen"
    }
}
