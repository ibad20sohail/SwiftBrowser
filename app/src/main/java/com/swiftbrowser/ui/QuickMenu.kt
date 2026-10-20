package com.swiftbrowser.ui

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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

/**
 * The ⋮ menu, sliding up from the bottom: the page it is about, then rows of buttons that scroll
 * sideways (the page, how it is shown, the app). It is built once and lives in the browser screen
 * itself (a dialog would make a new window every time, which takes a moment on this phone); opening
 * it only updates what depends on the page. Everything else about the app is in Settings.
 */
class QuickMenu(private val a: MainActivity, private val root: FrameLayout) {

    var isOpen = false
        private set

    private val scrim = View(a)
    private val panel = LinearLayout(a)
    private val pageRow = row()
    private val viewRow = row()
    private val appRow = row()

    private val headerIcon = ImageView(a)
    private val headerTitle = TextView(a)
    private val headerSite = TextView(a)

    private val forward: Item
    private val reload: Item
    private val bookmark: Item
    private val share: Item
    private val find: Item
    private val reader: Item
    private val save: Item
    private val homeScreen: Item
    private val lite: Item
    private val desktop: Item
    private val adBlock: Item
    private val speed: Item
    private val dark: Item
    private val closeAll: Item

    init {
        forward = item(pageRow, R.drawable.ic_forward, "Forward") { TabManager.goForward(it) }
        reload = item(pageRow, R.drawable.ic_refresh, "Reload") { if (it.loading) it.session?.stop() else TabManager.reload(it) }
        bookmark = item(pageRow, R.drawable.ic_star_border, "Bookmark") { toggleBookmark(it) }
        share = item(pageRow, R.drawable.ic_share, "Share") { a.share(it.url) }
        find = item(pageRow, R.drawable.ic_find, "Find in page") { a.openFind() }
        reader = item(pageRow, R.drawable.ic_reader, "Reader view") { PageActions.reader(a, it) }
        save = item(pageRow, R.drawable.ic_offline, "Save offline") { PageActions.savePage(a, it) }
        homeScreen = item(pageRow, R.drawable.ic_home_add, "Add to Home") { PageActions.addToHomeScreen(a, it) }

        lite = item(viewRow, R.drawable.ic_lite, "Lite mode") { PageActions.toggleLite(a, it) }
        desktop = item(viewRow, R.drawable.ic_desktop, "Desktop site") { TabManager.setDesktop(it, !it.desktop) }
        adBlock = item(viewRow, R.drawable.ic_shield, "Ad blocker") { Sheets.shield(a) }
        speed = item(viewRow, R.drawable.ic_speed, "Speed") { Sheets.speed(a, it) }
        dark = item(viewRow, R.drawable.ic_dark, "Dark mode") { Sheets.setDarkMode(!SwiftApp.prefs.darkMode) }

        item(appRow, R.drawable.ic_add, "New tab") { TabManager.newTab(incognito = false) }
        item(appRow, R.drawable.ic_incognito, "Incognito tab") { TabManager.newTab(incognito = true) }
        item(appRow, R.drawable.ic_bookmarks, "Bookmarks") { a.openList(BookmarksActivity::class.java) }
        item(appRow, R.drawable.ic_history, "History") { a.openList(HistoryActivity::class.java) }
        item(appRow, R.drawable.ic_download, "Downloads") { a.openDownloads() }
        closeAll = item(appRow, R.drawable.ic_close, "Close all tabs") { confirmCloseAll(it) }
        item(appRow, R.drawable.ic_settings, "Settings") { a.openList(SettingsActivity::class.java) }

        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(color(R.color.sheet_bg))
            val r = dp(24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.elevation = dp(16).toFloat()
        panel.setPadding(0, dp(8), 0, dp(10))
        // Touches between the buttons stay in the menu instead of reaching the page.
        panel.isClickable = true
        panel.visibility = View.GONE
        panel.addView(View(a).apply {
            background = GradientDrawable().apply {
                setColor(color(R.color.outline))
                cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(6)
        })
        panel.addView(header())
        panel.addView(divider())
        panel.addView(scroller(pageRow))
        panel.addView(scroller(viewRow))
        panel.addView(divider())
        panel.addView(scroller(appRow))

        scrim.setBackgroundColor(color(R.color.scrim))
        scrim.elevation = panel.elevation
        scrim.outlineProvider = null
        scrim.visibility = View.GONE
        scrim.setOnClickListener { close() }

        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun open() {
        val tab = TabManager.current ?: return
        if (isOpen) return
        isOpen = true
        update(tab)
        (pageRow.parent as View).scrollTo(0, 0)
        (viewRow.parent as View).scrollTo(0, 0)
        (appRow.parent as View).scrollTo(0, 0)
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        panel.translationY = panelHeight()
        panel.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()
        panel.animate().translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.animate().alpha(0f).setDuration(140).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationY(panelHeight()).setDuration(140).setInterpolator(AccelerateInterpolator())
            .withLayer().withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun update(tab: Tab) {
        val http = UrlUtils.isHttp(tab.url)
        val page = !tab.isHome && tab.session != null
        val inReader = Helper.isReaderUrl(tab.url)

        headerTitle.text = when {
            tab.isHome -> if (tab.incognito) "Incognito tab" else "New tab"
            else -> tab.displayTitle
        }
        headerSite.text = when {
            inReader -> "Reader view"
            http -> UrlUtils.host(tab.url)?.removePrefix("www.").orEmpty()
            else -> UrlUtils.display(tab.url)
        }
        headerSite.visibility = if (headerSite.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (tab.isHome && !tab.incognito) {
            headerIcon.setImageResource(R.drawable.ic_launcher)
            headerIcon.imageTintList = null
        } else {
            headerIcon.setImageResource(if (tab.incognito) R.drawable.ic_incognito else R.drawable.ic_globe)
            headerIcon.imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_secondary))
        }
        if (http && !tab.incognito) {
            Favicons.load(tab.url) { icon ->
                if (icon != null && isOpen && TabManager.current === tab) {
                    headerIcon.imageTintList = null
                    headerIcon.setImageBitmap(icon)
                }
            }
        }

        forward.set(R.drawable.ic_forward, "Forward", enabled = tab.canGoForward)
        if (tab.loading) reload.set(R.drawable.ic_close, "Stop")
        else reload.set(R.drawable.ic_refresh, "Reload", enabled = !tab.isBlank)
        val bookmarked = http && SwiftApp.db.isBookmarked(tab.profileId, tab.url)
        bookmark.set(
            if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border,
            if (bookmarked) "Bookmarked" else "Bookmark", active = bookmarked, enabled = http,
        )
        share.set(R.drawable.ic_share, "Share", enabled = http)
        find.set(R.drawable.ic_find, "Find in page", enabled = page)
        reader.set(R.drawable.ic_reader, if (inReader) "Exit reader" else "Reader view", active = inReader, enabled = (http && page) || inReader)
        save.set(R.drawable.ic_offline, "Save offline", enabled = http && page)
        homeScreen.set(R.drawable.ic_home_add, "Add to Home", enabled = http)

        lite.set(R.drawable.ic_lite, "Lite mode", active = PageActions.isLite(tab), enabled = http)
        desktop.set(R.drawable.ic_desktop, "Desktop site", active = tab.desktop, enabled = !tab.isHome)
        val level = AdBlock.levelFor(tab.url)
        val blocked = tab.badge?.takeIf { level != AdLevel.OFF && it.isNotBlank() && it != "0" }
        adBlock.set(
            if (level == AdLevel.OFF) R.drawable.ic_shield_off else R.drawable.ic_shield,
            if (blocked != null) "$blocked blocked" else "Ad blocker",
        )
        speed.set(R.drawable.ic_speed, "Speed ${a.formatSpeed(tab.speed)}")
        dark.set(R.drawable.ic_dark, "Dark mode", active = SwiftApp.prefs.darkMode)
        closeAll.set(R.drawable.ic_close, if (tab.incognito) "Close incognito" else "Close all tabs")
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

    // ------------------------------------------------------------------ building

    /** The page the menu is about; tapping it copies the address. */
    private fun header(): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(10), dp(10))
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
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_secondary))
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

    /** A button: a round icon with its name below, all in one view so the rows stay light. */
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

    private fun item(row: LinearLayout, icon: Int, label: String, action: (Tab) -> Unit): Item {
        val view = TextView(a).apply {
            layoutParams = LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(6))
            compoundDrawablePadding = dp(6)
            textSize = 11.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_primary))
            setBackgroundResource(borderless())
        }
        val item = Item(view, action)
        item.set(icon, label)
        view.setOnClickListener {
            val tab = TabManager.current ?: return@setOnClickListener
            close()
            item.action(tab)
        }
        row.addView(view)
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

    private fun row() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun scroller(row: LinearLayout) = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }

    private fun divider() = View(a).apply {
        setBackgroundColor(color(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(18), dp(2), dp(18), dp(4))
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
