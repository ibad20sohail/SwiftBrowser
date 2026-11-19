package com.swiftbrowser.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnSpeedTest {
    @Test
    fun megabitsASecond() {
        // 1.25 MB in a second is 10 Mbps.
        assertEquals(10.0, VpnSpeed.mbps(1_250_000, 1_000)!!, 0.001)
        assertNull(VpnSpeed.mbps(0, 1_000))
        assertNull(VpnSpeed.mbps(1_000, 0))
    }

    @Test
    fun theWarmUpIsLeftOut() {
        // 4.5 s after the warm-up read 5 MB (8.9 Mbps); the whole 5 s read 5.1 MB.
        assertEquals(8.889, VpnSpeed.downloadMbps(5_100_000, 5_000, 5_000_000, 4_500)!!, 0.01)
    }

    @Test
    fun aShortDownloadCountsWhole() {
        // Done within the warm-up: the whole time.
        assertEquals(16.0, VpnSpeed.downloadMbps(1_000_000, 500, 0, 0)!!, 0.001)
        assertNull(VpnSpeed.downloadMbps(0, 500, 0, 0))
    }

    @Test
    fun wordsFollowSpeed() {
        assertEquals("Fast", VpnSpeed.word(12.3))
        assertEquals("Good", VpnSpeed.word(3.0))
        assertEquals("Slow", VpnSpeed.word(0.8))
    }

    @Test
    fun whatTheRowSays() {
        assertEquals("Fast · 12.3 Mbps · 84 ms", VpnSpeed.text(true, 12.34, 84))
        assertEquals("Fast · 105 Mbps", VpnSpeed.text(true, 105.2, null))
        assertEquals("Slow · 0.8 Mbps · 410 ms", VpnSpeed.text(true, 0.8, 410))
        assertEquals("No answer", VpnSpeed.text(false, null, null))
        assertEquals("Answers, but nothing came through", VpnSpeed.text(true, null, 90))
    }
}
