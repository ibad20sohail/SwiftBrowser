package com.swiftbrowser.core

import com.swiftbrowser.adblock.AdLevel

/**
 * Settings → Privacy & security → Security status (3.0.1): the protections in one place, said as
 * they are. "Checked" only where Swift has seen it working (uBlock Origin loaded, the VPN's server
 * answering); "On" for a setting that is on but can't be checked from here; nothing is shown as safe
 * just because a switch is on.
 */
object SecurityStatus {
    enum class State(val word: String) { CHECKED("Checked"), ON("On"), OFF("Off") }

    class Line(val name: String, val state: State, val detail: String)

    enum class Vpn { OFF, CONNECTING, ON, FAILED }

    class Inputs(
        val adLevel: AdLevel,
        val uboReady: Boolean,
        /** "off", "cloudflare", "quad9" or "google". */
        val secureDns: String,
        val httpsOnly: Boolean,
        val saferBrowsing: Boolean,
        /** Passwords saved for this profile; null when the keystore couldn't be read. */
        val passwords: Int?,
        val offerPasswords: Boolean,
        val vpn: Vpn,
        val vpnServer: String?,
        /** Sites allowed to use the location without asking. */
        val locationSites: Int,
    )

    fun lines(i: Inputs): List<Line> {
        val out = ArrayList<Line>()
        out += when {
            i.adLevel == AdLevel.OFF -> Line("Ad blocking", State.OFF, "No site is filtered.")
            i.uboReady -> Line("Ad blocking", State.CHECKED, "${i.adLevel.label}: uBlock Origin is loaded and filtering pages.")
            else -> Line("Ad blocking", State.ON, "${i.adLevel.label}: uBlock Origin is still starting.")
        }
        out += when {
            i.adLevel == AdLevel.OFF -> Line("Fake login (phishing) sites", State.OFF, "Not blocked while ad blocking is off.")
            i.adLevel >= AdLevel.HIGH -> Line("Fake login (phishing) sites", State.ON, "Blocked by a list at ${i.adLevel.label}.")
            else -> Line("Fake login (phishing) sites", State.OFF, "The list is on at High and Extreme. Malware sites are blocked at every level.")
        }
        val dns = when (i.secureDns) {
            "off" -> null
            "quad9" -> "Quad9"
            "google" -> "Google"
            else -> "Cloudflare"
        }
        out += if (dns == null) {
            Line("Secure DNS", State.OFF, "Your network can see which sites you look up.")
        } else {
            Line("Secure DNS", State.ON, "$dns is asked first; if it doesn't answer, your network's own DNS is used.")
        }
        out += if (i.httpsOnly) {
            Line("Secure connections", State.ON, "Pages load over https; an insecure site asks first.")
        } else {
            Line("Secure connections", State.OFF, "Pages load the way their links say.")
        }
        out += if (i.saferBrowsing) {
            Line("Safer browsing", State.ON, "JavaScript speed-ups and WebAssembly are off for web pages.")
        } else {
            Line("Safer browsing", State.OFF, "Pages run at full speed.")
        }
        out += when {
            i.passwords == null -> Line("Saved passwords", State.ON, "Android's keystore couldn't be read just now.")
            i.passwords > 0 -> Line("Saved passwords", State.ON, "${i.passwords} for this profile, encrypted with Android's keystore.")
            i.offerPasswords -> Line("Saved passwords", State.ON, "None yet; Swift offers to save them, encrypted with Android's keystore.")
            else -> Line("Saved passwords", State.OFF, "Swift doesn't offer to save passwords.")
        }
        out += when (i.vpn) {
            Vpn.ON -> Line("VPN", State.CHECKED, "On${i.vpnServer?.let { " through $it" } ?: ""}: its server answered.")
            Vpn.CONNECTING -> Line("VPN", State.ON, "Connecting…")
            Vpn.FAILED -> Line("VPN", State.OFF, "Not connected: it couldn't reach its server.")
            Vpn.OFF -> Line("VPN", State.OFF, "Swift uses your normal connection.")
        }
        out += if (i.locationSites > 0) {
            Line("Location", State.ON, "${i.locationSites} ${if (i.locationSites == 1) "site" else "sites"} may use it without asking (Site settings).")
        } else {
            Line("Location", State.OFF, "No site may use it without asking.")
        }
        return out
    }

    /** The whole status as text, and what the words mean. */
    fun text(lines: List<Line>): String =
        lines.joinToString("\n\n") { "${it.name}: ${it.state.word}. ${it.detail}" } +
            "\n\nChecked: Swift has seen it working. On: set, but not something Swift can check from here."
}
