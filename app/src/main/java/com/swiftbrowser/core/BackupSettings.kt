package com.swiftbrowser.core

/**
 * Which settings a backup carries (3.0): only the user's own choices, each with the kind of value it
 * holds. Before 3.0 a backup took every setting but a few, so the weekly one in Download/Swift (which
 * any app with storage access can read) held the Private folder's PIN check, the files moved into
 * Private, the sites never to save passwords for and what only the phone knows (permissions asked,
 * hints seen, counters). A setting not listed here stays on the phone; a new setting that should
 * travel needs a line here.
 *
 * Plain Kotlin (no Android, no org.json), so the PC's tests can check it: Backup turns JSON into the
 * values given here and back.
 */
object BackupSettings {
    /** The kinds of value, as a backup writes them ("t" next to each value). */
    const val BOOLEAN = "b"
    const val INT = "i"
    const val LONG = "l"
    const val FLOAT = "f"
    const val STRING = "s"
    const val SET = "set"

    /** The user's choices, by their key in Swift's preferences, and the kind each one holds. */
    val KEYS: Map<String, String> = mapOf(
        // Look & screen
        "dark_mode" to BOOLEAN, "theme_mode" to INT, "accent" to STRING, "accent_custom" to INT,
        "light_bg" to STRING, "light_bg_custom" to INT, "dark_bg" to STRING, "dark_bg_custom" to INT,
        "night_light" to INT, "night_light_strength" to INT, "text_scale" to FLOAT, "force_zoom" to BOOLEAN,
        "full_screen" to BOOLEAN, "haptics" to BOOLEAN,
        // The address bar and the menu
        "toolbar_at_bottom" to BOOLEAN, "hide_toolbar_on_scroll" to BOOLEAN, "toolbar_follows_scroll" to BOOLEAN,
        "bar_swipes" to INT, "toolbar_layout" to STRING, "menu_layout" to STRING, "menu_hidden" to STRING,
        // 3.0.4: websites' colours in it.
        "site_colours" to BOOLEAN,
        // Before 2.2, read once to carry them over.
        "menu_buttons" to STRING, "show_home_button" to BOOLEAN,
        // Home & news
        "news_enabled" to BOOLEAN, "home_order" to STRING, "home_background" to STRING, "home_picture_clarity" to INT, "news_country" to STRING, "news_topics" to SET,
        "news_feeds" to STRING, "news_hidden" to SET, "home_continue" to BOOLEAN, "hidden_tiles" to SET,
        "news_topic" to STRING,
        // Browsing & tabs
        "search_engine" to STRING, "search_custom_url" to STRING, "search_custom_name" to STRING,
        "search_suggestions" to BOOLEAN, "pull_to_refresh" to BOOLEAN, "edge_swipe" to BOOLEAN,
        "inactive_days" to INT, "reader_font" to STRING, "reader_spacing" to INT,
        // 3.0.4: reader view's page colour (sepia).
        "reader_theme" to STRING,
        // 3.0.4: the tab grid as a list, and the group's strip by the bar (pinned tabs are tabs, not settings).
        "tabs_as_list" to BOOLEAN, "group_strip" to BOOLEAN,
        "translate_offer" to BOOLEAN, "translate_to" to STRING,
        // Ad blocking
        "ad_level" to STRING, "hide_cookie_notices" to BOOLEAN,
        // Speed & memory
        "lite_mode" to BOOLEAN, "lite_sites" to SET, "sites_as_designed" to BOOLEAN, "max_live_tabs" to STRING,
        // 3.0.7: Lite mode's level (lite_mode and sites_as_designed stay for older backups).
        "lite_level" to STRING,
        "data_saver" to BOOLEAN, "battery_saver" to BOOLEAN, "battery_saver_level" to INT, "show_draw_speed" to BOOLEAN,
        // Video & downloads
        "keep_playing" to BOOLEAN, "continue_watching" to BOOLEAN, "video_taps" to BOOLEAN, "video_gestures" to BOOLEAN,
        "auto_landscape" to BOOLEAN, "autoplay_muted" to BOOLEAN, "site_player" to BOOLEAN, "hls_to_mp4" to BOOLEAN,
        "confirm_downloads" to BOOLEAN, "wifi_only_downloads" to BOOLEAN, "downloads_by_type" to BOOLEAN,
        "download_folder" to STRING,
        // Swift's player
        "player_gestures" to BOOLEAN, "player_resume" to BOOLEAN, "player_background_audio" to BOOLEAN,
        "player_speed" to FLOAT, "player_subtitle_size" to INT, "player_subtitle_colour" to INT,
        "player_subtitle_background" to INT, "player_subtitle_language" to STRING, "player_rotation" to STRING, "player_double_tap_seconds" to INT,
        // 3.0.3: the equalizer and where the subtitles were dragged to (the one-time hints stay out).
        "player_eq_on" to BOOLEAN, "player_eq_preset" to INT, "player_eq_levels" to STRING, "player_eq_bass" to INT, "player_subtitle_lift" to FLOAT,
        // 3.0.4: night sound (loud parts quieter, quiet voices louder).
        "player_night_sound" to BOOLEAN,
        // Torrents
        "torrent_at_once" to INT, "torrent_down_kbps" to INT, "torrent_up_kbps" to INT, "torrent_wifi_only" to BOOLEAN,
        "torrent_battery_pause" to BOOLEAN, "torrent_extra_trackers" to BOOLEAN, "torrent_charging_only" to BOOLEAN,
        "torrent_night_only" to BOOLEAN, "torrent_encrypted_only" to BOOLEAN,
        // Files
        "files_hidden" to BOOLEAN, "files_start_at" to STRING, "files_grid" to BOOLEAN, "files_sort" to INT,
        "files_category_sort" to INT, "files_thumbnails" to BOOLEAN, "files_recycle_bin" to BOOLEAN,
        "files_bin_days" to INT, "files_wifi_port" to INT,
        // 3.0.4: two folders side by side (where each side was is not a setting).
        "files_two_panes" to BOOLEAN, "upload_from_files" to BOOLEAN,
        // Privacy & security
        "https_only" to BOOLEAN, "reject_cookie_banners" to BOOLEAN, "secure_dns" to STRING,
        "incognito_screenshots" to BOOLEAN, "safer_browsing" to BOOLEAN, "newer_firefox_version" to BOOLEAN,
        // Per-site settings (lists of sites; text sizes as "site=size")
        "site_desktop" to SET, "site_autoplay" to SET, "site_location_allow" to SET, "site_location_block" to SET,
        "site_no_lite" to SET, "site_reader" to SET, "site_dark" to SET, "site_text_scale" to SET,
        // 3.0.7: sites' own Lite mode levels ("site=level").
        "site_lite_level" to SET,
        // Storage & data
        "quit_clear" to SET, "auto_backup" to BOOLEAN,
        // 3.0.7: the cache size.
        "cache_limit_mb" to INT,
    )

    /** Settings of one profile, their key ending in the profile's id ("pinned_tiles_3"), and their kind. */
    val PER_PROFILE: Map<String, String> = mapOf("pinned_tiles_" to STRING)

    /**
     * Never in a backup, even if listed by mistake: the Private folder (its PIN check, tries and
     * files), sign-ins and passwords, the VPN (its locations hold private keys).
     */
    private val NEVER = listOf("files_private_", "login_", "password", "vpn_")

    /** The kind of value [key] holds when it is a setting backups carry, else null. */
    fun kindOf(key: String): String? {
        if (NEVER.any { key.startsWith(it) }) return null
        KEYS[key]?.let { return it }
        return PER_PROFILE.entries.firstOrNull { (prefix, _) -> profileOf(key, prefix) != null }?.value
    }

    /** The prefix of a per-profile setting ("pinned_tiles_" of "pinned_tiles_3"), or null. */
    fun perProfilePrefix(key: String): String? = PER_PROFILE.keys.firstOrNull { profileOf(key, it) != null }

    /** The profile id at the end of a per-profile [key] with [prefix], or null when it isn't one. */
    fun profileOf(key: String, prefix: String): Long? =
        if (key.startsWith(prefix)) key.substring(prefix.length).takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull() else null

    /**
     * What a backup keeps of [key] holding [value] (as Android's preferences give it): its kind and the
     * value (a set as a sorted list of texts), or null when it stays on the phone or isn't of its kind.
     */
    fun forBackup(key: String, value: Any?): Pair<String, Any>? {
        val kind = kindOf(key) ?: return null
        val v: Any = when (kind) {
            BOOLEAN -> value as? Boolean
            INT -> value as? Int
            LONG -> value as? Long
            FLOAT -> value as? Float
            STRING -> value as? String
            SET -> (value as? Set<*>)?.takeIf { s -> s.all { it is String } }?.map { it as String }?.sorted()
            else -> null
        } ?: return null
        return kind to v
    }

    /**
     * A setting read from a backup: [kind] is what the backup says it is, [value] what it holds (JSON
     * numbers, texts, true/false, or a list for a set). Gives the value to keep, of the kind [key]
     * holds here; null when Swift doesn't restore [key] or the value isn't of its kind (a wrong kind
     * would make reading the setting fail at every start).
     */
    fun fromBackup(key: String, kind: String?, value: Any?): Any? {
        val expected = kindOf(key) ?: return null
        if (kind != expected) return null
        return when (expected) {
            BOOLEAN -> value as? Boolean
            INT -> whole(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            LONG -> whole(value)
            FLOAT -> (value as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() && kotlin.math.abs(it) <= Float.MAX_VALUE }?.toFloat()
            STRING -> value as? String
            // Only texts; anything else in the list is left out.
            SET -> (value as? List<*>)?.filterIsInstance<String>()?.toSet()
            else -> null
        }
    }

    /** A whole number (JSON gives Integer, Long or Double), or null. */
    private fun whole(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> value.takeIf { !it.isNaN() && !it.isInfinite() && it == kotlin.math.floor(it) && kotlin.math.abs(it) <= 9.0E15 }?.toLong()
        is Float -> value.toDouble().takeIf { !it.isNaN() && !it.isInfinite() && it == kotlin.math.floor(it) && kotlin.math.abs(it) <= 9.0E15 }?.toLong()
        else -> null
    }
}
