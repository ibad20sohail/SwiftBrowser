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
import android.graphics.Color
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

/**
 * The accent colours to choose from (Settings → Look & screen → Colours). The first six have their
 * own hand-picked colours for light and dark; the others, and the user's own ([CUSTOM], 2.3), are
 * worked out from one colour (Palette).
 */
enum class Accent(
    val key: String,
    val label: String,
    /** The colour it is known by (its swatch), and the one its tones are worked out from. */
    val seed: Int,
    private val overlayRes: Int = 0,
    val primaryRes: Int = 0,
    val onPrimaryRes: Int = 0,
    val containerRes: Int = 0,
    val onContainerRes: Int = 0,
) {
    BLUE("blue", "Blue", 0xFF1B66E4.toInt(), R.style.ThemeOverlay_Swift_Accent_Blue, R.color.accent_blue, R.color.on_accent_blue, R.color.accent_blue_container, R.color.on_accent_blue_container),
    INDIGO("indigo", "Indigo", 0xFF4355C9.toInt()),
    PURPLE("purple", "Purple", 0xFF7A4CD9.toInt(), R.style.ThemeOverlay_Swift_Accent_Purple, R.color.accent_purple, R.color.on_accent_purple, R.color.accent_purple_container, R.color.on_accent_purple_container),
    PINK("pink", "Pink", 0xFFD0306E.toInt()),
    RED("red", "Red", 0xFFC62845.toInt(), R.style.ThemeOverlay_Swift_Accent_Red, R.color.accent_red, R.color.on_accent_red, R.color.accent_red_container, R.color.on_accent_red_container),
    ORANGE("orange", "Orange", 0xFFC2570C.toInt(), R.style.ThemeOverlay_Swift_Accent_Orange, R.color.accent_orange, R.color.on_accent_orange, R.color.accent_orange_container, R.color.on_accent_orange_container),
    AMBER("amber", "Amber", 0xFFE09B00.toInt()),
    GREEN("green", "Green", 0xFF1E7D45.toInt(), R.style.ThemeOverlay_Swift_Accent_Green, R.color.accent_green, R.color.on_accent_green, R.color.accent_green_container, R.color.on_accent_green_container),
    TEAL("teal", "Teal", 0xFF00796B.toInt(), R.style.ThemeOverlay_Swift_Accent_Teal, R.color.accent_teal, R.color.on_accent_teal, R.color.accent_teal_container, R.color.on_accent_teal_container),
    CYAN("cyan", "Cyan", 0xFF0097A7.toInt()),
    BROWN("brown", "Brown", 0xFF8A5A44.toInt()),
    GRAPHITE("graphite", "Graphite", 0xFF5B6270.toInt()),
    CUSTOM("custom", "Your colour", 0);

    // Every screen asks for the accent by these ids; Palette answers with this accent's colours.
    val primary: Int get() = R.color.accent
    val onPrimary: Int get() = R.color.on_accent
    val container: Int get() = R.color.accent_container
    val onContainer: Int get() = R.color.on_accent_container

    /** Its colour: the seed, or the one the user picked. */
    val seedColor: Int get() = if (this == CUSTOM) SwiftApp.prefs.accentCustom else seed

    /**
     * The hand-picked accent closest in hue: the theme of Android's own parts (a text cursor), which
     * can't take any colour while the app runs. 3.0: a grey, white or black has no hue to go by (it
     * came out red, with a red cursor and text handles): blue, the default, then.
     */
    val nearestPreset: Accent
        get() = when {
            primaryRes != 0 -> this
            Contrast.greyish(seedColor) -> BLUE
            else -> entries.filter { it.primaryRes != 0 }.minBy { Tones.hueDistance(seedColor, it.seed) }
        }

    val overlay: Int get() = nearestPreset.overlayRes

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
        // The window behind the screen in the chosen background (2.3), so nothing flashes the default.
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Palette.color(activity, R.color.page_bg)))
    }

    fun modeLabel(mode: Int) = when (mode) {
        DARK -> "Dark"
        NIGHT -> "Dark at night"
        else -> "Light"
    }

    /** 7 in the evening until 7 in the morning ("Dark at night", the night light). */
    fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_FROM || hour < NIGHT_UNTIL
    }

    /** Whether the app should be dark now, by the chosen mode (and the time, for "Dark at night"). */
    fun wantsDark(): Boolean = when (SwiftApp.prefs.themeMode) {
        DARK -> true
        NIGHT -> isNightTime()
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
        // 3.0.4: the home screen widget turns light or dark with Swift.
        SwiftApp.instance?.let { SearchWidget.refresh(it) }
    }

    fun setMode(mode: Int) {
        SwiftApp.prefs.themeMode = mode
        sync()
    }

    fun setAccent(activity: Activity, a: Accent, custom: Int? = null) {
        val prefs = SwiftApp.prefs
        if (prefs.accent == a.key && (custom == null || custom == prefs.accentCustom)) return
        prefs.accent = a.key
        if (custom != null) prefs.accentCustom = custom
        colorsChanged(activity)
    }

    /** The background of light or dark mode (2.3): a preset's key, or Palette.CUSTOM with [custom]. */
    fun setBackground(activity: Activity, night: Boolean, key: String, custom: Int? = null) {
        val prefs = SwiftApp.prefs
        if (night) {
            prefs.darkBg = key
            if (custom != null) prefs.darkBgCustom = custom
        } else {
            prefs.lightBg = key
            if (custom != null) prefs.lightBgCustom = custom
        }
        colorsChanged(activity)
    }

    /** A ready-made combination (2.3): accent, light and dark mode's background in one go. */
    fun setColours(activity: Activity, accent: Accent, lightBg: String, darkBg: String) {
        val prefs = SwiftApp.prefs
        if (prefs.accent == accent.key && prefs.lightBg == lightBg && prefs.darkBg == darkBg) return
        prefs.accent = accent.key
        prefs.lightBg = lightBg
        prefs.darkBg = darkBg
        colorsChanged(activity)
    }

    /**
     * 3.0.4: colours from a picture (PictureColours.mix): its accent as the user's own accent, and
     * its light and dark backgrounds as the user's own backgrounds, in one go.
     */
    fun setPictureColours(activity: Activity, accent: Int, lightBg: Int, darkBg: Int) {
        val prefs = SwiftApp.prefs
        prefs.accent = Accent.CUSTOM.key
        prefs.accentCustom = accent
        prefs.lightBg = Palette.CUSTOM
        prefs.lightBgCustom = lightBg
        prefs.darkBg = Palette.CUSTOM
        prefs.darkBgCustom = darkBg
        colorsChanged(activity)
    }

    private fun colorsChanged(activity: Activity) {
        Palette.invalidate()
        // Reader view's links follow too.
        com.swiftbrowser.media.Helper.sendReaderStyle()
        // 3.0.4: and the home screen widget.
        SearchWidget.refresh(activity)
        activity.recreate()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val nightCheck = Runnable {
        NightLight.refreshAll()
        watchClock(true)
        syncUnlessWatching()
    }

    /** 3.0: a switch that waited for a video, tried again every minute. */
    private val themeRetry = Runnable { syncUnlessWatching() }

    /**
     * "Dark at night" at 7 o'clock, but (3.0) not in the middle of a video: the screen would be
     * rebuilt and the page reloaded. The switch waits until the open tab stops playing and leaves full
     * screen, or until Swift is opened again (onStart syncs).
     */
    private fun syncUnlessWatching() {
        handler.removeCallbacks(themeRetry)
        val tab = com.swiftbrowser.tabs.TabManager.current
        if (wantsDark() != SwiftApp.prefs.darkMode && tab != null && (tab.playing || tab.fullscreen)) {
            handler.postDelayed(themeRetry, 60_000)
        } else {
            sync()
        }
    }

    /** While a screen is open with "Dark at night" or the night light at night: switch at 7 o'clock even if nothing else happens. */
    fun watchClock(on: Boolean) {
        handler.removeCallbacks(nightCheck)
        // 3.0: a switch waiting for a video goes when the screen does: it's made when Swift is back.
        if (!on) handler.removeCallbacks(themeRetry)
        val prefs = SwiftApp.prefs
        if (!on || (prefs.themeMode != NIGHT && prefs.nightLight != NightLight.AT_NIGHT)) return
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

    // Corner radii (dp). 3.0.4: every rounded corner in the app is one of these four (around 20
    // different sizes had crept in): XSMALL for small marks, labels and the inner corners of grouped
    // rows; SMALL for pictures, tiles and icon boxes; MEDIUM for cards; LARGE for sheets, dialogs and
    // the outer corners of grouped rows. Thin bars and pills stay fully round (half their height),
    // and a shape inside another is a step smaller than the one around it.
    const val R_XSMALL = 8
    const val R_SMALL = 12
    const val R_MEDIUM = 16
    const val R_LARGE = 24

    /** The widest content gets on a turned phone or a tablet (dp); it is centred beyond that. */
    const val MAX_CONTENT_WIDTH = 560

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun color(ctx: Context, res: Int) = Palette.color(ctx, res)

    fun accent(ctx: Context) = color(ctx, Looks.accent.primary)
    fun onAccent(ctx: Context) = color(ctx, Looks.accent.onPrimary)
    fun accentContainer(ctx: Context) = color(ctx, Looks.accent.container)
    fun onAccentContainer(ctx: Context) = color(ctx, Looks.accent.onContainer)
    fun text(ctx: Context) = color(ctx, R.color.text_primary)
    fun textSecondary(ctx: Context) = color(ctx, R.color.text_secondary)
    fun danger(ctx: Context) = color(ctx, R.color.danger)

    /** 3.0.4: Swift's own font (AppFont) when it's on, the phone's otherwise. */
    val MEDIUM: Typeface get() = AppFont.medium ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** 3.0.4: headings in bold, as Android 16's own apps have them (bolder headlines). */
    val BOLD: Typeface get() = AppFont.bold ?: Typeface.create("sans-serif", Typeface.BOLD)

    /** How heavy a text style is. */
    enum class Weight { REGULAR, MEDIUM, BOLD }

    /**
     * Text styles: size (sp), weight, and secondary (grey) colour or not. 3.0.4: headlines, titles
     * and section headings bolder and a little bigger, for a clearer order on every screen; and every
     * text in the app on this one scale (about 100 sizes had been set by hand, 10 to 34 sp): DISPLAY
     * for big numbers and big titles, HEADING for the parts of a page, SMALL for badges and the
     * smallest labels. The layout files use the same sizes (res/values/dimens.xml, text_*).
     */
    enum class Type(val sp: Float, val weight: Weight, val secondary: Boolean) {
        /**
         * 3.0.4: a screen's large title (Settings, History, Bookmarks, Downloads), as Android's own
         * apps open with (it moves up under the title bar as the page scrolls), and big numbers.
         */
        DISPLAY(30f, Weight.BOLD, false),
        HEADLINE(23f, Weight.BOLD, false),
        TITLE(19f, Weight.BOLD, false),
        HEADING(17f, Weight.BOLD, false),
        SUBTITLE(15.5f, Weight.MEDIUM, false),
        BODY(15f, Weight.REGULAR, false),
        BODY_SECONDARY(14f, Weight.REGULAR, true),
        LABEL(13.5f, Weight.MEDIUM, false),
        CAPTION(12.5f, Weight.REGULAR, true),
        SMALL(11f, Weight.MEDIUM, false),
        SECTION(14f, Weight.BOLD, false),
    }

    fun typeface(weight: Weight): Typeface = when (weight) {
        Weight.REGULAR -> AppFont.regular ?: Typeface.DEFAULT
        Weight.MEDIUM -> MEDIUM
        Weight.BOLD -> BOLD
    }

    /** 3.0.4: only the size and weight of [type], for text that keeps a colour (or a font) of its own. */
    fun size(tv: TextView, type: Type, keepFont: Boolean = false): TextView = tv.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
        if (!keepFont) typeface = typeface(type.weight)
        noHyphens(this)
    }

    /**
     * 3.0.4: no words split at the end of a line ("Proton VP-N"): Android 7 hyphenates labels by
     * default (Android 10 stopped), and a label reads better with the whole word on the next line.
     */
    fun noHyphens(tv: TextView) {
        if (android.os.Build.VERSION.SDK_INT >= 23) tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    fun style(tv: TextView, type: Type, color: Int? = null): TextView = tv.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
        typeface = typeface(type.weight)
        setTextColor(color ?: if (type == Type.SECTION) accent(context) else if (type.secondary) textSecondary(context) else text(context))
        if (type == Type.BODY || type == Type.BODY_SECONDARY) setLineSpacing(0f, 1.15f)
        noHyphens(this)
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

    /**
     * Swift's logo in the accent colour (2.3): the white comet (2.6) on a circle (2.8, round like the
     * app icon; a rounded square before) with a soft diagonal gradient. Blue (the default) is exactly
     * the app icon's blue, so nothing changes colour when Swift starts.
     */
    fun logo(ctx: Context): Drawable {
        val accent = Looks.accent
        val colors = if (accent == Accent.BLUE) {
            intArrayOf(0xFF5A97FF.toInt(), 0xFF1542C2.toInt())
        } else {
            // A very light colour of your own is deepened a little, so the white comet stays clear.
            var seed = accent.seedColor or 0xFF000000.toInt()
            var i = 0
            while (Color.luminance(seed) > 0.3f && i++ < 20) seed = Tones.mix(seed, Color.BLACK, 0.08)
            intArrayOf(Tones.mix(seed, Color.WHITE, 0.24), Tones.mix(seed, Color.BLACK, 0.22))
        }
        val size = dp(ctx, 48)
        val circle = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
            setSize(size, size)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(circle, ContextCompat.getDrawable(ctx, R.drawable.ic_logo_mark)!!))
    }

    fun circle(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /**
     * 3.0.1: a site's own colour, for where it has no icon of its own (its shortcut, the menu's
     * header, a Continue card without a picture): always the same one for the same site.
     */
    fun siteColor(key: String): Int {
        val palette = com.swiftbrowser.core.Profiles.COLORS
        return palette[(key.hashCode() and 0x7fffffff) % palette.size]
    }

    /**
     * 3.0.1: a site without an icon: its first letter, white, in a circle of its colour (as Chrome
     * does), the same size as the site icons beside it, so a row of shortcuts looks even.
     */
    fun monogram(ctx: Context, letter: String, key: String, sizeDp: Int): Drawable =
        Monogram(letter.take(1).uppercase(), siteColor(key), dp(ctx, sizeDp))

    private class Monogram(private val letter: String, fill: Int, private val size: Int) : Drawable() {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun draw(c: Canvas) {
            val b = bounds
            val r = minOf(b.width(), b.height()) / 2f
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, disc)
            text.textSize = r * 1.05f
            c.drawText(letter, b.exactCenterX(), b.exactCenterY() - (text.descent() + text.ascent()) / 2, text)
        }

        override fun getIntrinsicWidth() = size

        override fun getIntrinsicHeight() = size

        override fun setAlpha(alpha: Int) {
            disc.alpha = alpha
            text.alpha = alpha
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            disc.colorFilter = cf
            text.colorFilter = cf
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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

    /**
     * 3.0.4: a row that scrolls sideways fades out softly at an end where more of it is hidden, so it's
     * clear there's more (Android's own fading edge, which only shows where there's something to
     * scroll to). The row is drawn into a layer of its own, so the fading is worked out only when the
     * row itself changes, not on every frame of the page scrolling past it (a slow graphics chip).
     */
    fun fadeEdges(row: android.widget.HorizontalScrollView, lengthDp: Int = 28) {
        row.isHorizontalFadingEdgeEnabled = true
        row.setFadingEdgeLength(dp(row.context, lengthDp))
        row.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
    fun pressable(v: View, scale: Float = 0.96f, morph: Boolean = true) {
        // 3.0.4: and its shape changes a little as it is pressed (rounded corners squarer, a circle a
        // rounded square), springing back as it is let go: Android 16's buttons do that. Only its
        // background is redrawn, so it costs next to nothing.
        val down = AnimatorSet().apply {
            val parts = arrayListOf<Animator>(ObjectAnimator.ofFloat(v, View.SCALE_X, scale), ObjectAnimator.ofFloat(v, View.SCALE_Y, scale))
            if (morph) parts += ObjectAnimator.ofFloat(v, PRESS_SHAPE, 1f)
            playTogether(parts)
            duration = 90
            interpolator = Motion.standard
        }
        val up = AnimatorSet().apply {
            val parts = arrayListOf<Animator>(ObjectAnimator.ofFloat(v, View.SCALE_X, 1f), ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f))
            if (morph) parts += ObjectAnimator.ofFloat(v, PRESS_SHAPE, 0f)
            playTogether(parts)
            duration = 180
            interpolator = Motion.overshoot
        }
        v.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), up)
        }
    }

    /** How much rounder a pressed shape's corners get squarer (0.45: to about half). */
    private const val PRESS_SQUARER = 0.45f

    /** 0 as drawn, 1 pressed: the shape of [View]'s background follows it (see [morph]). */
    private val PRESS_SHAPE = object : android.util.Property<View, Float>(Float::class.java, "pressShape") {
        override fun get(v: View): Float = (v.getTag(R.id.press_shape) as? Float) ?: 0f
        override fun set(v: View, value: Float) {
            // Not pressed yet (the "let go" state also runs as the view is first shown): nothing to put back.
            if (value == 0f && v.getTag(R.id.press_radius) == null) return
            v.setTag(R.id.press_shape, value)
            morph(v, value)
        }
    }

    /** What [morph] keeps: the shape changed and its corners as drawn. */
    private class PressRadius(val target: Any, val radius: Float, val radii: FloatArray?)

    /**
     * 3.0.4: the corners of [v]'s background [f] of the way to pressed (squarer); 0 puts them back.
     * A chip or a Material button by its own corner radius; anything else by the first rounded shape
     * (GradientDrawable) in its background, a ripple's or an inset's included. A circle becomes a
     * rounded square of the same size, which looks the same until pressed.
     */
    private fun morph(v: View, f: Float) {
        when (v) {
            is com.google.android.material.chip.Chip -> {
                val base = baseRadius(v, v) { PressRadius(v, v.chipCornerRadius, null) } ?: return
                v.chipCornerRadius = base.radius * (1f - PRESS_SQUARER * f)
                return
            }
            is com.google.android.material.button.MaterialButton -> {
                val base = baseRadius(v, v) { PressRadius(v, v.cornerRadius.toFloat(), null) } ?: return
                v.cornerRadius = (base.radius * (1f - PRESS_SQUARER * f)).toInt()
                return
            }
        }
        val shape = roundedShape(v.background) ?: return
        val base = baseRadius(v, shape) {
            // Its own copy first: a shape from the app's drawables is shared by every view showing it.
            v.background?.mutate()
            val own = roundedShape(v.background) ?: return@baseRadius null
            if (own.shape == GradientDrawable.OVAL) {
                val b = own.bounds
                if (b.width() <= 0 || kotlin.math.abs(b.width() - b.height()) > 2) return@baseRadius null
                own.shape = GradientDrawable.RECTANGLE
                own.cornerRadius = b.height() / 2f
            }
            // Android 7 throws reading corners that were never set one by one.
            PressRadius(own, own.cornerRadius, runCatching { own.cornerRadii?.copyOf() }.getOrNull())
        } ?: return
        val g = base.target as GradientDrawable
        val k = 1f - PRESS_SQUARER * f
        val radii = base.radii
        if (radii != null) g.cornerRadii = FloatArray(radii.size) { radii[it] * k } else g.cornerRadius = base.radius * k
    }

    /** The corners as drawn, worked out on the first press ([make]) and again when the background changed. */
    private inline fun baseRadius(v: View, current: Any, make: () -> PressRadius?): PressRadius? {
        val kept = v.getTag(R.id.press_radius) as? PressRadius
        if (kept != null && (kept.target === current || (current is GradientDrawable && roundedShape(v.background) === kept.target))) return kept
        val made = make() ?: return null
        if (made.radius <= 0f && made.radii == null) return null
        v.setTag(R.id.press_radius, made)
        return made
    }

    /** The first rounded shape in [d]: itself, a ripple's or a layer's content, an inset's or a state's. */
    private fun roundedShape(d: Drawable?): GradientDrawable? = when (d) {
        null -> null
        is GradientDrawable -> d
        is android.graphics.drawable.LayerDrawable -> (0 until d.numberOfLayers).asSequence()
            .filter { d.getId(it) != android.R.id.mask }
            .mapNotNull { roundedShape(d.getDrawable(it)) }.firstOrNull()
        is android.graphics.drawable.InsetDrawable -> roundedShape(d.drawable)
        is android.graphics.drawable.StateListDrawable -> roundedShape(d.current)
        else -> null
    }

    /**
     * Gives [target] a finger-sized place, at least [sizeDp] square, without changing how it looks
     * (3.0): taps just beside it, inside [host] (an ancestor with no other TouchDelegate), go to it.
     * A button in a corner grows inward.
     */
    fun touchArea(target: View, host: ViewGroup, sizeDp: Int = 48) {
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (target.width <= 0 || target.visibility != View.VISIBLE) return@addOnLayoutChangeListener
            val r = android.graphics.Rect(0, 0, target.width, target.height)
            host.offsetDescendantRectToMyCoords(target, r)
            val min = dp(host.context, sizeDp)
            r.inset(-((min - r.width()).coerceAtLeast(0) + 1) / 2, -((min - r.height()).coerceAtLeast(0) + 1) / 2)
            if (r.left < 0) r.offset(-r.left, 0)
            if (r.right > host.width) r.offset(host.width - r.right, 0)
            if (r.top < 0) r.offset(0, -r.top)
            if (r.bottom > host.height) r.offset(0, host.height - r.bottom)
            host.touchDelegate = android.view.TouchDelegate(r, target)
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

    /** 3.0.4: whether Android's animations are on (Developer options can turn them off). */
    fun enabled(ctx: Context): Boolean =
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** 3.0.4: a panel's spring: quick, settling without bouncing. */
    const val SPRING_PANEL = 700f

    /**
     * 3.0.4 (spring motion): moves [property] of [v] to [to] with a spring, as Android 16 moves
     * things: it settles naturally, and asked again on the way it carries on from where it is, at
     * the speed it has (a panel closed while it opens turns back smoothly). [bounce]: a touch past
     * the end and back. [end] runs once it has settled at [to], not when a new target took over.
     * With Android's animations off it is there at once.
     */
    fun spring(
        v: View,
        property: androidx.dynamicanimation.animation.DynamicAnimation.ViewProperty,
        to: Float,
        stiffness: Float = SPRING_PANEL,
        bounce: Boolean = false,
        velocity: Float? = null,
        end: (() -> Unit)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        val springs = v.getTag(R.id.motion_springs) as? HashMap<Any, Spring>
            ?: HashMap<Any, Spring>().also { v.setTag(R.id.motion_springs, it) }
        val s = springs.getOrPut(property) { Spring(v, property) }
        s.end = end
        if (!enabled(v.context)) {
            s.animation.cancel()
            property.setValue(v, to)
            s.end = null
            end?.invoke()
            return
        }
        s.animation.spring.setStiffness(stiffness).dampingRatio = if (bounce) 0.72f else 1f
        if (velocity != null) s.animation.setStartVelocity(velocity)
        s.animation.animateToFinalPosition(to)
    }

    /** Stops [v]'s springs where they are (their ends don't run). */
    fun stopSprings(v: View) {
        @Suppress("UNCHECKED_CAST")
        (v.getTag(R.id.motion_springs) as? HashMap<Any, Spring>)?.values?.forEach {
            it.end = null
            it.animation.cancel()
        }
    }

    /** One property's spring on a view, and what runs when it settles. */
    class Spring(v: View, property: androidx.dynamicanimation.animation.DynamicAnimation.ViewProperty) {
        var end: (() -> Unit)? = null
        val animation = androidx.dynamicanimation.animation.SpringAnimation(v, property).apply {
            spring = androidx.dynamicanimation.animation.SpringForce()
            addEndListener { _, canceled, _, _ ->
                val f = end
                end = null
                if (!canceled) f?.invoke()
            }
        }
    }

    /** Fades [v] in, rising from [fromDy] dp below where it sits. */
    fun fadeIn(v: View, fromDy: Int = 8, delay: Long = 0, duration: Long = NORMAL) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = Ui.dpf(v.context, fromDy.toFloat())
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(enter).layerIfFits(v).start()
    }

    /**
     * Draws [v] through a hardware layer while it animates, unless it is bigger than the screen:
     * the GPU can't hold a picture that tall (a long list of results crashed drawing, 2.3).
     */
    private fun android.view.ViewPropertyAnimator.layerIfFits(v: View): android.view.ViewPropertyAnimator {
        val dm = v.resources.displayMetrics
        val tooBig = v.isLaidOut && (v.height > dm.heightPixels || v.width > dm.widthPixels)
        return if (tooBig) this else withLayer()
    }

    /** Fades [v] out (sinking [toDy] dp), then hides it and runs [then]. */
    fun fadeOut(v: View, toDy: Int = 8, duration: Long = SHORT, gone: Boolean = true, then: (() -> Unit)? = null) {
        v.animate().cancel()
        v.animate().alpha(0f).translationY(Ui.dpf(v.context, toDy.toFloat())).setStartDelay(0).setDuration(duration)
            .setInterpolator(exit).layerIfFits(v).withEndAction {
                v.visibility = if (gone) View.GONE else View.INVISIBLE
                v.alpha = 1f
                v.translationY = 0f
                then?.invoke()
            }.start()
    }

    /**
     * Shows or hides a small button with a quick pop (address bar icons, 2.3). Safe to call again
     * and again with the same answer: only a change animates.
     */
    fun pop(v: View, show: Boolean) {
        val hiding = v.getTag(R.id.motion_hiding) == true
        if (show) {
            if (v.visibility == View.VISIBLE && !hiding) return
            v.animate().cancel()
            v.setTag(R.id.motion_hiding, null)
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.scaleX = 0.6f
                v.scaleY = 0.6f
            }
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(SHORT).setInterpolator(enter).start()
        } else {
            if (v.visibility != View.VISIBLE || hiding) return
            if (!v.isShown) {
                v.visibility = View.GONE
                return
            }
            v.setTag(R.id.motion_hiding, true)
            v.animate().cancel()
            v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setStartDelay(0).setDuration(110).setInterpolator(exit).withEndAction {
                v.setTag(R.id.motion_hiding, null)
                v.visibility = View.GONE
                v.alpha = 1f
                v.scaleX = 1f
                v.scaleY = 1f
            }.start()
        }
    }

    /** Changes [v]'s icon with a quick turn and settle (reload ↔ stop, 2.3); the same icon again does nothing. */
    fun swapIcon(v: android.widget.ImageView, res: Int) {
        val shown = v.getTag(R.id.motion_icon) as? Int
        if (shown == res) return
        v.setTag(R.id.motion_icon, res)
        if (shown == null || !v.isShown) {
            v.setImageResource(res)
            return
        }
        v.animate().cancel()
        v.animate().rotation(-45f).scaleX(0.7f).scaleY(0.7f).alpha(0.3f).setStartDelay(0).setDuration(80).setInterpolator(exit).withEndAction {
            v.setImageResource(res)
            v.rotation = 45f
            v.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(SHORT).setInterpolator(enter).start()
        }.start()
    }

    /** Changes [tv]'s text with a quick cross-fade (status lines, 2.3); the same text does nothing. */
    fun swapText(tv: TextView, text: CharSequence) {
        val coming = tv.getTag(R.id.motion_text) as? CharSequence ?: tv.text
        if (coming.toString() == text.toString()) return
        if (!tv.isShown || tv.text.isNullOrEmpty()) {
            tv.animate().cancel()
            tv.setTag(R.id.motion_text, null)
            tv.alpha = 1f
            tv.text = text
            return
        }
        tv.setTag(R.id.motion_text, text)
        tv.animate().cancel()
        tv.animate().alpha(0f).setStartDelay(0).setDuration(90).setInterpolator(exit).withEndAction {
            tv.setTag(R.id.motion_text, null)
            tv.text = text
            tv.animate().alpha(1f).setDuration(SHORT).setInterpolator(enter).start()
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

    /** Opens [v] (hidden before) by growing its height and fading it in: a list folding open (2.3). */
    fun expand(v: View) {
        val parent = v.parent as? View
        val margins = (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0
        val width = (parent?.width ?: 0) - (parent?.paddingLeft ?: 0) - (parent?.paddingRight ?: 0) - margins
        if (width <= 0 || !v.isAttachedToWindow) {
            v.visibility = View.VISIBLE
            return
        }
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val target = v.measuredHeight
        val lp = v.layoutParams
        lp.height = 1
        v.layoutParams = lp
        v.alpha = 0f
        v.visibility = View.VISIBLE
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NORMAL
            interpolator = standard
            addUpdateListener {
                val f = it.animatedValue as Float
                v.alpha = f
                lp.height = maxOf(1, (target * f).toInt())
                v.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    v.layoutParams = lp
                    v.alpha = 1f
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

    /** Pulses only while it can be seen (2.6): hidden, or with Swift in the background, it costs nothing. */
    private fun syncPulse() {
        val on = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (on && pulse == null) {
            pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.45f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!on && pulse != null) {
            pulse?.cancel()
            pulse = null
            alpha = 1f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulse()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncPulse()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        pulse = null
        super.onDetachedFromWindow()
    }
}
