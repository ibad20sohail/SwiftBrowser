package com.swiftbrowser.ui

import android.view.MenuItem
import com.swiftbrowser.core.SearchEngine
import com.swiftbrowser.media.ReadAloud
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoSession

/**
 * The bar over selected text (2.6): the engine's Copy, Paste and Select all, with Search, Share and
 * Listen after Copy. Swift's own, set on each page it shows, so it goes away when the page changes
 * or another tab shows (the engine's bar could stay on screen over other pages).
 */
class SelectionMenu(private val a: MainActivity) : BasicSelectionActionDelegate(a, true) {
    private companion object {
        const val SEARCH = "com.swiftbrowser.SEARCH"
        const val SHARE = "com.swiftbrowser.SHARE"
        const val LISTEN = "com.swiftbrowser.LISTEN"
        val OURS = setOf(SEARCH, SHARE, LISTEN)
    }

    /** The same array every time: menu items are numbered by their place in it. */
    private val actions: Array<String> by lazy {
        val base = super.getAllActions().toMutableList()
        val at = base.indexOf(GeckoSession.SelectionActionDelegate.ACTION_COPY).let { if (it < 0) base.size else it + 1 }
        base.addAll(at, listOf(SEARCH, SHARE, LISTEN))
        base.toTypedArray()
    }

    override fun getAllActions(): Array<String> = actions

    private val text: String get() = mSelection?.text?.trim().orEmpty()

    /** Selected text on the page, not in a box being typed in (Cut and Paste belong there) nor a password. */
    private val pageText: Boolean
        get() {
            val typed = GeckoSession.SelectionActionDelegate.FLAG_IS_EDITABLE or GeckoSession.SelectionActionDelegate.FLAG_IS_PASSWORD
            return text.isNotEmpty() && ((mSelection?.flags ?: 0) and typed) == 0
        }

    override fun isActionAvailable(id: String): Boolean = when (id) {
        SEARCH, SHARE -> pageText
        LISTEN -> pageText && text.length > 1
        else -> super.isActionAvailable(id)
    }

    override fun prepareAction(id: String, item: MenuItem) {
        when (id) {
            SEARCH -> item.setTitle("Search")
            SHARE -> item.setTitle("Share")
            LISTEN -> item.setTitle("Listen")
            else -> super.prepareAction(id, item)
        }
        if (id in OURS) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun performAction(id: String, item: MenuItem): Boolean {
        if (id !in OURS) return super.performAction(id, item)
        val words = text
        val tab = TabManager.current
        clearSelection()
        when (id) {
            // A moment for the page to let go of the selection before its tab goes to the back.
            SEARCH -> a.window.decorView.postDelayed({ TabManager.newTab(SearchEngine.current.searchUrl(words.take(500)), parent = tab) }, 150)
            SHARE -> a.share(words)
            LISTEN -> {
                if (tab != null) ReadAloud.speak(a, words, tab.title.ifBlank { "Selected text" }, tab.id, tab.incognito)
                a.snack("Reading aloud. The notification has the controls.")
            }
        }
        return true
    }

    /** Takes the bar away (another tab or page shows). */
    fun dismiss() {
        val mode = mActionMode ?: return
        try {
            mode.finish()
        } catch (_: Exception) {
        }
        mActionMode = null
    }
}
