package com.swiftbrowser.core

import androidx.core.content.edit
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock

/**
 * What the user chose for one site (by its registrable domain, see UrlUtils.site): desktop
 * version, sound that starts by itself, location. The ad-blocking level per site is kept by
 * AdBlock; together they make the site settings page.
 */
object SiteSettings {
    enum class Location(val label: String) { ASK("Ask"), ALLOW("Allow"), BLOCK("Block") }

    private const val DESKTOP = "site_desktop"
    private const val AUTOPLAY = "site_autoplay"
    private const val LOCATION_ALLOW = "site_location_allow"
    private const val LOCATION_BLOCK = "site_location_block"

    private val sp get() = SwiftApp.prefs.sp

    private fun set(key: String): Set<String> = sp.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun put(key: String, site: String, on: Boolean) {
        val s = set(key)
        if ((site in s) == on) return
        sp.edit { putStringSet(key, if (on) s + site else s - site) }
    }

    /** The site always gets its desktop version. */
    fun desktop(site: String?): Boolean = site != null && site in set(DESKTOP)

    fun setDesktop(site: String, on: Boolean) = put(DESKTOP, site, on)

    /** Videos on the site may start with sound by themselves (blocked everywhere else). */
    fun autoplay(site: String?): Boolean = site != null && site in set(AUTOPLAY)

    fun setAutoplay(site: String, on: Boolean) = put(AUTOPLAY, site, on)

    fun location(site: String?): Location = when {
        site == null -> Location.ASK
        site in set(LOCATION_ALLOW) -> Location.ALLOW
        site in set(LOCATION_BLOCK) -> Location.BLOCK
        else -> Location.ASK
    }

    fun setLocation(site: String, value: Location) {
        put(LOCATION_ALLOW, site, value == Location.ALLOW)
        put(LOCATION_BLOCK, site, value == Location.BLOCK)
    }

    /** Sites with anything set, for the list in Settings. */
    fun sites(): List<String> =
        (set(DESKTOP) + set(AUTOPLAY) + set(LOCATION_ALLOW) + set(LOCATION_BLOCK) + AdBlock.overrides().keys).sorted()

    fun hasSettings(site: String) = site in sites()

    fun reset(site: String) {
        for (key in listOf(DESKTOP, AUTOPLAY, LOCATION_ALLOW, LOCATION_BLOCK)) put(key, site, false)
        AdBlock.setOverride(site, null)
    }

    /** For a backup: everything above, by kind. */
    fun export(): Map<String, Set<String>> =
        listOf(DESKTOP, AUTOPLAY, LOCATION_ALLOW, LOCATION_BLOCK).associateWith { set(it) }

    fun import(data: Map<String, Set<String>>) {
        sp.edit {
            for ((key, sites) in data) if (key in listOf(DESKTOP, AUTOPLAY, LOCATION_ALLOW, LOCATION_BLOCK)) putStringSet(key, set(key) + sites)
        }
    }
}
