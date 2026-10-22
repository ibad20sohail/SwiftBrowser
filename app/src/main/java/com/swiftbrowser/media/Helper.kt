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
            "playing" -> Unit
            "video-pos" -> WatchPositions.onPosition(tab, msg)
            // Read aloud, from the reader view page in this tab: it hears back how far it got.
            "tts-start", "tts-pause", "tts-resume", "tts-stop" -> {
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
                ReadAloud.onMessage(ctx, msg)
            }
            "video-start" -> WatchPositions.onStart(tab, msg)
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
                if (url.startsWith("moz-extension://") && tab.session != null) TabManager.load(tab, url)
            }
            "reader-failed" -> {
                val tab = tabsByExtId[msg.optInt("tabId", -1)] ?: return
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
     * Tells the extension which styles pages get from their first byte on: Lite mode, and the
     * lighter YouTube and TheFlixer (unless sites are shown as designed).
     */
    fun sendLook() {
        val port = backgroundPort ?: return
        val prefs = SwiftApp.prefs
        try {
            port.postMessage(JSONObject().put("type", "look").put("lite", prefs.liteMode).put("fixes", !prefs.sitesAsDesigned))
        } catch (e: Exception) {
            L.w("Couldn't send the look", e)
        }
    }

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
