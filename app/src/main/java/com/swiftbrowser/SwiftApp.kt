package com.swiftbrowser

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.Crash
import com.swiftbrowser.core.Db
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
        Crash.install(this)
        prefs = Prefs(this)
        migratePrefs()
        db = Db(this)
        applyNightMode(prefs.darkMode)
        Profiles.init(db, prefs)
        AdBlock.init(prefs, db)
        Downloads.init(this, db, prefs)
        Favicons.init(this)
        Thumbnails.init(this)
        TabManager.init(db, prefs)
        Thumbnails.cleanup(TabManager.tabs.map { it.id }.toSet())
        prefs.autoSoftwareRendering = GpuInfo.prefersSoftwareRendering(GpuInfo.renderer(this))
        Engine.init(this, prefs)
        L.d("Swift ${BuildConfig.VERSION_NAME} started")
    }

    /** Settings whose defaults changed between versions. */
    private fun migratePrefs() {
        val sp = prefs.sp
        val version = sp.getInt("prefs_version", 1)
        if (version >= 3) return
        sp.edit {
            // 1.0 kept 3 pages in memory; 2 suits a 2 GB phone better.
            if (version < 2) remove(Prefs.MAX_LIVE_TABS)
            // From 1.2 the drawing method is chosen for the phone's graphics chip unless changed.
            remove(Prefs.SOFTWARE_RENDERING)
            // From 1.2 full-screen videos neither force landscape nor add swipe gestures by default.
            remove(Prefs.AUTO_LANDSCAPE)
            remove(Prefs.VIDEO_GESTURES)
            putInt("prefs_version", 3)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (instance == null) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            L.d("Low memory ($level): suspending background tabs")
            TabManager.suspendBackgroundTabs()
        }
    }

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

        /** Set when the theme changes; the browser screen reloads the page once it is recreated. */
        var reloadAfterThemeChange = false

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
                    activity.finishAffinity()
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }
}
