package com.swiftbrowser.download

import com.swiftbrowser.download.Dash.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashTest {
    @Test
    fun theBestH264UpTo1080pIsSaved() {
        val list = listOf(
            Choice(2160, 16_000_000, "avc1.640033"),
            Choice(720, 3_000_000, "avc1.64001f"),
            Choice(1080, 6_000_000, "avc1.640028"),
            Choice(1080, 4_000_000, "vp09.00.40.08"),
        )
        assertEquals(2, Dash.bestVideo(list))
    }

    @Test
    fun h264BeforeHevcAndNothingAnMp4CantHold() {
        assertEquals(1, Dash.bestVideo(listOf(Choice(1080, 5_000_000, "hev1.1.6.L120"), Choice(720, 3_000_000, "avc1.64001f"))))
        assertEquals(0, Dash.bestVideo(listOf(Choice(1080, 5_000_000, "hvc1.1.6.L120"), Choice(720, 3_000_000, "vp09.00.31.08"))))
        assertNull(Dash.bestVideo(listOf(Choice(1080, 5_000_000, "vp09.00.40.08"), Choice(1080, 4_000_000, "av01.0.08M.08"))))
    }

    @Test
    fun onlyBiggerThan1080pTakesTheSmallest() {
        assertEquals(1, Dash.bestVideo(listOf(Choice(2160, 16_000_000, "avc1.640033"), Choice(1440, 9_000_000, "avc1.640032"))))
    }

    @Test
    fun theBestAacSoundGoesWithIt() {
        assertEquals(1, Dash.bestAudio(listOf(Choice(0, 64_000, "mp4a.40.5"), Choice(0, 128_000, "mp4a.40.2"), Choice(0, 384_000, "ec-3"))))
        assertNull(Dash.bestAudio(listOf(Choice(0, 128_000, "opus"))))
    }

    @Test
    fun theSoundIsInThePhonesLanguageThenTheMainOne() {
        val films = listOf(
            Choice(0, 192_000, "mp4a.40.2", "es"),
            Choice(0, 128_000, "mp4a.40.2", "en-US"),
            Choice(0, 160_000, "mp4a.40.2", "de", main = true),
            Choice(0, 256_000, "opus", "en"),
        )
        assertEquals(1, Dash.bestAudio(films, "en"))
        assertEquals(2, Dash.bestAudio(films, "fr"))
        assertEquals(2, Dash.bestAudio(films, null))
        assertEquals(0, Dash.bestAudio(films.take(2), "fr"))
    }

    @Test
    fun manifestsAreKnownByTheirMpdElement() {
        assertTrue(Dash.isManifest("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\">"))
        assertTrue(Dash.isManifest("<MPD type=\"static\"></MPD>"))
        assertFalse(Dash.isManifest("#EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(Dash.isManifest("<html><body>MPD</body></html>"))
    }
}
