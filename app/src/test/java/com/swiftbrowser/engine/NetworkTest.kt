package com.swiftbrowser.engine

import com.swiftbrowser.engine.MobileData.Net
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0: mobile data behind a VPN (MobileData) and the user agent sites are told (UserAgent). */
class NetworkTest {
    private val vpn = Net(vpn = true, wifi = false, cellular = false)
    private val wifi = Net(vpn = false, wifi = true, cellular = false)
    private val cellular = Net(vpn = false, wifi = false, cellular = true)

    @Test
    fun withoutAVpn() {
        assertTrue(MobileData.onMobile(cellular, emptyList()))
        assertFalse(MobileData.onMobile(wifi, emptyList()))
        assertFalse(MobileData.onMobile(Net(vpn = false, wifi = true, cellular = true), emptyList()))
    }

    @Test
    fun behindAVpnTheNetworkUnderneathCounts() {
        // Android 7 gives apps the VPN, which says neither: 2.9 took mobile data for Wi-Fi.
        assertTrue(MobileData.onMobile(vpn, listOf(vpn, cellular)))
        assertFalse(MobileData.onMobile(vpn, listOf(vpn, wifi)))
        // Both up (mobile data kept on while Wi-Fi is checked): Android uses Wi-Fi.
        assertFalse(MobileData.onMobile(vpn, listOf(cellular, wifi)))
        // Mobile data without internet doesn't count; nothing at all is not mobile data.
        assertFalse(MobileData.onMobile(vpn, listOf(Net(vpn = false, wifi = false, cellular = true, internet = false))))
        assertFalse(MobileData.onMobile(vpn, emptyList()))
    }

    private val engines = "Mozilla/5.0 (Android 10; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0"

    @Test
    fun newerFirefoxVersion() {
        assertEquals(
            "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Gecko/153.0 Firefox/153.0",
            UserAgent.withVersion(engines, UserAgent.NEWER),
        )
        // The desktop version, as the engine makes it (GeckoViewSettings.getDesktopUserAgent).
        assertEquals("Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0", UserAgent.desktopOf(engines))
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0",
            UserAgent.desktopOf(UserAgent.withVersion(engines, 153)),
        )
        assertEquals(
            "Mozilla/5.0 (Android 7.1.2; Mobile; rv:153.0) Gecko/153.0 Firefox/153.0",
            UserAgent.withVersion("Mozilla/5.0 (Android 7.1.2; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0", 153),
        )
    }
}
