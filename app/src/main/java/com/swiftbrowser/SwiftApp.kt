package com.swiftbrowser

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.Crash
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.engine.GpuInfo
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.tabs.Thumbnails
import com.swiftbrowser.ui.MainActivity
import java.io.File

class SwiftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs page content in extra processes of this app; only the main one sets up the browser.
        if (!isMainProcess()) return
        instance = this
        // Every line from here on also goes to the day's log file (2.7).
        L.init(this)
        Crash.install(this)
        Diagnostics.onAppStart(this)
        prefs = Prefs(this)
        migratePrefs()
        // 3.0.4: Swift's own font, before any screen is made (it stands in for the system's).
        com.swiftbrowser.ui.AppFont.install(this)
        db = Db(this)
        com.swiftbrowser.ui.Looks.start()
        Profiles.init(db, prefs)
        AdBlock.init(prefs, db)
        Downloads.init(this, db, prefs)
        Favicons.init(this)
        com.swiftbrowser.core.GoogleAccounts.init(this)
        Thumbnails.init(this)
        TabManager.init(db, prefs)
        Thumbnails.cleanup(TabManager.tabs.map { it.id }.toSet())
        // Phones with plenty of memory and a graphics chip that draws fast show sites as designed.
        prefs.autoSitesAsDesigned = !GpuInfo.isSlowChip(GpuInfo.renderer(this)) && totalMemory() >= 3_500_000_000L
        Engine.init(this, prefs)
        com.swiftbrowser.engine.DataSaver.init(this)
        com.swiftbrowser.engine.DataUsage.init()
        com.swiftbrowser.engine.Power.init(this)
        com.swiftbrowser.vpn.Vpn.onAppStart(this)
        com.swiftbrowser.vpn.VpnTileService.watch(this)
        // 2.9.2: torrents, run with the rules above (the VPN, mobile data, the battery).
        com.swiftbrowser.torrent.Torrents.init(this)
        com.swiftbrowser.core.Health.init(this)
        com.swiftbrowser.core.Storage.trimOwnCache(this)
        // 3.0.2: whether any of Swift's screens shows (the browser, Settings, Files, the player...).
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    TabManager.inSight(true)
                    // 3.0.6: downloads Android stopped after its 6 hours a day in the background go on
                    // (Android counts them again from the moment Swift is on screen).
                    Downloads.resumeAfterAndroidLimit()
                }
                // The screen comes in softly if the look has just changed.
                com.swiftbrowser.ui.Looks.fadeInAfterChange(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) {
                    TabManager.inSight(false)
                    // 3.0.7: Swift's own temporary files keep to their share of the cache size.
                    com.swiftbrowser.core.Storage.trimOwnCache(this@SwiftApp)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        L.d(
            "Swift ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started on ${android.os.Build.MANUFACTURER} " +
                "${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.SUPPORTED_ABIS.joinToString()}; " +
                "graphics chip ${GpuInfo.renderer(this).ifBlank { "unknown" }}; ${Diagnostics.memoryText()}; " +
                "tabs kept loaded ${prefs.maxLiveTabs}, ad blocking ${AdBlock.global.label}, Lite mode ${prefs.liteLevel.label}, " +
                "cache ${prefs.cacheLimitMb} MB, keep playing ${prefs.keepPlaying}, full screen ${prefs.fullScreen}, " +
                "data saver ${prefs.dataSaver}, VPN ${com.swiftbrowser.vpn.Vpn.wanted}",
        )
        if (Diagnostics.problem != null) com.swiftbrowser.core.Health.logEndOfPreviousRun()
    }

    /** Settings whose defaults changed between versions. */
    private fun migratePrefs() {
        val sp = prefs.sp
        val version = sp.getInt("prefs_version", 1)
        if (version >= 5) return
        sp.edit {
            if (version < 4) {
                // 1.0 kept 3 pages in memory; 2 suits a 2 GB phone better.
                if (version < 2) remove(Prefs.MAX_LIVE_TABS)
                if (version < 3) {
                    // From 1.2 full-screen videos neither force landscape nor add swipe gestures by default.
                    remove(Prefs.AUTO_LANDSCAPE)
                    remove(Prefs.VIDEO_GESTURES)
                }
                // 2.7: no more choice of drawing method (always the graphics chip), no search shortcuts.
                remove(Prefs.SOFTWARE_RENDERING)
                remove("search_shortcuts")
                remove("search_shortcuts_removed")
            }
            // 3.0.3: the shortcuts come first again; 3.0.2's first order goes (any other chosen order stays).
            if (sp.getString(Prefs.HOME_ORDER, null) == com.swiftbrowser.core.HomeOrder.OLD_DEFAULT) remove(Prefs.HOME_ORDER)
            putInt("prefs_version", 5)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (instance == null) return
        // Android's memory warnings (2.7): 5/10/15 while Swift runs (moderate, low, critical), 20 out of
        // sight, 40/60/80 in the background (the higher, the sooner Android closes it).
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) L.w("Android's memory warning $level; ${Diagnostics.memoryText()}")
        // Out of sight (2.6): the pictures kept in memory that load again from disk go (tabs,
        // documents, most site icons), so a 2 GB phone is less likely to close Swift. While
        // something plays or downloads in the background, Android only says so once that ends.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Thumbnails.trim()
            com.swiftbrowser.docs.DocThumbs.trim()
            com.swiftbrowser.files.FileThumbs.trim()
            Favicons.trim()
            // 3.0.4: and the home page's picture, read again when it shows.
            com.swiftbrowser.ui.HomeBackground.trim()
        }
        // Android is about to start closing apps: Swift could be next, so pages of other tabs go
        // (they reload when opened) and the log is saved. Merely leaving the app (UI_HIDDEN) isn't a
        // reason. 3.0.2: while Swift is on screen, only when the memory really is low, through the
        // usual limits (TabRules.onWarning): these warnings come with most of the memory free, and
        // every background tab used to reload after a visit to Settings.
        when (com.swiftbrowser.tabs.TabRules.onWarning(level, Diagnostics.memoryLow())) {
            com.swiftbrowser.tabs.TabRules.Warning.TRIM -> {
                TabManager.trimLiveSessions()
                L.flushNow()
            }
            com.swiftbrowser.tabs.TabRules.Warning.BACKGROUND_TABS -> {
                L.d("Memory getting low with Swift in the background: unloading background tabs")
                TabManager.suspendBackgroundTabs("Android needed memory while Swift was in the background")
                L.flushNow()
            }
            com.swiftbrowser.tabs.TabRules.Warning.NONE -> Unit
        }
    }

    private fun totalMemory(): Long = com.swiftbrowser.core.PhoneSize.totalMemory(this)

    private fun isMainProcess(): Boolean {
        val name = try {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        } catch (_: Exception) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName ?: packageName
        }
        return name == packageName
    }

    companion object {
        var instance: SwiftApp? = null
            private set
        lateinit var prefs: Prefs
            private set
        lateinit var db: Db
            private set

        /**
         * 3.0.5: the browser screen has been built in this run of the app. Saved state without it means
         * Android closed Swift in the background and it's starting again: a real start, not a rebuild.
         */
        var browserBuilt = false

        /** Set when the theme changes; the browser screen reloads the page once it is recreated. */
        var reloadAfterThemeChange = false

        /** Said once the screen is back in its new theme (2.6). */
        var messageAfterThemeChange: String? = null

        fun applyNightMode(dark: Boolean) {
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
            )
        }

        /** Restarts the app, for engine settings that only apply at start-up. */
        fun restart(activity: Activity) {
            TabManager.flushSaves()
            TabManager.io.execute {
                val intent = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(
                    activity, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val am = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 400, pending)
                activity.runOnUiThread {
                    Diagnostics.markCleanExit()
                    activity.finishAffinity()
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }
}
