package com.swiftbrowser.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubtitlesLanguageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aFileNameSaysItsLanguage() {
        assertTrue(Subtitles.inLanguage(File("Film.ur.srt"), "ur"))
        assertTrue(Subtitles.inLanguage(File("3_Urdu.srt"), "ur"))
        assertTrue(Subtitles.inLanguage(File("Film.eng.srt"), "en"))
        assertFalse(Subtitles.inLanguage(File("Film.srt"), "ur"))
        // Not part of another word, and "hi" is "hard of hearing", not Hindi.
        assertFalse(Subtitles.inLanguage(File("Urdunia.srt"), "ur"))
        assertFalse(Subtitles.inLanguage(File("Film.en.hi.srt"), "hi"))
        assertTrue(Subtitles.inLanguage(File("Film.hindi.srt"), "hi"))
        assertFalse(Subtitles.inLanguage(File("Film.ur.srt"), "xx"))
    }

    @Test
    fun theChosenLanguageComesFirstBesideTheVideo() {
        val dir = tmp.newFolder("films")
        val video = File(dir, "Film.mkv").apply { writeText("") }
        for (name in listOf("Film.srt", "Film.en.srt", "Film.ur.srt")) File(dir, name).writeText("1")
        // As before: the file named exactly like the video, then English.
        assertEquals(listOf("Film.srt", "Film.en.srt", "Film.ur.srt"), Subtitles.besideVideo(video).map { it.name })
        // With Urdu chosen, Urdu first.
        assertEquals(listOf("Film.ur.srt", "Film.srt", "Film.en.srt"), Subtitles.besideVideo(video, "ur").map { it.name })
    }
}
