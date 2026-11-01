package com.swiftbrowser.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
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

    /** The first time only: what Private is, then [then]. */
    fun introThen(a: Activity, then: () -> Unit) {
        val sp = SwiftApp.prefs.sp
        if (sp.getBoolean(PrivateFolder.PREF_INTRO, false)) {
            then()
            return
        }
        val lock = if (hasScreenLock(a)) "Opening it asks for your phone's screen lock." else "Your phone has no screen lock, so you'll choose a PIN for it."
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
        textSize = 22f
    }

    private fun box(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), 0)
    }

    private fun note(a: Activity, text: String): TextView = Ui.text(a, Ui.Type.BODY_SECONDARY, text).apply {
        setPadding(0, 0, 0, Ui.dp(a, 8))
    }

    /** Choose a PIN (4 to 8 digits, typed twice); [onDone] once it is kept. */
    fun setUpPin(a: Activity, title: String = "Choose a PIN for Private", onDone: () -> Unit) {
        val box = box(a)
        box.addView(note(a, "Your phone has no screen lock, so Private opens with a PIN of its own: 4 to 8 digits."))
        val first = pinInput(a, "PIN")
        val second = pinInput(a, "The same PIN again")
        box.addView(first)
        box.addView(second)
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
                !PrivateFolder.validPin(pin) -> error.text = "A PIN is 4 to 8 digits."
                second.text.toString() != pin -> {
                    error.text = "The two PINs aren't the same."
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

    /** Enter the PIN; [onOk] when it is right. "Forgot PIN" explains what can be done. */
    fun askPin(a: Activity, title: String = "Private", onOk: () -> Unit) {
        val box = box(a)
        box.addView(note(a, "Enter your PIN for Private."))
        val input = pinInput(a, "PIN")
        box.addView(input)
        val error = Ui.text(a, Ui.Type.CAPTION, "", Ui.danger(a))
        box.addView(error)
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
                wait > 0 -> error.text = "Too many wrong PINs. Try again in ${(wait + 999) / 1000} seconds."
                pin.isEmpty() -> {}
                else -> {
                    ok?.isEnabled = false
                    PrivateFolder.checkPin(pin) { right ->
                        ok?.isEnabled = true
                        if (right) {
                            d.dismiss()
                            onOk()
                        } else {
                            input.setText("")
                            error.text = if (PrivateFolder.waitLeft() > 0) "Too many wrong PINs. Try again in 30 seconds." else "That PIN isn't right."
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
            forgotPin(a)
        }
        input.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /**
     * A forgotten PIN can't be read back (only its hash is kept). Once the phone has a screen lock,
     * Private opens with that instead, and a new PIN can be chosen should the lock go again.
     */
    fun forgotPin(a: Activity) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Forgot your PIN?")
            .setMessage(
                "Swift can't tell you the PIN: it keeps only a scrambled form of it.\n\n" +
                    "Set a screen lock for your phone (in Android's settings, under Security → Screen lock). " +
                    "Private then opens with your screen lock instead, and its PIN is no longer needed.\n\n" +
                    "Without that, the files in Private can't be opened, unless you have a copy of them elsewhere.",
            )
            .setPositiveButton("Open Android's settings") { _, _ ->
                try {
                    a.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: Exception) {
                    runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showImmersive()
    }

    /** From Settings: choose a new PIN (the old one first), or say that the screen lock opens Private. */
    fun changePin(a: Activity) {
        when {
            hasScreenLock(a) -> Dialogs.info(
                a, "Private uses your screen lock",
                "Your phone has a screen lock, so Private opens with it. Swift's own PIN is only for phones without one.",
            )
            PrivateFolder.hasPin() -> askPin(a, "Change the PIN") {
                setUpPin(a, "Choose a new PIN") { Messages.show(a, "Private's PIN is changed") }
            }
            else -> setUpPin(a) { Messages.show(a, "Private opens with this PIN now") }
        }
    }
}
