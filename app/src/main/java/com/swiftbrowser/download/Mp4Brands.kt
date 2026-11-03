package com.swiftbrowser.download

import java.io.File
import java.io.RandomAccessFile

/**
 * 3.0.2: Android 7 reads an MP4 file only if its first box (ftyp) names a brand from its own short
 * list. DASH and CMAF parts often name only newer ones (iso5, iso6, dash, msix, cmfc), so the phone
 * refused a stream's sound file and the picture and sound couldn't be joined. One brand in the list
 * is renamed to isom (the general one); the box keeps its size and nothing else changes.
 */
object Mp4Brands {
    /** The brands Android 7's MP4 reader accepts anywhere in the list. */
    private val KNOWN = setOf("isom", "iso2", "avc1", "hvc1", "hev1", "3gp4", "mp41", "mp42", "qt  ", "MSNV", "3g2a", "3g2b")

    /** And the main brands its older check accepts. */
    private val KNOWN_MAIN = setOf("mp42", "3gr6", "3gs6", "3ge6", "3gg6", "isom", "M4V ", "M4A ", "f4v ", "kddi", "M4VP")

    /** Fixes [head] (a file's first bytes) in place; whether it changed. */
    fun fix(head: ByteArray): Boolean {
        if (head.size < 16 || brand(head, 4) != "ftyp") return false
        val size = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        if (size < 16) return false
        val major = brand(head, 8)
        val others = (16..minOf(size, head.size) - 4 step 4).toList()
        if (major in KNOWN || major in KNOWN_MAIN || major.startsWith("3gp") || others.any { brand(head, it) in KNOWN }) return false
        // The list's copy of the main brand if it has one, else its last brand, else the main one.
        val at = others.firstOrNull { brand(head, it) == major } ?: others.lastOrNull() ?: 8
        "isom".toByteArray(Charsets.ISO_8859_1).copyInto(head, at)
        return true
    }

    /** [fix] on [file]'s first bytes, written back if they changed. */
    fun fixFile(file: File): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(minOf(64L, raf.length()).toInt())
            raf.readFully(head)
            if (!fix(head)) return@use false
            raf.seek(0)
            raf.write(head)
            true
        }
    }.getOrDefault(false)

    private fun brand(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)
}
