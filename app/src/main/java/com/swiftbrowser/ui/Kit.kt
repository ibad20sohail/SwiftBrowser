package com.swiftbrowser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.engine.Engine
import java.util.Calendar

/*
 * Swift's look in one place (1.9): the accent colour and light/dark choice, text styles, spacing,
 * shapes, touch feedback and motion. Every screen builds from these, so the app looks and moves the
 * same everywhere. Motion only moves and fades views (cheap on a slow phone's graphics chip) and
 * never touches the page.
 */

/** The accent colours to choose from (Settings → Look & screen). */
enum class Accent(
    val key: String,
    val label: String,
    val overlay: Int,
    val primary: Int,
    val onPrimary: Int,
    val container: Int,
    val onContainer: Int,
) {
    BLUE("blue", "Blue", R.style.ThemeOverlay_Swift_Accent_Blue, R.color.accent_blue, R.color.on_accent_blue, R.color.accent_blue_container, R.color.on_accent_blue_container),
    GREEN("green", "Green", R.style.ThemeOverlay_Swift_Accent_Green, R.color.accent_green, R.color.on_accent_green, R.color.accent_green_container, R.color.on_accent_green_container),
    PURPLE("purple", "Purple", R.style.ThemeOverlay_Swift_Accent_Purple, R.color.accent_purple, R.color.on_accent_purple, R.color.accent_purple_container, R.color.on_accent_purple_container),
    ORANGE("orange", "Orange", R.style.ThemeOverlay_Swift_Accent_Orange, R.color.accent_orange, R.color.on_accent_orange, R.color.accent_orange_container, R.color.on_accent_orange_container),
    RED("red", "Red", R.style.ThemeOverlay_Swift_Accent_Red, R.color.accent_red, R.color.on_accent_red, R.color.accent_red_container, R.color.on_accent_red_container),
    TEAL("teal", "Teal", R.style.ThemeOverlay_Swift_Accent_Teal, R.color.accent_teal, R.color.on_accent_teal, R.color.accent_teal_container, R.color.on_accent_teal_container);

    companion object {
        fun of(key: String?): Accent = entries.firstOrNull { it.key == key } ?: BLUE
    }
}

/** The accent colour and light or dark, for the whole app. */
object Looks {
    const val LIGHT = 0
    const val DARK = 1
    /** Dark from 7 in the evening until 7 in the morning. */
    const val NIGHT = 2

    private const val NIGHT_FROM = 19
    private const val NIGHT_UNTIL = 7

    val accent: Accent get() = Accent.of(SwiftApp.prefs.accent)

    /** Every screen calls this in onCreate, before its views are made. */
    fun apply(activity: Activity) {
        activity.theme.applyStyle(accent.overlay, true)
    }

    fun modeLabel(mode: Int) = when (mode) {
        DARK -> "Dark"
        NIGHT -> "Dark at night"
        else -> "Light"
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_FROM || hour < NIGHT_UNTIL
    }

    /** Whether the app should be dark now, by the chosen mode (and the time, for "Dark at night"). */
    fun wantsDark(): Boolean = when (SwiftApp.prefs.themeMode) {
        DARK -> true
        NIGHT -> isNight()
        else -> false
    }

    /** At start-up, before the engine and any screen: light or dark as chosen. */
    fun start() {
        val prefs = SwiftApp.prefs
        prefs.darkMode = wantsDark()
        SwiftApp.applyNightMode(prefs.darkMode)
    }

    /**
     * Light or dark changes if needed (the chosen mode changed, or "Dark at night" passed 7
     * o'clock). Open screens redraw in the new colours; pages that support dark mode follow.
     */
    fun sync() {
        val prefs = SwiftApp.prefs
        val dark = wantsDark()
        if (dark == prefs.darkMode) return
        prefs.darkMode = dark
        // Sites that choose their colours when the page loads (Google, YouTube) follow after the
        // reload done when the screen restarts.
        try {
            Engine.setDark(dark)
        } catch (_: UninitializedPropertyAccessException) {
        }
        SwiftApp.reloadAfterThemeChange = true
        SwiftApp.applyNightMode(dark)
    }

    fun setMode(mode: Int) {
        SwiftApp.prefs.themeMode = mode
        sync()
    }

    fun setAccent(activity: Activity, a: Accent) {
        if (SwiftApp.prefs.accent == a.key) return
        SwiftApp.prefs.accent = a.key
        // Reader view's links follow too.
        com.swiftbrowser.media.Helper.sendReaderStyle()
        activity.recreate()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val nightCheck = Runnable { sync() }

    /** While a screen is open with "Dark at night": switch at 7 o'clock even if nothing else happens. */
    fun watchClock(on: Boolean) {
        handler.removeCallbacks(nightCheck)
        if (!on || SwiftApp.prefs.themeMode != NIGHT) return
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 5)
            set(Calendar.MILLISECOND, 0)
            val h = now.get(Calendar.HOUR_OF_DAY)
            set(Calendar.HOUR_OF_DAY, if (h in NIGHT_UNTIL until NIGHT_FROM) NIGHT_FROM else NIGHT_UNTIL)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        handler.postDelayed(nightCheck, next.timeInMillis - now.timeInMillis)
    }
}

/** Sizes, colours, text styles and shapes. */
object Ui {
    // Spacing (dp): every gap in the app is one of these.
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 20
    const val XXL = 24

    // Corner radii (dp).
    const val R_SMALL = 12
    const val R_MEDIUM = 16
    const val R_LARGE = 24

    /** The widest content gets on a turned phone or a tablet (dp); it is centred beyond that. */
    const val MAX_CONTENT_WIDTH = 560

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun color(ctx: Context, res: Int) = ContextCompat.getColor(ctx, res)

    fun accent(ctx: Context) = color(ctx, Looks.accent.primary)
    fun onAccent(ctx: Context) = color(ctx, Looks.accent.onPrimary)
    fun accentContainer(ctx: Context) = color(ctx, Looks.accent.container)
    fun onAccentContainer(ctx: Context) = color(ctx, Looks.accent.onContainer)
    fun text(ctx: Context) = color(ctx, R.color.text_primary)
    fun textSecondary(ctx: Context) = color(ctx, R.color.text_secondary)
    fun danger(ctx: Context) = color(ctx, R.color.danger)

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Text styles: size (sp), medium weight or not, and secondary (grey) colour or not. */
    enum class Type(val sp: Float, val medium: Boolean, val secondary: Boolean) {
        HEADLINE(22f, true, false),
        TITLE(18f, true, false),
        SUBTITLE(15.5f, true, false),
        BODY(15f, false, false),
        BODY_SECONDARY(14f, false, true),
        LABEL(13.5f, true, false),
        CAPTION(12.5f, false, true),
        SECTION(13f, true, false),
    }

    fun style(tv: TextView, type: Type, color: Int? = null): TextView = tv.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
        typeface = if (type.medium) MEDIUM else Typeface.DEFAULT
        setTextColor(color ?: if (type == Type.SECTION) accent(context) else if (type.secondary) textSecondary(context) else text(context))
        if (type == Type.BODY || type == Type.BODY_SECONDARY) setLineSpacing(0f, 1.15f)
    }

    fun text(ctx: Context, type: Type, value: CharSequence?, color: Int? = null, lines: Int = 0): TextView =
        style(TextView(ctx), type, color).apply {
            text = value
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    /** A filled rounded shape, with an optional outline. */
    fun shape(ctx: Context, fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(ctx, radiusDp.toFloat())
            if (stroke != null) setStroke(dpf(ctx, strokeDp).toInt().coerceAtLeast(1), stroke)
        }

    fun circle(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun highlight(ctx: Context): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)) {
            if (tv.resourceId != 0) color(ctx, tv.resourceId) else tv.data
        } else {
            0x1F000000
        }
    }

    /** Touch ripple over [content], kept inside a rounded shape of [radiusDp]. */
    fun ripple(ctx: Context, content: Drawable?, radiusDp: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(highlight(ctx)), content, shape(ctx, 0xFFFFFFFF.toInt(), radiusDp))

    /** Touch ripple for a round icon button (spreads in a circle without a box). */
    fun rippleBorderless(ctx: Context): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(highlight(ctx)), null, null)

    fun icon(ctx: Context, res: Int, tint: Int): Drawable? =
        ContextCompat.getDrawable(ctx, res)?.mutate()?.also { it.setTint(tint) }

    /** An icon in a soft round badge (settings rows, empty screens, sheets). */
    fun iconBadge(ctx: Context, res: Int, sizeDp: Int = 40, tint: Int = onAccentContainer(ctx), fill: Int = accentContainer(ctx)): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(icon(ctx, res, tint))
            background = circle(fill)
            val size = dp(ctx, sizeDp)
            // Big badges (empty screens, the welcome) get a big icon, drawn at its size.
            if (sizeDp >= 56) {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = (size * 0.28f).toInt()
                setPadding(p, p, p, p)
            } else {
                scaleType = ImageView.ScaleType.CENTER
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    fun divider(ctx: Context, insetStartDp: Int = 0): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1).coerceAtMost(2)).apply {
            marginStart = dp(ctx, insetStartDp)
        }
    }

    /**
     * Pressing [v] shrinks it a little and lets go with a small spring: every button, tile and card
     * reacts to the finger the same way. Done by state, so it never interferes with touch handling.
     */
    fun pressable(v: View, scale: Float = 0.96f) {
        val down = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(v, View.SCALE_X, scale), ObjectAnimator.ofFloat(v, View.SCALE_Y, scale))
            duration = 90
            interpolator = Motion.standard
        }
        val up = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(v, View.SCALE_X, 1f), ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f))
            duration = 180
            interpolator = Motion.overshoot
        }
        v.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), up)
        }
    }

    /** A centred "nothing here" screen: an icon in a soft circle, a title and a hint. */
    fun emptyState(ctx: Context, iconRes: Int, title: String, hint: String?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(ctx, 40), dp(ctx, 48), dp(ctx, 40), dp(ctx, 48))
        addView(iconBadge(ctx, iconRes, 88, tint = accent(ctx)))
        addView(text(ctx, Type.TITLE, title).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 24), 0, 0)
        })
        if (!hint.isNullOrBlank()) {
            addView(text(ctx, Type.BODY_SECONDARY, hint).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 8), 0, 0)
            })
        }
    }

    /** A soft rounded box in the accent's container colour, for a small note at the top of a page. */
    fun noteBox(ctx: Context, iconRes: Int, message: CharSequence): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(ctx, accentContainer(ctx), R_MEDIUM)
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        addView(ImageView(ctx).apply {
            setImageDrawable(icon(ctx, iconRes, onAccentContainer(ctx)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 12) })
        addView(text(ctx, Type.BODY_SECONDARY, message, onAccentContainer(ctx)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}

/** How things move: three durations and the same curves everywhere. */
object Motion {
    const val SHORT = 150L
    const val NORMAL = 220L
    const val LONG = 300L

    /** Starts quickly and settles gently: things moving on screen. */
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Things coming in. */
    val enter = PathInterpolator(0f, 0f, 0f, 1f)
    /** Things going away. */
    val exit = PathInterpolator(0.3f, 0f, 1f, 1f)
    /** A small spring past the end and back (letting go of a pressed button, a count changing). */
    val overshoot = android.view.animation.OvershootInterpolator(2f)

    /** Fades [v] in, rising from [fromDy] dp below where it sits. */
    fun fadeIn(v: View, fromDy: Int = 8, delay: Long = 0, duration: Long = NORMAL) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = Ui.dpf(v.context, fromDy.toFloat())
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(enter).withLayer().start()
    }

    /** Fades [v] out (sinking [toDy] dp), then hides it and runs [then]. */
    fun fadeOut(v: View, toDy: Int = 8, duration: Long = SHORT, gone: Boolean = true, then: (() -> Unit)? = null) {
        v.animate().cancel()
        v.animate().alpha(0f).translationY(Ui.dpf(v.context, toDy.toFloat())).setStartDelay(0).setDuration(duration)
            .setInterpolator(exit).withLayer().withEndAction {
                v.visibility = if (gone) View.GONE else View.INVISIBLE
                v.alpha = 1f
                v.translationY = 0f
                then?.invoke()
            }.start()
    }

    /** A quick grow-and-settle, to show something changed (the tab count, a new item). */
    fun bump(v: View, scale: Float = 1.18f) {
        v.animate().cancel()
        v.animate().scaleX(scale).scaleY(scale).setDuration(110).setInterpolator(standard).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(overshoot).start()
        }.start()
    }

    /** Folds [v] away (fading and closing its height), then runs [then]: rows that were removed. */
    fun collapse(v: View, then: () -> Unit) {
        val start = v.height
        if (start <= 0 || !v.isAttachedToWindow) {
            then()
            return
        }
        val lp = v.layoutParams
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = NORMAL
            interpolator = standard
            addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f
                lp.height = (start * f).toInt()
                v.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    v.layoutParams = lp
                    v.alpha = 1f
                    then()
                }
            })
            start()
        }
    }

    /** Children of [group] come in one after another (only for short lists: a sheet's rows). */
    fun stagger(group: ViewGroup, step: Long = 22, max: Int = 10) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (i >= max) break
            fadeIn(c, fromDy = 10, delay = i * step, duration = NORMAL)
        }
    }
}

/**
 * Holds its one child at most [Ui.MAX_CONTENT_WIDTH] wide, centred: settings and lists don't
 * stretch across a turned phone.
 */
class MaxWidthLayout @JvmOverloads constructor(context: Context, attrs: android.util.AttributeSet? = null) : FrameLayout(context, attrs) {
    var maxWidthDp = Ui.MAX_CONTENT_WIDTH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = Ui.dp(context, maxWidthDp)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > max) {
            val side = (w - max) / 2
            setPadding(side, paddingTop, side, paddingBottom)
        } else if (paddingLeft != 0 || paddingRight != 0) {
            setPadding(0, paddingTop, 0, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * Grey shapes where content is about to appear (news, lists), gently pulsing, instead of a
 * spinner. [rows] rows of a picture-less line pair, or with a picture on the right.
 */
class SkeletonView(context: Context, private val rows: Int = 4, private val picture: Boolean = true) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(context, R.color.skeleton) }
    private val rect = RectF()
    private val d = resources.displayMetrics.density
    private var pulse: ObjectAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows * 88 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 16 * d
        val r = 6 * d
        for (i in 0 until rows) {
            val top = i * 88 * d + 14 * d
            val right = if (picture) width - pad - 72 * d - 12 * d else width - pad
            rect.set(pad, top, right, top + 14 * d)
            canvas.drawRoundRect(rect, r, r, paint)
            rect.set(pad, top + 22 * d, pad + (right - pad) * 0.7f, top + 36 * d)
            canvas.drawRoundRect(rect, r, r, paint)
            rect.set(pad, top + 48 * d, pad + 90 * d, top + 58 * d)
            canvas.drawRoundRect(rect, r, r, paint)
            if (picture) {
                rect.set(width - pad - 72 * d, top, width - pad, top + 60 * d)
                canvas.drawRoundRect(rect, 10 * d, 10 * d, paint)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.45f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }
}
