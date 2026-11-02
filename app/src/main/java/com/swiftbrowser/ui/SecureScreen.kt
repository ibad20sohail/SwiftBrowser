package com.swiftbrowser.ui

import android.app.Activity
import android.view.WindowManager
import com.swiftbrowser.SwiftApp

/**
 * Keeps a screen out of screenshots and Android's recent-apps picture while it shows something
 * private (3.0): the Private folder, saved sign-ins, and incognito pages unless allowed in Settings.
 * Android 7 also saves the recent-apps picture to disk, so this keeps it off the phone's storage too.
 */
object SecureScreen {
    fun set(activity: Activity, on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** For a screen showing an incognito page or file: secure unless screenshots are allowed there. */
    fun forIncognito(activity: Activity, incognito: Boolean) =
        set(activity, incognito && !SwiftApp.prefs.allowIncognitoScreenshots)
}
