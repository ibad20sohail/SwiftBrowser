package com.swiftbrowser.adblock

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.core.Db
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
        "Blocks ads, trackers, malware sites and pop-ups that open without a tap. Rarely breaks sites.",
    ),
    HIGH(
        "high", "High",
        "Also blocks pop-ups and redirects to other sites, mobile ads, ad overlays and fake login (phishing) sites.",
    ),
    EXTREME(
        "extreme", "Extreme",
        "Also hides cookie notices and other annoyances, and blocks all third-party scripts and frames. " +
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

    /** Pages on these sites keep third-party scripts in Extreme mode, or signing in would break. */
    private val EXTREME_EXEMPT = listOf("google.com", "youtube.com", "gmail.com", "accounts.google.com")

    private val BEHIND_THE_SCENE = listOf(
        "behind-the-scene * * noop",
        "behind-the-scene * image noop",
        "behind-the-scene * 3p noop",
        "behind-the-scene * inline-script noop",
        "behind-the-scene * 1p-script noop",
        "behind-the-scene * 3p-script noop",
        "behind-the-scene * 3p-frame noop",
    )

    fun init(prefs: Prefs, db: Db) {
        this.prefs = prefs
        this.db = db
        overrides.clear()
        db.siteLevels().forEach { (site, key) -> overrides[site] = AdLevel.fromKey(key) }
    }

    val global: AdLevel get() = prefs.adLevel

    fun overrideFor(site: String?): AdLevel? = site?.let { overrides[it] }

    fun overrides(): Map<String, AdLevel> = LinkedHashMap(overrides)

    /** The level that applies to a page. */
    fun levelFor(url: String?): AdLevel = overrideFor(UrlUtils.site(url)) ?: global

    fun setGlobal(level: AdLevel) {
        if (prefs.adLevel != level) prefs.adLevel = level
        push()
        notifyListeners()
    }

    fun setOverride(site: String, level: AdLevel?) {
        if (level == null) overrides.remove(site) else overrides[site] = level
        db.setSiteLevel(site, level?.key)
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
    }

    private val main = Handler(Looper.getMainLooper())
    private val readyWaiters = ArrayList<() -> Unit>()

    /**
     * Runs [action] once uBlock Origin has its filters loaded (or after [timeoutMs]), so pages
     * restored at app start don't load unfiltered.
     */
    fun whenReady(timeoutMs: Long, action: () -> Unit) {
        if (ready || gaveUp || global == AdLevel.OFF) {
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
        main.postDelayed({
            if (readyWaiters.remove(run)) {
                // Wait only once: if uBO is stuck, later pages shouldn't be delayed too.
                gaveUp = true
                L.w("uBO not ready after ${timeoutMs}ms; loading anyway")
            }
            run()
        }, timeoutMs)
    }

    private var gaveUp = false

    private fun onBridgeMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "ready" -> {
                ready = true
                L.d("uBO ready, lists=${msg.optJSONArray("lists")?.length()}")
                val waiting = ArrayList(readyWaiters)
                readyWaiters.clear()
                waiting.forEach { it() }
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
        val msg = JSONObject()
            .put("type", "apply")
            .put("lists", JSONArray(lists))
            .put("rules", dynamicRules(g))
            .put("trusted", JSONArray(trustedDirectives(g)))
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

    private fun dynamicRules(g: AdLevel): String {
        val rules = ArrayList(BEHIND_THE_SCENE)
        if (g == AdLevel.EXTREME) {
            rules += "* * 3p-script block"
            rules += "* * 3p-frame block"
            val relaxed = EXTREME_EXEMPT + overrides.filterValues { it != AdLevel.EXTREME }.keys
            for (site in relaxed.distinct()) {
                rules += "$site * 3p-script noop"
                rules += "$site * 3p-frame noop"
            }
        } else {
            for (site in overrides.filterValues { it == AdLevel.EXTREME }.keys) {
                rules += "$site * 3p-script block"
                rules += "$site * 3p-frame block"
            }
        }
        return rules.joinToString("\n")
    }

    /** uBO "trusted sites": filtering is fully off for matching pages. */
    private fun trustedDirectives(g: AdLevel): List<String> {
        if (g == AdLevel.OFF) {
            val enabled = overrides.filterValues { it != AdLevel.OFF }.keys
            if (enabled.isEmpty()) return listOf("/^/")
            // Sites are plain hostnames ([a-z0-9.-]), so only the dots need escaping for JS RegExp.
            val alt = enabled.joinToString("|") { it.replace(".", "\\.") }
            // Everything is trusted (unfiltered) except pages on the sites the user turned blocking on for.
            return listOf("/^(?!https?:\\/\\/([^\\/:]*\\.)?($alt)(:\\d+)?\\/)/")
        }
        return overrides.filterValues { it == AdLevel.OFF }.keys.toList()
    }
}
