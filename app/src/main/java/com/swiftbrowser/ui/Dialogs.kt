package com.swiftbrowser.ui

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Questions in the same shape everywhere (2.0): a short title, one or two plain sentences, the
 * action on the right (red when it deletes something) and Cancel next to it. Only for what can't
 * be undone; single removals use Undo instead (Messages).
 */
object Dialogs {
    fun confirm(
        ctx: Context,
        title: String,
        message: String?,
        confirm: String,
        danger: Boolean = false,
        cancel: String = "Cancel",
        onConfirm: () -> Unit,
    ): AlertDialog {
        val d = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .apply { if (!message.isNullOrBlank()) setMessage(message) }
            .setPositiveButton(confirm) { _, _ -> onConfirm() }
            .setNegativeButton(cancel, null)
            .showImmersive()
        if (danger) markDanger(d)
        return d
    }

    /** Something to read, with OK. */
    fun info(ctx: Context, title: String, message: String): AlertDialog =
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .showImmersive()

    /** The dialog's main button in red: it deletes or closes something for good. */
    fun markDanger(d: AlertDialog) {
        d.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(Ui.danger(d.context))
    }
}
