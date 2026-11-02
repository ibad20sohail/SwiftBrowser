package com.swiftbrowser.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.swiftbrowser.core.L
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the audio/video tracks of an MPEG-TS file (what HLS streams are made of) into an MP4
 * container without re-encoding, so the video opens in any gallery app. If Android can't handle
 * the codecs, the caller keeps the .ts file (VLC and MX Player play it fine).
 *
 * On Android 7 the TS extractor returns AAC audio as several ADTS frames glued together (headers
 * included). MP4 needs one raw frame per sample, so those are split and the headers stripped.
 *
 * 3.0: the sound can come from a file of its own (HLS sites with a separate sound track): the
 * picture of the first source and the sound of the last one that has some go into one MP4, their
 * samples in time order. What was written is checked afterwards ([lengthUs]), so a copy cut short
 * (the phone filled up: Android 7's writer doesn't always say) never replaces the stream.
 */
object Remuxer {
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** What went into the MP4: the picture's length (from its first sample's time to its last's). */
    class Result(val videoUs: Long)

    /** A track copied from one of the sources. */
    private class Track(val source: Int, val index: Int, val out: Int, val aac: Boolean, val rate: Int, val video: Boolean)

    /** [sources]' picture and sound into the MP4 [dst]; null when it can't be done (the caller keeps the sources). */
    fun remux(sources: List<File>, dst: File, isCancelled: () -> Boolean): Result? {
        val extractors = ArrayList<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            for (f in sources) extractors += MediaExtractor().apply { setDataSource(f.path) }
            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val tracks = ArrayList<Track>()
            var maxInput = 1 shl 20
            fun add(source: Int, index: Int, video: Boolean): Boolean {
                val format = extractors[source].getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
                return try {
                    val aac = mime == MediaFormat.MIMETYPE_AUDIO_AAC
                    val out = muxer.addTrack(if (aac) aacFormat(format) else format)
                    tracks += Track(source, index, out, aac, if (aac) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0, video)
                    extractors[source].selectTrack(index)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    true
                } catch (e: Exception) {
                    L.w("Remux: unsupported track $mime", e)
                    false
                }
            }
            fun mimeOf(source: Int, index: Int) = extractors[source].getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            // The picture: the first source's. The sound: the last source's (a separate sound track
            // comes after the video), else the video's own.
            val video = extractors.indices.asSequence().flatMap { s -> (0 until extractors[s].trackCount).asSequence().map { s to it } }
                .firstOrNull { (s, t) -> mimeOf(s, t).startsWith("video/") } ?: return null
            if (!add(video.first, video.second, video = true)) return null
            val sounds = extractors.indices.reversed().asSequence().flatMap { s -> (0 until extractors[s].trackCount).asSequence().map { s to it } }
                .filter { (s, t) -> mimeOf(s, t).startsWith("audio/") }.toList()
            // 3.0: sound the MP4 can't take (Dolby AC-3 on Android 7): no MP4 at all rather than a silent
            // one that replaces the stream (before 3.0 the sound was lost that way).
            if (sounds.isNotEmpty() && sounds.none { (s, t) -> add(s, t, video = false) }) {
                L.w("Remux: no sound track the MP4 can hold")
                return null
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(maxOf(maxInput, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            val bySource = tracks.groupBy { it.source }
            var first = Long.MIN_VALUE
            var last = Long.MIN_VALUE
            val live = bySource.keys.toMutableSet()
            while (live.isNotEmpty()) {
                if (isCancelled()) return null
                // The source whose next sample comes first: samples go in in time order.
                val s = live.minByOrNull { extractors[it].sampleTime.let { t -> if (t < 0) Long.MAX_VALUE else t } } ?: break
                val ex = extractors[s]
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) {
                    live -= s
                    continue
                }
                val track = bySource[s]?.firstOrNull { it.index == ex.sampleTrackIndex }
                if (track != null) {
                    val time = ex.sampleTime
                    if (track.video) {
                        if (first == Long.MIN_VALUE || time < first) first = time
                        if (time > last) last = time
                    }
                    if (track.aac && isAdts(buffer, 0, size)) {
                        writeAdtsFrames(muxer, track.out, buffer, size, time, track.rate, info)
                    } else {
                        info.set(0, size, time, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track.out, buffer, info)
                    }
                }
                ex.advance()
            }
            muxer.stop()
            if (dst.length() <= 0 || first == Long.MIN_VALUE) return null
            return Result(last - first)
        } catch (e: Throwable) {
            L.w("Remux failed", e)
            return null
        } finally {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    /**
     * 3.0: how long [f] says it is (its longest track), read back from its index; 0 when it has
     * none (an MP4 whose writing was cut short has no index at its end).
     */
    fun lengthUs(f: File): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.path)
            (0 until ex.trackCount).maxOfOrNull { i ->
                ex.getTrackFormat(i).let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L }
            } ?: 0L
        } catch (e: Exception) {
            L.w("Remux: the MP4 can't be read back", e)
            0L
        } finally {
            runCatching { ex.release() }
        }
    }

    /** A clean AAC format for MP4 (no ADTS flag), with an AudioSpecificConfig in csd-0. */
    private fun aacFormat(src: MediaFormat): MediaFormat {
        val rate = src.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = src.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
        val csd = if (src.containsKey("csd-0")) src.getByteBuffer("csd-0") else null
        if (csd != null && csd.remaining() >= 2) {
            out.setByteBuffer("csd-0", csd.duplicate())
        } else {
            val profile = if (src.containsKey(MediaFormat.KEY_AAC_PROFILE)) src.getInteger(MediaFormat.KEY_AAC_PROFILE) else 2
            val index = SAMPLE_RATES.indexOf(rate).let { if (it < 0) 4 else it }
            val asc = byteArrayOf(
                ((profile shl 3) or (index shr 1)).toByte(),
                (((index and 1) shl 7) or (channels shl 3)).toByte(),
            )
            out.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        return out
    }

    private fun isAdts(b: ByteBuffer, off: Int, size: Int): Boolean =
        size >= 7 && (b.get(off).toInt() and 0xFF) == 0xFF && (b.get(off + 1).toInt() and 0xF0) == 0xF0

    /** Splits glued ADTS frames into one raw AAC frame per MP4 sample, with interpolated timestamps. */
    private fun writeAdtsFrames(
        muxer: MediaMuxer, track: Int, buffer: ByteBuffer, size: Int, startUs: Long, rate: Int, info: MediaCodec.BufferInfo,
    ) {
        val frameUs = if (rate > 0) 1024L * 1_000_000L / rate else 23_220L
        var off = 0
        var n = 0
        while (off + 7 <= size && isAdts(buffer, off, size - off)) {
            val b1 = buffer.get(off + 1).toInt() and 0xFF
            val b3 = buffer.get(off + 3).toInt() and 0xFF
            val b4 = buffer.get(off + 4).toInt() and 0xFF
            val b5 = buffer.get(off + 5).toInt() and 0xFF
            val b6 = buffer.get(off + 6).toInt() and 0xFF
            val header = if (b1 and 0x01 == 1) 7 else 9
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or (b5 ushr 5)
            if (frameLength <= header || off + frameLength > size) break
            val blocks = (b6 and 0x03) + 1
            info.set(off + header, frameLength - header, startUs + n * frameUs, 0)
            muxer.writeSampleData(track, buffer, info)
            n += blocks
            off += frameLength
        }
    }
}
