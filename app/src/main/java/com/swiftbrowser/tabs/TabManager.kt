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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * All tabs of all profiles. Only a few tabs keep a live engine session (see Prefs.maxLiveTabs);
 * the rest are "suspended": their history and scroll position are saved and restored on demand.
 */
object TabManager {
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dirty = LinkedHashSet<Tab>()
    private val saveRunnable = Runnable { flushSaves() }

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

    fun profileTabs(profileId: Long = Profiles.currentId): List<Tab> = tabs.filter { it.profileId == profileId }

    fun bySession(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }

    /** The tab to show for the current profile; creates a blank one if the profile has none. */
    fun ensureCurrent(): Tab {
        val pid = Profiles.currentId
        current?.let { if (it.profileId == pid && tabs.contains(it)) return it }
        val tab = profileTabs(pid).maxByOrNull { it.lastUsed } ?: createTab("about:blank", pid, null, false)
        select(tab)
        return tab
    }

    private fun createTab(url: String, profileId: Long, parent: Tab?, desktop: Boolean): Tab {
        val now = System.currentTimeMillis()
        val id = db.insertTab(profileId, url, parent?.id, now, desktop)
        val tab = Tab(id, profileId, url, "", null, parent?.id, now, desktop)
        val parentIndex = if (parent != null) tabs.indexOf(parent) else -1
        if (parentIndex >= 0) tabs.add(parentIndex + 1, tab) else tabs.add(tab)
        return tab
    }

    fun newTab(
        url: String = "about:blank",
        parent: Tab? = null,
        select: Boolean = true,
        profileId: Long = parent?.profileId ?: Profiles.currentId,
    ): Tab {
        val tab = createTab(url, profileId, parent, parent?.desktop ?: false)
        if (select) select(tab) else ui?.onTabsChanged()
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
        val session = ensureLive(tab)
        if (session.isOpen) {
            session.setActive(true)
            session.setFocused(true)
            Engine.runtime.webExtensionController.setTabActive(session, true)
        }
        ui?.onTabSelected(tab)
        ui?.onTabsChanged()
        trimLiveSessions()
    }

    /** Returns the tab's session, creating and restoring it if the tab was suspended. */
    fun ensureLive(tab: Tab): GeckoSession {
        tab.session?.let { return it }
        val session = SessionFactory.create(tab)
        session.open(Engine.runtime)
        tab.crashed = false
        val state = tab.state?.let { GeckoSession.SessionState.fromString(it) }
        if (state != null || !tab.isBlank) {
            AdBlock.whenReady(ADBLOCK_WAIT_MS) {
                if (tab.session !== session) return@whenReady
                if (state != null) session.restoreState(state) else session.loadUri(tab.url)
            }
        }
        return session
    }

    private const val ADBLOCK_WAIT_MS = 12000L

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

    fun suspendBackgroundTabs() {
        tabs.filter { it.session != null && it !== current && !it.playing }.forEach { suspend(it) }
    }

    fun close(tab: Tab) {
        val wasCurrent = tab === current
        tabs.remove(tab)
        tab.session?.let { s ->
            ui?.releaseSession(s)
            try {
                s.close()
            } catch (e: Exception) {
                L.w("close failed", e)
            }
        }
        tab.session = null
        Helper.detachTab(tab)
        dirty.remove(tab)
        io.execute { db.deleteTab(tab.id) }
        if (wasCurrent) {
            current = null
            val siblings = profileTabs(tab.profileId)
            val next = siblings.firstOrNull { it.id == tab.parentId }
                ?: siblings.maxByOrNull { it.lastUsed }
                ?: createTab("about:blank", tab.profileId, null, false)
            select(next)
        } else {
            ui?.onTabsChanged()
        }
    }

    fun closeAll(profileId: Long) {
        for (t in profileTabs(profileId)) if (t !== current) close(t)
        current?.let { if (it.profileId == profileId) close(it) }
    }

    /** Navigates [tab] to [url] on the user's behalf. */
    fun load(tab: Tab, url: String) {
        tab.userNavAt = System.currentTimeMillis()
        val session = ensureLive(tab)
        AdBlock.whenReady(ADBLOCK_WAIT_MS) {
            if (tab.session === session) session.loadUri(url)
        }
        if (tab.isBlank) tab.url = url
        notifyTabUpdated(tab)
    }

    fun reload(tab: Tab) {
        tab.userNavAt = System.currentTimeMillis()
        if (tab.session == null) {
            select(tab)
            return
        }
        tab.session?.reload()
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
            val session = ensureLive(tab)
            ui?.onTabSelected(tab)
            if (session.isOpen) session.setActive(true)
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
        val tab = createTab(uri, opener.profileId, opener, opener.desktop)
        tab.approvedUrl = uri
        tab.lastClick = opener.lastClick
        val session = SessionFactory.create(tab) // Gecko opens it itself
        select(tab)
        return session
    }

    /** An extension (uBlock Origin) opened a page such as its dashboard or logger. */
    fun openForExtension(url: String?): GeckoSession {
        val tab = createTab(url ?: "about:blank", Profiles.currentId, current, false)
        val session = SessionFactory.create(tab)
        select(tab)
        return session
    }

    // ------------------------------------------------------------------ profiles

    fun switchProfile(id: Long) {
        if (id == Profiles.currentId && current?.profileId == id) return
        val previous = current
        Profiles.setCurrent(id)
        val target = profileTabs(id).maxByOrNull { it.lastUsed } ?: createTab("about:blank", id, null, false)
        select(target)
        // Other profiles' pages don't need to stay in memory.
        tabs.filter { it.profileId != id && it.session != null }.forEach { suspend(it) }
        if (previous != null && previous.profileId != id) suspend(previous)
        ui?.onTabsChanged()
    }

    fun deleteProfile(id: Long) {
        if (Profiles.all().size <= 1) return
        for (t in profileTabs(id)) {
            t.session?.let { s ->
                ui?.releaseSession(s)
                s.close()
            }
            t.session = null
            Helper.detachTab(t)
            tabs.remove(t)
            dirty.remove(t)
        }
        Engine.runtime.storageController.clearDataForSessionContext(Profiles.contextId(id))
        Profiles.delete(id)
        if (current?.profileId == id) current = null
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
                ) = org.mozilla.geckoview.GeckoResult.fromValue(openForExtension(createDetails.url))

                override fun onOpenOptionsPage(source: WebExtension) {
                    source.metaData.optionsPageUrl?.let { newTab(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ history & persistence

    fun recordVisit(profileId: Long, url: String) {
        io.execute { db.recordVisit(profileId, url, "") }
    }

    fun setHistoryTitle(profileId: Long, url: String, title: String) {
        io.execute { db.setHistoryTitle(profileId, url, title) }
    }

    fun notifyTabUpdated(tab: Tab) {
        ui?.onTabUpdated(tab)
    }

    fun scheduleSave(tab: Tab) {
        dirty.add(tab)
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    fun flushSaves() {
        main.removeCallbacks(saveRunnable)
        if (dirty.isEmpty()) return
        val rows = dirty.filter { tabs.contains(it) }.map { t ->
            arrayOf<Any?>(t.id, t.url, t.title, t.state, t.lastUsed, t.desktop)
        }
        dirty.clear()
        io.execute {
            for (r in rows) {
                db.updateTab(r[0] as Long, r[1] as String, r[2] as String, r[3] as String?, r[4] as Long, r[5] as Boolean)
            }
        }
    }
}
