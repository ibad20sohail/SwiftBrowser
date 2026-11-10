package com.swiftbrowser.tabs

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.ClosedTabRow
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.TabRow
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.media.Playback
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.ExecutorService

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
    val io: ExecutorService = com.swiftbrowser.core.Background.single("tabs")
    private val main = Handler(Looper.getMainLooper())
    private val dirty = LinkedHashSet<Tab>()
    private val saveRunnable = Runnable { flushSaves() }
    private var nextIncognitoId = -1L

    /** New tabs' ids (2.6): given here, so the database is written off the main thread. */
    private var nextTabId = 1L

    /** Normal groups' ids too (3.0): a new group was written on the main thread before. */
    private var nextGroupId = 1L
    private var nextIncognitoGroupId = -1L

    /** 3.0: page restores asked for so far, which number them (Tab.restoreToken). */
    private var restoreCount = 0

    /** A tab not opened for this long lets go of its page (2.7). */
    private const val IDLE_UNLOAD_MS = 30 * 60_000L

    /** How many recently closed tabs each profile keeps. */
    private const val CLOSED_KEEP = 50
    private const val DAY_MS = 24L * 3600 * 1000

    /** 3.0: the tabs whose Back returns to the home page (Tab.leftHome), kept across restarts. */
    private const val LEFT_HOME = "tabs_left_home"

    /** Tabs kept loaded (3.0.1), by id; incognito ones are never written down. */
    private const val KEPT = "tabs_kept_loaded"

    /** 3.0.4: pinned tabs, by id, the same way (their order is the tabs' own, saved with them). */
    private const val PINNED = "tabs_pinned"

    /**
     * 3.0: [task] on [io], for the database: an error there (the phone full: SQLiteFullException) is
     * logged and Swift carries on. Before 3.0 such an error closed Swift, on a full phone at every start.
     */
    private fun ExecutorService.safe(what: String, task: () -> Unit) = execute {
        try {
            task()
        } catch (e: Exception) {
            L.w("Couldn't $what", e)
        } catch (e: OutOfMemoryError) {
            L.w("Couldn't $what: not enough memory")
        }
    }

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
        // 3.0: nothing read here stops Swift from starting (a damaged or full database): what can't
        // be read is left out, and what is deleted is deleted off the main thread.
        val groupRows = try {
            db.tabGroups()
        } catch (e: Exception) {
            L.w("Couldn't read the tab groups", e)
            emptyList()
        }
        for (g in groupRows) {
            if (g.profileId in validProfiles) groups.add(TabGroup(g.id, g.profileId, g.name, g.color)) else deleteGroupRow(g.id)
        }
        val leftHome = prefs.sp.getStringSet(LEFT_HOME, null).orEmpty()
        val kept = prefs.sp.getStringSet(KEPT, null).orEmpty()
        val pinned = prefs.sp.getStringSet(PINNED, null).orEmpty()
        var ungrouped = false
        val repaired = ArrayList<Tab>()
        // Without their histories (2.6): each is read when its tab opens.
        val read = readTabs()
        for (r in read.rows) {
            if (r.profileId !in validProfiles) {
                val id = r.id
                io.safe("delete a tab") { db.deleteTab(id) }
                continue
            }
            val tab = Tab(r.id, r.profileId, r.url, r.title, r.state, r.parentId, r.lastUsed, r.desktop)
            tab.stateLoaded = r.state == null
            tab.groupId = r.groupId?.takeIf { id -> groups.any { it.id == id && it.profileId == r.profileId } }
            tab.leftHome = r.id.toString() in leftHome
            tab.kept = r.id.toString() in kept
            tab.pinned = r.id.toString() in pinned
            // 3.0.4: a pinned tab is in no group.
            if (tab.pinned && tab.groupId != null) {
                tab.groupId = null
                ungrouped = true
            }
            // 3.0: saved (before 3.0) with more than Android 7 can read back, a picture's data: address
            // or a very long history: it comes back without it, and is saved so.
            val tooLong = !TabRules.storable(r.url)
            if (tooLong) {
                tab.url = "about:blank"
                tab.title = ""
            }
            if (tooLong || r.id in read.repair) {
                tab.state = null
                tab.stateLoaded = true
                repaired += tab
            }
            tabs.add(tab)
        }
        for (g in groups.filter { g -> tabs.none { it.groupId == g.id } }) {
            groups.remove(g)
            deleteGroupRow(g.id)
        }
        keepGroupsTogether()
        if (keepPinnedFirst() || ungrouped) saveOrder()
        nextTabId = maxOf(db.lastTabId(), tabs.maxOfOrNull { it.id } ?: 0L) + 1
        nextGroupId = maxOf(lastGroupId(), groupRows.maxOfOrNull { it.id } ?: 0L) + 1
        repaired.forEach { scheduleSave(it) }
        if (repaired.isNotEmpty()) L.w("${repaired.size} saved tabs held more than Swift can read back; they keep only what fits")
        // Ids of tabs closed since are let go.
        if (kept.size != tabs.count { it.kept }) saveKept()
        if (pinned.size != tabs.count { it.pinned }) savePinned()
        main.postDelayed(idleCheck, 5 * 60_000L)
        L.d("Restored ${tabs.size} tabs, ${groups.size} groups")
    }

    /** The saved tabs, and those of them that need saving again without what is too long (3.0). */
    private class ReadTabs(val rows: List<TabRow>, val repair: Set<Long> = emptySet())

    /**
     * 3.0: the saved tabs, at once if possible; otherwise one at a time, leaving out what Android 7
     * can't read (a database row over 2 MB: a picture's data: address saved before 3.0). Db.kt can
     * only read them all at once, and one such row stopped Swift at every start.
     */
    private fun readTabs(): ReadTabs = try {
        ReadTabs(db.tabsWithoutState())
    } catch (e: Exception) {
        L.w("Couldn't read the saved tabs at once (${e.javaClass.simpleName}); reading them one at a time")
        readTabsOneByOne()
    }

    private fun readTabsOneByOne(): ReadTabs {
        val rows = ArrayList<TabRow>()
        val repair = HashSet<Long>()
        val ids = ArrayList<Long>()
        val sql = try {
            db.readableDatabase.also { d ->
                d.rawQuery("SELECT id FROM tabs ORDER BY position, id", null).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            }
        } catch (e: Exception) {
            L.w("Couldn't read the saved tabs", e)
            return ReadTabs(rows)
        }
        // The limits are numbers in the query itself: bound as text, SQLite would compare them as text.
        val query = "SELECT profile_id, CASE WHEN length(url) > ${TabRules.MAX_STORED_URL} THEN NULL ELSE url END, " +
            "substr(title, 1, ${TabRules.MAX_STORED_TITLE}), " +
            "CASE WHEN state IS NULL THEN 0 WHEN length(state) > ${TabRules.MAX_STORED_STATE} THEN 2 ELSE 1 END, " +
            "parent_id, last_used, desktop, group_id FROM tabs WHERE id = ?"
        for (id in ids) {
            try {
                sql.rawQuery(query, arrayOf("$id")).use { c ->
                    if (!c.moveToFirst()) return@use
                    val url = if (c.isNull(1)) null else c.getString(1)
                    val stateKind = c.getInt(3)
                    if (url == null || stateKind == 2) repair += id
                    rows += TabRow(
                        id, c.getLong(0), url ?: "about:blank", if (url == null || c.isNull(2)) "" else c.getString(2),
                        if (stateKind == 1) "" else null,
                        if (c.isNull(4)) null else c.getLong(4), c.getLong(5), c.getInt(6) != 0,
                        if (c.isNull(7)) null else c.getLong(7),
                    )
                }
            } catch (e: Exception) {
                L.w("Left out a saved tab Swift couldn't read (${e.javaClass.simpleName})")
            }
        }
        return ReadTabs(rows, repair)
    }

    /** The highest group id handed out so far, deleted groups included (3.0, like Db.lastTabId). */
    private fun lastGroupId(): Long = try {
        db.readableDatabase.compileStatement("SELECT seq FROM sqlite_sequence WHERE name = 'tab_groups'").use { it.simpleQueryForLong() }
    } catch (_: Exception) {
        0L
    }

    fun profileTabs(profileId: Long = Profiles.currentId): List<Tab> =
        tabs.filter { !it.incognito && it.profileId == profileId }

    fun incognitoTabs(): List<Tab> = tabs.filter { it.incognito }

    /** The tabs listed together with [tab] in the tab grid: its profile's tabs, or the incognito tabs. */
    fun listedWith(tab: Tab): List<Tab> = if (tab.incognito) incognitoTabs() else profileTabs(tab.profileId)

    fun bySession(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }

    /** The tab to show for the current profile; creates a blank one if the profile has none. */
    /** 3.0.5: the tab [ensureCurrent] will show, if any: the start screen asks before the browser is built. */
    fun tabToOpen(): Tab? = current?.takeIf { tabs.contains(it) } ?: profileTabs(Profiles.currentId).maxByOrNull { it.lastUsed }

    fun ensureCurrent(): Tab {
        val pid = Profiles.currentId
        current?.let { if (tabs.contains(it) && (it.incognito || it.profileId == pid)) return it }
        val tab = profileTabs(pid).maxByOrNull { it.lastUsed } ?: createTab("about:blank", pid, null, false, false)
        select(tab)
        return tab
    }

    private fun createTab(url: String, profileId: Long, parent: Tab?, desktop: Boolean, incognito: Boolean): Tab {
        val now = System.currentTimeMillis()
        val id = if (incognito) nextIncognitoId-- else nextTabId++
        if (!incognito) {
            val parentId = parent?.id
            // 3.0: a picture opened from its data: address isn't saved as one (TabRules.storedUrl).
            val stored = TabRules.storedUrl(url)
            io.safe("save a new tab") { db.insertTab(id, profileId, stored, "", null, parentId, now, desktop) }
        }
        val tab = Tab(id, profileId, url, "", null, parent?.id, now, desktop, incognito)
        val parentIndex = if (parent != null) tabs.indexOf(parent) else -1
        if (parent != null && parentIndex >= 0) {
            // A link opened in a new tab from a grouped tab joins its group, as in Chrome.
            if (parent.incognito == incognito) tab.groupId = parent.groupId
            tabs.add(parentIndex + 1, tab)
            // 3.0.4: next to a pinned tab it goes after the pinned ones.
            keepPinnedFirst()
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

    /** 3.0.3: the tab chosen before the one showing now (a double tap on the tab button goes back to it). */
    private var previousTabId = -1L

    fun previousTab(): Tab? = tabs.firstOrNull { it.id == previousTabId && it !== current }

    fun select(tab: Tab) {
        val previous = current
        if (previous != null && previous !== tab) previousTabId = previous.id
        if (previous != null && previous !== tab) {
            // 3.0: it was in use until now, not only when it was opened (an idle tab lets go of its
            // page after 30 minutes, see trimLiveSessions).
            leftTab(previous)
            previous.session?.let { s ->
                if (s.isOpen) {
                    val rest = {
                        // Not if it was chosen again meanwhile (its picture takes a moment).
                        if (current !== previous && previous.session === s && s.isOpen) {
                            s.setActive(false)
                            s.setFocused(false)
                            Engine.runtime.webExtensionController.setTabActive(s, false)
                        }
                    }
                    // 3.0: its picture for the tab grid is taken first, while its page is still drawn
                    // (not after every page load any more).
                    val u = ui
                    if (u != null) u.onLeavingTab(previous, rest) else rest()
                }
            }
        }
        current = tab
        tab.lastUsed = System.currentTimeMillis()
        // It failed while offline and the connection is back now.
        if (tab.waitingForConnection && com.swiftbrowser.engine.DataSaver.online && tab.session != null) {
            ui?.activity?.window?.decorView?.post { if (current === tab) reload(tab) }
        }
        // Only when it was used (2.6): its history isn't written again for a switch.
        if (!tab.incognito) {
            val id = tab.id
            val used = tab.lastUsed
            io.safe("save when a tab was used") { db.setTabLastUsed(id, used) }
        }
        if (!tab.isHome) {
            tab.wokeUp = tab.session == null && (tab.sessionState != null || tab.state != null)
            val session = ensureLive(tab)
            if (session.isOpen) activate(tab, session)
        }
        ui?.onTabSelected(tab)
        ui?.onTabsChanged()
        trimLiveSessions()
    }

    private fun activate(tab: Tab, session: GeckoSession) {
        // 3.0: drawn (and its scripts at full speed) only while it can be seen, not under the home
        // page or the tab grid, nor with Swift out of sight; for the extensions it's the open tab anyway.
        if (pageOnScreen(tab)) session.setActive(true)
        session.setFocused(true)
        Engine.runtime.webExtensionController.setTabActive(session, true)
    }

    /** 3.0: [tab]'s page can be seen now (see BrowserUi.pageOnScreen): only then is it made active. */
    fun pageOnScreen(tab: Tab): Boolean = tab === current && ui?.pageOnScreen == true

    /**
     * 3.0: [tab] was in use until now: left for another tab, the home page or another app. Its last
     * use counts for unloading idle tabs (30 minutes) and for Inactive tabs.
     */
    fun leftTab(tab: Tab) {
        tab.lastUsed = System.currentTimeMillis()
        if (tab.incognito || tab !in tabs) return
        val id = tab.id
        val used = tab.lastUsed
        io.safe("save when a tab was used") { db.setTabLastUsed(id, used) }
    }

    /** 3.0: [tab]'s page is still to be restored (waiting for the ad blocker at start, or for its history to be read). */
    fun isRestoring(tab: Tab): Boolean = tab.restoreToken != 0

    /** 3.0: [tab]'s page waits for the ad blocker however long it takes: one Swift restores (not one the user just opened). */
    fun waitsForAdBlocker(tab: Tab): Boolean = tab.restoreToken != 0 && tab.restoreUntilReady && !AdBlock.ready

    /** Returns the tab's session, creating and restoring it if the tab was suspended. */
    fun ensureLive(tab: Tab): GeckoSession {
        tab.session?.let { s ->
            if (s.isOpen) return s
            // 3.0: a pop-up's, which the engine opens itself in a moment (see openedByEngine).
            if (tab.sessionOpening) return s
            // Closed behind our back (e.g. its process died): start over.
            detachSession(tab)
        }
        val session = SessionFactory.create(tab)
        session.open(Engine.runtime)
        tab.crashed = false
        // 3.0.4: a muted tab's page loads muted (its first frame's hello says it again, see Helper).
        if (tab.muted) Helper.muteEngine(tab, true)
        if (tab === current) activate(tab, session)
        val parsed = tab.sessionState
        val text = if (parsed == null) tab.state else null
        if (parsed != null || text != null || !tab.isBlank) {
            // Show progress right away, even while uBlock Origin is still starting.
            tab.loading = true
            tab.progress = 5
            // 3.0: numbered, so that it runs only if nothing newer was asked of the tab meanwhile: a
            // page the user opened in the first seconds isn't replaced by the restore (see load).
            val token = ++restoreCount
            tab.restoreToken = token
            val wait = TabRules.adBlockWait(tab.userNavAt, System.currentTimeMillis())
            tab.restoreUntilReady = wait == 0L
            val restore = { state: GeckoSession.SessionState? ->
                AdBlock.whenReady(wait) {
                    if (TabRules.restoreStillWanted(token, tab.restoreToken)) {
                        tab.restoreToken = 0
                        if (tab.session === session && session.isOpen) {
                            if (state != null) session.restoreState(state) else session.loadUri(tab.url)
                        }
                        notifyTabUpdated(tab)
                    }
                }
            }
            if (text == null) {
                restore(parsed)
            } else {
                // A long history takes a moment to read and turn back into the engine's state: not on
                // the main thread (2.6).
                val loaded = tab.stateLoaded
                val id = tab.id
                io.execute {
                    // 3.0: whatever goes wrong reading it (a row too big for Android 7, the database),
                    // the page still loads, without its history. Only the kind of error is logged:
                    // its message would quote the history.
                    var stored: String? = null
                    var state: GeckoSession.SessionState? = null
                    try {
                        stored = if (loaded) text else db.tabState(id)
                        state = stored?.takeIf { it.isNotEmpty() }?.let { GeckoSession.SessionState.fromString(it) }
                    } catch (e: Exception) {
                        L.w("Couldn't read a tab's history (${e.javaClass.simpleName})")
                    } catch (e: OutOfMemoryError) {
                        L.w("Couldn't read a tab's history: not enough memory")
                    }
                    val readText = stored
                    val readState = state
                    main.post {
                        if (!tab.stateLoaded) {
                            tab.state = readText
                            tab.stateLoaded = true
                        }
                        restore(readState)
                    }
                }
            }
        }
        return session
    }

    private fun detachSession(tab: Tab) {
        val session = tab.session ?: return
        ui?.releaseSession(session)
        if (tab.sessionOpening) {
            // 3.0: the engine hasn't opened it yet (closing it now does nothing): it's closed once it has, see watchOpening.
            tab.sessionOpening = false
        } else {
            try {
                session.close()
            } catch (e: Exception) {
                L.w("close failed", e)
            }
        }
        tab.session = null
        tab.restoreToken = 0
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
        val others = tabs.filter { it.session != null && it !== current }
        // Tabs not opened for half an hour let go of their page too (2.7), whatever the number:
        // their scripts would otherwise keep sharing the engine with the page on screen.
        val idleSince = System.currentTimeMillis() - IDLE_UNLOAD_MS
        // 3.0: nor a pop-up whose window the engine is still to open, nor its opener (see opensPopup).
        // 3.0.1: tabs kept loaded stay whatever happens, and leave room for others (TabRules.toUnload).
        val loaded = others.map { TabRules.Loaded(it.id, it.lastUsed, it.playing || it.sessionOpening || opensPopup(it), it.kept) }
        val openCounts = current?.session != null && current?.kept != true
        var count = 0
        var idleOnly = true
        for (id in TabRules.toUnload(loaded, openCounts, max, idleSince)) {
            val t = others.firstOrNull { it.id == id } ?: continue
            val idle = t.lastUsed < idleSince
            if (!idle) idleOnly = false
            L.d("Unloading tab ${t.id} (${if (idle) "not opened for 30 minutes" else "the limit is $max loaded"})")
            suspend(t)
            if (t.session == null) count++
        }
        noteUnloaded(
            count,
            when {
                low -> "the phone's memory was low"
                com.swiftbrowser.engine.Power.saving -> "battery saver keeps one tab loaded"
                idleOnly -> "they weren't opened for 30 minutes"
                else -> "more than $max tabs were loaded"
            },
        )
    }

    /** The last time pages were let go, and why (3.0.2: for Settings → Speed & memory → Right now). */
    class Unloaded(val time: Long, val count: Int, val why: String)

    var lastUnloaded: Unloaded? = null
        private set

    private fun noteUnloaded(count: Int, why: String) {
        if (count > 0) lastUnloaded = Unloaded(System.currentTimeMillis(), count, why)
    }

    /**
     * Whether any of Swift's screens shows (3.0.2, from SwiftApp: the browser, Settings, Files, the
     * player...). Before, Settings or the player in front of the browser counted as Swift being away,
     * and a minute later every background tab let go of its page. A minute really out of sight,
     * other tabs let go of their pages so Android is less likely to close Swift on a 2 GB phone; a
     * short trip to another app keeps everything.
     */
    fun inSight(visible: Boolean) {
        main.removeCallbacks(freeAfterLeaving)
        if (!visible) main.postDelayed(freeAfterLeaving, 60_000)
    }

    private val freeAfterLeaving = Runnable {
        L.d("Out of sight for a minute: keeping only the open (or playing, or kept) tab loaded")
        suspendBackgroundTabs("Swift was out of sight for a minute")
    }

    /** "Keep loaded" (3.0.1) on or off for [tab]; off lets the usual limits apply to it again. */
    fun setKept(tab: Tab, on: Boolean) {
        if (tab.kept == on) return
        tab.kept = on
        if (!tab.incognito) saveKept()
        L.d("Tab ${tab.id} ${if (on) "kept loaded" else "may be unloaded again"}")
        if (!on) trimLiveSessions()
    }

    private fun saveKept() {
        val ids = tabs.filter { it.kept && !it.incognito }.mapTo(HashSet()) { it.id.toString() }
        prefs.sp.edit().putStringSet(KEPT, ids).apply()
    }

    /**
     * 3.0.4: pins or unpins [tab]. Pinned, it goes after the tabs pinned before it and leaves its group
     * (a pinned tab is in none); unpinned, it goes first of the others (TabRules.withPinChanged).
     */
    fun setPinned(tab: Tab, on: Boolean) {
        if (tab.pinned == on || tab !in tabs) return
        val oldGroup = tab.groupId
        if (on) tab.groupId = null
        tab.pinned = on
        val order = TabRules.withPinChanged(tabs, tab) { it.pinned }
        tabs.clear()
        tabs.addAll(order)
        removeIfEmpty(oldGroup)
        if (!tab.incognito) savePinned()
        saveOrder()
        L.d("Tab ${tab.id} ${if (on) "pinned" else "unpinned"}")
        ui?.onTabsChanged()
    }

    /** 3.0.4: put in a group on purpose (the menu's "Add to group"), a pinned tab is unpinned first, as in Chrome. */
    private fun unpinForGroup(tab: Tab) {
        if (!tab.pinned) return
        tab.pinned = false
        val order = TabRules.withPinChanged(tabs, tab) { it.pinned }
        tabs.clear()
        tabs.addAll(order)
        if (!tab.incognito) savePinned()
    }

    private fun savePinned() {
        val ids = tabs.filter { it.pinned && !it.incognito }.mapTo(HashSet()) { it.id.toString() }
        prefs.sp.edit().putStringSet(PINNED, ids).apply()
    }

    /** 3.0.4: pinned tabs first, in their order (TabRules.pinnedFirst); true when something moved. */
    private fun keepPinnedFirst(): Boolean {
        val firstOther = tabs.indexOfFirst { !it.pinned }
        if (firstOther < 0 || (firstOther until tabs.size).none { tabs[it].pinned }) return false
        val order = TabRules.pinnedFirst(tabs) { it.pinned }
        tabs.clear()
        tabs.addAll(order)
        return true
    }

    /**
     * Swift came on screen or went out of sight (3.0.1): the idle check only runs while it's on
     * screen. Out of sight, background tabs are let go a minute after anyway (suspendBackgroundTabs).
     */
    fun onScreen(on: Boolean) {
        main.removeCallbacks(idleCheck)
        if (on) main.postDelayed(idleCheck, 5 * 60_000L)
    }

    /** Looks for tabs to unload every 5 minutes (2.7). */
    private val idleCheck = object : Runnable {
        override fun run() {
            trimLiveSessions()
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    /** Frees a background tab's engine session; it is restored when the tab is opened again. */
    fun suspend(tab: Tab) {
        // 3.0: a pop-up's session the engine is still to open stays (see openedByEngine), and so does its opener's.
        if (tab === current || tab.sessionOpening || opensPopup(tab)) return
        detachSession(tab)
    }

    /**
     * 3.0: [tab] opened a pop-up whose window the engine is still to open: the engine opens it from
     * [tab]'s session (and refuses once that is closed), so it must stay until then.
     */
    private fun opensPopup(tab: Tab): Boolean = tabs.any { it.sessionOpening && it.parentId == tab.id }

    /** "Keep playing in the background" was switched on or off: pages that are already open follow it. */
    fun applyKeepPlaying(on: Boolean) {
        for (t in tabs) t.session?.settings?.suspendMediaWhenInactive = !on
        Helper.sendLook()
    }

    /** Android is short of memory, or Swift is out of sight: background pages go, but playing and kept tabs (3.0.1). [why]: for Right now. */
    fun suspendBackgroundTabs(why: String) {
        val gone = tabs.filter { it.session != null && it !== current && !it.playing && !it.kept }
        gone.forEach { suspend(it) }
        noteUnloaded(gone.count { it.session == null }, why)
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

    /**
     * Closes several tabs at once (a group, the inactive tabs); one undo brings them all back.
     * [remember] false (3.0): they don't go to the recently closed list either ("When you quit"
     * with Open tabs ticked, an ad pop-up uBlock Origin closed). Only for closes that can't be undone.
     */
    fun closeTabs(list: List<Tab>, undoable: Boolean = false, remember: Boolean = true) {
        val closing = list.filter { it in tabs }.distinct()
        if (closing.isEmpty()) return
        forgetClosed()
        val entries = closing.map { ClosedEntry(it, tabs.indexOf(it)) }.sortedBy { it.index }
        val cur = current
        val wasCurrent = cur != null && cur in closing
        // First, while the tabs' rows (and histories not read yet) are still there.
        if (remember) rememberClosed(entries)
        for (t in closing) {
            tabs.remove(t)
            detachSession(t)
            dirty.remove(t)
            if (!undoable) Thumbnails.remove(t)
            if (!t.incognito) {
                val id = t.id
                io.safe("delete a closed tab") { db.deleteTab(id) }
            }
        }
        val goneGroups = closing.mapNotNull { group(it.groupId) }.distinct().filter { g -> tabs.none { it.groupId == g.id } }
        goneGroups.forEach { deleteGroup(it) }
        // 3.0.4: a closed pinned tab leaves the list of pinned ones (Undo pins it again).
        if (closing.any { it.pinned && !it.incognito }) savePinned()
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
        // The last incognito tab closed (2.6): its documents and downloads are forgotten too, and
        // (3.0.1) the site settings chosen there.
        if (closing.any { it.incognito } && tabs.none { it.incognito }) {
            com.swiftbrowser.docs.Docs.onIncognitoEnded()
            com.swiftbrowser.download.Downloads.onIncognitoEnded()
            com.swiftbrowser.core.SiteSettings.onIncognitoEnded()
        }
    }

    /** Brings back the tabs closed last from the tab grid, with their history and groups. */
    fun undoClose(): Tab? {
        val c = lastClosed ?: return null
        lastClosed = null
        val profiles = Profiles.all().map { it.id }.toSet()
        val newGroupIds = HashMap<Long, Long>()
        for (g in c.groups) {
            if (!g.incognito && g.profileId !in profiles) continue
            // 3.0: the id given here, the row written off the main thread (see newGroup).
            val id = if (g.incognito) nextIncognitoGroupId-- else nextGroupId++
            val group = TabGroup(id, g.profileId, g.name, g.color, g.incognito)
            if (!group.incognito) insertGroupRow(group)
            groups.add(group)
            newGroupIds[g.id] = id
        }
        var restoredCurrent: Tab? = null
        var first: Tab? = null
        val now = System.currentTimeMillis()
        var leftHome = false
        var pinnedBack = false
        var keptBack = false
        for (e in c.entries) {
            val old = e.tab
            if (!old.incognito && old.profileId !in profiles) continue
            val id = if (old.incognito) nextIncognitoId-- else nextTabId++
            if (!old.incognito) {
                val loaded = old.stateLoaded
                val state = old.state
                val url = old.url
                val title = old.title
                io.safe("bring back a closed tab") {
                    // 3.0: the history saved as it closed (the newest: the engine's, which old.state
                    // isn't once the tab was open), else what it had. One never read comes back from
                    // the recently closed list too (2.6).
                    val history = if (e.rowId >= 0) db.closedTabState(e.rowId) else if (loaded) state else null
                    val keep = TabRules.storable(url)
                    db.insertTab(
                        id, old.profileId, TabRules.storedUrl(url), if (keep) TabRules.storedTitle(title) else "",
                        if (keep) TabRules.storedState(history) else null, old.parentId, old.lastUsed, old.desktop,
                    )
                }
            }
            // It keeps its last use, so an inactive tab stays inactive.
            val tab = Tab(id, old.profileId, old.url, old.title, old.state, old.parentId, old.lastUsed, old.desktop, old.incognito)
            tab.stateLoaded = old.stateLoaded
            tab.sessionState = old.sessionState
            tab.speed = old.speed
            tab.leftHome = old.leftHome
            if (tab.leftHome) leftHome = true
            // 3.0.4: pinned (and muted) as it was.
            tab.pinned = old.pinned
            tab.muted = old.muted
            tab.kept = old.kept
            if (tab.pinned && !tab.incognito) pinnedBack = true
            if (tab.kept && !tab.incognito) keptBack = true
            tab.groupId = old.groupId?.let { gid -> newGroupIds[gid] ?: gid.takeIf { group(it) != null } }
            tabs.add(e.index.coerceIn(0, tabs.size), tab)
            Thumbnails.move(old, tab)
            scheduleSave(tab)
            if (old === c.wasCurrent) restoredCurrent = tab
            if (first == null) first = tab
        }
        if (leftHome) saveLeftHome()
        if (pinnedBack) savePinned()
        if (keptBack) saveKept()
        // They are open again, so they leave the recently closed list.
        io.safe("update the recently closed tabs") { for (e in c.entries) if (e.rowId >= 0) db.deleteClosedTab(e.rowId) }
        keepGroupsTogether()
        keepPinnedFirst()
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
            // 3.0: nor one whose address is too long to keep (a picture opened from its data: address).
            if (t.incognito || t.isBlank || !TabRules.storable(t.url)) continue
            val pid = t.profileId
            val url = t.url
            val title = TabRules.storedTitle(t.title)
            val sessionState = t.sessionState
            val state = t.state
            val loaded = t.stateLoaded
            val id = t.id
            // Turning the engine's state into text can take a while; done off the main thread.
            io.safe("remember a closed tab") {
                val saved = sessionState?.toString() ?: if (loaded) state else db.tabState(id)
                e.rowId = db.addClosedTab(pid, url, title, TabRules.storedState(saved), CLOSED_KEEP)
            }
        }
    }

    /**
     * Recently closed tabs of [profileId], newest first, without their histories (3.0: read with
     * db.closedTabState for the one opened again). Reads the database: call off the main thread.
     * 3.0: when they can't all be read (one too big for Android 7, a picture's data: address closed
     * before 3.0; this closed Swift), the others, without what is too long to read back.
     */
    fun recentlyClosed(profileId: Long): List<ClosedTabRow> = try {
        db.closedTabList(profileId, CLOSED_KEEP)
    } catch (e: Exception) {
        L.w("Couldn't read the recently closed tabs at once (${e.javaClass.simpleName})")
        try {
            // The limits are numbers in the query itself (see readTabsOneByOne).
            db.readableDatabase.rawQuery(
                "SELECT id, profile_id, url, substr(title, 1, ${TabRules.MAX_STORED_TITLE}), closed_at FROM closed_tabs " +
                    "WHERE profile_id = ? AND length(url) <= ${TabRules.MAX_STORED_URL} ORDER BY closed_at DESC, id DESC LIMIT $CLOSED_KEEP",
                arrayOf("$profileId"),
            ).use { c ->
                val rows = ArrayList<ClosedTabRow>()
                while (c.moveToNext()) {
                    rows += ClosedTabRow(c.getLong(0), c.getLong(1), c.getString(2), if (c.isNull(3)) "" else c.getString(3), null, c.getLong(4))
                }
                rows
            }
        } catch (e2: Exception) {
            L.w("Couldn't read the recently closed tabs (${e2.javaClass.simpleName})")
            emptyList()
        }
    }

    /** Opens a recently closed tab again, with its back and forward history. */
    fun reopen(row: ClosedTabRow): Tab {
        io.safe("update the recently closed tabs") { db.deleteClosedTab(row.id) }
        val tab = createTab(row.url, row.profileId, null, false, false)
        tab.title = row.title
        tab.state = row.state
        scheduleSave(tab)
        select(tab)
        return tab
    }

    fun clearRecentlyClosed(profileId: Long) {
        io.safe("clear the recently closed tabs") { db.clearClosedTabs(profileId) }
    }

    private fun nextAfterClosing(tab: Tab): Tab {
        val siblings = listedWith(tab)
        siblings.firstOrNull { it.id == tab.parentId }?.let { return it }
        siblings.maxByOrNull { it.lastUsed }?.let { return it }
        // The last incognito tab closed: back to the normal tabs.
        val pid = Profiles.currentId
        return profileTabs(pid).maxByOrNull { it.lastUsed } ?: createTab("about:blank", pid, null, false, false)
    }

    /**
     * Normal tabs can come back with Undo (2.3); incognito ones are gone for good, with their data.
     * 3.0.4: pinned tabs stay open, but incognito ones: closing them all is what deletes their data,
     * as the question before it says.
     */
    fun closeAll(incognito: Boolean) {
        val closing = if (incognito) incognitoTabs() else profileTabs().filter { !it.pinned }
        if (closing.isEmpty() && !incognito && profileTabs().any { it.pinned }) {
            ui?.showMessage("Pinned tabs stay open. Unpin one to close it with the others.")
            return
        }
        closeTabs(closing, undoable = !incognito)
    }

    // ------------------------------------------------------------------ order and groups

    /** Saves the order of the normal tabs and their groups (off the main thread). */
    private fun saveOrder() {
        val order = tabs.filter { !it.incognito }.map { it.id to it.groupId }
        io.safe("save the order of the tabs") { db.saveTabOrder(order) }
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
        keepPinnedFirst()
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
        // 3.0.4: a pinned tab is in no group: dropped on another tab (or another on it), nothing is grouped.
        if (tab.pinned || target.pinned) return null
        val group = groupOf(target) ?: newGroup(target)
        addToGroup(tab, group)
        return group
    }

    /** A new group with just [tab] in it, for the menu's "Add to group". */
    fun newGroupWith(tab: Tab): TabGroup {
        unpinForGroup(tab)
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
        // 3.0: its id given here and its row written off the main thread, as tabs' are (2.6).
        val id = if (first.incognito) nextIncognitoGroupId-- else nextGroupId++
        val group = TabGroup(id, first.profileId, "", color, first.incognito)
        if (!group.incognito) insertGroupRow(group)
        groups.add(group)
        first.groupId = group.id
        return group
    }

    /**
     * 3.0: a new group's row, with the id Swift gave it, written on [io]. Db.insertTabGroup can't take
     * an id (it returns the new one, which had to be waited for on the main thread).
     */
    private fun insertGroupRow(group: TabGroup) {
        val id = group.id
        val pid = group.profileId
        val name = group.name
        val color = group.color
        io.safe("save a tab group") {
            val done = db.writableDatabase.insertWithOnConflict("tab_groups", null, ContentValues().apply {
                put("id", id)
                put("profile_id", pid)
                put("name", name)
                put("color", color)
                put("created", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (done < 0) L.w("Tab group $id wasn't saved: the id is taken")
        }
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
        unpinForGroup(tab)
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
        io.safe("save a tab group") { db.updateTabGroup(id, name, color) }
    }

    private fun deleteGroup(group: TabGroup) {
        groups.remove(group)
        if (!group.incognito) deleteGroupRow(group.id)
    }

    private fun deleteGroupRow(id: Long) {
        io.safe("delete a tab group") { db.deleteTabGroup(id) }
    }

    private fun removeIfEmpty(id: Long?) {
        val g = group(id) ?: return
        if (tabs.none { it.groupId == g.id }) deleteGroup(g)
    }

    // ------------------------------------------------------------------ inactive tabs

    /**
     * Tabs not opened for a while (Settings → Browsing) move to the "Inactive tabs" part of the tab
     * grid, as in Chrome. Grouped, incognito and playing tabs never do, nor (3.0.4) pinned ones.
     */
    fun isInactive(tab: Tab, now: Long = System.currentTimeMillis()): Boolean {
        val days = prefs.inactiveDays
        return days > 0 && !tab.incognito && tab.groupId == null && tab !== current && !tab.playing && !tab.pinned &&
            now - tab.lastUsed > days * DAY_MS
    }

    /** The tabs swiping on the address bar goes through: as in the tab grid (3.0.4: pinned ones first), without inactive ones. */
    fun swipeOrder(tab: Tab): List<Tab> {
        val now = System.currentTimeMillis()
        return listedWith(tab).filter { it === tab || !isInactive(it, now) }
    }

    /** Navigates [tab] to [url] on the user's behalf. */
    fun load(tab: Tab, url: String) {
        tab.userNavAt = System.currentTimeMillis()
        tab.waitingForConnection = false
        tab.homeShown = false
        if (tab.isHome && !tab.leftHome) {
            tab.leftHome = true
            saveLeftHome()
        }
        // A blank tab gets an empty session here; the address is set after, so it loads only once.
        val session = ensureLive(tab)
        // 3.0: what the user asked for wins over a page restore still waiting (for the ad blocker at
        // start, or for the tab's history to be read): skipped, it can't replace this page later.
        tab.restoreToken = 0
        if (tab.isBlank) tab.url = url
        // The home page may have put the page underneath to rest (MainActivity.goHome): the new
        // page must load in an active session, or it isn't drawn, saved or added to history.
        // 3.0: only once it can be seen (after the address is set: a blank tab counts as the home page).
        if (session.isOpen && pageOnScreen(tab)) session.setActive(true)
        tab.loading = true
        tab.progress = 5
        AdBlock.whenReady(TabRules.USER_WAIT_MS) {
            if (tab.session === session && session.isOpen) session.loadUri(url)
        }
        notifyTabUpdated(tab)
    }

    /** 3.0: which tabs go back to the home page from their first page (Tab.leftHome), for the next start. */
    private fun saveLeftHome() {
        val ids = tabs.filter { it.leftHome && !it.incognito }.mapTo(HashSet()) { it.id.toString() }
        prefs.sp.edit().putStringSet(LEFT_HOME, ids).apply()
    }

    /** Back on the first page of a tab opened from the home page shows the home page again. */
    fun returnHome(tab: Tab) {
        detachSession(tab)
        tab.url = "about:blank"
        tab.title = ""
        tab.state = null
        tab.sessionState = null
        tab.leftHome = false
        saveLeftHome()
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

    /**
     * 3.0: the open tab's page process ended while Swift was out of sight (Android closing it for
     * memory): its page loads again now that Swift is back, not in the background (SessionFactory.onDead).
     */
    fun reviveOnReturn(tab: Tab) {
        if (tab !== current || tab.session != null || tab.isHome || tab.crashed) return
        L.d("Tab ${tab.id}: its page loads again, now that Swift is back")
        tab.autoRevivedAt = SystemClock.elapsedRealtime()
        revive(tab)
    }

    fun setDesktop(tab: Tab, desktop: Boolean) {
        // Remembered for the site, so its other pages and tabs get the same version. From an incognito
        // tab, only until incognito ends: it leaves nothing behind (2.6; 3.0.1: before, it was
        // forgotten at the tab's next page).
        UrlUtils.site(tab.url)?.let { if (UrlUtils.isHttp(tab.url)) com.swiftbrowser.core.SiteSettings.setDesktop(it, desktop, tab.incognito) }
        tab.desktop = desktop
        tab.session?.settings?.let { s ->
            s.setUserAgentMode(
                if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            // 3.0: with "Tell sites a newer Firefox version", its desktop or mobile kind.
            s.setUserAgentOverride(com.swiftbrowser.engine.UserAgent.overrideFor(desktop))
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
        return openedByEngine(tab)
    }

    /** An extension (uBlock Origin) opened a page such as its dashboard or logger. */
    fun openForExtension(url: String?): GeckoSession =
        openedByEngine(createTab(url ?: "about:blank", Profiles.currentId, current, false, false))

    /**
     * 3.0: a new tab whose session the engine opens itself, as the new window of a page (or an
     * extension): it gets the session unopened, from onNewSession (onNewTab), and opens it once it
     * has the answer, a moment later. That window is the real one: it has its opener, the form it was
     * sent and the referrer. Until then the session is kept as it is ([Tab.sessionOpening]); once
     * open, it goes on screen as select() puts any live one there. Before 3.0, select() took it for a
     * dead session and loaded the address again in a second one (fetched twice, without its opener),
     * and the engine's window stayed open, hidden, for good: its alerts showed over other pages, and
     * window.close() or uBlock Origin closing an ad pop-up did nothing.
     */
    private fun openedByEngine(tab: Tab): GeckoSession {
        tab.popup = true
        val session = SessionFactory.create(tab)
        tab.sessionOpening = true
        // Its progress shows at once, and the engine's first blank page doesn't take its address.
        if (!tab.isBlank) {
            tab.loading = true
            tab.progress = 5
        }
        select(tab)
        watchOpening(tab, session, 0)
        return session
    }

    /** Waits for the engine to open [session] (see [openedByEngine]): a couple of passes, then 50 ms at a time, for 2 s at most. */
    private fun watchOpening(tab: Tab, session: GeckoSession, tries: Int) {
        main.postDelayed({
            when {
                session.isOpen -> onOpenedByEngine(tab, session)
                tries < 40 -> watchOpening(tab, session, tries + 1)
                tab.session === session -> {
                    // Never opened (its opener closed first, say): the tab loads its address itself.
                    L.w("Tab ${tab.id}: the engine didn't open its new window; loading it again")
                    tab.sessionOpening = false
                    tab.loading = false
                    if (tab === current) {
                        val s = ensureLive(tab)
                        if (s.isOpen) activate(tab, s)
                        ui?.onTabSelected(tab)
                    } else {
                        detachSession(tab)
                    }
                }
            }
        }, if (tries < 2) 0L else 50L)
    }

    private fun onOpenedByEngine(tab: Tab, session: GeckoSession) {
        if (tab.session !== session) {
            // Closed (or let go of) before the engine had opened it: nobody shows this window.
            try {
                session.close()
            } catch (_: Exception) {
            }
            return
        }
        tab.sessionOpening = false
        if (tab === current) {
            activate(tab, session)
            ui?.onTabSelected(tab)
        } else {
            // Left already (another pop-up came after it): it waits like any tab in the background.
            session.setActive(false)
            session.setFocused(false)
            Engine.runtime.webExtensionController.setTabActive(session, false)
        }
        trimLiveSessions()
    }

    // ------------------------------------------------------------------ profiles

    fun switchProfile(id: Long) {
        if (id == Profiles.currentId && current?.profileId == id && current?.incognito == false) return
        val previous = current
        Profiles.setCurrent(id)
        val target = profileTabs(id).maxByOrNull { it.lastUsed } ?: createTab("about:blank", id, null, false, false)
        select(target)
        // Other profiles' pages don't need to stay in memory (3.0.1: but the ones kept loaded).
        tabs.filter { !it.incognito && it.profileId != id && it.session != null && !it.kept }.forEach { suspend(it) }
        if (previous != null && !previous.incognito && previous.profileId != id && !previous.kept) suspend(previous)
        ui?.onTabsChanged()
    }

    fun deleteProfile(id: Long) {
        if (Profiles.all().size <= 1) return
        val hadPinned = profileTabs(id).any { it.pinned }
        for (t in profileTabs(id)) {
            detachSession(t)
            tabs.remove(t)
            dirty.remove(t)
            Thumbnails.remove(t)
        }
        groups.removeAll { !it.incognito && it.profileId == id }
        if (hadPinned) savePinned()
        Engine.storage.clearDataForSessionContext(Profiles.contextId(id))
        // 3.0: its rows (a long history, its bookmarks) are deleted off the main thread; Swift moves
        // to another profile first, as Profiles.delete would.
        if (Profiles.currentId == id) Profiles.all().firstOrNull { it.id != id }?.let { Profiles.setCurrent(it.id) }
        io.safe("delete a profile") {
            Profiles.delete(id)
            main.post { ui?.onTabsChanged() }
        }
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
        io.safe("add a page to the history") { db.recordVisit(pid, url, "") }
    }

    fun setHistoryTitle(tab: Tab, url: String, title: String) {
        if (tab.incognito) return
        val pid = tab.profileId
        io.safe("save a page's title in the history") { db.setHistoryTitle(pid, url, title) }
    }

    fun notifyTabUpdated(tab: Tab) {
        ui?.onTabUpdated(tab)
    }

    /** When the next save runs (uptime), 0 when none is waiting. */
    private var saveAt = 0L

    /**
     * Writes the tab soon. [stateOnly]: only its scroll position or form state changed (that comes
     * every couple of seconds while scrolling): within 12 s then, not 2 (2.6). Going to the
     * background writes everything at once (flushSaves).
     */
    fun scheduleSave(tab: Tab, stateOnly: Boolean = false) {
        if (tab.incognito) return
        dirty.add(tab)
        val at = android.os.SystemClock.uptimeMillis() + if (stateOnly) 12_000L else 2000L
        if (saveAt == 0L || at < saveAt) {
            main.removeCallbacks(saveRunnable)
            main.postAtTime(saveRunnable, at)
            saveAt = at
        }
    }

    /** Tabs whose history was too long to save, said once in the log (only used on [io]). */
    private val tooLongNoted = HashSet<Long>()

    private class SaveRow(
        val id: Long, val url: String, val title: String,
        val sessionState: GeckoSession.SessionState?, val state: String?, val lastUsed: Long, val desktop: Boolean,
        val keepState: Boolean = false,
    )

    fun flushSaves() {
        main.removeCallbacks(saveRunnable)
        saveAt = 0L
        if (dirty.isEmpty()) return
        val rows = dirty.filter { tabs.contains(it) && !it.incognito }.map { t ->
            SaveRow(t.id, t.url, t.title, t.sessionState, t.state, t.lastUsed, t.desktop, keepState = !t.stateLoaded && t.sessionState == null)
        }
        dirty.clear()
        io.safe("save the tabs") {
            for (r in rows) {
                // Turning the engine's state into text can take a while for long histories.
                val state = r.sessionState?.toString() ?: r.state
                // 3.0: nothing Android 7 couldn't read back at the next start (a row over 2 MB stopped
                // Swift then): a tab showing a picture from its data: address is saved as a blank one,
                // and a history too long to keep is left out (the page itself stays open meanwhile).
                val keep = TabRules.storable(r.url)
                val stored = if (keep) TabRules.storedState(state) else null
                if (keep && state != null && stored == null && tooLongNoted.add(r.id)) {
                    L.w("Tab ${r.id}'s history is too long to keep (${state.length / 1024} KB): only its page is saved")
                }
                db.updateTab(
                    r.id, TabRules.storedUrl(r.url), if (keep) TabRules.storedTitle(r.title) else "", stored,
                    r.lastUsed, r.desktop, r.keepState && keep,
                )
            }
        }
    }

    /** 3.0: [flushSaves], then waits (at most [timeoutMs]) until it's written: Swift is about to end. */
    fun flushSavesAndWait(timeoutMs: Long = 2000) {
        flushSaves()
        try {
            io.submit(Runnable {}).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            L.w("Saving the tabs took too long", e)
        }
    }
}
