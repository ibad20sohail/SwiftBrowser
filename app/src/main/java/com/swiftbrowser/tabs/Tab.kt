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
    /** Serialized GeckoSession.SessionState as saved in the database. */
    var state: String?,
    var parentId: Long?,
    var lastUsed: Long,
    var desktop: Boolean,
    /** Incognito tabs are never saved and keep no history, cookies or cache after closing. */
    val incognito: Boolean = false,
) {
    /** Live engine session, or null while the tab is suspended (or shows the home page). */
    var session: GeckoSession? = null

    /**
     * The engine's latest back/forward history, scroll and form state. Kept as the engine's
     * object and only turned into text when saved, off the main thread.
     */
    var sessionState: GeckoSession.SessionState? = null

    var canGoBack = false
    var canGoForward = false
    var progress = 100
    var loading = false
    var secure = false
    var fullscreen = false
    var crashed = false

    /** The tab left the home page; Back on its first page returns there. */
    var leftHome = false

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

    /** A blank tab with nothing to restore shows the home page and needs no engine session. */
    val isHome: Boolean get() = isBlank && sessionState == null && state == null

    val displayTitle: String
        get() = title.ifBlank { if (isBlank) (if (incognito) "Incognito tab" else "New tab") else UrlUtils.display(url) }
}

/** What the browser screen does for the engine. Implemented by MainActivity. */
interface BrowserUi {
    val activity: AppCompatActivity

    fun onTabSelected(tab: Tab)
    fun onTabsChanged()
    fun onTabUpdated(tab: Tab)

    /** A new tab was opened in the background. */
    fun onTabOpenedInBackground(tab: Tab)

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

    /** The page scrolled; [scrollY] is in screen pixels, [byUser] when the user scrolled it. */
    fun onPageScroll(tab: Tab, scrollY: Float, byUser: Boolean)

    /** The page asks for the toolbar to be shown or hidden (e.g. to reveal a focused field). */
    fun onToolbarRequest(tab: Tab, show: Boolean)

    /**
     * The engine has drawn the tab's page since it was put on screen; [contentful] once some of the
     * page's content is drawn, after it has (re)loaded, not just its background.
     */
    fun onPageDrawn(tab: Tab, contentful: Boolean)

    /** Lets the user choose files for a page's upload field; null when cancelled. */
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, callback: (List<android.net.Uri>?) -> Unit)
}
