package com.swiftbrowser.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 3.0.3: the player's touch and layout rules as plain numbers, tested on the PC (PlayerRulesTest; 3.0.4: frame by frame's, FrameStepsTest). */
object PlayerRules {
    /** Pinching out past this fills the screen (the black bars are cut off); in past [PINCH_IN], the whole picture fits again. */
    const val PINCH_OUT = 1.15f
    const val PINCH_IN = 0.87f

    /** 1: fill, -1: fit, 0: not far enough yet, for a pinch [scale] times as wide as it began. */
    fun pinch(scale: Float): Int = when {
        scale >= PINCH_OUT -> 1
        scale <= PINCH_IN -> -1
        else -> 0
    }

    /** After a double tap on a side, each further tap there this soon after the last skips again (as on websites' videos). */
    const val SERIES_MS = 650L

    /** Skips this close together add up on the side's bubble (+10 s, +20 s…), as on websites' videos (MainActivity). */
    const val RUN_MS = 1200L

    /** -1 on the left third of [width], 1 on the right third, 0 in the middle. */
    fun third(x: Float, width: Int): Int = when {
        width <= 0 -> 0
        x < width / 3f -> -1
        x > width - width / 3f -> 1
        else -> 0
    }

    /** The bubble's total after a skip of [stepMs] on [side] at [at]: added up on the same side within [RUN_MS] of the last skip. */
    fun runTotal(totalMs: Long, lastSide: Int, lastAt: Long, side: Int, at: Long, stepMs: Long): Long =
        if (side == lastSide && at - lastAt < RUN_MS) totalMs + stepMs else stepMs

    /** "+30 s", "−20 s": the bubble of a run of skips, written as on websites' videos. */
    fun runLabel(side: Int, totalMs: Long): String = (if (side < 0) "−" else "+") + "${totalMs / 1000} s"

    /** A video ([videoRatio], width / height) more than 10% wider or narrower than the screen it plays on. */
    fun shapeDiffers(videoRatio: Float, screenRatio: Float): Boolean =
        videoRatio > 0f && screenRatio > 0f && abs(videoRatio - screenRatio) / screenRatio > 0.10f

    /** How far the subtitles may be dragged, as a share of the screen's height from their usual place: a little down, well up. */
    const val MIN_LIFT = -0.02f
    const val MAX_LIFT = 0.6f

    fun lift(fraction: Float): Float = if (fraction.isNaN()) 0f else fraction.coerceIn(MIN_LIFT, MAX_LIFT)

    /** How far up the subtitles sit (px): where they were dragged to, but above the controls or an upright panel while those show. */
    fun subtitleUp(liftPx: Float, controlsUp: Float, panelUp: Float): Float = maxOf(liftPx, controlsUp, panelUp)

    /**
     * The picture (from [top] to [bottom]) moved, and made smaller only if it must, to be whole in the
     * [room] above an upright panel: its move down (negative: up) and its scale, about its middle.
     */
    fun fitAbove(top: Float, bottom: Float, room: Float): Pair<Float, Float> {
        val h = bottom - top
        if (h <= 0f || room <= 0f || bottom <= room) return 0f to 1f
        val scale = minOf(1f, room / h)
        return room / 2f - (top + bottom) / 2f to scale
    }

    /** 3.0.4: frame by frame: a video that doesn't say how many frames a second it has is taken as 30. */
    const val FALLBACK_FPS = 30f

    /** How long one frame lasts (µs) at [frameRate] frames a second (Format.frameRate: −1 when not known). */
    fun frameUs(frameRate: Float): Long {
        val fps = if (frameRate.isNaN() || frameRate < 1f || frameRate > 1000f) FALLBACK_FPS else frameRate
        return (1_000_000.0 / fps).roundToLong()
    }

    /**
     * Where to seek (µs) for the frame after ([direction] > 0) or before the one shown at [shownUs]
     * (its own time), frames [frameUs] apart; null when there is none ([durationUs] ≤ 0: not known).
     * A seek shows the first frame at or after where it goes, so half a frame on lands on the next
     * frame, and a frame and a half back on the one before, even when the frame rate said is a little off.
     */
    fun frameStepUs(shownUs: Long, frameUs: Long, direction: Int, durationUs: Long): Long? {
        if (frameUs <= 0) return null
        return if (direction > 0) {
            if (durationUs > 0 && shownUs + frameUs + frameUs / 2 > durationUs) null else shownUs + frameUs / 2
        } else {
            if (shownUs < frameUs) null else (shownUs - frameUs - frameUs / 2).coerceAtLeast(0)
        }
    }

    /** "1:23.456" (an hour or more: "1:02:03.456"): a frame's time, to the millisecond. */
    fun exactTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        val milli = t % 1000
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, milli) else String.format(Locale.US, "%d:%02d.%03d", m, s, milli)
    }
}

/** 3.0.3: the further taps after a double tap on a side (see [PlayerRules.SERIES_MS]). */
class TapSeries {
    var side = 0
        private set
    private var until = 0L

    /** A skip on [side] at [at]: the series goes on from it. */
    fun start(side: Int, at: Long) {
        this.side = side
        until = at + PlayerRules.SERIES_MS
    }

    /** A tap on [side] at [at] is another skip of the series. */
    fun claims(side: Int, at: Long): Boolean = side != 0 && side == this.side && at <= until

    fun clear() {
        side = 0
        until = 0L
    }
}

/** 3.0.3: one pinch, measured from where it began or last snapped (see [PlayerRules.pinch]). */
class PinchSteps {
    private var scale = 1f

    fun begin() {
        scale = 1f
    }

    /** The pinch widened by [factor]: 1 when it now fills, -1 when it fits, 0 otherwise (measured afresh after a snap). */
    fun add(factor: Float): Int {
        if (factor.isNaN() || factor <= 0f) return 0
        scale *= factor
        val d = PlayerRules.pinch(scale)
        if (d != 0) scale = 1f
        return d
    }
}
