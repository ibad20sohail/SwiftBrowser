package com.swiftbrowser.ui

import android.app.Activity
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Clearing
import com.swiftbrowser.core.Profiles
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.tabs.TabManager

/**
 * Clear browsing data (2.6): for the last hour, day, week, four weeks or all time, and what — each
 * with what it means. The same list sets what Quit clears.
 */
object ClearData {
    private const val PREF_RANGE = "clear_range"
    private const val PREF_WHAT = "clear_what"
    private const val PREF_QUIT = "quit_clear"

    /** Open tabs: only for Quit (closing Swift for good). */
    private const val TABS = "TABS"

    private fun saved(key: String, default: Set<String>): Set<String> = SwiftApp.prefs.sp.getStringSet(key, default) ?: default

    /** What Quit clears (Settings → Privacy & backup → When you quit). */
    fun quitChoices(): Set<String> = saved(PREF_QUIT, emptySet())

    /** Settings' line under When you quit. */
    fun quitSummary(): String {
        val c = quitChoices()
        val names = listOfNotNull(if (TABS in c) "Open tabs" else null) +
            Clearing.What.entries.filter { it.name in c }.map { it.label }
        return if (names.isEmpty()) "≡ → Quit closes Swift and clears nothing" else "≡ → Quit also clears: " + names.joinToString(", ").lowercase().replaceFirstChar { it.uppercase() }
    }

    /**
     * Asks for a time and what, then clears it. [preset] overrides what was ticked last time. A sheet
     * the height of the screen, so every choice can be seen.
     */
    fun show(a: Activity, preset: Set<Clearing.What>? = null, done: (() -> Unit)? = null) {
        val prefs = SwiftApp.prefs
        var range = Clearing.Range.entries.getOrNull(prefs.sp.getInt(PREF_RANGE, Clearing.Range.ALL.ordinal)) ?: Clearing.Range.ALL
        val ticked = (preset?.map { it.name }?.toSet() ?: saved(PREF_WHAT, Clearing.DEFAULTS.map { it.name }.toSet())).toMutableSet()
        val profile = Profiles.current()
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "Clear browsing data", if (Profiles.all().size > 1) "Profile: ${profile.name}" else null))
        col.addView(Sheets.label(a, "Time", top = 0))
        col.addView(Sheets.chipRow(a, Clearing.Range.entries.map { it.label }, range.ordinal) { i -> range = Clearing.Range.entries[i] })
        col.addView(Sheets.label(a, "What"))
        val list = column(a)
        for (w in Clearing.What.entries) {
            list.addView(row(a, w.label, hint(w), w.name in ticked) { on -> if (on) ticked += w.name else ticked -= w.name })
        }
        col.addView(list)
        lateinit var sheet: com.google.android.material.bottomsheet.BottomSheetDialog
        col.addView(button(a, "Clear", danger = true) {
            val what = ticked.mapNotNull { n -> Clearing.What.entries.firstOrNull { it.name == n } }.toSet()
            if (what.isEmpty()) {
                Messages.show(a, "Tick what to clear")
                return@button
            }
            sheet.dismiss()
            // What was ticked is remembered for next time, unless the list came preset (History).
            prefs.sp.edit().putInt(PREF_RANGE, range.ordinal).apply { if (preset == null) putStringSet(PREF_WHAT, ticked) }.apply()
            Clearing.clear(a, profile.id, range, what) {
                Messages.show(a, if (range == Clearing.Range.ALL) "Cleared" else "Cleared: ${range.label.lowercase()}")
                done?.invoke()
            }
        })
        sheet = Sheets.sheet(a, col)
        sheet.showImmersive()
    }

    /** A wide button at the bottom of a sheet; [danger] in red, for what can't be undone. */
    private fun button(a: Activity, text: String, danger: Boolean = false, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(a).apply {
            this.text = text
            if (danger) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.danger(a))
                setTextColor(android.graphics.Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)).apply {
                setMargins(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 20), 0)
            }
            setOnClickListener { onClick() }
        }

    private fun hint(w: Clearing.What): String = when (w) {
        Clearing.What.COOKIES -> "Signs you out of the sites (for a time: the sites you visited then)"
        Clearing.What.CACHE -> "Pages load them again when needed"
        else -> w.hint
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
        col.addView(button(a, "Done") {
            SwiftApp.prefs.sp.edit().putStringSet(PREF_QUIT, ticked).apply()
            sheet.dismiss()
            done()
        })
        sheet = Sheets.sheet(a, col)
        sheet.showImmersive()
    }

    /** ≡ → Quit: clears what was chosen, then closes Swift completely (downloads and playing stop). */
    fun quit(a: Activity) {
        val choices = quitChoices()
        val running = Downloads.activeCount()
        val go = {
            val what = choices.mapNotNull { n -> Clearing.What.entries.firstOrNull { it.name == n } }.toSet()
            TabManager.closeTabs(TabManager.incognitoTabs())
            if (TABS in choices) TabManager.closeTabs(TabManager.profileTabs())
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
            setBackgroundResource(RowHolder.selectableBackground(a))
            setOnClickListener { box.isChecked = !box.isChecked }
            contentDescription = "$title, $hint"
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
