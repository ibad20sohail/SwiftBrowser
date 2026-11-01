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

    /** A subtitle file ready to show: what is on screen at each moment of the video. */
    class Track internal constructor(
        val label: String,
        private val starts: LongArray,
        private val ends: LongArray,
        private val cues: List<List<Cue>>,
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

    /** Reads subtitle file [f]. Blocking. Null if it isn't one Swift can read. */
    fun read(f: File): Track? = try {
        if (f.length() > MAX_BYTES) null else f.inputStream().use { read(it, f.name) }
    } catch (e: Exception) {
        L.w("Couldn't read a subtitle file", e)
        null
    }

    /** Reads a subtitle file from [input]; [name] (its file name) says which kind. Blocking. */
    fun read(input: InputStream, name: String): Track? {
        val bytes = readCapped(input) ?: return null
        val utf8 = toUtf8(bytes)
        val parser: SubtitleParser = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "srt" -> SubripParser()
            "vtt" -> WebvttParser()
            "ass", "ssa" -> SsaParser()
            else -> sniff(utf8) ?: return null
        }
        val list = ArrayList<CuesWithTiming>()
        return try {
            parser.parse(utf8, SubtitleParser.OutputOptions.allCues()) { list.add(it) }
            if (list.isEmpty()) null else build(list, name)
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

    private fun build(list: List<CuesWithTiming>, label: String): Track {
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
        return Track(label, starts, ends, sorted.map { it.cues })
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
     * Windows encoding of their language: those are turned into UTF-8 first, guessing the
     * encoding from the phone's language, so accents and other alphabets don't come out garbled.
     */
    private fun toUtf8(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) {
            return bytes
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            bytes
        } catch (_: CharacterCodingException) {
            val charset = try {
                Charset.forName(legacyEncoding())
            } catch (_: Exception) {
                Charsets.ISO_8859_1
            }
            String(bytes, charset).toByteArray(Charsets.UTF_8)
        }
    }

    private fun legacyEncoding(): String = when (Locale.getDefault().language) {
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
