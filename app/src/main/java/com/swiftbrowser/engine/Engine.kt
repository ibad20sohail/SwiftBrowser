package com.swiftbrowser.engine

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.core.content.edit
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebExtension
import java.io.File

/** The single Gecko runtime of the app plus the two bundled extensions. */
object Engine {
    const val UBO_ID = "uBlock0@raymondhill.net"
    const val HELPER_ID = "helper@swiftbrowser.app"
    private const val UBO_URI = "resource://android/assets/extensions/ublock/"
    private const val HELPER_URI = "resource://android/assets/extensions/helper/"

    /**
     * Bump when tools/ublock-patch/swift-bridge.js changes: uBO keeps its upstream version number,
     * so GeckoView would otherwise keep the previously installed copy.
     */
    private const val UBO_BRIDGE_REV = 7

    private const val BRANCH = GeckoPreferenceController.PREF_BRANCH_USER

    lateinit var runtime: GeckoRuntime
        private set

    val executor: GeckoWebExecutor by lazy { GeckoWebExecutor(runtime) }

    /** Lets the UI start activities for Gecko (used by passkeys / WebAuthn). */
    var activityDelegate: GeckoRuntime.ActivityDelegate? = null

    private lateinit var app: Application
    private lateinit var prefs: Prefs

    fun init(app: Application, prefs: Prefs) {
        this.app = app
        this.prefs = prefs
        // uBlock Origin does the blocking: its malware and tracker lists replace Firefox's
        // Safe Browsing and tracking-protection lists, which cost CPU on every request and
        // over 10 MB of storage.
        removeSafeBrowsingData(app)
        removeTempFiles(app)
        val startupConfig = writeStartupConfig()
        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.NONE)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
            .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            // uBlock Origin gets its own process, as in Firefox. Otherwise its filter matching and
            // list rebuilding run on the engine's main thread, and every page and search waits.
            .extensionsProcessEnabled(true)
            .contentBlocking(contentBlocking)
            .preferredColorScheme(colorScheme(prefs.darkMode))
            .aboutConfigEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .lowMemoryDetection(true)
            .loginAutofillEnabled(false)
            .configFilePath(startupConfig)
            // Engine crashes are noted (in a process of their own) for the problem report.
            .crashHandler(CrashReportService::class.java)
            // Text size is Swift's own setting (Settings → Appearance), not the phone's.
            .automaticFontSizeAdjustment(false)
            .fontSizeFactor(prefs.textScale)
            .forceUserScalableEnabled(prefs.forceZoom)
            .build()
        runtime = GeckoRuntime.create(app, settings)
        runtime.delegate = GeckoRuntime.Delegate {
            L.e("Gecko runtime shut down")
            Diagnostics.markCleanExit()
            Process.killProcess(Process.myPid())
        }
        runtime.activityDelegate = GeckoRuntime.ActivityDelegate { pending ->
            activityDelegate?.onStartActivityForResult(pending)
                ?: GeckoResult.fromException(IllegalStateException("No activity"))
        }
        applyPerformancePrefs(prefs)
        installExtensions(app)
    }

    /**
     * Engine settings for a phone with 2 GB of memory. They are queued until the engine has
     * started and are stored in Gecko's profile; the rendering choice applies after a restart.
     */
    private fun applyPerformancePrefs(prefs: Prefs) {
        val list = listOf<SetGeckoPreference<*>>(
            // The web cache could otherwise grow to several hundred MB. 20 MB keeps the pieces
            // sites reuse on every page (scripts, styles, logos) without filling the phone.
            SetGeckoPreference.setBoolPref("browser.cache.disk.smart_size.enabled", false, BRANCH),
            SetGeckoPreference.setIntPref("browser.cache.disk.capacity", 20 * 1024, BRANCH),
            // Keep one previous page ready for an instant Back; each kept page holds its memory.
            SetGeckoPreference.setIntPref("browser.sessionhistory.max_total_viewers", 1, BRANCH),
            // Decoded images held in memory per process: 48 MB is plenty for a 720p screen.
            SetGeckoPreference.setIntPref("image.mem.surfacecache.max_size_kb", 48 * 1024, BRANCH),
            // No guessing and prefetching of pages that may never be opened.
            SetGeckoPreference.setBoolPref("network.predictor.enabled", false, BRANCH),
            SetGeckoPreference.setBoolPref("network.prefetch-next", false, BRANCH),
            SetGeckoPreference.setBoolPref("network.dns.disablePrefetch", true, BRANCH),
            SetGeckoPreference.setBoolPref("dom.ipc.processPrelaunch.enabled", false, BRANCH),
            // Background workers of sites (mostly push-notification spam on these sites).
            SetGeckoPreference.setBoolPref("dom.serviceWorkers.enabled", false, BRANCH),
            // Firefox's own tracker and Safe Browsing lists: uBlock Origin covers them. This stops
            // their downloads (over 10 MB, refreshed every hour).
            SetGeckoPreference.setBoolPref("browser.safebrowsing.update.enabled", false, BRANCH),
            // Pages with touch handlers make scrolling wait for their (busy) script to answer;
            // start scrolling after 300 ms at most instead of 600.
            SetGeckoPreference.setIntPref("apz.content_response_timeout", 300, BRANCH),
            // No glow at the ends of pages: it is drawn by the app's window, frame by frame.
            SetGeckoPreference.setBoolPref("apz.overscroll.enabled", false, BRANCH),
            // Phones like this have no AV1 video hardware, and decoding AV1 with the CPU makes
            // videos stutter. Without it YouTube and others send H.264, which the hardware plays.
            SetGeckoPreference.setBoolPref("media.av1.enabled", false, BRANCH),
            // The numbers shown by the drawing-speed meter (Settings → Performance).
            SetGeckoPreference.setStringPref("gfx.webrender.debug.profiler-ui", DRAW_SPEED_UI, BRANCH),
            SetGeckoPreference.setBoolPref("gfx.webrender.debug.profiler", prefs.showDrawSpeed, BRANCH),
            SetGeckoPreference.setBoolPref("gfx.webrender.debug.gpu-time-queries", prefs.showDrawSpeed, BRANCH),
        ) + lookPrefs(prefs.sitesAsDesigned) + privacyPrefs(prefs)
        GeckoPreferenceController.setGeckoPrefs(list).accept({ result ->
            val failed = result?.filterValues { !it }?.keys.orEmpty()
            if (failed.isNotEmpty()) L.w("Engine prefs not set: $failed")
        }, { e -> L.w("Engine prefs failed", e) })
        // The drawing method comes from the start-up config; a value saved by Swift 1.1 in the
        // profile would override it.
        GeckoPreferenceController.clearGeckoUserPref(SOFTWARE_RENDERING_PREF)
    }

    private const val SOFTWARE_RENDERING_PREF = "gfx.webrender.software"

    /**
     * Settings the engine must have before it starts, such as the drawing method: it is fixed when
     * the graphics process starts, so setting it later would only apply after a restart.
     */
    private fun writeStartupConfig(): String {
        val file = File(app.filesDir, "gecko-startup.yaml")
        val text = buildString {
            append("prefs:\n")
            append("  $SOFTWARE_RENDERING_PREF: ${prefs.softwareRendering}\n")
            if (!prefs.sitesAsDesigned) {
                // Frosted glass (backdrop-filter, e.g. YouTube's bottom bar and the badges on every
                // thumbnail) blurs whatever scrolls under it on every frame: far too slow on this
                // phone, on the GPU and the CPU alike. Sites show their plain see-through colour.
                append("  $BACKDROP_FILTER_PREF: false\n")
                // Sites that have a version without decorative animations use it.
                append("  $REDUCED_MOTION_PREF: 1\n")
            }
            // All tabs share one engine process for web pages (Firefox allows 8). Each extra
            // process costs 60-100 MB, which a 2 GB phone doesn't have: Android then closes Swift.
            append("  dom.ipc.processCount: 1\n")
        }
        try {
            if (!file.exists() || file.readText() != text) file.writeText(text)
        } catch (e: Exception) {
            L.w("Could not write the engine start-up config", e)
        }
        return file.path
    }

    /** Text size on all sites, 1 = as designed. Pages pick it up when they (re)load. */
    fun setTextScale(scale: Float) {
        runtime.settings.setFontSizeFactor(scale)
    }

    /** Pinch-zooming even on sites that turn it off. */
    fun setForceZoom(on: Boolean) {
        runtime.settings.setForceUserScalableEnabled(on)
    }

    /** Draw pages with the CPU instead of the GPU (Prefs.softwareRendering). Takes effect after the app restarts. */
    fun applySoftwareRendering() {
        writeStartupConfig()
        GeckoPreferenceController.clearGeckoUserPref(SOFTWARE_RENDERING_PREF)
    }

    private const val BACKDROP_FILTER_PREF = "layout.css.backdrop-filter.enabled"
    private const val REDUCED_MOTION_PREF = "ui.prefersReducedMotion"

    /**
     * Glass blur and animations as sites designed them, or Swift's lighter version for slow phones.
     * Also set in the profile (not only at start-up), so a change shows when pages reload.
     */
    private fun lookPrefs(asDesigned: Boolean) = listOf<SetGeckoPreference<*>>(
        SetGeckoPreference.setBoolPref(BACKDROP_FILTER_PREF, asDesigned, BRANCH),
        SetGeckoPreference.setIntPref(REDUCED_MOTION_PREF, if (asDesigned) 0 else 1, BRANCH),
    )

    /** Prefs.sitesAsDesigned changed. */
    fun applySitesAsDesigned() {
        writeStartupConfig()
        GeckoPreferenceController.setGeckoPrefs(lookPrefs(prefs.sitesAsDesigned)).accept({ result ->
            val failed = result?.filterValues { !it }?.keys.orEmpty()
            if (failed.isNotEmpty()) L.w("Look prefs not set: $failed")
        }, { e -> L.w("Look prefs failed", e) })
    }

    /** Frames per second, the time each frame takes and what it was spent on. */
    private const val DRAW_SPEED_UI =
        "FPS,Frame CPU total,Renderer,GPU,Texture cache update,Shader build time,Rendered picture tiles,Draw calls,Color passes,Alpha passes"

    /** Shows or hides the drawing-speed meter on pages, immediately. GPU timing is measured only meanwhile. */
    /**
     * Secure connections first and Firefox's own answer to cookie banners (Settings → Privacy).
     * HTTPS-First rather than HTTPS-only: sites without a secure version still open (Swift says so,
     * see TabDelegates), as GeckoView has no way to let one such site through HTTPS-only mode.
     */
    private fun privacyPrefs(prefs: Prefs): List<SetGeckoPreference<*>> {
        val banners = if (prefs.rejectCookieBanners) 1 else 0
        return listOf(
            SetGeckoPreference.setBoolPref("dom.security.https_only_mode", false, BRANCH),
            SetGeckoPreference.setBoolPref("dom.security.https_only_mode_pbm", false, BRANCH),
            SetGeckoPreference.setBoolPref("dom.security.https_first", prefs.httpsOnly, BRANCH),
            SetGeckoPreference.setBoolPref("dom.security.https_first_pbm", prefs.httpsOnly, BRANCH),
            // 1: click "Reject" (or hide the banner) where Firefox knows the site's banner.
            SetGeckoPreference.setIntPref("cookiebanners.service.mode", banners, BRANCH),
            SetGeckoPreference.setIntPref("cookiebanners.service.mode.privateBrowsing", banners, BRANCH),
        )
    }

    fun applyPrivacyPrefs() {
        GeckoPreferenceController.setGeckoPrefs(privacyPrefs(SwiftApp.prefs))
    }

    /** Whether pages are held back now (see [setNetworkBlocked]). */
    @Volatile var networkBlocked = false
        private set

    /**
     * While the VPN should be on but isn't (connecting, or it dropped), nothing leaves the pages:
     * every request goes to a proxy that doesn't exist (nothing listens on port 9 of the phone), so
     * it fails at once, names included (no DNS lookups outside), and WebRTC may only use that proxy.
     * Afterwards the engine's own settings come back.
     */
    fun setNetworkBlocked(blocked: Boolean) {
        if (blocked == networkBlocked) return
        networkBlocked = blocked
        if (blocked) {
            GeckoPreferenceController.setGeckoPrefs(
                listOf(
                    SetGeckoPreference.setStringPref("network.proxy.http", "127.0.0.1", BRANCH),
                    SetGeckoPreference.setIntPref("network.proxy.http_port", 9, BRANCH),
                    SetGeckoPreference.setStringPref("network.proxy.ssl", "127.0.0.1", BRANCH),
                    SetGeckoPreference.setIntPref("network.proxy.ssl_port", 9, BRANCH),
                    SetGeckoPreference.setStringPref("network.proxy.socks", "127.0.0.1", BRANCH),
                    SetGeckoPreference.setIntPref("network.proxy.socks_port", 9, BRANCH),
                    SetGeckoPreference.setBoolPref("network.proxy.socks_remote_dns", true, BRANCH),
                    SetGeckoPreference.setStringPref("network.proxy.no_proxies_on", "", BRANCH),
                    SetGeckoPreference.setBoolPref("network.proxy.allow_hijacking_localhost", true, BRANCH),
                    SetGeckoPreference.setBoolPref("media.peerconnection.ice.proxy_only", true, BRANCH),
                    SetGeckoPreference.setIntPref("network.proxy.type", 1, BRANCH),
                ),
            )
        } else {
            for (name in BLOCK_PREFS) GeckoPreferenceController.clearGeckoUserPref(name)
        }
    }

    /** At start: pages are never held back by a previous run (these settings would survive it). */
    fun resetNetworkBlock() {
        networkBlocked = true
        setNetworkBlocked(false)
    }

    private val BLOCK_PREFS = listOf(
        "network.proxy.type", "network.proxy.http", "network.proxy.http_port", "network.proxy.ssl", "network.proxy.ssl_port",
        "network.proxy.socks", "network.proxy.socks_port", "network.proxy.socks_remote_dns", "network.proxy.no_proxies_on",
        "network.proxy.allow_hijacking_localhost", "media.peerconnection.ice.proxy_only",
    )

    fun setDrawSpeedMeter(enabled: Boolean) {
        GeckoPreferenceController.setGeckoPrefs(
            listOf(
                SetGeckoPreference.setBoolPref("gfx.webrender.debug.profiler", enabled, BRANCH),
                SetGeckoPreference.setBoolPref("gfx.webrender.debug.gpu-time-queries", enabled, BRANCH),
            ),
        )
    }

    /** Lists downloaded before updates were turned off (e.g. by version 1.0). Runs before the engine starts. */
    /** Files the engine left in its temporary folder (it can hold several MB); nothing uses them before it starts. */
    private fun removeTempFiles(app: Application) {
        try {
            File(app.cacheDir, "gecko_temp").listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            L.w("Could not remove temporary files", e)
        }
    }

    private fun removeSafeBrowsingData(app: Application) {
        try {
            File(app.filesDir, "mozilla").listFiles()?.forEach { profile ->
                File(profile, "safebrowsing").takeIf { it.isDirectory }?.deleteRecursively()
            }
        } catch (e: Exception) {
            L.w("Could not remove Safe Browsing data", e)
        }
    }

    private fun installExtensions(app: Application) {
        val controller = runtime.webExtensionController
        val sp = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val reinstallUbo = sp.getInt("ubo_bridge_rev", 0) != UBO_BRIDGE_REV
        val ubo = if (reinstallUbo) controller.installBuiltIn(UBO_URI) else controller.ensureBuiltIn(UBO_URI, UBO_ID)
        ubo.then { ext -> allowInPrivateTabs(ext) }.accept({ ext: WebExtension? ->
            if (ext == null) return@accept
            if (reinstallUbo) sp.edit { putInt("ubo_bridge_rev", UBO_BRIDGE_REV) }
            AdBlock.attach(ext)
            TabManager.onExtensionReady()
        }, { e: Throwable? -> L.e("uBlock Origin install failed", e) })

        controller.ensureBuiltIn(HELPER_URI, HELPER_ID).then { ext -> allowInPrivateTabs(ext) }.accept({ ext: WebExtension? ->
            if (ext == null) return@accept
            Helper.attach(ext)
            TabManager.onExtensionReady()
        }, { e: Throwable? -> L.e("Helper extension install failed", e) })
    }

    /** Extensions don't run in incognito tabs unless allowed. A failure here must not stop them elsewhere. */
    private fun allowInPrivateTabs(ext: WebExtension?): GeckoResult<WebExtension> {
        if (ext == null || ext.metaData.allowedInPrivateBrowsing) return GeckoResult.fromValue(ext)
        return runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true).then(
            GeckoResult.OnValueListener { updated -> GeckoResult.fromValue(updated ?: ext) },
            GeckoResult.OnExceptionListener { e ->
                L.w("Couldn't allow ${ext.id} in incognito tabs", e)
                GeckoResult.fromValue(ext)
            },
        )
    }

    private fun colorScheme(dark: Boolean) =
        if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT

    /** Sites that support dark mode (prefers-color-scheme) switch immediately. */
    fun setDark(dark: Boolean) {
        runtime.settings.setPreferredColorScheme(colorScheme(dark))
    }
}
