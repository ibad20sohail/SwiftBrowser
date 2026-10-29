package com.swiftbrowser.media

import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.NavPolicy
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.L
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/** A video stream or file a page loaded. */
data class MediaItem(
    val url: String,
    val kind: Kind,
    val mime: String,
    val size: Long,
    val headers: Map<String, String>,
    val frameUrl: String,
    val pageUrl: String,
    val pageTitle: String,
    val time: Long,
) {
    enum class Kind { HLS, FILE }
}

/**
 * Talks to the bundled helper extension (assets/extensions/helper):
 *  - its background script reports media requests per extension tab id,
 *  - content scripts in every frame report taps on links and apply the playback speed.
 */
object Helper {
    private const val NATIVE_APP = "swift"

    var extension: WebExtension? = null
        private set

    private var backgroundPort: WebExtension.Port? = null
    private val tabsByExtId = HashMap<Int, Tab>()
    private val pendingMedia = HashMap<Int, MutableList<JSONObject>>()

    fun attach(ext: WebExtension) {
        extension = ext
        // Documents asked for before the extension was ready (opened from another app at start-up).
        com.swiftbrowser.docs.Docs.onViewerReady()
        ext.setMessageDelegate(object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                backgroundPort = port
                sendLook()
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        (message as? JSONObject)?.let { onBackgroundMessage(it) }
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (backgroundPort === port) backgroundPort = null
                    }
                })
            }

            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                (message as? JSONObject)?.let { onBackgroundMessage(it) }
                return null
            }
        }, NATIVE_APP)
        L.d("Helper extension attached")
    }

    /** Called for every live session of a tab, once both exist. */
    fun attachSession(tab: Tab, session: GeckoSession) {
        val ext = extension ?: return
        session.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                tab.helperPorts.add(port)
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        (message as? JSONObject)?.let { onContentMessage(tab, it, port) }
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        tab.helperPorts.remove(port)
                    }
                })
            }

            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                (message as? JSONObject)?.let { onContentMessage(tab, it, null) }
                return null
            }
        }, NATIVE_APP)
    }

    /** The tab's session went away (closed or suspended). */
    /** The tab's id as extensions know it (the same for all of them), once its page has said hello. */
    fun extensionTabId(tab: Tab): Int? = tabsByExtId.entries.firstOrNull { it.value === tab }?.key

    fun detachTab(tab: Tab) {
        tab.helperPorts.clear()
        tabsByExtId.entries.removeAll { it.value === tab }
    }

    private fun onContentMessage(tab: Tab, msg: JSONObject, port: WebExtension.Port?) {
        when (msg.optString("type")) {
            "hello" -> {
                val extTabId = msg.optInt("tabId", -1)
                if (msg.optBoolean("top") && extTabId >= 0) {
                    tabsByExtId.entries.removeAll { it.value === tab }
                    tabsByExtId[extTabId] = tab
                    pendingMedia.remove(extTabId)?.forEach { addMedia(tab, it) }
                }
                if (tab.speed != 1.0 && port != null) {
                    port.postMessage(JSONObject().put("type", "speed").put("rate", tab.speed))
                }
            }
            "click" -> tab.lastClick = NavPolicy.Click(msg.optString("href"), System.currentTimeMillis())
            "icon" -> if (!tab.incognito) Favicons.onPageIcon(msg.optString("page"), msg.optString("href").ifBlank { null })
            "playing" -> if (!tab.videoPlaying) {
                tab.videoPlaying = true
                TabManager.ui?.onPlayingChanged(tab)
            }
            "video-pos" -> WatchPositions.onPosition(tab, msg)
            "reader-place" -> Unfinished.onReaderPlace(tab, msg)
            // Swift's document viewer (2.4).
            "doc-hello", "doc-read", "doc-range", "doc-place", "doc-action", "doc-reading", "doc-zip", "doc-save" ->
                com.swiftbrowser.docs.Docs.onMessage(tab, msg, port)
            // Read aloud, from the reader view page in this tab: it hears back how far it got.
            "tts-start", "tts-pause", "tts-resume", "tts-stop", "tts-skip", "tts-rate" -> {
                val ctx = SwiftApp.instance ?: return
                if (port != null) {
                    ReadAloud.listener = { state ->
                        try {
                            port.postMessage(state)
                        } catch (_: Exception) {
                            ReadAloud.listener = null
                        }
                    }
                }
                if (msg.optString("type") == "tts-start") {
                    ReadAloud.tabId = tab.id
                    ReadAloud.incognito = tab.incognito
                }
                ReadAloud.onMessage(ctx, msg)
            }
            "video-start" -> WatchPositions.onStart(tab, msg)
            "media-ended" -> SleepTimer.onMediaEnded(tab)
            // For the log (2.7): players failing or stopping to load.
            "media-error" -> L.w(
                "Tab ${tab.id} (${if (tab.incognito) "incognito" else com.swiftbrowser.core.UrlUtils.siteName(tab.url)}): a video failed, " +
                    "code ${msg.optInt("code")} ${msg.optString("text").take(200)}",
            )
            "media-waiting" -> L.d(
                "Tab ${tab.id}: the video stopped to load ${msg.optInt("count")} time(s) " +
                    "(at ${msg.optInt("t")} s, ${msg.optInt("buffered")} s loaded ahead)",
            )
        }
    }

    private fun onBackgroundMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "media" -> {
                val extTabId = msg.optInt("tabId", -1)
                val tab = tabsByExtId[extTabId]
                if (tab != null) {
                    addMedia(tab, msg)
                } else if (extTabId >= 0) {
                    // The tab's content script hasn't said hello yet; keep it briefly.
                    val list = pendingMedia.getOrPut(extTabId) { ArrayList() }
                    if (list.size < 30) list.add(msg)
                    if (pendingMedia.size > 50) pendingMedia.remove(pendingMedia.keys.first())
                }
            }
            "headers" -> {
                val id = msg.optInt("id", -1)
                val cb = headerCallbacks.remove(id) ?: return
                cb(toMap(msg.optJSONObject("headers")))
            }
            "reader-ready" -> {
                val tab = tabsByExtId[msg.optInt("tabId", -1)] ?: return
                val url = msg.optString("url")
                tab.quietReader = false
                if (url.startsWith("moz-extension://") && tab.session != null) TabManager.load(tab, url)
            }
            "reader-failed" -> {
                val tab = tabsByExtId[msg.optInt("tabId", -1)] ?: return
                if (tab.quietReader) {
                    tab.quietReader = false
                    return
                }
                if (tab === TabManager.current) TabManager.ui?.showMessage("This page has no article to show in reader view")
            }
        }
    }

    // ------------------------------------------------------------------ reader view and Lite mode

    /** Shows the page's article in reader view (the extension finds it with Readability). */
    fun startReader(tab: Tab): Boolean {
        val msg = JSONObject().put("type", "reader")
        var sent = false
        for (p in tab.helperPorts.toList()) {
            try {
                p.postMessage(msg)
                sent = true
            } catch (_: Exception) {
                tab.helperPorts.remove(p)
            }
        }
        return sent
    }

    /** Reader view pages are the extension's own; they show as such in the address bar. */
    fun isReaderUrl(url: String?): Boolean =
        url != null && url.startsWith("moz-extension://") && url.contains("/reader/view.html")

    /**
     * Tells the extension which styles pages get from their first byte on: Lite mode (but not on
     * the sites left out of it), and the lighter YouTube and TheFlixer (unless sites are shown as
     * designed); and whether playing pages are kept playing out of view.
     */
    fun sendLook() {
        val port = backgroundPort ?: return
        val prefs = SwiftApp.prefs
        try {
            port.postMessage(
                JSONObject().put("type", "look").put("lite", prefs.liteMode || com.swiftbrowser.engine.Power.saving).put("fixes", !prefs.sitesAsDesigned)
                    .put("liteExcept", org.json.JSONArray(com.swiftbrowser.core.SiteSettings.liteExceptions()))
                    .put("darkSites", org.json.JSONArray(com.swiftbrowser.core.SiteSettings.darkPages()))
                    .put("keepPlaying", prefs.keepPlaying)
                    .put("readerFont", prefs.readerFont).put("readerSpacing", prefs.readerSpacing)
                    .put("accentLight", accentHex(night = false)).put("accentDark", accentHex(night = true))
                    .put("pageLight", pageColors(false)).put("pageDark", pageColors(true)),
            )
        } catch (e: Exception) {
            L.w("Couldn't send the look", e)
        }
    }

    /** The accent colour as "#rrggbb" in light or dark mode, for Swift's own pages (the document viewer, 2.4). */
    fun accentColor(night: Boolean) = accentHex(night)

    /**
     * Swift's own colours for its pages (reader view, the document viewer, 2.6): the page, cards, text,
     * lines and the accent with the colour of text on it, as they look in light or dark mode.
     */
    fun pageColors(night: Boolean): org.json.JSONObject {
        val app = SwiftApp.instance ?: return org.json.JSONObject()
        fun hex(res: Int) = String.format("#%06x", com.swiftbrowser.ui.Palette.colorFor(app, night, res) and 0xFFFFFF)
        return org.json.JSONObject()
            .put("bg", hex(com.swiftbrowser.R.color.page_bg))
            .put("surface", hex(com.swiftbrowser.R.color.card_bg))
            .put("text", hex(com.swiftbrowser.R.color.text_primary))
            .put("muted", hex(com.swiftbrowser.R.color.text_secondary))
            .put("line", hex(com.swiftbrowser.R.color.divider))
            .put("accent", accentHex(night))
            .put("onAccent", hex(com.swiftbrowser.R.color.on_accent))
    }

    /** The accent colour as "#rrggbb", as it looks in light or dark mode (for reader view). */
    private fun accentHex(night: Boolean): String {
        val app = SwiftApp.instance ?: return if (night) "#8ab4ff" else "#1b66e4"
        val config = android.content.res.Configuration(app.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
        val c = app.createConfigurationContext(config).let { com.swiftbrowser.ui.Palette.color(it, com.swiftbrowser.ui.Looks.accent.primary) }
        return String.format("#%06x", c and 0xFFFFFF)
    }

    /** Reader view's font and spacing travel with the look (open reader pages follow at once). */
    fun sendReaderStyle() = sendLook()

    private fun addMedia(tab: Tab, msg: JSONObject) {
        val url = msg.optString("url")
        if (url.isBlank() || tab.media.any { it.url == url }) return
        val item = MediaItem(
            url = url,
            kind = if (msg.optString("kind") == "hls") MediaItem.Kind.HLS else MediaItem.Kind.FILE,
            mime = msg.optString("mime"),
            size = msg.optLong("size", -1),
            headers = toMap(msg.optJSONObject("headers")),
            frameUrl = msg.optString("frameUrl"),
            pageUrl = tab.url,
            pageTitle = tab.title,
            time = System.currentTimeMillis(),
        )
        tab.media.add(item)
        while (tab.media.size > 40) tab.media.removeAt(0)
        TabManager.notifyTabUpdated(tab)
    }

    private fun toMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = HashMap<String, String>()
        o.keys().forEach { k -> out[k.lowercase()] = o.optString(k) }
        return out
    }

    /** Applies a playback speed to every video in every frame of the tab. */
    fun setSpeed(tab: Tab, rate: Double) {
        tab.speed = rate
        val msg = JSONObject().put("type", "speed").put("rate", rate)
        for (p in tab.helperPorts.toList()) {
            try {
                p.postMessage(msg)
            } catch (e: Exception) {
                tab.helperPorts.remove(p)
            }
        }
    }

    /** Skips the full-screen video forward or back (double-tap on its sides). */
    fun seek(tab: Tab, deltaSeconds: Double) {
        postToFrames(tab, JSONObject().put("type", "seek").put("delta", deltaSeconds))
    }

    /** Double speed while a finger is held on the full-screen video, back to normal when let go. */
    fun holdSpeed(tab: Tab, on: Boolean) {
        postToFrames(tab, JSONObject().put("type", "rate-hold").put("on", on))
    }

    /** Whatever is playing in the tab skips [deltaSeconds] (headset and notification buttons). */
    fun seekPlaying(tab: Tab, deltaSeconds: Double) {
        postToFrames(tab, JSONObject().put("type", "seek-playing").put("delta", deltaSeconds))
    }

    /** The sleep timer: what plays in the tab gets quieter over [ms] and then pauses. */
    fun fadeOut(tab: Tab, ms: Long) {
        postToFrames(tab, JSONObject().put("type", "fade").put("ms", ms))
    }

    /** Continue watching: the video [length] seconds long goes to [position]. */
    fun resume(tab: Tab, position: Double, length: Double) {
        postToFrames(tab, JSONObject().put("type", "resume").put("t", position).put("d", length))
    }

    private fun postToFrames(tab: Tab, msg: JSONObject) {
        for (p in tab.helperPorts.toList()) {
            try {
                p.postMessage(msg)
            } catch (_: Exception) {
                tab.helperPorts.remove(p)
            }
        }
    }

    private var nextHeaderRequest = 1
    private val headerCallbacks = HashMap<Int, (Map<String, String>) -> Unit>()

    private val main = Handler(Looper.getMainLooper())

    /** Clearing browsing data (2.6): the articles kept for reader view are deleted. */
    fun clearArticles() {
        try {
            backgroundPort?.postMessage(JSONObject().put("type", "clear-articles"))
        } catch (e: Exception) {
            L.w("Couldn't clear reader articles", e)
        }
    }

    /** Request headers the page used for [url] (cookies, referer), if the extension saw it. */
    fun lookupHeaders(url: String, callback: (Map<String, String>) -> Unit) {
        val port = backgroundPort
        if (port == null) {
            callback(emptyMap())
            return
        }
        val id = nextHeaderRequest++
        headerCallbacks[id] = callback
        // Don't wait (or keep the caller's screen in memory) if the extension never answers.
        main.postDelayed({ headerCallbacks.remove(id)?.invoke(emptyMap()) }, 2000)
        try {
            port.postMessage(JSONObject().put("type", "headers").put("id", id).put("url", url))
        } catch (e: Exception) {
            headerCallbacks.remove(id)
            callback(emptyMap())
        }
    }
}
