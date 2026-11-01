package com.swiftbrowser.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.swiftbrowser.core.L
import com.swiftbrowser.files.Transfers
import com.swiftbrowser.files.Volumes
import java.io.File

/**
 * Asking Android for the right to change files (2.7, its own class since 2.9 so the cleaner asks
 * the same way as Files): the storage permission on Android 9 and older, and once for good the SD
 * card's document access. Made while the screen is made (it registers for Android's answers).
 */
class ChangeAccess(private val activity: ComponentActivity, private val say: (String) -> Unit) {
    private var afterWrite: (() -> Unit)? = null
    private val askWrite = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val run = afterWrite
        afterWrite = null
        if (granted) run?.invoke() else say("Swift needs access to your files to change them")
    }

    private var cardAsked: Volumes.Volume? = null
    private var afterCard: (() -> Unit)? = null
    private var cardTriedPicker = false
    private val askCard: ActivityResultLauncher<Intent> = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val v = cardAsked ?: return@registerForActivityResult
        if (r.resultCode == Activity.RESULT_OK && Volumes.onAccessResult(activity, v, r.data)) {
            val run = afterCard
            afterCard = null
            run?.invoke()
        } else if (r.resultCode == Activity.RESULT_OK) {
            afterCard = null
            Dialogs.info(activity, "Choose the SD card itself", "Swift needs the whole SD card, not a folder in it. Try again and pick the card at the top.")
        } else if (!cardTriedPicker) {
            // The short question was refused or isn't there: Android's folder picker instead.
            cardTriedPicker = true
            try {
                askCard.launch(Volumes.pickerIntent(v))
            } catch (_: Exception) {
                afterCard = null
                say(Transfers.CARD_TEXT)
            }
        } else {
            afterCard = null
            say("Swift can only read the SD card until you allow it to change files there")
        }
    }

    /** Runs [action] once Swift may change files (asking Android first if needed). */
    fun withWriteAccess(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted || Build.VERSION.SDK_INT > 28) {
            action()
        } else {
            afterWrite = action
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Runs [action] once Swift may change [files] (on an SD card, Android is asked once, for good). */
    fun withChanges(files: Collection<File>, action: () -> Unit) = withWriteAccess {
        val v = Volumes.cardWithoutAccess(activity, files)
        if (v == null) {
            action()
            return@withWriteAccess
        }
        Dialogs.confirm(
            activity, "Allow changes on the SD card",
            "Android lets Swift change files on the SD card only once you allow it. On the next screen, tap Allow (or choose the SD card and tap Select).",
            "Continue",
        ) {
            cardAsked = v
            afterCard = action
            cardTriedPicker = false
            try {
                askCard.launch(Volumes.accessIntent(activity, v))
            } catch (e: Exception) {
                L.w("No way to ask for the card", e)
                afterCard = null
                say(Transfers.CARD_TEXT)
            }
        }
    }
}
