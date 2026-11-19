package com.swiftbrowser.core

/**
 * 3.0.13: what Swift does when the phone's free memory drops while it is on screen (Health looks
 * every [CHECK_MS]). In a problem report the phone went from 419 to 91 MB free in about 30 seconds
 * during a video: Android's own drawing in Swift's main process then got no graphics memory, and
 * Android 7 ends an app on purpose when that happens. The once-a-minute memory note and Android's
 * own warnings came too late. Plain code, tested on the PC (MemoryGuardTest).
 */
object MemoryGuard {
    enum class Action {
        /** Enough memory. */
        NONE,

        /** Other tabs let go of their pages (the open, playing and kept ones stay). */
        UNLOAD,

        /** That, and the engine's own clean-up plus Swift's and Android's picture caches. */
        RELIEVE,
    }

    const val CHECK_MS = 2_000L

    /** The clean-up runs at most this often: it costs the engine some work each time. */
    const val RELIEVE_EVERY_MS = 15_000L

    /** Before any clean-up: [decide] lets the first one run at once. */
    const val NEVER = -RELIEVE_EVERY_MS

    /**
     * [free] and [line] (where Android starts closing apps) in bytes; [low]: Android says memory is
     * low now; [now] and [lastRelief] in the same clock (ms). Under twice the line other tabs go (as
     * Diagnostics.memoryLow counts low); under one and a half times it, or when Android says low, the
     * clean-up too, at most every [RELIEVE_EVERY_MS].
     */
    fun decide(free: Long, line: Long, low: Boolean, now: Long, lastRelief: Long): Action {
        if (line <= 0L || free <= 0L) return Action.NONE
        val critical = low || free < line * 3 / 2
        if (critical) return if (now - lastRelief >= RELIEVE_EVERY_MS) Action.RELIEVE else Action.UNLOAD
        return if (free < line * 2) Action.UNLOAD else Action.NONE
    }
}
