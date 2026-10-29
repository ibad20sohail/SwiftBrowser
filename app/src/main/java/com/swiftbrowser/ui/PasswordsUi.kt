package com.swiftbrowser.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.core.Passwords
import com.swiftbrowser.core.UrlUtils

/**
 * Settings → Saved passwords (2.6): the sign-ins Swift fills in, by site. They show only after the
 * phone's screen lock is entered ([check]); a password stays hidden until Show is tapped.
 */
object PasswordsUi {
    private val io = com.swiftbrowser.core.Background.single("passwords")

    private fun later(a: Activity, work: () -> Unit) = a.window.decorView.post(work)

    /** The list; [changed] runs after a sign-in was deleted (Settings updates its count). */
    fun list(a: Activity, check: (() -> Unit) -> Unit, changed: () -> Unit) {
        check {
            io.execute {
                val logins = try {
                    Passwords.all(com.swiftbrowser.core.Profiles.currentId)
                } catch (e: Exception) {
                    emptyList()
                }
                later(a) { if (!a.isFinishing) showList(a, logins, changed) }
            }
        }
    }

    private fun showList(a: Activity, logins: List<Passwords.Login>, changed: () -> Unit) {
        if (logins.isEmpty()) {
            Dialogs.info(a, "Saved passwords", "None yet. When you sign in on a site, Swift offers to keep the password and fills it in next time.")
            return
        }
        val grey = Ui.textSecondary(a)
        val items = logins.map { l ->
            SpannableStringBuilder(UrlUtils.siteName(l.origin).ifBlank { l.origin }).apply {
                val start = length
                append("\n").append(l.username.ifBlank { "(no user name)" })
                setSpan(ForegroundColorSpan(grey), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.87f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(a)
            .setTitle("Saved passwords (${logins.size})")
            .setItems(items) { _, which -> details(a, logins[which], changed) }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    private fun details(a: Activity, l: Passwords.Login, changed: () -> Unit) {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 12), 0)
        }
        col.addView(field(a, "User name", l.username.ifBlank { "(none)" }, null) { copy(a, "User name", l.username) })
        val dots = "•".repeat(l.password.length.coerceIn(6, 16))
        var shown = false
        lateinit var value: TextView
        lateinit var eye: ImageButton
        eye = button(a, R.drawable.ic_visibility, "Show password") {
            shown = !shown
            value.text = if (shown) l.password else dots
            eye.setImageResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
            eye.contentDescription = if (shown) "Hide password" else "Show password"
        }
        val row = field(a, "Password", dots, eye) { copy(a, "Password", l.password) }
        value = row.findViewWithTag("value")
        col.addView(row)
        val d = MaterialAlertDialogBuilder(a)
            .setTitle(UrlUtils.siteName(l.origin).ifBlank { l.origin })
            .setView(col)
            .setPositiveButton("Close", null)
            .setNeutralButton("Delete") { _, _ -> delete(a, l, changed) }
            .showImmersive()
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setTextColor(Ui.danger(a))
    }

    private fun delete(a: Activity, l: Passwords.Login, changed: () -> Unit) {
        io.execute {
            val row = Passwords.remove(l.guid)
            later(a) {
                changed()
                Messages.show(a, "Password deleted", action = if (row != null) "Undo" else null) {
                    if (row != null) io.execute { Passwords.putBack(row); later(a) { changed() } }
                }
            }
        }
    }

    /** Settings → Never saved for: sites Swift doesn't offer to save for. Tapping one offers again. */
    fun never(a: Activity, changed: () -> Unit) {
        val sites = Passwords.neverSites()
        if (sites.isEmpty()) {
            Dialogs.info(a, "Never saved for", "No sites. When Swift offers to save a password, \"Never for this site\" adds the site here.")
            return
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("Never saved for")
            .setMessage("Tap a site to be offered again.")
            .setItems(sites.toTypedArray()) { _, which ->
                Passwords.setNever(sites[which], false)
                changed()
                Messages.show(a, "Swift will offer to save on ${sites[which]}")
            }
            .setNegativeButton("Close", null)
            .showImmersive()
    }

    private fun copy(a: Activity, label: String, text: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Messages.show(a, "$label copied")
    }

    private fun field(a: Activity, label: String, text: String, extra: View?, copy: () -> Unit): LinearLayout =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 56)
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, Ui.Type.CAPTION, label))
                addView(Ui.text(a, Ui.Type.BODY, text).apply {
                    tag = "value"
                    setTextIsSelectable(false)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (extra != null) addView(extra)
            addView(button(a, R.drawable.ic_copy, "Copy ${label.lowercase()}") { copy() })
        }

    private fun button(a: Activity, icon: Int, description: String, onClick: () -> Unit) = ImageButton(a).apply {
        setImageResource(icon)
        contentDescription = description
        setBackgroundResource(RowHolder.selectableBackground(a))
        layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48))
        setOnClickListener { onClick() }
    }
}
