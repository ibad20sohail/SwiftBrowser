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
        onMobileData = isMobile(cm, cm.getNetworkCapabilities(cm.activeNetwork))
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        online = true
                        onlineListeners.toList().forEach { it() }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    main.post { update(isMobile(cm, caps)) }
                }

                override fun onLost(network: Network) {
                    main.post {
                        online = false
                        update(false)
                    }
                }
            })
            // 3.0: behind a VPN the network above doesn't change when the phone moves between
            // Wi-Fi and mobile data underneath it; these do (a request leaves VPNs out).
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = recheck()

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheck()

                    override fun onLost(network: Network) = recheck()

                    private fun recheck() {
                        main.post {
                            val active = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@post
                            if (active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) update(isMobile(cm, active))
                        }
                    }
                },
            )
        } catch (e: Exception) {
            L.w("Couldn't watch the network", e)
        }
        apply()
    }

    private fun netOf(caps: NetworkCapabilities) = MobileData.Net(
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        // An Ethernet adapter is as good as Wi-Fi.
        wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    )

    private fun isMobile(cm: ConnectivityManager, caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        val active = netOf(caps)
        if (!active.vpn) return MobileData.onMobile(active, emptyList())
        val under = try {
            // Android 12 prefers callbacks for this; one look at the networks is all that's needed here.
            @Suppress("DEPRECATION")
            cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { netOf(it) } }
        } catch (_: Exception) {
            emptyList()
        }
        return MobileData.onMobile(active, under)
    }

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

/** 3.0: telling mobile data from Wi-Fi, also behind a VPN (DataSaver). Plain, for tests. */
object MobileData {
    /** What it needs to know of one network. */
    class Net(val vpn: Boolean, val wifi: Boolean, val cellular: Boolean, val internet: Boolean = true)

    /**
     * Whether the phone is on mobile data, from the network in use ([active]) and, when that is a
     * VPN (Swift's own included), the others ([others]): on Android 7 the VPN is the network apps get,
     * and it says neither Wi-Fi nor mobile data, so mobile data underneath looked like Wi-Fi (pictures
     * stayed on, Wi-Fi-only downloads and torrents ran on mobile data). Wi-Fi wins when both are up,
     * as Android uses it then.
     */
    fun onMobile(active: Net, others: List<Net>): Boolean {
        if (!active.vpn) return active.cellular && !active.wifi
        val under = others.filter { !it.vpn && it.internet }
        if (under.any { it.wifi }) return false
        return under.any { it.cellular }
    }
}
