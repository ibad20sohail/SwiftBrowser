package com.swiftbrowser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * 3.0.11: a picture opened from Files grows out of its small picture there into the picture viewer,
 * over a dark background that comes in meanwhile, and shrinks back into it when the viewer is closed
 * on that picture. The viewer's window is see-through for that (MediaViewerActivity.OverFiles), so
 * Files shows behind it. The moving picture is drawn here ([Move]): one picture cut to a rounded box,
 * with no copy of the screen and no layer. First the small picture Files had in memory, then the
 * whole picture once it is decoded (until then only the middle square, the part the small one shows,
 * since nothing of the rest is known yet). Motion's time and curve, and only with animations on.
 *
 * Android's own shared-element motion isn't used: this costs one drawn picture a frame.
 */
internal object PictureGrow {
    /**
     * What Files hands over as one of its pictures opens: the file, where its small picture was on
     * the screen ([rect], with [radius] corners), that small picture ([thumb], the picture's middle
     * square), the picture's own size upright, and the colour of Files' status bar.
     */
    class Start(val path: String, val rect: Rect, val radius: Float, val thumb: Bitmap, val width: Int, val height: Int, val barColor: Int)

    /** A picture's small picture on the screen (px) and its corners, as Files shows it now, and the colour of Files' status bar. */
    class Spot(val rect: Rect, val radius: Float, val barColor: Int)

    /** What the viewer asks Files while a picture opened from it is on show. */
    interface Source {
        /** [path]'s small picture, if it is wholly on the screen now and Files is on show behind the viewer. */
        fun spot(path: String): Spot?

        /** [path]'s small picture is hidden while the picture moves out of it or back into it; null: none is. */
        fun hold(path: String?)
    }

    private var start: Start? = null
    private var source: WeakReference<Source>? = null

    /** Files opens a picture: the viewer takes [s] as it starts ([claim]) and asks [from] when it closes. */
    fun handOver(s: Start, from: Source) {
        start = s
        source = WeakReference(from)
    }

    /** Files was made again (turned) while one of its pictures may be open: it is the one to ask now. */
    fun use(from: Source) {
        source = WeakReference(from)
    }

    /** Files has gone. */
    fun forget(from: Source) {
        if (source?.get() === from) source = null
    }

    /** What Files handed over for the picture at [path], once (null when there is none for it). */
    fun claim(path: String): Start? {
        val s = start
        start = null
        return s?.takeIf { it.path == path }
    }

    fun source(): Source? = source?.get()

    // ------------------------------------------------------------------ where the picture is (plain numbers, tested on the PC)

    /** A box on the screen (px). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2
        val centerY: Float get() = (top + bottom) / 2
    }

    /** The picture ([w] × [h]) as the viewer shows it at first: as big as fits in a [boxW] × [boxH] screen, in its middle (PictureView). */
    fun fit(w: Int, h: Int, boxW: Int, boxH: Int): Box {
        val s = min(boxW.toFloat() / w, boxH.toFloat() / h)
        val l = (boxW - w * s) / 2
        val t = (boxH - h * s) / 2
        return Box(l, t, l + w * s, t + h * s)
    }

    /** The whole picture ([w] × [h]) placed so its middle square fills [square]: as Files' small picture shows it (FileThumbs). */
    fun aroundSquare(w: Int, h: Int, square: Box): Box {
        val s = square.width / min(w, h)
        return Box(square.centerX - w * s / 2, square.centerY - h * s / 2, square.centerX + w * s / 2, square.centerY + h * s / 2)
    }

    /** The middle square of a [w] × [h] picture drawn at [picture]: the part a small picture shows. */
    fun middle(picture: Box, w: Int, h: Int): Box {
        val half = min(w, h) * picture.width / w / 2
        return Box(picture.centerX - half, picture.centerY - half, picture.centerX + half, picture.centerY + half)
    }

    /** What of [a] is also in [b] (an empty box where they don't meet). */
    fun within(a: Box, b: Box): Box {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        return Box(l, t, max(l, min(a.right, b.right)), max(t, min(a.bottom, b.bottom)))
    }

    /** [a] moved [f] of the way to [b]. */
    fun mix(a: Box, b: Box, f: Float) = Box(
        a.left + (b.left - a.left) * f, a.top + (b.top - a.top) * f,
        a.right + (b.right - a.right) * f, a.bottom + (b.bottom - a.bottom) * f,
    )

    private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun boxOf(r: Rect, dx: Int, dy: Int) = Box((r.left - dx).toFloat(), (r.top - dy).toFloat(), (r.right - dx).toFloat(), (r.bottom - dy).toFloat())

    // ------------------------------------------------------------------ the moving picture

    /** The picture on its way: its picture cut to [clip] with [corner] corners, the whole picture being at [picture]. */
    private class Picture(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val m = Matrix()
        private val from = RectF()
        private val to = RectF()
        private var bitmap: Bitmap? = null

        /** [bitmap] is the whole picture; otherwise only its middle square (Files' small picture). */
        var whole = false
            private set

        /** The picture's own size, upright. */
        var w = 1
        var h = 1
        var picture = Box(0f, 0f, 0f, 0f)
        var clip = Box(0f, 0f, 0f, 0f)
        var corner = 0f

        init {
            // It stands for what is under it while it moves: taps wait for it.
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun show(b: Bitmap, whole: Boolean) {
            bitmap = b
            this.whole = whole
            paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            invalidate()
        }

        fun place(picture: Box, clip: Box, corner: Float) {
            this.picture = picture
            this.clip = clip
            this.corner = corner
            invalidate()
        }

        fun clear() {
            bitmap = null
            paint.shader = null
        }

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            val shader = paint.shader ?: return
            if (clip.width <= 0f || clip.height <= 0f) return
            val area = if (whole) picture else middle(picture, w, h)
            from.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
            to.set(area.left, area.top, area.right, area.bottom)
            m.setRectToRect(from, to, Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(m)
            c.drawRoundRect(clip.left, clip.top, clip.right, clip.bottom, corner, corner, paint)
        }
    }

    /**
     * The viewer's side of it, on [root] (its black background fades in and out with the motion),
     * with [pager] hidden while the picture moves and [bars] coming and going with the background.
     */
    class Move(private val a: Activity, private val root: FrameLayout, private val pager: View, private val bars: List<View>) {
        private val view = Picture(a)
        private val bg: ColorDrawable = (root.background as? ColorDrawable ?: ColorDrawable(Color.BLACK)).also { root.background = it }
        private val argb = ArgbEvaluator()
        private var motion: ValueAnimator? = null
        private var reveal: ValueAnimator? = null
        private var path: String? = null
        private var held = false
        private var growing = false
        private var shrinking = false

        /** The growing has got to its end (the whole picture may still be on its way). */
        private var arrived = false
        private var then: (() -> Unit)? = null

        /** How far it has got (0 to 1), and how much of the whole picture shows around its middle square (0 to 1, growing). */
        private var f = 0f
        private var shown = 0f

        // The way it goes: from Files' small picture to the screen, or back.
        private var pictureFrom = NONE
        private var pictureTo = NONE
        private var clipFrom = NONE
        private var clipTo = NONE
        private var cornerFrom = 0f
        private var cornerTo = 0f
        private var bgFrom = 0
        private var bgTo = 255
        private var barFrom = Color.BLACK
        private var barTo = Color.BLACK
        private var lightBar: Boolean? = null
        private var nightFrom = 1f

        /** The viewer's night light, once it has one (looked for until then, not on every frame after). */
        private var night: View? = null

        private fun night(): View? = night ?: NightLight.layer(a)?.also { night = it }

        /** The picture is moving (growing or shrinking): the viewer's own pages are hidden meanwhile. */
        val moving: Boolean get() = growing || shrinking

        val isShrinking: Boolean get() = shrinking

        /**
         * The picture of [s] grows out of Files' small picture into the screen: on the first frame
         * at that small picture's place, then over [Motion.LONG]; [done] once the whole picture has
         * taken its place (the viewer's own page shows from then on).
         */
        fun grow(s: Start, done: () -> Unit = {}) {
            path = s.path
            growing = true
            arrived = false
            then = done
            view.w = s.width
            view.h = s.height
            view.show(s.thumb, whole = false)
            bg.alpha = 0
            pager.visibility = View.INVISIBLE
            for (b in bars) b.alpha = 0f
            root.addView(view, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            barFrom = s.barColor
            barTo = Color.BLACK
            bgFrom = 0
            bgTo = 255
            barColour(barFrom)
            val observer = root.viewTreeObserver
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    (if (observer.isAlive) observer else root.viewTreeObserver).removeOnPreDrawListener(this)
                    if (!growing || root.width <= 0 || root.height <= 0) return true
                    val at = IntArray(2).also { root.getLocationOnScreen(it) }
                    val thumb = boxOf(s.rect, at[0], at[1])
                    pictureFrom = aroundSquare(view.w, view.h, thumb)
                    pictureTo = fit(view.w, view.h, root.width, root.height)
                    clipFrom = thumb
                    cornerFrom = s.radius
                    cornerTo = 0f
                    f = 0f
                    update()
                    // From the next frame on, once this one (the small picture where it was) is up: so
                    // the time Android takes to show a new screen doesn't eat the motion's first part.
                    root.postOnAnimation { if (growing && motion == null) run(Motion.LONG) }
                    return true
                }
            })
        }

        /**
         * The whole picture was decoded ([bmp], [w] × [h]), or can't be read (null): it takes the
         * small one's place, and the rest of it comes into sight around the middle square.
         */
        fun full(bmp: Bitmap?, w: Int, h: Int) {
            if (!growing || view.whole) return
            if (bmp == null || w <= 0 || h <= 0) {
                // Nothing more to show: the viewer says so on its own page.
                end()
                return
            }
            if ((w != view.w || h != view.h) && root.width > 0) {
                // Its size as decoded wins (a turn Files' small picture didn't know of).
                view.w = w
                view.h = h
                pictureTo = fit(w, h, root.width, root.height)
            }
            view.show(bmp, whole = true)
            reveal = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.SHORT
                interpolator = Motion.standard
                addUpdateListener {
                    shown = it.animatedValue as Float
                    update()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        reveal = null
                        // 3.0.11: the Move's own end(), not the ending animator's (inside apply {} a bare end()
                        // is ValueAnimator.end(): on Android 14+ that called this listener again, for ever).
                        if (growing && arrived) this@Move.end()
                    }
                })
                start()
            }
        }

        /**
         * The picture on show ([from], the viewer's page, at ([dx], [dy]) in the screen; unless it is
         * still growing: then from where it is now) shrinks back into Files' small picture [to] over
         * [Motion.NORMAL] as the screen clears to Files behind it; [done] once it is there (the viewer
         * then closes at once). False when there is no picture to move.
         */
        @Suppress("DEPRECATION")
        fun shrink(from: PictureView?, dx: Float, dy: Float, to: Spot, path: String, done: () -> Unit): Boolean {
            if (shrinking) return true
            if (growing) {
                // Turned back on its way out of Files: from where it is now.
                motion?.cancel()
                reveal?.cancel()
                motion = null
                reveal = null
                growing = false
            } else {
                if (from == null) return false
                val bmp = from.shown ?: return false
                val r = RectF()
                if (!from.pictureRect(r) || from.fullWidth <= 0 || from.fullHeight <= 0) return false
                view.w = from.fullWidth
                view.h = from.fullHeight
                view.show(bmp, whole = true)
                val picture = Box(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
                view.place(picture, within(picture, Box(0f, 0f, root.width.toFloat(), root.height.toFloat())), 0f)
                if (view.parent == null) root.addView(view, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                pager.visibility = View.INVISIBLE
            }
            this.path = path
            shrinking = true
            then = done
            val at = IntArray(2).also { root.getLocationOnScreen(it) }
            val thumb = boxOf(to.rect, at[0], at[1])
            pictureFrom = view.picture
            pictureTo = aroundSquare(view.w, view.h, thumb)
            clipFrom = view.clip
            clipTo = thumb
            cornerFrom = view.corner
            cornerTo = to.radius
            bgFrom = bg.alpha
            bgTo = 0
            barFrom = a.window.statusBarColor
            barTo = to.barColor
            nightFrom = night()?.alpha ?: 1f
            f = 0f
            for (b in bars) Motion.anim(b, Motion.NORMAL).alpha(0f).withLayer().start()
            hold(true)
            run(Motion.NORMAL)
            return true
        }

        /** Stops at once (the screen turned, or went): as at the end of growing, the viewer's own page showing. */
        fun stop() {
            motion?.cancel()
            reveal?.cancel()
            motion = null
            reveal = null
            if (!growing && !shrinking) return
            growing = false
            shrinking = false
            then = null
            finishUp()
        }

        private fun run(ms: Long) {
            if (growing) hold(true)
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                interpolator = Motion.standard
                addUpdateListener {
                    f = it.animatedValue as Float
                    update()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        motion = null
                        if (shrinking) {
                            landed()
                        } else if (growing) {
                            // Grown: done once the whole picture shows too (it may still be decoding).
                            arrived = true
                            if (view.whole && reveal == null) this@Move.end()
                        }
                    }
                })
                start()
            }
            if (growing) for (b in bars) Motion.anim(b, ms).alpha(1f).withLayer().start()
        }

        /** The frame for how far it has got. */
        private fun update() {
            val clipEnd = if (growing) mix(middle(pictureTo, view.w, view.h), pictureTo, shown) else clipTo
            view.place(mix(pictureFrom, pictureTo, f), mix(clipFrom, clipEnd, f), mix(cornerFrom, cornerTo, f))
            bg.alpha = mix(bgFrom.toFloat(), bgTo.toFloat(), f).toInt().coerceIn(0, 255)
            barColour(argb.evaluate(f, barFrom, barTo) as Int)
            // The viewer's night light comes and goes with its background, so Files' own (under it) isn't doubled.
            night()?.alpha = if (growing) f else mix(nightFrom, 0f, f)
        }

        /** The status bar (when it shows) goes from Files' colour to the viewer's black and back, its icons dark or light on it. */
        @Suppress("DEPRECATION")
        private fun barColour(c: Int) {
            a.window.statusBarColor = c
            val light = Contrast.luminance(c) > 0.5
            if (light != lightBar) {
                lightBar = light
                WindowCompat.getInsetsController(a.window, a.window.decorView).isAppearanceLightStatusBars = light
            }
        }

        /** Grown: the viewer's own page takes over (the same picture, in the same place). */
        private fun end() {
            if (!growing) return
            growing = false
            val done = then
            then = null
            finishUp()
            done?.invoke()
        }

        /** Back in Files' small picture: Files shows it again under this last frame, then the viewer goes. */
        private fun landed() {
            hold(false)
            val done = then
            then = null
            root.postOnAnimation { done?.invoke() }
        }

        private fun finishUp() {
            bg.alpha = 255
            barColour(Color.BLACK)
            pager.visibility = View.VISIBLE
            for (b in bars) {
                b.animate().cancel()
                b.alpha = 1f
            }
            night()?.alpha = 1f
            (view.parent as? ViewGroup)?.removeView(view)
            view.clear()
            shrinking = false
            hold(false)
        }

        private fun hold(on: Boolean) {
            if (held == on) return
            held = on
            source()?.hold(if (on) path else null)
        }

        private companion object {
            val NONE = Box(0f, 0f, 0f, 0f)
        }
    }
}
