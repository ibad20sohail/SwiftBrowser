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
    fun aStreamsSoundCountsByItsShareOfTheBytes() {
        // 159 sound parts, then 159 video parts; the sound is 5% of the bytes.
        assertEquals(5, DownloadRules.streamPercent(159, 318, 159, 0.05))
        assertEquals(2, DownloadRules.streamPercent(80, 318, 159, 0.05))
        assertEquals(52, DownloadRules.streamPercent(159 + 80, 318, 159, 0.05))
        assertEquals(100, DownloadRules.streamPercent(318, 318, 159, 0.05))
        // No separate sound: by parts, as before.
        assertEquals(50, DownloadRules.streamPercent(60, 120, 0, 0.05))
        assertEquals(-1, DownloadRules.streamPercent(0, 0, 0, 0.05))
    }

    @Test
    fun aStreamsShareIsItsPercentWithoutRounding() {
        assertEquals(0.05 + 0.95 * 80 / 159, DownloadRules.streamFraction(159 + 80, 318, 159, 0.05), 1e-9)
        assertEquals(0.5, DownloadRules.streamFraction(60, 120, 0, 0.05), 1e-9)
        assertEquals(1.0, DownloadRules.streamFraction(318, 318, 159, 0.05), 1e-9)
        assertEquals(-1.0, DownloadRules.streamFraction(0, 0, 0, 0.05), 1e-9)
        for (done in 0..318) {
            val share = DownloadRules.streamFraction(done, 318, 159, 0.05)
            assertEquals((share * 100).toInt(), DownloadRules.streamPercent(done, 318, 159, 0.05))
        }
    }

    @Test
    fun timeLeftIsSaidOnceThereIsEnoughToGoOn() {
        val mb = 1_048_576L
        // A file: 25 MB of 100 MB in, at 1 MB a second.
        assertEquals(75L, DownloadRules.secondsLeft(25 * mb, 0.25, mb))
        // Under 2% in, no speed yet, nothing in or nothing known: not said.
        assertNull(DownloadRules.secondsLeft(mb, 0.01, mb))
        assertNull(DownloadRules.secondsLeft(25 * mb, 0.25, 0))
        assertNull(DownloadRules.secondsLeft(0, 0.25, mb))
        assertNull(DownloadRules.secondsLeft(25 * mb, -1.0, mb))
        assertNull(DownloadRules.secondsLeft(25 * mb, Double.NaN, mb))
        // All of it in: nothing left.
        assertEquals(0L, DownloadRules.secondsLeft(100 * mb, 1.0, mb))
    }

    @Test
    fun aStreamsTimeLeftComesFromItsParts() {
        val mb = 1_048_576L
        // 10 sound parts (8% of the bytes), then 100 video parts; the sound and half the video are in,
        // 54 MB so far, at 2 MB a second: about 100 MB in all, 46 MB (23 seconds) to go.
        val share = DownloadRules.streamFraction(60, 110, 10, 0.08)
        assertEquals(0.54, share, 1e-9)
        assertEquals(23L, DownloadRules.secondsLeft(54 * mb, share, 2 * mb))
        // The sound alone is only 8% of it: still most of the video to come.
        val sound = DownloadRules.streamFraction(10, 110, 10, 0.08)
        assertEquals(92L, DownloadRules.secondsLeft(8 * mb, sound, mb))
        // Its first part (under 2%) says nothing yet.
        assertNull(DownloadRules.secondsLeft(mb / 10, DownloadRules.streamFraction(1, 110, 10, 0.08), mb))
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
