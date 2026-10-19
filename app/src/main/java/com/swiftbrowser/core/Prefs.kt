package com.swiftbrowser.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.swiftbrowser.adblock.AdLevel

/** App settings. Keys match res/xml/settings.xml so the settings screen edits the same values. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    var darkMode: Boolean
        get() = sp.getBoolean(DARK_MODE, false)
        set(v) = sp.edit { putBoolean(DARK_MODE, v) }

    var adLevel: AdLevel
        get() = AdLevel.fromKey(sp.getString(AD_LEVEL, AdLevel.HIGH.key))
        set(v) = sp.edit { putString(AD_LEVEL, v.key) }

    val autoLandscape: Boolean get() = sp.getBoolean(AUTO_LANDSCAPE, true)
    val videoGestures: Boolean get() = sp.getBoolean(VIDEO_GESTURES, true)
    val hlsToMp4: Boolean get() = sp.getBoolean(HLS_TO_MP4, true)
    val confirmDownloads: Boolean get() = sp.getBoolean(CONFIRM_DOWNLOADS, true)

    val maxLiveTabs: Int
        get() = sp.getString(MAX_LIVE_TABS, "3")?.toIntOrNull()?.coerceIn(1, 6) ?: 3

    var currentProfileId: Long
        get() = sp.getLong(CURRENT_PROFILE, 1L)
        set(v) = sp.edit { putLong(CURRENT_PROFILE, v) }

    companion object {
        const val DARK_MODE = "dark_mode"
        const val AD_LEVEL = "ad_level"
        const val AUTO_LANDSCAPE = "auto_landscape"
        const val VIDEO_GESTURES = "video_gestures"
        const val HLS_TO_MP4 = "hls_to_mp4"
        const val CONFIRM_DOWNLOADS = "confirm_downloads"
        const val MAX_LIVE_TABS = "max_live_tabs"
        const val CURRENT_PROFILE = "current_profile"
    }
}
