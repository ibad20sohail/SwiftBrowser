package com.swiftbrowser.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackChoiceTest {
    private val tracks = listOf<Pair<String?, String?>>(
        "ru" to null,
        "en" to "Commentary",
        "en" to "Main",
        "fr" to null,
    )

    @Test
    fun theSameLanguageAndNameComeFirst() {
        assertEquals(2, TrackChoice.match("en", "Main", tracks))
        assertEquals(1, TrackChoice.match("en", "Commentary", tracks))
        assertEquals(3, TrackChoice.match("fr", null, tracks))
        // Languages as ExoPlayer gives them, in any case.
        assertEquals(2, TrackChoice.match("EN", "Main", tracks))
    }

    @Test
    fun aRenamedTrackIsFoundByItsLanguage() {
        assertEquals(1, TrackChoice.match("en", "Director's cut", tracks))
        assertEquals(0, TrackChoice.match("ru", "Dub", tracks))
        assertEquals(-1, TrackChoice.match("de", "Main", tracks))
    }

    @Test
    fun withoutALanguageTheNameIsEnough() {
        val unnamed = listOf<Pair<String?, String?>>(null to "Signs", "und" to "Full")
        assertEquals(1, TrackChoice.match(null, "Full", unnamed))
        assertEquals(1, TrackChoice.match("und", "Full", unnamed))
        assertEquals(-1, TrackChoice.match(null, null, unnamed))
        assertEquals(-1, TrackChoice.match("", " ", unnamed))
    }

    @Test
    fun choicesReplaceWhatTheyAreAbout() {
        val sound = TrackChoice().withSound("en", "Main")
        assertTrue(sound.hasSound)
        assertFalse(sound.hasSubtitles)
        val off = sound.withSubtitlesOff()
        assertTrue(off.subtitlesOff)
        assertTrue(off.hasSound)
        val file = off.withSubtitleFile("/sdcard/Movies/Film.srt")
        assertFalse(file.subtitlesOff)
        assertEquals("/sdcard/Movies/Film.srt", file.subtitleFile)
        assertFalse(file.hasOwnSubtitles)
        val own = file.withSubtitles("fr", " ")
        assertNull(own.subtitleFile)
        assertNull(own.subtitleLabel)
        assertTrue(own.hasOwnSubtitles)
        // A track with neither a language nor a name can't be found again: nothing is kept for it.
        assertTrue(TrackChoice().withSound("und", null).isEmpty)
        assertFalse(TrackChoice().withSubtitles(null, null).hasSubtitles)
    }
}
