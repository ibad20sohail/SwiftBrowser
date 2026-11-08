package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.swiftbrowser.R

/**
 * 3.0.3: the player's menus (speed, sleep timer, turning, the playlist, sound and subtitles, the
 * equalizer, More) in a panel on the player's own screen instead of a sheet over it: no window of
 * its own and nothing dimmed, so the video plays on in full screen beside it. On a turned screen it
 * is a column at the right (45% of the width, at most 380 dp) that slides in from the side;
 * upright, it comes up from the bottom (at most half the screen) and the picture moves up out of its
 * way (the host does that). A tap beside it, or Back, closes it. Over a video it is dark and a
 * little see-through; on the music screen it is in Swift's colours. It only moves (no shadow).
 */
class PlayerPanel(private val root: FrameLayout, private val host: Host) {
    interface Host {
        /** Over a video (dark), or on the music screen (Swift's colours). */
        fun panelOverVideo(): Boolean

        /** The panel opened, changed or closed: [side] for the column at the right; [size] its width or height, 0 once closed. */
        fun onPanelMoved(side: Boolean, size: Int, animate: Boolean)
    }

    private val ctx: Context = root.context
    private var shield: View? = null
    private var box: Box? = null
    private var shown: View? = null
    private var shownScrolls = true

    var isOpen = false
        private set

    /** The column at the right (a turned screen); otherwise the panel at the bottom. */
    var side = false
        private set

    /** Its width at the side, or its height at the bottom. */
    var size = 0
        private set

    /** What it shows (to tell whether a choice put something else in its place). */
    val content: View? get() = shown

    /** What content is made with: Swift's dark look over a video (whatever the app's own is), the screen's own on the music screen. */
    fun context(): Context = if (host.panelOverVideo()) dark(ctx) else ctx

    /** The most the panel can be high. */
    fun maxHeight(): Int = if (root.width > root.height) root.height else root.height / 2

    /** Shows [content] (in a scrolling box unless [scrolls] is false: a list that scrolls itself), or puts it in place of what shows. */
    fun show(content: View, scrolls: Boolean = true) {
        if (root.width <= 0 || root.height <= 0) return
        val wasOpen = isOpen
        val wasSide = side
        val oldSize = size
        val fresh = content !== shown
        val dark = host.panelOverVideo()
        side = root.width > root.height
        shown = content
        shownScrolls = scrolls
        if (fresh) Palette.paint(content)
        (content.parent as? ViewGroup)?.removeView(content)
        val inner: View = if (scrolls) {
            NestedScrollView(content.context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        } else {
            content
        }
        val b = box ?: Box(ctx).also { box = it }
        b.removeAllViews()
        b.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (side && scrolls) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
        b.background = background(dark, side)
        val width = if (side) minOf((root.width * 0.45f).toInt(), Ui.dp(ctx, 380)) else root.width
        b.maxHeight = if (side) 0 else root.height / 2
        val lp = if (side) {
            FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        if (shield == null) {
            shield = Shield(ctx).also { root.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        }
        if (b.parent == null) root.addView(b, lp) else b.layoutParams = lp
        // Its size now, so it slides in from its edge and the picture moves as far as it must.
        b.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, if (side) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
        )
        size = if (side) width else b.measuredHeight
        isOpen = true
        b.animate().cancel()
        if (!wasOpen) {
            // 3.0.4: in on a spring (closing it on the way in turns it back smoothly).
            if (b.parent == null || !b.isLaidOut || (b.translationX == 0f && b.translationY == 0f)) {
                b.translationX = if (side) size.toFloat() else 0f
                b.translationY = if (side) 0f else size.toFloat()
            }
            Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_X, 0f)
            Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_Y, 0f)
        } else {
            Motion.stopSprings(b)
            b.translationX = 0f
            // Upright, a taller panel in place of a shorter one rises to its height; a shorter one, the fade covers.
            if (fresh && !side && !wasSide && size > oldSize) {
                b.translationY = (size - oldSize).toFloat()
                Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_Y, 0f)
            } else {
                b.translationY = 0f
            }
            if (fresh) {
                inner.alpha = 0f
                // Through a layer: the whole panel isn't drawn again every frame over the video.
                Motion.anim(inner, Motion.SHORT, Motion.standard).alpha(1f).withLayer().start()
            }
        }
        host.onPanelMoved(side, size, true)
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        shown = null
        shield?.let { root.removeView(it) }
        shield = null
        val b = box ?: return
        host.onPanelMoved(side, 0, animate)
        b.animate().cancel()
        if (!animate || !b.isShown) {
            Motion.stopSprings(b)
            root.removeView(b)
            b.removeAllViews()
            return
        }
        // 3.0.4: out on a spring, as it came in.
        val gone = {
            if (!isOpen) {
                root.removeView(b)
                b.removeAllViews()
            }
        }
        if (side) {
            Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_Y, 0f)
            Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_X, size.toFloat(), end = gone)
        } else {
            Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_X, 0f)
            Motion.spring(b, androidx.dynamicanimation.animation.SpringAnimation.TRANSLATION_Y, size.toFloat(), end = gone)
        }
    }

    /** The screen turned: the panel goes to its side or the bottom once the screen has its new size. */
    fun relayout() {
        if (!isOpen) return
        val content = shown ?: return
        val scrolls = shownScrolls
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                root.removeOnLayoutChangeListener(this)
                root.post { if (isOpen && shown === content) show(content, scrolls) }
            }
        })
    }

    private fun background(dark: Boolean, side: Boolean): Drawable {
        val r = Ui.dpf(ctx, Ui.R_MEDIUM.toFloat())
        // The corners away from the screen's edge are round.
        val radii = if (side) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        val fill = GradientDrawable().apply {
            cornerRadii = radii
            setColor(if (dark) OverPicture.FILL_PANEL else Palette.color(ctx, R.color.sheet_bg))
        }
        if (dark) return fill
        // On the music screen a thin line where it meets the screen (both can be white).
        val line = GradientDrawable().apply {
            cornerRadii = radii
            setColor(Palette.color(ctx, R.color.outline))
        }
        return LayerDrawable(arrayOf(line, fill)).apply {
            val px = Ui.dp(ctx, 1)
            if (side) setLayerInset(1, px, 0, 0, 0) else setLayerInset(1, 0, px, 0, 0)
        }
    }

    /** Over the rest of the screen: a tap there closes the panel (and doesn't reach the video under it). */
    @SuppressLint("ViewConstructor")
    private inner class Shield(c: Context) : View(c) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) close()
            return true
        }
    }

    /** Holds the content, at most [maxHeight] high (0: as high as it is given); touches on it stay on it. */
    private class Box(c: Context) : FrameLayout(c) {
        var maxHeight = 0

        init {
            isClickable = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var h = heightMeasureSpec
            if (maxHeight > 0) {
                val mode = MeasureSpec.getMode(h)
                h = if (mode == MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
                } else {
                    MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(h), maxHeight), mode)
                }
            }
            super.onMeasure(widthMeasureSpec, h)
        }
    }

    companion object {

        /** Swift's dark mode colours (and the theme's, for Material parts), whatever the app's own mode is. */
        fun dark(base: Context): Context {
            val config = Configuration(base.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            return androidx.appcompat.view.ContextThemeWrapper(base, R.style.Theme_Swift).apply {
                applyOverrideConfiguration(config)
                theme.applyStyle(Looks.accent.overlay, true)
            }
        }
    }
}

/**
 * What the player, the picture viewer and the picture editor share over a dark picture: its fills
 * and shades, its pills, and the bars that come and go.
 */
internal object OverPicture {
    /** Under the top bar, and from the bottom edge up under the bottom bar. */
    private const val SHADE_TOP = 0xCC000000.toInt()
    private const val SHADE_BOTTOM = 0xE0000000.toInt()
    private const val SHADE_BOTTOM_MIDDLE = 0x99000000.toInt()

    /** A small dark shape on the picture (the boost sign, unlock, frame by frame's bar). */
    const val FILL_SOFT = 0x99000000.toInt()

    /** The gesture readout's box. */
    const val FILL_HUD = 0xB3000000.toInt()

    /** Panels and Up next: dark and a little see-through. */
    const val FILL_PANEL = 0xF0121418.toInt()

    /** Behind the loading indicator. */
    const val FILL_SPINNER = 0x66000000

    /** A chip or a small picture's place, and a pill that isn't the main one. */
    const val TINT_CHIP = 0x33FFFFFF
    const val TINT_PILL = 0x26FFFFFF

    /** A quick answer (a readout, a picture after a wait, the grid while dragging). */
    const val QUICK_MS = 120L

    /** A double tap's icon going toward the skip and back: [QUICK_MS] in all. */
    const val NUDGE_OUT_MS = 50L
    const val NUDGE_BACK_MS = 70L
    const val NUDGE_DP = 6f

    /** How long a wait must be before the loading indicator shows. */
    const val SPINNER_WAIT_MS = 400L

    /** How far bars slide as they come and go (dp). */
    private const val BAR_SLIDE_DP = 8f

    fun shadeTop() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SHADE_TOP, 0))

    fun shadeBottom() = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(SHADE_BOTTOM, SHADE_BOTTOM_MIDDLE, 0))

    /** A ripple in [ink] that shows on what [ink] is written on. */
    fun rippleOf(ink: Int): Int = Tones.withAlpha(ink, android.graphics.Color.alpha(Ui.RIPPLE_ON_DARK))

    /**
     * [top] and [bottom] coming or going: a fade and a small slide from their edges, each through its
     * one layer. Asked again on the way, they turn back from where they are.
     */
    fun bars(top: View, bottom: View, show: Boolean) {
        bar(top, -1, show)
        bar(bottom, 1, show)
    }

    private fun bar(v: View, edge: Int, show: Boolean) {
        v.animate().cancel()
        val slide = Ui.dpf(v.context, BAR_SLIDE_DP) * edge
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.translationY = slide
                v.visibility = View.VISIBLE
            }
            Motion.anim(v, Motion.SHORT, Motion.enter).alpha(1f).translationY(0f).withLayer().start()
        } else {
            Motion.anim(v, Motion.SHORT, Motion.exit).alpha(0f).translationY(slide).withLayer().withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    /**
     * Words on a filled pill, at least [heightDp] high, round at half that unless [radiusDp] says
     * otherwise; a ripple in [ripple] (null: the screen's own) and a press.
     */
    fun pill(
        ctx: Context,
        label: CharSequence,
        type: Ui.Type,
        ink: Int,
        fill: Int,
        heightDp: Int,
        radiusDp: Int = heightDp / 2,
        ripple: Int? = Ui.RIPPLE_ON_DARK,
        outline: Int? = null,
    ): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(ink)
        Ui.size(this, type, keepFont = true)
        typeface = Ui.medium(type)
        minHeight = Ui.dp(ctx, heightDp)
        background = Ui.ripple(ctx, Ui.shape(ctx, fill, radiusDp, outline), radiusDp, ripple)
        Ui.pressable(this, Motion.PRESS_BUTTON)
    }

    /** The shape of a [pill], for what changes its colour later. */
    fun pillFill(pill: View): GradientDrawable = (pill.background as RippleDrawable).getDrawable(0) as GradientDrawable
}
