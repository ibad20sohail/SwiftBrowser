package com.swiftbrowser.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Passwords
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** [cancel]: answers no, for a question whose page has gone (3.0). */
    private class Waiting(val session: GeckoSession, val open: () -> Unit, val cancel: (() -> Unit)? = null)

    /** Dialogs of tabs that aren't on screen (2.6): they wait until their tab is chosen. */
    private val waiting = ArrayList<Waiting>()

    /** [session]'s tab is on screen now: what its page asked for meanwhile shows. */
    fun showWaiting(session: GeckoSession) {
        // 3.0: questions of tabs closed meanwhile are answered no (and let go).
        waiting.filter { !it.session.isOpen }.forEach {
            waiting.remove(it)
            it.cancel?.invoke()
        }
        val now = waiting.filter { it.session === session }
        waiting.removeAll(now)
        now.forEach { it.open() }
    }

    /**
     * Shows [prompt] for [session]'s page. A page in a tab that isn't on screen can't put a dialog
     * over another site's page (it would look as if that site asked): it waits for its tab.
     * [onClosed]: the dialog has gone (3.0, see [DialogRun]).
     */
    private fun <T : BasePrompt> show(
        session: GeckoSession,
        prompt: T,
        onClosed: (() -> Unit)? = null,
        build: (Context, GeckoResult<PromptResponse>) -> AlertDialog?,
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val tab = TabManager.bySession(session)
        if (tab != null && tab !== TabManager.current) {
            val w = Waiting(session, { open(prompt, result, onClosed, build) })
            waiting += w
            prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: BasePrompt) {
                    waiting.remove(w)
                }
            })
            return result
        }
        open(prompt, result, onClosed, build)
        return result
    }

    private fun <T : BasePrompt> open(
        prompt: T,
        result: GeckoResult<PromptResponse>,
        onClosed: (() -> Unit)?,
        build: (Context, GeckoResult<PromptResponse>) -> AlertDialog?,
    ) {
        val ctx = context
        val dialog = if (ctx != null) build(ctx, result) else null
        if (dialog == null) {
            result.complete(prompt.dismiss())
            return
        }
        prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) {
                dialog.dismiss()
            }
        })
        dialog.setOnDismissListener {
            if (!prompt.isComplete) result.complete(prompt.dismiss())
            onClosed?.invoke()
        }
        dialog.showImmersive()
    }

    /**
     * 3.0: a page's alerts, confirms and prompts in a row, for an end to endless ones (scam pages
     * loop "Your phone has a virus!" so the page can't be left): from the third in a short time the
     * dialog offers "Block dialogs from this page". Gecko's own "stop this page's dialogs" isn't
     * reachable through GeckoView. Per tab, for one site until the user reloads, types an address
     * or goes back or forward (a page can change its address itself, but not those).
     */
    private class DialogRun(val site: String?, val userNavAt: Long) {
        var inARow = 0
        var lastClosedAt = -1L
        var blocked = false
    }

    private val runs = java.util.WeakHashMap<GeckoSession, DialogRun>()

    private fun runOf(session: GeckoSession): DialogRun? {
        val tab = TabManager.bySession(session) ?: return null
        val site = UrlUtils.site(tab.url)
        runs[session]?.let { if (it.site == site && it.userNavAt == tab.userNavAt) return it }
        return DialogRun(site, tab.userNavAt).also { runs[session] = it }
    }

    /**
     * An alert, confirm or prompt of a page (see [DialogRun]). [build] gets the "Block dialogs from
     * this page" action when the dialog should offer it: it answers the dialog like Cancel.
     */
    private fun <T : BasePrompt> pageDialog(
        session: GeckoSession,
        prompt: T,
        build: (Context, GeckoResult<PromptResponse>, (() -> Unit)?) -> AlertDialog?,
    ): GeckoResult<PromptResponse> {
        val run = runOf(session)
        if (run?.blocked == true) {
            // Answered after a moment: a page asking in an endless loop then turns slowly, not the phone.
            val result = GeckoResult<PromptResponse>()
            mainHandler.postDelayed({ result.complete(prompt.dismiss()) }, 500)
            return result
        }
        return show(session, prompt, onClosed = { run?.lastClosedAt = SystemClock.elapsedRealtime() }) { ctx, result ->
            build(ctx, result, if (run != null && countShown(run)) ({ block(run) }) else null)
        }
    }

    /** [run]'s page shows a dialog now: true when it should offer to block the page's dialogs. */
    private fun countShown(run: DialogRun): Boolean {
        run.inARow = DialogLoop.inARow(run.inARow, run.lastClosedAt, SystemClock.elapsedRealtime())
        return DialogLoop.offers(run.inARow)
    }

    private fun block(run: DialogRun) {
        if (run.blocked) return
        run.blocked = true
        TabManager.ui?.showMessage("This page can't show more dialogs. Reload it to allow them again.", long = true)
    }

    /** "Block dialogs from this page" as the dialog's third button. */
    private fun MaterialAlertDialogBuilder.blockButton(block: (() -> Unit)?, answer: () -> Unit): MaterialAlertDialogBuilder {
        if (block != null) setNeutralButton("Block dialogs from this page") { _, _ ->
            block()
            answer()
        }
        return this
    }

    /** In the app's colours (2.6), like Swift's own dialogs. */
    private fun builder(ctx: Context, title: String?) =
        MaterialAlertDialogBuilder(ctx).setBackground(Palette.dialogBackground(ctx)).apply { if (!title.isNullOrBlank()) setTitle(title) }

    /** "example.com says": which site's page is asking (2.6). */
    private fun says(session: GeckoSession, title: String?): String? {
        if (!title.isNullOrBlank()) return title
        val site = TabManager.bySession(session)?.url?.let { com.swiftbrowser.core.UrlUtils.siteName(it) }
        return if (site.isNullOrBlank()) null else "$site says"
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse> =
        pageDialog(session, prompt) { ctx, result, block ->
            builder(ctx, says(session, prompt.title))
                .setMessage(prompt.message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
                .blockButton(block) { result.complete(prompt.dismiss()) }
                .create()
        }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse> =
        pageDialog(session, prompt) { ctx, result, block ->
            builder(ctx, says(session, prompt.title))
                .setMessage(prompt.message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
                }
                .blockButton(block) { result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)) }
                .create()
        }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse> =
        pageDialog(session, prompt) { ctx, result, block ->
            val input = EditText(ctx).apply {
                setText(prompt.defaultValue ?: "")
                setSingleLine()
                selectAll()
                Ui.field(this)
            }
            builder(ctx, says(session, prompt.title))
                .setMessage(prompt.message)
                .setView(padded(ctx, input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    result.complete(prompt.confirm(input.text.toString()))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .blockButton(block) { result.complete(prompt.dismiss()) }
                .create()
        }

    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse> =
        show(session, prompt) { ctx, result ->
            val onlyPassword = prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
            val user = EditText(ctx).apply {
                hint = "Username"
                setSingleLine()
                setText(prompt.authOptions.username ?: "")
                Ui.field(this)
            }
            val pass = EditText(ctx).apply {
                hint = "Password"
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                Ui.field(this)
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                if (!onlyPassword) addView(user)
                addView(pass, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (!onlyPassword) topMargin = Ui.dp(ctx, Ui.S)
                })
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

    private class Row(val choice: ChoicePrompt.Choice?, val label: String, val enabled: Boolean, val depth: Int)

    private fun flatten(choices: Array<ChoicePrompt.Choice>, out: MutableList<Row>, depth: Int, headers: Boolean) {
        for (c in choices) {
            if (c.separator) continue
            val children = c.items
            if (children != null) {
                if (headers) out.add(Row(null, c.label, false, depth))
                flatten(children, out, depth + 1, headers)
            } else {
                out.add(Row(c, c.label, !c.disabled, depth))
            }
        }
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> =
        show(session, prompt) { ctx, result ->
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val rows = ArrayList<Row>().also { flatten(prompt.choices, it, 0, headers = false) }
                val checked = BooleanArray(rows.size) { rows[it].choice?.selected == true }
                builder(ctx, prompt.title)
                    .setMultiChoiceItems(rows.map { "    ".repeat(it.depth) + it.label }.toTypedArray(), checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selected = rows.filterIndexed { i, _ -> checked[i] }.mapNotNull { it.choice }
                        result.complete(prompt.confirm(selected.toTypedArray()))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            } else {
                val rows = ArrayList<Row>().also { flatten(prompt.choices, it, 0, headers = true) }
                val b = builder(ctx, prompt.title)
                // The rows of Swift's own dialogs (made in the dialog's look): a radio for a choice, plain for a menu.
                val layout = if (prompt.type == ChoicePrompt.Type.SINGLE) {
                    com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice
                } else {
                    com.swiftbrowser.R.layout.dialog_list_item
                }
                val indent = Ui.dp(ctx, 16)
                val rowHeight = Ui.dp(ctx, 48)
                val startPadding = intArrayOf(-1, -1)
                val adapter = object : ArrayAdapter<String>(b.context, layout, rows.map { it.label }) {
                    override fun isEnabled(position: Int) = rows[position].enabled
                    override fun areAllItemsEnabled() = false
                    override fun getViewTypeCount() = 2
                    override fun getItemViewType(position: Int) = if (rows[position].choice == null) 1 else 0
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row = rows[position]
                        val kind = getItemViewType(position)
                        // A group's name is no choice: a plain row, without a radio.
                        val v = if (kind == 1) {
                            convertView ?: android.view.LayoutInflater.from(context).inflate(com.swiftbrowser.R.layout.dialog_list_item, parent, false)
                        } else {
                            super.getView(position, convertView, parent)
                        }
                        (v as? TextView)?.apply {
                            if (kind == 1) text = row.label
                            if (startPadding[kind] < 0) startPadding[kind] = paddingStart
                            // A group's name on Swift's scale at full strength; its options under it, moved in by padding.
                            Ui.style(this, if (kind == 1) Ui.Type.SECTION else Ui.Type.BODY)
                            minHeight = rowHeight
                            alpha = if (kind == 1 || row.enabled) 1f else Ui.DISABLED_ALPHA
                            setPaddingRelative(startPadding[kind] + row.depth * indent, paddingTop, paddingEnd, paddingBottom)
                        }
                        return v
                    }
                }
                val checkedIndex = rows.indexOfFirst { it.choice?.selected == true }
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
        show(session, prompt) { ctx, result ->
            val colors = (prompt.predefinedValues?.toList() ?: emptyList()).ifEmpty {
                listOf(
                    "#000000", "#ffffff", "#f44336", "#e91e63", "#9c27b0", "#3f51b5", "#2196f3", "#03a9f4",
                    "#009688", "#4caf50", "#8bc34a", "#ffeb3b", "#ffc107", "#ff9800", "#795548", "#9e9e9e",
                )
            }
            val input = EditText(ctx).apply {
                setText(prompt.defaultValue ?: "#000000")
                setSingleLine()
                Ui.field(this)
            }
            var shown: AlertDialog? = null
            // A grid of swatches, as many to a row as fit a dialog on this screen.
            val grid = android.widget.GridLayout(ctx).apply {
                val fit = ((ctx.resources.configuration.screenWidthDp - DIALOG_SIDES_DP) / SWATCH_PLACE_DP).coerceIn(4, 8)
                // One left over on a row of its own looks like a mistake: a column fewer then.
                columnCount = if (colors.size % fit == 1) fit - 1 else fit
                for (hex in colors) {
                    addView(swatch(ctx, hex) {
                        result.complete(prompt.confirm(hex))
                        shown?.dismiss()
                    })
                }
            }
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Ui.dp(ctx, 8)
                })
            }
            builder(ctx, prompt.title ?: "Choose a colour")
                .setView(android.widget.ScrollView(ctx).apply { addView(padded(ctx, body)) })
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                    result.complete(prompt.confirm(value.lowercase(Locale.ROOT)))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create().also { shown = it }
        }

    /** A colour to tap in a colour dialog: 40 dp, rounded, outlined so that a colour like the dialog's still shows. */
    private fun swatch(ctx: Context, hex: String, onPick: () -> Unit): View = View(ctx).apply {
        val c = try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.GRAY
        }
        background = Ui.shape(ctx, c, Ui.R_SMALL, Palette.color(ctx, com.swiftbrowser.R.color.outline))
        foreground = Ui.ripple(ctx, null, Ui.R_SMALL)
        contentDescription = hex
        // The exact colour, whatever the dialog's colours are changed to around it.
        setTag(com.swiftbrowser.R.id.palette_keep, true)
        setOnClickListener { onPick() }
        val size = Ui.dp(ctx, 40)
        val gap = Ui.dp(ctx, 4)
        layoutParams = android.widget.GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(gap, gap, gap, gap)
        }
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
        ui.pickFiles(mimes, prompt.type == FilePrompt.Type.MULTIPLE, prompt.capture != FilePrompt.Capture.NONE) { uris ->
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

    /** A sign-in was sent: offer to save it (not from incognito tabs, nor for sites marked Never). */
    override fun onLoginSave(
        session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>,
    ): GeckoResult<PromptResponse> {
        val option = request.options.firstOrNull() ?: return GeckoResult.fromValue(request.dismiss())
        val entry = option.value
        val tab = TabManager.bySession(session)
        if (tab == null || tab.incognito || !SwiftApp.prefs.offerPasswords || Passwords.never(entry.origin)) {
            return GeckoResult.fromValue(request.dismiss())
        }
        return show(session, request) { ctx, result ->
            val site = UrlUtils.siteName(entry.origin).ifBlank { entry.origin }
            val update = entry.guid != null
            builder(ctx, if (update) "Update your password for $site?" else "Save your password for $site?")
                .setMessage(
                    (entry.username?.ifBlank { null } ?: "(no user name)") +
                        "\n\nSwift keeps it on your phone, locked by Android, and fills it in next time.",
                )
                .setPositiveButton(if (update) "Update" else "Save") { _, _ -> result.complete(request.confirm(option)) }
                .setNegativeButton("Not now") { _, _ -> result.complete(request.dismiss()) }
                .setNeutralButton("Never for this site") { _, _ ->
                    Passwords.setNever(entry.origin, true)
                    result.complete(request.dismiss())
                }
                .create()
        }
    }

    /** Several sign-ins saved for the site: which one to fill in. */
    override fun onLoginSelect(
        session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>,
    ): GeckoResult<PromptResponse> {
        val options = request.options
        if (options.isEmpty()) return GeckoResult.fromValue(request.dismiss())
        return show(session, request) { ctx, result ->
            builder(ctx, "Sign in as")
                .setItems(options.map { it.value.username?.ifBlank { null } ?: "(no user name)" }.toTypedArray()) { _, which ->
                    result.complete(request.confirm(options[which]))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
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
        show(session, prompt) { ctx, result ->
            builder(ctx, "Resend data?")
                .setMessage("Showing this page again sends what you entered in its form again (for example an order or a payment).")
                .setPositiveButton("Resend") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                .create()
        }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt): GeckoResult<PromptResponse> {
        // 3.0: a page whose dialogs were blocked can't hold you back from leaving it either.
        if (runs[session]?.blocked == true) return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        return show(session, prompt) { ctx, result ->
            builder(ctx, "Leave this page?")
                .setMessage("Changes you made may not be saved.")
                .setPositiveButton("Leave") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton("Stay") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
                .create()
        }
    }

    /**
     * Allow/Block question for a site permission (location, a site inside the page wanting its
     * cookies). 3.0: it waits for its tab like the page's own dialogs, so a tab in the background
     * can't ask over another site; if that tab has moved on to another site meanwhile, the answer is
     * no. [rememberable]: with a "Remember for this site" box. [onAnswer] hears a button's answer
     * (true: allowed) and whether the box was ticked; going away without one is a no, not told.
     */
    fun askPermission(
        session: GeckoSession,
        message: String,
        allow: String = "Allow",
        deny: String = "Block",
        rememberable: Boolean = false,
        onAnswer: ((allowed: Boolean, remember: Boolean) -> Unit)? = null,
    ): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        var answered = false
        val answer = { value: Int ->
            if (!answered) {
                answered = true
                result.complete(value)
            }
        }
        val tab = TabManager.bySession(session)
        val site = tab?.let { UrlUtils.site(it.url) }
        val ask = ask@{
            val ctx = context
            if (ctx == null || (tab != null && UrlUtils.site(tab.url) != site)) {
                answer(ContentPermission.VALUE_DENY)
                return@ask
            }
            val remember = if (!rememberable) null else android.widget.CheckBox(ctx).apply {
                text = "Remember for this site"
                setPadding(Ui.dp(ctx, 4), 0, 0, 0)
            }
            val reply = { allowed: Boolean ->
                onAnswer?.invoke(allowed, remember?.isChecked == true)
                answer(if (allowed) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
            }
            val dialog = builder(ctx, null)
                .setMessage(message)
                .apply { if (remember != null) setView(padded(ctx, remember)) }
                .setPositiveButton(allow) { _, _ -> reply(true) }
                .setNegativeButton(deny) { _, _ -> reply(false) }
                .create()
            dialog.setOnDismissListener { answer(ContentPermission.VALUE_DENY) }
            dialog.showImmersive()
        }
        if (tab != null && tab !== TabManager.current) {
            waiting += Waiting(session, ask) { answer(ContentPermission.VALUE_DENY) }
        } else {
            ask()
        }
        return result
    }

    /** A dialog's sides and insets on a phone, and a swatch with its gaps (dp): how many fit a row. */
    private companion object {
        const val DIALOG_SIDES_DP = 96
        const val SWATCH_PLACE_DP = 48
    }

    private fun padded(ctx: Context, v: View): View {
        // Starts where the dialog's title does.
        val pad = (24 * ctx.resources.displayMetrics.density).toInt()
        return LinearLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}

/** 3.0: when a page's dialogs come one after another (see Prompts.DialogRun). Plain, for tests. */
internal object DialogLoop {
    /** A dialog that comes this soon after the one before it closed is the next in a row. */
    const val GAP_MS = 10_000L

    /** From this dialog in a row on, "Block dialogs from this page" is offered. */
    const val OFFER_FROM = 3

    /** The place in a row of a dialog coming at [now], after [before] in a row, the last one closed at [lastClosedAt] (-1: none). */
    fun inARow(before: Int, lastClosedAt: Long, now: Long): Int =
        if (lastClosedAt >= 0 && now - lastClosedAt <= GAP_MS) before + 1 else 1

    fun offers(inARow: Int): Boolean = inARow >= OFFER_FROM
}
