package com.swiftbrowser.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.swiftbrowser.adblock.AdLevel

/** App settings, shown and changed on the settings screen. */
class Prefs(ctx: Context) {
    // Same file androidx.preference used in 1.0, so settings carry over.
    val sp: SharedPreferences = ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE)

    var darkMode: Boolean
        get() = sp.getBoolean(DARK_MODE, false)
        set(v) = sp.edit { putBoolean(DARK_MODE, v) }

    var adLevel: AdLevel
        get() = AdLevel.fromKey(sp.getString(AD_LEVEL, AdLevel.HIGH.key))
        set(v) = sp.edit { putString(AD_LEVEL, v.key) }

    /** Full-screen videos turn the screen to landscape. Off: the phone's own auto-rotate decides. */
    var autoLandscape: Boolean
        get() = sp.getBoolean(AUTO_LANDSCAPE, false)
        set(v) = sp.edit { putBoolean(AUTO_LANDSCAPE, v) }

    /** Swipes on full-screen videos change brightness and volume. */
    var videoGestures: Boolean
        get() = sp.getBoolean(VIDEO_GESTURES, false)
        set(v) = sp.edit { putBoolean(VIDEO_GESTURES, v) }

    var hlsToMp4: Boolean
        get() = sp.getBoolean(HLS_TO_MP4, true)
        set(v) = sp.edit { putBoolean(HLS_TO_MP4, v) }

    var confirmDownloads: Boolean
        get() = sp.getBoolean(CONFIRM_DOWNLOADS, true)
        set(v) = sp.edit { putBoolean(CONFIRM_DOWNLOADS, v) }

    /**
     * Let silent videos start by themselves, such as the previews that play in YouTube's feed while
     * scrolling. Off by default: decoding them makes scrolling choppy on this phone.
     */
    var autoplayMuted: Boolean
        get() = sp.getBoolean(AUTOPLAY_MUTED, false)
        set(v) = sp.edit { putBoolean(AUTOPLAY_MUTED, v) }

    /** Show the engine's drawing-speed meter (frames per second and frame times) on pages. */
    var showDrawSpeed: Boolean
        get() = sp.getBoolean(SHOW_DRAW_SPEED, false)
        set(v) = sp.edit { putBoolean(SHOW_DRAW_SPEED, v) }

    var searchSuggestions: Boolean
        get() = sp.getBoolean(SEARCH_SUGGESTIONS, true)
        set(v) = sp.edit { putBoolean(SEARCH_SUGGESTIONS, v) }

    /** Hide the toolbar while scrolling down a page, show it again when scrolling up. */
    var hideToolbarOnScroll: Boolean
        get() = sp.getBoolean(HIDE_TOOLBAR, true)
        set(v) = sp.edit { putBoolean(HIDE_TOOLBAR, v) }

    var pullToRefresh: Boolean
        get() = sp.getBoolean(PULL_TO_REFRESH, true)
        set(v) = sp.edit { putBoolean(PULL_TO_REFRESH, v) }

    /** Set at start-up: this phone's graphics chip draws pages slower than its CPU (see GpuInfo). */
    var autoSoftwareRendering = false

    /** Draw pages on the CPU instead of the GPU (applies after a restart). Automatic until changed. */
    var softwareRendering: Boolean
        get() = if (sp.contains(SOFTWARE_RENDERING)) sp.getBoolean(SOFTWARE_RENDERING, false) else autoSoftwareRendering
        set(v) = sp.edit { putBoolean(SOFTWARE_RENDERING, v) }

    /** How many tabs keep their page loaded in memory; the others reload when opened. */
    var maxLiveTabs: Int
        get() = sp.getString(MAX_LIVE_TABS, "2")?.toIntOrNull()?.coerceIn(1, MAX_LIVE_TABS_LIMIT) ?: 2
        set(v) = sp.edit { putString(MAX_LIVE_TABS, v.toString()) }

    var currentProfileId: Long
        get() = sp.getLong(CURRENT_PROFILE, 1L)
        set(v) = sp.edit { putLong(CURRENT_PROFILE, v) }

    /** Sites the user removed from the home page shortcuts. */
    var hiddenTiles: Set<String>
        get() = sp.getStringSet(HIDDEN_TILES, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet(HIDDEN_TILES, v) }

    /** Text size on all sites (1 = as the site designed it). */
    var textScale: Float
        get() = sp.getFloat(TEXT_SCALE, 1f).coerceIn(0.5f, 2f)
        set(v) = sp.edit { putFloat(TEXT_SCALE, v) }

    /** Pinch-zooming allowed even on sites that turn it off. */
    var forceZoom: Boolean
        get() = sp.getBoolean(FORCE_ZOOM, false)
        set(v) = sp.edit { putBoolean(FORCE_ZOOM, v) }

    /** Videos and music keep playing when Swift is in the background or the screen is off. */
    var backgroundPlay: Boolean
        get() = sp.getBoolean(BACKGROUND_PLAY, true)
        set(v) = sp.edit { putBoolean(BACKGROUND_PLAY, v) }

    /** Swiping in from the left or right edge of a page goes back or forward. */
    var edgeSwipe: Boolean
        get() = sp.getBoolean(EDGE_SWIPE, true)
        set(v) = sp.edit { putBoolean(EDGE_SWIPE, v) }

    /** Sites shown in Lite mode: no animations, shadows or filters. */
    var liteSites: Set<String>
        get() = sp.getStringSet(LITE_SITES, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet(LITE_SITES, v) }

    /** When Swift last asked GitHub for a newer version. */
    var lastUpdateCheck: Long
        get() = sp.getLong(LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit { putLong(LAST_UPDATE_CHECK, v) }

    companion object {
        const val DARK_MODE = "dark_mode"
        const val AD_LEVEL = "ad_level"
        const val AUTO_LANDSCAPE = "auto_landscape"
        const val VIDEO_GESTURES = "video_gestures"
        const val HLS_TO_MP4 = "hls_to_mp4"
        const val CONFIRM_DOWNLOADS = "confirm_downloads"
        const val AUTOPLAY_MUTED = "autoplay_muted"
        const val SHOW_DRAW_SPEED = "show_draw_speed"
        const val SEARCH_SUGGESTIONS = "search_suggestions"
        const val HIDE_TOOLBAR = "hide_toolbar_on_scroll"
        const val PULL_TO_REFRESH = "pull_to_refresh"
        const val SOFTWARE_RENDERING = "software_rendering"
        const val MAX_LIVE_TABS = "max_live_tabs"
        const val MAX_LIVE_TABS_LIMIT = 10
        const val CURRENT_PROFILE = "current_profile"
        const val HIDDEN_TILES = "hidden_tiles"
        const val TEXT_SCALE = "text_scale"
        const val FORCE_ZOOM = "force_zoom"
        const val BACKGROUND_PLAY = "background_play"
        const val EDGE_SWIPE = "edge_swipe"
        const val LITE_SITES = "lite_sites"
        const val LAST_UPDATE_CHECK = "last_update_check"
    }
}
