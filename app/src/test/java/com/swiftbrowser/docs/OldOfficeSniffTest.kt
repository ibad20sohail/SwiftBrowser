package com.swiftbrowser.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a file named .doc or .ppt really is, by its first bytes (the viewer then shows it as that). */
class OldOfficeSniffTest {
    private fun head(s: String) = s.toByteArray(Charsets.ISO_8859_1).copyOf(8).let { it to minOf(8, s.length) }

    private fun kind(s: String) = head(s).let { (b, n) -> OldOffice.headKind(b, n) }

    @Test
    fun compoundFilesAndZips() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertTrue(OldOffice.isCompound(ole, 8))
        assertFalse(OldOffice.isCompound(ole, 7))
        assertFalse(OldOffice.isCompound(ole.copyOf(8).also { it[7] = 0 }, 8))
        assertTrue(OldOffice.isZip("PK\u0003\u0004rest".toByteArray(), 8))
        assertFalse(OldOffice.isZip("PK\u0005\u0006".toByteArray(), 4))
        assertFalse(OldOffice.isZip("PK".toByteArray(), 2))
    }

    @Test
    fun otherKindsByTheirStart() {
        assertEquals("rtf", kind("{\\rtf1\\ansi"))
        assertEquals("rtf", kind("ï»¿{\\rtf1"))
        assertEquals("pdf", kind("%PDF-1.7"))
        assertEquals("html", kind("<html>"))
        assertEquals("html", kind("  <!DOCTY"))
        assertNull(kind("Hello th"))
        assertNull(OldOffice.headKind(ByteArray(8), 0))
    }
}
