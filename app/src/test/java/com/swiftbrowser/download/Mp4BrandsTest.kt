package com.swiftbrowser.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4BrandsTest {
    private fun ftyp(major: String, vararg others: String): ByteArray {
        val size = 16 + 4 * others.size
        val out = ByteArray(size + 8)
        out[3] = size.toByte()
        "ftyp".toByteArray().copyInto(out, 4)
        major.toByteArray().copyInto(out, 8)
        out[15] = 1
        others.forEachIndexed { i, b -> b.toByteArray().copyInto(out, 16 + 4 * i) }
        "moov".toByteArray().copyInto(out, size + 4)
        return out
    }

    @Test
    fun dashPartsNamingOnlyNewBrandsGetIsom() {
        val sound = ftyp("iso5", "iso5", "dash", "msix")
        assertTrue(Mp4Brands.fix(sound))
        assertArrayEquals(ftyp("iso5", "isom", "dash", "msix"), sound)
        val cmaf = ftyp("cmfc", "iso6", "cmfa")
        assertTrue(Mp4Brands.fix(cmaf))
        assertArrayEquals(ftyp("cmfc", "iso6", "isom"), cmaf)
        val bare = ftyp("dash")
        assertTrue(Mp4Brands.fix(bare))
        assertArrayEquals(ftyp("isom"), bare)
    }

    @Test
    fun filesAndroidAlreadyReadsAreLeftAlone() {
        for (ok in listOf(ftyp("iso5", "iso5", "dash", "msix", "avc1"), ftyp("mp42", "isom", "mp42"), ftyp("M4A ", "M4A ", "mp42"), ftyp("3gp5", "3gp5"))) {
            val before = ok.copyOf()
            assertFalse(Mp4Brands.fix(ok))
            assertArrayEquals(before, ok)
        }
        assertFalse(Mp4Brands.fix("not an mp4 at all".toByteArray()))
        assertFalse(Mp4Brands.fix(ByteArray(8)))
    }
}
