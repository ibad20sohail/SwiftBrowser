package com.swiftbrowser.engine

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.core.content.edit
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebExtension

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
    private const val UBO_BRIDGE_REV = 2

    lateinit var runtime: GeckoRuntime
        private set

    val executor: GeckoWebExecutor by lazy { GeckoWebExecutor(runtime) }

    /** Lets the UI start activities for Gecko (used by passkeys / WebAuthn). */
    var activityDelegate: GeckoRuntime.ActivityDelegate? = null

    fun init(app: Application, prefs: Prefs) {
        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking)
            .preferredColorScheme(colorScheme(prefs.darkMode))
            .aboutConfigEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .lowMemoryDetection(true)
            .loginAutofillEnabled(false)
            .build()
        runtime = GeckoRuntime.create(app, settings)
        runtime.delegate = GeckoRuntime.Delegate {
            L.e("Gecko runtime shut down")
            Process.killProcess(Process.myPid())
        }
        runtime.activityDelegate = GeckoRuntime.ActivityDelegate { pending ->
            activityDelegate?.onStartActivityForResult(pending)
                ?: GeckoResult.fromException(IllegalStateException("No activity"))
        }
        installExtensions(app)
    }

    private fun installExtensions(app: Application) {
        val controller = runtime.webExtensionController
        val sp = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val reinstallUbo = sp.getInt("ubo_bridge_rev", 0) != UBO_BRIDGE_REV
        val ubo = if (reinstallUbo) controller.installBuiltIn(UBO_URI) else controller.ensureBuiltIn(UBO_URI, UBO_ID)
        ubo.accept({ ext: WebExtension? ->
            if (ext == null) return@accept
            if (reinstallUbo) sp.edit { putInt("ubo_bridge_rev", UBO_BRIDGE_REV) }
            AdBlock.attach(ext)
            TabManager.onExtensionReady()
        }, { e: Throwable? -> L.e("uBlock Origin install failed", e) })

        controller.ensureBuiltIn(HELPER_URI, HELPER_ID).accept({ ext: WebExtension? ->
            if (ext == null) return@accept
            Helper.attach(ext)
            TabManager.onExtensionReady()
        }, { e: Throwable? -> L.e("Helper extension install failed", e) })
    }

    private fun colorScheme(dark: Boolean) =
        if (dark) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT

    /** Sites that support dark mode (prefers-color-scheme) switch immediately. */
    fun setDark(dark: Boolean) {
        runtime.settings.setPreferredColorScheme(colorScheme(dark))
    }
}
