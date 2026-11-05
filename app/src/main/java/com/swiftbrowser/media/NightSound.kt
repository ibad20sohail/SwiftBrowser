package com.swiftbrowser.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * 3.0.4: night sound, for watching at night at a low volume: loud parts (explosions, loud music)
 * come down and quiet voices come up. Android 7 has no DynamicsProcessing (Android 9 and later), so
 * it is Swift's own, in the player's sound output (LocalPlayback's sink, before the speed's): a
 * feed-forward compressor on 16-bit sound of any number of channels, which is what the phone's
 * decoders and FFmpeg's (Dolby, DTS) give. The sink turns any other PCM into 16-bit before this;
 * float sound, were it ever to come, goes past untouched.
 *
 * The level is the RMS of all the channels of a frame, followed quickly (10 ms) as it rises and
 * slowly (250 ms) as it falls. Above −30 dBFS it is brought down 3:1 (a soft knee 10 dB wide), then
 * everything is raised by 8 dB, so sound at −18 dBFS stays as it is. A soft limiter (at once, letting
 * go over 50 ms) keeps every sample under −1 dBFS. One gain for all the channels of a frame, worked
 * out every [BLOCK] frames and slid to; nothing is made while the sound goes through.
 *
 * It is always in the sink: off ([enabled] false) the sound is copied through as it is, so turning
 * it on or off needs no new start of the player (it slides over 30 ms, without a click). A seek
 * starts it afresh from the level it leaves as it is.
 */
@OptIn(UnstableApi::class)
class NightSound : BaseAudioProcessor() {
    /** Set on the main thread, read on the playback thread. */
    @Volatile
    var enabled = false

    // How fast each part moves at the sound's sample rate (worked out in onConfigure).
    private var attack = 0f
    private var release = 0f
    private var letGo = 0f
    private var slide = 0f

    // The playback thread's own.
    /** The level: the mean of the channels' squares, smoothed. */
    private var level = NEUTRAL_POWER

    /** The compressor's gain, moving by [gainStep] a frame to what the last block worked out. */
    private var gain = 1f
    private var gainStep = 0f
    private var blockLeft = 0

    /** The limiter's gain (1: not limiting). */
    private var limit = 1f

    /** 0: the sound as it is, 1: night sound; it slides between them as it is turned on or off. */
    private var wet = 0f

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only 16-bit sound: anything else isn't touched (the processor is left out).
        if (format.encoding != C.ENCODING_PCM_16BIT || format.sampleRate <= 0 || format.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val rate = format.sampleRate.toFloat()
        attack = smoothing(ATTACK_S, rate)
        release = smoothing(RELEASE_S, rate)
        letGo = smoothing(LIMIT_RELEASE_S, rate)
        slide = 1f / (SLIDE_S * rate)
        return format
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val size = end - start
        if (size <= 0) return
        val out = replaceOutputBuffer(size)
        val on = enabled
        if (!on && wet <= 0f) {
            // Off: as it is.
            out.put(inputBuffer)
            out.flip()
            return
        }
        // Turned on: from the level it leaves as it is, so nothing jumps.
        if (wet <= 0f) startOver(0f)
        val channels = inputAudioFormat.channelCount
        val frameBytes = inputAudioFormat.bytesPerFrame
        val perChannel = 1f / channels
        var i = start
        while (i + frameBytes <= end) {
            var peak = 0f
            var squares = 0f
            var c = 0
            while (c < channels) {
                val x = inputBuffer.getShort(i + 2 * c) * SCALE
                squares += x * x
                val a = abs(x)
                if (a > peak) peak = a
                c++
            }
            val power = squares * perChannel
            level = power + (if (power > level) attack else release) * (level - power)
            if (level < FLOOR_POWER) level = FLOOR_POWER
            if (blockLeft == 0) {
                val target = toGain(gainDb(10f * log10(level)))
                gainStep = (target - gain) / BLOCK
                blockLeft = BLOCK
            }
            gain += gainStep
            blockLeft--
            // The limiter: down at once to keep this frame's peak under the limit, back up slowly.
            limit = 1f - (1f - limit) * letGo
            val peakOut = peak * gain
            if (peakOut > LIMIT_KNEE) {
                val need = limited(peakOut) / peakOut
                if (need < limit) limit = need
            }
            if (on) {
                if (wet < 1f) wet = minOf(1f, wet + slide)
            } else {
                wet = maxOf(0f, wet - slide)
            }
            val g = 1f + wet * (gain * limit - 1f)
            c = 0
            while (c < channels) {
                val v = Math.round(inputBuffer.getShort(i + 2 * c) * g)
                out.putShort(v.coerceIn(-32768, 32767).toShort())
                c++
            }
            i += frameBytes
        }
        // Part of a frame (never sent, but kept as it is).
        while (i < end) {
            out.put(inputBuffer.get(i))
            i++
        }
        inputBuffer.position(end)
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = startOver(if (enabled) 1f else 0f)

    override fun onReset() = startOver(0f)

    private fun startOver(wetness: Float) {
        level = NEUTRAL_POWER
        gain = 1f
        gainStep = 0f
        blockLeft = 0
        limit = 1f
        wet = wetness
    }

    companion object {
        const val THRESHOLD_DB = -30f
        const val RATIO = 3f
        const val KNEE_DB = 10f
        const val MAKEUP_DB = 8f
        const val ATTACK_S = 0.010f
        const val RELEASE_S = 0.250f

        /** The limiter keeps every sample under this (−1 dBFS, 1 being full scale), bending from [LIMIT_KNEE] (−4 dBFS). */
        const val LIMIT = 0.8912509f
        const val LIMIT_KNEE = 0.6309573f
        const val LIMIT_RELEASE_S = 0.050f

        /** How long turning it on or off takes. */
        const val SLIDE_S = 0.030f

        /** The gain is worked out every so many frames (under a millisecond) and slid to. */
        const val BLOCK = 32

        /** The level (dBFS) night sound leaves as it is (−18): where the cut and the make-up gain meet. */
        const val NEUTRAL_DB = THRESHOLD_DB + MAKEUP_DB * RATIO / (RATIO - 1f)

        private val NEUTRAL_POWER = 10f.pow(NEUTRAL_DB / 10f)

        /** −100 dBFS: the level goes no lower (no denormal numbers in a long silence). */
        private const val FLOOR_POWER = 1e-10f
        private const val SCALE = 1f / 32768f

        /** ln(10) / 20: dB to a gain. */
        private const val DB_TO_LN = 0.115129255f

        /** The gain (dB) for sound whose level (its RMS) is [levelDb] dBFS: 3:1 above the threshold, with a soft knee, and the make-up gain. */
        fun gainDb(levelDb: Float): Float {
            val over = levelDb - THRESHOLD_DB
            val slope = 1f / RATIO - 1f
            val cut = when {
                2f * over <= -KNEE_DB -> 0f
                2f * over >= KNEE_DB -> slope * over
                else -> slope * (over + KNEE_DB / 2f) * (over + KNEE_DB / 2f) / (2f * KNEE_DB)
            }
            return cut + MAKEUP_DB
        }

        /** The soft limiter: a peak [x] (1 is full scale) as it comes out, as it is up to [LIMIT_KNEE], then bending to stay under [LIMIT]. */
        fun limited(x: Float): Float {
            if (x <= LIMIT_KNEE) return x
            val room = LIMIT - LIMIT_KNEE
            return LIMIT_KNEE + room * (1f - exp(-(x - LIMIT_KNEE) / room))
        }

        private fun toGain(db: Float): Float = exp(db * DB_TO_LN)

        /** A one-pole smoothing factor for a time constant of [seconds]. */
        private fun smoothing(seconds: Float, rate: Float): Float = exp(-1f / (seconds * rate))
    }
}
