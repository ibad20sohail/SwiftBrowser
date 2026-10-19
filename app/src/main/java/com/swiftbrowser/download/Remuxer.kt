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
 */
object Remuxer {
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun tsToMp4(src: File, dst: File, isCancelled: () -> Boolean): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.path)
            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = IntArray(extractor.trackCount) { -1 }
            var audioSource = -1
            var audioRate = 0
            var hasVideo = false
            var maxInput = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                try {
                    when {
                        mime.startsWith("video/") && !hasVideo -> {
                            trackMap[i] = muxer.addTrack(format)
                            hasVideo = true
                        }
                        mime == MediaFormat.MIMETYPE_AUDIO_AAC && audioSource < 0 -> {
                            audioRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            trackMap[i] = muxer.addTrack(aacFormat(format))
                            audioSource = i
                        }
                        mime.startsWith("audio/") && audioSource < 0 -> {
                            trackMap[i] = muxer.addTrack(format)
                            audioSource = i
                        }
                        else -> continue
                    }
                    extractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                } catch (e: Exception) {
                    L.w("Remux: unsupported track $mime", e)
                }
            }
            if (!hasVideo) return false
            muxer.start()
            val buffer = ByteBuffer.allocate(maxOf(maxInput, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            val isAacAudio = audioSource >= 0 &&
                extractor.getTrackFormat(audioSource).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            while (true) {
                if (isCancelled()) return false
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val source = extractor.sampleTrackIndex
                val track = trackMap[source]
                if (track >= 0) {
                    val time = extractor.sampleTime
                    if (source == audioSource && isAacAudio && isAdts(buffer, 0, size)) {
                        writeAdtsFrames(muxer, track, buffer, size, time, audioRate, info)
                    } else {
                        info.set(0, size, time, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        })
                        muxer.writeSampleData(track, buffer, info)
                    }
                }
                extractor.advance()
            }
            muxer.stop()
            return dst.length() > 0
        } catch (e: Throwable) {
            L.w("Remux failed", e)
            return false
        } finally {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
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
