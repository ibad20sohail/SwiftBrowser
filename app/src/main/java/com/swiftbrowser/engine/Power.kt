package com.swiftbrowser.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.TabManager

/**
 * Battery saver (Settings → Speed & memory, 2.3): below 20% battery (or 15 or 30, as chosen), or
 * while Android's own battery saver is on, Swift uses Lite mode, keeps only the open tab loaded and
 * loads pages without pictures on mobile data. Plugging the phone in brings everything back. The
 * settings themselves don't change.
 */
object Power {
    /** Saving battery right now. */
    var saving = false
        private set

    /** The user was told it started (so they hear when it ends); see MainActivity.announceBattery. */
    var noticeShown = false

    /** The battery's level (percent; -1 not known yet) and whether it's charging. */
    private var percent = -1
    private var charging = false
    private var androidSaver = false

    /** Told when [saving] changes (MainActivity says so on screen). */
    var listener: ((Boolean) -> Unit)? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> read(i)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> androidSaver = pm.isPowerSaveMode
                }
                update()
            }
        }
        try {
            androidSaver = pm.isPowerSaveMode
            app.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { read(it) }
        } catch (e: Exception) {
            L.w("Couldn't watch the battery", e)
        }
        update()
    }

    private fun read(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** The battery or the settings changed. */
    fun update() {
        val prefs = SwiftApp.prefs
        val low = !charging && percent >= 0 && percent < prefs.batterySaverLevel
        val now = prefs.batterySaver && (low || androidSaver)
        if (now == saving) return
        saving = now
        L.d("Battery saver ${if (now) "on" else "off"}")
        Helper.sendLook()
        DataSaver.apply()
        if (now) TabManager.trimLiveSessions()
        listener?.invoke(now)
    }
}
