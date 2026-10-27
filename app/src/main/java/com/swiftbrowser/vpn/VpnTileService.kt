package com.swiftbrowser.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.ui.MainActivity

/**
 * The "Swift VPN" quick settings tile (2.3): turns Swift's VPN on or off from the notification
 * shade. As in the app, only Swift's own pages go through it. An "active" tile: Android asks it for
 * its state only when the VPN changes ([watch]) or it is tapped, not every time the shade opens.
 */
class VpnTileService : TileService() {
    private val update: () -> Unit = { refresh() }

    override fun onStartListening() {
        Vpn.addListener(update)
        refresh()
    }

    override fun onStopListening() {
        Vpn.removeListener(update)
    }

    override fun onClick() {
        when (Vpn.state) {
            Vpn.State.ON, Vpn.State.CONNECTING -> Vpn.turnOff()
            else -> {
                val permission = try {
                    VpnService.prepare(this)
                } catch (_: Exception) {
                    null
                }
                val loc = Vpn.current()
                if (loc == null || permission != null) {
                    // A location to add, or Android's permission to give: Swift's VPN sheet does both.
                    startActivityAndCollapse(
                        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_VPN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return
                }
                Vpn.turnOn(this, loc)
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = Vpn.state
        tile.state = if (state == Vpn.State.ON || state == Vpn.State.CONNECTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = when (state) {
            Vpn.State.CONNECTING -> getString(R.string.tile_vpn_connecting)
            Vpn.State.ON -> Vpn.current()?.countryName ?: getString(R.string.tile_vpn)
            else -> getString(R.string.tile_vpn)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
        tile.updateTile()
    }

    companion object {
        /**
         * Keeps the tile in step with the VPN (from start-up). Asked once straight away too: until
         * then Android shows a new tile as on.
         */
        fun watch(ctx: Context) {
            val app = ctx.applicationContext
            val component = ComponentName(app, VpnTileService::class.java)
            val ask = {
                try {
                    requestListeningState(app, component)
                } catch (e: Exception) {
                    L.w("Couldn't update the VPN tile", e)
                }
            }
            Vpn.addListener(ask)
            ask()
        }
    }
}
