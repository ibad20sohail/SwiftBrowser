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
    /** The tab group it is in, see [TabGroup]. A group's tabs are next to each other in the tab list. */
    var groupId: Long? = null

    /**
     * False while the tab's saved history is still only in the database (2.6: it's read when the tab
     * opens, not at start). [state] is "" meanwhile when there is one.
     */
    var stateLoaded = true

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

    /** The page couldn't load for lack of a connection: it loads again when there is one. */
    var waitingForConnection = false

    /** The home button shows the home page over the tab's page; Back (or opening something) leaves it. */
    var homeShown = false

    /** The screen shows the home page for this tab. */
    val showsHome: Boolean get() = isHome || homeShown

    /**
     * Where Back goes when this tab can't go back any more and has no tab to return to (2.6): the
     * Documents or Downloads screen it was opened from, or the app that asked Swift to open it
     * (Docs.BACK_*). Not kept when Swift restarts.
     */
    var backTo: String? = null

    /** uBlock Origin's per-tab blocked-request count, as shown on its badge. */
    var badge: String? = null
    var uboAction: WebExtension.Action? = null

    val media = ArrayList<MediaItem>()
    val helperPorts = ArrayList<WebExtension.Port>()
    var speed = 1.0
    var lastClick: NavPolicy.Click? = null

    /** What the engine knows of the page's language and translation (2.6, Translate). */
    var translation: org.mozilla.geckoview.TranslationsController.SessionTranslation.TranslationState? = null

    /** When the user last navigated on purpose (typed URL, back/forward, reload). */
    var userNavAt = 0L

    /** Selected while asleep (no engine session): its page is being loaded again. */
    var wokeUp = false

    /** The page whose video Swift offered to continue, and from where (once per page load; see WatchPositions). */
    var resumeOffered: String? = null

    /** Opened from the home page's "Continue": reader view comes up once this page has loaded (2.3). */
    var readerOnLoad: String? = null

    /** Pages of this tab already opened in reader view by themselves (back to the page stays there). */
    val autoReaderDone = HashSet<String>()

    /** Reader view was asked for by itself: a page without an article says nothing. */
    var quietReader = false
    var resumePosition = 0.0

    /** A pop-up allowed by NavPolicy may load exactly this address once without a second check. */
    var approvedUrl: String? = null

    var mediaSession: MediaSession? = null
    var playing = false

    /** A video (not just sound) has played on the page since it loaded: the screen stays on while it plays. */
    var videoPlaying = false

    val isBlank: Boolean get() = UrlUtils.isBlank(url)

    /** A blank tab with nothing to restore shows the home page and needs no engine session. */
    val isHome: Boolean get() = isBlank && sessionState == null && state == null

    val displayTitle: String
        get() = title.ifBlank { if (isBlank) (if (incognito) "Incognito tab" else "New tab") else UrlUtils.display(url) }
}

/**
 * Tabs grouped together in the tab grid, like in Chrome. [color] is an index into
 * [TabGroup.COLORS]; incognito groups (negative ids) are never saved.
 */
class TabGroup(val id: Long, val profileId: Long, var name: String, var color: Int, val incognito: Boolean = false) {
    companion object {
        /** Grey, blue, red, yellow, green, pink, purple, cyan; each has a light and a dark-theme shade. */
        val COLORS = intArrayOf(
            com.swiftbrowser.R.color.group_grey, com.swiftbrowser.R.color.group_blue,
            com.swiftbrowser.R.color.group_red, com.swiftbrowser.R.color.group_yellow,
            com.swiftbrowser.R.color.group_green, com.swiftbrowser.R.color.group_pink,
            com.swiftbrowser.R.color.group_purple, com.swiftbrowser.R.color.group_cyan,
        )
        val COLOR_NAMES = arrayOf("Grey", "Blue", "Red", "Yellow", "Green", "Pink", "Purple", "Cyan")

        fun colorRes(index: Int) = COLORS[index.mod(COLORS.size)]
    }
}

/** What the browser screen does for the engine. Implemented by MainActivity. */
interface BrowserUi {
    val activity: AppCompatActivity

    fun onTabSelected(tab: Tab)
    fun onTabsChanged()
    fun onTabUpdated(tab: Tab)

    /** A new tab was opened in the background. */
    fun onTabOpenedInBackground(tab: Tab)

    /** Tabs were closed from the tab grid; the user may undo it (TabManager.undoClose). */
    fun onTabsClosed(tabs: List<Tab>)

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

    /** Continue watching: a button to go back to [position] in the tab's video, until used or closed. */
    fun showResumeChip(tab: Tab, position: String, onResume: () -> Unit)

    /** The continue-watching button goes ([tab] null: whichever tab it is for). */
    fun hideResumeChip(tab: Tab?)

    /** A short message at the bottom of the screen, optionally with a button. */
    fun showMessage(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null)

    /** Lets the user choose files for a page's upload field; null when cancelled. */
    fun pickFiles(mimeTypes: Array<String>, multiple: Boolean, callback: (List<android.net.Uri>?) -> Unit)
}
