package com.swiftbrowser.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.swiftbrowser.core.L
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager

/**
 * Stops what is playing after a while (⋮ menu or the playback notification): after some minutes,
 * or when the video or song that is playing ends. Sites that start the next video by themselves
 * (YouTube, TheFlixer) are stopped too, so nothing plays on, and the screen can turn off.
 */
object SleepTimer {
    /** Minutes to choose from; [END] means at the end of what is playing. */
    val CHOICES = intArrayOf(15, 30, 60, 90)
    const val END = -1

    private val handler = Handler(Looper.getMainLooper())

    /** When the timer runs out (uptime), or 0. */
    var endsAt = 0L
        private set

    /** Stops at the end of the video or song that is playing. */
    var atEnd = false
        private set

    /** After stopping at the end of a video: a next one starting by itself is stopped too, until then. */
    private var stopNextUntil = 0L

    /** Told when the timer is set, runs out or is cancelled (to update the menu and notification). */
    var listener: (() -> Unit)? = null

    val isSet: Boolean get() = endsAt > 0 || atEnd

    private val fire = Runnable {
        endsAt = 0
        stopAll("time is up")
    }

    /** Once a minute while the timer runs: the time left in the menu and notification. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (endsAt <= 0) return
            listener?.invoke()
            com.swiftbrowser.SwiftApp.instance?.let { PlaybackService.sync(it) }
            handler.postDelayed(this, 60_000)
        }
    }

    /** How long the sound fades out before it stops. */
    private const val FADE_MS = 8000L

    fun set(ctx: Context, minutes: Int) {
        cancel(notify = false)
        if (minutes == END) {
            atEnd = true
        } else {
            endsAt = SystemClock.uptimeMillis() + minutes * 60_000L
            handler.postDelayed(fire, minutes * 60_000L)
            handler.postDelayed(tick, 60_000)
        }
        changed(ctx)
    }

    fun cancel(ctx: Context? = null, notify: Boolean = true) {
        handler.removeCallbacks(fire)
        handler.removeCallbacks(tick)
        endsAt = 0
        atEnd = false
        if (notify && ctx != null) changed(ctx)
    }

    /** "23 min left", "End of video", or null when off. */
    fun label(): String? = when {
        atEnd -> "End of video"
        endsAt > 0 -> "${((endsAt - SystemClock.uptimeMillis()) / 60_000L + 1).coerceAtLeast(1)} min left"
        else -> null
    }

    /** The next choice after the current one, for the notification's button: off → 15 → … → end → off. */
    fun cycle(ctx: Context) {
        val steps = CHOICES.toList() + END
        val now = when {
            atEnd -> steps.size - 1
            endsAt > 0 -> {
                val left = (endsAt - SystemClock.uptimeMillis()) / 60_000L
                CHOICES.indexOfLast { it <= left + 1 }.coerceAtLeast(0)
            }
            else -> -1
        }
        if (now + 1 >= steps.size) cancel(ctx) else set(ctx, steps[now + 1])
    }

    /** A video or song in [tab] reached its end. */
    fun onMediaEnded(tab: Tab) {
        if (!atEnd) return
        atEnd = false
        stopNextUntil = SystemClock.uptimeMillis() + 60_000
        stopAll("the video ended")
    }

    /** Something started playing: right after the timer stopped playback, that is the site's next video. */
    fun onPlaying(tab: Tab) {
        if (SystemClock.uptimeMillis() < stopNextUntil) tab.mediaSession?.pause()
    }

    private fun stopAll(why: String) {
        L.d("Sleep timer: $why, fading out")
        val playing = TabManager.tabs.filter { it.playing }
        // The sound fades out over a few seconds instead of stopping at once; the page pauses
        // itself at the end (and is paused here in case it couldn't).
        for (t in playing) Helper.fadeOut(t, FADE_MS)
        handler.postDelayed({ for (t in playing) if (t.playing) t.mediaSession?.pause() }, FADE_MS + 1500)
        stopNextUntil = maxOf(stopNextUntil, SystemClock.uptimeMillis() + FADE_MS + 60_000)
        listener?.invoke()
        TabManager.ui?.showMessage("Sleep timer: stopping playback")
        com.swiftbrowser.SwiftApp.instance?.let { PlaybackService.sync(it) }
    }

    private fun changed(ctx: Context) {
        listener?.invoke()
        PlaybackService.sync(ctx)
    }
}
