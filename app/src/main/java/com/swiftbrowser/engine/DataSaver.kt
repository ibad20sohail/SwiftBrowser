package com.swiftbrowser.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference

/**
 * Data saver (Settings → Speed & memory): on mobile data, pages load without images. Wi-Fi brings
 * them back. Pages already open keep what they have; the next page (or a reload) follows.
 */
object DataSaver {
    private const val IMAGES_PREF = "permissions.default.image"
    private val main = Handler(Looper.getMainLooper())

    /** On mobile data (and not Wi-Fi) right now. */
    var onMobileData = false
        private set
    private var applied: Boolean? = null
    var listener: ((Boolean) -> Unit)? = null

    /** Told when the phone moves between Wi-Fi and mobile data (Settings, downloads waiting for Wi-Fi). */
    val networkListeners = ArrayList<() -> Unit>()

    /** Told when the phone has a connection again (pages that failed to load, failed downloads). */
    val onlineListeners = ArrayList<() -> Unit>()

    /** The phone has a connection (as far as Android knows). */
    var online = true
        private set

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        onMobileData = isMobile(cm.getNetworkCapabilities(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        online = true
                        onlineListeners.toList().forEach { it() }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    main.post { update(isMobile(caps)) }
                }

                override fun onLost(network: Network) {
                    main.post {
                        online = false
                        update(false)
                    }
                }
            })
        } catch (e: Exception) {
            L.w("Couldn't watch the network", e)
        }
        apply()
    }

    private fun isMobile(caps: NetworkCapabilities?): Boolean =
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

    private fun update(mobile: Boolean) {
        if (mobile == onMobileData) return
        onMobileData = mobile
        apply()
        networkListeners.toList().forEach { it() }
    }

    /** True while images are held back (also on mobile data while saving battery, see [Power]). */
    val active: Boolean get() = (SwiftApp.prefs.dataSaver || Power.saving) && onMobileData

    /** "Show images" for this site, while it is the one on screen. */
    private var allowedSite: String? = null

    /** Shows images on [site] until another site is opened. */
    fun showImagesOn(site: String) {
        allowedSite = site
        apply()
    }

    /** The screen shows a page of [site]: images held back again when it isn't the allowed one. */
    fun onSiteShown(site: String?) {
        if (allowedSite != null && site != allowedSite) {
            allowedSite = null
            apply()
        }
    }

    /** The setting or the network changed. */
    fun apply() {
        val block = active && allowedSite == null
        if (applied == block) return
        applied = block
        GeckoPreferenceController.setGeckoPrefs(
            listOf(SetGeckoPreference.setIntPref(IMAGES_PREF, if (block) 2 else 1, GeckoPreferenceController.PREF_BRANCH_USER)),
        )
        L.d("Data saver: images ${if (block) "off" else "on"}")
        listener?.invoke(block)
    }
}
