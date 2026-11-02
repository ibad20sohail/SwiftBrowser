package com.swiftbrowser.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import com.swiftbrowser.core.L
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * 2.9: subtitle files for the player (.srt, .vtt, .ass and .ssa), found next to a video or picked by
 * the user. They are read with Media3's own parsers and shown by the player screen itself, which
 * lets them be moved earlier or later (the player's own subtitle display can't).
 */
@OptIn(UnstableApi::class)
object Subtitles {
    val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    /** Bigger than any real subtitle file: something else picked by mistake isn't read whole. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * 3.0: the encodings the subtitle sheet offers when letters come out wrong, as people know them
     * (the first: what Swift guessed).
     */
    val ENCODINGS = listOf(
        "UTF-8" to "UTF-8 (most files)",
        "windows-1256" to "Arabic, Urdu, Persian (Windows-1256)",
        "windows-1252" to "Western European (Windows-1252)",
        "windows-1251" to "Cyrillic (Windows-1251)",
        "windows-1250" to "Central European (Windows-1250)",
        "windows-1253" to "Greek (Windows-1253)",
        "windows-1254" to "Turkish (Windows-1254)",
        "windows-1255" to "Hebrew (Windows-1255)",
        "ISO-8859-6" to "Arabic (ISO-8859-6)",
        "windows-874" to "Thai (Windows-874)",
        "GBK" to "Chinese (GBK)",
        "Big5" to "Chinese, traditional (Big5)",
        "Shift_JIS" to "Japanese (Shift_JIS)",
        "EUC-KR" to "Korean (EUC-KR)",
    )

    /** How [ENCODINGS] calls [name] ("Arabic, Urdu, Persian (Windows-1256)"), or the name itself. */
    fun encodingLabel(name: String): String = ENCODINGS.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second ?: name

    /** A subtitle file ready to show: what is on screen at each moment of the video. */
    class Track internal constructor(
        val label: String,
        private val starts: LongArray,
        private val ends: LongArray,
        private val cues: List<List<Cue>>,
        /** 3.0: the encoding its text was read in (guessed, or chosen in the subtitle sheet). */
        val encoding: String = "UTF-8",
    ) {
        /** The cues on screen at [timeUs] of the video, the earliest first. */
        fun at(timeUs: Long): List<Cue> {
            // The last cue starting by then, and the ones before it still on screen (overlapping
            // lines of .srt files; .ass and .vtt come already merged into one cue list at a time).
            var lo = 0
            var hi = starts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= timeUs) lo = mid + 1 else hi = mid
            }
            var i = lo - 1
            var out: ArrayList<Cue>? = null
            var looked = 0
            while (i >= 0 && looked < 30) {
                if (ends[i] > timeUs && cues[i].isNotEmpty()) {
                    val list = out ?: ArrayList<Cue>().also { out = it }
                    list.addAll(0, cues[i])
                }
                i--
                looked++
            }
            return out ?: emptyList()
        }
    }

    /**
     * Subtitle files next to [video] named like it ("Film.srt", "Film.en.srt"), the exact name
     * first, then English ones (2.9.2: not "Film.de.srt" for being first in the alphabet). Reads the
     * folder: call it off the main thread.
     */
    fun besideVideo(video: File): List<File> {
        val base = video.nameWithoutExtension
        val files = video.parentFile?.listFiles() ?: return emptyList()
        return files.filter { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in EXTENSIONS &&
                (f.nameWithoutExtension.equals(base, ignoreCase = true) || f.name.startsWith("$base.", ignoreCase = true))
        }.sortedWith(compareBy<File>({ !it.nameWithoutExtension.equals(base, ignoreCase = true) }, { !isEnglish(it) }, { it.name.lowercase(Locale.ROOT) }))
    }

    private val SUB_FOLDERS = setOf("subs", "sub", "subtitles", "subtitle")
    private val ENGLISH = Regex("(?i)(^|[^a-z])(en|eng|english)([^a-z]|$)")

    /** An English subtitle file, by its name ("2_English.srt", "Film.en.srt"). */
    fun isEnglish(f: File) = ENGLISH.containsMatchIn(f.nameWithoutExtension)

    /**
     * 2.9.2: subtitle files in a Subs (or Subtitles) folder next to [video], the way torrents pack
     * them: the video's own folder in it ("Subs/Show.S01E01/2_English.srt"), files named like the
     * video, or, for the only video of its folder, all of them. English first. Reads folders: call it
     * off the main thread.
     */
    fun inSubsFolder(video: File): List<File> {
        val base = video.nameWithoutExtension
        val files = video.parentFile?.listFiles() ?: return emptyList()
        val folders = files.filter { it.isDirectory && it.name.lowercase(Locale.ROOT) in SUB_FOLDERS }
        if (folders.isEmpty()) return emptyList()
        val alone = files.count { it.isFile && com.swiftbrowser.ui.PlayerActivity.kindOf(it.name) == com.swiftbrowser.ui.PlayerActivity.Kind.VIDEO } <= 1
        fun subtitle(f: File) = f.isFile && f.extension.lowercase(Locale.ROOT) in EXTENSIONS
        val out = ArrayList<File>()
        for (d in folders) {
            val inside = d.listFiles() ?: continue
            inside.firstOrNull { it.isDirectory && it.name.equals(base, ignoreCase = true) }?.listFiles()?.filter(::subtitle)?.let { out += it }
            val loose = inside.filter(::subtitle)
            val named = loose.filter { it.nameWithoutExtension.equals(base, ignoreCase = true) || it.name.startsWith("$base.", ignoreCase = true) }
            out += if (named.isNotEmpty()) named else if (alone) loose else emptyList()
        }
        return out.distinct().sortedWith(compareBy<File>({ !isEnglish(it) }, { it.name.lowercase(Locale.ROOT) }))
    }

    /** Reads subtitle file [f] (3.0: in [encoding], or the one it most likely has). Blocking. Null if it isn't one Swift can read. */
    fun read(f: File, encoding: String? = null): Track? = try {
        if (f.length() > MAX_BYTES) null else f.inputStream().use { read(it, f.name, encoding) }
    } catch (e: Exception) {
        L.w("Couldn't read a subtitle file", e)
        null
    }

    /** Reads a subtitle file from [input]; [name] (its file name) says which kind. Blocking. */
    fun read(input: InputStream, name: String, encoding: String? = null): Track? {
        val bytes = readCapped(input) ?: return null
        val used = encoding ?: guessEncoding(bytes)
        val utf8 = toUtf8(bytes, used)
        val parser: SubtitleParser = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "srt" -> SubripParser()
            "vtt" -> WebvttParser()
            "ass", "ssa" -> SsaParser()
            else -> sniff(utf8) ?: return null
        }
        val list = ArrayList<CuesWithTiming>()
        return try {
            parser.parse(utf8, SubtitleParser.OutputOptions.allCues()) { list.add(it) }
            if (list.isEmpty()) null else build(list, name, used)
        } catch (e: Exception) {
            L.w("Couldn't read a subtitle file", e)
            null
        }
    }

    /** A file without a known ending: the kind its first lines show. */
    private fun sniff(bytes: ByteArray): SubtitleParser? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r')
        return when {
            head.startsWith("WEBVTT") -> WebvttParser()
            head.contains("[Script Info]", ignoreCase = true) -> SsaParser()
            head.contains("-->") -> SubripParser()
            else -> null
        }
    }

    private fun build(list: List<CuesWithTiming>, label: String, encoding: String): Track {
        val sorted = list.sortedBy { if (it.startTimeUs == C.TIME_UNSET) 0L else it.startTimeUs }
        val n = sorted.size
        val starts = LongArray(n)
        val ends = LongArray(n)
        for (i in 0 until n) {
            val c = sorted[i]
            starts[i] = if (c.startTimeUs == C.TIME_UNSET) 0L else c.startTimeUs
            // Without an end, a cue lasts until the next one.
            ends[i] = when {
                c.durationUs != C.TIME_UNSET -> starts[i] + c.durationUs
                i + 1 < n -> sorted[i + 1].startTimeUs.coerceAtLeast(starts[i])
                else -> Long.MAX_VALUE
            }
        }
        return Track(label, starts, ends, sorted.map { it.cues }, encoding)
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    /**
     * The parsers read UTF-8 (or UTF-16 with its mark). Older subtitle files are often in the
     * Windows encoding of their language: those are turned into UTF-8 first ([encoding]), so
     * accents and other alphabets don't come out garbled.
     */
    private fun toUtf8(bytes: ByteArray, encoding: String): ByteArray {
        if (encoding.startsWith("UTF-16", ignoreCase = true) && bom16(bytes)) return bytes
        if (encoding.equals("UTF-8", ignoreCase = true)) {
            // A few broken bytes in a UTF-8 file become "�" rather than garbling all the rest.
            val strict = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                strict.decode(ByteBuffer.wrap(bytes))
                bytes
            } catch (_: CharacterCodingException) {
                String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
            }
        }
        val charset = try {
            Charset.forName(encoding)
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
        return String(bytes, charset).toByteArray(Charsets.UTF_8)
    }

    private fun bom16(b: ByteArray) =
        b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))

    /**
     * 3.0: the encoding [bytes] most likely have. Before 3.0 one broken byte in a UTF-8 file sent the
     * whole file to the phone language's old encoding (Windows-1252 on an English phone), and Urdu or
     * Arabic files in Windows-1256 came out garbled there. Now: a byte-order mark says; UTF-8 when
     * nearly all of it is; else each old encoding is tried and the text that looks most like real
     * words wins (one alphabet a word, capitals only at the start, no signs inside words), the
     * phone's language deciding a tie.
     */
    fun guessEncoding(bytes: ByteArray, language: String = Locale.getDefault().language): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        val (valid, invalid) = utf8Counts(bytes)
        // One stray byte breaks up to three; text in an old encoding has far more broken than whole.
        if (invalid == 0 || (valid >= 8 && invalid * 8 <= valid)) return "UTF-8"
        val phone = legacyEncoding(language)
        // Chinese, Japanese and Korean files take two bytes a letter: the phone's own is used.
        if (phone in setOf("GBK", "Shift_JIS", "EUC-KR")) return phone
        val candidates = (SINGLE_BYTE + phone).distinct()
        var best = phone
        var bestScore = Int.MIN_VALUE
        // The first 256 KB tell as well as the whole file (a big .ass file is read seven times otherwise).
        val sample = minOf(bytes.size, 256 * 1024)
        for (name in candidates) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val score = plausibility(String(bytes, 0, sample, cs)) + if (name == phone) 3 else 0
            if (score > bestScore) {
                bestScore = score
                best = name
            }
        }
        return best
    }

    private val SINGLE_BYTE = listOf("windows-1256", "windows-1252", "windows-1251", "windows-1250", "windows-1253", "windows-1254", "windows-1255")

    /** Well-formed UTF-8 sequences of two or more bytes, and bytes that fit no sequence. */
    private fun utf8Counts(b: ByteArray): Pair<Int, Int> {
        var i = 0
        var valid = 0
        var invalid = 0
        while (i < b.size) {
            val x = b[i].toInt() and 0xFF
            val len = when {
                x < 0x80 -> 1
                x in 0xC2..0xDF -> 2
                x in 0xE0..0xEF -> 3
                x in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (len == 1) {
                i++
                continue
            }
            if (len == 0 || i + len > b.size || (1 until len).any { (b[i + it].toInt() and 0xC0) != 0x80 }) {
                invalid++
                i++
                continue
            }
            valid++
            i += len
        }
        return valid to invalid
    }

    /** How much [text] looks like words of a real language (higher: more). */
    private fun plausibility(text: String): Int {
        var score = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '�' || c in '\u0080'..'\u009F') {
                // A byte the encoding hasn't got.
                score -= 6
                i++
                continue
            }
            if (!Character.isLetter(c)) {
                if (c.code >= 0x80 && !Character.isWhitespace(c) && !Character.isDigit(c) && Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                    // A sign glued between letters ("Ø§Ù„") is what wrong decoding looks like.
                    val inside = i > 0 && Character.isLetter(text[i - 1]) && i + 1 < n && Character.isLetter(text[i + 1])
                    score -= if (inside) 3 else 1
                }
                i++
                continue
            }
            val start = i
            while (i < n && (Character.isLetter(text[i]) || Character.getType(text[i]) == Character.NON_SPACING_MARK.toInt())) i++
            score += wordScore(text, start, i)
        }
        return score
    }

    private fun wordScore(text: String, from: Int, to: Int): Int {
        var ascii = 0
        var latin = 0
        var other = 0
        var upper = 0
        var upperInside = 0
        var letters = 0
        val scripts = HashSet<Character.UnicodeScript>()
        for (k in from until to) {
            val c = text[k]
            if (!Character.isLetter(c)) continue
            letters++
            if (c.code < 0x80) {
                ascii++
            } else {
                val s = Character.UnicodeScript.of(c.code)
                scripts += s
                if (s == Character.UnicodeScript.LATIN) latin++ else other++
            }
            if (Character.isUpperCase(c)) {
                upper++
                if (k > from) upperInside++
            }
        }
        var s = 0
        // Two alphabets in one word.
        if ((ascii > 0 && other > 0) || scripts.size > 1) s -= 4
        // Capitals inside a word that isn't all capitals ("ЗбУбЗг").
        if (upperInside > 0 && upper < letters) s -= 2 * upperInside.coerceAtMost(3)
        // A word of accented letters only ("ÇáÓáÇã"): real Western words have plain letters too.
        if (ascii == 0 && latin >= 3) s -= 2
        // Greek's final sigma anywhere but at a word's end (Russian read as Greek has it everywhere).
        if ((from until to - 1).any { text[it] == 'ς' }) s -= 3
        // A word in one other alphabet (Arabic, Cyrillic, Greek, Hebrew…).
        if (ascii == 0 && other >= 2 && scripts.size == 1) s += 2
        // A plain word with an accent or two ("café", "Größe").
        if (ascii > 0 && latin in 1..2 && other == 0) s += 1
        return s
    }

    private fun legacyEncoding(language: String = Locale.getDefault().language): String = when (language) {
        "ar", "fa", "ur" -> "windows-1256"
        "ru", "uk", "bg", "sr", "mk", "be" -> "windows-1251"
        "el" -> "windows-1253"
        "tr", "az" -> "windows-1254"
        "he", "iw" -> "windows-1255"
        "pl", "cs", "sk", "hu", "ro", "hr", "sl" -> "windows-1250"
        "th" -> "windows-874"
        "vi" -> "windows-1258"
        "zh" -> "GBK"
        "ja" -> "Shift_JIS"
        "ko" -> "EUC-KR"
        else -> "windows-1252"
    }
}
