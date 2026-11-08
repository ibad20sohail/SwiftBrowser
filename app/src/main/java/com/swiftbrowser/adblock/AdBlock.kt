package com.swiftbrowser.adblock

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.IncognitoChoices
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.UrlUtils
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.CopyOnWriteArrayList

enum class AdLevel(val key: String, val label: String, val summary: String) {
    OFF(
        "off", "Off",
        "No blocking. Pages load exactly as the site sends them.",
    ),
    BALANCED(
        "balanced", "Balanced",
        "Blocks ads, trackers, harmful sites and pop-ups that open without a tap. Rarely breaks sites.",
    ),
    HIGH(
        "high", "High",
        "Also blocks pop-ups and jumps to other sites, ads made for phones, ads laid over pages and fake login (phishing) sites.",
    ),
    EXTREME(
        "extreme", "Extreme",
        "Also hides cookie notices and other annoyances, and blocks everything a site loads from other companies. " +
            "Strongest, but can stop video players: if a video won't load, set that site to High.",
    );

    companion object {
        fun fromKey(key: String?): AdLevel = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/**
 * Owns the ad-blocking configuration and pushes it to the bundled uBlock Origin through a small
 * native-messaging bridge (assets/extensions/ublock/js/swift-bridge.js).
 *
 * Filter lists follow the global level. Per-site levels control uBO's trusted-site list (Off),
 * its dynamic rules (Extreme blocks third-party scripts and frames) and the app's own pop-up and
 * redirect rules ([NavPolicy]).
 */
object AdBlock {
    /** 3.0.5: the page view of [wakeUp] closes by then even if the ad blocker never gets ready. */
    private const val WAKE_LIMIT_MS = 60_000L

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private val overrides = LinkedHashMap<String, AdLevel>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var port: WebExtension.Port? = null

    var extension: WebExtension? = null
        private set

    /** True once uBlock Origin has loaded its filters. */
    var ready = false
        private set

    /** When it got ready (uptime ms; 0 before), 3.0. */
    var readyAt = 0L
        private set

    val uboVersion: String get() = extension?.metaData?.version ?: ""

    /** uBlock Origin's "page blocked" screen (a phishing or malware site stopped before it loaded). */
    fun isBlockedPage(url: String?): Boolean {
        val base = extension?.metaData?.baseUrl ?: return false
        return url != null && url.startsWith(base + "document-blocked.html")
    }

    /** The address the "page blocked" screen stopped (from its details). */
    fun blockedPageTarget(url: String?): String? = try {
        val details = android.net.Uri.parse(url).getQueryParameter("details")
        details?.let { org.json.JSONObject(it).optString("url").ifEmpty { null } }
    } catch (_: Exception) {
        null
    }

    private val BALANCED_LISTS = listOf(
        "user-filters", "ublock-filters", "ublock-badware", "ublock-privacy", "ublock-unbreak",
        "ublock-quick-fixes", "easylist", "easyprivacy", "urlhaus-1", "plowe-0",
    )
    // Every list costs memory and filtering time on a 2 GB phone, so High sticks to ads, pop-ups and
    // overlays, plus (2.2) the phishing list: the engine gets no security fixes any more, so fake
    // login pages are blocked at High too. Annoyance lists are only in Extreme; the malware list
    // (urlhaus-1) is on at every level.
    private val HIGH_EXTRA = listOf(
        "adguard-mobile", "adguard-popup-overlays", "adguard-mobile-app-banners", "curben-phishing",
    )
    private val EXTREME_EXTRA = listOf(
        "ublock-annoyances", "ublock-cookies-easylist", "fanboy-cookiemonster",
        "adguard-generic", "adguard-spyware-url", "adguard-other-annoyances", "adguard-widgets",
        "easylist-annoyances", "easylist-notifications", "easylist-newsletters", "easylist-chat",
        "adguard-social", "fanboy-social", "block-lan",
    )

    /** "Hide cookie pop-ups" (Settings): the two lists that hide cookie consent notices. Part of Extreme anyway. */
    private val COOKIE_LISTS = listOf("ublock-cookies-easylist", "fanboy-cookiemonster")

    fun init(prefs: Prefs, db: Db) {
        this.prefs = prefs
        this.db = db
        overrides.clear()
        db.siteLevels().forEach { (site, key) -> overrides[site] = AdLevel.fromKey(key) }
    }

    val global: AdLevel get() = prefs.adLevel

    /**
     * Levels chosen from incognito tabs (3.0.1): in memory only, gone when the last one closes. One
     * uBO filters every tab, so until then they count for the site's normal tabs too.
     */
    private val incognitoOverrides = IncognitoChoices<AdLevel>()

    /** The site's own level now (with one chosen from an incognito tab, 3.0.1). */
    fun overrideFor(site: String?): AdLevel? = site?.let { incognitoOverrides.get(it, overrides[it]) }

    /** The site's saved level only, for Undo in a normal tab. */
    fun savedOverrideFor(site: String?): AdLevel? = site?.let { overrides[it] }

    /** The saved levels (Settings, backups, clearing): never the ones chosen from incognito tabs. */
    fun overrides(): Map<String, AdLevel> = LinkedHashMap(overrides)

    /** The levels in use: the saved ones with incognito's choices on top. */
    private fun inUse(): Map<String, AdLevel> = incognitoOverrides.applyTo(overrides)

    /** The level that applies to a page. */
    fun levelFor(url: String?): AdLevel = overrideFor(UrlUtils.site(url)) ?: global

    fun setGlobal(level: AdLevel) {
        if (prefs.adLevel != level) prefs.adLevel = level
        push()
        // 3.0: with ad blocking off, pages waiting for uBO load now.
        if (level == AdLevel.OFF) runWaiting()
        notifyListeners()
    }

    /** [incognito]: chosen from an incognito tab, so kept in memory only (3.0.1). */
    fun setOverride(site: String, level: AdLevel?, incognito: Boolean = false) {
        if (incognito) {
            incognitoOverrides.set(site, level)
        } else {
            // A level chosen in a normal tab counts in incognito too.
            incognitoOverrides.drop(site)
            if (level == null) overrides.remove(site) else overrides[site] = level
            // 3.0: written off the main thread (the levels are only read back at the next start).
            val key = level?.key
            com.swiftbrowser.tabs.TabManager.io.execute {
                try {
                    db.setSiteLevel(site, key)
                } catch (e: Exception) {
                    L.w("Couldn't save a site's ad blocking", e)
                }
            }
        }
        push()
        notifyListeners()
    }

    /** The last incognito tab closed: the levels chosen there go (3.0.1). */
    fun onIncognitoEnded() {
        if (!incognitoOverrides.clear()) return
        push()
        notifyListeners()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    // ------------------------------------------------------------------ uBO bridge

    fun attach(ext: WebExtension) {
        extension = ext
        ext.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(p: WebExtension.Port) {
                port = p
                p.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        onBridgeMessage(message as? JSONObject ?: return)
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (this@AdBlock.port === port) this@AdBlock.port = null
                    }
                })
                push()
            }
        }, "swift")
        L.d("uBlock Origin ${ext.metaData.version} attached")
        // 3.0.5: the start-up screen goes on from "Starting the browser" to the ad blocker.
        notifyListeners()
    }

    private val main = Handler(Looper.getMainLooper())
    private val readyWaiters = ArrayList<() -> Unit>()

    /**
     * Runs [action] once uBlock Origin has its filters loaded, so pages restored at app start don't
     * load unfiltered. [timeoutMs] > 0: after that long at most (a page the user just asked for);
     * 0: however long it takes, until [loadWaitingNow] (3.0: before, pages restored at start gave up
     * after 8 s, and once one had, later ones didn't wait at all; uBO was ready 26 s after a cold start
     * in a test).
     */
    /** 3.0.5: the page view that starts the ad blocker ([wakeUp]), while it's open. */
    private var waker: org.mozilla.geckoview.GeckoSession? = null

    /**
     * 3.0.5: starts the ad blocker as Swift opens on the home page. The engine starts extensions only
     * after a page view has been opened and it has gone idle (GeckoView 143), so with the home page
     * open it waited until the first page was asked for, which then waited 3 seconds and loaded
     * without blocking. A page view that shows nothing is opened here, and closes when the ad blocker
     * is ready. (With a site open its own page view starts it; opening one earlier gains nothing.)
     */
    fun wakeUp() {
        if (ready || global == AdLevel.OFF || waker != null) return
        val session = org.mozilla.geckoview.GeckoSession(org.mozilla.geckoview.GeckoSessionSettings.Builder().build())
        waker = session
        session.open(com.swiftbrowser.engine.Engine.runtime)
        session.setActive(false)
        whenReady(WAKE_LIMIT_MS) {
            session.close()
            waker = null
        }
    }

    fun whenReady(timeoutMs: Long, action: () -> Unit) {
        if (ready || global == AdLevel.OFF) {
            action()
            return
        }
        var done = false
        val run: () -> Unit = {
            if (!done) {
                done = true
                action()
            }
        }
        readyWaiters.add(run)
        if (timeoutMs > 0) {
            main.postDelayed({
                if (readyWaiters.remove(run)) L.w("uBO not ready after ${timeoutMs}ms; loading anyway")
                run()
            }, timeoutMs)
        }
    }

    /**
     * 3.0: "Load now" on the start-up screen: the pages waiting for uBO ([whenReady]) load at once,
     * without ad blocking. Returns how many there were.
     */
    fun loadWaitingNow(): Int {
        val n = readyWaiters.size
        if (n > 0) L.w("Loading $n page${if (n == 1) "" else "s"} before uBO is ready (Load now)")
        runWaiting()
        return n
    }

    private fun runWaiting() {
        val waiting = ArrayList(readyWaiters)
        readyWaiters.clear()
        waiting.forEach { it() }
    }

    private fun onBridgeMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "ready" -> {
                ready = true
                if (readyAt == 0L) readyAt = android.os.SystemClock.uptimeMillis()
                L.d("uBO ready, lists=${msg.optJSONArray("lists")?.length()}")
                runWaiting()
                notifyListeners()
            }
            "applied" -> L.d("uBO config applied (lists reloaded=${msg.optBoolean("reloaded")})")
            "log" -> L.d("uBO: ${msg.optString("text")}")
        }
    }

    /** Sends the full desired configuration. The bridge only changes what differs. */
    private fun push() {
        val p = port ?: return
        val g = global
        val levelLists = when (g) {
            AdLevel.OFF, AdLevel.BALANCED -> BALANCED_LISTS
            AdLevel.HIGH -> BALANCED_LISTS + HIGH_EXTRA
            AdLevel.EXTREME -> BALANCED_LISTS + HIGH_EXTRA + EXTREME_EXTRA
        }
        val lists = if (prefs.hideCookieNotices) (levelLists + COOKIE_LISTS).distinct() else levelLists
        val inUse = inUse()
        val msg = JSONObject()
            .put("type", "apply")
            .put("lists", JSONArray(lists))
            .put("rules", AdRules.dynamicRules(g, inUse))
            .put("trusted", JSONArray(AdRules.trusted(g, inUse)))
            // 3.0.1: what uBO may write down: the same without the levels chosen from incognito tabs.
            .put("rulesKeep", AdRules.dynamicRules(g, overrides))
            .put("trustedKeep", JSONArray(AdRules.trusted(g, overrides)))
        try {
            p.postMessage(msg)
        } catch (e: Exception) {
            L.w("uBO push failed", e)
        }
    }

    /** Settings → Ad blocking → Hide cookie pop-ups changed. */
    fun applyCookieNotices() {
        push()
        notifyListeners()
    }

    /**
     * Starts uBlock Origin's element picker on [tab]'s page: tap something (a leftover ad, a
     * banner) and create a filter that hides it on that site from now on.
     */
    fun pickElement(tab: com.swiftbrowser.tabs.Tab): Boolean {
        val p = port ?: return false
        val extTabId = com.swiftbrowser.media.Helper.extensionTabId(tab) ?: return false
        return try {
            p.postMessage(JSONObject().put("type", "pick").put("tabId", extTabId))
            true
        } catch (e: Exception) {
            L.w("uBO picker failed", e)
            false
        }
    }

    /** Downloads newer versions of filter lists that are due, then rebuilds the filters. */
    fun updateFilterLists(): Boolean {
        val p = port ?: return false
        return try {
            p.postMessage(JSONObject().put("type", "update"))
            true
        } catch (e: Exception) {
            L.w("uBO update failed", e)
            false
        }
    }

    /**
     * Lists are updated while the app is in the background (uBO's own timer is off): rebuilding
     * the filters keeps uBO busy for a while on a slow phone, and pages would wait for it.
     */
    fun updateInBackground() {
        if (!ready) return
        val now = System.currentTimeMillis()
        val last = prefs.sp.getLong(LAST_UPDATE_CHECK, 0)
        if (now - last < UPDATE_CHECK_INTERVAL_MS) return
        if (updateFilterLists()) prefs.sp.edit().putLong(LAST_UPDATE_CHECK, now).apply()
    }

    private const val LAST_UPDATE_CHECK = "ubo_last_update_check"
    private const val UPDATE_CHECK_INTERVAL_MS = 6L * 3600 * 1000
}
