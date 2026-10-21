package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

/**
 * The ⋮ menu, sliding up from the bottom:
 *  - the page it is about, with the most used page actions as a row of icons,
 *  - four switches (Lite mode, dark mode, desktop site, full screen) that stay open when tapped,
 *  - the rest as pages of 4×2 buttons that are swiped sideways.
 * Swiping the menu down (or tapping outside it) closes it. It is built once and lives in the
 * browser screen itself (a dialog would make a new window every time, which takes a moment on this
 * phone); opening it only updates what depends on the page.
 */
class QuickMenu(private val a: MainActivity, private val root: FrameLayout) {

    var isOpen = false
        private set

    private val scrim = View(a)
    private val panel = Panel()
    private val pager = Pager()
    private val dots = LinearLayout(a)

    private val headerIcon = ImageView(a)
    private val headerTitle = TextView(a)
    private val headerSite = TextView(a)

    private val forward: IconAction
    private val reload: IconAction
    private val bookmark: IconAction
    private val share: IconAction
    private val find: IconAction

    private val lite: Toggle
    private val dark: Toggle
    private val desktop: Toggle
    private val fullScreen: Toggle

    private val reader: Item
    private val save: Item
    private val homeScreen: Item
    private val adBlock: Item
    private val speed: Item
    private val closeAll: Item
    private val addToGroup: Item

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

        panel.addView(header())

        val actions = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
        forward = iconAction(actions, R.drawable.ic_forward, "Forward") { TabManager.goForward(it) }
        reload = iconAction(actions, R.drawable.ic_refresh, "Reload") { if (it.loading) it.session?.stop() else TabManager.reload(it) }
        bookmark = iconAction(actions, R.drawable.ic_star_border, "Bookmark") { toggleBookmark(it) }
        share = iconAction(actions, R.drawable.ic_share, "Share") { a.share(it.url) }
        find = iconAction(actions, R.drawable.ic_find, "Find in page") { a.openFind() }
        panel.addView(actions)

        // The switches, as a row of tiles.
        val switches = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        lite = toggle(switches, R.drawable.ic_lite, "Lite mode") { PageActions.setLite(a, it) }
        // The screen restarts in the other theme; MainActivity opens the menu again.
        dark = toggle(switches, R.drawable.ic_dark, "Dark mode") { Sheets.setDarkMode(it) }
        desktop = toggle(switches, R.drawable.ic_desktop, "Desktop") { on ->
            TabManager.current?.let { TabManager.setDesktop(it, on) }
        }
        fullScreen = toggle(switches, R.drawable.ic_fullscreen, "Full screen") { on ->
            SwiftApp.prefs.fullScreen = on
            a.applySystemBars()
        }
        panel.addView(switches)
        panel.addView(View(a).apply { setBackgroundColor(color(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(18), 0, dp(18), dp(4))
            })

        // Pages of buttons, swiped sideways.
        val page1 = page()
        item(page1, R.drawable.ic_add, "New tab") { TabManager.newTab(incognito = false) }
        item(page1, R.drawable.ic_incognito, "Incognito") { TabManager.newTab(incognito = true) }
        item(page1, R.drawable.ic_bookmarks, "Bookmarks") { a.openList(BookmarksActivity::class.java) }
        item(page1, R.drawable.ic_history, "History") { a.openList(HistoryActivity::class.java) }
        item(page1, R.drawable.ic_download, "Downloads") { a.openDownloads() }
        reader = item(page1, R.drawable.ic_reader, "Reader view") { PageActions.reader(a, it) }
        save = item(page1, R.drawable.ic_offline, "Save offline") { PageActions.savePage(a, it) }
        item(page1, R.drawable.ic_settings, "Settings") { a.openList(SettingsActivity::class.java) }
        val page2 = page()
        homeScreen = item(page2, R.drawable.ic_home_add, "Add to Home") { PageActions.addToHomeScreen(a, it) }
        adBlock = item(page2, R.drawable.ic_shield, "Ad blocker") { Sheets.shield(a) }
        speed = item(page2, R.drawable.ic_speed, "Speed") { Sheets.speed(a, it) }
        closeAll = item(page2, R.drawable.ic_close, "Close all tabs") { confirmCloseAll(it) }
        addToGroup = item(page2, R.drawable.ic_layers, "Add to group") { Sheets.chooseGroup(a, it) }
        item(page2, R.drawable.ic_reopen, "Recent tabs") { Sheets.recentlyClosed(a) }
        pager.addPage(page1)
        pager.addPage(page2)
        // A last row with fewer buttons keeps them in their columns.
        for (p in listOf(page1, page2)) {
            val last = p.getChildAt(p.childCount - 1) as? LinearLayout ?: continue
            while (last.childCount < 4) last.addView(View(a), LinearLayout.LayoutParams(0, dp(84), 1f))
        }
        panel.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        repeat(2) {
            dots.addView(View(a), LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        panel.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(2) })
        pager.reset()

        scrim.setBackgroundColor(color(R.color.scrim))
        scrim.elevation = panel.elevation
        scrim.outlineProvider = null
        scrim.visibility = View.GONE
        scrim.setOnClickListener { close() }

        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** [animate] false: shown at once, as when the screen restarts in the other theme. */
    fun open(animate: Boolean = true) {
        val tab = TabManager.current ?: return
        if (isOpen) return
        isOpen = true
        update(tab)
        pager.reset()
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
        isOpen = false
        scrim.animate().cancel()
        panel.animate().cancel()
        val h = panelHeight()
        val remaining = ((h - panel.translationY) / h).coerceIn(0.2f, 1f)
        scrim.animate().alpha(0f).setDuration((140 * remaining).toLong()).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationY(h).setDuration((140 * remaining).toLong()).setInterpolator(AccelerateInterpolator())
            .withLayer().withEndAction { panel.visibility = View.GONE }.start()
    }

    /** Refreshes what depends on the page; also after a switch changes something. */
    private fun update(tab: Tab) {
        val http = UrlUtils.isHttp(tab.url)
        val page = !tab.isHome && tab.session != null
        val inReader = Helper.isReaderUrl(tab.url)

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
        lite.set(prefs.liteMode)
        dark.set(prefs.darkMode)
        desktop.set(tab.desktop, enabled = !tab.isHome)
        fullScreen.set(prefs.fullScreen)

        reader.set(R.drawable.ic_reader, if (inReader) "Exit reader" else "Reader view", active = inReader, enabled = (http && page) || inReader)
        save.set(R.drawable.ic_offline, "Save offline", enabled = http && page)
        homeScreen.set(R.drawable.ic_home_add, "Add to Home", enabled = http)
        val level = AdBlock.levelFor(tab.url)
        val blocked = tab.badge?.takeIf { level != AdLevel.OFF && it.isNotBlank() && it != "0" }
        adBlock.set(
            if (level == AdLevel.OFF) R.drawable.ic_shield_off else R.drawable.ic_shield,
            if (blocked != null) "$blocked blocked" else "Ad blocker",
        )
        speed.set(R.drawable.ic_speed, "Speed ${a.formatSpeed(tab.speed)}")
        closeAll.set(R.drawable.ic_close, if (tab.incognito) "Close incognito" else "Close all tabs")
        addToGroup.set(R.drawable.ic_layers, if (tab.groupId != null) "Move to group" else "Add to group")
    }

    private fun toggleBookmark(tab: Tab) {
        val pid = tab.profileId
        val url = tab.url
        if (SwiftApp.db.isBookmarked(pid, url)) {
            TabManager.io.execute { SwiftApp.db.removeBookmark(pid, url) }
            a.snack("Bookmark removed")
        } else {
            val title = tab.title
            TabManager.io.execute { SwiftApp.db.addBookmark(pid, url, title) }
            a.snack("Bookmarked")
        }
    }

    private fun confirmCloseAll(tab: Tab) {
        val incognito = tab.incognito
        MaterialAlertDialogBuilder(a)
            .setMessage(if (incognito) "Close all incognito tabs?" else "Close all tabs in ${Profiles.current().name}?")
            .setPositiveButton("Close all") { _, _ -> TabManager.closeAll(incognito) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The panel's height, measured on the first opening (before it has been laid out). */
    private fun panelHeight(): Float {
        if (panel.height > 0) return panel.height.toFloat()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return panel.measuredHeight.toFloat()
    }

    private fun showPage(index: Int) {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(if (i == index) R.color.accent else R.color.outline))
            }
        }
    }

    // ------------------------------------------------------------------ the panel and its pages

    /** The menu itself; a downward swipe anywhere on it drags it down and closes it. */
    private inner class Panel : LinearLayout(a) {
        private val slop = ViewConfiguration.get(a).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var velocity: VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (dy > slop && dy > abs(ev.rawX - downX) * 1.2f) {
                        startDrag(ev)
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
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
        private var page = 0

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        }

        fun addPage(page: View) {
            strip.addView(page, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

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

        private fun goTo(target: Int) {
            page = target.coerceIn(0, (strip.childCount - 1).coerceAtLeast(0))
            smoothScrollTo(page * width, 0)
            showPage(page)
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
        addView(ImageButton(a).apply {
            setImageResource(R.drawable.ic_copy)
            imageTintList = ColorStateList.valueOf(color(R.color.text_secondary))
            setBackgroundResource(borderless())
            contentDescription = "Copy link"
            setOnClickListener {
                val tab = TabManager.current ?: return@setOnClickListener
                if (UrlUtils.isHttp(tab.url)) {
                    close()
                    a.copy(tab.url)
                }
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
                view.imageTintList = ColorStateList.valueOf(color(if (active) R.color.accent else R.color.text_primary))
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

    /** A switch tile: an icon over its name, filled with the accent colour when on. Tapping it leaves the menu open. */
    private inner class Toggle(val view: TextView, val onChange: (Boolean) -> Unit) {
        var on = false
            private set
        private val glyph = view.compoundDrawablesRelative[1]
        private val fill = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }

        init {
            view.background = fill
        }

        fun set(on: Boolean, enabled: Boolean = true) {
            this.on = on
            fill.setColor(color(if (on) R.color.accent_container else R.color.surface_alt))
            val tint = color(if (on) R.color.on_accent_container else R.color.text_primary)
            view.setTextColor(tint)
            glyph?.setTint(tint)
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.38f
        }
    }

    private fun toggle(row: LinearLayout, icon: Int, label: String, onChange: (Boolean) -> Unit): Toggle {
        val view = TextView(a).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setPadding(dp(4), dp(10), dp(4), dp(8))
            compoundDrawablePadding = dp(5)
            val d = ContextCompat.getDrawable(a, icon)!!.mutate()
            d.setBounds(0, 0, dp(22), dp(22))
            setCompoundDrawablesRelative(null, d, null, null)
            foreground = ContextCompat.getDrawable(a, RowHolder.selectableBackground(a))
        }
        row.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (row.childCount > 0) marginStart = dp(8)
        })
        val t = Toggle(view, onChange)
        t.set(false)
        view.setOnClickListener {
            if (!view.isEnabled) return@setOnClickListener
            val on = !t.on
            t.set(on)
            t.onChange(on)
        }
        return t
    }

    /** A page of the pager: 4 columns, 2 rows (always two rows high, so the pages don't jump in size). */
    private fun page() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        minimumHeight = dp(2 * 84)
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
            if (view.isEnabled != enabled) {
                view.isEnabled = enabled
                view.alpha = if (enabled) 1f else 0.38f
            }
        }
    }

    private fun item(page: LinearLayout, icon: Int, label: String, action: (Tab) -> Unit): Item {
        var row = page.getChildAt(page.childCount - 1) as? LinearLayout
        if (row == null || row.childCount == 4) {
            row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            page.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val view = TextView(a).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            compoundDrawablePadding = dp(6)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_primary))
            setBackgroundResource(borderless())
        }
        row.addView(view, LinearLayout.LayoutParams(0, dp(84), 1f))
        val item = Item(view, action)
        item.set(icon, label)
        view.setOnClickListener {
            val tab = TabManager.current ?: return@setOnClickListener
            close()
            item.action(tab)
        }
        return item
    }

    private fun roundIcon(icon: Int, active: Boolean): Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(if (active) R.color.accent_container else R.color.surface_alt))
        }
        val glyph = ContextCompat.getDrawable(a, icon)!!.mutate()
        glyph.setTint(color(if (active) R.color.on_accent_container else R.color.text_primary))
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerSize(0, dp(46), dp(46))
            setLayerSize(1, dp(22), dp(22))
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
