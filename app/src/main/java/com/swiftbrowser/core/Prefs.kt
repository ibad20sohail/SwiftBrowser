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

    /** SearchEngine key: google, duckduckgo, bing or startpage. */
    var searchEngine: String
        get() = sp.getString(SEARCH_ENGINE, "google") ?: "google"
        set(v) = sp.edit { putString(SEARCH_ENGINE, v) }

    var searchSuggestions: Boolean
        get() = sp.getBoolean(SEARCH_SUGGESTIONS, true)
        set(v) = sp.edit { putBoolean(SEARCH_SUGGESTIONS, v) }

    /** Hide the toolbar while scrolling down a page, show it again when scrolling up. */
    var hideToolbarOnScroll: Boolean
        get() = sp.getBoolean(HIDE_TOOLBAR, true)
        set(v) = sp.edit { putBoolean(HIDE_TOOLBAR, v) }

    /**
     * The toolbar slides away and back with the finger (as in Chrome) instead of jumping in one
     * frame after a deliberate scroll.
     */
    var toolbarFollowsScroll: Boolean
        get() = sp.getBoolean(TOOLBAR_FOLLOWS, true)
        set(v) = sp.edit { putBoolean(TOOLBAR_FOLLOWS, v) }

    /** The address bar at the bottom of the screen, easier to reach with one hand. */
    var toolbarAtBottom: Boolean
        get() = sp.getBoolean(TOOLBAR_BOTTOM, false)
        set(v) = sp.edit { putBoolean(TOOLBAR_BOTTOM, v) }

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

    /**
     * A video or music keeps playing when you switch tabs, go to another app or turn the screen off.
     * Off: it pauses as soon as its page is out of view.
     */
    var keepPlaying: Boolean
        get() = sp.getBoolean(KEEP_PLAYING, true)
        set(v) = sp.edit { putBoolean(KEEP_PLAYING, v) }

    /** The house button in the toolbar, which shows the home page. */
    var showHomeButton: Boolean
        get() = sp.getBoolean(SHOW_HOME_BUTTON, true)
        set(v) = sp.edit { putBoolean(SHOW_HOME_BUTTON, v) }

    /** A light vibration on long presses, dragging tabs and grouping them. */
    var haptics: Boolean
        get() = sp.getBoolean(HAPTICS, true)
        set(v) = sp.edit { putBoolean(HAPTICS, v) }

    /** Pages always load over a secure connection; an insecure site asks first. */
    var httpsOnly: Boolean
        get() = sp.getBoolean(HTTPS_ONLY, false)
        set(v) = sp.edit { putBoolean(HTTPS_ONLY, v) }

    /** Firefox answers cookie banners with "Reject" where it knows how. */
    var rejectCookieBanners: Boolean
        get() = sp.getBoolean(REJECT_COOKIE_BANNERS, true)
        set(v) = sp.edit { putBoolean(REJECT_COOKIE_BANNERS, v) }

    /** Large downloads wait for Wi-Fi when the phone is on mobile data. */
    var wifiOnlyDownloads: Boolean
        get() = sp.getBoolean(WIFI_ONLY_DOWNLOADS, false)
        set(v) = sp.edit { putBoolean(WIFI_ONLY_DOWNLOADS, v) }

    /** Reader view: "serif" (as before) or "sans". */
    var readerFont: String
        get() = sp.getString(READER_FONT, "serif") ?: "serif"
        set(v) = sp.edit { putString(READER_FONT, v) }

    /** Reader view line spacing: 0 tight, 1 normal, 2 loose. */
    var readerSpacing: Int
        get() = sp.getInt(READER_SPACING, 1)
        set(v) = sp.edit { putInt(READER_SPACING, v) }

    /** Swiping in from the left or right edge of a page goes back or forward. */
    var edgeSwipe: Boolean
        get() = sp.getBoolean(EDGE_SWIPE, true)
        set(v) = sp.edit { putBoolean(EDGE_SWIPE, v) }

    /** Sites that had Lite mode in 1.4, when it was chosen per site. */
    private val liteSites: Set<String>
        get() = sp.getStringSet(LITE_SITES, emptySet())?.toSet() ?: emptySet()

    /**
     * Lite mode on every site: no animations, shadows or blur. Whoever used it on a site in 1.4
     * (when it was per site) has it on.
     */
    var liteMode: Boolean
        get() = if (sp.contains(LITE_MODE)) sp.getBoolean(LITE_MODE, false) else liteSites.isNotEmpty()
        set(v) = sp.edit { putBoolean(LITE_MODE, v) }

    /** Browsing without Android's status and navigation bars (a swipe from the edge shows them). */
    var fullScreen: Boolean
        get() = sp.getBoolean(FULL_SCREEN, false)
        set(v) = sp.edit { putBoolean(FULL_SCREEN, v) }

    /**
     * Tabs not opened for this many days move to "Inactive tabs" at the end of the tab grid
     * (0 = never).
     */
    var inactiveDays: Int
        get() = sp.getInt(INACTIVE_DAYS, 14)
        set(v) = sp.edit { putInt(INACTIVE_DAYS, v) }

    /** Set at start-up: this phone has plenty of memory and a graphics chip that draws fast. */
    var autoSitesAsDesigned = false

    /**
     * Sites look exactly as designed: Swift's changes for slow phones (no glass blur, fewer
     * animations, lighter YouTube and TheFlixer) are off. Automatic until changed.
     */
    var sitesAsDesigned: Boolean
        get() = if (sp.contains(SITES_AS_DESIGNED)) sp.getBoolean(SITES_AS_DESIGNED, false) else autoSitesAsDesigned
        set(v) = sp.edit { putBoolean(SITES_AS_DESIGNED, v) }

    /** uBlock Origin's cookie-notice lists hide "Accept cookies" pop-ups (always on in Extreme). */
    var hideCookieNotices: Boolean
        get() = sp.getBoolean(HIDE_COOKIE_NOTICES, false)
        set(v) = sp.edit { putBoolean(HIDE_COOKIE_NOTICES, v) }

    /** Folder for downloads (a full path); empty: Download/Swift. See Downloads.directory. */
    var downloadFolder: String
        get() = sp.getString(DOWNLOAD_FOLDER, "") ?: ""
        set(v) = sp.edit { putString(DOWNLOAD_FOLDER, v) }

    /** On mobile data, pages load without images (see DataSaver). */
    var dataSaver: Boolean
        get() = sp.getBoolean(DATA_SAVER, false)
        set(v) = sp.edit { putBoolean(DATA_SAVER, v) }

    /** Remember where long videos were left off and offer to continue from there. */
    var continueWatching: Boolean
        get() = sp.getBoolean(CONTINUE_WATCHING, true)
        set(v) = sp.edit { putBoolean(CONTINUE_WATCHING, v) }

    /** On full-screen videos: double-tap the sides to skip 10 seconds, hold for double speed. */
    var videoTaps: Boolean
        get() = sp.getBoolean(VIDEO_TAPS, true)
        set(v) = sp.edit { putBoolean(VIDEO_TAPS, v) }

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
        const val SEARCH_ENGINE = "search_engine"
        const val HIDE_TOOLBAR = "hide_toolbar_on_scroll"
        const val TOOLBAR_FOLLOWS = "toolbar_follows_scroll"
        const val TOOLBAR_BOTTOM = "toolbar_at_bottom"
        const val PULL_TO_REFRESH = "pull_to_refresh"
        const val SOFTWARE_RENDERING = "software_rendering"
        const val MAX_LIVE_TABS = "max_live_tabs"
        const val MAX_LIVE_TABS_LIMIT = 10
        const val CURRENT_PROFILE = "current_profile"
        const val HIDDEN_TILES = "hidden_tiles"
        const val TEXT_SCALE = "text_scale"
        const val FORCE_ZOOM = "force_zoom"
        const val KEEP_PLAYING = "keep_playing"
        const val SHOW_HOME_BUTTON = "show_home_button"
        const val HAPTICS = "haptics"
        const val HTTPS_ONLY = "https_only"
        const val REJECT_COOKIE_BANNERS = "reject_cookie_banners"
        const val WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        const val READER_FONT = "reader_font"
        const val READER_SPACING = "reader_spacing"
        const val EDGE_SWIPE = "edge_swipe"
        const val LITE_SITES = "lite_sites"
        const val LITE_MODE = "lite_mode"
        const val FULL_SCREEN = "full_screen"
        const val LAST_UPDATE_CHECK = "last_update_check"
        const val INACTIVE_DAYS = "inactive_days"
        const val SITES_AS_DESIGNED = "sites_as_designed"
        const val HIDE_COOKIE_NOTICES = "hide_cookie_notices"
        const val CONTINUE_WATCHING = "continue_watching"
        const val VIDEO_TAPS = "video_taps"
        const val DATA_SAVER = "data_saver"
        const val DOWNLOAD_FOLDER = "download_folder"
    }
}
