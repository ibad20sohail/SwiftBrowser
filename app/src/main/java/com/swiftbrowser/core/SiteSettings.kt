package com.swiftbrowser.core

import androidx.core.content.edit
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock

/**
 * What the user chose for one site (by its registrable domain, see UrlUtils.site): desktop
 * version, sound that starts by itself, location, left out of Lite mode. The ad-blocking level per site is kept by
 * AdBlock; together they make the site settings page.
 *
 * 3.0.1: what is chosen from an incognito tab (the `incognito` arguments) is kept in memory only and
 * goes when the last incognito tab closes ([onIncognitoEnded]): incognito leaves nothing behind.
 * Incognito tabs start from the saved settings and their own choices count only for them, except
 * Lite mode and Dark page, which one page style serves for every tab (as one uBO does for ad
 * blocking): those count for the site's normal tabs too until incognito ends. A choice made in a
 * normal tab is saved and counts in incognito as well.
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

    /** The on/off settings (location has three choices). */
    private val SWITCHES = listOf(DESKTOP, AUTOPLAY, NO_LITE, READER, DARK)

    /** Settings the helper's page styles carry for every tab alike (3.0.1). */
    private val FOR_ALL_TABS = setOf(NO_LITE, DARK)

    /** What incognito tabs chose (3.0.1), on the main thread. */
    private val incognitoSwitches = SWITCHES.associateWith { IncognitoChoices<Boolean>() }
    private val incognitoLocation = IncognitoChoices<Location>()
    private val incognitoScale = IncognitoChoices<Float>()

    private val sp get() = SwiftApp.prefs.sp

    private fun set(key: String): Set<String> = sp.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun isOn(key: String, site: String?, incognito: Boolean): Boolean {
        site ?: return false
        val saved = site in set(key)
        val choices = incognitoSwitches[key] ?: return saved
        return if (incognito || key in FOR_ALL_TABS) choices.get(site, saved) == true else saved
    }

    private fun put(key: String, site: String, on: Boolean, incognito: Boolean = false) {
        if (incognito) {
            incognitoSwitches[key]?.set(site, on)
            return
        }
        incognitoSwitches[key]?.drop(site)
        val s = set(key)
        if ((site in s) == on) return
        sp.edit { putStringSet(key, if (on) s + site else s - site) }
    }

    /** The site always gets its desktop version. */
    fun desktop(site: String?, incognito: Boolean = false): Boolean = isOn(DESKTOP, site, incognito)

    fun setDesktop(site: String, on: Boolean, incognito: Boolean = false) = put(DESKTOP, site, on, incognito)

    /** Videos on the site may start with sound by themselves (blocked everywhere else). */
    fun autoplay(site: String?, incognito: Boolean = false): Boolean = isOn(AUTOPLAY, site, incognito)

    fun setAutoplay(site: String, on: Boolean, incognito: Boolean = false) = put(AUTOPLAY, site, on, incognito)

    fun location(site: String?, incognito: Boolean = false): Location {
        site ?: return Location.ASK
        val saved = when (site) {
            in set(LOCATION_ALLOW) -> Location.ALLOW
            in set(LOCATION_BLOCK) -> Location.BLOCK
            else -> Location.ASK
        }
        return if (incognito) incognitoLocation.get(site, saved) ?: Location.ASK else saved
    }

    /** Lite mode leaves the site as it is (for a site that looks broken with it). */
    fun noLite(site: String?, incognito: Boolean = false): Boolean = isOn(NO_LITE, site, incognito)

    fun setNoLite(site: String, on: Boolean, incognito: Boolean = false) {
        put(NO_LITE, site, on, incognito)
        com.swiftbrowser.media.Helper.sendLook()
    }

    /** The sites left out of Lite mode now, with the ones chosen from incognito tabs. */
    fun liteExceptions(): List<String> = incognitoSwitches.getValue(NO_LITE).sitesOn(set(NO_LITE)).sorted()

    /** The saved ones only: what the helper may write down. */
    fun liteExceptionsKept(): List<String> = set(NO_LITE).sorted()

    /** The site's articles open in reader view by themselves (2.3). */
    fun autoReader(site: String?, incognito: Boolean = false): Boolean = isOn(READER, site, incognito)

    fun setAutoReader(site: String, on: Boolean, incognito: Boolean = false) = put(READER, site, on, incognito)

    /** Swift turns the site dark (2.6): for sites without a dark mode of their own. */
    fun darkPage(site: String?, incognito: Boolean = false): Boolean = isOn(DARK, site, incognito)

    fun setDarkPage(site: String, on: Boolean, incognito: Boolean = false) {
        put(DARK, site, on, incognito)
        com.swiftbrowser.media.Helper.sendLook()
    }

    /** The sites turned dark now, with the ones chosen from incognito tabs. */
    fun darkPages(): List<String> = incognitoSwitches.getValue(DARK).sitesOn(set(DARK)).sorted()

    /** The saved ones only: what the helper may write down. */
    fun darkPagesKept(): List<String> = set(DARK).sorted()

    /** The site's own text size (1 = as designed); null: the size for all sites. */
    fun textScale(site: String?, incognito: Boolean = false): Float? {
        site ?: return null
        val prefix = "$site="
        val saved = set(TEXT_SCALE).firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)?.toFloatOrNull()
        return if (incognito) incognitoScale.get(site, saved) else saved
    }

    fun setTextScale(site: String, scale: Float?, incognito: Boolean = false) {
        if (incognito) {
            incognitoScale.set(site, scale)
            return
        }
        incognitoScale.drop(site)
        val prefix = "$site="
        val others = set(TEXT_SCALE).filterNot { it.startsWith(prefix) }.toSet()
        sp.edit { putStringSet(TEXT_SCALE, if (scale == null) others else others + "$prefix$scale") }
    }

    fun setLocation(site: String, value: Location, incognito: Boolean = false) {
        if (incognito) {
            incognitoLocation.set(site, value)
            return
        }
        incognitoLocation.drop(site)
        put(LOCATION_ALLOW, site, value == Location.ALLOW)
        put(LOCATION_BLOCK, site, value == Location.BLOCK)
    }

    /** How many sites may use the location without asking (saved), for the security status (3.0.1). */
    fun locationAllowedCount(): Int = set(LOCATION_ALLOW).size

    /** Sites with anything saved, for the list in Settings (never what incognito tabs chose). */
    fun sites(): List<String> =
        (KEYS.flatMap { set(it) } + set(TEXT_SCALE).map { it.substringBefore('=') } + AdBlock.overrides().keys).toSortedSet().toList()

    /** Whether [site] has anything set; [incognito]: as incognito tabs see it. */
    fun hasSettings(site: String, incognito: Boolean = false): Boolean =
        if (!incognito) {
            site in sites()
        } else {
            SWITCHES.any { isOn(it, site, true) } || location(site, true) != Location.ASK ||
                textScale(site, true) != null || AdBlock.overrideFor(site) != null
        }

    fun reset(site: String, incognito: Boolean = false) {
        val look = noLite(site) || darkPage(site)
        if (incognito) {
            for (key in SWITCHES) put(key, site, false, incognito = true)
            setLocation(site, Location.ASK, incognito = true)
            setTextScale(site, null, incognito = true)
            AdBlock.setOverride(site, null, incognito = true)
        } else {
            for (key in KEYS) put(key, site, false)
            incognitoLocation.drop(site)
            setTextScale(site, null)
            AdBlock.setOverride(site, null)
        }
        if (look) com.swiftbrowser.media.Helper.sendLook()
    }

    /** What [site] has set, to put back with Undo (2.6). */
    class Saved(val site: String, val keys: List<String>, val scale: Float?, val level: com.swiftbrowser.adblock.AdLevel?)

    /** [incognito]: what incognito tabs see, to put back as incognito choices. */
    fun save(site: String, incognito: Boolean = false): Saved {
        if (!incognito) return Saved(site, KEYS.filter { site in set(it) }, textScale(site), AdBlock.savedOverrideFor(site))
        val place = when (location(site, true)) {
            Location.ALLOW -> listOf(LOCATION_ALLOW)
            Location.BLOCK -> listOf(LOCATION_BLOCK)
            Location.ASK -> emptyList()
        }
        return Saved(site, SWITCHES.filter { isOn(it, site, true) } + place, textScale(site, true), AdBlock.overrideFor(site))
    }

    fun restore(s: Saved, incognito: Boolean = false) {
        for (key in s.keys) {
            when (key) {
                LOCATION_ALLOW -> setLocation(s.site, Location.ALLOW, incognito)
                LOCATION_BLOCK -> setLocation(s.site, Location.BLOCK, incognito)
                else -> put(key, s.site, true, incognito)
            }
        }
        setTextScale(s.site, s.scale, incognito)
        AdBlock.setOverride(s.site, s.level, incognito)
        com.swiftbrowser.media.Helper.sendLook()
    }

    /** The last incognito tab closed: what was chosen there goes (3.0.1). */
    fun onIncognitoEnded() {
        var look = false
        for ((key, choices) in incognitoSwitches) if (choices.clear() && key in FOR_ALL_TABS) look = true
        incognitoLocation.clear()
        incognitoScale.clear()
        AdBlock.onIncognitoEnded()
        if (look) com.swiftbrowser.media.Helper.sendLook()
    }

    /** For a backup: everything above, by kind (saved settings only). */
    fun export(): Map<String, Set<String>> =
        KEYS.associateWith { set(it) }

    fun import(data: Map<String, Set<String>>) {
        sp.edit {
            for ((key, sites) in data) if (key in KEYS) putStringSet(key, set(key) + sites)
        }
    }
}
