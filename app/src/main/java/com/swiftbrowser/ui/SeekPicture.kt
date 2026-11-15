package com.swiftbrowser.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 3.0.11: while the video's seek bar is dragged, a small picture of that moment above the finger with
 * the time under it, for videos that are files on the phone (website videos and streams show the time
 * as before). The frames come from Android's own reader (MediaMetadataRetriever) on a thread of their
 * own: the key frame nearest the time (quick to read), read small. Only the latest asked for is read:
 * an older ask is dropped, never queued, so the picture keeps up on older phones and the main thread
 * only places it. One reader per drag, let go with the finger. A video whose frames can't be read
 * shows just the time. In the player's own shade and corners (OverPicture, Ui.R_SMALL).
 */
internal class SeekPicture(private val root: FrameLayout) {
    private val ctx = root.context
    private val pad = Ui.dp(ctx, Ui.XS)

    private val picture = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        // A frame's place while it is read: a shape a step rounder inside the box's corners.
        background = Ui.shape(ctx, OverPicture.TINT_CHIP, Corners.nested(Ui.R_SMALL, Ui.XS))
        clipToOutline = true
    }

    private val time = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        Ui.size(this, Ui.Type.LABEL, keepFont = true)
        typeface = Ui.medium(Ui.Type.LABEL)
        // Figures all of a width: the time doesn't wobble as it changes.
        Ui.steadyFigures(this)
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(0, pad, 0, 0)
    }

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.shape(ctx, OverPicture.FILL_HUD, Ui.R_SMALL)
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
        // The bar itself says where it is to TalkBack.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(picture)
        addView(time)
    }

    /** The bar being dragged, and the frames being read for it (null between drags). */
    private var bar: SeekBar? = null
    private var frames: Frames? = null
    private var shown = false
    private var moves = 0

    /** Where the last frame was asked for (a finger that hardly moved asks for nothing new). */
    private var asked = Long.MIN_VALUE

    /** The picture's width (px), the time's width alone, and whether a frame was read in this drag. */
    private var pictureWidth = 0
    private var timeWidth = 0
    private var withPicture = true
    private var hadFrame = false
    private val barAt = IntArray(2)
    private val rootAt = IntArray(2)

    /**
     * A drag of [seekBar] begins on [file], a video [ratio] wide to its height (0: not known), whose
     * longest time reads [longest] (its length): the box keeps one size while it is dragged.
     */
    fun start(seekBar: SeekBar, file: File, ratio: Float, longest: String) {
        frames?.end()
        if (box.parent == null) root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        bar = seekBar
        moves = 0
        asked = Long.MIN_VALUE
        hadFrame = false
        val (w, h) = SeekPictureMaths.size(ratio, Ui.dp(ctx, PICTURE_DP))
        pictureWidth = w
        timeWidth = (time.paint.measureText(longest) + 4 * pad).roundToInt()
        picture.setImageDrawable(null)
        picture.layoutParams = LinearLayout.LayoutParams(w, h)
        setPicture(true)
        frames = Frames(file.path, w, h) { frame -> frameRead(frame) }
    }

    /**
     * The finger is at [ms], which reads [label]. The first move only notes it (a tap on the bar
     * shows nothing); from the second the box shows over the handle and the frame there is asked for.
     */
    fun move(ms: Long, label: String) {
        val b = bar ?: return
        if (!TextUtils.equals(time.text, label)) time.text = label
        if (++moves < 2) return
        place(b)
        if (!shown) appear()
        // Key frames are seconds apart: a move of a moment would read the same one again.
        if (asked != Long.MIN_VALUE && kotlin.math.abs(ms - asked) < SAME_FRAME_MS) return
        asked = ms
        frames?.ask(ms)
    }

    /** The finger let go: the box goes, and the frames' reader with it. */
    fun stop() {
        bar = null
        frames?.end()
        frames = null
        if (!shown) return
        shown = false
        box.animate().cancel()
        Motion.anim(box, Motion.SHORT, Motion.exit).alpha(0f).withEndAction {
            box.visibility = View.GONE
            // The last frame goes with it (a drag's frames are a quarter of a megabyte each).
            picture.setImageDrawable(null)
        }.start()
    }

    /** The screen is going: nothing is read any more. */
    fun release() {
        bar = null
        frames?.end()
        frames = null
    }

    private fun appear() {
        shown = true
        box.animate().cancel()
        box.alpha = 0f
        box.visibility = View.VISIBLE
        Motion.anim(box, OverPicture.QUICK_MS, Motion.enter).alpha(1f).start()
    }

    /** A frame came for the latest ask (null: it couldn't be read). */
    private fun frameRead(frame: Bitmap?) {
        if (bar == null) return
        if (frame != null) {
            hadFrame = true
            if (!withPicture) setPicture(true)
            picture.setImageBitmap(frame)
        } else if (!hadFrame && withPicture) {
            // None of its frames can be read: just the time.
            setPicture(false)
        }
    }

    /** With the picture, or the time alone; each a size of its own that stays while dragged (no layout as the time changes). */
    private fun setPicture(on: Boolean) {
        withPicture = on
        picture.visibility = if (on) View.VISIBLE else View.GONE
        time.layoutParams = LinearLayout.LayoutParams(if (on) pictureWidth else timeWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        time.setPadding(0, if (on) pad else 0, 0, 0)
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        bar?.let { if (shown) place(it) }
    }

    /** Over the bar's handle (as far as the screen's sides allow), just above the bar. */
    private fun place(b: SeekBar) {
        b.getLocationInWindow(barAt)
        root.getLocationInWindow(rootAt)
        val track = b.width - b.paddingLeft - b.paddingRight
        val share = if (b.max > 0) b.progress.toFloat() / b.max else 0f
        val x = barAt[0] - rootAt[0] + b.paddingLeft + share * track
        val w = box.measuredWidth
        box.translationX = SeekPictureMaths.left(x, w, root.width, Ui.dp(ctx, Ui.MARGIN)).toFloat()
        box.translationY = (barAt[1] - rootAt[1] - box.measuredHeight - pad).toFloat()
    }

    /**
     * One drag's frames of one video, read on the frames' thread: only the latest asked for. The reader
     * opens with the first ask and is let go once the drag ends (after a frame being read, if any).
     */
    private class Frames(private val path: String, private val width: Int, private val height: Int, private val onFrame: (Bitmap?) -> Unit) {
        private val wanted = AtomicLong(NONE)
        private val busy = AtomicBoolean(false)

        @Volatile
        private var ended = false

        // The frames' thread's own.
        private var reader: MediaMetadataRetriever? = null
        private var unreadable = false

        fun ask(ms: Long) {
            if (ended) return
            wanted.set(ms)
            if (busy.compareAndSet(false, true)) worker.execute(drain)
        }

        fun end() {
            ended = true
            wanted.set(NONE)
            worker.execute { close() }
        }

        /** Reads the latest ask, then the one after it, until there is none. */
        private val drain = Runnable {
            while (true) {
                val ms = wanted.getAndSet(NONE)
                if (ms == NONE || ended) {
                    busy.set(false)
                    // An ask that came between the look and the flag: read it now.
                    if (!ended && wanted.get() != NONE && busy.compareAndSet(false, true)) continue
                    break
                }
                val frame = read(ms)
                main.post { if (!ended) onFrame(frame) }
            }
        }

        private fun read(ms: Long): Bitmap? {
            if (unreadable) return null
            val r = reader ?: open() ?: return null
            return try {
                val us = ms * 1000
                if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { shrink(it) }
                }
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

        private fun open(): MediaMetadataRetriever? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(path)
                reader = r
                r
            } catch (e: Exception) {
                unreadable = true
                runCatching { r.release() }
                L.w("Seek picture: the video's frames can't be read (${e.javaClass.simpleName})")
                null
            }
        }

        /** Before Android 8.1 the frame comes whole: made small at once, and the big one let go. */
        private fun shrink(src: Bitmap): Bitmap {
            val s = minOf(width.toFloat() / src.width.coerceAtLeast(1), height.toFloat() / src.height.coerceAtLeast(1))
            if (s >= 1f) return src
            val out = Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
            if (out !== src) src.recycle()
            return out
        }

        private fun close() {
            reader?.let { runCatching { it.release() } }
            reader = null
        }

        private companion object {
            const val NONE = Long.MIN_VALUE
            val main = Handler(Looper.getMainLooper())
        }
    }

    private companion object {
        /** The picture's longer side (dp): about the width of a phone's thumbnail of a video. */
        const val PICTURE_DP = 160

        /** Asks closer than this to the last one (ms of the video) would show the same key frame. */
        const val SAME_FRAME_MS = 250L

        /** One thread for every player's frames (one drag at a time). */
        val worker by lazy { Background.single("seek-pictures") }
    }
}

/** 3.0.11: the seek picture's size and place, as plain maths (tested on the PC, SeekPictureTest). */
internal object SeekPictureMaths {
    /** The usual shape of a video, for one whose own isn't known yet. */
    private const val USUAL = 16f / 9f

    /**
     * The picture's size (px) for a video [ratio] wide to its height (0 or less: not known): its longer
     * side [longest], the other in proportion, but never under a quarter of it (a very wide film).
     */
    fun size(ratio: Float, longest: Int): Pair<Int, Int> {
        val r = if (ratio > 0f && ratio.isFinite()) ratio else USUAL
        return if (r >= 1f) longest to maxOf((longest / r).roundToInt(), longest / 4) else maxOf((longest * r).roundToInt(), longest / 4) to longest
    }

    /** Where a box [boxWidth] wide starts to be centred on [x], kept [margin] from the sides of a [width] wide screen. */
    fun left(x: Float, boxWidth: Int, width: Int, margin: Int): Int {
        val most = width - margin - boxWidth
        if (most < margin) return margin
        return (x - boxWidth / 2f).roundToInt().coerceIn(margin, most)
    }
}
