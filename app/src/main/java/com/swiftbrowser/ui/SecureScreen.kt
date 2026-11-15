package com.swiftbrowser.ui

import android.app.Activity
import android.view.WindowManager

/**
 * Keeps a screen out of screenshots and Android's recent-apps picture while it shows something
 * private (3.0): the Private folder and saved sign-ins (3.0.11: no longer incognito pages, which was a
 * setting). Android 7 also saves the recent-apps picture to disk, so this keeps it off the phone's
 * storage too.
 */
object SecureScreen {
    fun set(activity: Activity, on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
