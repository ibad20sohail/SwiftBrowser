package com.swiftbrowser.files.net

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 3.0.4: a computer or storage added to Network folders: where it is ([host], [port], and a share
 * and folder to open at, if the address named them), the name shown for it, who signs in (no
 * [user]: a guest) and, only when the user chose to remember it, the password as Android's keystore
 * locked it ([sealed]: never the password itself; see NetServers). Pure, tested on the PC.
 */
data class NetServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = NetAddress.DEFAULT_PORT,
    val share: String = "",
    val path: String = "",
    val user: String = "",
    val domain: String = "",
    val sealed: String = "",
    val added: Long = 0L,
) {
    /** Signs in as a guest (no user name). */
    val guest: Boolean get() = user.isBlank()

    /** Its password is kept (locked by the keystore). */
    val remembers: Boolean get() = sealed.isNotEmpty()

    /** Where it is, as shown: "192.168.1.5", "nas:4445/Films". */
    fun address(): String = NetAddress(host, port, share, path).label()

    /** The name shown, or its address when it has none. */
    val title: String get() = name.ifBlank { address() }

    /** What a session depends on besides the password: another of these means signing in again. */
    val signIn: String get() = listOf(host.lowercase(), port, domain, user).joinToString("|")

    /** One line for the store (the sealed password goes as it is: it is unreadable without the keystore). */
    fun encode(): String = listOf(
        "v" to VERSION.toString(), "id" to id, "name" to name, "host" to host, "port" to port.toString(),
        "share" to share, "path" to path, "user" to user, "domain" to domain, "pw" to sealed, "added" to added.toString(),
    ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    /** Never where it is, who signs in or the password, so a stray log line can't show them. */
    override fun toString(): String = "NetServer($id)"

    companion object {
        private const val VERSION = 1

        /** [encode]'s line back, or null when it isn't one (another version, or damaged). */
        fun decode(text: String): NetServer? = try {
            val fields = text.split('&').associate { part ->
                val key = part.substringBefore('=')
                key to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            if (fields["v"] != VERSION.toString()) {
                null
            } else {
                val id = fields["id"].orEmpty()
                val host = fields["host"].orEmpty()
                val port = fields["port"]?.toIntOrNull() ?: NetAddress.DEFAULT_PORT
                if (id.isEmpty() || host.isEmpty() || port !in 1..65535) {
                    null
                } else {
                    NetServer(
                        id = id,
                        name = fields["name"].orEmpty(),
                        host = host,
                        port = port,
                        share = fields["share"].orEmpty(),
                        path = NetPath.clean(fields["path"].orEmpty()),
                        user = fields["user"].orEmpty(),
                        domain = fields["domain"].orEmpty(),
                        sealed = fields["pw"].orEmpty(),
                        added = fields["added"]?.toLongOrNull() ?: 0L,
                    )
                }
            }
        } catch (_: IllegalArgumentException) {
            // A broken %-sequence.
            null
        }
    }
}
