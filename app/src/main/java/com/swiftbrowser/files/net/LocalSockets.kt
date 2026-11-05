package com.swiftbrowser.files.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.swiftbrowser.core.L
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * 3.0.4: network folders' connections go over the phone's own Wi-Fi (or Ethernet). Swift's VPN takes
 * all of Swift's traffic (its tunnel includes the app), so a home computer's address would go to the
 * VPN's server and never be reached: these sockets are bound to the local network instead, which
 * the VPN's own app is allowed to do, and names are looked up there too. Without such a network
 * (mobile data only), or when Android refuses (another app's VPN), the usual way.
 */
class LocalSockets(ctx: Context) : SocketFactory() {
    private val app = ctx.applicationContext

    override fun createSocket(): Socket = Socket().also { s -> localNetwork()?.let { bind(it, s) } }

    override fun createSocket(host: String, port: Int): Socket = connect(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = connect(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket = connect(host.hostAddress ?: host.hostName, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connect(address.hostAddress ?: address.hostName, port)

    private fun connect(host: String, port: Int): Socket {
        val net = localNetwork()
        val s = Socket()
        try {
            if (net != null) bind(net, s)
            s.connect(InetSocketAddress(resolve(net, host), port), CONNECT_TIMEOUT_MS)
            return s
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun bind(net: Network, s: Socket) {
        try {
            net.bindSocket(s)
        } catch (e: IOException) {
            L.w("Network folders: the Wi-Fi can't be used directly (${e.javaClass.simpleName})")
        }
    }

    /** A literal address as it is; a name as the local network's own DNS knows it, or the usual way. */
    private fun resolve(net: Network?, host: String): InetAddress {
        val literal = NetAddress.isIpv4(host) || ':' in host
        if (literal || net == null) return InetAddress.getByName(host)
        return try {
            net.getByName(host)
        } catch (_: IOException) {
            InetAddress.getByName(host)
        }
    }

    /** The Wi-Fi or Ethernet network the phone is on (not a VPN), if any. */
    private fun localNetwork(): Network? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        } catch (e: RuntimeException) {
            L.w("Network folders: can't look at the phone's networks (${e.javaClass.simpleName})")
            null
        }
    }

    private companion object {
        /** A computer on the same Wi-Fi answers in well under a second; 6 s allows for a slow one waking. */
        const val CONNECT_TIMEOUT_MS = 6000
    }
}
