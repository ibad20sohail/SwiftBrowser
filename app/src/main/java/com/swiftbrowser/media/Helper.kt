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
    enum class Kind { HLS, FILE, DASH }
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
                        (message as? JSONObject)?.let { onContentMessage(tab, it, port, port.sender) }
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        tab.helperPorts.remove(port)
                        // 3.0: what is being sent to a viewer page that went stops.
                        com.swiftbrowser.docs.Docs.onPortClosed(port)
                    }
                })
            }

            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                (message as? JSONObject)?.let { onContentMessage(tab, it, null, sender) }
                return null
            }
        }, NATIVE_APP)
    }

    /**
     * 3.0: who sent a message, as the engine reports it (it checks that an extension page is really
     * one of the helper's, in the extensions' process): a content script in a website's frame, the
     * document viewer, reader view, or something else.
     */
    private fun senderOf(sender: WebExtension.MessageSender?): com.swiftbrowser.docs.DocRules.Sender =
        if (sender == null) com.swiftbrowser.docs.DocRules.Sender.OTHER
        else com.swiftbrowser.docs.DocRules.sender(
            contentScript = sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT,
            extensionPage = sender.environmentType == WebExtension.MessageSender.ENV_TYPE_EXTENSION,
            url = sender.url,
            base = extension?.metaData?.baseUrl,
        )

    /**
     * 3.0: the extensions' process (uBlock Origin, this helper, the document viewer) ended more than
     * five times in half a minute (a heavy document running it out of memory, a crash), so the engine
     * stopped starting it: every tab would be without ad blocking until Swift restarts. It is started
     * again after a pause that grows each time, so a process that keeps crashing doesn't keep the
     * phone busy, and the log says so. Engine.init sets this up.
     */
    fun watchExtensionProcess(runtime: org.mozilla.geckoview.GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.setExtensionProcessDelegate(object : org.mozilla.geckoview.WebExtensionController.ExtensionProcessDelegate {
            override fun onDisabledProcessSpawning() {
                val wait = restartWaits[minOf(restarts, restartWaits.size - 1)]
                restarts++
                L.w("The extensions' process kept stopping (${restarts} time(s) now): starting it again in ${wait / 1000} s")
                main.postDelayed({ controller.enableExtensionProcessSpawning() }, wait)
            }
        })
    }

    private var restarts = 0
    private val restartWaits = longArrayOf(3_000, 20_000, 60_000, 300_000)

    /** 3.0: how many video failures each tab logged in the last ten minutes (a page can repeat them endlessly). */
    private val mediaErrors = HashMap<Long, LongArray>()

    private fun mayLogMediaError(tab: Tab): Boolean {
        val now = System.currentTimeMillis()
        val e = mediaErrors.getOrPut(tab.id) { longArrayOf(now, 0) }
        if (now - e[0] > 10 * 60_000L) {
            e[0] = now
            e[1] = 0
        }
        e[1]++
        if (e[1] == MEDIA_ERRORS_LOGGED + 1) L.w("Tab ${tab.id}: more videos failed; not logged for ten minutes")
        if (mediaErrors.size > 100) mediaErrors.keys.firstOrNull { it != tab.id }?.let { mediaErrors.remove(it) }
        return e[1] <= MEDIA_ERRORS_LOGGED
    }

    private const val MEDIA_ERRORS_LOGGED = 5L

    /** Tabs a refused message was logged for (3.0). */
    private val refusedIn = HashSet<Long>()

    /** The tab's session went away (closed or suspended). */
    /** The tab's id as extensions know it (the same for all of them), once its page has said hello. */
    fun extensionTabId(tab: Tab): Int? = tabsByExtId.entries.firstOrNull { it.value === tab }?.key

    fun detachTab(tab: Tab) {
        tab.helperPorts.clear()
        tabsByExtId.entries.removeAll { it.value === tab }
        mediaErrors.remove(tab.id)
    }

    private fun onContentMessage(tab: Tab, msg: JSONObject, port: WebExtension.Port?, sender: WebExtension.MessageSender?) {
        val type = msg.optString("type")
        // 3.0: each message only from those that send it (see DocRules): a website that took over its
        // process can't read documents, save files or speak through Swift.
        val from = senderOf(sender)
        if (!com.swiftbrowser.docs.DocRules.allowed(type, from)) {
            // Said once per tab (warnings skip the log's daily limit).
            if (refusedIn.add(tab.id)) {
                L.w("Tab ${tab.id}: a helper message (${type.filter { it in 'a'..'z' || it == '-' }.take(24)}) came from ${from.name.lowercase()}: refused")
            }
            return
        }
        when (type) {
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
                // 3.0.4: a muted tab's new frames (and each new page) are muted too.
                if (tab.muted) {
                    port?.postMessage(JSONObject().put("type", "mute").put("on", true))
                    if (msg.optBoolean("top")) muteEngine(tab, true)
                }
            }
            "click" -> tab.lastClick = NavPolicy.Click(msg.optString("href"), System.currentTimeMillis())
            "icon" -> if (!tab.incognito) Favicons.onPageIcon(msg.optString("page"), msg.optString("href").ifBlank { null })
            // Who is signed in to Google in this tab's profile (3.0).
            "google-account" -> if (!tab.incognito) com.swiftbrowser.core.GoogleAccounts.onSeen(tab.profileId, msg)
            "playing" -> if (!tab.videoPlaying) {
                tab.videoPlaying = true
                TabManager.ui?.onPlayingChanged(tab)
            }
            "video-pos" -> WatchPositions.onPosition(tab, msg)
            "reader-place" -> Unfinished.onReaderPlace(tab, msg)
            // 3.0.4: the colour the page asks for (its theme-color), for the address bar: from the top
            // frame, and never kept for an incognito tab (it keeps its own colours).
            "theme-color" -> if (!tab.incognito && msg.optBoolean("top")) {
                val colour = com.swiftbrowser.ui.CssColour.parse(msg.optString("color"))
                val changed = colour != tab.siteColour
                tab.siteColour = colour
                tab.siteColourStale = false
                if (changed) TabManager.ui?.onSiteColour(tab)
            }
            // 3.0.4: reader view's Aa button: Swift's panel for its font, line spacing and page colour.
            "reader-style" -> TabManager.ui?.let { ui ->
                if (tab === TabManager.current && ui.onScreen) com.swiftbrowser.ui.Sheets.readerStyle(ui.activity)
            }
            // Swift's document viewer (2.4); 3.0: with the page's address, for its own document only.
            "doc-hello", "doc-read", "doc-range", "doc-place", "doc-action", "doc-reading", "doc-zip", "doc-save", "doc-convert", "doc-thumb" ->
                com.swiftbrowser.docs.Docs.onMessage(tab, msg, port, sender?.url)
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
            // For the log (2.7): players failing or stopping to load. 3.0: a few per tab every ten
            // minutes (a page can fail over and over, and warnings skip the log's daily limit).
            "media-error" -> if (mayLogMediaError(tab)) L.w(
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
     * 3.0.7: where each of Lite mode's page styles applies, from the level for all sites and the
     * sites' own [levels] (saving battery: at least Medium): "fixes" (lighter YouTube and TheFlixer)
     * from Low, "lite" (no animations, shadows, blur or filters) from Medium, "litehigh" (fewer
     * graphics layers; 3.0.9: silent "GIF" videos play once) from High, "liteextreme" (3.0.9: they
     * stay still until tapped, and moving drawings stop) at Extreme.
     */
    private fun liteLayers(levels: Map<String, com.swiftbrowser.core.LiteLevel>): JSONObject {
        val saving = com.swiftbrowser.engine.Power.saving
        val all = com.swiftbrowser.core.LiteLevel.effective(SwiftApp.prefs.liteLevel, null, saving)
        val sites = levels.mapValues { com.swiftbrowser.core.LiteLevel.effective(all, it.value, saving) }
        fun reach(min: com.swiftbrowser.core.LiteLevel): JSONObject {
            val r = com.swiftbrowser.core.LiteReach.of(min, all, sites)
            return JSONObject().put("on", r.on).put("exclude", org.json.JSONArray(r.exclude))
                .put("only", r.only?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
        }
        return JSONObject()
            .put("fixes", reach(com.swiftbrowser.core.LiteLevel.LOW))
            .put("lite", reach(com.swiftbrowser.core.LiteLevel.MEDIUM))
            .put("litehigh", reach(com.swiftbrowser.core.LiteLevel.HIGH))
            .put("liteextreme", reach(com.swiftbrowser.core.LiteLevel.EXTREME))
    }

    /**
     * Tells the extension which styles pages get from their first byte on: Lite mode's, by level and
     * site (3.0.7, [liteLayers]), and Dark page's; and whether playing pages are kept playing out of view.
     */
    fun sendLook() {
        val port = backgroundPort ?: return
        val prefs = SwiftApp.prefs
        try {
            port.postMessage(
                JSONObject().put("type", "look")
                    .put("layers", liteLayers(com.swiftbrowser.core.SiteSettings.liteLevels()))
                    .put("darkSites", org.json.JSONArray(com.swiftbrowser.core.SiteSettings.darkPages()))
                    // 3.0.1: what the helper may write down: the same without incognito tabs' choices.
                    .put("layersKeep", liteLayers(com.swiftbrowser.core.SiteSettings.liteLevelsKept()))
                    .put("darkSitesKeep", org.json.JSONArray(com.swiftbrowser.core.SiteSettings.darkPagesKept()))
                    .put("keepPlaying", prefs.keepPlaying)
                    .put("readerFont", prefs.readerFont).put("readerSpacing", prefs.readerSpacing)
                    // 3.0.4: and its page colour (auto, light, sepia or dark).
                    .put("readerTheme", prefs.readerTheme)
                    .put("accentLight", accentHex(night = false)).put("accentDark", accentHex(night = true))
                    .put("pageLight", pageColors(false)).put("pageDark", pageColors(true))
                    // 3.0.5: Swift's own pages use Swift's font, or the phone's if it couldn't be read.
                    .put("swiftFont", com.swiftbrowser.ui.AppFont.active),
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
        // 3.0.1: a page's report is checked: only addresses Swift could fetch (MediaRules).
        if (!MediaRules.acceptable(url) || tab.media.any { it.url == url }) return
        val item = MediaItem(
            url = url,
            kind = when (msg.optString("kind")) {
                "hls" -> MediaItem.Kind.HLS
                "dash" -> MediaItem.Kind.DASH
                else -> MediaItem.Kind.FILE
            },
            mime = msg.optString("mime"),
            size = msg.optLong("size", -1),
            headers = toMap(msg.optJSONObject("headers")),
            frameUrl = msg.optString("frameUrl").takeIf { MediaRules.acceptable(it) }.orEmpty(),
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

    /**
     * 3.0.4: mutes or unmutes everything [tab] plays. Its frames' content scripts mute their videos
     * and songs (ones added later too, and frames that connect later: see "hello"), and the engine
     * mutes the whole page as well, for sound a script can't reach (Web Audio, a song not in the page).
     */
    fun setMuted(tab: Tab, on: Boolean) {
        if (tab.muted == on) return
        tab.muted = on
        L.d("Tab ${tab.id} ${if (on) "muted" else "unmuted"}")
        postToFrames(tab, JSONObject().put("type", "mute").put("on", on))
        muteEngine(tab, on)
        TabManager.ui?.onPlayingChanged(tab)
    }

    /**
     * 3.0.4: GeckoView's own mute of a page (MediaSession.muteAudio, which mutes the tab's browser as
     * Firefox's tab mute does). It is only handed to the app with a media session that started; a
     * MediaSession made for the tab's session sends the same request at any time.
     */
    fun muteEngine(tab: Tab, on: Boolean) {
        val session = tab.session ?: return
        if (!session.isOpen) return
        try {
            EngineMute(session).muteAudio(on)
        } catch (e: Exception) {
            L.w("Couldn't ${if (on) "mute" else "unmute"} tab ${tab.id} in the engine", e)
        }
    }

    private class EngineMute(session: GeckoSession) : org.mozilla.geckoview.MediaSession(session)

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

    /**
     * Clearing browsing data (2.6): the articles kept for reader view are deleted. 2.8: for a time,
     * only those made from [from] to [to] (an older helper ignores these and clears them all).
     */
    fun clearArticles(from: Long = 0L, to: Long = Long.MAX_VALUE) {
        try {
            val message = JSONObject().put("type", "clear-articles")
            if (from > 0L) message.put("from", from)
            if (to != Long.MAX_VALUE) message.put("to", to)
            backgroundPort?.postMessage(message)
        } catch (e: Exception) {
            L.w("Couldn't clear reader articles", e)
        }
    }

    /**
     * Request headers the page used for [url], if the extension saw it: the Referer only (3.0.1: the
     * extension keeps no cookies; what Swift fetches itself goes without the profile's, see GeckoDataSource).
     */
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
