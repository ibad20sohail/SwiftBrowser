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

private val mainHandler = Handler(Looper.getMainLooper())

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
        // 3.0: "Tell sites a newer Firefox version" (Settings → Privacy & security).
        com.swiftbrowser.engine.UserAgent.overrideFor(tab.desktop)?.let { settings.setUserAgentOverride(it) }
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
        session.translationsSessionDelegate = com.swiftbrowser.ui.Translate.delegate(tab)
        tab.session = session
        attachExtensions(tab, session)
        return session
    }

    fun attachExtensions(tab: Tab, session: GeckoSession) {
        AdBlock.extension?.let { ext ->
            session.webExtensionController.setActionDelegate(ext, UboActionDelegate(tab))
            session.webExtensionController.setTabDelegate(ext, object : WebExtension.SessionTabDelegate {
                // uBlock Origin closes tabs it identifies as ad pop-ups. 3.0: the pop-up's tab really has
                // that session now (TabManager.openedByEngine), and it doesn't go to Recently closed.
                override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
                    TabManager.bySession(session)?.let { TabManager.closeTabs(listOf(it), remember = false) }
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
        com.swiftbrowser.adblock.AdStats.onBadge(t.badge, action.badgeText)
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
                NavPolicy.navigation(level, request, tab.lastClick, System.currentTimeMillis(), tab.userNavAt, tab.loading, waited)
            }
        }
    }

    /** Switches the tab between the mobile and desktop versions as the site's setting says. */
    private fun applySiteDesktop(session: GeckoSession, url: String) {
        val site = UrlUtils.site(url) ?: return
        val want = SiteSettings.desktop(site, tab.incognito)
        if (want == tab.desktop) return
        tab.desktop = want
        session.settings.setUserAgentMode(
            if (want) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        )
        // 3.0: a newer Firefox version told to sites goes with the desktop or mobile version.
        session.settings.setUserAgentOverride(com.swiftbrowser.engine.UserAgent.overrideFor(want))
        session.settings.setViewportMode(
            if (want) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
        )
        TabManager.scheduleSave(tab)
    }

    private fun decide(url: String, popup: Boolean, eval: (Boolean) -> NavPolicy.Decision): GeckoResult<AllowOrDeny>? {
        return when (eval(false)) {
            NavPolicy.Decision.ALLOW -> null
            NavPolicy.Decision.BLOCK -> {
                if (!tab.incognito) L.d("Blocked ${if (popup) "pop-up" else "redirect"} to ${UrlUtils.host(url)}")
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
                        if (!tab.incognito) L.d("Blocked ${if (popup) "pop-up" else "redirect"} to ${UrlUtils.host(url)}")
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

    /** The address that last failed to load (3.0): its error page isn't a site that "isn't secure". */
    private var failedUrl: String? = null

    override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
        failedUrl = uri
        // No connection (or it dropped): the page tries again by itself once there is one. 3.0: not
        // a site that can't be found (or won't answer) while the phone is online, see ErrorPage.
        // Failed while pages were paused for the VPN (Engine.setNetworkBlocked): loads once it's connected.
        tab.waitingForConnection = ErrorPage.waitsForConnection(error.code, com.swiftbrowser.engine.DataSaver.online) ||
            (com.swiftbrowser.engine.Engine.networkBlocked && error.category == WebRequestError.ERROR_CATEGORY_PROXY)
        return GeckoResult.fromValue(ErrorPage.create(uri, error))
    }

    // ------------------------------------------------------------------ progress

    /** When the page on screen started loading, and which site it is, for the log (2.7). */
    private var pageStartedAt = 0L
    private var pageSite = ""

    /**
     * The site for the log: never the address, and nothing of incognito tabs. 3.0: other addresses
     * only by their kind ("a magnet link", "a file"): the site name was the whole address for them.
     */
    private fun logSite(url: String? = tab.url) = when {
        tab.incognito -> "(incognito)"
        // A web address without a site (malformed) would come back whole too.
        else -> TabRules.logKind(url) ?: if (UrlUtils.host(url).isNullOrEmpty()) "a web page" else UrlUtils.siteName(url)
    }

    override fun onPageStart(session: GeckoSession, url: String) {
        pageStartedAt = android.os.SystemClock.elapsedRealtime()
        pageSite = logSite(url)
        if (UrlUtils.isHttp(url)) L.d("Tab ${tab.id} loading $pageSite")
        tab.loading = true
        tab.progress = 5
        tab.media.clear()
        tab.videoPlaying = false
        // A reloaded page may offer to continue its video again (see WatchPositions).
        tab.resumeOffered = null
        tab.resumePosition = 0.0
        // 3.0: only if it can be seen (not with Swift in the background, under the home page or the tab grid).
        if (session.isOpen && TabManager.pageOnScreen(tab)) session.setActive(true)
        changed()
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        if (pageStartedAt > 0 && pageSite.isNotEmpty()) {
            val s = (android.os.SystemClock.elapsedRealtime() - pageStartedAt) / 100 / 10.0
            L.d("Tab ${tab.id} ${if (success) "loaded" else "didn't load"} $pageSite in $s s")
        }
        pageStartedAt = 0L
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
        if (!SwiftApp.prefs.httpsOnly || tab.secure || tab !== TabManager.current || tab.waitingForConnection || tab.url == failedUrl) return
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
        TabManager.scheduleSave(tab, stateOnly = true)
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
        // 3.0.3: in the page's own pixels, for the fast scroll handle (the toolbar's are the screen's).
        tab.pageY = update.scrollY.toFloat()
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
        // Only pages opened by script may close themselves. 3.0: a pop-up's tab now has the window
        // the engine opened for it (TabManager.openedByEngine), so its window.close() reaches it.
        if (tab.parentId != null && tab.session === session) TabManager.close(tab)
    }

    /**
     * A page's script has been busy for a long time (the page doesn't respond). On the tab on screen
     * Swift offers to stop it (2.7); the script waits meanwhile, as in Firefox. Other tabs go on.
     */
    override fun onSlowScript(session: GeckoSession, scriptFileName: String): GeckoResult<org.mozilla.geckoview.SlowScriptResponse> {
        val site = logSite()
        L.w("Tab ${tab.id}: a script of $site isn't responding")
        if (tab !== TabManager.current || ui == null) return GeckoResult.fromValue(org.mozilla.geckoview.SlowScriptResponse.CONTINUE)
        val result = GeckoResult<org.mozilla.geckoview.SlowScriptResponse>()
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        ui?.showMessage("${if (tab.incognito) "This page" else site} isn't responding", "Stop", long = true) {
            if (answered.compareAndSet(false, true)) {
                L.w("Tab ${tab.id}: the page's script was stopped")
                result.complete(org.mozilla.geckoview.SlowScriptResponse.STOP)
            }
        }
        // No answer while the message shows: the script carries on.
        mainHandler.postDelayed({
            if (answered.compareAndSet(false, true)) result.complete(org.mozilla.geckoview.SlowScriptResponse.CONTINUE)
        }, 8500)
        return result
    }

    override fun onCrash(session: GeckoSession) = onDead(session, crashed = true)

    override fun onKill(session: GeckoSession) = onDead(session, crashed = false)

    private fun onDead(session: GeckoSession, crashed: Boolean) {
        L.w("Tab ${tab.id} (${logSite()}): its page process ${if (crashed) "crashed" else "ended (Android closing it for memory, or a crash without notes)"}; ${com.swiftbrowser.core.Diagnostics.memoryText()}")
        if (tab.session !== session) return
        ui?.releaseSession(session)
        try {
            session.close()
        } catch (_: Exception) {
        }
        tab.session = null
        tab.restoreToken = 0
        tab.loading = false
        tab.playing = false
        Helper.detachTab(tab)
        // 3.0: a page in full screen leaves it: the engine says nothing more of it, and the screen
        // stayed in full screen (Back asked the new page, which isn't in it).
        if (tab.fullscreen) {
            tab.fullscreen = false
            ui?.onFullScreen(tab, false)
        }
        if (tab === TabManager.current) {
            val now = android.os.SystemClock.elapsedRealtime()
            when (TabRules.revive(now, tab.autoRevivedAt, ui?.onScreen == true)) {
                // It ended again soon after Swift loaded it again (3.0: also when Android ended it):
                // the crash page, with its Reload button.
                TabRules.Revive.CRASH_PAGE -> {
                    tab.crashed = true
                    ui?.onTabCrashed(tab)
                }
                // 3.0: not while Swift is out of sight: when it's back (TabManager.reviveOnReturn).
                TabRules.Revive.WHEN_BACK -> L.d("Tab ${tab.id}: its page loads again when Swift is back")
                // Reloaded by itself once (2.7), instead of the crash page.
                TabRules.Revive.NOW -> {
                    tab.autoRevivedAt = now
                    if (crashed) ui?.showMessage("The page stopped working, so Swift loaded it again")
                    TabManager.revive(tab)
                }
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
            // Protected films and series (DRM).
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            // 3.0: no site keeps its data out of reach of clearing (Android's own space cleaning included).
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> storageAccess(session, perm)
            // Sound only after the user presses play (stops noisy auto-playing ads), unless the site is allowed.
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> GeckoResult.fromValue(
                if (SiteSettings.autoplay(UrlUtils.site(perm.uri), tab.incognito)) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
            )
            // Notification prompts on these sites are spam.
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                val site = UrlUtils.site(perm.uri)
                when (SiteSettings.location(site, tab.incognito)) {
                    SiteSettings.Location.ALLOW -> GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
                    SiteSettings.Location.BLOCK -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                    // 3.0: the answer can be kept for the site (not from incognito, which leaves nothing behind).
                    SiteSettings.Location.ASK -> SessionFactory.prompts.askPermission(
                        session,
                        "Allow ${UrlUtils.host(perm.uri) ?: "this site"} to use your location?",
                        rememberable = site != null && !tab.incognito,
                    ) { allowed, remember ->
                        if (remember && site != null) {
                            SiteSettings.setLocation(site, if (allowed) SiteSettings.Location.ALLOW else SiteSettings.Location.BLOCK)
                        }
                    }
                }
            }
            else -> GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        }
    }

    /**
     * 3.0: a site inside the page (a video player, a "Sign in with" button, comments) asks for its
     * own cookies here. Asked, not allowed silently: with them it also knows you from site to site.
     * A "Don't allow" holds for the two sites until Swift closes; an "Allow" the engine keeps itself.
     */
    private fun storageAccess(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
        val inside = UrlUtils.siteName(perm.uri).ifBlank { UrlUtils.siteName(perm.thirdPartyOrigin) }
        val here = UrlUtils.siteName(tab.url)
        if (inside.isBlank() || here.isBlank() || ui == null) return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        val pair = "${tab.incognito} ${UrlUtils.siteOfHost(inside)} ${UrlUtils.siteOfHost(here)}"
        storageAnswers[pair]?.let { return GeckoResult.fromValue(it) }
        return SessionFactory.prompts.askPermission(
            session, "Let $inside use its cookies on $here?", allow = "Allow", deny = "Don't allow",
        ) { allowed, _ ->
            storageAnswers[pair] = if (allowed) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY
        }
    }

    private companion object {
        /** 3.0: answers to a site's question for its cookies inside another (see storageAccess), this run. */
        val storageAnswers = HashMap<String, Int>()
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

    /** When this tab last said that sites can't use the camera or microphone (3.0). */
    private var mediaRefusedAt = 0L

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        callback.reject()
        // 3.0: said, not refused silently (a call or a voice search would only seem broken); once
        // in a while, as a page may ask again and again.
        val now = android.os.SystemClock.elapsedRealtime()
        if (tab === TabManager.current && now - mediaRefusedAt > 10_000) {
            mediaRefusedAt = now
            ui?.showMessage("Swift doesn't let sites use the camera or microphone")
        }
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
        L.d("Tab ${tab.id} ${if (playing) "playing" else "stopped playing"} (${logSite()})")
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
