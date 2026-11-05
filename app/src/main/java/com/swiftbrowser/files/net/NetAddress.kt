package com.swiftbrowser.files.net

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 3.0.4: where a network folder is, from what the user typed in Network folders (Files → Tools): a
 * computer's name or IP address ("192.168.1.5", "nas", "nas:4445", "[fe80::1]"), or a whole address
 * as Windows or other apps write it ("\\nas\Films\2024", "smb://anna@nas/Films"), which may also
 * name a share, a folder in it and who signs in. Pure, so it is tested on the PC.
 */
data class NetAddress(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val share: String = "",
    val path: String = "",
    val user: String = "",
    val domain: String = "",
    val password: String = "",
) {
    /** How it is shown: "192.168.1.5", "nas:4445", "nas/Films/2024" (never who signs in). */
    fun label(): String = buildString {
        val v6 = ':' in host
        if (v6 && port != DEFAULT_PORT) append('[').append(host).append(']') else append(host)
        if (port != DEFAULT_PORT) append(':').append(port)
        if (share.isNotEmpty()) append('/').append(share)
        if (path.isNotEmpty()) append('/').append(path)
    }

    /** Never the user or the password, so a stray log line can't show them. */
    override fun toString(): String = "NetAddress"

    companion object {
        /** SMB's own port (139, NetBIOS, is SMB1's and isn't tried). */
        const val DEFAULT_PORT = 445

        /** What the user typed, or null when it isn't an address (a web address, spaces in a name…). */
        fun parse(input: String): NetAddress? {
            var s = input.trim()
            if (s.isEmpty() || s.length > 1000) return null
            var url = false
            val lower = s.lowercase(Locale.ROOT)
            when {
                lower.startsWith("smb://") -> {
                    s = s.substring(6)
                    url = true
                }
                lower.startsWith("cifs://") -> {
                    s = s.substring(7)
                    url = true
                }
                // http:// and the rest aren't network folders.
                "://" in s -> return null
                s.startsWith("\\\\") || s.startsWith("//") -> s = s.substring(2)
            }
            s = s.replace('\\', '/')
            val authority = s.substringBefore('/')
            val rest = s.substringAfter('/', "")
            var hostPort = authority
            var user = ""
            var domain = ""
            var password = ""
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                val info = authority.substring(0, at)
                hostPort = authority.substring(at + 1)
                if (':' in info) password = decode(info.substringAfter(':'), url) ?: return null
                val who = decode(info.substringBefore(':'), url) ?: return null
                // smb://WORKGROUP;anna@nas, as SMB addresses write a domain.
                if (';' in who) {
                    domain = who.substringBefore(';')
                    user = who.substringAfter(';')
                } else {
                    user = who
                }
            }
            var host: String
            var port = DEFAULT_PORT
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end < 0) return null
                host = hostPort.substring(1, end)
                val after = hostPort.substring(end + 1)
                if (after.isNotEmpty()) {
                    if (!after.startsWith(":")) return null
                    port = portOf(after.substring(1)) ?: return null
                }
                if (!isIpv6(host)) return null
            } else {
                when (hostPort.count { it == ':' }) {
                    0 -> host = hostPort
                    1 -> {
                        host = hostPort.substringBefore(':')
                        port = portOf(hostPort.substringAfter(':')) ?: return null
                    }
                    // fe80::1 without brackets has no port.
                    else -> {
                        host = hostPort
                        if (!isIpv6(host)) return null
                    }
                }
            }
            host = normalHost(host) ?: return null
            val parts = ArrayList<String>()
            for (raw in rest.split('/')) {
                if (raw.isEmpty() || raw == ".") continue
                val part = decode(raw, url) ?: return null
                // Never above the share.
                if (part == "..") return null
                parts += part
            }
            return NetAddress(host, port, parts.firstOrNull().orEmpty(), parts.drop(1).joinToString("/"), user, domain, password)
        }

        /** "WORKGROUP\anna" as Windows writes a user of a domain: the domain, then the user ("" for none). */
        fun splitUser(text: String): Pair<String, String> {
            val t = text.trim()
            val slash = t.indexOf('\\')
            return if (slash > 0) t.substring(0, slash).trim() to t.substring(slash + 1).trim() else "" to t
        }

        /** An IPv4 address: four numbers from 0 to 255 ("192.168.1.5"). */
        fun isIpv4(s: String): Boolean = ipv4(s) != null

        /** A port from 1 to 65535, or null. */
        private fun portOf(s: String): Int? {
            if (s.isEmpty() || s.length > 5 || !s.all { it in '0'..'9' }) return null
            return s.toInt().takeIf { it in 1..65535 }
        }

        /** "192.168.001.005" as "192.168.1.5"; null when it isn't one. */
        private fun ipv4(s: String): String? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            val n = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null else p.toInt() }
            if (n.any { it > 255 }) return null
            return n.joinToString(".")
        }

        private fun isIpv6(s: String): Boolean {
            val addr = s.substringBefore('%')
            val scope = s.substringAfter('%', "")
            if (addr.count { it == ':' } < 2 || addr.indexOf("::") != addr.lastIndexOf("::")) return false
            if (!addr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
            return scope.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }

        /** The host as it is used: an IPv4 address without leading zeros, an IPv6 one, or a name. */
        private fun normalHost(h: String): String? {
            if (h.isEmpty()) return null
            if (h.all { it in '0'..'9' || it == '.' }) return ipv4(h)
            if (':' in h) return if (isIpv6(h)) h.lowercase(Locale.ROOT) else null
            val name = h.removeSuffix(".")
            if (name.isEmpty() || name.length > 253) return null
            val label = Regex("[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?")
            return name.takeIf { n -> n.split('.').all { label.matches(it) } }
        }

        /** %XX sequences of an smb:// address as UTF-8 (a plain "+" stays one); null when broken. */
        private fun decode(s: String, url: Boolean): String? {
            if (!url || '%' !in s) return s
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                if (s[i] == '%') {
                    if (i + 2 >= s.length) return null
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 3
                } else {
                    val end = s.indexOf('%', i).let { if (it < 0) s.length else it }
                    val bytes = s.substring(i, end).toByteArray(Charsets.UTF_8)
                    out.write(bytes, 0, bytes.size)
                    i = end
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
