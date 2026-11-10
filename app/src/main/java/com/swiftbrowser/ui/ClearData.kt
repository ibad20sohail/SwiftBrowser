package com.swiftbrowser.ui

import android.app.Activity
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.ClearSizes
import com.swiftbrowser.core.Clearing
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.tabs.TabManager

/**
 * Clear browsing data (2.6): for the last hour, day, week, four weeks or all time (2.8: or chosen
 * dates), and what — each with what it means and (2.8) how much it takes away. The same list sets
 * what Quit clears.
 */
object ClearData {
    private const val PREF_RANGE = "clear_range"
    private const val PREF_WHAT = "clear_what"
    private const val PREF_QUIT = "quit_clear"

    /** Open tabs: only for Quit (closing Swift for good). */
    private const val TABS = "TABS"

    private fun saved(key: String, default: Set<String>): Set<String> = SwiftApp.prefs.sp.getStringSet(key, default) ?: default

    /** What Quit clears (Settings → Storage & data → When you quit). */
    fun quitChoices(): Set<String> = saved(PREF_QUIT, emptySet())

    /** Settings' line under When you quit. */
    fun quitSummary(): String {
        val c = quitChoices()
        val names = listOfNotNull(if (TABS in c) "Open tabs" else null) +
            Clearing.What.entries.filter { it.name in c }.map { it.label }
        return if (names.isEmpty()) "≡ → Quit closes Swift and clears nothing" else "≡ → Quit also clears: " + names.joinToString(", ").lowercase().replaceFirstChar { it.uppercase() }
    }

    /** 2.8: the days chosen last ("20261012-20261020"), kept like the other choices. */
    private const val PREF_DAYS = "clear_days"

    /** 2.8: the chip after the times, "Choose dates…"; [PREF_RANGE] holds this number when it was the choice. */
    private val CHOSEN_DAYS = Clearing.Range.entries.size

    private fun savedDays(): IntArray? {
        val parts = SwiftApp.prefs.sp.getString(PREF_DAYS, null)?.split('-') ?: return null
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val last = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (first in 19000101..last) intArrayOf(first, last) else null
    }

    /**
     * Asks for a time and what, then clears it. [preset] overrides what was ticked last time. A sheet
     * the height of the screen, so every choice can be seen. 2.8: or chosen dates; each row shows
     * how much it takes away (worked out off the main thread, again when the time changes), the
     * Clear button their total, and the downloaded files of that time can go too.
     */
    fun show(a: Activity, preset: Set<Clearing.What>? = null, done: (() -> Unit)? = null) {
        val prefs = SwiftApp.prefs
        var days = savedDays()
        // 3.0: the time chosen last; the last hour the first time (it opened on all time, one tap from clearing everything).
        var choice = prefs.sp.getInt(PREF_RANGE, Clearing.Range.HOUR.ordinal).let {
            if (it in Clearing.Range.entries.indices || (it == CHOSEN_DAYS && days != null)) it else Clearing.Range.HOUR.ordinal
        }
        val profiles = Profiles.all().size
        val ticked = (preset?.map { it.name }?.toSet() ?: saved(PREF_WHAT, Clearing.DEFAULTS.map { it.name }.toSet())).toMutableSet()
        val profile = Profiles.current()
        fun span(): Clearing.Span {
            val d = days
            return if (choice == CHOSEN_DAYS && d != null) Clearing.Span.days(d[0], d[1]) else Clearing.Span.of(Clearing.Range.entries[choice])
        }

        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "Clear browsing data", if (Profiles.all().size > 1) "Profile: ${profile.name}" else null))
        // A normal gap above the time (2.8: the chips looked cut off right under the header).
        col.addView(Sheets.label(a, "Time"))
        val chooseLabel = "Choose dates…"
        val labels = Clearing.Range.entries.map { it.label } + (days?.let { ChooseDates.label(it[0], it[1]) } ?: chooseLabel)
        lateinit var remeasure: () -> Unit
        lateinit var chips: com.google.android.material.chip.ChipGroup
        val chipRow = Sheets.chipRow(a, labels, choice) { i ->
            if (i != CHOSEN_DAYS) {
                choice = i
                remeasure()
                return@chipRow
            }
            // The calendar: chosen days, or back to the chip before when left without.
            val before = choice
            val d = days
            ChooseDates.show(a, d?.get(0), d?.get(1), onChosen = { first, last ->
                days = intArrayOf(first, last)
                choice = CHOSEN_DAYS
                (chips.getChildAt(CHOSEN_DAYS) as? TextView)?.text = ChooseDates.label(first, last)
                remeasure()
            }, onCancel = {
                if (before != CHOSEN_DAYS) chips.getChildAt(before)?.let { chips.check(it.id) }
            })
        }
        // Room above and below the chips, so their tops and shadows aren't cut off by the row (2.8).
        (chipRow as ViewGroup).apply {
            setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6))
            clipToPadding = false
        }
        chips = chipRow.getChildAt(0) as com.google.android.material.chip.ChipGroup
        col.addView(chipRow)
        col.addView(Sheets.label(a, "What"))

        // Each row with what it takes away, worked out for the time chosen.
        val sizes = HashMap<Clearing.What, ClearSizes.Measure>()
        var files: ClearSizes.Files? = null
        var deleteFiles = false
        val rows = HashMap<Clearing.What, SizedRow>()
        lateinit var clearButton: com.google.android.material.button.MaterialButton
        lateinit var filesRow: SizedRow

        /** The bytes of what is ticked ("about …"), or null while a ticked one is being worked out; the lists add no bytes. */
        fun total(): String? {
            var total = 0L
            var about = false
            for (w in BYTES) {
                if (w.name !in ticked) continue
                val m = sizes[w] ?: return null
                total += m.bytes
                about = about || m.about
            }
            val f = files
            if (deleteFiles && f != null) total += f.bytes
            return if (total > 0) (if (about) "about " else "") + UrlUtils.formatBytes(total) else null
        }

        fun showTotal() {
            Motion.swapText(clearButton, total()?.let { "Clear $it" } ?: "Clear")
        }

        fun showSize(w: Clearing.What) {
            rows[w]?.setSize(sizeText(w, sizes[w]))
        }

        val list = column(a)
        for (w in Clearing.What.entries) {
            val r = sizedRow(a, w.label, hint(w, span().isAll, profiles), w.name in ticked) { on ->
                if (on) ticked += w.name else ticked -= w.name
                // The files can only go with their entries.
                if (w == Clearing.What.DOWNLOADS && !on && deleteFiles) filesRow.box.isChecked = false
                showTotal()
            }
            rows[w] = r
            list.addView(r.view)
            if (w == Clearing.What.DOWNLOADS) {
                // 2.8: under the downloads list, when that time has downloaded files still on the phone.
                filesRow = sizedRow(a, "Delete the downloaded files too", "Only the files Swift downloaded then", false, indent = true) { on ->
                    deleteFiles = on
                    if (on && Clearing.What.DOWNLOADS.name !in ticked) rows[Clearing.What.DOWNLOADS]?.box?.isChecked = true
                    rows[Clearing.What.DOWNLOADS]?.setHint(if (on) "The files are deleted too" else hint(Clearing.What.DOWNLOADS, span().isAll, profiles))
                    showTotal()
                }
                filesRow.view.visibility = View.GONE
                list.addView(filesRow.view)
            }
        }
        col.addView(list)

        remeasure = {
            sizes.clear()
            files = null
            if (deleteFiles) filesRow.box.isChecked = false
            Motion.reveal(filesRow.view, false)
            // 3.0: for a time, cookies go for those sites in every profile (the engine clears a site for all of them).
            rows[Clearing.What.COOKIES]?.setHint(hint(Clearing.What.COOKIES, span().isAll, profiles))
            for (w in Clearing.What.entries) showSize(w)
            showTotal()
            ClearSizes.measure(a, profile.id, span(), onPart = { w, m ->
                sizes[w] = m
                showSize(w)
                showTotal()
            }, onFiles = { f ->
                files = f
                if (f.ids.isNotEmpty()) {
                    val n = f.ids.size
                    filesRow.setSize((if (n == 1) "1 file" else "${number(n)} files") + " · " + UrlUtils.formatBytes(f.bytes))
                    Motion.reveal(filesRow.view, true)
                }
            })
        }

        lateinit var sheet: com.google.android.material.bottomsheet.BottomSheetDialog
        clearButton = Sheets.wideButton(a, "Clear", danger = true) {
            val what = ticked.mapNotNull { n -> Clearing.What.entries.firstOrNull { it.name == n } }.toSet()
            if (what.isEmpty()) {
                Messages.show(a, "Tick what to clear")
                return@wideButton
            }
            val span = span()
            val chosen = choice
            val d = days
            val f = files?.takeIf { deleteFiles && Clearing.What.DOWNLOADS in what }
            // 3.0.7: the room it gave back, as the button said it.
            val freed = total()
            val go = {
                sheet.dismiss()
                // What was ticked is remembered for next time, unless the list came preset (History).
                prefs.sp.edit().putInt(PREF_RANGE, chosen).apply {
                    if (chosen == CHOSEN_DAYS && d != null) putString(PREF_DAYS, "${d[0]}-${d[1]}")
                    if (preset == null) putStringSet(PREF_WHAT, ticked)
                }.apply()
                Clearing.clear(a, profile.id, span, what, f?.ids.orEmpty()) {
                    val time = when {
                        chosen == CHOSEN_DAYS && d != null -> ": " + ChooseDates.label(d[0], d[1])
                        chosen == Clearing.Range.ALL.ordinal -> ""
                        else -> ": " + Clearing.Range.entries[chosen].label.lowercase()
                    }
                    val n = f?.ids?.size ?: 0
                    Messages.show(a, "Cleared$time" + (freed?.let { " · $it freed" } ?: "") + if (n == 1) ". 1 file deleted" else if (n > 1) ". ${number(n)} files deleted" else "")
                    done?.invoke()
                }
            }
            val n = f?.ids?.size ?: 0
            when {
                // 3.0: everything goes only after a question (it took one tap, also from History's Clear).
                span.isAll -> Dialogs.confirm(
                    a, "Clear everything from all time?",
                    "All of it goes: " + what.sortedBy { it.ordinal }.joinToString(", ") { it.label.lowercase() } +
                        (if (profiles > 1) ", in the profile ${profile.name}" else "") +
                        (if (n > 0) "; and ${if (n == 1) "1 downloaded file" else "${number(n)} downloaded files"}" else "") +
                        ". This can't be undone.",
                    "Clear everything", danger = true,
                ) { go() }
                // Files can't come back: asked first.
                n > 0 -> Dialogs.confirm(
                    a, "Delete ${if (n == 1) "1 file" else "${number(n)} files"} (${UrlUtils.formatBytes(f!!.bytes)})?",
                    "This can't be undone.", "Delete", danger = true,
                ) { go() }
                else -> go()
            }
        }
        Ui.steadyFigures(clearButton)
        // The main button stays in sight, however long the list is.
        sheet = Sheets.sheet(a, col, footer = clearButton)
        // Closed: whatever is still being worked out stops.
        sheet.setOnDismissListener { ClearSizes.cancel() }
        sheet.showImmersive()
        remeasure()
    }

    /** The rows that take away bytes (the others are lists, shown by how many entries they lose). */
    private val BYTES = listOf(Clearing.What.HISTORY, Clearing.What.COOKIES, Clearing.What.CACHE)

    private fun number(n: Number): String = java.text.NumberFormat.getIntegerInstance().format(n)

    /** What a row takes away: "3,410 pages · 1.2 MB", "45.3 MB", "about 12 MB", "12 entries", "None". */
    private fun sizeText(w: Clearing.What, m: ClearSizes.Measure?): String {
        if (m == null) return "Working out…"
        if (!m.known) return "Not known"
        if (w == Clearing.What.HISTORY) {
            return when {
                m.count > 0 -> (if (m.count == 1) "1 page" else "${number(m.count)} pages") + " · " + UrlUtils.formatBytes(m.bytes)
                m.bytes > 0 -> UrlUtils.formatBytes(m.bytes)
                else -> "None"
            }
        }
        if (m.bytes >= 0) return if (m.bytes == 0L) "None" else (if (m.about) "about " else "") + UrlUtils.formatBytes(m.bytes)
        if (m.count == 0) return "None"
        val (one, many) = when (w) {
            Clearing.What.PLACES -> "place" to "places"
            Clearing.What.SITE_SETTINGS -> "site" to "sites"
            Clearing.What.PASSWORDS -> "password" to "passwords"
            else -> "entry" to "entries"
        }
        return if (m.count == 1) "1 $one" else "${number(m.count)} $many"
    }

    /**
     * What a row means for the time chosen ([all]: all time). 3.0: for a time, cookies go for the
     * sites visited then in every profile, as the engine clears a site for all of them: said so
     * when there are several profiles (the sheet names one).
     */
    private fun hint(w: Clearing.What, all: Boolean = true, profiles: Int = 1): String = when (w) {
        Clearing.What.COOKIES -> when {
            all -> "Signs you out of every site" + if (profiles > 1) " in this profile" else ""
            profiles > 1 -> "Signs you out of the sites visited then, in all your profiles"
            else -> "Signs you out of the sites visited then"
        }
        Clearing.What.CACHE -> "Copies of pages' pictures and files; pages load them again when needed"
        else -> w.hint
    }

    /** 3.0: TalkBack says a row is a tick box and whether it is ticked (it only read the row's text). */
    private class TickAccessibility(private val box: CheckBox) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = CheckBox::class.java.name
            info.isCheckable = true
            // The only way on Android 7 (the newer one taking a number is Android 16's).
            @Suppress("DEPRECATION")
            info.isChecked = box.isChecked
        }
    }

    /** Settings → When you quit: what ≡ → Quit clears (all of it), open tabs included. */
    fun chooseForQuit(a: Activity, done: () -> Unit) {
        val ticked = quitChoices().toMutableSet()
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "When you quit", "≡ → Quit closes Swift and clears what is ticked, all of it."))
        val list = column(a)
        list.addView(row(a, "Open tabs", "They close and don't come back", TABS in ticked) { on -> if (on) ticked += TABS else ticked -= TABS })
        for (w in Clearing.What.entries) {
            list.addView(row(a, w.label, hint(w), w.name in ticked) { on -> if (on) ticked += w.name else ticked -= w.name })
        }
        col.addView(list)
        lateinit var sheet: com.google.android.material.bottomsheet.BottomSheetDialog
        val doneButton = Sheets.wideButton(a, "Done") {
            SwiftApp.prefs.sp.edit().putStringSet(PREF_QUIT, ticked).apply()
            sheet.dismiss()
            done()
        }
        sheet = Sheets.sheet(a, col, footer = doneButton)
        sheet.showImmersive()
    }

    /** ≡ → Quit: clears what was chosen, then closes Swift completely (downloads and playing stop). */
    fun quit(a: Activity) {
        val choices = quitChoices()
        val running = Downloads.activeCount()
        val go = {
            val what = choices.mapNotNull { n -> Clearing.What.entries.firstOrNull { it.name == n } }.toSet()
            TabManager.closeTabs(TabManager.incognitoTabs())
            // 3.0: and they don't come back in Recently closed either, as the hint says.
            if (TABS in choices) TabManager.closeTabs(TabManager.profileTabs(), remember = false)
            TabManager.flushSaves()
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val finish = {
                a.finishAndRemoveTask()
                // The engine and services stop with the process, so nothing goes on in the background.
                main.postDelayed({ Process.killProcess(Process.myPid()) }, 600)
                Unit
            }
            // After the tab writes queued above, so closed tabs don't come back into a cleared history.
            TabManager.io.execute {
                main.post {
                    if (what.isEmpty()) finish() else Clearing.clear(a, Profiles.currentId, Clearing.Range.ALL, what) { finish() }
                }
            }
        }
        if (running > 0) {
            Dialogs.confirm(a, "Quit Swift?", "$running download${if (running == 1) "" else "s"} will stop. They can go on next time.", "Quit") { go() }
        } else {
            go()
        }
    }

    private fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 24), 0)
    }

    /** 2.8: a row of Clear browsing data: its tick, its name with what it takes away on the right (in grey), and what it means. */
    private class SizedRow(val view: View, val box: CheckBox, private val hint: TextView, private val size: TextView, private val title: String) {
        fun setSize(text: String) {
            Motion.swapText(size, text)
            view.contentDescription = "$title, $text, ${hint.text}"
        }

        fun setHint(text: String) {
            hint.text = text
            view.contentDescription = "$title, ${size.text}, $text"
        }
    }

    /** [indent]: an option of the row above (the downloaded files, under the downloads list). */
    private fun sizedRow(a: Activity, title: String, hint: String, on: Boolean, indent: Boolean = false, change: (Boolean) -> Unit): SizedRow {
        val box = CheckBox(a).apply {
            isChecked = on
            setOnCheckedChangeListener { _, c -> change(c) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = Ui.text(a, Ui.Type.CAPTION, "", lines = 1).apply {
            gravity = Gravity.END
            setPadding(Ui.dp(a, 8), 0, 0, 0)
            Ui.steadyFigures(this)
        }
        val hintView = Ui.text(a, Ui.Type.CAPTION, hint)
        val view = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, if (indent) 48 else 56)
            if (indent) setPadding(Ui.dp(a, 32), 0, 0, 0)
            background = Ui.ripple(a, null, Ui.R_XSMALL)
            setOnClickListener { box.isChecked = !box.isChecked }
            accessibilityDelegate = TickAccessibility(box)
            addView(box)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(a, 8), Ui.dp(a, 6), 0, Ui.dp(a, 6))
                // The name and the size on one line, on the same baseline.
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Ui.text(a, Ui.Type.BODY, title, lines = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(size, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
                addView(hintView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return SizedRow(view, box, hintView, size, title).also { it.setSize("") }
    }

    private fun row(a: Activity, title: String, hint: String, on: Boolean, change: (Boolean) -> Unit): View {
        val box = CheckBox(a).apply {
            isChecked = on
            setOnCheckedChangeListener { _, c -> change(c) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 56)
            background = Ui.ripple(a, null, Ui.R_XSMALL)
            setOnClickListener { box.isChecked = !box.isChecked }
            contentDescription = "$title, $hint"
            accessibilityDelegate = TickAccessibility(box)
            addView(box)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(a, 8), Ui.dp(a, 6), 0, Ui.dp(a, 6))
                addView(Ui.text(a, Ui.Type.BODY, title))
                addView(Ui.text(a, Ui.Type.CAPTION, hint))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
