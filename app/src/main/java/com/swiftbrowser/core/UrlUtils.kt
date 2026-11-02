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

    /**
     * 3.0: platforms where every customer gets a site of their own under the platform's name
     * (someone.github.io, a-shop.netlify.app): each of those is a site of its own, so a site's
     * settings and "clear this site" never cover the whole platform. The common ones, instead of
     * the whole public-suffix list.
     */
    private val SHARED_HOSTING = setOf(
        "github.io", "gitlab.io", "blogspot.com", "netlify.app", "vercel.app", "pages.dev", "workers.dev", "web.app",
        "firebaseapp.com", "herokuapp.com", "appspot.com", "glitch.me", "surge.sh", "wordpress.com", "tumblr.com",
        "onrender.com", "fly.dev", "azurewebsites.net", "cloudfront.net", "s3.amazonaws.com",
    )

    fun isBlank(url: String?): Boolean = url.isNullOrBlank() || url == "about:blank"

    /**
     * Turns what the user typed into a URL to load: a web address, or a search. 3.0: text that
     * didn't come from typing ([trusted] false: a QR code, the clipboard, voice) only ever opens web
     * pages; "javascript:", "data:", "file:" and other addresses are searched for instead, as they
     * would run in (or open) something other than a site.
     */
    fun toUrlOrSearch(input: String, trusted: Boolean = true): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        return addressOf(t, trusted) ?: searchUrl(t)
    }

    /** 3.0: the address [input] opens (see [toUrlOrSearch]), or null when its words are searched for. */
    fun addressOf(input: String, trusted: Boolean = true): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (!t.contains(' ') && SCHEME.containsMatchIn(t)) {
            val scheme = t.substringBefore(':').lowercase(Locale.ROOT)
            if (scheme in WEB_SCHEMES) return if (trusted || scheme == "http" || scheme == "https") t else null
            // "example.com:8080/path" parses like a scheme; treat it as a host with a port.
            if (!looksLikeHost(t)) return if (trusted) t else null
        }
        if (!t.contains(' ') && looksLikeHost(t)) {
            // Routers and other local devices (192.168.x.x, localhost) rarely support HTTPS.
            val host = t.substringBefore('/').substringBefore('?').substringBefore(':')
            val local = host.equals("localhost", ignoreCase = true) || IPV4.matches(host)
            return (if (local) "http://" else "https://") + t
        }
        return null
    }

    /** Dots typed on other alphabets' keyboards, which addresses take as dots ("paypal。com"). */
    private val OTHER_DOTS = Regex("[\\u3002\\uFF0E\\uFF61]")

    private fun isSpace(c: Char) = c.isWhitespace() || Character.isSpaceChar(c)

    /**
     * 3.0: whether [text] reads like a web address or a site's name: "paypal.com",
     * "accounts.google.com/signin", "https://…", "javascript:…", "192.168.1.1", in any alphabet and
     * whatever hidden characters it holds. Search words like these are never shown as words in the
     * address bar (they would pass for the site), nor sent to the search engine for suggestions.
     */
    fun looksLikeAddress(text: String): Boolean {
        val t = text.replace(HIDDEN_CHARS, "").replace(OTHER_DOTS, ".").trim { isSpace(it) }
        if (t.isEmpty()) return false
        if ("://" in t) return true
        if (t.any { isSpace(it) }) return false
        if (SCHEME.containsMatchIn(t) && scheme(t) in WEB_SCHEMES) return true
        val host = t.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').trimEnd('.')
        if (host.equals("localhost", ignoreCase = true) || IPV4.matches(host)) return true
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        if (!labels.all { label -> label.all { it.isLetterOrDigit() || it == '-' } }) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    /**
     * 3.0: [host] for a box it may not fit in ([fits] measures): "…" and its end when it's too long,
     * so the part that says whose site it is stays in view ("…verify-session.example.net" rather
     * than "accounts.google.com.verify-se"). A scrap of a part ("…om.") is left out.
     */
    fun hostEnd(host: String, fits: (String) -> Boolean): String {
        if (host.length < 2 || fits(host)) return host
        // The longest end that fits after "…" (a shorter end always fits when a longer one does).
        var lo = 1
        var hi = host.length - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (fits("…" + host.substring(mid))) hi = mid else lo = mid + 1
        }
        val end = host.substring(lo)
        val dot = end.indexOf('.')
        if (host[lo - 1] != '.' && dot in 0..3 && (dot == 0 || end.indexOf('.', dot + 1) > 0)) return "…" + end.substring(dot + 1)
        return "…$end"
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

    /** 3.0: [scheme] (of a link a page hands to another app) reaches files on the phone: content: or file:. */
    fun isLocalFile(scheme: String?): Boolean = scheme?.lowercase(Locale.ROOT)?.trim() in setOf("content", "file")

    fun host(url: String?): String? = try {
        if (url.isNullOrBlank()) null else Uri.parse(url).host?.lowercase(Locale.ROOT)?.trimEnd('.')
    } catch (_: Exception) {
        null
    }

    /**
     * 3.0: the host of a web address, worked out from its text alone (lower case, without a user name,
     * port or final dot), as the engine writes addresses. For decisions that are also tested on the
     * PC, where Android's own address reader doesn't run.
     */
    fun plainHost(url: String?): String? {
        val rest = url?.substringAfter("://", "")?.takeIf { it.isNotEmpty() } ?: return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase(Locale.ROOT).trimEnd('.').ifEmpty { null }
    }

    /** 3.0: the path of a web address ("/a/b", "/" when it has none), worked out like [plainHost]. */
    fun plainPath(url: String?): String {
        val rest = url?.substringAfter("://", "") ?: return "/"
        val at = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (at < 0 || rest[at] != '/') return "/"
        return rest.substring(at).substringBefore('?').substringBefore('#')
    }

    /** Registrable domain ("site") of a host, approximated without a public-suffix list. */
    fun siteOfHost(host: String): String {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        if (IPV4.matches(h) || h.startsWith("[") || !h.contains('.')) return h
        // 3.0: a customer's own site on a shared platform; "www." is the platform's own.
        for (suffix in SHARED_HOSTING) {
            if (h.length > suffix.length + 1 && h.endsWith(".$suffix")) {
                val name = h.substring(0, h.length - suffix.length - 1).substringAfterLast('.')
                return if (name == "www") suffix else "$name.$suffix"
            }
        }
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

    /**
     * A page's name for lists (history, bookmarks): its title, except that a search results page
     * whose title is only its address reads "swift browser – DuckDuckGo" (2.2).
     */
    fun readableTitle(title: String?, url: String?): String {
        // Whatever the words (3.0): here they always come with the engine's name.
        val query = SearchEngine.wordsOf(url)?.takeIf { it.isNotBlank() }
        val t = title?.trim().orEmpty()
        val onlyAddress = t.isEmpty() || t.startsWith("http") || (' ' !in t && '.' in t)
        // 3.0: the engine's own site only (not any address with its name in it).
        val engine = SearchEngine.ofHost(host(url))?.label
        if (query != null && onlyAddress) return "$query – ${engine ?: siteName(url)}"
        // A search engine's own page in between (Bing's redirect): just its name.
        if (engine != null && onlyAddress) return engine
        return t.ifEmpty { display(url) }
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

    /**
     * 3.0: invisible characters that reorder or hide text, so "report‮fdp.apk" can't show as
     * "reportkpa.pdf": direction marks and overrides, zero-width spaces and joiners.
     */
    private val HIDDEN_CHARS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(HIDDEN_CHARS, "").replace(BAD_CHARS, "_").trim().trim('.')
        if (cleaned.isEmpty()) return "download"
        if (cleaned.length <= 120) return cleaned
        // Too long: shortened before the extension, which stays (2.6).
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..10 && ' ' !in it }
        return if (ext == null) cleaned.take(120) else cleaned.take(119 - ext.length).trimEnd() + "." + ext
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
