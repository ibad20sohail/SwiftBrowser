package com.swiftbrowser.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.files.PrivateFolder

/**
 * The questions around Private (2.9): what it is (once), Swift's own PIN on a phone without a
 * screen lock (choose it, enter it, change it) and what to do when it is forgotten. Android's own
 * screen-lock question is asked by the screen that opens Private (it needs that screen's answer).
 *
 * 3.0: a PIN, once chosen, stays Private's lock even after the phone gets a screen lock. Wrong PINs
 * wait longer and longer. "Forgot PIN" starts a day's wait, after which a new PIN can be chosen (or
 * the screen lock used, if the phone has one); the right PIN typed meanwhile shows that a reset was
 * asked for, and when, and offers to call it off.
 */
object PrivateLock {
    /** The phone has a screen lock (a PIN, pattern, password or fingerprint). */
    fun hasScreenLock(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardSecure == true

    /** Android's screen-lock question for Private; null when the phone has no screen lock. */
    @Suppress("DEPRECATION")
    fun credentialIntent(ctx: Context): Intent? = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.createConfirmDeviceCredentialIntent("Private", "Confirm it's you to open your Private files in Swift")
    } catch (_: Exception) {
        null
    }

    const val UNINSTALL_TEXT = "Uninstalling Swift or clearing its data deletes the files in Private. Keep a copy of anything important somewhere else too."

    private const val PIN_NOTE = "Your phone has no screen lock, so Private opens with a PIN of its own: 4 to 8 digits."
    private const val NEW_PIN_NOTE = "Private opens with this PIN from now on: 4 to 8 digits."

    /** The first time only: what Private is, then [then]. */
    fun introThen(a: Activity, then: () -> Unit) {
        val sp = SwiftApp.prefs.sp
        if (sp.getBoolean(PrivateFolder.PREF_INTRO, false)) {
            then()
            return
        }
        val lock = when {
            PrivateFolder.hasPin() -> "It opens with your PIN."
            hasScreenLock(a) -> "Opening it asks for your phone's screen lock."
            else -> "Your phone has no screen lock, so you'll choose a PIN for it."
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("Private")
            .setMessage("Files you move to Private are kept inside Swift: other apps, the Gallery and computers can't see them. $lock\n\n$UNINSTALL_TEXT")
            .setPositiveButton("Continue") { _, _ ->
                sp.edit().putBoolean(PrivateFolder.PREF_INTRO, true).apply()
                then()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    private fun pinInput(a: Activity, hint: String): EditText = EditText(a).apply {
        this.hint = hint
        setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(8))
        gravity = Gravity.CENTER
        letterSpacing = 0.3f
        // The filled look of every field in a dialog, the digits as large as ever.
        Ui.field(this)
        Ui.size(this, Ui.Type.HEADLINE, keepFont = true)
    }

    private fun box(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), 0)
    }

    private fun note(a: Activity, text: String): TextView = Ui.text(a, Ui.Type.BODY_SECONDARY, text).apply {
        setPadding(0, 0, 0, Ui.dp(a, 8))
    }

    /** 3.0: "30 seconds", "5 minutes", "2 hours", "1 day" (rounded up). */
    fun duration(ms: Long): String {
        val s = (ms + 999) / 1000
        return when {
            s < 60 -> if (s == 1L) "1 second" else "$s seconds"
            s < 3600 -> ((s + 59) / 60).let { if (it == 1L) "1 minute" else "$it minutes" }
            s < 48 * 3600 -> ((s + 3599) / 3600).let { if (it == 1L) "1 hour" else "$it hours" }
            else -> ((s + 86399) / 86400).let { if (it == 1L) "1 day" else "$it days" }
        }
    }

    /** "2 Nov, 14:05". */
    private fun moment(a: Activity, t: Long): String =
        DateUtils.formatDateTime(a, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)

    /** Choose a PIN (4 to 8 digits, typed twice); [onDone] once it is kept. */
    fun setUpPin(a: Activity, title: String = "Choose a PIN for Private", note: String = PIN_NOTE, onDone: () -> Unit) {
        val box = box(a)
        box.addView(note(a, note))
        val first = pinInput(a, "PIN")
        val second = pinInput(a, "The same PIN again")
        box.addView(first)
        box.addView(second, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(a, 8) })
        val error = Ui.text(a, Ui.Type.CAPTION, "", Ui.danger(a))
        box.addView(error)
        box.addView(Ui.text(a, Ui.Type.CAPTION, UNINSTALL_TEXT).apply { setPadding(0, Ui.dp(a, 10), 0, 0) })
        Palette.paint(box)
        val d = MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val pin = first.text.toString()
            when {
                !PrivateFolder.validPin(pin) -> Motion.swapText(error, "A PIN is 4 to 8 digits.")
                second.text.toString() != pin -> {
                    Motion.swapText(error, "The two PINs aren't the same.")
                    second.setText("")
                    second.requestFocus()
                }
                else -> {
                    d.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
                    PrivateFolder.setPin(pin) {
                        d.dismiss()
                        onDone()
                    }
                }
            }
        }
        first.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** What the PIN screen says about a reset asked for (3.0), or null when none was. */
    private fun resetLine(a: Activity): String? {
        val asked = PrivateFolder.resetAskedAt() ?: return null
        val left = PrivateFolder.resetLeft() ?: return null
        return "A PIN reset was asked for on ${moment(a, asked)}. " +
            if (left > 0) "It can be done in ${duration(left)}." else "It can be done now: tap Forgot PIN."
    }

    /**
     * Enter the PIN; [onOk] when it is right (3.0: with true when a new PIN was chosen through a reset
     * instead). "Forgot PIN" starts or finishes a reset; [onScreenLock] (when the screen that asks can
     * show Android's screen-lock question) offers the screen lock as the way to reset it.
     */
    fun askPin(a: Activity, title: String = "Private", onScreenLock: (() -> Unit)? = null, onOk: (newPin: Boolean) -> Unit) {
        val box = box(a)
        box.addView(note(a, "Enter your PIN for Private."))
        resetLine(a)?.let { box.addView(Ui.text(a, Ui.Type.CAPTION, it).apply { setPadding(0, 0, 0, Ui.dp(a, 8)) }) }
        val input = pinInput(a, "PIN")
        box.addView(input)
        val error = Ui.text(a, Ui.Type.CAPTION, "", Ui.danger(a))
        box.addView(error)
        PrivateFolder.waitLeft().takeIf { it > 0 }?.let { error.text = "Too many wrong PINs. Try again in ${duration(it)}." }
        Palette.paint(box)
        val d = MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Open", null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Forgot PIN", null)
            .showImmersive()
        val ok = d.getButton(DialogInterface.BUTTON_POSITIVE)
        val tryIt = {
            val wait = PrivateFolder.waitLeft()
            val pin = input.text.toString()
            when {
                wait > 0 -> Motion.swapText(error, "Too many wrong PINs. Try again in ${duration(wait)}.")
                pin.isEmpty() -> {}
                else -> {
                    ok?.isEnabled = false
                    PrivateFolder.checkPin(pin) { right ->
                        ok?.isEnabled = true
                        if (right) {
                            d.dismiss()
                            // 3.0: a reset asked for meanwhile is shown, with a way to call it off.
                            resetAskedThen(a) { onOk(false) }
                        } else {
                            input.setText("")
                            val next = PrivateFolder.waitLeft()
                            Motion.swapText(error, if (next > 0) "That PIN isn't right. Too many wrong PINs: try again in ${duration(next)}." else "That PIN isn't right.")
                            Motion.bump(input, 1.04f)
                        }
                    }
                }
            }
        }
        ok?.setOnClickListener { tryIt() }
        input.setOnEditorActionListener { _, _, _ ->
            tryIt()
            true
        }
        d.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            d.dismiss()
            forgotPin(a, onScreenLock) { onOk(true) }
        }
        input.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** 3.0: after the right PIN, a reset asked for meanwhile is told (with when), and can be called off; then [then]. */
    private fun resetAskedThen(a: Activity, then: () -> Unit) {
        val asked = PrivateFolder.resetAskedAt()
        if (asked == null) {
            then()
            return
        }
        var answered = false
        val d = MaterialAlertDialogBuilder(a)
            .setTitle("A PIN reset was asked for")
            .setMessage(
                "On ${moment(a, asked)}, someone tapped Forgot PIN, so a new PIN could be chosen after a day. " +
                    "If that wasn't you, or you remember the PIN now, call the reset off.",
            )
            .setPositiveButton("Call it off") { _, _ ->
                answered = true
                PrivateFolder.cancelReset()
                Messages.show(a, "The PIN reset is called off")
                then()
            }
            .setNegativeButton("Keep it") { _, _ ->
                answered = true
                then()
            }
            .showImmersive()
        d.setOnDismissListener { if (!answered) then() }
    }

    /**
     * A forgotten PIN can't be read back (only its hash is kept). 3.0: it can be reset after a day's
     * wait, so someone else holding the phone can't do it quickly: "Forgot PIN" starts the wait, and
     * once it is over a new PIN can be chosen ([onNewPin] then), or the phone's screen lock used
     * ([onScreenLock], when there is one and the screen can ask for it).
     */
    fun forgotPin(a: Activity, onScreenLock: (() -> Unit)? = null, onNewPin: () -> Unit) {
        val left = PrivateFolder.resetLeft()
        val screenLock = onScreenLock?.takeIf { hasScreenLock(a) }
        when {
            left == null -> MaterialAlertDialogBuilder(a)
                .setTitle("Forgot your PIN?")
                .setMessage(
                    "Swift can't tell you the PIN: it keeps only a scrambled form of it.\n\n" +
                        "You can reset it after a wait of one day, so that nobody else holding your phone can do it quickly. " +
                        "Then you choose a new PIN" + (if (screenLock != null) ", or use your phone's screen lock instead" else "") +
                        ". Your files stay in Private, and the PIN still opens it meanwhile.",
                )
                .setPositiveButton("Start the wait") { _, _ ->
                    PrivateFolder.askReset()
                    Messages.show(a, "You can reset the PIN in ${duration(PrivateFolder.resetLeft() ?: 0L)}: open Private and tap Forgot PIN then")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showImmersive()
            left > 0 -> Dialogs.info(
                a, "The PIN reset is waiting",
                "A reset was asked for on ${moment(a, PrivateFolder.resetAskedAt() ?: System.currentTimeMillis())}. " +
                    "You can choose a new PIN in ${duration(left)}: open Private then and tap Forgot PIN.",
            )
            else -> {
                val b = MaterialAlertDialogBuilder(a)
                    .setTitle("Reset the PIN")
                    .setMessage("The day's wait is over. Choose a new PIN for Private" + (if (screenLock != null) ", or open it with your phone's screen lock from now on." else "."))
                    .setPositiveButton("Choose a new PIN") { _, _ ->
                        setUpPin(a, "Choose a new PIN", NEW_PIN_NOTE) {
                            Messages.show(a, "Private opens with your new PIN now")
                            onNewPin()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                if (screenLock != null) b.setNeutralButton("Use the screen lock") { _, _ -> screenLock() }
                b.showImmersive()
            }
        }
    }

    /** From Settings: choose a new PIN (the old one first), or say that the screen lock opens Private. */
    fun changePin(a: Activity) {
        when {
            // 3.0: a PIN keeps Private, screen lock or not.
            PrivateFolder.hasPin() -> askPin(a, "Change the PIN") { newPin ->
                if (!newPin) setUpPin(a, "Choose a new PIN", NEW_PIN_NOTE) { Messages.show(a, "Private's PIN is changed") }
            }
            hasScreenLock(a) -> Dialogs.info(
                a, "Private uses your screen lock",
                "Your phone had a screen lock when Private was set up, so Private opens with it. Set or change it in Android's settings, under Security.",
            )
            else -> setUpPin(a) { Messages.show(a, "Private opens with this PIN now") }
        }
    }
}
