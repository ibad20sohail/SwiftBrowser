package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.AbsListView
import android.widget.EdgeEffect
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.core.L
import java.lang.reflect.Field
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 3.0.11: a list pulled past its end stretches a little that way and springs back when let go (a
 * fling that hits the end bounces it), as lists do on newer Android, instead of the coloured glow
 * Android 7 to 11 show there. The list itself is scaled from the end being pulled: its own picture,
 * no copy of it and no layer, so it costs next to nothing on an old graphics chip; and what the
 * scale would push past its edges is cut off at them, so nothing beside it is covered. Android 12
 * and later stretch their lists themselves, and that stays. With Android's animations off: nothing.
 *
 * Lists, sheets and scrolling pages get it where Swift colours their glow (Palette). Android 10 and
 * 11 keep the glow (in the accent chosen) on plain scrolling pages: they don't let it be replaced.
 */
internal object Stretch {
    /** At most this much longer (a share of its length), however far it is pulled. */
    const val MOST = 0.1f

    /** Half of [MOST] when pulled this share of its own length: the further, the harder it gets, as rubber. */
    const val HALF_AT = 0.4f

    /** A fling hitting the end stretches it as a pull would that went this long at the fling's speed (s). */
    const val FLING_S = 0.1f

    /** How much a list pulled [pull] (a share of its length) past its end stretches (a share of its length). */
    fun amount(pull: Float): Float = if (pull <= 0f) 0f else MOST * pull / (pull + HALF_AT)

    /** The pull that stretches it [amount]: a stretch caught by the finger on its way back goes on from there. */
    fun pullFor(amount: Float): Float = if (amount <= 0f) 0f else HALF_AT * amount / (MOST - amount.coerceAtMost(MOST * 0.99f))

    /**
     * The speed (a share of its length a second) a stretch starts with when a fling of [velocity]
     * (px a second) hits the end of a list [length] px long: so that the spring, which settles without
     * bouncing, carries it about as far as [FLING_S] of such a pull would. Such a spring started at
     * speed v goes at most v / (e × √stiffness) out.
     */
    fun kick(velocity: Float, length: Int): Float {
        if (length <= 0) return 0f
        val most = amount(abs(velocity) * FLING_S / length)
        return most * kotlin.math.E.toFloat() * kotlin.math.sqrt(Motion.SPRING_PANEL)
    }

    /**
     * How much of its own length, [length] px, stays in its bounds once stretched by [amount]: the
     * rest is cut off at its far end (its clip), so the stretch shows only where the list is.
     */
    fun kept(length: Int, amount: Float): Float = length / (1f + amount.coerceAtLeast(0f))

    // The ends, as RecyclerView names them.
    private const val LEFT = RecyclerView.EdgeEffectFactory.DIRECTION_LEFT
    private const val TOP = RecyclerView.EdgeEffectFactory.DIRECTION_TOP
    private const val RIGHT = RecyclerView.EdgeEffectFactory.DIRECTION_RIGHT
    private const val BOTTOM = RecyclerView.EdgeEffectFactory.DIRECTION_BOTTOM

    /**
     * Gives [v] (a list or a scrolling page) the stretch at its ends. True when its ends stretch now
     * (or never glow: overscrolling off, or Android 12's own stretch); false when the glow has to stay
     * (Android 10 and 11's plain scrolling pages), which the caller then colours.
     */
    @SuppressLint("RestrictedApi")
    fun add(v: View): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return true
        return try {
            when (v) {
                is RecyclerView -> {
                    if (v.edgeEffectFactory !is Factory) v.edgeEffectFactory = Factory()
                    true
                }
                // Its ends are open to change (androidx), on every Android version.
                is NestedScrollView -> {
                    if (v.mEdgeGlowTop !is Edge) {
                        val s = Stretcher(v)
                        v.mEdgeGlowTop = Edge(v.context, s, TOP)
                        v.mEdgeGlowBottom = Edge(v.context, s, BOTTOM)
                    }
                    true
                }
                // Android's own: only below Android 10 (their ends can't be reached later).
                is ScrollView -> Build.VERSION.SDK_INT < 29 && swap(v, ExactAccent.Hidden.scrollTop, ExactAccent.Hidden.scrollBottom, TOP, BOTTOM)
                is HorizontalScrollView -> Build.VERSION.SDK_INT < 29 && swap(v, ExactAccent.Hidden.sideLeft, ExactAccent.Hidden.sideRight, LEFT, RIGHT)
                is AbsListView -> Build.VERSION.SDK_INT < 29 && swap(v, ExactAccent.Hidden.listTop, ExactAccent.Hidden.listBottom, TOP, BOTTOM)
                else -> false
            }
        } catch (e: Exception) {
            L.d("Couldn't give a list its stretch: $e")
            false
        }
    }

    /** [v]'s two ends ([one], [other]) stretch; with overscrolling off it has none, and nothing glows. */
    private fun swap(v: View, one: Field?, other: Field?, oneEnd: Int, otherEnd: Int): Boolean {
        if (one == null || other == null) return false
        val now = one.get(v) ?: return v.overScrollMode == View.OVER_SCROLL_NEVER
        if (now is Edge) return true
        val s = Stretcher(v)
        one.set(v, Edge(v.context, s, oneEnd))
        other.set(v, Edge(v.context, s, otherEnd))
        return true
    }

    /** A RecyclerView's ends, made when first needed, both stretching the one list. */
    private class Factory : RecyclerView.EdgeEffectFactory() {
        private var stretcher: Stretcher? = null

        override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
            val s = stretcher?.takeIf { it.view === view } ?: Stretcher(view).also { stretcher = it }
            return Edge(view.context, s, direction)
        }
    }

    /** One end of a list, as Android asks for it: it draws nothing, it moves the list ([Stretcher]). */
    private class Edge(ctx: Context, private val s: Stretcher, private val end: Int) : EdgeEffect(ctx) {
        override fun onPull(deltaDistance: Float) = s.pull(end, deltaDistance)

        override fun onPull(deltaDistance: Float, displacement: Float) = s.pull(end, deltaDistance)

        override fun onRelease() = s.release(end)

        override fun onAbsorb(velocity: Int) = s.absorb(end, velocity)

        override fun isFinished() = s.finished(end)

        override fun finish() = s.finish(end)

        override fun draw(canvas: Canvas) = false
    }

    /**
     * One list's stretch, shared by its two ends: one end at a time. Pulled, the list grows longer
     * from that end ([MOST] at most), its far end cut off at its edge; let go, it springs back with
     * Motion's panel spring, settling without a bounce.
     */
    private class Stretcher(val view: View) {
        private var end = -1
        private var pull = 0f
        private var amount = 0f

        /** The list's look is changed (its pivot and clip): put back when the stretch has gone. */
        private var changed = false
        private var pivotX = 0f
        private var pivotY = 0f
        private var pivotSet = false
        private val clip = Rect()
        private val value = FloatValueHolder()

        private val spring: SpringAnimation by lazy {
            SpringAnimation(value).apply {
                spring = SpringForce(0f).setStiffness(Motion.SPRING_PANEL).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                // Amounts are small (a tenth at most): settled once within a pixel or so of the end.
                minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE / 4
                addUpdateListener { _, v, _ -> if (changed) show(v) }
                addEndListener { _, canceled, _, _ -> if (!canceled) rest() }
            }
        }

        fun pull(at: Int, delta: Float) {
            if (at != end) to(at)
            if (!changed && !begin()) return
            if (spring.isRunning) {
                // Caught on its way back: on from where it is.
                spring.cancel()
                pull = Stretch.pullFor(amount)
            }
            pull += abs(delta)
            show(Stretch.amount(pull))
        }

        fun release(at: Int) {
            if (at != end || !changed) return
            pull = 0f
            if (amount <= 0f) {
                rest()
                return
            }
            if (spring.isRunning) return
            value.value = amount
            spring.setStartValue(amount)
            spring.setStartVelocity(0f)
            spring.animateToFinalPosition(0f)
        }

        fun absorb(at: Int, velocity: Int) {
            if (at != end) to(at)
            if (!changed && !begin()) return
            spring.cancel()
            pull = 0f
            value.value = amount
            spring.setStartValue(amount)
            spring.setStartVelocity(Stretch.kick(velocity.toFloat(), length()))
            spring.animateToFinalPosition(0f)
        }

        fun finished(at: Int) = at != end || !changed

        fun finish(at: Int) {
            if (at != end || !changed) return
            spring.cancel()
            rest()
        }

        /** The other end now: the one before lets go at once (it had all but settled anyway). */
        private fun to(at: Int) {
            if (changed) {
                spring.cancel()
                rest()
            }
            end = at
            pull = 0f
        }

        private fun vertical() = end == TOP || end == BOTTOM

        private fun length() = if (vertical()) view.height else view.width

        /** Only a list as it rests (not moved by anything else) stretches, and only with animations on. */
        private fun begin(): Boolean {
            if (length() <= 0 || view.scaleX != 1f || view.scaleY != 1f || view.clipBounds != null || !Motion.enabled(view.context)) return false
            changed = true
            pivotX = view.pivotX
            pivotY = view.pivotY
            pivotSet = Build.VERSION.SDK_INT < 28 || view.isPivotSet
            return true
        }

        private fun show(a: Float) {
            amount = a.coerceIn(0f, MOST)
            val w = view.width
            val h = view.height
            val s = 1f + amount
            when (end) {
                TOP -> {
                    view.pivotY = 0f
                    view.scaleY = s
                    clip.set(0, 0, w, ceil(Stretch.kept(h, amount)).toInt())
                }
                BOTTOM -> {
                    view.pivotY = h.toFloat()
                    view.scaleY = s
                    clip.set(0, floor(h - Stretch.kept(h, amount)).toInt(), w, h)
                }
                LEFT -> {
                    view.pivotX = 0f
                    view.scaleX = s
                    clip.set(0, 0, ceil(Stretch.kept(w, amount)).toInt(), h)
                }
                else -> {
                    view.pivotX = w.toFloat()
                    view.scaleX = s
                    clip.set(floor(w - Stretch.kept(w, amount)).toInt(), 0, w, h)
                }
            }
            // The clip is in the list's own (stretched) space: it maps onto exactly its bounds.
            view.clipBounds = clip
        }

        /** The stretch has gone: the list as it was. */
        private fun rest() {
            pull = 0f
            amount = 0f
            if (!changed) return
            changed = false
            view.scaleX = 1f
            view.scaleY = 1f
            view.clipBounds = null
            if (Build.VERSION.SDK_INT >= 28 && !pivotSet) {
                view.resetPivot()
            } else {
                view.pivotX = pivotX
                view.pivotY = pivotY
            }
        }
    }
}
