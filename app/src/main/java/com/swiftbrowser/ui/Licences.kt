package com.swiftbrowser.ui

import android.app.Activity
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.swiftbrowser.R

/**
 * About Swift → Open-source licences (3.0): every project Swift is built from (LicencesData), with
 * its licence and home page, and the notices they ship. Tapping a link opens it in a new tab.
 */
object Licences {
    /** [open] opens an address in a new tab (Settings closes for it). */
    fun show(a: Activity, open: (String) -> Unit) {
        val col = Sheets.column(a)
        col.addView(Sheets.header(
            a, "Open-source licences",
            "Swift is built from these projects, under their licences. Tap a licence or a home page to open it in a new tab.",
        ))
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        val go: (String) -> Unit = { url ->
            dialog.dismiss()
            open(url)
        }
        // 3.0.5: no lines between them: each is a short block of text with space around it.
        for (c in LicencesData.components) col.addView(entry(a, c, go))
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    private fun entry(a: Activity, c: LicencesData.Component, go: (String) -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), Ui.dp(a, 12))
        addView(Ui.text(a, Ui.Type.BODY, listOf(c.name, c.version).filter { it.isNotBlank() }.joinToString(" ")).apply {
            setTypeface(Ui.medium(Ui.Type.BODY))
        })
        addView(link(a, c.licence, "Licence: ${c.licence}") { go(c.licenceUrl) })
        addView(link(a, c.home.substringAfter("://").trimEnd('/'), "Home page of ${c.name}") { go(c.home) })
        c.note?.let { addView(withLinks(a, it, go)) }
        c.notice?.let { notice ->
            val text = Ui.text(a, Ui.Type.CAPTION, notice).apply {
                Ui.size(this, Ui.Type.SMALL)
                visibility = View.GONE
                background = Ui.shape(a, Palette.color(a, R.color.surface_alt), 12)
                val p = Ui.dp(a, 10)
                setPadding(p, p, p, p)
            }
            val toggle = link(a, "Show its notice", "Show the notice of ${c.name}") {}
            var open = false
            toggle.setOnClickListener {
                open = !open
                Motion.reveal(text, open)
                Motion.swapText(toggle, if (open) "Hide its notice" else "Show its notice")
            }
            addView(toggle)
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(a, 4) })
        }
    }

    /** Words in the accent colour that do something when tapped (48 dp high, for fingers). */
    private fun link(a: Activity, text: String, description: String, onClick: () -> Unit): TextView =
        Ui.text(a, Ui.Type.BODY_SECONDARY, text, Ui.accent(a)).apply {
            minHeight = Ui.dp(a, 48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = Ui.ripple(a, null, 8)
            contentDescription = "$description, opens in a new tab"
            setOnClickListener { onClick() }
        }

    /** A note whose addresses can be tapped (opened in a new tab, not in another app). */
    private fun withLinks(a: Activity, note: String, go: (String) -> Unit): TextView {
        val s = SpannableString(note)
        for (m in URL.findAll(note)) {
            val url = m.value.trimEnd('.', ',', ')')
            s.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = go(url)
            }, m.range.first, m.range.first + url.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return Ui.text(a, Ui.Type.CAPTION, s).apply {
            setPadding(0, Ui.dp(a, 4), 0, 0)
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(Ui.accent(a))
        }
    }

    private val URL = Regex("https?://[^\\s]+")
}
