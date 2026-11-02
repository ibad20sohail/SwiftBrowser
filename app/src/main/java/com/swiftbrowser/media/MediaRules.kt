package com.swiftbrowser.media

/** Checks on what the helper extension reports from pages (3.0.1). */
object MediaRules {
    /** The longest video address kept: signed links run long, but not this long. */
    const val MAX_URL = 8192

    /**
     * Whether a detected video's address is one Swift can fetch: http(s) with a host, no spaces or
     * control characters, not too long. Anything else from a page is dropped, never shown or used.
     */
    fun acceptable(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL) return false
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (host.isEmpty() || host.startsWith(':')) return false
        return url.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }
    }
}
