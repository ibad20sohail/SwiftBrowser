package com.swiftbrowser.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Backup
import com.swiftbrowser.core.BackupLock
import com.swiftbrowser.core.GoogleBackup
import com.swiftbrowser.core.L
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * 3.0.8: the backup password, the backup to the user's Google account (GoogleBackup), restoring it
 * on a new phone, and the passwords and sign-ins in backup files (BackupLock). Settings → Storage &
 * data → Backup, and the offer at start when Android put a backup back.
 */
object BackupUi {
    private val io = com.swiftbrowser.core.Background.single("backup-ui")

    private const val MIN_PASSWORD = 6

    private fun day(time: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

    private fun ago(time: Long): String =
        if (System.currentTimeMillis() - time < 60_000) "just now"
        else android.text.format.DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()

    // ------------------------------------------------------------------ Settings' rows

    fun googleSummary(a: Activity): String {
        if (!GoogleBackup.on) return "Off: nothing goes to Google"
        if (!BackupLock.isSet) return "On, but it needs your backup password again"
        val written = GoogleBackup.written
        val copy = if (written > 0) "Swift's copy updated ${ago(written)}" else "Swift makes its copy when you leave Swift"
        return when (GoogleBackup.phoneBackupOn(a)) {
            false -> "On · $copy, but your phone's own backup is off, so nothing reaches Google"
            else -> "On · $copy. Your phone sends it to Google about once a day while charging on Wi-Fi."
        }
    }

    fun passwordSummary(): String = when {
        !BackupLock.isSet -> "Not set. It locks the Google backup, and passwords and sign-ins in backup files."
        BackupLock.hint.isNotBlank() -> "Set · hint: ${BackupLock.hint}"
        else -> "Set"
    }

    fun filesSummary(): String =
        "Added to backup files, locked with your backup password"

    fun foundSummary(a: Activity): String {
        val f = GoogleBackup.found(a, withData = false)
            ?: return "None on this phone. Android brings it back when you install Swift again or on a new phone."
        return if (f.fromHere) "Swift's own copy from ${day(f.created)}" else "From ${f.device}, ${day(f.created)}"
    }

    /** The Google backup's row. */
    fun google(a: Activity, refresh: () -> Unit) {
        if (GoogleBackup.on) {
            val labels = listOf("Make Swift's copy now", "Change the backup password", "Turn off")
            MaterialAlertDialogBuilder(a)
                .setTitle("Back up to your Google account")
                .setItems(labels.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> withPassword(a, refresh) { copyNow(a, refresh) }
                        1 -> setPassword(a, "Change the backup password") {
                            GoogleBackup.writeSoon(a) { refresh() }
                            refresh()
                        }
                        else -> Dialogs.confirm(
                            a, "Turn off the Google backup?",
                            "Swift's copy is deleted now, and the one in your Google account goes at your phone's next backup. Your backup password stays for backup files.",
                            "Turn off", danger = true,
                        ) {
                            GoogleBackup.turnOff(a)
                            refresh()
                        }
                    }
                }
                .setNegativeButton("Close", null)
                .showImmersive()
            return
        }
        // A backup Android put back that wasn't restored: turning on would replace it.
        io.execute {
            val found = GoogleBackup.found(a, withData = false)?.takeIf { !it.fromHere }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (found != null) {
                    MaterialAlertDialogBuilder(a)
                        .setTitle("Restore your backup first?")
                        .setMessage("A Google backup from ${found.device}, ${day(found.created)}, is on this phone and hasn't been restored. Turning the backup on replaces it with this phone's.")
                        .setPositiveButton("Restore it") { _, _ -> restoreGoogle(a, found) }
                        .setNeutralButton("Replace it") { _, _ -> explain(a, refresh) }
                        .setNegativeButton("Cancel", null)
                        .showImmersive()
                } else {
                    explain(a, refresh)
                }
            }
        }
    }

    private fun explain(a: Activity, refresh: () -> Unit) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Back up to your Google account")
            .setMessage(
                "Swift keeps a copy of your bookmarks, history, open tabs, settings, saved passwords and sign-ins to sites, and your phone " +
                    "puts it in your Google account (about once a day, while charging on Wi-Fi). On a new phone, or after installing Swift " +
                    "again, Swift offers to bring it all back.\n\nIt is locked with your backup password: nobody can open it without it, " +
                    "not even Google. If you forget the password, the backup can't be restored.\n\nThe VPN and the Private folder are never in it.",
            )
            .setPositiveButton("Turn on") { _, _ -> withPassword(a, refresh) { turnOn(a, refresh) } }
            .setNegativeButton("Cancel", null)
            .showImmersive()
    }

    /** [then] once a backup password is set (asked for first if it isn't, or if the keystore lost its key). */
    private fun withPassword(a: Activity, refresh: () -> Unit, then: () -> Unit) {
        io.execute {
            val ready = BackupLock.isSet && BackupLock.key() != null
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (ready) then() else setPassword(a, "Choose a backup password") {
                    refresh()
                    then()
                }
            }
        }
    }

    private fun turnOn(a: Activity, refresh: () -> Unit) {
        GoogleBackup.on = true
        refresh()
        copyNow(a, refresh)
        // Once (3.0.8): the phone's own backup must be on, or nothing reaches Google.
        if (GoogleBackup.phoneBackupOn(a) == false && !GoogleBackup.reminded) {
            GoogleBackup.reminded = true
            MaterialAlertDialogBuilder(a)
                .setTitle("Your phone's backup is off")
                .setMessage(
                    "Swift's copy is ready, but your phone doesn't back up to Google, so it stays on the phone. Turn on " +
                        "\"Back up my data\" (Settings → Backup & reset, or Settings → Google → Backup on newer phones).",
                )
                .setPositiveButton("Open phone settings") { _, _ -> openPhoneBackup(a) }
                .setNegativeButton("Later", null)
                .showImmersive()
        }
    }

    private fun copyNow(a: Activity, refresh: () -> Unit) {
        GoogleBackup.writeSoon(a) { ok ->
            refresh()
            if (!a.isFinishing) {
                Messages.show(
                    a,
                    if (ok) "Swift's copy is ready. Your phone sends it to Google about once a day while charging on Wi-Fi." else "Swift's copy couldn't be made",
                    long = ok,
                )
            }
        }
    }

    private fun openPhoneBackup(a: Activity) {
        val intent = Intent(if (android.os.Build.VERSION.SDK_INT < 28) android.provider.Settings.ACTION_PRIVACY_SETTINGS else android.provider.Settings.ACTION_SETTINGS)
        try {
            a.startActivity(intent)
        } catch (_: Exception) {
            Messages.show(a, "Open your phone's Settings → Backup")
        }
    }

    /** The backup password's row. */
    fun password(a: Activity, refresh: () -> Unit) {
        if (!BackupLock.isSet) {
            setPassword(a, "Choose a backup password") { refresh() }
            return
        }
        val inUse = GoogleBackup.on || SwiftApp.prefs.sp.getBoolean(Backup.FILES_LOCKED, false)
        MaterialAlertDialogBuilder(a)
            .setTitle("Backup password")
            .setItems(arrayOf("Change it", "Remove it")) { _, which ->
                if (which == 0) {
                    setPassword(a, "Change the backup password") {
                        if (GoogleBackup.on) GoogleBackup.writeSoon(a) { refresh() }
                        refresh()
                    }
                } else if (inUse) {
                    Dialogs.info(a, "Backup password", "The Google backup or backup files still use it. Turn those off first.")
                } else {
                    Dialogs.confirm(a, "Remove the backup password?", "Backups locked with it can still be opened with it on another phone.", "Remove", danger = true) {
                        BackupLock.clear()
                        refresh()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    /** The switch for passwords and sign-ins in backup files. */
    fun filesLocked(a: Activity, on: Boolean, refresh: () -> Unit) {
        val sp = SwiftApp.prefs.sp
        if (!on) {
            sp.edit().putBoolean(Backup.FILES_LOCKED, false).apply()
            refresh()
            return
        }
        withPassword(a, refresh) {
            sp.edit().putBoolean(Backup.FILES_LOCKED, true).apply()
            refresh()
            Messages.show(a, "Backups you make from now on hold your passwords and sign-ins, locked with your backup password", long = true)
        }
    }

    // ------------------------------------------------------------------ passwords

    private fun passwordBox(a: Activity, hint: String): EditText = EditText(a).apply {
        this.hint = hint
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        Ui.field(this)
    }

    private fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), 0)
    }

    /** A field under the one before, with a little room between them. */
    private fun below(a: Activity) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = Ui.dp(a, 8)
    }

    private fun showToggle(a: Activity, boxes: List<EditText>) = CheckBox(a).apply {
        text = "Show the password"
        setOnCheckedChangeListener { _, on ->
            for (b in boxes) {
                b.inputType = InputType.TYPE_CLASS_TEXT or if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                b.setSelection(b.text.length)
            }
        }
    }

    /** Asks for a new backup password (twice) and a hint; [done] once its key is made and kept. */
    fun setPassword(a: Activity, title: String, done: () -> Unit) {
        val box = column(a)
        val first = passwordBox(a, "Backup password")
        val again = passwordBox(a, "The same again")
        val hint = EditText(a).apply {
            this.hint = "A hint only you understand (optional)"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(BackupLock.MAX_HINT))
            Ui.field(this)
        }
        val error = Ui.text(a, Ui.Type.CAPTION, "").apply { setTextColor(Ui.danger(a)) }
        box.addView(Ui.text(a, Ui.Type.BODY_SECONDARY, "At least $MIN_PASSWORD characters. Swift never shows it again, and nobody can open your backups without it.").apply {
            setPadding(0, 0, 0, Ui.dp(a, 8))
        })
        box.addView(first)
        box.addView(again, below(a))
        box.addView(hint, below(a))
        box.addView(showToggle(a, listOf(first, again)))
        box.addView(error)
        Palette.paint(box)
        val d = MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val pw = first.text.toString()
            val h = hint.text.toString().trim()
            error.text = when {
                pw.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters."
                pw != again.text.toString() -> "The two don't match."
                h.isNotEmpty() && h.contains(pw, ignoreCase = true) -> "The hint can't hold the password."
                else -> ""
            }
            if (error.text.isNotEmpty()) return@setOnClickListener
            d.dismiss()
            val waiting = Messages.sticky(a, "Locking with your backup password…")
            io.execute {
                BackupLock.set(pw, h)
                a.runOnUiThread {
                    waiting?.dismiss()
                    if (!a.isFinishing) Messages.show(a, "Backup password saved")
                    done()
                }
            }
        }
        first.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** Asks for the backup password of a backup ([hint] its hint); [wrong]: the last one didn't open it. */
    private fun askPassword(a: Activity, title: String, message: String, hint: String, wrong: Boolean, skip: String?, onCancel: () -> Unit, onOk: (String) -> Unit) {
        val box = column(a)
        val input = passwordBox(a, "Backup password")
        val text = buildString {
            append(if (wrong) "That password doesn't open it. Check it and try again." else message)
            if (hint.isNotBlank()) append("\n\nHint: $hint")
        }
        box.addView(Ui.text(a, Ui.Type.BODY_SECONDARY, text).apply { setPadding(0, 0, 0, Ui.dp(a, 8)) })
        box.addView(input)
        box.addView(showToggle(a, listOf(input)))
        Palette.paint(box)
        var answered = false
        val b = MaterialAlertDialogBuilder(a)
            .setTitle(if (wrong) "Wrong password" else title)
            .setView(box)
            .setPositiveButton("Open") { _, _ ->
                answered = true
                val pw = input.text.toString()
                if (pw.isEmpty()) onCancel() else onOk(pw)
            }
            .setNegativeButton(skip ?: "Cancel") { _, _ ->
                answered = true
                onCancel()
            }
        val d = b.showImmersive()
        d.setOnDismissListener { if (!answered) onCancel() }
        input.requestFocus()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ------------------------------------------------------------------ restoring

    /** At start: Android put back a backup from another install ([f]); offered once. */
    fun offerOnStart(a: MainActivity, f: GoogleBackup.Found) {
        GoogleBackup.offered(f)
        L.d("Google backup: offering the copy from ${day(f.created)} at start")
        MaterialAlertDialogBuilder(a)
            .setTitle("Restore from your Google backup?")
            .setMessage(
                "Your phone brought back Swift's backup from ${f.device}, ${day(f.created)}: bookmarks, history, open tabs, settings, " +
                    "saved passwords and sign-ins to sites. You need your backup password.\n\nIt stays in Settings → Storage & data if you want it later.",
            )
            .setPositiveButton("Restore") { _, _ -> restoreGoogle(a, f) }
            .setNegativeButton("Not now", null)
            .showImmersive()
    }

    /** The row "Restore from your Google backup". */
    fun restoreFromSettings(a: Activity) {
        io.execute {
            val f = GoogleBackup.found(a, withData = false)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (f == null) {
                    Dialogs.info(a, "Restore from your Google backup", "There's none on this phone. When you install Swift again, or on a new phone, your phone brings back the last one from your Google account.")
                } else {
                    restoreGoogle(a, f)
                }
            }
        }
    }

    /** Opens the Google backup with its password, restores it, keeps backing up with the same password, then restarts Swift. */
    fun restoreGoogle(a: Activity, header: GoogleBackup.Found, wrong: Boolean = false) {
        askPassword(
            a, "Restore from your Google backup", "Backup from ${header.device}, ${day(header.created)}. Type your backup password.",
            header.hint, wrong, skip = null, onCancel = {},
        ) { pw ->
            val waiting = Messages.sticky(a, "Opening your backup…")
            io.execute {
                val f = GoogleBackup.found(a) ?: header
                val opened = GoogleBackup.open(f, pw)
                a.runOnUiThread {
                    waiting?.dismiss()
                    if (a.isFinishing) return@runOnUiThread
                    if (opened == null) {
                        restoreGoogle(a, header, wrong = true)
                        return@runOnUiThread
                    }
                    val (root, key) = opened
                    if (Backup.isNewer(root)) {
                        Messages.show(a, "That backup was made by a newer Swift. Update Swift, then restore it.", long = true)
                        return@runOnUiThread
                    }
                    Backup.restoreThen(a, root, secrets = root) { restored ->
                        if (restored == null) {
                            if (!a.isFinishing) Messages.show(a, "That backup couldn't be restored. Nothing on your phone was changed.", long = true)
                            return@restoreThen
                        }
                        io.execute {
                            // The same password locks this phone's backups from now on.
                            BackupLock.remember(key, f.salt, f.rounds, f.hint)
                            GoogleBackup.on = true
                            GoogleBackup.offered(f)
                            // Back with their own things: not a newcomer to welcome.
                            SwiftApp.prefs.welcomeDone = true
                            a.runOnUiThread { restoredThenRestart(a, Backup.restoredText(restored) + "\n\nThe Google backup stays on, locked with the same password.") }
                        }
                    }
                }
            }
        }
    }

    private fun restoredThenRestart(a: Activity, text: String) {
        if (a.isFinishing || a.isDestroyed) {
            SwiftApp.restart(a)
            return
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("Backup restored")
            .setMessage("$text\n\nSwift restarts to finish.")
            .setPositiveButton("Restart Swift") { _, _ -> SwiftApp.restart(a) }
            .setCancelable(false)
            .showImmersive()
    }

    /**
     * A backup file's locked part (passwords and sign-ins): opened with this phone's key when it was
     * locked with it, else with the password asked for; [then] gets what it holds, or null (skipped).
     */
    fun openLocked(a: Activity, box: JSONObject, then: (JSONObject?) -> Unit, wrong: Boolean = false) {
        io.execute {
            val mine = !wrong && BackupLock.opensWithoutPassword(box)
            val opened = if (mine) BackupLock.openBox(box, null) else null
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                if (opened != null) {
                    then(opened)
                    return@runOnUiThread
                }
                askPassword(
                    a, "Passwords and sign-ins", "This backup also holds saved passwords and sign-ins, locked with a backup password.",
                    box.optString("hint").take(BackupLock.MAX_HINT), wrong, skip = "Skip them", onCancel = { then(null) },
                ) { pw ->
                    val waiting = Messages.sticky(a, "Opening…")
                    io.execute {
                        val inside = BackupLock.openBox(box, pw)
                        a.runOnUiThread {
                            waiting?.dismiss()
                            if (inside == null) openLocked(a, box, then, wrong = true) else then(inside)
                        }
                    }
                }
            }
        }
    }
}
