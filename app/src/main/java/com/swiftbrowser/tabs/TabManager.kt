package com.swiftbrowser.tabs

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.ClosedTabRow
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.media.Playback
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * All tabs of all profiles plus the incognito tabs, in the order the tab grid shows them. Only a few
 * tabs keep a live engine session (see Prefs.maxLiveTabs); the rest are "suspended": their history
 * and scroll position are kept and restored on demand. Tabs showing the home page have no session.
 *
 * Tabs can be grouped ([TabGroup]); a group's tabs are always next to each other in [tabs].
 */
object TabManager {
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dirty = LinkedHashSet<Tab>()
    private val saveRunnable = Runnable { flushSaves() }
    private var nextIncognitoId = -1L
    private var nextIncognitoGroupId = -1L

    /** How many recently closed tabs each profile keeps. */
    private const val CLOSED_KEEP = 50
    private const val DAY_MS = 24L * 3600 * 1000

    /**
     * How long a page waits for uBlock Origin's filters at app start before loading anyway: pages
     * the user just asked for wait briefly, pages restored at start a little longer.
     */
    private const val USER_WAIT_MS = 3000L
    private const val RESTORE_WAIT_MS = 8000L

    private fun adblockWait(tab: Tab) =
        if (System.currentTimeMillis() - tab.userNavAt < 2000) USER_WAIT_MS else RESTORE_WAIT_MS

    val tabs = ArrayList<Tab>()
    val groups = ArrayList<TabGroup>()

    var current: Tab? = null
        private set

    var ui: BrowserUi? = null

    /**
     * The connection is back (or the VPN connected): the open tab's failed page loads again
     * (other tabs when opened).
     */
    fun onOnline() {
        val tab = current ?: return
        if (tab.waitingForConnection && tab.session != null) {
            L.d("Connection back: loading the page again")
            reload(tab)
        }
    }

    fun init(db: Db, prefs: Prefs) {
        com.swiftbrowser.engine.DataSaver.onlineListeners += { onOnline() }
        this.db = db
        this.prefs = prefs
        tabs.clear()
        groups.clear()
        val validProfiles = Profiles.all().map { it.id }.toSet()
        for (g in db.tabGroups()) {
            if (g.profileId in validProfiles) groups.add(TabGroup(g.id, g.profileId, g.name, g.color)) else db.deleteTabGroup(g.id)
        }
        for (r in db.tabs()) {
            if (r.profileId !in validProfiles) {
                db.deleteTab(r.id)
                continue
            }
            val tab = Tab(r.id, r.profileId, r.url, r.title, r.state, r.parentId, r.lastUsed, r.desktop)
            tab.groupId = r.groupId?.takeIf { id -> groups.any { it.id == id && it.profileId == r.profileId } }
            tabs.add(tab)
        }
        for (g in groups.filter { g -> tabs.none { it.groupId == g.id } }) {
            groups.remove(g)
            db.deleteTabGroup(g.id)
        }
        keepGroupsTogether()
        L.d("Restored ${tabs.size} tabs, ${groups.size} groups")
    }

    fun profileTabs(profileId: Long = Profiles.currentId): List<Tab> =
        tabs.filter { !it.incognito && it.profileId == profileId }

    fun incognitoTabs(): List<Tab> = tabs.filter { it.incognito }

    /** The tabs listed together with [tab] in the tab grid: its profile's tabs, or the incognito tabs. */
    fun listedWith(tab: Tab): List<Tab> = if (tab.incognito) incognitoTabs() else profileTabs(tab.profileId)

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
        if (parent != null && parentIndex >= 0) {
            // A link opened in a new tab from a grouped tab joins its group, as in Chrome.
            if (parent.incognito == incognito) tab.groupId = parent.groupId
            tabs.add(parentIndex + 1, tab)
        } else {
            tabs.add(tab)
        }
        saveOrder()
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
        // It failed while offline and the connection is back now.
        if (tab.waitingForConnection && com.swiftbrowser.engine.DataSaver.online && tab.session != null) {
            ui?.activity?.window?.decorView?.post { if (current === tab) reload(tab) }
        }
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
        SwiftApp.instance?.let { Playback.onMediaGone(it, tab) }
    }

    fun trimLiveSessions() {
        // Whatever the setting says, when the phone is close to running out of memory only the
        // open tab keeps its page: otherwise Android closes Swift altogether. Same on low battery.
        val low = prefs.maxLiveTabs > 1 && Diagnostics.memoryLow()
        if (low) L.d("Memory low (${Diagnostics.memoryText()}): keeping only the open tab loaded")
        val max = if (low || com.swiftbrowser.engine.Power.saving) 1 else prefs.maxLiveTabs
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

    /** "Keep playing in the background" was switched on or off: pages that are already open follow it. */
    fun applyKeepPlaying(on: Boolean) {
        for (t in tabs) t.session?.settings?.suspendMediaWhenInactive = !on
        Helper.sendLook()
    }

    fun suspendBackgroundTabs() {
        tabs.filter { it.session != null && it !== current && !it.playing }.forEach { suspend(it) }
    }

    /** Tabs closed together from the tab grid, until the next close; see [undoClose]. */
    private class Closed(
        /** In the order they were listed, with the place each had. */
        val entries: List<ClosedEntry>,
        /** Groups that went away with them. */
        val groups: List<TabGroup>,
        val wasCurrent: Tab?,
        val replacement: Tab?,
    )

    private class ClosedEntry(val tab: Tab, val index: Int) {
        /** Its row in the recently closed list; written (and read) on the [io] thread. */
        @Volatile var rowId = -1L
    }

    private var lastClosed: Closed? = null

    /** Closes [tab]. An [undoable] close (from the tab grid) can be taken back with [undoClose]. */
    fun close(tab: Tab, undoable: Boolean = false) = closeTabs(listOf(tab), undoable)

    /** Closes several tabs at once (a group, the inactive tabs); one undo brings them all back. */
    fun closeTabs(list: List<Tab>, undoable: Boolean = false) {
        val closing = list.filter { it in tabs }.distinct()
        if (closing.isEmpty()) return
        forgetClosed()
        val entries = closing.map { ClosedEntry(it, tabs.indexOf(it)) }.sortedBy { it.index }
        val cur = current
        val wasCurrent = cur != null && cur in closing
        for (t in closing) {
            tabs.remove(t)
            detachSession(t)
            dirty.remove(t)
            if (!undoable) Thumbnails.remove(t)
            if (!t.incognito) {
                val id = t.id
                io.execute { db.deleteTab(id) }
            }
        }
        rememberClosed(entries)
        val goneGroups = closing.mapNotNull { group(it.groupId) }.distinct().filter { g -> tabs.none { it.groupId == g.id } }
        goneGroups.forEach { deleteGroup(it) }
        saveOrder()
        var replacement: Tab? = null
        if (wasCurrent) {
            current = null
            val before = tabs.size
            val next = nextAfterClosing(cur!!)
            if (tabs.size > before) replacement = next
            select(next)
        } else {
            ui?.onTabsChanged()
        }
        if (undoable) {
            lastClosed = Closed(entries, goneGroups, if (wasCurrent) cur else null, replacement)
            ui?.onTabsClosed(closing)
        }
    }

    /** Brings back the tabs closed last from the tab grid, with their history and groups. */
    fun undoClose(): Tab? {
        val c = lastClosed ?: return null
        lastClosed = null
        // They are open again, so they leave the recently closed list.
        io.execute { for (e in c.entries) if (e.rowId >= 0) db.deleteClosedTab(e.rowId) }
        val profiles = Profiles.all().map { it.id }.toSet()
        val newGroupIds = HashMap<Long, Long>()
        for (g in c.groups) {
            if (!g.incognito && g.profileId !in profiles) continue
            val id = if (g.incognito) nextIncognitoGroupId-- else db.insertTabGroup(g.profileId, g.name, g.color)
            groups.add(TabGroup(id, g.profileId, g.name, g.color, g.incognito))
            newGroupIds[g.id] = id
        }
        var restoredCurrent: Tab? = null
        var first: Tab? = null
        val now = System.currentTimeMillis()
        for (e in c.entries) {
            val old = e.tab
            if (!old.incognito && old.profileId !in profiles) continue
            val id = if (old.incognito) nextIncognitoId-- else db.insertTab(old.profileId, old.url, old.parentId, now, old.desktop)
            // It keeps its last use, so an inactive tab stays inactive.
            val tab = Tab(id, old.profileId, old.url, old.title, old.state, old.parentId, old.lastUsed, old.desktop, old.incognito)
            tab.sessionState = old.sessionState
            tab.speed = old.speed
            tab.groupId = old.groupId?.let { gid -> newGroupIds[gid] ?: gid.takeIf { group(it) != null } }
            tabs.add(e.index.coerceIn(0, tabs.size), tab)
            Thumbnails.move(old, tab)
            scheduleSave(tab)
            if (old === c.wasCurrent) restoredCurrent = tab
            if (first == null) first = tab
        }
        keepGroupsTogether()
        saveOrder()
        if (restoredCurrent != null) select(restoredCurrent) else ui?.onTabsChanged()
        // The blank tab opened because the last tab had closed goes again if it wasn't used.
        c.replacement?.let { r -> if (r in tabs && r.isHome) close(r) }
        return restoredCurrent ?: first
    }

    private fun forgetClosed() {
        lastClosed?.entries?.forEach { Thumbnails.remove(it.tab) }
        lastClosed = null
    }

    /** Closed pages go to the recently closed list (not incognito ones, nor empty tabs). */
    private fun rememberClosed(entries: List<ClosedEntry>) {
        for (e in entries) {
            val t = e.tab
            if (t.incognito || t.isBlank) continue
            val pid = t.profileId
            val url = t.url
            val title = t.title
            val sessionState = t.sessionState
            val state = t.state
            // Turning the engine's state into text can take a while; done off the main thread.
            io.execute { e.rowId = db.addClosedTab(pid, url, title, sessionState?.toString() ?: state, CLOSED_KEEP) }
        }
    }

    /** Recently closed tabs of [profileId], newest first. Reads the database: call off the main thread. */
    fun recentlyClosed(profileId: Long): List<ClosedTabRow> = db.closedTabs(profileId, CLOSED_KEEP)

    /** Opens a recently closed tab again, with its back and forward history. */
    fun reopen(row: ClosedTabRow): Tab {
        io.execute { db.deleteClosedTab(row.id) }
        val tab = createTab(row.url, row.profileId, null, false, false)
        tab.title = row.title
        tab.state = row.state
        scheduleSave(tab)
        select(tab)
        return tab
    }

    fun clearRecentlyClosed(profileId: Long) {
        io.execute { db.clearClosedTabs(profileId) }
    }

    private fun nextAfterClosing(tab: Tab): Tab {
        val siblings = listedWith(tab)
        siblings.firstOrNull { it.id == tab.parentId }?.let { return it }
        siblings.maxByOrNull { it.lastUsed }?.let { return it }
        // The last incognito tab closed: back to the normal tabs.
        val pid = Profiles.currentId
        return profileTabs(pid).maxByOrNull { it.lastUsed } ?: createTab("about:blank", pid, null, false, false)
    }

    /** Normal tabs can come back with Undo (2.3); incognito ones are gone for good, with their data. */
    fun closeAll(incognito: Boolean) {
        closeTabs(if (incognito) incognitoTabs() else profileTabs(), undoable = !incognito)
    }

    // ------------------------------------------------------------------ order and groups

    /** Saves the order of the normal tabs and their groups (off the main thread). */
    private fun saveOrder() {
        val order = tabs.filter { !it.incognito }.map { it.id to it.groupId }
        io.execute { db.saveTabOrder(order) }
    }

    /** Puts each group's tabs right after the group's first tab, keeping the order otherwise. */
    private fun keepGroupsTogether() {
        if (groups.isEmpty()) return
        val out = ArrayList<Tab>(tabs.size)
        val placed = HashSet<Long>()
        for (t in tabs) {
            val g = t.groupId
            if (g == null) {
                out.add(t)
            } else if (placed.add(g)) {
                tabs.filterTo(out) { it.groupId == g }
            }
        }
        tabs.clear()
        tabs.addAll(out)
    }

    /**
     * The tab grid was rearranged: [order] is the new order of tabs listed together (one profile's
     * tabs, or the incognito tabs). Other tabs keep their places.
     */
    fun reorder(order: List<Tab>) {
        val set = order.toHashSet()
        val slots = tabs.indices.filter { tabs[it] in set }
        if (slots.size != order.size || set.size != order.size) return
        slots.forEachIndexed { i, index -> tabs[index] = order[i] }
        keepGroupsTogether()
        saveOrder()
        ui?.onTabsChanged()
    }

    fun group(id: Long?): TabGroup? = if (id == null) null else groups.firstOrNull { it.id == id }

    fun groupOf(tab: Tab): TabGroup? = group(tab.groupId)

    fun tabsIn(group: TabGroup): List<Tab> = tabs.filter { it.groupId == group.id }

    /** The groups shown together with normal tabs of [profileId], or with the incognito tabs. */
    fun groupsFor(incognito: Boolean, profileId: Long = Profiles.currentId): List<TabGroup> =
        groups.filter { it.incognito == incognito && (incognito || it.profileId == profileId) }

    /** A group's name, or how many tabs it has if it has no name. */
    fun groupTitle(group: TabGroup): String = group.name.ifBlank {
        val n = tabs.count { it.groupId == group.id }
        if (n == 1) "1 tab" else "$n tabs"
    }

    /** Puts [tab] in a group with [target]: [target]'s group, or a new one for the two of them. */
    fun groupWith(tab: Tab, target: Tab): TabGroup? {
        if (tab === target || tab.incognito != target.incognito || tab.profileId != target.profileId) return null
        val group = groupOf(target) ?: newGroup(target)
        addToGroup(tab, group)
        return group
    }

    /** A new group with just [tab] in it, for the menu's "Add to group". */
    fun newGroupWith(tab: Tab): TabGroup {
        val old = tab.groupId
        val group = newGroup(tab)
        val index = tabs.indexOf(tab)
        // A tab leaving another group goes after that group's tabs.
        if (old != null && index >= 0) {
            tabs.removeAt(index)
            val last = tabs.indexOfLast { it.groupId == old }
            tabs.add(if (last >= 0) last + 1 else index.coerceAtMost(tabs.size), tab)
            removeIfEmpty(old)
        }
        saveOrder()
        ui?.onTabsChanged()
        return group
    }

    private fun newGroup(first: Tab): TabGroup {
        val color = nextGroupColor(first)
        val id = if (first.incognito) nextIncognitoGroupId-- else db.insertTabGroup(first.profileId, "", color)
        val group = TabGroup(id, first.profileId, "", color, first.incognito)
        groups.add(group)
        first.groupId = group.id
        return group
    }

    /** A colour no other group next to it has yet, starting with blue. */
    private fun nextGroupColor(tab: Tab): Int {
        val used = groupsFor(tab.incognito, tab.profileId).map { it.color }.toSet()
        val order = (1 until TabGroup.COLORS.size) + 0
        return order.firstOrNull { it !in used } ?: order[groups.size % order.size]
    }

    /** Moves [tab] into [group], after the group's last tab. */
    fun addToGroup(tab: Tab, group: TabGroup) {
        if (tab.groupId == group.id || tab.incognito != group.incognito || (!tab.incognito && tab.profileId != group.profileId)) return
        val old = tab.groupId
        tabs.remove(tab)
        tab.groupId = group.id
        val last = tabs.indexOfLast { it.groupId == group.id }
        tabs.add(if (last >= 0) last + 1 else tabs.size, tab)
        removeIfEmpty(old)
        saveOrder()
        ui?.onTabsChanged()
    }

    /** Takes [tab] out of its group; it goes right after the group's other tabs. */
    fun removeFromGroup(tab: Tab) {
        val gid = tab.groupId ?: return
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        tab.groupId = null
        val last = tabs.indexOfLast { it.groupId == gid }
        tabs.add(if (last >= 0) last + 1 else index.coerceAtMost(tabs.size), tab)
        removeIfEmpty(gid)
        saveOrder()
        ui?.onTabsChanged()
    }

    /** The group goes; its tabs stay where they are. */
    fun ungroup(group: TabGroup) {
        tabs.filter { it.groupId == group.id }.forEach { it.groupId = null }
        deleteGroup(group)
        saveOrder()
        ui?.onTabsChanged()
    }

    fun renameGroup(group: TabGroup, name: String) {
        group.name = name.trim().take(40)
        persistGroup(group)
        ui?.onTabsChanged()
    }

    fun setGroupColor(group: TabGroup, color: Int) {
        group.color = color
        persistGroup(group)
        ui?.onTabsChanged()
    }

    /** Closes all of a group's tabs; undo brings back the group too. */
    fun closeGroup(group: TabGroup) = closeTabs(tabsIn(group), undoable = true)

    /** Opens a new tab at the end of [group] and shows it. */
    fun newTabInGroup(group: TabGroup): Tab {
        val tab = createTab("about:blank", group.profileId, null, false, group.incognito)
        tabs.remove(tab)
        tab.groupId = group.id
        val last = tabs.indexOfLast { it.groupId == group.id }
        tabs.add(if (last >= 0) last + 1 else tabs.size, tab)
        saveOrder()
        select(tab)
        return tab
    }

    private fun persistGroup(group: TabGroup) {
        if (group.incognito) return
        val id = group.id
        val name = group.name
        val color = group.color
        io.execute { db.updateTabGroup(id, name, color) }
    }

    private fun deleteGroup(group: TabGroup) {
        groups.remove(group)
        if (!group.incognito) {
            val id = group.id
            io.execute { db.deleteTabGroup(id) }
        }
    }

    private fun removeIfEmpty(id: Long?) {
        val g = group(id) ?: return
        if (tabs.none { it.groupId == g.id }) deleteGroup(g)
    }

    // ------------------------------------------------------------------ inactive tabs

    /**
     * Tabs not opened for a while (Settings → Browsing) move to the "Inactive tabs" part of the tab
     * grid, as in Chrome. Grouped, incognito and playing tabs never do.
     */
    fun isInactive(tab: Tab, now: Long = System.currentTimeMillis()): Boolean {
        val days = prefs.inactiveDays
        return days > 0 && !tab.incognito && tab.groupId == null && tab !== current && !tab.playing &&
            now - tab.lastUsed > days * DAY_MS
    }

    /** The tabs swiping on the address bar goes through: as in the tab grid, without inactive ones. */
    fun swipeOrder(tab: Tab): List<Tab> {
        val now = System.currentTimeMillis()
        return listedWith(tab).filter { it === tab || !isInactive(it, now) }
    }

    /** Navigates [tab] to [url] on the user's behalf. */
    fun load(tab: Tab, url: String) {
        tab.userNavAt = System.currentTimeMillis()
        tab.waitingForConnection = false
        tab.homeShown = false
        if (tab.isHome) tab.leftHome = true
        // A blank tab gets an empty session here; the address is set after, so it loads only once.
        val session = ensureLive(tab)
        // The home page may have put the page underneath to rest (MainActivity.goHome): the new
        // page must load in an active session, or it isn't drawn, saved or added to history.
        if (tab === current && session.isOpen) session.setActive(true)
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
        tab.waitingForConnection = false
        val session = tab.session
        if (session == null || !session.isOpen) {
            if (!tab.isHome) select(tab)
            return
        }
        session.reload()
    }

    fun goBack(tab: Tab) {
        tab.userNavAt = System.currentTimeMillis()
        tab.waitingForConnection = false
        tab.session?.goBack()
    }

    fun goForward(tab: Tab) {
        tab.userNavAt = System.currentTimeMillis()
        tab.waitingForConnection = false
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
        // Remembered for the site, so its other pages and tabs get the same version.
        UrlUtils.site(tab.url)?.let { if (UrlUtils.isHttp(tab.url)) com.swiftbrowser.core.SiteSettings.setDesktop(it, desktop) }
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
        groups.removeAll { !it.incognito && it.profileId == id }
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
