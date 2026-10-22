package com.swiftbrowser.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import java.util.Calendar
import java.util.Locale

/**
 * Native dialogs for everything web pages ask for: alert/confirm/prompt, <select> lists,
 * file uploads, date and colour pickers, HTTP logins. GeckoView has no built-in UI for these.
 */
class Prompts : PromptDelegate {

    private val context: Context? get() = TabManager.ui?.activity

    private fun <T : BasePrompt> show(
        prompt: T,
        build: (Context, GeckoResult<PromptResponse>) -> AlertDialog?,
    ): GeckoResult<PromptResponse> {
        val ctx = context ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        val dialog = build(ctx, result) ?: return GeckoResult.fromValue(prompt.dismiss())
        prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) {
                dialog.dismiss()
            }
        })
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
        }
        dialog.showImmersive()
        return result
    }

    private fun builder(ctx: Context, title: String?) =
        MaterialAlertDialogBuilder(ctx).apply { if (!title.isNullOrBlank()) setTitle(title) }

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            builder(ctx, prompt.title)
                .setMessage(prompt.message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
                .create()
        }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            builder(ctx, prompt.title)
                .setMessage(prompt.message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
                }
                .create()
        }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            val input = EditText(ctx).apply {
                setText(prompt.defaultValue ?: "")
                setSingleLine()
                selectAll()
            }
            builder(ctx, prompt.title)
                .setMessage(prompt.message)
                .setView(padded(ctx, input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(input.text.toString()))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            val onlyPassword = prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
            val user = EditText(ctx).apply {
                hint = "Username"
                setSingleLine()
                setText(prompt.authOptions.username ?: "")
            }
            val pass = EditText(ctx).apply {
                hint = "Password"
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                if (!onlyPassword) addView(user)
                addView(pass)
            }
            builder(ctx, prompt.title ?: "Sign in")
                .setMessage(prompt.message)
                .setView(padded(ctx, box))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(
                        if (onlyPassword) prompt.confirm(pass.text.toString())
                        else prompt.confirm(user.text.toString(), pass.text.toString()),
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

    private class Row(val choice: ChoicePrompt.Choice?, val label: String, val enabled: Boolean)

    private fun flatten(choices: Array<ChoicePrompt.Choice>, out: MutableList<Row>, indent: String, headers: Boolean) {
        for (c in choices) {
            if (c.separator) continue
            val children = c.items
            if (children != null) {
                if (headers) out.add(Row(null, c.label, false))
                flatten(children, out, "$indent    ", headers)
            } else {
                out.add(Row(c, indent + c.label, !c.disabled))
            }
        }
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val rows = ArrayList<Row>().also { flatten(prompt.choices, it, "", headers = false) }
                val checked = BooleanArray(rows.size) { rows[it].choice?.selected == true }
                builder(ctx, prompt.title)
                    .setMultiChoiceItems(rows.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selected = rows.filterIndexed { i, _ -> checked[i] }.mapNotNull { it.choice }
                        result.complete(prompt.confirm(selected.toTypedArray()))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            } else {
                val rows = ArrayList<Row>().also { flatten(prompt.choices, it, "", headers = true) }
                val layout = if (prompt.type == ChoicePrompt.Type.SINGLE) {
                    android.R.layout.simple_list_item_single_choice
                } else {
                    android.R.layout.simple_list_item_1
                }
                val adapter = object : ArrayAdapter<String>(ctx, layout, rows.map { it.label }) {
                    override fun isEnabled(position: Int) = rows[position].enabled
                    override fun areAllItemsEnabled() = false
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent)
                        (v as? TextView)?.alpha = if (rows[position].enabled) 1f else 0.5f
                        return v
                    }
                }
                val checkedIndex = rows.indexOfFirst { it.choice?.selected == true }
                val b = builder(ctx, prompt.title)
                val pick = { which: Int ->
                    rows[which].choice?.let { result.complete(prompt.confirm(it)) }
                }
                if (prompt.type == ChoicePrompt.Type.SINGLE) {
                    b.setSingleChoiceItems(adapter, checkedIndex) { d, which ->
                        pick(which)
                        d.dismiss()
                    }
                } else {
                    b.setAdapter(adapter) { _, which -> pick(which) }
                }
                b.create()
            }
        }

    override fun onColorPrompt(session: GeckoSession, prompt: ColorPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            val colors = (prompt.predefinedValues?.toList() ?: emptyList()).ifEmpty {
                listOf(
                    "#000000", "#ffffff", "#f44336", "#e91e63", "#9c27b0", "#3f51b5", "#2196f3", "#03a9f4",
                    "#009688", "#4caf50", "#8bc34a", "#ffeb3b", "#ffc107", "#ff9800", "#795548", "#9e9e9e",
                )
            }
            val input = EditText(ctx).apply {
                setText(prompt.defaultValue ?: "#000000")
                setSingleLine()
            }
            val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, colors) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    val c = try {
                        Color.parseColor(colors[position])
                    } catch (_: Exception) {
                        Color.GRAY
                    }
                    v.setBackgroundColor(c)
                    v.setTextColor(if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE)
                    return v
                }
            }
            builder(ctx, prompt.title ?: "Choose a colour")
                .setAdapter(adapter) { _, which -> result.complete(prompt.confirm(colors[which])) }
                .setView(padded(ctx, input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                    result.complete(prompt.confirm(value.lowercase(Locale.ROOT)))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        val ctx = context ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        val cal = Calendar.getInstance()
        parseInto(cal, prompt.defaultValue, prompt.type)
        val finishDate = { y: Int, m: Int, d: Int ->
            cal.set(y, m, d)
            when (prompt.type) {
                DateTimePrompt.Type.MONTH -> result.complete(prompt.confirm(String.format(Locale.US, "%04d-%02d", y, m + 1)))
                DateTimePrompt.Type.WEEK -> {
                    cal.minimalDaysInFirstWeek = 4
                    cal.firstDayOfWeek = Calendar.MONDAY
                    val week = cal.get(Calendar.WEEK_OF_YEAR)
                    result.complete(prompt.confirm(String.format(Locale.US, "%04d-W%02d", cal.getWeekYear(), week)))
                }
                DateTimePrompt.Type.DATETIME_LOCAL -> {
                    val tp = TimePickerDialog(ctx, { _, h, min ->
                        result.complete(
                            prompt.confirm(String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", y, m + 1, d, h, min)),
                        )
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
                    tp.setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
                    tp.showImmersive()
                }
                else -> result.complete(prompt.confirm(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)))
            }
        }
        if (prompt.type == DateTimePrompt.Type.TIME) {
            val tp = TimePickerDialog(ctx, { _, h, min ->
                result.complete(prompt.confirm(String.format(Locale.US, "%02d:%02d", h, min)))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
            tp.setOnCancelListener { result.complete(prompt.dismiss()) }
            tp.showImmersive()
        } else {
            val dp = DatePickerDialog(ctx, { _, y, m, d -> finishDate(y, m, d) },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            dp.setOnCancelListener { result.complete(prompt.dismiss()) }
            dp.showImmersive()
        }
        return result
    }

    private fun parseInto(cal: Calendar, value: String?, type: Int) {
        if (value.isNullOrBlank()) return
        try {
            when (type) {
                DateTimePrompt.Type.TIME -> {
                    val p = value.split(":")
                    cal.set(Calendar.HOUR_OF_DAY, p[0].toInt())
                    cal.set(Calendar.MINUTE, p[1].toInt())
                }
                else -> {
                    val date = value.substringBefore('T').split("-")
                    if (date.size >= 2 && !date[1].startsWith("W")) {
                        cal.set(Calendar.YEAR, date[0].toInt())
                        cal.set(Calendar.MONTH, date[1].toInt() - 1)
                        if (date.size >= 3) cal.set(Calendar.DAY_OF_MONTH, date[2].toInt())
                    }
                    if (value.contains('T')) {
                        val t = value.substringAfter('T').split(":")
                        cal.set(Calendar.HOUR_OF_DAY, t[0].toInt())
                        cal.set(Calendar.MINUTE, t[1].toInt())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
        val ui = TabManager.ui ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        val mimes = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray() ?: emptyArray()
        ui.pickFiles(mimes, prompt.type == FilePrompt.Type.MULTIPLE) { uris ->
            val ctx = ui.activity
            result.complete(
                when {
                    uris.isNullOrEmpty() -> prompt.dismiss()
                    prompt.type == FilePrompt.Type.MULTIPLE -> prompt.confirm(ctx, uris.toTypedArray())
                    else -> prompt.confirm(ctx, uris[0])
                },
            )
        }
        return result
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse> {
        // Gecko's own pop-up blocker caught a pop-up opened without a tap. The app's NavPolicy
        // already handled taps; untapped pop-ups are blocked at every level except Off.
        val tab = TabManager.bySession(session)
        val level = com.swiftbrowser.adblock.AdBlock.levelFor(tab?.url)
        if (level == com.swiftbrowser.adblock.AdLevel.OFF) return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        val target = prompt.targetUri
        if (tab != null && !target.isNullOrBlank()) TabManager.ui?.onBlocked(tab, target, true)
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    override fun onSharePrompt(session: GeckoSession, prompt: SharePrompt): GeckoResult<PromptResponse> {
        val ctx = context ?: return GeckoResult.fromValue(prompt.dismiss())
        val text = listOfNotNull(prompt.text, prompt.uri).joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            prompt.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return try {
            ctx.startActivity(Intent.createChooser(intent, "Share"))
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.SUCCESS))
        } catch (_: Exception) {
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE))
        }
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            builder(ctx, "Resend data?")
                .setMessage("This page needs to send the form data again.")
                .setPositiveButton("Resend") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                .create()
        }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt): GeckoResult<PromptResponse> =
        show(prompt) { ctx, result ->
            builder(ctx, "Leave this page?")
                .setMessage("Changes you made may not be saved.")
                .setPositiveButton("Leave") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton("Stay") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                .create()
        }

    /** Allow/Block question for a site permission (location). */
    fun askPermission(message: String): GeckoResult<Int> {
        val ctx = context ?: return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
        val result = GeckoResult<Int>()
        var answered = false
        val answer = { value: Int ->
            if (!answered) {
                answered = true
                result.complete(value)
            }
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ -> answer(ContentPermission.VALUE_ALLOW) }
            .setNegativeButton("Block") { _, _ -> answer(ContentPermission.VALUE_DENY) }
            .create()
        dialog.setOnDismissListener { answer(ContentPermission.VALUE_DENY) }
        dialog.showImmersive()
        return result
    }

    private fun padded(ctx: Context, v: View): View {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        return LinearLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
