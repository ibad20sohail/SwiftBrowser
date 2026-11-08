package com.swiftbrowser.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swiftbrowser.R
import com.swiftbrowser.core.Passwords
import com.swiftbrowser.core.UrlUtils

/**
 * Settings → Saved passwords (2.6): the sign-ins Swift fills in, by site. They show only after the
 * phone's screen lock is entered ([check]); a password stays hidden until Show is tapped. 3.0: kept
 * out of screenshots and the recent-apps picture while they show (SecureScreen); a copied password
 * leaves the clipboard after 45 seconds; sign-ins that can't be read any more are said to be so.
 */
object PasswordsUi {
    private val io = com.swiftbrowser.core.Background.single("passwords")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A copied password is taken off the clipboard after this, if it is still there (any app can read it on Android 7). */
    private const val CLIP_MS = 45_000L

    /** Each half of the eye's change of icon. */
    private const val EYE_MS = Motion.ICON / 2

    private fun later(a: Activity, work: () -> Unit) = a.window.decorView.post(work)

    /** Password dialogs on screen: the screen stays secure until the last one goes. */
    private var open = 0

    /**
     * 3.0: [d] (built, not shown yet) and its screen stay out of screenshots and the recent-apps
     * picture while it shows (a dialog is a window of its own).
     */
    private fun showSecure(a: Activity, d: androidx.appcompat.app.AlertDialog): androidx.appcompat.app.AlertDialog {
        d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        open++
        SecureScreen.set(a, true)
        d.setOnDismissListener {
            open--
            // A moment later: the next dialog (details after the list) counts itself first.
            later(a) { if (open <= 0 && !a.isFinishing) SecureScreen.set(a, false) }
        }
        d.showImmersive()
        return d
    }

    /** The list; [changed] runs after a sign-in was deleted (Settings updates its count). */
    fun list(a: Activity, check: (() -> Unit) -> Unit, changed: () -> Unit) {
        check {
            io.execute {
                val read = try {
                    Passwords.read(com.swiftbrowser.core.Profiles.currentId)
                } catch (e: Exception) {
                    com.swiftbrowser.core.L.w("Saved passwords couldn't be opened", e)
                    null
                }
                later(a) {
                    if (a.isFinishing) return@later
                    // 3.0: Android's secure storage didn't answer: said so (it showed "None yet").
                    if (read == null) Messages.show(a, "Swift couldn't open your saved passwords just now. Try again in a moment.", long = true)
                    else showList(a, read.first, read.second, changed)
                }
            }
        }
    }

    private fun showList(a: Activity, logins: List<Passwords.Login>, unreadable: List<com.swiftbrowser.core.LoginRow>, changed: () -> Unit) {
        if (logins.isEmpty() && unreadable.isEmpty()) {
            Dialogs.info(a, "Saved passwords", "None yet. When you sign in on a site, Swift offers to keep the password and fills it in next time.")
            return
        }
        if (logins.isEmpty()) {
            unreadableDialog(a, unreadable, changed)
            return
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (l in logins) {
            rows.addView(loginRow(a, l) {
                details(a, l, changed)
                dialog.dismiss()
            })
        }
        // 3.0: the ones that can't be read any more, last, in red.
        if (unreadable.isNotEmpty()) {
            val n = unreadable.size
            rows.addView(Ui.text(a, Ui.Type.BODY, if (n == 1) "1 sign-in can't be read any more" else "$n sign-ins can't be read any more", Ui.danger(a)).apply {
                minHeight = Ui.dp(a, Ui.ROW_ONE_LINE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 8))
                background = Ui.ripple(a, null, 0)
                setOnClickListener {
                    unreadableDialog(a, unreadable, changed)
                    dialog.dismiss()
                }
            })
        }
        dialog = showSecure(
            a,
            MaterialAlertDialogBuilder(a)
                .setTitle("Saved passwords (${logins.size})")
                .setView(ScrollView(a).apply {
                    isVerticalScrollBarEnabled = false
                    addView(rows)
                })
                .setNegativeButton("Close", null)
                .create(),
        )
    }

    /** A saved sign-in: the site's icon in the lists' rounded holder (from what Swift has kept; nothing is fetched), its name and the user name. */
    private fun loginRow(a: Activity, l: Passwords.Login, onClick: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(a, Ui.ROW_TWO_LINES)
        setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 8))
        background = Ui.ripple(a, null, 0)
        val holder = ImageView(a).apply {
            setBackgroundResource(R.drawable.bg_icon_badge)
            setImageDrawable(Ui.icon(a, R.drawable.ic_globe, Ui.textSecondary(a)))
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(holder, LinearLayout.LayoutParams(Ui.dp(a, Ui.ICON_BOX), Ui.dp(a, Ui.ICON_BOX)).apply { marginEnd = Ui.dp(a, 16) })
        com.swiftbrowser.core.Favicons.load(l.origin) { bmp ->
            if (bmp != null) {
                holder.setImageBitmap(bmp)
                // A little dimmer in the dark look, as every site's icon.
                SoftPictures.apply(holder)
                holder.scaleType = ImageView.ScaleType.FIT_CENTER
                val p = Ui.dp(a, 10)
                holder.setPadding(p, p, p, p)
            }
        }
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(a, Ui.Type.BODY, UrlUtils.siteName(l.origin).ifBlank { l.origin }, lines = 1))
            addView(Ui.text(a, Ui.Type.CAPTION, l.username.ifBlank { "(no user name)" }, lines = 1))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
    }

    /**
     * 3.0: sign-ins locked with a key Android's secure storage lost (it can happen after an update
     * of Android or a change of screen lock): they can't be opened again, only deleted.
     */
    private fun unreadableDialog(a: Activity, rows: List<com.swiftbrowser.core.LoginRow>, changed: () -> Unit) {
        val n = rows.size
        val sites = rows.map { UrlUtils.siteName(it.origin).ifBlank { it.origin } }.distinct().take(6).joinToString(", ")
        val d = MaterialAlertDialogBuilder(a)
            .setTitle(if (n == 1) "This sign-in can't be read any more" else "These sign-ins can't be read any more")
            .setMessage(
                "$sites${if (rows.size > 6) " and more" else ""}.\n\nAndroid's secure storage lost the key they were locked with, " +
                    "so Swift can't open them again. Delete them; Swift offers to save each password again the next time you sign in.",
            )
            .setPositiveButton(if (n == 1) "Delete it" else "Delete them") { _, _ ->
                io.execute {
                    Passwords.removeRows(rows)
                    later(a) {
                        changed()
                        Messages.show(a, if (n == 1) "Deleted" else "Deleted $n sign-ins")
                    }
                }
            }
            .setNegativeButton("Not now", null)
            .showImmersive()
        Dialogs.markDanger(d)
    }

    private fun details(a: Activity, l: Passwords.Login, changed: () -> Unit) {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 12), 0)
        }
        col.addView(field(a, "User name", l.username.ifBlank { "(none)" }, null) { copy(a, "User name", l.username, secret = false) })
        val dots = "•".repeat(l.password.length.coerceIn(6, 16))
        var shown = false
        lateinit var value: TextView
        lateinit var eye: ImageButton
        eye = button(a, R.drawable.ic_visibility, "Show password") {
            shown = !shown
            Motion.swapText(value, if (shown) l.password else dots)
            // A plain fade: the turn of Motion.swapIcon reads wrong on an eye.
            val icon = if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            Motion.anim(eye, EYE_MS, Motion.exit).alpha(0f).withEndAction {
                eye.setImageResource(icon)
                Motion.anim(eye, EYE_MS, Motion.enter).alpha(1f).start()
            }.start()
            eye.contentDescription = if (shown) "Hide password" else "Show password"
        }
        val row = field(a, "Password", dots, eye) { copy(a, "Password", l.password, secret = true) }
        value = row.findViewWithTag("value")
        col.addView(row)
        val d = showSecure(
            a,
            MaterialAlertDialogBuilder(a)
                .setTitle(UrlUtils.siteName(l.origin).ifBlank { l.origin })
                .setView(col)
                .setPositiveButton("Close", null)
                .setNeutralButton("Delete") { _, _ -> delete(a, l, changed) }
                .create(),
        )
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
        // A sheet: a dialog with a message can't show a list too, so the sites never showed.
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "Never saved for", "Tap a site to be offered again."))
        lateinit var sheet: com.google.android.material.bottomsheet.BottomSheetDialog
        for (site in sites) {
            col.addView(Sheets.row(a, R.drawable.ic_globe, site) {
                sheet.dismiss()
                Passwords.setNever(site, false)
                changed()
                Messages.show(a, "Swift will offer to save on $site")
            })
        }
        col.addView(Sheets.wideButton(a, "Close", outlined = true) { sheet.dismiss() })
        sheet = Sheets.sheet(a, col)
        sheet.showImmersive()
    }

    /**
     * 3.0: a [secret] (the password) is marked as such (newer Androids then don't show it in their
     * clipboard preview) and goes from the clipboard after [CLIP_MS] if it is still what is there.
     */
    private fun copy(a: Activity, label: String, text: String, secret: Boolean) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (secret) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        if (!secret) {
            Messages.show(a, "$label copied")
            return
        }
        val app = a.applicationContext
        main.postDelayed({ clearIfStill(app, text) }, CLIP_MS)
        Messages.show(a, "$label copied. Swift takes it off the clipboard in 45 seconds.")
    }

    private fun clearIfStill(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val now = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (_: Exception) {
            null
        }
        // Something else was copied since (or Android no longer lets Swift look): left alone.
        if (now != text) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {
        }
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
        background = Ui.rippleBorderless(a)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48))
        setOnClickListener { onClick() }
    }
}
