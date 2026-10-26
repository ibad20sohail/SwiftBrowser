package com.swiftbrowser.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.view.Gravity
import android.view.ViewGroup
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

    /** A line about the state for sheets and Settings. */
    fun status(): String = if (Vpn.testing) {
        val step = Vpn.testStep
        "Testing the locations" + (step?.let { " (${it.first} of ${it.second})" } ?: "") + "…" +
            if (Vpn.wanted && Vpn.pauseWhenDown) "\nPages wait until it's done." else ""
    } else when (Vpn.state) {
        Vpn.State.ON -> "Connected to " + (Vpn.current()?.let { "${it.flag} ${it.countryName}" } ?: "the VPN") +
            (Vpn.seenAs?.let { "\nSites see: $it" } ?: "\nChecking what sites see…")
        Vpn.State.CONNECTING -> "Connecting to " + (Vpn.current()?.countryName ?: "the VPN") + "…" +
            if (Vpn.pauseWhenDown) "\nPages wait until it's connected." else ""
        Vpn.State.FAILED -> (Vpn.error ?: "Not connected.") + if (Vpn.wanted && Vpn.pauseWhenDown) "\nPages are paused until it connects or you turn it off." else ""
        Vpn.State.OFF -> if (Vpn.locations().isEmpty()) "No locations yet. Add a free Proton VPN location to start." else "Off. Sites see your own connection."
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

    /** The reminder at start: which location stops working when, and what to do. */
    fun expiryText(loc: Vpn.Location): String =
        "VPN ${loc.flag} ${loc.countryName} (${loc.server}): ${renewText(loc).replaceFirstChar { it.lowercase() }}. " +
            "Tap Extend next to it on Proton's page, then \"I extended it\" in Settings → VPN."

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

    /** The quick sheet: the state, on/off, and the locations to pick from. */
    fun sheet(a: Activity, permission: ActivityResultLauncher<Intent>, openSettings: () -> Unit) {
        val col = Sheets.column(a)
        col.addView(Sheets.header(a, "VPN", status()))
        lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
        val locations = Vpn.locations()
        val on = Vpn.wanted
        val actions = ArrayList<Sheets.Action>()
        if (locations.isNotEmpty()) {
            val dropped = dropped()
            col.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), Ui.dp(a, 8))
                addView(com.google.android.material.button.MaterialButton(a).apply {
                    text = if (Vpn.testing) "Stop testing" else if (dropped) "Reconnect" else if (on) "Turn off" else "Turn on"
                    setOnClickListener {
                        dialog.dismiss()
                        if (Vpn.testing) {
                            if (on) turnOn(a, permission) else Vpn.turnOff()
                        } else if (on && !dropped) {
                            Vpn.turnOff()
                            Messages.show(a, "VPN off")
                        } else {
                            turnOn(a, permission)
                        }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)))
                if (dropped) {
                    addView(com.google.android.material.button.MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Turn off"
                        setOnClickListener {
                            dialog.dismiss()
                            Vpn.turnOff()
                            Messages.show(a, "VPN off")
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)).apply { topMargin = Ui.dp(a, 4) })
                }
            })
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
                    visibility = android.view.View.GONE
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
                        textSize = 22f
                    }, LinearLayout.LayoutParams(Ui.dp(a, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
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
                        setOnClickListener {
                            val open = list.visibility != android.view.View.VISIBLE
                            list.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                            rotation = if (open) 180f else 0f
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
                        setPadding(Ui.dp(a, 24) + Ui.dp(a, 36), 0, Ui.dp(a, 24), 0)
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
