package com.swiftbrowser.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swiftbrowser.R
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.Diagnostics
import com.swiftbrowser.core.L

/**
 * Sending a problem report (3.0): a sheet shows what the report holds before it goes anywhere, and
 * the names of the sites visited stay out of it unless "Include the sites I visited" is turned on.
 * The report is put together and written off the main thread (three days of logs can be several MB).
 */
object ReportUi {
    private val io = Background.single("report")

    fun show(a: Activity) {
        val extra = Diagnostics.reportExtra(a)
        val waiting = Messages.sticky(a, "Getting the problem report ready…")
        io.execute {
            val preview = try {
                Diagnostics.preview(extra)
            } catch (e: Exception) {
                L.w("The problem report couldn't be put together", e)
                null
            }
            a.runOnUiThread {
                waiting?.dismiss()
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (preview == null) Messages.show(a, "Swift couldn't put the report together") else sheet(a, extra, preview)
            }
        }
    }

    private fun sheet(a: Activity, extra: String, p: Diagnostics.Preview) {
        val col = Sheets.column(a)
        col.addView(Sheets.header(
            a, "Send a problem report",
            "What Swift noted about how it ran: crashes, freezes, memory, and its log of the last 3 days. Nothing is sent until you pick where to send it.",
        ))
        var withSites = false
        val text = Ui.text(a, Ui.Type.CAPTION, "").apply {
            typeface = Typeface.MONOSPACE
            Ui.size(this, Ui.Type.SMALL, keepFont = true)
            setTextIsSelectable(false)
            background = Ui.shape(a, Palette.color(a, R.color.surface_alt), 12)
            val p8 = Ui.dp(a, 10)
            setPadding(p8, p8, p8, p8)
        }
        fun render() {
            val shown = p.head.size + p.tail.size
            text.text = buildString {
                p.head.forEach { append(Diagnostics.clean(it, withSites)).append('\n') }
                if (p.lines > shown) {
                    val more = p.lines - shown
                    append("\n… ${java.text.NumberFormat.getIntegerInstance().format(more)} more ${if (more == 1) "line" else "lines"} of the log …\n\n")
                }
                p.tail.forEach { append(Diagnostics.clean(it, withSites)).append('\n') }
            }.trimEnd()
        }
        // Off at first: sites' names show as "a site".
        val sw = SlimSwitch(a)
        val hint = Ui.text(a, Ui.Type.CAPTION, "")
        fun hintText() {
            hint.text = if (withSites) "Their names are in it (addresses only up to the site)" else "Off: their names show as \"a site\""
        }
        hintText()
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 60)
            setPadding(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 20), Ui.dp(a, 6))
            setBackgroundResource(RowHolder.selectableBackground(a))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, Ui.Type.BODY, "Include the sites I visited"))
                addView(hint)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw, LinearLayout.LayoutParams(Ui.dp(a, 46), Ui.dp(a, 32)))
            accessibilityDelegate = SwitchRowAccessibility(sw)
            setOnClickListener {
                withSites = !withSites
                sw.setChecked(withSites, animate = true)
                hintText()
                render()
            }
        })
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        col.addView(Sheets.wideButton(a, "Send…") {
            dialog.dismiss()
            send(a, extra, withSites)
        })
        col.addView(Sheets.label(a, "What it holds"))
        col.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16))
        })
        text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        render()
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    private fun send(a: Activity, extra: String, withSites: Boolean) {
        val waiting = Messages.sticky(a, "Getting the problem report ready…")
        io.execute {
            val file = try {
                Diagnostics.writeReport(a, extra, withSites)
            } catch (e: Exception) {
                L.w("The problem report couldn't be written", e)
                null
            }
            a.runOnUiThread {
                waiting?.dismiss()
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (file == null) Messages.show(a, "Swift couldn't write the report. Is the phone full?") else Diagnostics.shareFile(a, file)
            }
        }
    }
}
