package com.swiftbrowser.tabs

import androidx.appcompat.app.AppCompatActivity
import com.swiftbrowser.adblock.NavPolicy
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.media.MediaItem
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse

class Tab(
    val id: Long,
    val profileId: Long,
    var url: String,
    var title: String,
    /** Serialized GeckoSession.SessionState: back/forward history, scroll, form data. */
    var state: String?,
    var parentId: Long?,
    var lastUsed: Long,
    var desktop: Boolean,
) {
    /** Live engine session, or null while the tab is suspended to save memory. */
    var session: GeckoSession? = null

    var canGoBack = false
    var canGoForward = false
    var progress = 100
    var loading = false
    var secure = false
    var fullscreen = false
    var crashed = false

    /** uBlock Origin's per-tab blocked-request count, as shown on its badge. */
    var badge: String? = null
    var uboAction: WebExtension.Action? = null

    val media = ArrayList<MediaItem>()
    val helperPorts = ArrayList<WebExtension.Port>()
    var speed = 1.0
    var lastClick: NavPolicy.Click? = null

    /** When the user last navigated on purpose (typed URL, back/forward, reload). */
    var userNavAt = 0L

    /** A pop-up allowed by NavPolicy may load exactly this address once without a second check. */
    var approvedUrl: String? = null

    var mediaSession: MediaSession? = null
    var playing = false

    val isBlank: Boolean get() = UrlUtils.isBlank(url)

    val displayTitle: String
        get() = title.ifBlank { if (isBlank) "New tab" else UrlUtils.display(url) }
}

/** What the browser screen does for the engine. Implemented by MainActivity. */
interface BrowserUi {
    val activity: AppCompatActivity

    fun onTabSelected(tab: Tab)
    fun onTabsChanged()
    fun onTabUpdated(tab: Tab)

    /** Detach [session] from the screen before it gets closed. */
    fun releaseSession(session: GeckoSession)

    fun onFullScreen(tab: Tab, fullScreen: Boolean)
    fun onVideoFullscreen(tab: Tab, width: Long, height: Long)
    fun onContextMenu(tab: Tab, element: GeckoSession.ContentDelegate.ContextElement)
    fun onExternalResponse(tab: Tab, response: WebResponse)
    fun onBlocked(tab: Tab, url: String, popup: Boolean)
    fun onExternalApp(tab: Tab, url: String)
    fun onTabCrashed(tab: Tab)
    fun onPlayingChanged(tab: Tab)
    fun requestAndroidPermissions(permissions: Array<String>, callback: (Boolean) -> Unit)
    fun showExtensionPopup(session: GeckoSession)

    /** Lets the user choose files for a page's upload field; null when cancelled. */
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, callback: (List<android.net.Uri>?) -> Unit)
}
