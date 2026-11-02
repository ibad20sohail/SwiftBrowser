package com.swiftbrowser.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class StorageReadTest {
    @Test
    fun aSmallFileIsReadWhole() {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, Storage.readAtMost(ByteArrayInputStream(bytes), 64 * 1024))
        assertArrayEquals(ByteArray(0), Storage.readAtMost(ByteArrayInputStream(ByteArray(0)), 10))
        // Exactly the limit is still fine.
        assertArrayEquals(ByteArray(10), Storage.readAtMost(ByteArrayInputStream(ByteArray(10)), 10))
    }

    @Test
    fun aBigFileStopsAtTheLimitWithoutBeingReadWhole() {
        // A "file" of a gigabyte that would never fit in memory.
        var given = 0L
        val endless = object : InputStream() {
            override fun read(): Int = if (given++ < 1L shl 30) 7 else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (given >= 1L shl 30) return -1
                java.util.Arrays.fill(b, off, off + len, 7)
                given += len
                return len
            }
        }
        assertNull(Storage.readAtMost(endless, 64 * 1024))
        assertTrue("read $given bytes", given < 200 * 1024)
    }
}
