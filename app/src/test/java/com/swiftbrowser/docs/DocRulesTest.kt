package com.swiftbrowser.docs

import com.swiftbrowser.docs.DocRules.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DocRulesTest {
    private val base = "moz-extension://5c2f1d6e-0000-4000-8000-123456789abc/"
    private val id = "0123456789abcdef0123"

    /** As Docs.idFor makes ids (Docs itself needs Android to load). */
    private fun idFor(key: String) = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(10).joinToString("") { String.format("%02x", it) }

    @Test
    fun idsAreTwentyHexDigits() {
        assertTrue(DocRules.validId(id))
        assertTrue(DocRules.validId(idFor("file:///sdcard/Download/a.pdf")))
        assertTrue(DocRules.validId(idFor("incognito|content://com.swiftbrowser.app.files/private/x.pdf")))
        assertFalse(DocRules.validId(null))
        assertFalse(DocRules.validId(""))
        assertFalse(DocRules.validId(id.dropLast(1)))
        assertFalse(DocRules.validId(id + "0"))
        assertFalse(DocRules.validId(id.uppercase()))
        // What once made a file name outside Swift's folder (Docs.savePart: File(dir, id + ".part")).
        assertFalse(DocRules.validId("../../files/private/"))
        assertFalse(DocRules.validId("0123456789abcdef/../"))
    }

    @Test
    fun sendersAreToldApartByTheEnginesReport() {
        assertEquals(Sender.CONTENT_SCRIPT, DocRules.sender(contentScript = true, extensionPage = false, url = "https://example.com/", base = base))
        assertEquals(Sender.VIEWER, DocRules.sender(false, true, base + "docs/view.html?screen#$id", base))
        assertEquals(Sender.VIEWER, DocRules.sender(false, true, base + "docs/view.html#$id", base))
        assertEquals(Sender.READER, DocRules.sender(false, true, base + "reader/view.html#lw3k2", base))
        // The background page, another extension, nothing reported.
        assertEquals(Sender.OTHER, DocRules.sender(false, true, base + "_generated_background_page.html", base))
        assertEquals(Sender.OTHER, DocRules.sender(false, true, "moz-extension://another-uuid/docs/view.html#$id", base))
        assertEquals(Sender.OTHER, DocRules.sender(false, true, null, base))
        assertEquals(Sender.OTHER, DocRules.sender(false, false, base + "docs/view.html#$id", base))
        assertEquals(Sender.OTHER, DocRules.sender(false, true, base + "docs/view.html.evil#$id", base))
        // A base address without its last slash still matches.
        assertEquals(Sender.VIEWER, DocRules.sender(false, true, base + "docs/view.html#$id", base.trimEnd('/')))
    }

    @Test
    fun eachMessageOnlyFromItsSender() {
        for (type in listOf("doc-hello", "doc-read", "doc-range", "doc-save", "doc-action", "doc-convert", "doc-zip", "doc-thumb")) {
            assertTrue(type, DocRules.allowed(type, Sender.VIEWER))
            assertFalse(type, DocRules.allowed(type, Sender.CONTENT_SCRIPT))
            assertFalse(type, DocRules.allowed(type, Sender.READER))
            assertFalse(type, DocRules.allowed(type, Sender.OTHER))
        }
        for (type in listOf("hello", "click", "icon", "playing", "video-pos", "video-start", "media-ended", "media-error", "media-waiting", "google-account")) {
            assertTrue(type, DocRules.allowed(type, Sender.CONTENT_SCRIPT))
            assertFalse(type, DocRules.allowed(type, Sender.VIEWER))
        }
        assertTrue(DocRules.allowed("tts-start", Sender.VIEWER))
        assertTrue(DocRules.allowed("tts-start", Sender.READER))
        assertFalse(DocRules.allowed("tts-start", Sender.CONTENT_SCRIPT))
        assertTrue(DocRules.allowed("reader-place", Sender.READER))
        assertFalse(DocRules.allowed("reader-place", Sender.CONTENT_SCRIPT))
        assertFalse(DocRules.allowed("something-new", Sender.VIEWER))
    }

    @Test
    fun aViewerAsksOnlyForItsOwnDocument() {
        val other = "fedcba9876543210fedc"
        assertTrue(DocRules.ownDocument(id, base + "docs/view.html?screen#$id", null))
        assertFalse(DocRules.ownDocument(other, base + "docs/view.html?screen#$id", null))
        // The page's address wins over what its screen was opened for.
        assertFalse(DocRules.ownDocument(other, base + "docs/view.html#$id", other))
        // No id in the address: the screen's own document.
        assertTrue(DocRules.ownDocument(id, base + "docs/view.html", id))
        assertFalse(DocRules.ownDocument(other, base + "docs/view.html", id))
        assertFalse(DocRules.ownDocument(id, null, null))
        assertFalse(DocRules.ownDocument("../x", base + "docs/view.html#../x", "../x"))
        assertEquals(id, DocRules.idInUrl(base + "docs/view.html?screen#$id"))
    }

    @Test
    fun savedFilesArePdfs() {
        assertEquals("form (filled in).pdf", DocRules.savedPdfName("form (filled in).pdf"))
        assertEquals("FORM.PDF", DocRules.savedPdfName("FORM.PDF"))
        assertEquals("update.apk.pdf", DocRules.savedPdfName("update.apk"))
        assertEquals("page.html.pdf", DocRules.savedPdfName("page.html"))
        assertEquals("document.pdf", DocRules.savedPdfName(""))
        assertEquals("document.pdf", DocRules.savedPdfName(null))
        assertTrue(DocRules.looksLikePdf("%PDF-1.7\n".toByteArray()))
        assertTrue(DocRules.looksLikePdf("\r\n%PDF-1.4".toByteArray()))
        assertFalse(DocRules.looksLikePdf("PK\u0003\u0004".toByteArray()))
        assertFalse(DocRules.looksLikePdf("%PD".toByteArray()))
        assertFalse(DocRules.looksLikePdf(ByteArray(2000) + "%PDF-".toByteArray()))
    }
}
