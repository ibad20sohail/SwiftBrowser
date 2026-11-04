package com.swiftbrowser.media

import androidx.media3.common.C
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

/**
 * 3.0.3: the subtitles' and the sound's timing in Swift's player (Sound and subtitles → Timing), as
 * plain numbers so the rules are tested on the PC (DelaysTest). Both are in milliseconds, later
 * (> 0) or earlier (< 0) than the picture, at most [MAX_MS] either way, in steps of [STEP_MS].
 */
object Delays {
    const val STEP_MS = 100L
    const val MAX_MS = 10_000L

    /** The last moment and a half of a stream, where later subtitles are back in time (see [subtitlePositionUs]). */
    const val END_MARGIN_US = 1_500_000L

    fun clamp(ms: Long): Long = ms.coerceIn(-MAX_MS, MAX_MS)

    /** One tap of − ([direction] < 0) or + (> 0). */
    fun step(ms: Long, direction: Int): Long = clamp(ms + direction.sign * STEP_MS)

    /** "0.3 s later", "1.25 s earlier", "In time". */
    fun label(ms: Long): String {
        if (ms == 0L) return "In time"
        val s = String.format(Locale.US, "%.2f", abs(ms) / 1000.0).trimEnd('0').trimEnd('.')
        return if (ms > 0) "$s s later" else "$s s earlier"
    }

    /**
     * Where the subtitle renderer is told the video is, at [positionUs] with the subtitles [delayUs]
     * later: behind by the delay (ahead, for earlier ones). In the last [END_MARGIN_US] of the stream
     * being read ([streamEndUs], C.TIME_UNSET when unknown) later subtitles get the true position:
     * the renderer reads a stream's end only once it gets there, and ExoPlayer waits for that before
     * it goes on to the next video or ends.
     */
    fun subtitlePositionUs(positionUs: Long, delayUs: Long, streamEndUs: Long): Long {
        if (delayUs == 0L) return positionUs
        if (delayUs > 0 && streamEndUs != C.TIME_UNSET && positionUs >= streamEndUs - END_MARGIN_US) return positionUs
        return positionUs - delayUs
    }

    /**
     * The sound's clock, which the picture follows, for the sound's own position [actualUs] and the
     * sound [delayUs] later. Sound later means the picture runs ahead of the sound, so the clock says
     * the sound is further on than it is (actual + delay); earlier is the other way round. The sink's
     * "not known yet" (Long.MIN_VALUE) stays as it is.
     */
    fun soundClockUs(actualUs: Long, delayUs: Long): Long = if (actualUs == Long.MIN_VALUE) actualUs else actualUs + delayUs

    /**
     * After the subtitles' timing went from [fromMs] to [toMs]: whether the video's own subtitles are
     * read again (a quick seek where the video is). Earlier subtitles are read ahead of the picture
     * and dropped once shown; made later again, some of them are wanted again.
     */
    fun rereadSubtitles(fromMs: Long, toMs: Long): Boolean = fromMs < 0 && toMs > fromMs
}
