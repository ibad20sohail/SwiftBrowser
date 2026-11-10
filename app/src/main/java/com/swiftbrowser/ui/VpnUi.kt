package com.swiftbrowser.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.swiftbrowser.R
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.vpn.Vpn
import java.io.File

/**
 * The VPN's screens (2.1): Android's one-time "allow VPN" question, the quick sheet from the ≡
 * menu and the address bar, and adding a location from a Proton VPN setup file.
 */
object VpnUi {
    /** The location to connect to once Android allowed the VPN. */
    private var pending: Vpn.Location? = null

    /** Testing the locations once Android allowed the VPN. */
    private var pendingTest = false

    /** "VPN · Netherlands", "VPN connecting…", "VPN off". */
    fun label(): String = when (Vpn.state) {
        Vpn.State.ON -> "VPN · " + (Vpn.current()?.countryName ?: "on")
        Vpn.State.CONNECTING -> "VPN connecting…"
        Vpn.State.FAILED -> if (Vpn.wanted) "VPN not connected" else "VPN off"
        Vpn.State.OFF -> "VPN off"
    }

    /** It should be on but isn't (it dropped, or didn't connect): Reconnect, or Turn off. */
    fun dropped(): Boolean = Vpn.wanted && Vpn.state == Vpn.State.FAILED

    /** 3.0: it didn't come back on when Swift started, and pages wait: Try again, or Continue without VPN. */
    fun resumeFailed(): Boolean = dropped() && Vpn.resuming

    /** The buttons of a VPN that should be on but isn't: [first] reconnects, [second] turns it off. */
    fun reconnectLabel(): String = if (Vpn.resuming) "Try again" else "Reconnect"

    fun turnOffLabel(): String = if (Vpn.resuming) "Continue without VPN" else "Turn off"

    /**
     * Turns the VPN off from a sheet or Settings; 3.0: a page that was waiting for it loads now, over
     * the phone's own connection (it waited until the VPN connected).
     */
    fun turnOff(a: Activity) {
        val waited = Vpn.resuming || (Vpn.wanted && Vpn.state != Vpn.State.ON)
        Vpn.turnOff()
        if (waited) com.swiftbrowser.tabs.TabManager.onOnline()
        Messages.show(a, if (waited) "VPN off. Pages load over your own connection." else "VPN off")
    }

    /**
     * How the VPN's state is shown, in the colours of the address bar's badge: the accent's soft colour
     * and the filled key when it's on, red when it should be on but isn't, grey otherwise. [busy]:
     * connecting or testing.
     */
    data class StateColours(val fill: Int, val ink: Int, val icon: Int, val busy: Boolean)

    fun stateColours(ctx: Context): StateColours {
        val grey = StateColours(Ui.color(ctx, R.color.surface_alt), Ui.textSecondary(ctx), R.drawable.ic_vpn, false)
        return when {
            Vpn.testing -> grey.copy(busy = true)
            Vpn.state == Vpn.State.ON -> StateColours(Ui.accentContainer(ctx), Ui.onAccentContainer(ctx), R.drawable.ic_vpn_on, false)
            Vpn.state == Vpn.State.CONNECTING -> grey.copy(busy = true)
            dropped() -> StateColours(Ui.color(ctx, R.color.danger_container), Ui.color(ctx, R.color.on_danger_container), R.drawable.ic_vpn, false)
            else -> grey
        }
    }

    /** The round holder of the VPN page and sheet, in the state's colours ([stateColours]); the loading shape while busy. */
    class StateMark(private val ctx: Context) {
        private var shown = stateColours(ctx)
        private var painted = shown.fill
        private var fade: ValueAnimator? = null
        private val badge = Ui.iconBadge(ctx, shown.icon, tint = shown.ink, fill = shown.fill).apply {
            if (shown.busy) setImageDrawable(null)
        }
        private val spinner = LoadingIndicator(ctx).apply {
            visibility = if (shown.busy) View.VISIBLE else View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val view = FrameLayout(ctx).apply {
            addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(spinner, FrameLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Gravity.CENTER))
        }

        /** Follows the state; nothing happens while it stays the same. */
        fun update() {
            val now = stateColours(ctx)
            if (now == shown) return
            shown = now
            badge.setImageDrawable(if (now.busy) null else Ui.icon(ctx, now.icon, now.ink))
            val circle = badge.background as GradientDrawable
            fade?.cancel()
            if (badge.isShown && Motion.enabled(ctx)) {
                fade = ValueAnimator.ofArgb(painted, now.fill).apply {
                    duration = Motion.SHORT
                    interpolator = Motion.standard
                    addUpdateListener {
                        painted = it.animatedValue as Int
                        circle.setColor(painted)
                    }
                    start()
                }
            } else {
                painted = now.fill
                circle.setColor(painted)
            }
            Motion.pop(spinner, now.busy)
        }
    }

    /** A line about the state for sheets and Settings. */
    fun status(): String = if (Vpn.testing) {
        val step = Vpn.testStep
        "Testing the locations" + (step?.let { " (${it.first} of ${it.second})" } ?: "") + "…" +
            if (Vpn.wanted && Vpn.pauseWhenDown) "\nPages wait until it's done." else ""
    } else when (Vpn.state) {
        Vpn.State.ON -> "Connected to " + (Vpn.current()?.let { "${it.flag} ${it.countryName}" } ?: "the VPN") +
            (Vpn.seenAs?.let { "\nSites see: $it" } ?: "\nChecking what sites see…")
        Vpn.State.CONNECTING -> (if (Vpn.resuming) "Turning the VPN back on: " else "Connecting to ") +
            (Vpn.current()?.countryName ?: "the VPN") + "…" +
            if (Vpn.pauseWhenDown || Vpn.resuming) "\nPages wait until it's connected." else ""
        // 3.0: it didn't come back on at start: why, and what the buttons do.
        Vpn.State.FAILED -> if (resumeFailed()) {
            "The VPN didn't come back on when Swift started. " + (Vpn.resumeReason ?: "") +
                "\nPages wait until it connects, or until you continue without it."
        } else {
            (Vpn.error ?: "Not connected.") + if (Vpn.wanted && Vpn.pauseWhenDown) "\nPages are paused until it connects or you turn it off." else ""
        }
        Vpn.State.OFF -> when {
            Vpn.locations().isEmpty() -> "No locations yet. Add a free Proton VPN location to start."
            // 3.0.7: it turned itself off (no internet, or no location answered): why.
            Vpn.error != null -> Vpn.error!!
            else -> "Off. Sites see your own connection."
        }
    }

    /**
     * Connects to [loc], first asking Android for the VPN permission (once) through [permission],
     * whose result goes to [onPermission].
     */
    fun turnOn(a: Activity, permission: ActivityResultLauncher<Intent>, loc: Vpn.Location? = Vpn.current()) {
        if (loc == null) {
            Messages.show(a, "Add a location first (Settings → VPN)")
            return
        }
        val ask = try {
            VpnService.prepare(a)
        } catch (_: Exception) {
            null
        }
        if (ask != null) {
            pending = loc
            permission.launch(ask)
        } else {
            Vpn.turnOn(a, loc)
        }
    }

    fun onPermission(a: Activity, granted: Boolean) {
        val loc = pending
        val test = pendingTest
        pending = null
        pendingTest = false
        if (granted && test) {
            Vpn.testAll(a)
        } else if (granted && loc != null) {
            Vpn.turnOn(a, loc)
        } else if (!granted) {
            Messages.show(a, "The VPN needs Android's permission. Turn it on again and tap OK.", long = true)
        }
    }

    /** Tries every location in turn (2.2), asking Android for the VPN permission first if needed. */
    fun test(a: Activity, permission: ActivityResultLauncher<Intent>) {
        val ask = try {
            VpnService.prepare(a)
        } catch (_: Exception) {
            null
        }
        if (ask != null) {
            pendingTest = true
            permission.launch(ask)
        } else {
            Vpn.testAll(a)
        }
    }

    /** "Fast · 0.3 s", "Slow · 4.2 s", "No answer", or null when it wasn't tested. */
    fun testResult(loc: Vpn.Location): String? {
        val ms = Vpn.testResults[loc.id] ?: return null
        if (ms == Vpn.NO_ANSWER) return "No answer"
        val s = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
        return when {
            ms < 1000 -> "Fast · $s"
            ms < 3000 -> "Good · $s"
            else -> "Slow · $s"
        }
    }

    /** "Renew by 26 Oct 2027", from two weeks before: "Stops working in 5 days". */
    fun renewText(loc: Vpn.Location): String {
        val days = ((loc.renewBy - System.currentTimeMillis()) / (24L * 3600 * 1000)).toInt()
        return when {
            days < 0 -> "Expired: extend it at Proton"
            days <= 14 -> if (days <= 1) "Stops working tomorrow" else "Stops working in $days days"
            else -> "Renew by " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(loc.renewBy))
        }
    }

    /**
     * The reminder at start: which location stops working when, and what to do. 3.0: the reminder has
     * "I extended it" itself (it sent people to a button that only showed when a location was held).
     */
    fun expiryText(loc: Vpn.Location): String =
        "VPN ${loc.flag} ${loc.countryName} (${loc.server}): ${renewText(loc).replaceFirstChar { it.lowercase() }}. " +
            "Tap Extend next to it on Proton's page, then come back and tap \"I extended it\"."

    /** 3.0: "I extended it" (the reminder, the location's sheet in Settings): another year from now. */
    fun markExtended(a: Activity, loc: Vpn.Location) {
        Vpn.markExtended(loc)
        val now = Vpn.locations().firstOrNull { it.id == loc.id } ?: loc
        Messages.show(a, "${loc.countryName}: ${renewText(now).replaceFirstChar { it.lowercase() }}")
    }

    /** A WireGuard setup file (.conf) downloaded in Swift (Android 7's file picker hardly finds them). */
    fun isSetupFile(d: Downloads.Item) = d.status == Downloads.DONE && d.fileName.endsWith(".conf", ignoreCase = true)

    /**
     * Adds a downloaded setup file as a location. The download, with the key in plain text, is then
     * deleted: Swift keeps its own locked copy.
     */
    fun addDownloaded(a: Activity, d: Downloads.Item) {
        val text = try {
            File(d.filePath).takeIf { it.length() < 64 * 1024 }?.readText()
        } catch (_: Exception) {
            null
        }
        if (text == null) {
            Messages.show(a, "Couldn't read that file")
            return
        }
        try {
            val loc = Vpn.add(a, text, d.fileName)
            Downloads.remove(d.id, deleteFile = true)
            Messages.show(a, "Added ${loc.flag} ${loc.countryName} to the VPN. The download was deleted: Swift keeps a locked copy.", long = true)
        } catch (e: IllegalArgumentException) {
            Messages.show(a, e.message ?: "That isn't a WireGuard setup file", long = true)
        }
    }

    /**
     * Holding the VPN mark in the address bar (2.3): just the countries, tap one to move there (its
     * fastest server). The whole VPN sheet is a row away.
     */
    fun quickSwitch(a: Activity, permission: ActivityResultLauncher<Intent>, openSheet: () -> Unit) {
        val locations = Vpn.locations()
        if (locations.size < 2) {
            openSheet()
            return
        }
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "Switch country", "Sites see you in the country you pick."))
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        val chosen = Vpn.current()
        val connected = Vpn.state == Vpn.State.ON
        fun row(flag: String?, title: String, sub: String?, check: Boolean, icon: Int = 0, onClick: () -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(a, 56)
            setPadding(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6))
            background = Ui.ripple(a, null, 0)
            if (flag != null) {
                addView(TextView(a).apply {
                    text = flag
                    Ui.size(this, Ui.Type.HEADLINE, keepFont = true)
                }, LinearLayout.LayoutParams(Ui.dp(a, Ui.ICON_BOX), ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                addView(android.widget.ImageView(a).apply {
                    setImageDrawable(Ui.icon(a, icon, Ui.textSecondary(a)))
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                }, LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 24)))
            }
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, Ui.Type.BODY, title))
                if (sub != null) addView(Ui.text(a, Ui.Type.CAPTION, sub))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (check) addView(android.widget.ImageView(a).apply {
                setImageDrawable(Ui.icon(a, R.drawable.ic_check, Ui.accent(a)))
            }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)))
            contentDescription = title + if (check) ", in use" else ""
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }
        for (servers in locations.groupBy { it.country.ifEmpty { it.server } }.values.sortedBy { it.first().countryName }) {
            val best = Vpn.ranked(servers).first()
            val here = chosen?.let { c -> servers.any { it.id == c.id } } == true
            col.addView(row(best.flag, best.countryName, if (here && connected) "Connected" else null, here) {
                if (here && connected) return@row
                turnOn(a, permission, best)
                Messages.show(a, "Moving to ${best.flag} ${best.countryName}…")
            })
        }
        col.addView(Ui.divider(a, 24))
        col.addView(row(null, "More VPN options", null, false, icon = R.drawable.ic_settings) { openSheet() })
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }

    /** The quick sheet: the state, on/off, and the locations to pick from. */
    fun sheet(a: Activity, permission: ActivityResultLauncher<Intent>, openSettings: () -> Unit) {
        val col = Sheets.column(a)
        val mark = StateMark(a)
        val statusLine = Ui.text(a, Ui.Type.CAPTION, status()).apply { setPadding(0, Ui.dp(a, 3), 0, 0) }
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 14))
            addView(mark.view, LinearLayout.LayoutParams(Ui.dp(a, Ui.ICON_BOX), Ui.dp(a, Ui.ICON_BOX)).apply { marginEnd = Ui.dp(a, 14) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(a, Ui.Type.SUBTITLE, "VPN"))
                addView(statusLine)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        // The state follows while the sheet is open (it connects, or drops); only for as long as it's on screen.
        val changed: () -> Unit = {
            mark.update()
            Motion.swapText(statusLine, status())
        }
        col.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Vpn.addListener(changed)

            override fun onViewDetachedFromWindow(v: View) = Vpn.removeListener(changed)
        })
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        val locations = Vpn.locations()
        val on = Vpn.wanted
        val actions = ArrayList<Sheets.Action>()
        if (locations.isNotEmpty()) {
            val dropped = dropped()
            col.addView(Sheets.wideButton(a, if (Vpn.testing) "Stop testing" else if (dropped) reconnectLabel() else if (on) "Turn off" else "Turn on") {
                dialog.dismiss()
                if (Vpn.testing) {
                    if (on) turnOn(a, permission) else Vpn.turnOff()
                } else if (on && !dropped) {
                    turnOff(a)
                } else {
                    turnOn(a, permission)
                }
            })
            if (dropped) {
                // 3.0: after a start that didn't bring it back, "Continue without VPN".
                col.addView(Sheets.wideButton(a, turnOffLabel(), outlined = true) {
                    dialog.dismiss()
                    turnOff(a)
                }.apply { (layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0 })
            }
            col.addView(Sheets.label(a, "Locations"))
            val chosen = Vpn.current()
            // One row per country (2.2); tapping it connects to its fastest server, the arrow shows them all.
            for (servers in locations.groupBy { it.country.ifEmpty { it.server } }.values.sortedBy { it.first().countryName }) {
                val best = Vpn.ranked(servers).first()
                val here = chosen?.let { c -> servers.any { it.id == c.id } } == true
                val detail = listOfNotNull(
                    if (servers.size > 1) "${servers.size} servers" else best.server,
                    if (here && servers.size > 1) "${chosen!!.server} in use" else null,
                    testResult(if (here) chosen!! else best),
                ).joinToString(" · ")
                val list = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }
                col.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = Ui.dp(a, 60)
                    setPadding(Ui.dp(a, 24), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6))
                    background = Ui.ripple(a, null, 0)
                    contentDescription = best.countryName + if (here) ", in use" else ""
                    addView(TextView(a).apply {
                        text = best.flag
                        Ui.size(this, Ui.Type.HEADLINE, keepFont = true)
                    }, LinearLayout.LayoutParams(Ui.dp(a, Ui.ICON_BOX), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(a, Ui.Type.BODY, best.countryName))
                        addView(Ui.text(a, Ui.Type.CAPTION, detail))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (here) addView(android.widget.ImageView(a).apply {
                        setImageDrawable(Ui.icon(a, R.drawable.ic_check, Ui.accent(a)))
                    }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)).apply { marginEnd = Ui.dp(a, 8) })
                    if (servers.size > 1) addView(android.widget.ImageButton(a).apply {
                        setImageDrawable(Ui.icon(a, R.drawable.ic_down, Ui.textSecondary(a)))
                        background = Ui.rippleBorderless(a)
                        contentDescription = "Servers in ${best.countryName}"
                        var open = false
                        setOnClickListener {
                            // Folds open and shut, the arrow turning with it (2.3).
                            open = !open
                            Motion.reveal(list, open)
                            Motion.anim(this, Motion.EXPAND).rotation(if (open) 180f else 0f).start()
                        }
                    }, LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)))
                    setOnClickListener {
                        dialog.dismiss()
                        // The country's fastest server (or the one in use, when it's already on there).
                        turnOn(a, permission, if (here && Vpn.state == Vpn.State.ON) chosen else best)
                    }
                })
                for (loc in Vpn.ranked(servers)) {
                    list.addView(TextView(a).apply {
                        text = listOfNotNull(loc.server, testResult(loc)).joinToString("  ·  ")
                        Ui.style(this, Ui.Type.BODY_SECONDARY)
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = Ui.dp(a, 48)
                        setPadding(Ui.dp(a, 24) + Ui.dp(a, Ui.ICON_BOX), 0, Ui.dp(a, 24), 0)
                        if (loc.id == chosen?.id) {
                            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ui.icon(a, R.drawable.ic_check, Ui.accent(a)), null)
                        }
                        background = Ui.ripple(a, null, 0)
                        setOnClickListener {
                            dialog.dismiss()
                            turnOn(a, permission, loc)
                        }
                    })
                }
                col.addView(list)
            }
        }
        actions += Sheets.Action(R.drawable.ic_settings, if (locations.isEmpty()) "Add a free location" else "VPN settings") {
            openSettings()
        }
        for (action in actions) col.addView(Sheets.row(a, action.icon, action.label) {
            dialog.dismiss()
            action.run()
        })
        dialog = Sheets.sheet(a, col)
        dialog.showImmersive()
    }
}
