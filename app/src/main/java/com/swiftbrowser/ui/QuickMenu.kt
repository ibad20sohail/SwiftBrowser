package com.swiftbrowser.ui

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The ⋮ menu's buttons and how they are arranged (2.2): pages of 3 rows of 4, in the order the
 * user put them (hold a button to arrange), and the ones they hid.
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
        Button("reader", R.drawable.ic_reader, "Reader view"),
        Button("save", R.drawable.ic_offline, "Save offline"),
        Button("lite", R.drawable.ic_lite, "Lite mode"),
        Button("dark", R.drawable.ic_dark, "Dark mode"),
        Button("desktop", R.drawable.ic_desktop, "Desktop"),
        Button("fullscreen", R.drawable.ic_fullscreen, "Full screen"),
        Button("recent", R.drawable.ic_reopen, "Recent tabs"),
        Button("vpn", R.drawable.ic_vpn, "VPN"),
        Button("scan", R.drawable.ic_qr, "Scan QR"),
        Button("home_screen", R.drawable.ic_home_add, "Add to Home"),
        Button("ad_blocker", R.drawable.ic_shield, "Ad blocker"),
        Button("speed", R.drawable.ic_speed, "Speed"),
        Button("sleep", R.drawable.ic_timer, "Sleep timer"),
        Button("group", R.drawable.ic_layers, "Add to group"),
        Button("hide", R.drawable.ic_block, "Tap to hide"),
        Button("close_all", R.drawable.ic_close, "Close all tabs"),
    )

    /** The switches (they were tiles above the buttons until 2.2): tapping one leaves the menu open. */
    val switches = setOf("lite", "dark", "desktop", "fullscreen")

    const val COLUMNS = 4
    const val ROWS = 3
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
 * The ⋮ menu, sliding up from the bottom:
 *  - the page it is about (with its site settings, copy and Settings buttons), and the most used
 *    page actions as a row of icons,
 *  - the buttons, in pages of 3 rows of 4 swiped sideways (or picked with the dots). The switches
 *    among them (Lite mode, dark mode, desktop site, full screen) leave the menu open.
 * Holding a button arranges the menu: buttons are dragged to another place or page, or onto the
 * strip at the bottom to hide them (2.2). Swiping the menu down (or tapping outside it) closes it.
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
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(a, R.color.text_secondary))
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
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(a, R.color.text_secondary))
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

    /** Every button by MenuButtons key, made once; arranging moves them between the pages. */
    private val items = LinkedHashMap<String, Item>()
    private val grids = ArrayList<PageGrid>()
    private var layout = MenuButtons.layout()

    // Arranging (2.2).
    private var arranging = false
    private val arrangeBar: View
    private val tray = LinearLayout(a)
    private val trayRow = LinearLayout(a)
    private val trayHint = TextView(a)
    private val trayChips = LinkedHashMap<String, TextView>()
    private val ghost = DragGhost(root)
    private var dragKey: String? = null
    private var lastX = 0f
    private var lastY = 0f
    private var edge = 0
    private val handler = Handler(Looper.getMainLooper())
    private var wiggle: ValueAnimator? = null

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
        content.addView(headerRow)
        arrangeBar = arrangeBar()
        content.addView(arrangeBar)

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
        content.addView(actions)
        // While arranging, the hidden buttons wait above the pages (the menu grows upwards, so the
        // buttons stay under the finger).
        content.addView(tray(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(2), dp(14), dp(10))
        })
        content.addView(View(a).apply { setBackgroundColor(color(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(18), 0, dp(18), dp(4))
            })

        // Pages of buttons, swiped sideways.
        for (b in MenuButtons.all) items[b.key] = item(b.key, b.icon, b.label, closes = b.key !in MenuButtons.switches)
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
        }
        if (key in MenuButtons.switches && isOpen) update(tab)
    }

    /**
     * Puts the buttons on their pages as [layout] says (and, while arranging, an empty page at the
     * end to start a new one, and the hidden ones in the tray). [animate]: buttons that only moved
     * on their page slide there.
     */
    private fun render(animate: Boolean) {
        val count = layout.pages.size + if (arranging) 1 else 0
        while (grids.size < count) PageGrid().also {
            grids += it
            pager.addPage(it)
        }
        while (grids.size > count) grids.removeAt(grids.lastIndex).also {
            it.removeAllViews()
            pager.removePage(it)
        }
        grids.forEachIndexed { i, grid ->
            grid.show(layout.pages.getOrNull(i).orEmpty().map { items.getValue(it).view }, animate)
        }
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
        renderTray()
    }

    /** [animate] false: shown at once, as when the screen restarts in the other theme. [arrange]: ready to arrange (from Settings). */
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
        if (arrange) startArranging()
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
        finishArranging()
        isOpen = false
        scrim.animate().cancel()
        panel.animate().cancel()
        val h = panelHeight()
        val remaining = ((h - panel.translationY) / h).coerceIn(0.2f, 1f)
        scrim.animate().alpha(0f).setDuration((140 * remaining).toLong()).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationY(h).setDuration((140 * remaining).toLong()).setInterpolator(AccelerateInterpolator())
            .withLayer().withEndAction { panel.visibility = View.GONE }.start()
    }

    /** Back: ends arranging first, then closes. */
    fun onBack() {
        if (arranging) finishArranging() else close()
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
            http -> UrlUtils.host(tab.url)?.removePrefix("www.").orEmpty()
            tab.isHome -> "Swift"
            else -> UrlUtils.display(tab.url)
        }
        if (tab.isHome && !tab.incognito) {
            headerIcon.setImageResource(R.drawable.ic_logo)
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
        share.set(R.drawable.ic_share, enabled = http)
        find.set(R.drawable.ic_find, enabled = page)

        val prefs = SwiftApp.prefs
        items["lite"]?.set(R.drawable.ic_lite, "Lite mode", active = prefs.liteMode)
        items["dark"]?.set(R.drawable.ic_dark, "Dark mode", active = prefs.darkMode)
        items["desktop"]?.set(R.drawable.ic_desktop, "Desktop", active = tab.desktop, enabled = !tab.isHome)
        items["fullscreen"]?.set(R.drawable.ic_fullscreen, "Full screen", active = prefs.fullScreen)
        items["reader"]?.set(R.drawable.ic_reader, if (inReader) "Exit reader" else "Reader view", active = inReader, enabled = (http && page) || inReader)
        items["save"]?.set(R.drawable.ic_offline, "Save offline", enabled = http && page)
        items["home_screen"]?.set(R.drawable.ic_home_add, "Add to Home", enabled = http)
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

    private fun confirmCloseAll(tab: Tab) {
        val incognito = tab.incognito
        Dialogs.confirm(
            a,
            if (incognito) "Close all incognito tabs?" else "Close all tabs?",
            if (incognito) "Their history and cookies are deleted." else "All tabs in ${Profiles.current().name} close. Recent tabs in the menu keeps them for a while.",
            "Close all", danger = true,
        ) { TabManager.closeAll(incognito) }
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
        for (i in 0 until dots.childCount) {
            // A small dot in a finger-sized place.
            dots.getChildAt(i).background = InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(if (i == index) Looks.accent.primary else R.color.outline))
            }, dp(6), dp(7), dp(6), dp(7))
        }
    }

    // ------------------------------------------------------------------ arranging (2.2)

    private fun startArranging() {
        if (arranging) return
        arranging = true
        headerRow.visibility = View.GONE
        actionsRow.visibility = View.GONE
        arrangeBar.visibility = View.VISIBLE
        tray.visibility = View.VISIBLE
        TabManager.current?.let { update(it) }
        render(animate = false)
        // The buttons sway a little, so it's clear they can be moved.
        wiggle = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val grid = grids.getOrNull(pager.page) ?: return@addUpdateListener
                for (i in 0 until grid.childCount) grid.getChildAt(i).rotation = 1.6f * sin(t + i * 1.7f)
            }
            start()
        }
    }

    private fun finishArranging() {
        if (!arranging) return
        dragKey?.let {
            ghost.cancel()
            items.getValue(it).view.visibility = View.VISIBLE
            trayChips[it]?.visibility = View.VISIBLE
        }
        dragKey = null
        handler.removeCallbacksAndMessages(null)
        wiggle?.cancel()
        wiggle = null
        for (item in items.values) item.view.rotation = 0f
        arranging = false
        headerRow.visibility = View.VISIBLE
        actionsRow.visibility = View.VISIBLE
        arrangeBar.visibility = View.GONE
        tray.visibility = View.GONE
        layout.tidy()
        MenuButtons.save(layout)
        render(animate = false)
        TabManager.current?.let { update(it) }
    }

    /** The bar that takes the header's place while arranging. */
    private fun arrangeBar(): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(12), dp(8))
        visibility = View.GONE
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, Ui.Type.TITLE, "Arrange the menu"))
            addView(Ui.text(a, Ui.Type.CAPTION, "Drag a button to move it, or up onto the strip to hide it.").apply {
                setPadding(0, dp(2), dp(8), 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(MaterialButton(a, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Reset"
            setOnClickListener {
                this@QuickMenu.layout = MenuButtons.defaultLayout()
                MenuButtons.save(this@QuickMenu.layout)
                render(animate = true)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        addView(MaterialButton(a).apply {
            text = "Done"
            setOnClickListener { finishArranging() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(4) })
    }

    /** Where hidden buttons wait while arranging (above the pages); dropping a button here hides it. */
    private fun tray(): View {
        tray.orientation = LinearLayout.VERTICAL
        tray.setPadding(dp(12), dp(10), dp(12), dp(10))
        tray.visibility = View.GONE
        trayHint.apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(a, R.drawable.ic_hide, color(R.color.text_secondary)), null, null, null)
        }
        tray.addView(trayHint)
        trayRow.orientation = LinearLayout.HORIZONTAL
        tray.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(trayRow)
        })
        trayLit(false)
        return tray
    }

    private fun trayLit(lit: Boolean) {
        tray.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color(if (lit) Looks.accent.container else R.color.surface_alt))
        }
    }

    private fun renderTray() {
        if (!arranging) return
        trayHint.text = if (layout.hidden.isEmpty()) "Drop a button here to hide it" else "Hidden: tap one to put it back"
        trayRow.removeAllViews()
        trayChips.clear()
        for (key in layout.hidden) {
            val b = MenuButtons.button(key)
            val chip = TextView(a).apply {
                text = b.label
                textSize = 11.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(color(R.color.text_primary))
                setPadding(dp(2), dp(8), dp(2), dp(2))
                compoundDrawablePadding = dp(6)
                // White circles: the tray itself is the grey of the buttons' circles.
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, roundIcon(b.icon, false, 40, color(R.color.sheet_bg)), null, null)
                setBackgroundResource(borderless())
                contentDescription = "${b.label}, hidden"
                visibility = if (key == dragKey) View.INVISIBLE else View.VISIBLE
                setOnClickListener { putBack(key) }
                setOnLongClickListener {
                    startDrag(key, this)
                    true
                }
            }
            trayChips[key] = chip
            trayRow.addView(chip, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        trayRow.visibility = if (layout.hidden.isEmpty()) View.GONE else View.VISIBLE
    }

    /** A hidden button back in the first free place. */
    private fun putBack(key: String) {
        layout.hidden.remove(key)
        val page = layout.pages.firstOrNull { it.size < MenuButtons.PER_PAGE } ?: mutableListOf<String>().also { layout.pages.add(it) }
        page.add(key)
        MenuButtons.save(layout)
        Haptics.tick(tray)
        render(animate = true)
        pager.goTo(layout.pageOf(key))
    }

    private fun startDrag(key: String, source: View) {
        if (!arranging) startArranging()
        dragKey = key
        // Its picture without the press highlight or the sway.
        source.isPressed = false
        source.jumpDrawablesToCurrentState()
        source.rotation = 0f
        ghost.start(source, lastX, lastY)
        // Held from the tray or the page: it shows only in the finger until let go.
        items.getValue(key).view.visibility = View.INVISIBLE
        trayChips[key]?.visibility = View.INVISIBLE
        Haptics.hold(source)
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
            if (side != 0) handler.postDelayed(flip, 600)
        }
        retarget(x, y)
    }

    private val flip = object : Runnable {
        override fun run() {
            val to = pager.page + edge
            if (dragKey == null || edge == 0 || to !in 0 until grids.size) return
            pager.goTo(to)
            handler.postDelayed({ retarget(lastX, lastY) }, 260)
            // Still held there: one more page after a longer moment (not two at once).
            handler.postDelayed(this, 1300)
        }
    }

    /** Moves the held button to the place under the finger (on the page shown), or to the hidden ones. */
    private fun retarget(x: Float, y: Float) {
        val key = dragKey ?: return
        val overTray = within(tray, x, y)
        trayLit(overTray)
        if (overTray) {
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

    private fun dragDrop() {
        val key = dragKey ?: return
        dragKey = null
        edge = 0
        handler.removeCallbacksAndMessages(null)
        trayLit(false)
        layout.tidy()
        MenuButtons.save(layout)
        render(animate = true)
        val target = if (key in layout.hidden) trayChips[key] else items.getValue(key).view
        target?.visibility = View.INVISIBLE
        ghost.drop(target) {
            items.getValue(key).view.visibility = View.VISIBLE
            trayChips[key]?.visibility = View.VISIBLE
        }
        Haptics.tick(panel)
    }

    /** Tapped while arranging: move it a place, to another page, or hide it. */
    private fun arrangeSheet(key: String) {
        val b = MenuButtons.button(key)
        val pageIndex = layout.pageOf(key)
        if (pageIndex < 0) return
        val page = layout.pages[pageIndex]
        val place = page.indexOf(key)
        val actions = ArrayList<Sheets.Action>()
        fun moveTo(p: Int, at: Int) {
            page.remove(key)
            while (layout.pages.size <= p) layout.pages.add(mutableListOf())
            layout.pages[p].add(at.coerceIn(0, layout.pages[p].size), key)
            layout.tidy()
            MenuButtons.save(layout)
            render(animate = true)
            pager.goTo(layout.pageOf(key))
        }
        if (place > 0) actions += Sheets.Action(R.drawable.ic_back, "Move left", primary = true) { moveTo(pageIndex, place - 1) }
        if (place < page.size - 1) actions += Sheets.Action(R.drawable.ic_forward, "Move right", primary = true) { moveTo(pageIndex, place + 1) }
        actions += Sheets.Action(R.drawable.ic_hide, "Hide", primary = true) {
            page.remove(key)
            layout.hidden.add(key)
            layout.tidy()
            MenuButtons.save(layout)
            render(animate = true)
        }
        for (p in layout.pages.indices) {
            if (p != pageIndex) actions += Sheets.Action(R.drawable.ic_layers, "Move to page ${p + 1}") { moveTo(p, layout.pages[p].size) }
        }
        if (page.size > 1) actions += Sheets.Action(R.drawable.ic_add, "Move to a new page") { moveTo(layout.pages.size, 0) }
        Sheets.actions(a, b.label, "Page ${pageIndex + 1}, place ${place + 1}", actions, iconRes = b.icon)
    }

    /** The button (in the grid or the tray) under a point on the screen, for a drag that starts while arranging. */
    private fun keyAt(x: Float, y: Float): String? {
        for ((key, chip) in trayChips) if (chip.visibility == View.VISIBLE && within(chip, x, y)) return key
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
     * The menu itself; a downward swipe anywhere on it drags it down and closes it. While arranging
     * it follows a held button instead.
     */
    private inner class Panel : LinearLayout(a) {
        private val slop = ViewConfiguration.get(a).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var velocity: VelocityTracker? = null
        private var pressedKey: String? = null

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
                    pressedKey = if (arranging) keyAt(ev.rawX, ev.rawY) else null
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    if (arranging) {
                        // While arranging, a button moves as soon as it's dragged (no need to hold it first).
                        val key = pressedKey
                        if (key != null && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                            val source = trayChips[key]?.takeIf { key in layout.hidden } ?: items.getValue(key).view
                            lastX = downX
                            lastY = downY
                            startDrag(key, source)
                            dragMove(ev.rawX, ev.rawY)
                            return true
                        }
                        return false
                    }
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
            if (arranging) return true
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
     * A page of buttons: up to 12 in 3 rows of 4, always 3 rows high so every page (and the menu)
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

        /** The place (0–11) under a point on the screen, or -1 well outside this page. */
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
    private inner class Item(val view: TextView, val action: (Tab) -> Unit) {
        private var icon = 0
        private var active = false

        fun set(icon: Int, label: String, active: Boolean = false, enabled: Boolean = true) {
            if (icon != this.icon || active != this.active) {
                this.icon = icon
                this.active = active
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, roundIcon(icon, active), null, null)
            }
            if (view.text.toString() != label) view.text = label
            // While arranging, every button can be moved, even one the page can't use now.
            val usable = enabled || arranging
            if (view.isEnabled != usable) {
                view.isEnabled = usable
                view.alpha = if (usable) 1f else 0.38f
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
        val item = Item(view, action ?: { tab -> run(key, tab) })
        item.set(icon, label)
        view.setOnClickListener {
            if (arranging) {
                arrangeSheet(key)
                return@setOnClickListener
            }
            val tab = TabManager.current ?: return@setOnClickListener
            if (closes) close()
            item.action(tab)
        }
        // Holding a button arranges the menu, starting with that button in hand.
        view.setOnLongClickListener {
            if (dragKey == null) startDrag(key, view)
            true
        }
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

    private fun color(res: Int) = ContextCompat.getColor(a, res)

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
