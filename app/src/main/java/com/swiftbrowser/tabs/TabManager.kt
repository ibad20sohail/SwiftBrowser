package com.swiftbrowser.tabs

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * All tabs of all profiles plus the incognito tabs. Only a few tabs keep a live engine session
 * (see Prefs.maxLiveTabs); the rest are "suspended": their history and scroll position are kept
 * and restored on demand. Tabs showing the home page have no session at all.
 */
object TabManager {
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dirty = LinkedHashSet<Tab>()
    private val saveRunnable = Runnable { flushSaves() }
    private var nextIncognitoId = -1L

    /**
     * How long a page waits for uBlock Origin's filters at app start before loading anyway: pages
     * the user just asked for wait briefly, pages restored at start a little longer.
     */
    private const val USER_WAIT_MS = 3000L
    private const val RESTORE_WAIT_MS = 8000L

    private fun adblockWait(tab: Tab) =
        if (System.currentTimeMillis() - tab.userNavAt < 2000) USER_WAIT_MS else RESTORE_WAIT_MS

    val tabs = ArrayList<Tab>()

    var current: Tab? = null
        private set

    var ui: BrowserUi? = null

    fun init(db: Db, prefs: Prefs) {
        this.db = db
        this.prefs = prefs
        tabs.clear()
        val validProfiles = Profiles.all().map { it.id }.toSet()
        for (r in db.tabs()) {
            if (r.profileId !in validProfiles) {
                db.deleteTab(r.id)
                continue
            }
            tabs.add(Tab(r.id, r.profileId, r.url, r.title, r.state, r.parentId, r.lastUsed, r.desktop))
        }
        L.d("Restored ${tabs.size} tabs")
    }

    fun profileTabs(profileId: Long = Profiles.currentId): List<Tab> =
        tabs.filter { !it.incognito && it.profileId == profileId }

    fun incognitoTabs(): List<Tab> = tabs.filter { it.incognito }

    /** The tabs listed together with [tab]: its profile's tabs, or the incognito tabs. */
    fun groupOf(tab: Tab): List<Tab> = if (tab.incognito) incognitoTabs() else profileTabs(tab.profileId)

    fun bySession(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }

    /** The tab to show for the current profile; creates a blank one if the profile has none. */
    fun ensureCurrent(): Tab {
        val pid = Profiles.currentId
        current?.let { if (tabs.contains(it) && (it.incognito || it.profileId == pid)) return it }
        val tab = profileTabs(pid).maxByOrNull { it.lastUsed } ?: createTab("about:blank", pid, null, false, false)
        select(tab)
        return tab
    }

    private fun createTab(url: String, profileId: Long, parent: Tab?, desktop: Boolean, incognito: Boolean): Tab {
        val now = System.currentTimeMillis()
        val id = if (incognito) nextIncognitoId-- else db.insertTab(profileId, url, parent?.id, now, desktop)
        val tab = Tab(id, profileId, url, "", null, parent?.id, now, desktop, incognito)
        val parentIndex = if (parent != null) tabs.indexOf(parent) else -1
        if (parentIndex >= 0) tabs.add(parentIndex + 1, tab) else tabs.add(tab)
        return tab
    }

    fun newTab(
        url: String = "about:blank",
        parent: Tab? = null,
        select: Boolean = true,
        incognito: Boolean = parent?.incognito ?: false,
        profileId: Long = parent?.profileId ?: Profiles.currentId,
    ): Tab {
        val tab = createTab(url, profileId, parent, parent?.desktop ?: false, incognito)
        if (!tab.isBlank) tab.userNavAt = System.currentTimeMillis()
        if (select) {
            select(tab)
        } else {
            ui?.onTabsChanged()
            ui?.onTabOpenedInBackground(tab)
        }
        return tab
    }

    fun select(tab: Tab) {
        val previous = current
        if (previous !== tab) {
            previous?.session?.let { s ->
                if (s.isOpen) {
                    s.setActive(false)
                    s.setFocused(false)
                    Engine.runtime.webExtensionController.setTabActive(s, false)
                }
            }
        }
        current = tab
        tab.lastUsed = System.currentTimeMillis()
        scheduleSave(tab)
        if (!tab.isHome) {
            tab.wokeUp = tab.session == null && (tab.sessionState != null || tab.state != null)
            val session = ensureLive(tab)
            if (session.isOpen) activate(session)
        }
        ui?.onTabSelected(tab)
        ui?.onTabsChanged()
        trimLiveSessions()
    }

    private fun activate(session: GeckoSession) {
        session.setActive(true)
        session.setFocused(true)
        Engine.runtime.webExtensionController.setTabActive(session, true)
    }

    /** Returns the tab's session, creating and restoring it if the tab was suspended. */
    fun ensureLive(tab: Tab): GeckoSession {
        tab.session?.let { s ->
            if (s.isOpen) return s
            // Closed behind our back (e.g. its process died): start over.
            detachSession(tab)
        }
        val session = SessionFactory.create(tab)
        session.open(Engine.runtime)
        tab.crashed = false
        if (tab === current) activate(session)
        val state = tab.sessionState ?: tab.state?.let { GeckoSession.SessionState.fromString(it) }
        if (state != null || !tab.isBlank) {
            // Show progress right away, even while uBlock Origin is still starting.
            tab.loading = true
            tab.progress = 5
            AdBlock.whenReady(adblockWait(tab)) {
                if (tab.session !== session || !session.isOpen) return@whenReady
                if (state != null) session.restoreState(state) else session.loadUri(tab.url)
            }
        }
        return session
    }

    private fun detachSession(tab: Tab) {
        val session = tab.session ?: return
        ui?.releaseSession(session)
        try {
            session.close()
        } catch (e: Exception) {
            L.w("close failed", e)
        }
        tab.session = null
        tab.loading = false
        tab.playing = false
        tab.mediaSession = null
        tab.uboAction = null
        Helper.detachTab(tab)
    }

    private fun trimLiveSessions() {
        val max = prefs.maxLiveTabs
        val others = tabs.filter { it.session != null && it !== current }.sortedBy { it.lastUsed }
        var live = others.size + if (current?.session != null) 1 else 0
        for (t in others) {
            if (live <= max) break
            if (t.playing) continue
            suspend(t)
            live--
        }
    }

    /** Frees a background tab's engine session; it is restored when the tab is opened again. */
    fun suspend(tab: Tab) {
        if (tab === current) return
        detachSession(tab)
    }

    fun suspendBackgroundTabs() {
        tabs.filter { it.session != null && it !== current && !it.playing }.forEach { suspend(it) }
    }

    /** The tab closed last from the tab grid, until the next one is closed; see [undoClose]. */
    private class Closed(val tab: Tab, val index: Int, val wasCurrent: Boolean, val replacement: Tab?)

    private var lastClosed: Closed? = null

    /** Closes [tab]. An [undoable] close (from the tab grid) can be taken back with [undoClose]. */
    fun close(tab: Tab, undoable: Boolean = false) {
        val wasCurrent = tab === current
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        detachSession(tab)
        dirty.remove(tab)
        forgetClosed()
        if (!undoable) Thumbnails.remove(tab)
        if (!tab.incognito) io.execute { db.deleteTab(tab.id) }
        var replacement: Tab? = null
        if (wasCurrent) {
            current = null
            val before = tabs.size
            val next = nextAfterClosing(tab)
            if (tabs.size > before) replacement = next
            select(next)
        } else {
            ui?.onTabsChanged()
        }
        if (undoable) {
            lastClosed = Closed(tab, index, wasCurrent, replacement)
            ui?.onTabClosed(tab)
        }
    }

    /** Brings back the tab closed last from the tab grid, with its history. */
    fun undoClose(): Tab? {
        val c = lastClosed ?: return null
        lastClosed = null
        val old = c.tab
        if (Profiles.all().none { it.id == old.profileId }) return null
        val now = System.currentTimeMillis()
        val id = if (old.incognito) nextIncognitoId-- else db.insertTab(old.profileId, old.url, old.parentId, now, old.desktop)
        val tab = Tab(id, old.profileId, old.url, old.title, old.state, old.parentId, now, old.desktop, old.incognito)
        tab.sessionState = old.sessionState
        tab.speed = old.speed
        tabs.add(c.index.coerceIn(0, tabs.size), tab)
        Thumbnails.move(old, tab)
        scheduleSave(tab)
        if (c.wasCurrent) select(tab) else ui?.onTabsChanged()
        // The blank tab opened because the last tab had closed goes again if it wasn't used.
        c.replacement?.let { r -> if (r in tabs && r.isHome) close(r) }
        return tab
    }

    private fun forgetClosed() {
        lastClosed?.let { Thumbnails.remove(it.tab) }
        lastClosed = null
    }

    private fun nextAfterClosing(tab: Tab): Tab {
        val siblings = groupOf(tab)
        siblings.firstOrNull { it.id == tab.parentId }?.let { return it }
        siblings.maxByOrNull { it.lastUsed }?.let { return it }
        // The last incognito tab closed: back to the normal tabs.
        val pid = Profiles.currentId
        return profileTabs(pid).maxByOrNull { it.lastUsed } ?: createTab("about:blank", pid, null, false, false)
    }

    fun closeAll(incognito: Boolean) {
        val group = if (incognito) incognitoTabs() else profileTabs()
        for (t in group) if (t !== current) close(t)
        current?.let { if (it in group) close(it) }
    }

    /** Navigates [tab] to [url] on the user's behalf. */
    fun load(tab: Tab, url: String) {
        tab.userNavAt = System.currentTimeMillis()
        if (tab.isHome) tab.leftHome = true
        // A blank tab gets an empty session here; the address is set after, so it loads only once.
        val session = ensureLive(tab)
        if (tab.isBlank) tab.url = url
        tab.loading = true
        tab.progress = 5
        AdBlock.whenReady(USER_WAIT_MS) {
            if (tab.session === session && session.isOpen) session.loadUri(url)
        }
        notifyTabUpdated(tab)
    }

    /** Back on the first page of a tab opened from the home page shows the home page again. */
    fun returnHome(tab: Tab) {
        detachSession(tab)
        tab.url = "about:blank"
        tab.title = ""
        tab.state = null
        tab.sessionState = null
        tab.leftHome = false
        tab.canGoBack = false
        tab.canGoForward = false
        tab.progress = 100
        tab.badge = null
        tab.media.clear()
        scheduleSave(tab)
        if (tab === current) ui?.onTabSelected(tab)
        ui?.onTabsChanged()
    }

    fun reload(tab: Tab) {
        tab.userNavAt = System.currentTimeMillis()
        val session = tab.session
        if (session == null || !session.isOpen) {
            if (!tab.isHome) select(tab)
            return
        }
        session.reload()
    }

    fun goBack(tab: Tab) {
        tab.userNavAt = System.currentTimeMillis()
        tab.session?.goBack()
    }

    fun goForward(tab: Tab) {
        tab.userNavAt = System.currentTimeMillis()
        tab.session?.goForward()
    }

    /** Re-creates the current tab after its content process crashed or was killed. */
    fun revive(tab: Tab) {
        tab.crashed = false
        tab.userNavAt = System.currentTimeMillis()
        if (tab === current) {
            if (!tab.isHome) ensureLive(tab)
            ui?.onTabSelected(tab)
        }
    }

    fun setDesktop(tab: Tab, desktop: Boolean) {
        tab.desktop = desktop
        tab.session?.settings?.let { s ->
            s.setUserAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            s.setViewportMode(
                if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
            )
        }
        reload(tab)
        scheduleSave(tab)
    }

    // ------------------------------------------------------------------ new windows

    /** Web content called window.open() or followed a target=_blank link, and the policy allowed it. */
    fun openPopup(opener: Tab, uri: String): GeckoSession {
        val tab = createTab(uri, opener.profileId, opener, opener.desktop, opener.incognito)
        tab.approvedUrl = uri
        tab.lastClick = opener.lastClick
        val session = SessionFactory.create(tab) // Gecko opens it itself
        select(tab)
        return session
    }

    /** An extension (uBlock Origin) opened a page such as its dashboard or logger. */
    fun openForExtension(url: String?): GeckoSession {
        val tab = createTab(url ?: "about:blank", Profiles.currentId, current, false, false)
        val session = SessionFactory.create(tab)
        select(tab)
        return session
    }

    // ------------------------------------------------------------------ profiles

    fun switchProfile(id: Long) {
        if (id == Profiles.currentId && current?.profileId == id && current?.incognito == false) return
        val previous = current
        Profiles.setCurrent(id)
        val target = profileTabs(id).maxByOrNull { it.lastUsed } ?: createTab("about:blank", id, null, false, false)
        select(target)
        // Other profiles' pages don't need to stay in memory.
        tabs.filter { !it.incognito && it.profileId != id && it.session != null }.forEach { suspend(it) }
        if (previous != null && !previous.incognito && previous.profileId != id) suspend(previous)
        ui?.onTabsChanged()
    }

    fun deleteProfile(id: Long) {
        if (Profiles.all().size <= 1) return
        for (t in profileTabs(id)) {
            detachSession(t)
            tabs.remove(t)
            dirty.remove(t)
            Thumbnails.remove(t)
        }
        Engine.runtime.storageController.clearDataForSessionContext(Profiles.contextId(id))
        Profiles.delete(id)
        if (current?.let { !it.incognito && it.profileId == id } == true) current = null
        ensureCurrent()
        ui?.onTabsChanged()
    }

    // ------------------------------------------------------------------ extensions

    fun onExtensionReady() {
        for (t in tabs) {
            val s = t.session ?: continue
            SessionFactory.attachExtensions(t, s)
        }
        AdBlock.extension?.let { ext ->
            // Opening uBO's panel (Action.click) goes to the extension-wide delegate.
            ext.setActionDelegate(UboActionDelegate(null))
            ext.tabDelegate = object : WebExtension.TabDelegate {
                override fun onNewTab(
                    source: WebExtension, createDetails: WebExtension.CreateTabDetails,
                ) = GeckoResult.fromValue(openForExtension(createDetails.url))

                override fun onOpenOptionsPage(source: WebExtension) {
                    source.metaData.optionsPageUrl?.let { newTab(it, incognito = false) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ history & persistence

    fun recordVisit(tab: Tab, url: String) {
        if (tab.incognito) return
        val pid = tab.profileId
        io.execute { db.recordVisit(pid, url, "") }
    }

    fun setHistoryTitle(tab: Tab, url: String, title: String) {
        if (tab.incognito) return
        val pid = tab.profileId
        io.execute { db.setHistoryTitle(pid, url, title) }
    }

    fun notifyTabUpdated(tab: Tab) {
        ui?.onTabUpdated(tab)
    }

    fun scheduleSave(tab: Tab) {
        if (tab.incognito) return
        dirty.add(tab)
        if (dirty.size == 1) {
            main.removeCallbacks(saveRunnable)
            main.postDelayed(saveRunnable, 2000)
        }
    }

    private class SaveRow(
        val id: Long, val url: String, val title: String,
        val sessionState: GeckoSession.SessionState?, val state: String?, val lastUsed: Long, val desktop: Boolean,
    )

    fun flushSaves() {
        main.removeCallbacks(saveRunnable)
        if (dirty.isEmpty()) return
        val rows = dirty.filter { tabs.contains(it) && !it.incognito }.map { t ->
            SaveRow(t.id, t.url, t.title, t.sessionState, t.state, t.lastUsed, t.desktop)
        }
        dirty.clear()
        io.execute {
            for (r in rows) {
                // Turning the engine's state into text can take a while for long histories.
                val state = r.sessionState?.toString() ?: r.state
                db.updateTab(r.id, r.url, r.title, state, r.lastUsed, r.desktop)
            }
        }
    }
}
