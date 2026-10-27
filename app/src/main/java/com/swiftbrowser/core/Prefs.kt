package com.swiftbrowser.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.swiftbrowser.adblock.AdLevel

/** App settings, shown and changed on the settings screen. */
class Prefs(ctx: Context) {
    // Same file androidx.preference used in 1.0, so settings carry over.
    val sp: SharedPreferences = ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE)

    /** Whether the app and pages are dark right now (follows [themeMode]; see Looks). */
    var darkMode: Boolean
        get() = sp.getBoolean(DARK_MODE, false)
        set(v) = sp.edit { putBoolean(DARK_MODE, v) }

    /** Light, dark, or dark at night (Looks.LIGHT / DARK / NIGHT). Before 1.9 only on or off. */
    var themeMode: Int
        get() = sp.getInt(THEME_MODE, if (darkMode) 1 else 0)
        set(v) = sp.edit { putInt(THEME_MODE, v) }

    /**
     * Address bar swipes (2.1): [SWIPE_HIDE] swiping on the bar hides it, [SWIPE_SHOW] swiping in
     * from the screen's edge shows it again; both by default.
     */
    var barSwipes: Int
        get() = sp.getInt(BAR_SWIPES, SWIPE_HIDE or SWIPE_SHOW)
        set(v) = sp.edit { putInt(BAR_SWIPES, v) }

    /** The welcome cards were shown (first start of a new install, 2.0). */
    var welcomeDone: Boolean
        get() = sp.getBoolean(WELCOME_DONE, false)
        set(v) = sp.edit { putBoolean(WELCOME_DONE, v) }

    /** News on the home page (2.0). */
    var newsEnabled: Boolean
        get() = sp.getBoolean(NEWS_ENABLED, true)
        set(v) = sp.edit { putBoolean(NEWS_ENABLED, v) }

    /** The Documents shortcut among the home page's shortcuts (2.4). */
    var homeDocuments: Boolean
        get() = sp.getBoolean(HOME_DOCUMENTS, true)
        set(v) = sp.edit { putBoolean(HOME_DOCUMENTS, v) }

    /** Night light (2.3): 0 off, 1 at night, 2 always (see ui.NightLight). */
    var nightLight: Int
        get() = sp.getInt(NIGHT_LIGHT, 0)
        set(v) = sp.edit { putInt(NIGHT_LIGHT, v) }

    /** How warm the night light is: 1 low, 2 medium, 3 high. */
    var nightLightStrength: Int
        get() = sp.getInt(NIGHT_LIGHT_STRENGTH, 2)
        set(v) = sp.edit { putInt(NIGHT_LIGHT_STRENGTH, v) }

    /** The home page's "Continue" row: videos and articles left half-way (2.3). */
    var homeContinue: Boolean
        get() = sp.getBoolean(HOME_CONTINUE, true)
        set(v) = sp.edit { putBoolean(HOME_CONTINUE, v) }

    /** The news country's code, or empty for the phone's region (News.country). */
    var newsCountry: String
        get() = sp.getString(NEWS_COUNTRY, "") ?: ""
        set(v) = sp.edit { putString(NEWS_COUNTRY, v) }

    /** The news topics shown as chips (News.Topic keys); Top stories always is. */
    var newsTopics: Set<String>
        get() = sp.getStringSet(NEWS_TOPICS, null)?.toSet() ?: setOf("top", "world", "nation", "business", "tech", "sports", "entertainment")
        set(v) = sp.edit { putStringSet(NEWS_TOPICS, v) }

    /** Feeds of news sites the user added, in the order added. */
    var newsFeeds: List<String>
        get() = (sp.getString(NEWS_FEEDS, "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit { putString(NEWS_FEEDS, v.joinToString("\n")) }

    /** Sites whose stories are hidden (holding a story → Hide). */
    var newsHidden: Set<String>
        get() = sp.getStringSet(NEWS_HIDDEN, null)?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet(NEWS_HIDDEN, v) }

    /** Before 2.2: the menu's first-page buttons (MenuButtons keys). Only read to carry them over. */
    val menuButtons: List<String>
        get() = (sp.getString(MENU_BUTTONS, null) ?: DEFAULT_MENU_BUTTONS).split(',').filter { it.isNotBlank() }

    /**
     * 2.2: the menu as arranged, page by page ("new_tab,incognito,…|recent,vpn,…"); null until the
     * first arranging (MenuButtons makes the starting layout).
     */
    var menuLayout: String?
        get() = sp.getString(MENU_LAYOUT, null)
        set(v) = sp.edit { if (v == null) remove(MENU_LAYOUT) else putString(MENU_LAYOUT, v) }

    /** 2.2: menu buttons hidden while arranging (MenuButtons keys). */
    var menuHidden: List<String>
        get() = (sp.getString(MENU_HIDDEN, null) ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit { putString(MENU_HIDDEN, v.joinToString(",")) }

    /** 2.2: the buttons next to the address ("profile,home|tabs,menu"); null until arranged. */
    var toolbarLayout: String?
        get() = sp.getString(TOOLBAR_LAYOUT, null)
        set(v) = sp.edit { if (v == null) remove(TOOLBAR_LAYOUT) else putString(TOOLBAR_LAYOUT, v) }

    /** 2.2: news stories as cards with a big picture (as in Chrome) instead of compact rows. */
    var newsCards: Boolean
        get() = sp.getBoolean(NEWS_CARDS, false)
        set(v) = sp.edit { putBoolean(NEWS_CARDS, v) }

    /** 2.2: secure DNS (DNS over HTTPS): "off", "cloudflare", "quad9" or "google". */
    var secureDns: String
        get() = sp.getString(SECURE_DNS, "cloudflare") ?: "cloudflare"
        set(v) = sp.edit { putString(SECURE_DNS, v) }

    /** The accent colour's key (Accent.key): one of the presets, or "custom" ([accentCustom]). */
    var accent: String
        get() = sp.getString(ACCENT, "blue") ?: "blue"
        set(v) = sp.edit { putString(ACCENT, v) }

    /** 2.3: the colour picked for a custom accent. */
    var accentCustom: Int
        get() = sp.getInt(ACCENT_CUSTOM, 0xFF1B66E4.toInt())
        set(v) = sp.edit { putInt(ACCENT_CUSTOM, v) }

    /** 2.3: the background in light mode: "white", "warm", "cool" or "custom" ([lightBgCustom]). */
    var lightBg: String
        get() = sp.getString(LIGHT_BG, "white") ?: "white"
        set(v) = sp.edit { putString(LIGHT_BG, v) }

    var lightBgCustom: Int
        get() = sp.getInt(LIGHT_BG_CUSTOM, 0xFFFCF8F0.toInt())
        set(v) = sp.edit { putInt(LIGHT_BG_CUSTOM, v) }

    /** 2.3: the background in dark mode: "grey", "black", "navy", "warm" or "custom" ([darkBgCustom]). */
    var darkBg: String
        get() = sp.getString(DARK_BG, "grey") ?: "grey"
        set(v) = sp.edit { putString(DARK_BG, v) }

    var darkBgCustom: Int
        get() = sp.getInt(DARK_BG_CUSTOM, 0xFF0D1322.toInt())
        set(v) = sp.edit { putInt(DARK_BG_CUSTOM, v) }

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

    /** Before 2.2: the house button next to the address. Only read to carry it over (ToolbarButtons). */
    val showHomeButton: Boolean
        get() = sp.getBoolean(SHOW_HOME_BUTTON, true)

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

    /** Low battery: Lite mode, one tab loaded, no pictures on mobile data (see engine.Power, 2.3). */
    var batterySaver: Boolean
        get() = sp.getBoolean(BATTERY_SAVER, true)
        set(v) = sp.edit { putBoolean(BATTERY_SAVER, v) }

    /** The battery saver starts below this many percent (15, 20 or 30). */
    var batterySaverLevel: Int
        get() = sp.getInt(BATTERY_SAVER_LEVEL, 20)
        set(v) = sp.edit { putInt(BATTERY_SAVER_LEVEL, v) }

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

    /** A backup file is written once a week by itself (the last two are kept). */
    var autoBackup: Boolean
        get() = sp.getBoolean(AUTO_BACKUP, true)
        set(v) = sp.edit { putBoolean(AUTO_BACKUP, v) }

    var lastAutoBackup: Long
        get() = sp.getLong(LAST_AUTO_BACKUP, 0L)
        set(v) = sp.edit { putLong(LAST_AUTO_BACKUP, v) }

    /** When Swift last emptied its cache because the phone was almost full. */
    var lastLowSpaceCleanup: Long
        get() = sp.getLong(LAST_LOW_SPACE_CLEANUP, 0L)
        set(v) = sp.edit { putLong(LAST_LOW_SPACE_CLEANUP, v) }

    /** The version whose "What's new" was shown (0 on a new install). */
    var seenVersion: Int
        get() = sp.getInt(SEEN_VERSION, 0)
        set(v) = sp.edit { putInt(SEEN_VERSION, v) }

    /** When Swift last asked GitHub for a newer version. */
    var lastUpdateCheck: Long
        get() = sp.getLong(LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit { putLong(LAST_UPDATE_CHECK, v) }

    companion object {
        const val DARK_MODE = "dark_mode"
        const val THEME_MODE = "theme_mode"
        const val ACCENT = "accent"
        const val ACCENT_CUSTOM = "accent_custom"
        const val LIGHT_BG = "light_bg"
        const val LIGHT_BG_CUSTOM = "light_bg_custom"
        const val DARK_BG = "dark_bg"
        const val DARK_BG_CUSTOM = "dark_bg_custom"
        const val MENU_BUTTONS = "menu_buttons"
        const val MENU_LAYOUT = "menu_layout"
        const val MENU_HIDDEN = "menu_hidden"
        const val TOOLBAR_LAYOUT = "toolbar_layout"
        const val SECURE_DNS = "secure_dns"
        const val NEWS_CARDS = "news_cards"
        const val NEWS_ENABLED = "news_enabled"
        const val HOME_CONTINUE = "home_continue"
        const val NIGHT_LIGHT = "night_light"
        const val HOME_DOCUMENTS = "home_documents"
        const val NIGHT_LIGHT_STRENGTH = "night_light_strength"
        const val WELCOME_DONE = "welcome_done"
        const val BAR_SWIPES = "bar_swipes"
        const val SWIPE_HIDE = 1
        const val SWIPE_SHOW = 2
        const val NEWS_COUNTRY = "news_country"
        const val NEWS_TOPICS = "news_topics"
        const val NEWS_FEEDS = "news_feeds"
        const val NEWS_HIDDEN = "news_hidden"
        const val DEFAULT_MENU_BUTTONS = "new_tab,incognito,bookmarks,history,downloads,reader,save"
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
        const val AUTO_BACKUP = "auto_backup"
        const val LAST_AUTO_BACKUP = "last_auto_backup"
        const val LAST_LOW_SPACE_CLEANUP = "last_low_space_cleanup"
        const val SEEN_VERSION = "seen_version"
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
        const val BATTERY_SAVER = "battery_saver"
        const val BATTERY_SAVER_LEVEL = "battery_saver_level"
        const val DOWNLOAD_FOLDER = "download_folder"
    }
}
