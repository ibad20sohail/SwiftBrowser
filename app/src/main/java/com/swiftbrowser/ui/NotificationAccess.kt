package com.swiftbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.swiftbrowser.core.L

/**
 * 3.0.6: Android 13 and later show an app's notifications only once the app has asked and been
 * allowed; Swift never asked, so on newer phones downloads gave no progress and no "Downloaded", and
 * nothing said why. Swift asks once, when it first has something to say: a download starting, or
 * the Downloads screen opening. After an answer, Android's settings are the place to change it.
 * Android 7 to 12 show notifications without asking: nothing happens there.
 */
object NotificationAccess {
    private const val ASKED = "notifications_asked"
    private const val REQUEST = 4713

    fun askOnce(a: Activity) {
        if (Build.VERSION.SDK_INT < 33 || a.isFinishing) return
        if (NotificationManagerCompat.from(a).areNotificationsEnabled()) return
        val sp = a.getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (sp.getBoolean(ASKED, false)) return
        sp.edit { putBoolean(ASKED, true) }
        L.d("Asking for notifications (Android 13+)")
        ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }
}
