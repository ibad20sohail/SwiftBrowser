package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BinAndErrorsTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_790_000_000_000L // 2026

    @Test
    fun thingsGoOnlyAfterTheirDays() {
        assertFalse(BinClock.due(now - 6 * day, now, 7))
        assertTrue(BinClock.due(now - 8 * day, now, 7))
        assertFalse(BinClock.due(now - 8 * day, now, 30))
    }

    @Test
    fun aResetClockDoesntEraseEverything() {
        // Deleted while the clock said 1970 or 2000: counted from now, not gone at once.
        assertFalse(BinClock.due(0L, now, 7))
        assertFalse(BinClock.due(946_684_800_000L, now, 7))
        assertEquals(now, BinClock.fixed(946_684_800_000L, now))
        // Deleted while the clock was ahead (a date still to come): counted from now too.
        assertEquals(now, BinClock.fixed(now + 400 * day, now))
        assertFalse(BinClock.due(now + 400 * day, now, 7))
        // The clock itself wrong (before 2020): nothing goes.
        assertFalse(BinClock.sane(946_684_800_000L))
        assertFalse(BinClock.due(0L, 946_684_800_000L + 100 * day, 7))
        // A sane date stays as it is.
        assertEquals(now - day, BinClock.fixed(now - day, now))
    }

    @Test
    fun usualErrorsInWords() {
        assertEquals(FileErrors.FULL, FileErrors.text(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(FileErrors.CARD_FULL, FileErrors.text(IOException("write failed: ENOSPC (No space left on device)"), onCard = true))
        assertEquals(FileErrors.FAT32, FileErrors.text(IOException("write failed: EFBIG (File too large)")))
        // "Invalid argument" means FAT32's limit only when the file had got to about 4 GB.
        assertEquals(FileErrors.FAT32, FileErrors.text(IOException("write failed: EINVAL (Invalid argument)"), size = FileErrors.FAT32_MAX - 1000))
        assertNull(FileErrors.text(IOException("write failed: EINVAL (Invalid argument)"), size = 1000))
        assertEquals(FileErrors.NOT_ALLOWED, FileErrors.text(IOException("open failed: EACCES (Permission denied)")))
        assertEquals(FileErrors.NOT_ALLOWED, FileErrors.text(IOException("open failed: EROFS (Read-only file system)")))
        assertEquals(FileErrors.NOT_ALLOWED, FileErrors.text(SecurityException("no")))
        // Found in the cause too.
        assertEquals(FileErrors.FULL, FileErrors.text(RuntimeException("copy", IOException("ENOSPC"))))
        assertNull(FileErrors.text(IOException("Connection reset")))
    }
}
