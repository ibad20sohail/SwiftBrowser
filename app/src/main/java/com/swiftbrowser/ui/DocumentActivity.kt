package com.swiftbrowser.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.core.AppFiles
import com.swiftbrowser.core.L
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.DocRules
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.media.Helper
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Locale

/**
 * A document on a screen of its own (2.8), not in a tab: the viewer page full screen, over the
 * screen it was opened from (Documents, Files, Downloads, the browser, another app). Its bar is the
 * page's own; Back closes the page's panels first, then this screen, which returns to where the
 * document was opened from.
 *
 * The page talks to Swift as it does in a tab, through a [Tab] of its own that is never in the tab
 * list (Docs answers it). The document keeps its own colours; only the viewer's bars follow Swift's.
 *
 * 3.0: the screen itself is [DocumentScreen], which only Swift opens. Other apps' "Open with" and
 * home screen shortcuts come in through "DocumentActivity", now an exported alias of it with this
 * class's old name (so "Always open with Swift" and shortcuts made before keep working): links to
 * Swift's own private files are refused there, and a shortcut opens only one of the recent
 * documents. Private and incognito documents stay out of screenshots; the page's own questions
 * (lists in PDF forms, dates, colours) show as Swift's dialogs.
 */
open class DocumentActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var gecko: GeckoView
    private lateinit var spinner: LoadingIndicator
    private lateinit var findBar: LinearLayout
    private lateinit var findText: EditText
    private lateinit var findCount: TextView

    /** Find is open (its bar may still be fading away). */
    private var finding = false

    private var session: GeckoSession? = null
    private var tab: Tab? = null
    private var docId: String? = null
    private var incognito = false
    private var canGoBack = false

    /** Opened for another app or a home screen shortcut through the browser: Back leaves Swift. */
    private var leaveSwift = false

    /** The page's engine process crashed once already: a second crash shows the message instead. */
    private var revived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Looks.apply(this)
        super.onCreate(savedInstanceState)
        screens += WeakReference(this)
        window.statusBarColor = Palette.color(this, R.color.page_bg)
        buildViews()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        SystemBars.keepHidden(this)
        if (!start(intent)) finish()
    }

    private fun buildViews() {
        val pageBehind = Palette.color(this, R.color.settings_bg)
        root = FrameLayout(this).apply { setBackgroundColor(pageBehind) }
        gecko = GeckoView(this)
        // The engine's view is white until the page first draws: in the page's own colour instead.
        gecko.coverUntilFirstPaint(pageBehind)
        root.addView(gecko, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Until the viewer page is there (the engine may still be starting).
        // 3.0.4: Swift's loading indicator, in the accent.
        spinner = LoadingIndicator(this)
        root.addView(spinner, FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER))
        buildFindBar()
        setContentView(root)
    }

    /**
     * What the intent asks for: a document Swift knows (its id), or a file another app sends.
     * 3.0: only Swift itself opens [DocumentScreen] directly (with a document's id); what comes
     * through the public entrance (the DocumentActivity alias) is checked first.
     */
    private fun start(i: Intent): Boolean {
        leaveSwift = i.getBooleanExtra(EXTRA_LEAVE, false)
        if (i.component?.className != DocumentScreen::class.java.name) return startFromOutside(i)
        val id = i.getStringExtra(EXTRA_ID)?.takeIf { DocRules.validId(it) } ?: return false
        incognito = i.getBooleanExtra(EXTRA_INCOGNITO, false)
        show(id, i.getBooleanExtra(AppFiles.EXTRA_SECURE, false))
        return true
    }

    /**
     * Another app's file ("Open with Swift"), or a home screen shortcut to a recent document (3.0).
     * Never one of Swift's own files (its Private folder, its data), nor an incognito document or one
     * of Private's (only the recent documents have shortcuts).
     */
    private fun startFromOutside(i: Intent): Boolean {
        when (i.action) {
            Intent.ACTION_VIEW -> {
                val data = i.data ?: return false
                // Docs.register refuses these too; said here at once, without asking another app.
                if (AppFiles.refusedFromOutside(this, data)) {
                    L.w("Another app asked to show one of Swift's own files: refused")
                    say("Swift can't open this file")
                    return false
                }
                val type = i.type
                val app = applicationContext
                // The app that sends it is asked for the file's name and size off the main thread
                // (it may be slow to answer); the spinner shows meanwhile.
                lookups.execute {
                    val id = try {
                        Docs.register(app, data, null, type, incognito = false)
                    } catch (e: Exception) {
                        L.w("Couldn't take a file from another app (${e.javaClass.simpleName})")
                        null
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (id == null) {
                            say("Swift can't show this kind of file")
                            finish()
                        } else {
                            show(id, false)
                        }
                    }
                }
                return true
            }
            Docs.ACTION_OPEN_DOC -> {
                val id = i.getStringExtra(Docs.EXTRA_DOC_ID)?.takeIf { DocRules.validId(it) }
                lookups.execute {
                    // Only one of the recent documents (never an incognito one, nor one of Private's).
                    val doc = id?.let { Docs.find(it) }?.takeIf { !it.incognito && !Docs.isPrivate(it.id) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (doc == null) {
                            say("Swift doesn't have this document any more")
                            finish()
                        } else {
                            show(doc.id, false)
                        }
                    }
                }
                return true
            }
            else -> return false
        }
    }

    /** A message that outlives this screen (it closes at once). */
    private fun say(text: String) = android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show()

    /**
     * Shows document [id] once the viewer page can load. [secure]: kept out of screenshots and the
     * recent-apps picture (a file of Private's); incognito ones too, unless allowed in Settings (3.0).
     */
    private fun show(id: String, secure: Boolean) {
        docId = id
        if (Docs.find(id)?.incognito == true) incognito = true
        if (secure || Docs.isPrivate(id)) SecureScreen.set(this, true) else SecureScreen.forIncognito(this, incognito)
        Docs.whenViewerReady { if (!isDestroyed) load(id) }
    }

    private fun load(id: String) {
        // "?screen": the page knows it is on this screen (a Back arrow in its bar).
        val url = Docs.viewerUrl(id)?.replace("/docs/view.html#", "/docs/view.html?screen#") ?: return
        val doc = Docs.find(id)
        val profile = Profiles.currentId
        val t = Tab(nextTabId--, profile, url, doc?.name ?: "Document", null, null, System.currentTimeMillis(), false, incognito)
        tab = t
        hosts[t] = WeakReference(this)
        openSession(t, url)
    }

    private fun openSession(t: Tab, url: String) {
        val builder = GeckoSessionSettings.Builder()
        if (!t.incognito) builder.contextId(Profiles.contextId(t.profileId))
        val s = GeckoSession(builder.usePrivateMode(t.incognito).viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build())
        s.navigationDelegate = navigation
        s.contentDelegate = content
        // 3.0: without one, the engine declines every question the page asks (a PDF form's list, a date).
        s.promptDelegate = prompts
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                spinner.visibility = View.GONE
            }
        }
        s.selectionActionDelegate = BasicSelectionActionDelegate(this)
        t.session = s
        Helper.attachSession(t, s)
        s.open(Engine.runtime)
        gecko.coverUntilFirstPaint(Palette.color(this, R.color.settings_bg))
        gecko.setSession(s)
        session = s
        s.loadUri(url)
    }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@DocumentActivity.canGoBack = canGoBack
        }

        // The viewer stays on its page; a link in the document opens in the browser. 3.0: only the
        // blank pages the viewer itself uses; e-mail and phone links go to the phone's apps.
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
            val uri = request.uri
            if (Docs.isViewerUrl(uri) || uri == "about:blank" || uri == "about:srcdoc" || uri.startsWith("blob:") || uri.startsWith("data:")) {
                return GeckoResult.allow()
            }
            if (UrlUtils.isHttp(uri) || request.hasUserGesture) openLink(uri)
            return GeckoResult.deny()
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            if (UrlUtils.isHttp(uri)) openInBrowser(uri)
            return null
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) = pageGone()

        override fun onKill(session: GeckoSession) = pageGone()
    }

    /** The page's engine process ended: the document loads again once; a second time, it says so. */
    private fun pageGone() {
        val t = tab ?: return
        L.w("Document viewer's page process ended")
        session?.let { s ->
            gecko.releaseSession()
            s.close()
        }
        session = null
        if (revived || isFinishing) {
            Messages.show(this, "The document stopped working. Open it again to read it.")
            return
        }
        revived = true
        spinner.visibility = View.VISIBLE
        openSession(t, t.url)
    }

    /** A link from the document: in a new tab of the browser, which comes to the front. */
    private fun openInBrowser(url: String) {
        TabManager.newTab(url, incognito = incognito)
        startActivity(Intent(this, MainActivity::class.java))
    }

    /**
     * A link tapped in the document (3.0, also for e-books, whose links did nothing): web pages in
     * a new tab of the browser; e-mail, phone and map links to the phone's own apps; nothing else.
     */
    fun openLink(url: String) {
        val uri = Uri.parse(url)
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> openInBrowser(url)
            "mailto", "tel", "sms", "smsto", "geo" -> try {
                val action = if (uri.scheme.equals("mailto", ignoreCase = true)) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
                startActivity(Intent(action, uri))
            } catch (_: ActivityNotFoundException) {
                Messages.show(this, "No app on your phone can open this link")
            } catch (_: SecurityException) {
                Messages.show(this, "No app on your phone can open this link")
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ the page's questions (3.0)

    /**
     * What the viewer page asks through the engine, as Swift's dialogs on this screen: a list of a
     * PDF form (select), a date or time field, a colour. The viewer's own questions (a PDF's password,
     * Go to page, Details) are part of the page; plain alerts and questions are answered here too.
     * (The browser's dialogs, ui/Prompts, belong to its tabs.)
     */
    private val prompts = object : PromptDelegate {
        private fun builder(title: String?) = MaterialAlertDialogBuilder(this@DocumentActivity)
            .setBackground(Palette.dialogBackground(this@DocumentActivity)).apply { if (!title.isNullOrBlank()) setTitle(title) }

        /** Shows the dialog [build] makes; a dialog closed any other way answers "dismissed". */
        private fun <T : BasePrompt> ask(prompt: T, build: (GeckoResult<PromptResponse>) -> AlertDialog): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            if (isFinishing || isDestroyed) {
                result.complete(prompt.dismiss())
                return result
            }
            val dialog = build(result)
            prompt.setDelegate(object : PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: BasePrompt) = dialog.dismiss()
            })
            dialog.setOnDismissListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
            dialog.showImmersive()
            return result
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> = ask(prompt) { result ->
            builder(prompt.title).setMessage(prompt.message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }.create()
        }

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> = ask(prompt) { result ->
            builder(prompt.title).setMessage(prompt.message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .create()
        }

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> = ask(prompt) { result ->
            val input = EditText(this@DocumentActivity).apply {
                setText(prompt.defaultValue ?: "")
                setSingleLine()
                Ui.field(this)
            }
            builder(prompt.title).setMessage(prompt.message).setView(padded(input))
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                .setNegativeButton(android.R.string.cancel, null).create()
        }

        override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> = ask(prompt) { result ->
            val rows = ArrayList<Pair<ChoicePrompt.Choice?, String>>()
            fun flatten(list: Array<ChoicePrompt.Choice>, indent: String) {
                for (c in list) {
                    if (c.separator) continue
                    val children = c.items
                    if (children != null) {
                        // A group's name, not a choice.
                        rows += null to c.label
                        flatten(children, "$indent    ")
                    } else {
                        rows += c to indent + c.label
                    }
                }
            }
            flatten(prompt.choices, "")
            val labels = rows.map { it.second }.toTypedArray()
            val usable = { i: Int -> rows[i].first?.disabled == false }
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(rows.size) { rows[it].first?.selected == true }
                builder(prompt.title)
                    .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on && usable(which) }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.complete(prompt.confirm(rows.filterIndexed { i, _ -> checked[i] }.mapNotNull { it.first }.toTypedArray()))
                    }
                    .setNegativeButton(android.R.string.cancel, null).create()
            } else {
                val adapter = object : ArrayAdapter<String>(this@DocumentActivity, android.R.layout.simple_list_item_single_choice, labels) {
                    override fun isEnabled(position: Int) = usable(position)
                    override fun areAllItemsEnabled() = false
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        super.getView(position, convertView, parent).also { it.alpha = if (usable(position)) 1f else Ui.DISABLED_ALPHA }
                }
                builder(prompt.title)
                    .setSingleChoiceItems(adapter, rows.indexOfFirst { it.first?.selected == true }) { d, which ->
                        rows[which].first?.takeIf { usable(which) }?.let {
                            result.complete(prompt.confirm(it))
                            d.dismiss()
                        }
                    }
                    .create()
            }
        }

        override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> = ask(prompt) { result ->
            val colors = (prompt.predefinedValues?.toList() ?: emptyList()).ifEmpty { COLORS }
            val input = EditText(this@DocumentActivity).apply {
                setText(prompt.defaultValue ?: "#000000")
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                Ui.field(this)
            }
            val adapter = object : ArrayAdapter<String>(this@DocumentActivity, android.R.layout.simple_list_item_1, colors) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    val c = runCatching { Color.parseColor(colors[position]) }.getOrDefault(Color.GRAY)
                    v.setBackgroundColor(c)
                    v.setTextColor(if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE)
                    return v
                }
            }
            builder(prompt.title ?: "Choose a colour")
                .setAdapter(adapter) { _, which -> result.complete(prompt.confirm(colors[which])) }
                .setView(padded(input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }.lowercase(Locale.ROOT)
                    if (Regex("#[0-9a-f]{6}").matches(v)) result.complete(prompt.confirm(v)) else result.complete(prompt.dismiss())
                }
                .setNegativeButton(android.R.string.cancel, null).create()
        }

        override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            if (isFinishing || isDestroyed) return GeckoResult.fromValue(prompt.dismiss())
            val cal = Calendar.getInstance()
            dateInto(cal, prompt.defaultValue, prompt.type)
            val answer = { value: String? -> if (!prompt.isComplete) result.complete(if (value == null) prompt.dismiss() else prompt.confirm(value)) }
            fun time(then: (Int, Int) -> String) {
                TimePickerDialog(this@DocumentActivity, { _, h, m -> answer(then(h, m)) }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
                    .apply { setOnCancelListener { answer(null) } }.showImmersive()
            }
            if (prompt.type == DateTimePrompt.Type.TIME) {
                time { h, m -> String.format(Locale.US, "%02d:%02d", h, m) }
                return result
            }
            DatePickerDialog(this@DocumentActivity, { _, y, mo, d ->
                cal.set(y, mo, d)
                when (prompt.type) {
                    DateTimePrompt.Type.MONTH -> answer(String.format(Locale.US, "%04d-%02d", y, mo + 1))
                    DateTimePrompt.Type.WEEK -> {
                        cal.minimalDaysInFirstWeek = 4
                        cal.firstDayOfWeek = Calendar.MONDAY
                        answer(String.format(Locale.US, "%04d-W%02d", cal.getWeekYear(), cal.get(Calendar.WEEK_OF_YEAR)))
                    }
                    DateTimePrompt.Type.DATETIME_LOCAL -> time { h, m -> String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d", y, mo + 1, d, h, m) }
                    else -> answer(String.format(Locale.US, "%04d-%02d-%02d", y, mo + 1, d))
                }
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .apply { setOnCancelListener { answer(null) } }.showImmersive()
            return result
        }

        // Nothing in the viewer uploads files or opens windows.
        override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.dismiss())

        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    /** A form field's date or time as it was, so the picker starts there. */
    private fun dateInto(cal: Calendar, value: String?, type: Int) {
        if (value.isNullOrBlank()) return
        try {
            if (type == DateTimePrompt.Type.TIME) {
                val p = value.split(":")
                cal.set(Calendar.HOUR_OF_DAY, p[0].toInt())
                cal.set(Calendar.MINUTE, p[1].toInt())
                return
            }
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
        } catch (_: Exception) {
        }
    }

    private fun padded(v: View): View = LinearLayout(this).apply {
        val pad = Ui.dp(this@DocumentActivity, 24)
        setPadding(pad, pad / 2, pad, 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ Back

    private fun back() {
        when {
            finding -> closeFind()
            // A panel, menu or slideshow of the page: the page's own history step closes it.
            canGoBack -> session?.goBack()
            else -> close()
        }
    }

    /** Leaves the document: to the screen it was opened from, or out of Swift for another app. */
    private fun close() {
        if (leaveSwift) moveTaskToBack(true)
        finish()
    }

    /** What the viewer page asks of this screen (Docs routes it here for its tab). */
    fun onPageAction(action: String) {
        when (action) {
            "find" -> openFind()
            "close" -> close()
            // 2.9: documents are found in Files.
            "documents" -> {
                FilesActivity.open(this)
                finish()
            }
        }
    }

    // ------------------------------------------------------------------ find (Word, text and the rest)

    /** Over the title bar and as tall, with Swift's filled search box: the page's own find bar (PDFs, sheets) looks the same. */
    private fun buildFindBar() {
        val text = Palette.color(this, R.color.text_primary)
        val muted = Palette.color(this, R.color.text_secondary)
        findText = EditText(this).apply {
            hint = "Find in document"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            Ui.searchBox(this)
            // 3.0.5: the cursor in the accent chosen (this bar isn't recoloured by Palette.paint).
            Palette.cursor(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = find(s?.toString().orEmpty(), backwards = false, again = false)
            })
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    find(this.text.toString(), backwards = false, again = true)
                    true
                } else {
                    false
                }
            }
        }
        findCount = Ui.steadyFigures(TextView(this).apply {
            setTextColor(muted)
            Ui.size(this, Ui.Type.CAPTION, keepFont = true)
            setPadding(Ui.dp(this@DocumentActivity, 4), 0, Ui.dp(this@DocumentActivity, 4), 0)
        })
        fun button(icon: Int, label: String, click: () -> Unit) = ImageButton(this, null, 0, R.style.Swift_ToolbarButton).apply {
            setImageDrawable(Ui.icon(this@DocumentActivity, icon, text))
            contentDescription = label
            setOnClickListener { click() }
        }
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.color(this@DocumentActivity, R.color.settings_bg))
            visibility = View.GONE
            val size = Ui.dp(this@DocumentActivity, 44)
            addView(findText, LinearLayout.LayoutParams(0, Ui.dp(this@DocumentActivity, Ui.SEARCH_HEIGHT), 1f).apply { marginStart = Ui.dp(this@DocumentActivity, 8) })
            addView(findCount)
            addView(button(R.drawable.ic_up, "Previous") { find(findText.text.toString(), backwards = true, again = true) }, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_down, "Next") { find(findText.text.toString(), backwards = false, again = true) }, LinearLayout.LayoutParams(size, size))
            addView(button(R.drawable.ic_close, "Close") { closeFind() }, LinearLayout.LayoutParams(size, size))
        }
        root.addView(findBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.BAR_HEIGHT), Gravity.TOP))
    }

    private fun openFind() {
        finding = true
        Motion.fadeIn(findBar, fromDy = -8, duration = Motion.SHORT)
        findText.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(findText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        finding = false
        Motion.fadeOut(findBar, toDy = -8, duration = Motion.PAGE_OUT_MS)
        findText.setText("")
        findCount.text = ""
        session?.finder?.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(findText.windowToken, 0)
    }

    private fun find(text: String, backwards: Boolean, again: Boolean) {
        val finder = session?.finder ?: return
        if (text.isEmpty()) {
            finder.clear()
            findCount.text = ""
            return
        }
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DIM_PAGE
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0
        finder.find(if (again) null else text, flags).accept { r ->
            if (r == null || isFinishing) return@accept
            findCount.text = if (r.found) "${r.current}/${r.total}" else "0/0"
        }
    }

    // ------------------------------------------------------------------ storage (saving to Downloads)

    private var afterStorage: (() -> Unit)? = null

    private val storageAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        val run = afterStorage
        afterStorage = null
        if (granted) run?.invoke() else Messages.show(this, "Swift needs storage access to save to Downloads")
    }

    /** [action] once Swift may write to Downloads (asked the first time). */
    fun withStorage(action: () -> Unit) {
        if (com.swiftbrowser.download.Downloads.hasStoragePermission(this)) {
            action()
        } else {
            afterStorage = action
            storageAsk.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.apply(this)
    }

    override fun onResume() {
        super.onResume()
        NightLight.attach(this)
        session?.setActive(true)
    }

    override fun onPause() {
        session?.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        screens.removeAll { it.get().let { a -> a == null || a === this } }
        tab?.let { t ->
            hosts.remove(t)
            Helper.detachTab(t)
            t.session = null
        }
        session?.let { s ->
            gecko.releaseSession()
            s.close()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "doc_id"
        const val EXTRA_INCOGNITO = "incognito"
        const val EXTRA_LEAVE = "leave_swift"

        /** Ids of the viewer's tabs: below zero, so they never meet a real tab's. */
        private var nextTabId = -1L

        private val hosts = HashMap<Tab, WeakReference<DocumentActivity>>()

        /** Every document screen open (3.0), for closing those showing Private's documents when it locks. */
        private val screens = ArrayList<WeakReference<DocumentActivity>>()

        /** Other apps asked about their files, and shortcuts' documents looked up, off the main thread (3.0). */
        private val lookups = com.swiftbrowser.core.Background.single("doc-lookups")

        private val COLORS = listOf(
            "#000000", "#ffffff", "#f44336", "#e91e63", "#9c27b0", "#3f51b5", "#2196f3", "#03a9f4",
            "#009688", "#4caf50", "#8bc34a", "#ffeb3b", "#ffc107", "#ff9800", "#795548", "#9e9e9e",
        )

        /** The screen showing [tab]'s document, when it is one of these (not a tab of the browser). */
        fun hostOf(tab: Tab): DocumentActivity? = hosts[tab]?.get()

        /** The document [tab]'s screen was opened for (3.0: its viewer page may ask for that one only). */
        fun docIdOf(tab: Tab): String? = hostOf(tab)?.docId

        /** Closes the screens showing documents [ids] (3.0: Private locked). */
        fun closeShowing(ids: Set<String>) {
            for (a in screens.mapNotNull { it.get() }) {
                if (a.docId in ids && !a.isFinishing) a.finish()
            }
        }

        /**
         * Opens document [id] over [from]. [leaveSwift]: Back goes out of Swift (another app, a shortcut).
         * [secure] (3.0): the screen stays out of screenshots (Private's files; AppFiles.EXTRA_SECURE).
         * Always [DocumentScreen] itself: what comes through the DocumentActivity alias is another app's.
         */
        fun open(from: Context, id: String, incognito: Boolean = false, leaveSwift: Boolean = false, secure: Boolean = false) {
            val i = Intent(from, DocumentScreen::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_INCOGNITO, incognito)
                .putExtra(EXTRA_LEAVE, leaveSwift)
            if (secure) i.putExtra(AppFiles.EXTRA_SECURE, true)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }
    }
}

/**
 * The document screen itself (3.0): not open to other apps. Swift opens it directly; other apps and
 * home screen shortcuts reach it through "DocumentActivity", an exported alias in the manifest with
 * the screen's old name, and [DocumentActivity.start] checks what those ask for.
 */
class DocumentScreen : DocumentActivity()
