package com.swiftbrowser.media

/**
 * 3.0.4: repeating a part of a video or song (A-B, as VLC does), its rules as plain numbers so they
 * are tested on the PC (PartRepeatTest). The start (A) is set from ⋮, the end (B) from the chip
 * that then shows. Playback that reaches the end goes back to the start, and so does a seek past the
 * end; a seek before the start plays on into the part. Times in milliseconds.
 */
object PartRepeat {
    /** The end must come at least this long after the start. */
    const val MIN_MS = 1_000L

    /**
     * The end is watched for no later than this long before the very end of what plays: a video or
     * song can end before the player gets to its last moment.
     */
    const val END_ROOM_MS = 200L

    /** [endMs] can end a part starting at [startMs]. */
    fun endFits(startMs: Long, endMs: Long): Boolean = startMs >= 0 && endMs - startMs >= MIN_MS

    /** Where the end of the part is watched for: [endMs], but not in the last [END_ROOM_MS] of [durationMs] (≤ 0: not known). */
    fun watchAt(endMs: Long, durationMs: Long): Long =
        if (durationMs > 0 && endMs > durationMs - END_ROOM_MS) (durationMs - END_ROOM_MS).coerceAtLeast(0) else endMs

    /**
     * Where playback at [positionMs] goes for the part from [startMs] to [endMs] (where it is watched
     * for, see [watchAt]): back to the start once at or past the end; null to go on (no part, or not there yet).
     */
    fun backTo(startMs: Long, endMs: Long, positionMs: Long): Long? =
        if (startMs >= 0 && endMs > startMs && positionMs >= endMs) startMs else null
}
