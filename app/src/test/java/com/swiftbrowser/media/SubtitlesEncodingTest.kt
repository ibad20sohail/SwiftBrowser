package com.swiftbrowser.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class SubtitlesEncodingTest {
    private fun srt(vararg lines: String): String = lines.mapIndexed { i, text ->
        "${i + 1}\n00:00:0${i},000 --> 00:00:0${i + 1},000\n$text\n"
    }.joinToString("\n")

    private val urdu = srt("آپ کيسے ہيں؟", "ميں ٹھيک ہوں، شکريہ", "يہ فلم بہت اچھی ہے", "چلو گھر چليں")
    private val arabic = srt("مرحبا بكم في هذا الفيلم", "أين كنت طوال الليل؟", "سأعود غدا إن شاء الله")
    private val russian = srt("Привет, как дела?", "Я смотрю хороший фильм.", "Где ты был всю ночь?")
    private val greek = srt("Γεια σας, τι κάνετε;", "Αυτή η ταινία είναι πολύ καλή.", "Πού ήσουν όλη τη νύχτα;")
    private val french = srt("Où étiez-vous toute la nuit ?", "Ce film est très beau, n'est-ce pas ?", "À demain, mon frère.")

    private fun bytes(text: String, charset: String) = text.toByteArray(Charset.forName(charset))

    @Test
    fun urduAndArabicInWindows1256AreFoundOnAnEnglishPhone() {
        // Before 3.0 an English phone read these as Windows-1252: garbled.
        assertEquals("windows-1256", Subtitles.guessEncoding(bytes(urdu, "windows-1256"), "en"))
        assertEquals("windows-1256", Subtitles.guessEncoding(bytes(arabic, "windows-1256"), "en"))
    }

    @Test
    fun otherAlphabetsAreFoundToo() {
        assertEquals("windows-1251", Subtitles.guessEncoding(bytes(russian, "windows-1251"), "en"))
        assertEquals("windows-1253", Subtitles.guessEncoding(bytes(greek, "windows-1253"), "en"))
        assertEquals("windows-1252", Subtitles.guessEncoding(bytes(french, "windows-1252"), "en"))
    }

    @Test
    fun oneBrokenByteDoesntTurnUtf8IntoSomethingElse() {
        val good = bytes(urdu, "UTF-8")
        val broken = good.copyOfRange(0, 40) + byteArrayOf(0xFF.toByte()) + good.copyOfRange(40, good.size)
        assertEquals("UTF-8", Subtitles.guessEncoding(broken, "en"))
        assertEquals("UTF-8", Subtitles.guessEncoding(bytes(russian, "UTF-8"), "ur"))
        assertEquals("UTF-8", Subtitles.guessEncoding("Plain English only.".toByteArray(), "en"))
    }

    @Test
    fun byteOrderMarksDecide() {
        assertEquals("UTF-16LE", Subtitles.guessEncoding(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'a'.code.toByte(), 0), "en"))
        assertEquals("UTF-16BE", Subtitles.guessEncoding(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 'a'.code.toByte()), "en"))
        assertEquals("UTF-8", Subtitles.guessEncoding(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes(arabic, "windows-1256"), "en"))
    }

    @Test
    fun encodingsHaveNamesPeopleKnow() {
        assertEquals("Arabic, Urdu, Persian (Windows-1256)", Subtitles.encodingLabel("windows-1256"))
        assertEquals("x-unknown", Subtitles.encodingLabel("x-unknown"))
    }
}
