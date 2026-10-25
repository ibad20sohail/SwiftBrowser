package com.swiftbrowser.core

import android.net.Uri
import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object UrlUtils {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val HOST_CHARS = Regex("^[a-zA-Z0-9.-]+$")
    private val WEB_SCHEMES = setOf(
        "http", "https", "about", "data", "blob", "file", "moz-extension", "javascript", "view-source", "resource",
    )

    /** Second-level labels that act like public suffixes under country TLDs (co.uk, com.pk, ...). */
    private val SECOND_LEVEL = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "nic", "mil", "sch", "ltd", "plc",
        "info", "biz", "nom", "web", "res", "gen",
    )

    fun isBlank(url: String?): Boolean = url.isNullOrBlank() || url == "about:blank"

    /** Turns what the user typed into a URL to load: a web address, or a search. */
    fun toUrlOrSearch(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (!t.contains(' ') && SCHEME.containsMatchIn(t)) {
            val scheme = t.substringBefore(':').lowercase(Locale.ROOT)
            if (scheme in WEB_SCHEMES) return t
            // "example.com:8080/path" parses like a scheme; treat it as a host with a port.
            if (!looksLikeHost(t)) return t
        }
        if (!t.contains(' ') && looksLikeHost(t)) {
            // Routers and other local devices (192.168.x.x, localhost) rarely support HTTPS.
            val host = t.substringBefore('/').substringBefore('?').substringBefore(':')
            val local = host.equals("localhost", ignoreCase = true) || IPV4.matches(host)
            return (if (local) "http://" else "https://") + t
        }
        return searchUrl(t)
    }

    /** A search with the engine chosen in Settings. */
    fun searchUrl(query: String): String = SearchEngine.current.searchUrl(query)

    private fun looksLikeHost(t: String): Boolean {
        val hostPort = t.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringBefore(':')
        if (host.equals("localhost", ignoreCase = true)) return true
        if (IPV4.matches(host)) return true
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        if (!HOST_CHARS.matches(host)) return false
        val tld = host.substringAfterLast('.')
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    fun scheme(url: String?): String = url?.substringBefore(':', "")?.lowercase(Locale.ROOT) ?: ""

    fun isWebScheme(url: String?): Boolean = scheme(url) in WEB_SCHEMES

    fun isHttp(url: String?): Boolean = scheme(url).let { it == "http" || it == "https" }

    fun host(url: String?): String? = try {
        if (url.isNullOrBlank()) null else Uri.parse(url).host?.lowercase(Locale.ROOT)?.trimEnd('.')
    } catch (_: Exception) {
        null
    }

    /** Registrable domain ("site") of a host, approximated without a public-suffix list. */
    fun siteOfHost(host: String): String {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        if (IPV4.matches(h) || h.startsWith("[") || !h.contains('.')) return h
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val tld = parts[parts.size - 1]
        val sld = parts[parts.size - 2]
        return if (tld.length == 2 && sld in SECOND_LEVEL) parts.takeLast(3).joinToString(".")
        else parts.takeLast(2).joinToString(".")
    }

    fun site(url: String?): String? = host(url)?.let { siteOfHost(it) }

    fun sameSite(a: String?, b: String?): Boolean {
        val sa = site(a) ?: return false
        val sb = site(b) ?: return false
        return sa == sb
    }

    fun stripFragment(url: String): String = url.substringBefore('#')

    /**
     * Just the site (host, without "www."), as the address bar shows it while reading a page and
     * sheets show under a title. Other addresses (files, data) are shown in short.
     */
    fun siteName(url: String?): String {
        if (url.isNullOrBlank() || url == "about:blank") return ""
        if (url.startsWith("data:")) return "data:…"
        return try {
            val u = Uri.parse(url)
            if (isHttp(url) && !u.host.isNullOrEmpty()) {
                val port = if (u.port > 0) ":${u.port}" else ""
                u.host!!.lowercase(Locale.ROOT).removePrefix("www.") + port
            } else if (url.startsWith("file:")) {
                Uri.decode(u.lastPathSegment ?: url)
            } else {
                display(url)
            }
        } catch (_: Exception) {
            url
        }
    }

    /** What the address bar shows when not editing. */
    fun display(url: String?): String {
        if (url.isNullOrBlank() || url == "about:blank") return ""
        return try {
            val u = Uri.parse(url)
            if (isHttp(url)) {
                val host = (u.host ?: "").removePrefix("www.")
                val port = if (u.port > 0) ":${u.port}" else ""
                val path = u.encodedPath.orEmpty().let { if (it == "/") "" else it }
                val query = u.encodedQuery?.let { "?$it" } ?: ""
                decode(host + port + path + query)
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    /** The search terms if [url] is a search results page, so the bar shows the query instead of the URL. */
    fun searchQuery(url: String?): String? = SearchEngine.queryOf(url)

    private val BAD_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(BAD_CHARS, "_").trim().trim('.')
        return (if (cleaned.isEmpty()) "download" else cleaned).take(120)
    }

    /** Picks a file name from Content-Disposition, the URL, or the MIME type. */
    fun fileName(url: String, contentDisposition: String?, mime: String?): String {
        var name: String? = null
        if (!contentDisposition.isNullOrBlank()) {
            val star = Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
            name = if (star != null) {
                try {
                    URLDecoder.decode(star.groupValues[2].trim().trim('"'), star.groupValues[1].ifBlank { "UTF-8" })
                } catch (_: Exception) {
                    star.groupValues[2]
                }
            } else {
                Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentDisposition)
                    ?.groupValues?.get(1)?.trim()
            }
        }
        if (name.isNullOrBlank()) {
            name = try {
                Uri.parse(url).lastPathSegment
            } catch (_: Exception) {
                null
            }
        }
        if (name.isNullOrBlank()) name = "download"
        var result = sanitizeFileName(name)
        val mimeClean = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (!result.contains('.') && !mimeClean.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeClean)?.let { result = "$result.$it" }
        }
        return result
    }

    fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return null
        return when (ext) {
            "ts" -> "video/mp2t"
            "mkv" -> "video/x-matroska"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
