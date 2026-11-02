package com.swiftbrowser.download

import com.swiftbrowser.download.DownloadRules.Resume
import com.swiftbrowser.download.DownloadRules.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DownloadRulesTest {
    @Test
    fun errorsAreSaidInPlainWords() {
        val full = DownloadRules.explain(IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals(Why.FULL, full.why)
        assertEquals("The phone is full.", full.text)
        assertTrue(full.retryable)

        val refused = DownloadRules.explain(IOException("Segment error 403"))
        assertEquals(Why.REFUSED, refused.why)
        assertTrue(refused.text.startsWith("The site refused — the link may have expired"))
        assertFalse(refused.retryable)

        assertEquals(Why.NETWORK, DownloadRules.explain(SocketTimeoutException("timeout")).why)
        assertEquals(Why.NETWORK, DownloadRules.explain(IOException("Server error 503")).why)
        assertEquals(Why.REFUSED, DownloadRules.explain(IOException("Server error 404")).why)
    }

    @Test
    fun liveStreamsAndCopyProtectionAreNeverTriedAgain() {
        assertEquals(Why.PERMANENT, DownloadRules.explain(DownloadRules.Failure(Why.PERMANENT, DownloadRules.LIVE)).why)
        // Rows from before 3.0 kept these words: they weren't network errors then either.
        assertFalse(DownloadRules.legacyNetwork("This is a live stream, it can't be downloaded"))
        assertFalse(DownloadRules.legacyNetwork("This video is copy-protected and can't be saved"))
        assertFalse(DownloadRules.legacyNetwork("The link expired (403). Start the download again from the page."))
        assertTrue(DownloadRules.legacyNetwork("Network error: NS_ERROR_NET_RESET"))
        assertTrue(DownloadRules.legacyNetwork("Connection closed early"))
        assertFalse(DownloadRules.reword("This is a live stream, it can't be downloaded").retryable)
    }

    @Test
    fun aResumedDownloadIsOnlyAppendedToWhereItStopped() {
        assertEquals(Resume.APPEND, DownloadRules.resume(1000, 206, "bytes 1000-1999/2000", 2000))
        // A part from elsewhere, or the whole file again (it changed, If-Range): never mixed in.
        assertEquals(Resume.RESTART, DownloadRules.resume(1000, 206, "bytes 0-1999/2000", 2000))
        assertEquals(Resume.RESTART, DownloadRules.resume(1000, 200, null, 2000))
        assertEquals(Resume.DONE, DownloadRules.resume(2000, 416, "bytes */2000", -1))
        assertEquals(Resume.DONE, DownloadRules.resume(2000, 416, null, 2000))
        assertEquals(Resume.RESTART, DownloadRules.resume(2500, 416, "bytes */2000", -1))
        assertEquals(Resume.FAIL, DownloadRules.resume(1000, 403, null, 2000))
    }

    @Test
    fun contentRangesAreRead() {
        assertEquals(100L, DownloadRules.rangeStart("bytes 100-199/1000"))
        assertNull(DownloadRules.rangeStart("bytes */1000"))
        assertEquals(1000L, DownloadRules.rangeTotal("bytes 100-199/1000"))
        assertNull(DownloadRules.rangeTotal("bytes 100-199/*"))
        assertEquals("\"abc\"", DownloadRules.ifRange("\"abc\"", "Wed, 21 Oct 2015 07:28:00 GMT"))
        // A weak ETag can't be used with If-Range: the date instead.
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", DownloadRules.ifRange("W/\"abc\"", "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(DownloadRules.ifRange(null, ""))
    }

    @Test
    fun bigFilesWaitForWifiOnceTheirSizeIsKnown() {
        val mb = 1024L * 1024
        assertTrue(DownloadRules.mustWait(true, true, hls = false, total = 1024 * mb, done = 0, mime = "application/octet-stream"))
        assertFalse(DownloadRules.mustWait(true, true, hls = false, total = 10 * mb, done = 0, mime = "application/octet-stream"))
        assertFalse(DownloadRules.mustWait(true, false, hls = false, total = 1024 * mb, done = 0, mime = null))
        assertFalse(DownloadRules.mustWait(false, true, hls = true, total = -1, done = 0, mime = null))
        // Size unknown: a video waits, and so does anything that has grown past 50 MB.
        assertTrue(DownloadRules.mustWait(true, true, hls = false, total = -1, done = 0, mime = "video/mp4"))
        assertTrue(DownloadRules.mustWait(true, true, hls = false, total = -1, done = 60 * mb, mime = null))
        assertTrue(DownloadRules.mustWait(true, true, hls = true, total = -1, done = 0, mime = null))
    }

    @Test
    fun streamsBecomeMp4OnlyWhenThatIsSafe() {
        val mb = 1024L * 1024
        assertEquals(DownloadRules.Convert.OK, DownloadRules.convert(700 * mb, 2048 * mb))
        assertEquals(DownloadRules.Convert.NO_ROOM, DownloadRules.convert(700 * mb, 720 * mb))
        assertEquals(DownloadRules.Convert.TOO_BIG, DownloadRules.convert(4_500_000_000L, 10_000 * mb))
        assertEquals(DownloadRules.Convert.OK, DownloadRules.convert(700 * mb, -1))

        val hour = 3_600_000_000L
        assertTrue(DownloadRules.lengthOk(hour, hour - 1_000_000))
        assertFalse(DownloadRules.lengthOk(hour, hour / 2))
        // An MP4 cut short has no index: no length at all.
        assertFalse(DownloadRules.lengthOk(hour, 0))
        assertTrue(DownloadRules.lengthOk(0, 5_000_000))
    }

    @Test
    fun aStreamWithAllItsPartsNeedsNoSite() {
        assertTrue(DownloadRules.streamSaved(120, 120, 0, 0, 5_000, 5_000))
        assertTrue(DownloadRules.streamSaved(120, 120, 40, 40, 5_100, 5_000))
        assertFalse(DownloadRules.streamSaved(119, 120, 0, 0, 5_000, 5_000))
        assertFalse(DownloadRules.streamSaved(120, 120, 39, 40, 5_000, 5_000))
        // The file is shorter than what was counted: it is fetched again.
        assertFalse(DownloadRules.streamSaved(120, 120, 0, 0, 4_000, 5_000))
        assertFalse(DownloadRules.streamSaved(0, 0, 0, 0, 0, 0))
    }

    @Test
    fun theSameAddressAgainShowsWhatIsThereFirst() {
        assertEquals(DownloadRules.Again.NEW, DownloadRules.again(null, fileThere = false))
        for (going in listOf(Downloads.QUEUED, Downloads.RUNNING, Downloads.PAUSED, Downloads.CONVERTING)) {
            assertEquals(DownloadRules.Again.BUSY, DownloadRules.again(going, fileThere = false))
        }
        assertEquals(DownloadRules.Again.DONE, DownloadRules.again(Downloads.DONE, fileThere = true))
        // Finished but the file was deleted since, or it failed: a new download, as before.
        assertEquals(DownloadRules.Again.NEW, DownloadRules.again(Downloads.DONE, fileThere = false))
        assertEquals(DownloadRules.Again.NEW, DownloadRules.again(Downloads.FAILED, fileThere = true))
    }
}
