package com.swiftbrowser.download

import java.net.URL
import java.util.Locale

/** Minimal HLS (m3u8) playlist parser: master playlists, media playlists, AES-128 keys, init maps, byte ranges. */
object Hls {
    data class Variant(
        val url: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val name: String?,
        /**
         * 3.0: the playlist of its sound when that comes separately (#EXT-X-MEDIA:TYPE=AUDIO with a
         * URI): without it the video is silent. Null when the sound is in the video's own parts.
         */
        val audio: String? = null,
    ) {
        val label: String
            get() {
                val res = if (height > 0) "${height}p" else name ?: "Stream"
                val mbps = if (bandwidth > 0) String.format(Locale.US, " (%.1f Mbps)", bandwidth / 1_000_000.0) else ""
                return res + mbps
            }
    }

    class Key(val method: String, val uri: String?, val iv: ByteArray?)

    class Segment(
        val url: String,
        val duration: Double,
        val key: Key?,
        val rangeStart: Long,
        val rangeLength: Long,
        val sequence: Long,
    )

    class Media(
        val segments: List<Segment>,
        val endList: Boolean,
        val init: Segment?,
        val duration: Double,
    ) {
        val encrypted: Boolean get() = segments.any { it.key != null }
        val unsupportedKey: Boolean get() = segments.any { it.key != null && it.key.method != "AES-128" }
        val fragmentedMp4: Boolean get() = init != null
    }

    fun isPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun resolve(base: String, ref: String): String = try {
        URL(URL(base), ref.trim()).toString()
    } catch (_: Exception) {
        ref.trim()
    }

    /** Parses an attribute list: KEY=VALUE,KEY="quoted, value",... */
    fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().trimStart(',').trim().uppercase(Locale.ROOT)
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val end = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, end)
                j = end + 1
            } else {
                val end = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, end)
                j = end
            }
            out[key] = value.trim()
            i = if (j < s.length && s[j] == ',') j + 1 else j
        }
        return out
    }

    /** 3.0: one of a master playlist's sound tracks (#EXT-X-MEDIA:TYPE=AUDIO). */
    class Rendition(
        val group: String,
        val name: String?,
        val language: String?,
        /** Its own playlist; null when the sound is inside the video's parts. */
        val uri: String?,
        val default: Boolean,
        val autoselect: Boolean,
        /** An audio description for the blind ("public.accessibility.describes-video"). */
        val describes: Boolean,
    )

    /**
     * [language]: the phone's (its sound track is chosen when a stream has several). 3.0: each
     * quality knows its separate sound track, if it has one ([Variant.audio]).
     */
    fun parseMaster(text: String, base: String, language: String = Locale.getDefault().language): List<Variant> {
        val out = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()
        var pending: Map<String, String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(line.substringAfter(':'))
                    if (a["TYPE"].equals("AUDIO", ignoreCase = true)) {
                        renditions += Rendition(
                            group = a["GROUP-ID"].orEmpty(),
                            name = a["NAME"],
                            language = a["LANGUAGE"],
                            uri = a["URI"]?.takeIf { it.isNotBlank() }?.let { resolve(base, it) },
                            default = a["DEFAULT"].equals("YES", ignoreCase = true),
                            autoselect = a["AUTOSELECT"].equals("YES", ignoreCase = true),
                            describes = a["CHARACTERISTICS"]?.contains("describes-video", ignoreCase = true) == true,
                        )
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    val a = pending
                    val res = a["RESOLUTION"]?.split('x')
                    out.add(
                        Variant(
                            url = resolve(base, line),
                            bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0,
                            width = res?.getOrNull(0)?.toIntOrNull() ?: 0,
                            height = res?.getOrNull(1)?.toIntOrNull() ?: 0,
                            name = a["NAME"],
                            audio = a["AUDIO"],
                        ),
                    )
                    pending = null
                }
            }
        }
        // The EXT-X-MEDIA lines may come after the qualities: their sound is matched once all are read.
        val chosen = renditions.groupBy { it.group }.mapValues { (_, list) -> soundOf(list, language) }
        return out.map { v -> v.copy(audio = v.audio?.let { chosen[it] }) }
            .distinctBy { it.url }.sortedByDescending { if (it.height > 0) it.height * 1_000_000_000L + it.bandwidth else it.bandwidth }
    }

    /**
     * 3.0: the separate sound track to download with a quality whose AUDIO group is [group]: the
     * phone's language first, then the stream's default, never an audio description when there is
     * another. Null when the sound is in the video's own parts (a rendition of the group without a
     * playlist of its own), or the group is unknown.
     */
    fun soundOf(group: List<Rendition>, language: String): String? {
        if (group.isEmpty() || group.any { it.uri == null }) return null
        fun lang(r: Rendition) = r.language?.substringBefore('-')?.lowercase(Locale.ROOT)
        val phone = language.lowercase(Locale.ROOT)
        // Playlists write "en" or "eng".
        val phone3 = runCatching { Locale.forLanguageTag(phone).isO3Language }.getOrDefault(phone)
        return group.withIndex().maxByOrNull { (i, r) ->
            (if (lang(r) == phone || lang(r) == phone3) 8 else 0) + (if (r.default) 4 else 0) + (if (r.autoselect) 2 else 0) - (if (r.describes) 16 else 0) - i * 0.001
        }?.value?.uri
    }

    fun parseMedia(text: String, base: String): Media {
        val segments = ArrayList<Segment>()
        var sequence = 0L
        var key: Key? = null
        var init: Segment? = null
        var duration = 0.0
        var total = 0.0
        var endList = false
        var rangeLength = -1L
        var rangeStart = -1L
        var lastRangeEnd = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val method = a["METHOD"]?.uppercase(Locale.ROOT) ?: "NONE"
                    key = if (method == "NONE") null else Key(method, a["URI"]?.let { resolve(base, it) }, a["IV"]?.let { hex(it) })
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val uri = a["URI"]
                    if (uri != null) {
                        var start = 0L
                        var len = -1L
                        a["BYTERANGE"]?.let { br ->
                            len = br.substringBefore('@').toLongOrNull() ?: -1
                            start = br.substringAfter('@', "0").toLongOrNull() ?: 0
                        }
                        init = Segment(resolve(base, uri), 0.0, null, start, len, -1)
                    }
                }
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = line.substringAfter(':').trim()
                    rangeLength = v.substringBefore('@').toLongOrNull() ?: -1
                    rangeStart = if (v.contains('@')) v.substringAfter('@').toLongOrNull() ?: 0 else lastRangeEnd
                }
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val start = if (rangeLength >= 0) rangeStart.coerceAtLeast(0) else 0
                    segments.add(Segment(resolve(base, line), duration, key, start, rangeLength, sequence++))
                    if (rangeLength >= 0) lastRangeEnd = start + rangeLength
                    total += duration
                    duration = 0.0
                    rangeLength = -1
                    rangeStart = -1
                }
            }
        }
        return Media(segments, endList, init, total)
    }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X")
        if (h.length % 2 != 0) return null
        return try {
            ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    /** Default AES-128 IV: the segment's media sequence number as a 16-byte big-endian integer. */
    fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        return iv
    }

    /**
     * HLS packagers often restart each segment's MPEG-TS continuity counters at 0. Joined into
     * one file, that looks like packet loss to Android's TS reader, which then drops audio at
     * every segment boundary. This renumbers the counters in place so the joined file is
     * continuous. [next] holds the next counter per PID (8192 entries, initialised to -1).
     */
    fun renumberContinuity(buf: ByteArray, next: IntArray) {
        var i = 0
        while (i + 188 <= buf.size) {
            if (buf[i] != 0x47.toByte()) {
                i++ // resync
                continue
            }
            val pid = ((buf[i + 1].toInt() and 0x1F) shl 8) or (buf[i + 2].toInt() and 0xFF)
            if (pid != 0x1FFF) {
                val b3 = buf[i + 3].toInt() and 0xFF
                val hasPayload = (b3 shr 4) and 0x1 == 1
                val expected = next[pid]
                if (hasPayload) {
                    val cc = if (expected < 0) b3 and 0x0F else expected
                    buf[i + 3] = ((b3 and 0xF0) or cc).toByte()
                    next[pid] = (cc + 1) and 0x0F
                } else if (expected >= 0) {
                    // Packets without payload repeat the previous counter.
                    buf[i + 3] = ((b3 and 0xF0) or ((expected - 1) and 0x0F)).toByte()
                }
            }
            i += 188
        }
    }

    fun isTransportStream(buf: ByteArray): Boolean =
        buf.size >= 188 * 2 && buf[0] == 0x47.toByte() && buf[188] == 0x47.toByte()

    /**
     * 3.0: a separate sound track's parts are often "packed audio" (.aac, .mp3): plain audio frames,
     * each part starting with an ID3 tag that holds its time. Joined, the tags in between would stop
     * Android's reader, so each part goes in without its leading tags.
     */
    fun stripId3(buf: ByteArray): ByteArray {
        var at = 0
        while (at + 10 <= buf.size && buf[at] == 'I'.code.toByte() && buf[at + 1] == 'D'.code.toByte() && buf[at + 2] == '3'.code.toByte()) {
            // The size is "syncsafe": 7 bits a byte; a footer (flag 0x10) adds 10 bytes.
            val size = ((buf[at + 6].toInt() and 0x7F) shl 21) or ((buf[at + 7].toInt() and 0x7F) shl 14) or
                ((buf[at + 8].toInt() and 0x7F) shl 7) or (buf[at + 9].toInt() and 0x7F)
            val footer = if (buf[at + 5].toInt() and 0x10 != 0) 10 else 0
            at += 10 + size + footer
        }
        return if (at == 0) buf else if (at >= buf.size) ByteArray(0) else buf.copyOfRange(at, buf.size)
    }

    /** 3.0: what a sound track's file is, from its first bytes: the ending it gets when kept as it is. */
    fun soundExtension(head: ByteArray, fragmented: Boolean): String = when {
        fragmented -> "m4a"
        isTransportStream(head) -> "ts"
        head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF6) == 0xF0 -> "aac"
        head.size >= 2 && head[0] == 0x0B.toByte() && head[1] == 0x77.toByte() -> "ac3"
        head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
        else -> "aac"
    }

    fun formatDuration(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
