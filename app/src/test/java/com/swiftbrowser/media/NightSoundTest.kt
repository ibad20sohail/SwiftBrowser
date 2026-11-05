package com.swiftbrowser.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NightSoundTest {
    private val rate = 48_000

    private fun processor(on: Boolean, channels: Int = 2): NightSound = NightSound().apply {
        enabled = on
        configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    /** [samples] (interleaved) through [p], in pieces as the sink sends them. */
    private fun run(p: NightSound, samples: ShortArray, channels: Int, pieceFrames: Int = 1024): ShortArray {
        val out = ShortArray(samples.size)
        var at = 0
        while (at < samples.size) {
            val n = minOf(pieceFrames * channels, samples.size - at)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putShort(samples[at + k])
            buf.flip()
            p.queueInput(buf)
            assertFalse("all of it taken", buf.hasRemaining())
            val o = p.output
            assertEquals(n * 2, o.remaining())
            for (k in 0 until n) out[at + k] = o.getShort()
            at += n
        }
        return out
    }

    /** A 1 kHz tone of [amplitude] (1 is full scale) on every channel, for [seconds]. */
    private fun tone(amplitude: Double, seconds: Double, channels: Int = 2): ShortArray {
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * channels) { i -> (amplitude * 32767 * sin(2 * PI * 1000 * (i / channels) / rate)).roundToInt().toShort() }
    }

    /** The RMS of the last [seconds] (1 is full scale). */
    private fun rmsOfLast(s: ShortArray, seconds: Double, channels: Int = 2): Double {
        val n = minOf(s.size, (seconds * rate).toInt() * channels)
        var sum = 0.0
        for (i in s.size - n until s.size) sum += (s[i] / 32768.0) * (s[i] / 32768.0)
        return sqrt(sum / n)
    }

    @Test
    fun silenceStaysSilent() {
        val p = processor(on = true)
        val out = run(p, ShortArray(rate * 2), 2)
        assertTrue(out.all { it == 0.toShort() })
    }

    @Test
    fun aLoudToneIsMadeQuieter() {
        val p = processor(on = true)
        val input = tone(0.9, 2.0)
        val out = run(p, input, 2)
        val ratio = rmsOfLast(out, 0.5) / rmsOfLast(input, 0.5)
        // About −10 dB (a third) once settled.
        assertTrue("ratio $ratio", ratio < 0.5)
        assertTrue("ratio $ratio", ratio > 0.2)
    }

    @Test
    fun aQuietToneIsMadeLouder() {
        val p = processor(on = true)
        val input = tone(0.01, 3.0)
        val out = run(p, input, 2)
        val ratio = rmsOfLast(out, 0.5) / rmsOfLast(input, 0.5)
        // The make-up gain: +8 dB, two and a half times.
        assertTrue("ratio $ratio", ratio > 2.2)
        assertTrue("ratio $ratio", ratio < 2.8)
    }

    @Test
    fun theSoundNeverGoesOverTheLimit() {
        val most = NightSound.LIMIT * 32768 + 1
        for (channels in intArrayOf(1, 2, 6)) {
            val p = processor(on = true, channels = channels)
            // A quiet moment (the gain goes up), then full-scale bursts: a square wave and noise.
            val random = Random(channels)
            val quiet = ShortArray(rate / 2 * channels)
            val square = ShortArray(rate / 2 * channels) { i -> if ((i / channels / 24) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
            val noise = ShortArray(rate / 2 * channels) { random.nextInt(-32768, 32768).toShort() }
            val out = run(p, quiet + square + tone(0.02, 0.3, channels) + noise + tone(1.0, 0.5, channels), channels, pieceFrames = 333)
            val loudest = out.maxOf { abs(it.toInt()) }
            assertTrue("$channels channels: $loudest", loudest <= most)
        }
    }

    @Test
    fun offCopiesTheSoundAsItIs() {
        val p = processor(on = false)
        val random = Random(7)
        val input = ShortArray(rate * 2) { random.nextInt(-32768, 32768).toShort() }
        assertArrayEquals(input, run(p, input, 2))
    }

    @Test
    fun turnedOffItSlidesBackToTheSoundAsItIs() {
        val p = processor(on = true)
        run(p, tone(0.9, 1.0), 2)
        p.enabled = false
        val input = tone(0.9, 0.5)
        val out = run(p, input, 2)
        // After the 30 ms slide, exactly as it was.
        val from = (0.05 * rate).toInt() * 2
        assertArrayEquals(input.copyOfRange(from, input.size), out.copyOfRange(from, out.size))
        // And the slide starts where night sound was (no jump at the first frame).
        assertTrue(abs(out[2].toInt()) < abs(input[2].toInt()) || input[2].toInt() == 0)
    }

    @Test
    fun floatSoundIsLeftAlone() {
        val p = NightSound()
        p.enabled = true
        val f = p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, f)
        assertFalse(p.isActive)
        val p16 = NightSound()
        p16.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        assertTrue(p16.isActive)
    }

    @Test
    fun theGainComputer() {
        // Quiet: the make-up gain only.
        assertEquals(8f, NightSound.gainDb(-60f), 0.001f)
        assertEquals(8f, NightSound.gainDb(-35f), 0.001f)
        // −18 dBFS is left as it is; louder comes down, 3:1 above −30.
        assertEquals(0f, NightSound.gainDb(NightSound.NEUTRAL_DB), 0.001f)
        assertEquals(-18f, NightSound.NEUTRAL_DB, 0.001f)
        assertEquals(-12f, NightSound.gainDb(0f), 0.001f)
        // The knee is soft: no step at its edges.
        assertEquals(NightSound.gainDb(-25.001f), NightSound.gainDb(-24.999f), 0.01f)
        assertEquals(NightSound.gainDb(-35.001f), NightSound.gainDb(-34.999f), 0.01f)
        // Louder in is always louder out (speech keeps its shape): never squashed past flat.
        var last = -1000f
        var level = -90f
        while (level <= 0f) {
            val out = level + NightSound.gainDb(level)
            assertTrue("at $level", out >= last)
            last = out
            level += 0.5f
        }
    }

    @Test
    fun theLimiterBendsUnderMinusOneDb() {
        assertEquals(0.5f, NightSound.limited(0.5f), 0f)
        assertEquals(NightSound.LIMIT_KNEE, NightSound.limited(NightSound.LIMIT_KNEE), 0f)
        assertTrue(NightSound.limited(0.8f) < 0.8f)
        assertTrue(NightSound.limited(0.8f) > NightSound.LIMIT_KNEE)
        assertTrue(NightSound.limited(100f) <= NightSound.LIMIT + 1e-6f)
        assertTrue(NightSound.limited(2f) > NightSound.limited(1f))
    }
}
