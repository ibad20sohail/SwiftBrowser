package com.swiftbrowser.core

import androidx.core.content.edit
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock

/**
 * What the user chose for one site (by its registrable domain, see UrlUtils.site): desktop
 * version, sound that starts by itself, location, left out of Lite mode. The ad-blocking level per site is kept by
 * AdBlock; together they make the site settings page.
 */
object SiteSettings {
    enum class Location(val label: String) { ASK("Ask"), ALLOW("Allow"), BLOCK("Block") }

    private const val DESKTOP = "site_desktop"
    private const val AUTOPLAY = "site_autoplay"
    private const val LOCATION_ALLOW = "site_location_allow"
    private const val LOCATION_BLOCK = "site_location_block"
    private const val NO_LITE = "site_no_lite"
    private const val READER = "site_reader"
    private const val DARK = "site_dark"

    /** "site=scale" entries: the site's own text size (2.3). */
    private const val TEXT_SCALE = "site_text_scale"
    private val KEYS = listOf(DESKTOP, AUTOPLAY, LOCATION_ALLOW, LOCATION_BLOCK, NO_LITE, READER, DARK)

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

    /** Lite mode leaves the site as it is (for a site that looks broken with it). */
    fun noLite(site: String?): Boolean = site != null && site in set(NO_LITE)

    fun setNoLite(site: String, on: Boolean) {
        put(NO_LITE, site, on)
        com.swiftbrowser.media.Helper.sendLook()
    }

    fun liteExceptions(): List<String> = set(NO_LITE).sorted()

    /** The site's articles open in reader view by themselves (2.3). */
    fun autoReader(site: String?): Boolean = site != null && site in set(READER)

    fun setAutoReader(site: String, on: Boolean) = put(READER, site, on)

    /** Swift turns the site dark (2.6): for sites without a dark mode of their own. */
    fun darkPage(site: String?): Boolean = site != null && site in set(DARK)

    fun setDarkPage(site: String, on: Boolean) {
        put(DARK, site, on)
        com.swiftbrowser.media.Helper.sendLook()
    }

    fun darkPages(): List<String> = set(DARK).sorted()

    /** The site's own text size (1 = as designed); null: the size for all sites. */
    fun textScale(site: String?): Float? {
        site ?: return null
        val prefix = "$site="
        return set(TEXT_SCALE).firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)?.toFloatOrNull()
    }

    fun setTextScale(site: String, scale: Float?) {
        val prefix = "$site="
        val others = set(TEXT_SCALE).filterNot { it.startsWith(prefix) }.toSet()
        sp.edit { putStringSet(TEXT_SCALE, if (scale == null) others else others + "$prefix$scale") }
    }

    fun setLocation(site: String, value: Location) {
        put(LOCATION_ALLOW, site, value == Location.ALLOW)
        put(LOCATION_BLOCK, site, value == Location.BLOCK)
    }

    /** Sites with anything set, for the list in Settings. */
    fun sites(): List<String> =
        (KEYS.flatMap { set(it) } + set(TEXT_SCALE).map { it.substringBefore('=') } + AdBlock.overrides().keys).toSortedSet().toList()

    fun hasSettings(site: String) = site in sites()

    fun reset(site: String) {
        val look = noLite(site) || darkPage(site)
        for (key in KEYS) put(key, site, false)
        setTextScale(site, null)
        AdBlock.setOverride(site, null)
        if (look) com.swiftbrowser.media.Helper.sendLook()
    }

    /** What [site] has set, to put back with Undo (2.6). */
    class Saved(val site: String, val keys: List<String>, val scale: Float?, val level: com.swiftbrowser.adblock.AdLevel?)

    fun save(site: String) = Saved(site, KEYS.filter { site in set(it) }, textScale(site), AdBlock.overrideFor(site))

    fun restore(s: Saved) {
        for (key in s.keys) put(key, s.site, true)
        setTextScale(s.site, s.scale)
        AdBlock.setOverride(s.site, s.level)
        com.swiftbrowser.media.Helper.sendLook()
    }

    /** For a backup: everything above, by kind. */
    fun export(): Map<String, Set<String>> =
        KEYS.associateWith { set(it) }

    fun import(data: Map<String, Set<String>>) {
        sp.edit {
            for ((key, sites) in data) if (key in KEYS) putStringSet(key, set(key) + sites)
        }
    }
}
