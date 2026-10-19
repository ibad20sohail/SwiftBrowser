package com.swiftbrowser.ui

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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager

/**
 * The ⋮ menu: a row of buttons for the page and a row for the app, both scrolling sideways, sliding
 * up from the bottom. It is built once and lives in the browser screen itself (a dialog would make
 * a new window every time, which takes a moment on this phone); opening it only updates the buttons
 * that depend on the page. Everything else about the app is in Settings.
 */
class QuickMenu(private val a: MainActivity, private val root: FrameLayout) {

    var isOpen = false
        private set

    private val scrim = View(a)
    private val panel = LinearLayout(a)
    private val pageRow = row()
    private val appRow = row()

    private val forward: Item
    private val reload: Item
    private val bookmark: Item
    private val share: Item
    private val find: Item
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
        desktop = item(pageRow, R.drawable.ic_desktop, "Desktop site") { TabManager.setDesktop(it, !it.desktop) }
        adBlock = item(pageRow, R.drawable.ic_shield, "Ad blocker") { Sheets.shield(a) }
        speed = item(pageRow, R.drawable.ic_speed, "Speed") { Sheets.speed(a, it) }

        item(appRow, R.drawable.ic_add, "New tab") { TabManager.newTab(incognito = false) }
        item(appRow, R.drawable.ic_incognito, "Incognito tab") { TabManager.newTab(incognito = true) }
        item(appRow, R.drawable.ic_bookmarks, "Bookmarks") { a.openList(BookmarksActivity::class.java) }
        item(appRow, R.drawable.ic_history, "History") { a.openList(HistoryActivity::class.java) }
        item(appRow, R.drawable.ic_download, "Downloads") { a.openDownloads() }
        dark = item(appRow, R.drawable.ic_dark, "Dark mode") { Sheets.setDarkMode(!SwiftApp.prefs.darkMode) }
        closeAll = item(appRow, R.drawable.ic_close, "Close all tabs") { confirmCloseAll(it) }
        item(appRow, R.drawable.ic_settings, "Settings") { a.openList(SettingsActivity::class.java) }

        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(color(R.color.card_bg))
            val r = dp(16).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        panel.elevation = dp(16).toFloat()
        panel.setPadding(0, dp(10), 0, dp(8))
        // Touches between the buttons stay in the menu instead of reaching the page.
        panel.isClickable = true
        panel.visibility = View.GONE
        panel.addView(View(a).apply {
            background = GradientDrawable().apply {
                setColor(color(R.color.divider))
                cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })
        panel.addView(scroller(pageRow))
        panel.addView(View(a).apply { setBackgroundColor(color(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(16), dp(2), dp(16), dp(2))
            })
        panel.addView(scroller(appRow))

        scrim.setBackgroundColor(0x66000000)
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
        (appRow.parent as View).scrollTo(0, 0)
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        panel.translationY = panelHeight()
        panel.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.animate().alpha(0f).setDuration(150).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationY(panelHeight()).setDuration(150).setInterpolator(AccelerateInterpolator())
            .withLayer().withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun update(tab: Tab) {
        val http = UrlUtils.isHttp(tab.url)
        forward.set(R.drawable.ic_forward, "Forward", enabled = tab.canGoForward)
        if (tab.loading) reload.set(R.drawable.ic_close, "Stop")
        else reload.set(R.drawable.ic_refresh, "Reload", enabled = !tab.isBlank)
        val bookmarked = http && SwiftApp.db.isBookmarked(tab.profileId, tab.url)
        bookmark.set(
            if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border,
            if (bookmarked) "Bookmarked" else "Bookmark", active = bookmarked, enabled = http,
        )
        share.set(R.drawable.ic_share, "Share", enabled = http)
        find.set(R.drawable.ic_find, "Find in page", enabled = !tab.isHome && tab.session != null)
        desktop.set(R.drawable.ic_desktop, "Desktop site", active = tab.desktop, enabled = !tab.isHome)
        val level = AdBlock.levelFor(tab.url)
        val blocked = tab.badge?.takeIf { level != AdLevel.OFF && it.isNotBlank() && it != "0" }
        adBlock.set(
            if (level == AdLevel.OFF) R.drawable.ic_shield_off else R.drawable.ic_shield,
            if (blocked != null) "$blocked blocked" else "Ad blocker",
        )
        speed.set(R.drawable.ic_speed, "Speed ${a.formatSpeed(tab.speed)}")
        dark.set(R.drawable.ic_dark, "Dark mode", active = SwiftApp.prefs.darkMode)
        closeAll.set(R.drawable.ic_close, if (tab.incognito) "Close incognito tabs" else "Close all tabs")
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
            layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(8))
            compoundDrawablePadding = dp(6)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text_primary))
            setBackgroundResource(RowHolder.selectableBackground(a))
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
            setColor(color(if (active) R.color.accent else R.color.surface_alt))
        }
        val glyph = ContextCompat.getDrawable(a, icon)!!.mutate()
        glyph.setTint(color(if (active) R.color.on_accent else R.color.text_primary))
        return LayerDrawable(arrayOf(circle, glyph)).apply {
            setLayerSize(0, dp(48), dp(48))
            setLayerSize(1, dp(24), dp(24))
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

    private fun color(res: Int) = ContextCompat.getColor(a, res)

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
