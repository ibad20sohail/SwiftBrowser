package com.swiftbrowser.docs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipIndexTest {
    private fun zip(charset: Charset = Charsets.UTF_8, comment: String? = null, build: (ZipOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { z ->
            build(z)
            if (comment != null) z.setComment(comment)
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.add(name: String, data: ByteArray, stored: Boolean = false) {
        val e = ZipEntry(name)
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = CRC32().apply { update(data) }.value
        }
        putNextEntry(e)
        write(data)
        closeEntry()
    }

    private val text = ("A document in a ZIP. ".repeat(500)).toByteArray()
    private val pdf = "%PDF-1.4 not really".toByteArray()

    private fun sample(comment: String? = null) = zip(comment = comment) { z ->
        z.add("notes.txt", text)
        z.putNextEntry(ZipEntry("folder/"))
        z.closeEntry()
        z.add("folder/report.pdf", pdf, stored = true)
        z.add("Über.txt", "ü".toByteArray())
    }

    @Test
    fun listsFromTheCentralDirectory() {
        val l = ZipIndex.list(ZipIndex.BytesSource(sample()), 100)
        assertEquals(listOf("notes.txt", "folder/", "folder/report.pdf", "Über.txt"), l.entries.map { it.name })
        assertEquals(4L, l.total)
        assertFalse(l.truncated)
        assertEquals(text.size.toLong(), l.entries[0].size)
        assertTrue(l.entries[1].isDirectory)
        assertFalse(l.entries.any { it.encrypted })
    }

    @Test
    fun takesOutOneFile() {
        val src = ZipIndex.BytesSource(sample())
        val l = ZipIndex.list(src, 100)
        assertArrayEquals(text, ZipIndex.open(src, l.entries[0]).use { it.readBytes() })
        assertArrayEquals(pdf, ZipIndex.open(src, l.entries[2]).use { it.readBytes() })
    }

    @Test
    fun aCommentThatLooksLikeTheEndIsntTakenForIt() {
        val bytes = sample(comment = "PK\u0005\u0006 not the end of anything")
        val src = ZipIndex.BytesSource(bytes)
        val l = ZipIndex.list(src, 100)
        assertEquals(4, l.entries.size)
        assertArrayEquals(text, ZipIndex.open(src, l.entries[0]).use { it.readBytes() })
    }

    @Test
    fun aZipJoinedToSomethingStillOpens() {
        // As a self-extracting ZIP: a program first, its offsets counted from the ZIP's own start.
        val bytes = ByteArray(1000) { 0x4D } + sample()
        val src = ZipIndex.BytesSource(bytes)
        val l = ZipIndex.list(src, 100)
        assertArrayEquals(pdf, ZipIndex.open(src, l.entries[2]).use { it.readBytes() })
    }

    @Test
    fun filesLockedWithAPasswordAreKnown() {
        val bytes = sample()
        // Set "encrypted" (bit 0 of the flags) on report.pdf's central directory entry.
        val name = "folder/report.pdf".toByteArray()
        var at = -1
        for (i in bytes.size - 46 downTo 0) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() &&
                bytes.copyOfRange(i + 46, minOf(bytes.size, i + 46 + name.size)).contentEquals(name)
            ) {
                at = i
                break
            }
        }
        assertTrue(at > 0)
        bytes[at + 8] = (bytes[at + 8].toInt() or 1).toByte()
        val src = ZipIndex.BytesSource(bytes)
        val l = ZipIndex.list(src, 100)
        assertTrue(l.entries[2].encrypted)
        assertFalse(l.entries[0].encrypted)
        try {
            ZipIndex.open(src, l.entries[2])
            fail("A locked entry was opened")
        } catch (_: ZipIndex.Locked) {
        }
    }

    @Test
    fun aLongListIsCutButCounted() {
        val bytes = zip { z -> for (i in 0 until 50) z.add("file$i.txt", "x".toByteArray()) }
        val l = ZipIndex.list(ZipIndex.BytesSource(bytes), 10)
        assertEquals(10, l.entries.size)
        assertEquals(50L, l.total)
        assertTrue(l.truncated)
    }

    @Test
    fun oldWindowsNamesReadInTheDosCodePage() {
        val bytes = zip(Charset.forName("CP437")) { z -> z.add("café.txt", "x".toByteArray()) }
        assertEquals("café.txt", ZipIndex.list(ZipIndex.BytesSource(bytes), 10).entries[0].name)
    }

    @Test
    fun moreThan65535EntriesUseZip64() {
        val bytes = zip { z -> for (i in 0 until 65_600) z.add("f$i", ByteArray(0), stored = true) }
        val l = ZipIndex.list(ZipIndex.BytesSource(bytes), 2000)
        assertEquals(2000, l.entries.size)
        assertEquals(65_600L, l.total)
        assertEquals("f1999", l.entries[1999].name)
    }

    @Test
    fun notAZip() {
        for (junk in listOf(ByteArray(0), "%PDF-1.4 a PDF".toByteArray(), ByteArray(5000) { it.toByte() })) {
            try {
                ZipIndex.list(ZipIndex.BytesSource(junk), 10)
                fail("Listed something that isn't a ZIP")
            } catch (_: ZipIndex.NotZip) {
            }
        }
    }

    @Test
    fun aDamagedDirectoryDoesntRunPastTheFile() {
        val bytes = sample()
        // The end record says the directory is far bigger than the file.
        var eocd = bytes.size - 22
        while (!(bytes[eocd] == 0x50.toByte() && bytes[eocd + 1] == 0x4B.toByte() && bytes[eocd + 2] == 5.toByte() && bytes[eocd + 3] == 6.toByte())) eocd--
        bytes[eocd + 12] = 0xFF.toByte()
        bytes[eocd + 13] = 0xFF.toByte()
        bytes[eocd + 14] = 0x0F.toByte()
        try {
            ZipIndex.list(ZipIndex.BytesSource(bytes), 10)
            fail("Read a directory that can't be there")
        } catch (_: ZipIndex.NotZip) {
        }
    }
}
