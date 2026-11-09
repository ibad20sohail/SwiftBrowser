package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSizeTest {
    private val gb = 1_000_000_000L

    @Test
    fun theMotoE4KeepsItsPictureMemory() {
        // 2 GB (Android says about 1.9 GB) and 720 × 1280, or less when the navigation bar is left out.
        assertEquals(48 * 1024, PhoneSize.pictureMemoryKb(1_900_000_000L, 720L * 1280))
        assertEquals(48 * 1024, PhoneSize.pictureMemoryKb(1_900_000_000L, 720L * 1184))
        // Never below 48 MB, whatever the screen says.
        assertEquals(48 * 1024, PhoneSize.pictureMemoryKb(1_000_000_000L, 0))
    }

    @Test
    fun sharperScreensGetRoomInProportion() {
        // 1080 × 2400 is 2.8 times the dots of 720 × 1280: 135 MB.
        assertEquals(138_240, PhoneSize.pictureMemoryKb(4 * gb, 1080L * 2400))
        // A 2 GB phone with a sharper screen gets more too, up to a sixteenth of its memory (about 116 MB).
        assertEquals((1_900_000_000L / 16 / 1024).toInt(), PhoneSize.pictureMemoryKb(1_900_000_000L, 1080L * 2400))
        // A big screen on a big phone: in proportion (1440 × 3200 = 5 times: 240 MB).
        assertEquals(245_760, PhoneSize.pictureMemoryKb(8 * gb, 1440L * 3200))
    }

    @Test
    fun only64BitCapablePhonesWithMemoryMoveTo64Bit() {
        // The Moto E4 (2 GB): stays 32-bit even if its Android can run 64-bit apps.
        assertFalse(PhoneSize.wants64Bit(runs64Bit = false, can64Bit = true, totalMemory = 1_900_000_000L))
        assertFalse(PhoneSize.wants64Bit(runs64Bit = false, can64Bit = false, totalMemory = 1_900_000_000L))
        // A newer phone running the 32-bit Swift: moves to 64-bit with its next update.
        assertTrue(PhoneSize.wants64Bit(runs64Bit = false, can64Bit = true, totalMemory = 4 * gb))
        // A 32-bit-only Android keeps the 32-bit Swift, whatever its memory.
        assertFalse(PhoneSize.wants64Bit(runs64Bit = false, can64Bit = false, totalMemory = 6 * gb))
        // A phone already running the 64-bit Swift stays with it.
        assertTrue(PhoneSize.wants64Bit(runs64Bit = true, can64Bit = true, totalMemory = 1_900_000_000L))
    }

    @Test
    fun eachPhoneGetsItsAppFile() {
        val both = listOf("Swift-3.0.6.apk", "Swift-64bit-3.0.6.apk")
        assertEquals("Swift-3.0.6.apk", PhoneSize.pickApk(both, want64 = false, can64Bit = true))
        assertEquals("Swift-64bit-3.0.6.apk", PhoneSize.pickApk(both, want64 = true, can64Bit = true))
        // GitHub lists files by name: whatever the order, the choice is the same.
        assertEquals("Swift-3.0.6.apk", PhoneSize.pickApk(both.reversed(), want64 = false, can64Bit = true))
        // Releases before 3.0.6 have one (32-bit) file: everyone gets it.
        assertEquals("Swift-3.0.5.apk", PhoneSize.pickApk(listOf("Swift-3.0.5.apk"), want64 = true, can64Bit = true))
        // Only a 64-bit file: fine for a phone that can run it, nothing for one that can't.
        val only64 = listOf("Swift-64bit-3.1.apk")
        assertEquals("Swift-64bit-3.1.apk", PhoneSize.pickApk(only64, want64 = false, can64Bit = true))
        assertNull(PhoneSize.pickApk(only64, want64 = false, can64Bit = false))
        // Other files of a release are never taken.
        assertNull(PhoneSize.pickApk(listOf("notes.txt", "Swift-3.0.6.apk.sha256"), want64 = false, can64Bit = true))
    }

    @Test
    fun the32BitFileComesFirstForOlderSwifts() {
        // Swift 3.0.5 and older take the first app file GitHub lists, and GitHub lists them by name:
        // the 32-bit one must come first so a Moto E4 never gets the 64-bit file.
        val names = listOf("Swift-64bit-3.0.6.apk", "Swift-3.0.6.apk")
        assertEquals("Swift-3.0.6.apk", names.sorted().first())
        assertEquals("Swift-3.0.6.apk", names.sortedWith(String.CASE_INSENSITIVE_ORDER).first())
    }
}
