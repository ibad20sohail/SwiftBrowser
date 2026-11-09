package com.swiftbrowser.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 3.0.6: opens one of Swift's screens from a quick settings tile ("New tab", "Swift VPN") and closes
 * the shade. Android 14 and later refuse the old way (an Intent) from apps made for them, with an
 * error instead of the screen; there it goes as a PendingIntent. Android 7 to 13 keep the old way.
 */
fun TileService.openAndCollapse(intent: Intent, requestCode: Int) {
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(
            PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
    }
}
