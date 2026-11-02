package com.swiftbrowser.docs

/**
 * The rules the helper extension's channel to Swift keeps (3.0), as plain logic tested on the PC.
 *
 * Pages can't reach Swift, but the extension's content scripts run inside every website's process,
 * and GeckoView 143 gets no more security fixes: a site that takes over its process could send what
 * a content script can. So each message is taken only from the kind of sender that really sends it,
 * which the engine itself reports (it checks that an extension page is one of the helper's own,
 * running in the extensions' process): the document viewer's messages from the viewer page, read
 * aloud from the viewer and reader view, a page's video reports from content scripts. And a viewer
 * page may only ask for the document it was opened with.
 */
object DocRules {
    /** Who sent a message from the helper extension. */
    enum class Sender { CONTENT_SCRIPT, VIEWER, READER, OTHER }

    /**
     * What the content scripts in websites' frames send (content.js). "google-account" (3.0) is
     * checked again in Kotlin: only from Google's own https pages, never from incognito.
     */
    private val FROM_PAGES = setOf(
        "hello", "click", "icon", "playing", "video-pos", "video-start", "media-ended", "media-error", "media-waiting",
        "google-account",
    )

    /** The document viewer's own messages (docs/view.js). */
    private val FROM_VIEWER = setOf(
        "doc-hello", "doc-read", "doc-range", "doc-place", "doc-action", "doc-reading", "doc-zip", "doc-save", "doc-convert", "doc-thumb",
    )

    /** Read aloud, from the document viewer and reader view. */
    private val READ_ALOUD = setOf("tts-start", "tts-pause", "tts-resume", "tts-stop", "tts-skip", "tts-rate")

    /** A document's id as [Docs.idFor] makes it: exactly 20 lowercase hex characters (nothing like "../x"). */
    fun validId(id: String?): Boolean = id != null && id.length == 20 && id.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The sender as the engine reports it: [contentScript] or [extensionPage] (its environment type)
     * and [url], the address of the sender's page. [base] is the helper's moz-extension://…/ address.
     */
    fun sender(contentScript: Boolean, extensionPage: Boolean, url: String?, base: String?): Sender {
        if (contentScript) return Sender.CONTENT_SCRIPT
        if (!extensionPage || url.isNullOrEmpty() || base.isNullOrEmpty()) return Sender.OTHER
        val root = if (base.endsWith("/")) base else "$base/"
        if (!url.startsWith(root)) return Sender.OTHER
        return when (url.substring(root.length).substringBefore('#').substringBefore('?')) {
            "docs/view.html" -> Sender.VIEWER
            "reader/view.html" -> Sender.READER
            else -> Sender.OTHER
        }
    }

    /** Whether [sender] may send a message of [type]. Anything not listed is refused. */
    fun allowed(type: String, sender: Sender): Boolean = when (sender) {
        Sender.CONTENT_SCRIPT -> type in FROM_PAGES
        Sender.VIEWER -> type in FROM_VIEWER || type in READ_ALOUD
        Sender.READER -> type in READ_ALOUD || type == "reader-place"
        Sender.OTHER -> false
    }

    /** The document a viewer page was opened with: the id after the # of its address, when it is one. */
    fun idInUrl(url: String?): String? = url?.substringAfter('#', "")?.takeIf { validId(it) }

    /**
     * Whether a viewer page may ask for document [id]: its own one, from the address the engine
     * reports for it ([senderUrl]) or, when that has none, the one its screen was opened for ([registered]).
     */
    fun ownDocument(id: String?, senderUrl: String?, registered: String?): Boolean {
        if (!validId(id)) return false
        val own = idInUrl(senderUrl) ?: registered?.takeIf { validId(it) } ?: return false
        return id == own
    }

    /**
     * The name a file the viewer made is saved under: the viewer only makes PDFs (a filled-in form),
     * so whatever the page says, it is a .pdf (never an app or a page that opens itself).
     */
    fun savedPdfName(name: String?): String {
        val n = name.orEmpty().trim()
        return when {
            n.isEmpty() || n.equals(".pdf", ignoreCase = true) -> "document.pdf"
            n.endsWith(".pdf", ignoreCase = true) -> n
            else -> "$n.pdf"
        }
    }

    /** The first bytes of a PDF ("%PDF-"), as every file the viewer saves starts. */
    fun looksLikePdf(head: ByteArray): Boolean {
        val sig = "%PDF-".toByteArray(Charsets.US_ASCII)
        if (head.size < sig.size) return false
        // A few bytes of rubbish may come first (some programs write them); PDF readers allow 1 KB.
        val room = minOf(head.size - sig.size, 1024)
        for (at in 0..room) {
            var match = true
            for (k in sig.indices) if (head[at + k] != sig[k]) {
                match = false
                break
            }
            if (match) return true
        }
        return false
    }
}
