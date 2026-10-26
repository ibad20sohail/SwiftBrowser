package com.swiftbrowser.tabs

import android.Manifest
import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.adblock.AdLevel
import com.swiftbrowser.adblock.NavPolicy
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.SiteSettings
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.media.Playback
import com.swiftbrowser.ui.ErrorPage
import com.swiftbrowser.ui.Prompts
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/** Sites already said to be insecure (secure connections first), for this run of Swift. */
private val warnedInsecure = HashSet<String>()

object SessionFactory {
    val prompts = Prompts()

    fun create(tab: Tab): GeckoSession {
        val builder = GeckoSessionSettings.Builder()
        // Incognito tabs share Gecko's private-browsing storage, which is wiped when the last one closes.
        if (!tab.incognito) builder.contextId(Profiles.contextId(tab.profileId))
        val settings = builder
            .usePrivateMode(tab.incognito)
            .userAgentMode(
                if (tab.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
            )
            .viewportMode(
                if (tab.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
            )
            // A page that isn't on screen (another tab, Swift in the background) stops its media,
            // unless "Keep playing in the background" is on: then video and music go on.
            .suspendMediaWhenInactive(!SwiftApp.prefs.keepPlaying)
            .build()
        val session = GeckoSession(settings)
        val d = TabDelegates(tab)
        session.setNavigationDelegate(d)
        session.setProgressDelegate(d)
        session.setContentDelegate(d)
        session.setHistoryDelegate(d)
        session.setPermissionDelegate(d)
        session.setMediaSessionDelegate(d)
        session.setCompositorScrollDelegate(d)
        session.setPromptDelegate(prompts)
        tab.session = session
        attachExtensions(tab, session)
        return session
    }

    fun attachExtensions(tab: Tab, session: GeckoSession) {
        AdBlock.extension?.let { ext ->
            session.webExtensionController.setActionDelegate(ext, UboActionDelegate(tab))
            session.webExtensionController.setTabDelegate(ext, object : WebExtension.SessionTabDelegate {
                // uBlock Origin closes tabs it identifies as ad pop-ups.
                override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
                    TabManager.bySession(session)?.let { TabManager.close(it) }
                    return GeckoResult.allow()
                }

                // 2.2: a page blocked outright (phishing, malware) shows uBlock Origin's "page blocked"
                // screen, with the list that blocked it, instead of a blank page. Only that page.
                override fun onUpdateTab(source: WebExtension, session: GeckoSession, details: WebExtension.UpdateTabDetails): GeckoResult<AllowOrDeny> {
                    val url = details.url ?: return GeckoResult.deny()
                    val base = ext.metaData.baseUrl
                    if (!url.startsWith(base + "document-blocked.html")) return GeckoResult.deny()
                    session.loadUri(url)
                    return GeckoResult.allow()
                }
            })
        }
        Helper.attachSession(tab, session)
    }
}

/** Receives uBlock Origin's badge (blocked count) and opens its popup panel. */
class UboActionDelegate(private val tab: Tab?) : WebExtension.ActionDelegate {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
        val t = tab ?: return
        t.uboAction = action
        if (t.badge == action.badgeText) return
        t.badge = action.badgeText
        // The count changes with nearly every blocked request; redraw the toolbar at most 3 times a second.
        if (pending) return
        pending = true
        handler.postDelayed({
            pending = false
            TabManager.notifyTabUpdated(t)
        }, 300)
    }

    override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
        openPopup()

    override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
        openPopup()

    private fun openPopup(): GeckoResult<GeckoSession>? {
        val ui = TabManager.ui ?: return null
        val session = GeckoSession(GeckoSessionSettings.Builder().build())
        session.setPromptDelegate(SessionFactory.prompts)
        session.open(Engine.runtime)
        ui.showExtensionPopup(session)
        return GeckoResult.fromValue(session)
    }
}

private val CONNECTION_ERRORS = setOf(
    WebRequestError.ERROR_UNKNOWN_HOST, WebRequestError.ERROR_OFFLINE, WebRequestError.ERROR_NET_RESET,
    WebRequestError.ERROR_NET_INTERRUPT, WebRequestError.ERROR_NET_TIMEOUT, WebRequestError.ERROR_CONNECTION_REFUSED,
)

class TabDelegates(private val tab: Tab) :
    GeckoSession.NavigationDelegate,
    GeckoSession.ProgressDelegate,
    GeckoSession.ContentDelegate,
    GeckoSession.HistoryDelegate,
    GeckoSession.PermissionDelegate,
    GeckoSession.CompositorScrollDelegate,
    MediaSession.Delegate {

    private val handler = Handler(Looper.getMainLooper())
    private val ui: BrowserUi? get() = TabManager.ui

    private fun changed() = TabManager.notifyTabUpdated(tab)

    // ------------------------------------------------------------------ navigation

    override fun onLocationChange(
        session: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean,
    ) {
        if (url == null) return
        // The engine's own blank page while a tab starts up; the tab keeps the address it is loading.
        if (url == "about:blank" && !tab.isBlank && tab.loading) return
        tab.url = url
        TabManager.scheduleSave(tab)
        changed()
    }

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        tab.canGoBack = canGoBack
        changed()
    }

    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
        tab.canGoForward = canGoForward
        changed()
    }

    override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
        val url = request.uri
        if (!UrlUtils.isWebScheme(url)) {
            // intent://, market://, tel:, whatsapp: ... Ask first; silent attempts are ad tricks.
            val level = AdBlock.levelFor(tab.url)
            if (request.hasUserGesture || level < AdLevel.HIGH) ui?.onExternalApp(tab, url)
            return GeckoResult.deny()
        }
        // Sites set to their desktop version get it from the first request on (and others don't).
        if (request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) applySiteDesktop(session, url)
        // The first load of a pop-up that the policy already allowed in its opener.
        tab.approvedUrl?.let { approved ->
            if (request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW &&
                UrlUtils.stripFragment(approved) == UrlUtils.stripFragment(url)
            ) {
                tab.approvedUrl = null
                return null
            }
        }
        val level = AdBlock.levelFor(request.triggerUri ?: tab.url)
        return if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
            decide(url, popup = true) { waited ->
                NavPolicy.popup(level, request, tab.lastClick, System.currentTimeMillis(), waited)
            }
        } else {
            decide(url, popup = false) { waited ->
                NavPolicy.navigation(level, request, tab.lastClick, System.currentTimeMillis(), tab.userNavAt, waited)
            }
        }
    }

    /** Switches the tab between the mobile and desktop versions as the site's setting says. */
    private fun applySiteDesktop(session: GeckoSession, url: String) {
        val site = UrlUtils.site(url) ?: return
        val want = SiteSettings.desktop(site)
        if (want == tab.desktop) return
        tab.desktop = want
        session.settings.setUserAgentMode(
            if (want) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        )
        session.settings.setViewportMode(
            if (want) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
        )
        TabManager.scheduleSave(tab)
    }

    private fun decide(url: String, popup: Boolean, eval: (Boolean) -> NavPolicy.Decision): GeckoResult<AllowOrDeny>? {
        return when (eval(false)) {
            NavPolicy.Decision.ALLOW -> null
            NavPolicy.Decision.BLOCK -> {
                L.d("Blocked ${if (popup) "pop-up" else "redirect"}: $url")
                ui?.onBlocked(tab, url, popup)
                GeckoResult.deny()
            }
            NavPolicy.Decision.WAIT -> {
                // The tap report from the page may still be on its way; decide a moment later.
                val result = GeckoResult<AllowOrDeny>()
                handler.postDelayed({
                    if (eval(true) == NavPolicy.Decision.ALLOW) {
                        result.complete(AllowOrDeny.ALLOW)
                    } else {
                        L.d("Blocked ${if (popup) "pop-up" else "redirect"}: $url")
                        ui?.onBlocked(tab, url, popup)
                        result.complete(AllowOrDeny.DENY)
                    }
                }, 300)
                result
            }
        }
    }

    override fun onSubframeLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
        // Frames trying to open apps (intent://, market://) are always ad tricks.
        return if (!UrlUtils.isWebScheme(request.uri)) GeckoResult.deny() else null
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? =
        GeckoResult.fromValue(TabManager.openPopup(tab, uri))

    override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
        // No connection (or it dropped): the page tries again by itself once there is one.
        // Failed while pages were paused for the VPN (Engine.setNetworkBlocked): loads once it's connected.
        tab.waitingForConnection = error.code in CONNECTION_ERRORS ||
            (com.swiftbrowser.engine.Engine.networkBlocked && error.category == WebRequestError.ERROR_CATEGORY_PROXY)
        return GeckoResult.fromValue(ErrorPage.create(uri, error))
    }

    // ------------------------------------------------------------------ progress

    override fun onPageStart(session: GeckoSession, url: String) {
        tab.loading = true
        tab.progress = 5
        tab.media.clear()
        tab.videoPlaying = false
        // A reloaded page may offer to continue its video again (see WatchPositions).
        tab.resumeOffered = null
        tab.resumePosition = 0.0
        if (tab === TabManager.current && session.isOpen) session.setActive(true)
        changed()
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        tab.loading = false
        tab.progress = 100
        changed()
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {
        tab.progress = progress
        changed()
    }

    override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
        tab.secure = securityInfo.isSecure
        changed()
        warnIfInsecure()
    }

    /** With secure connections first on: a site without a secure version says so, once per site. */
    private fun warnIfInsecure() {
        // Not for an error page (the site couldn't be reached at all).
        if (!SwiftApp.prefs.httpsOnly || tab.secure || tab !== TabManager.current || tab.waitingForConnection) return
        val url = tab.url
        if (!url.startsWith("http://")) return
        val host = UrlUtils.host(url) ?: return
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("10.")) return
        if (!warnedInsecure.add(host)) return
        ui?.showMessage("$host isn't secure: others on the same network could see what you send here", long = true)
    }

    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
        // Called often (scrolling, typing); serialized later, off the main thread.
        tab.sessionState = sessionState
        TabManager.scheduleSave(tab)
    }

    // ------------------------------------------------------------------ content

    override fun onTitleChange(session: GeckoSession, title: String?) {
        tab.title = title.orEmpty()
        if (UrlUtils.isHttp(tab.url) && tab.title.isNotBlank()) {
            TabManager.setHistoryTitle(tab, tab.url, tab.title)
        }
        TabManager.scheduleSave(tab)
        changed()
    }

    override fun onFirstComposite(session: GeckoSession) {
        ui?.onPageDrawn(tab, contentful = false)
    }

    override fun onFirstContentfulPaint(session: GeckoSession) {
        ui?.onPageDrawn(tab, contentful = true)
    }

    override fun onShowDynamicToolbar(session: GeckoSession) {
        ui?.onToolbarRequest(tab, true)
    }

    override fun onHideDynamicToolbar(session: GeckoSession) {
        ui?.onToolbarRequest(tab, false)
    }

    // ------------------------------------------------------------------ scrolling

    override fun onScrollChanged(session: GeckoSession, update: GeckoSession.ScrollPositionUpdate) {
        if (tab !== TabManager.current) return
        ui?.onPageScroll(
            tab, update.scrollY * update.zoom,
            update.source == GeckoSession.ScrollPositionUpdate.SOURCE_USER_INTERACTION,
        )
    }

    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        tab.fullscreen = fullScreen
        ui?.onFullScreen(tab, fullScreen)
    }

    override fun onContextMenu(
        session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement,
    ) {
        ui?.onContextMenu(tab, element)
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        val u = ui
        if (u != null) {
            u.onExternalResponse(tab, response)
        } else {
            try {
                response.body?.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun onCloseRequest(session: GeckoSession) {
        // Only pages opened by script may close themselves.
        if (tab.parentId != null && tab.session === session) TabManager.close(tab)
    }

    override fun onCrash(session: GeckoSession) = onDead(session, crashed = true)

    override fun onKill(session: GeckoSession) = onDead(session, crashed = false)

    private fun onDead(session: GeckoSession, crashed: Boolean) {
        L.w("Tab ${tab.id} content process ${if (crashed) "crashed" else "was killed"}")
        if (tab.session !== session) return
        ui?.releaseSession(session)
        try {
            session.close()
        } catch (_: Exception) {
        }
        tab.session = null
        tab.loading = false
        tab.playing = false
        Helper.detachTab(tab)
        if (tab === TabManager.current) {
            if (crashed) {
                tab.crashed = true
                ui?.onTabCrashed(tab)
            } else {
                TabManager.revive(tab)
            }
        }
    }

    // ------------------------------------------------------------------ history

    override fun onVisited(
        session: GeckoSession, url: String, lastVisitedURL: String?, flags: Int,
    ): GeckoResult<Boolean>? {
        val skip = GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE or
            GeckoSession.HistoryDelegate.VISIT_REDIRECT_SOURCE_PERMANENT or
            GeckoSession.HistoryDelegate.VISIT_UNRECOVERABLE_ERROR
        if (flags and GeckoSession.HistoryDelegate.VISIT_TOP_LEVEL == 0 || flags and skip != 0) return null
        if (!UrlUtils.isHttp(url) || tab.incognito) return null
        TabManager.recordVisit(tab, url)
        return GeckoResult.fromValue(true)
    }

    // ------------------------------------------------------------------ permissions

    override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int>? {
        return when (perm.permission) {
            // Silent videos that start by themselves (e.g. YouTube feed previews while scrolling).
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> GeckoResult.fromValue(
                if (SwiftApp.prefs.autoplayMuted) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
            )
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS,
            -> GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            // Sound only after the user presses play (stops noisy auto-playing ads), unless the site is allowed.
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(
                if (SiteSettings.autoplay(UrlUtils.site(perm.uri))) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
            )
            // Notification prompts on these sites are spam.
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> when (SiteSettings.location(UrlUtils.site(perm.uri))) {
                SiteSettings.Location.ALLOW -> GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
                SiteSettings.Location.BLOCK -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                SiteSettings.Location.ASK -> SessionFactory.prompts.askPermission(
                    "Allow ${UrlUtils.host(perm.uri) ?: "this site"} to use your location?",
                )
            }
            else -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
    }

    override fun onAndroidPermissionsRequest(
        session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        val wanted = permissions?.filter {
            it == Manifest.permission.ACCESS_FINE_LOCATION || it == Manifest.permission.ACCESS_COARSE_LOCATION
        }.orEmpty()
        val u = ui
        if (wanted.isEmpty() || u == null) {
            callback.reject()
            return
        }
        u.requestAndroidPermissions(wanted.toTypedArray()) { granted -> if (granted) callback.grant() else callback.reject() }
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        callback.reject()
    }

    // ------------------------------------------------------------------ media

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        L.d("Media session active in tab ${tab.id}")
        tab.mediaSession = mediaSession
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        if (tab.mediaSession === mediaSession) tab.mediaSession = null
        setPlaying(false)
        SwiftApp.instance?.let { Playback.onMediaGone(it, tab) }
    }

    override fun onMetadata(session: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata) {
        SwiftApp.instance?.let { Playback.onMetadata(it, tab, meta) }
    }

    override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
        Playback.onFeatures(tab, features)
    }

    override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
        SwiftApp.instance?.let { Playback.onPosition(it, tab, state) }
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) = setPlaying(true)

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) = setPlaying(false)

    override fun onStop(session: GeckoSession, mediaSession: MediaSession) = setPlaying(false)

    private fun setPlaying(playing: Boolean) {
        if (tab.playing == playing) return
        L.d("Tab ${tab.id} ${if (playing) "playing" else "stopped playing"}")
        tab.playing = playing
        if (playing) {
            // One tab plays at a time: starting something pauses what another tab was playing.
            for (t in TabManager.tabs) if (t !== tab && t.playing) t.mediaSession?.pause()
            com.swiftbrowser.media.SleepTimer.onPlaying(tab)
        }
        ui?.onPlayingChanged(tab)
        SwiftApp.instance?.let { Playback.onPlayingChanged(it, tab) }
    }

    override fun onFullscreen(
        session: GeckoSession, mediaSession: MediaSession, enabled: Boolean, meta: MediaSession.ElementMetadata?,
    ) {
        if (enabled && meta != null) ui?.onVideoFullscreen(tab, meta.width, meta.height)
    }
}
