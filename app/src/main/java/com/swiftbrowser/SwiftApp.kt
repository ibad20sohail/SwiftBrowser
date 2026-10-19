package com.swiftbrowser

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.core.Crash
import com.swiftbrowser.core.Db
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Prefs
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.tabs.TabManager
import java.io.File

class SwiftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs page content in extra processes of this app; only the main one sets up the browser.
        if (!isMainProcess()) return
        instance = this
        Crash.install(this)
        prefs = Prefs(this)
        db = Db(this)
        applyNightMode(prefs.darkMode)
        Profiles.init(db, prefs)
        AdBlock.init(prefs, db)
        Downloads.init(this, db, prefs)
        TabManager.init(db, prefs)
        Engine.init(this, prefs)
        L.d("Swift ${BuildConfig.VERSION_NAME} started")
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

        fun applyNightMode(dark: Boolean) {
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
            )
        }
    }
}
