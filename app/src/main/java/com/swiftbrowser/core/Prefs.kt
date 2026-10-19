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

    var autoLandscape: Boolean
        get() = sp.getBoolean(AUTO_LANDSCAPE, true)
        set(v) = sp.edit { putBoolean(AUTO_LANDSCAPE, v) }

    var videoGestures: Boolean
        get() = sp.getBoolean(VIDEO_GESTURES, true)
        set(v) = sp.edit { putBoolean(VIDEO_GESTURES, v) }

    var hlsToMp4: Boolean
        get() = sp.getBoolean(HLS_TO_MP4, true)
        set(v) = sp.edit { putBoolean(HLS_TO_MP4, v) }

    var confirmDownloads: Boolean
        get() = sp.getBoolean(CONFIRM_DOWNLOADS, true)
        set(v) = sp.edit { putBoolean(CONFIRM_DOWNLOADS, v) }

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

    /** Draw pages on the CPU instead of the GPU (applies after a restart). */
    var softwareRendering: Boolean
        get() = sp.getBoolean(SOFTWARE_RENDERING, false)
        set(v) = sp.edit { putBoolean(SOFTWARE_RENDERING, v) }

    /** How many tabs keep their page loaded in memory; the others reload when opened. */
    var maxLiveTabs: Int
        get() = sp.getString(MAX_LIVE_TABS, "2")?.toIntOrNull()?.coerceIn(1, 4) ?: 2
        set(v) = sp.edit { putString(MAX_LIVE_TABS, v.toString()) }

    var currentProfileId: Long
        get() = sp.getLong(CURRENT_PROFILE, 1L)
        set(v) = sp.edit { putLong(CURRENT_PROFILE, v) }

    /** Sites the user removed from the home page shortcuts. */
    var hiddenTiles: Set<String>
        get() = sp.getStringSet(HIDDEN_TILES, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet(HIDDEN_TILES, v) }

    companion object {
        const val DARK_MODE = "dark_mode"
        const val AD_LEVEL = "ad_level"
        const val AUTO_LANDSCAPE = "auto_landscape"
        const val VIDEO_GESTURES = "video_gestures"
        const val HLS_TO_MP4 = "hls_to_mp4"
        const val CONFIRM_DOWNLOADS = "confirm_downloads"
        const val SEARCH_SUGGESTIONS = "search_suggestions"
        const val HIDE_TOOLBAR = "hide_toolbar_on_scroll"
        const val PULL_TO_REFRESH = "pull_to_refresh"
        const val SOFTWARE_RENDERING = "software_rendering"
        const val MAX_LIVE_TABS = "max_live_tabs"
        const val CURRENT_PROFILE = "current_profile"
        const val HIDDEN_TILES = "hidden_tiles"
    }
}
