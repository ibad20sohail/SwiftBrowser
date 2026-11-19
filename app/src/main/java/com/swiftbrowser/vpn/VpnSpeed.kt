package com.swiftbrowser.vpn

import java.util.Locale

/**
 * 3.0.13: "Test the locations" measures each location's real speed: a few seconds of download from
 * Cloudflare's speed server through the tunnel, and how quickly small requests come back (ping).
 * Before, it only timed the WireGuard handshake, which is mostly Android setting the tunnel up
 * ("answered in 363 ms" said nothing about speed). The sums and the words are plain, for tests
 * (VpnSpeedTest); Vpn does the measuring.
 */
object VpnSpeed {
    /** Cloudflare's speed server answers over plain http too (no certificates for Android 7 to doubt). */
    const val URL = "http://speed.cloudflare.com/__down"

    /** How much a location may download: about 5 seconds at 40 Mbps on Wi-Fi; little on mobile data. */
    const val WIFI_BYTES = 25_000_000L
    const val MOBILE_BYTES = 5_000_000L

    /** How long the download runs at most, and the start left out of the sum (the connection speeds up first). */
    const val DOWNLOAD_MS = 5_000L
    const val WARM_UP_MS = 500L

    /** Megabits a second for [bytes] in [ms]; null when nothing came or no time passed. */
    fun mbps(bytes: Long, ms: Long): Double? = if (bytes <= 0L || ms <= 0L) null else bytes * 8.0 / ms / 1000.0

    /**
     * The speed of a download that read [total] bytes in [totalMs], [counted] of them in the last
     * [countedMs] (after the warm-up): that part when it is long enough to tell, else the whole.
     */
    fun downloadMbps(total: Long, totalMs: Long, counted: Long, countedMs: Long): Double? =
        if (countedMs >= 300L && counted > 0L) mbps(counted, countedMs) else mbps(total, totalMs)

    /** "Fast", "Good" or "Slow" for a speed in Mbps. */
    fun word(mbps: Double): String = when {
        mbps >= 10.0 -> "Fast"
        mbps >= 3.0 -> "Good"
        else -> "Slow"
    }

    /** "12.3 Mbps", "0.8 Mbps", "105 Mbps". */
    fun speedText(mbps: Double): String =
        if (mbps >= 100.0) String.format(Locale.US, "%.0f Mbps", mbps) else String.format(Locale.US, "%.1f Mbps", mbps)

    const val NO_ANSWER = "No answer"
    const val NOTHING_THROUGH = "Answers, but nothing came through"

    /**
     * What a location's row says after the test: [answered] (its server did the handshake), the
     * download's speed and the ping when they were measured.
     */
    fun text(answered: Boolean, mbps: Double?, pingMs: Long?): String = when {
        !answered -> NO_ANSWER
        mbps == null -> NOTHING_THROUGH
        else -> listOfNotNull(word(mbps), speedText(mbps), pingMs?.let { "$it ms" }).joinToString(" · ")
    }
}
