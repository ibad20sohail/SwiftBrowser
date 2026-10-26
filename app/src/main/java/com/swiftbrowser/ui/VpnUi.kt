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
 * The VPN's screens (2.1): Android's one-time "allow VPN" question, the quick sheet from the ⋮
 * menu and the address bar, and adding a location from a Proton VPN setup file.
 */
object VpnUi {
    /** The location to connect to once Android allowed the VPN. */
    private var pending: Vpn.Location? = null

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
    fun status(): String = when (Vpn.state) {
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
        pending = null
        if (granted && loc != null) {
            Vpn.turnOn(a, loc)
        } else if (!granted) {
            Messages.show(a, "The VPN needs Android's permission. Turn it on again and tap OK.", long = true)
        }
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
                    text = if (dropped) "Reconnect" else if (on) "Turn off" else "Turn on"
                    setOnClickListener {
                        dialog.dismiss()
                        if (on && !dropped) {
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
            val chosen = Vpn.current()?.id
            for (loc in locations) {
                col.addView(TextView(a).apply {
                    text = "${loc.flag}  ${loc.countryName}"
                    Ui.style(this, Ui.Type.BODY)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = Ui.dp(a, 52)
                    setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), 0)
                    if (loc.id == chosen) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ui.icon(a, R.drawable.ic_check, Ui.accent(a)), null)
                    }
                    background = Ui.ripple(a, null, 0)
                    contentDescription = loc.countryName + if (loc.id == chosen) ", chosen" else ""
                    setOnClickListener {
                        dialog.dismiss()
                        // Picking a location connects to it (or switches to it when connected).
                        turnOn(a, permission, loc)
                    }
                })
                col.addView(Ui.text(a, Ui.Type.CAPTION, loc.server).apply { setPadding(Ui.dp(a, 24) + Ui.dp(a, 32), 0, Ui.dp(a, 24), Ui.dp(a, 6)) })
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
