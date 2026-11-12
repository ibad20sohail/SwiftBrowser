package com.swiftbrowser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import android.view.ViewPropertyAnimator
import android.view.ViewTreeObserver
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
     * The hand-picked accent closest in hue: the theme of Android's own parts, which can't take any
     * colour while the app runs (3.0.5: radios, check boxes, text cursors and handles and lists' glow
     * are given the exact accent one by one: ExactAccent). 3.0: a grey, white or black has no hue to
     * go by (it came out red, with a red cursor and text handles): blue, the default, then.
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
        val dark = wantsDark()
        if (dark == SwiftApp.prefs.darkMode) return
        // 3.0.10: a picture of the screen first, which fades into it made again in the new colours.
        ThemeFade.takeThen { switchTo(dark) }
    }

    private fun switchTo(dark: Boolean) {
        val prefs = SwiftApp.prefs
        if (dark == prefs.darkMode) return
        prefs.darkMode = dark
        changedAt = android.os.SystemClock.uptimeMillis()
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

    /** When light, dark or a colour last changed: the screens made because of it come in softly. */
    private var changedAt = 0L

    /** How soon after a change a screen counts as made because of it (ms). */
    private const val CHANGE_WINDOW_MS = 2500L

    /**
     * Android rebuilds every screen when the look changes and shows no transition for it. Called as
     * each screen starts (SwiftApp): the new one comes in from under a flat cover of its own page
     * colour. One colour and no picture of the old screen (that would be about 3.7 MB on a 720p phone,
     * with the web page missing from it); it also hides the moment a page changes its colours.
     */
    fun fadeInAfterChange(activity: Activity) {
        if (changedAt == 0L || android.os.SystemClock.uptimeMillis() - changedAt > CHANGE_WINDOW_MS || !Motion.enabled(activity)) return
        // 3.0.10: the screen as it was, fading into its new colours (ThemeFade); the flat cover is for
        // screens that weren't on show.
        ThemeFade.claim(activity)?.let { picture ->
            ThemeFade.fadeOver(activity, picture)
            return
        }
        val decor = activity.window.decorView
        val dm = activity.resources.displayMetrics
        val side = 2 * maxOf(dm.widthPixels, dm.heightPixels)
        val cover = android.graphics.drawable.ColorDrawable(Palette.color(activity, R.color.page_bg)).apply { setBounds(0, 0, side, side) }
        decor.overlay.add(cover)
        val observer = decor.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (observer.isAlive) observer else decor.viewTreeObserver).removeOnPreDrawListener(this)
                // From the first frame, so the time making the screen doesn't use up the fade.
                ValueAnimator.ofInt(255, 0).apply {
                    duration = Motion.DIALOG
                    interpolator = Motion.standard
                    addUpdateListener { cover.alpha = it.animatedValue as Int }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = decor.overlay.remove(cover)
                    })
                    start()
                }
                return true
            }
        })
    }

    private fun colorsChanged(activity: Activity) {
        // 3.0.10: new colours cross-fade too (ThemeFade), from a picture of the screen as it was.
        ThemeFade.takeThen {
            changedAt = android.os.SystemClock.uptimeMillis()
            Palette.invalidate()
            // Reader view's links follow too.
            com.swiftbrowser.media.Helper.sendReaderStyle()
            // 3.0.4: and the home screen widget.
            SearchWidget.refresh(activity)
            if (!activity.isFinishing) activity.recreate()
        }
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

    /**
     * 3.0.5: how much something unavailable (a button with nothing to do, a day that can't be
     * chosen) is dimmed: one value everywhere (it was 0.3 to 0.5 from place to place), the one
     * Material gives disabled content. Not for what is merely off (shuffle, an equalizer that is off).
     */
    const val DISABLED_ALPHA = 0.38f

    // 3.0.5: one look on every screen, in one place (res/values/dimens.xml has the same numbers for
    // the layout files): one side margin, one icon holder, one title bar, one search box, one rhythm
    // for rows and sections. Pages of rows and cards have the settings' colour behind them
    // (R.color.settings_bg), with the cards in R.color.card_bg (ListActivity, Settings, tab grid).

    /** How far everything full-width keeps from the screen's sides (dp): rows, cards, search boxes. */
    const val MARGIN = 16

    /** Touch ripple on a dark surface (the player, a dark bar, a picture), also in the light look. */
    const val RIPPLE_ON_DARK = 0x40FFFFFF

    /** How far in from a card's or grouped row's edge its first icon or text starts (dp). */
    const val ROW_INSET = 12

    /**
     * [MARGIN] + [ROW_INSET] from the screen's edge (dp): where a page's section headings and large
     * title start, lined up with the first icon or text inside the rows and cards under them.
     */
    const val CONTENT_X = MARGIN + ROW_INSET

    /** The holder of a row's or tile's icon (dp) (see [iconBadge]). */
    const val ICON_BOX = 40

    /** How big an icon is drawn in that holder (dp): one size, whatever the screen. */
    const val ICON_IN_BOX = 22

    /** Title bars (Settings, the lists, Files) and search boxes (dp). */
    const val BAR_HEIGHT = 52
    const val SEARCH_HEIGHT = 44

    /** Rows at least this tall (dp): one line of text, or two (a row whose text needs more grows). */
    const val ROW_ONE_LINE = 56
    const val ROW_TWO_LINES = 64

    /** 24 dp above a section heading and 8 dp between it and its rows; groups without a heading are 16 dp apart (dp). */
    const val SECTION_ABOVE = 24
    const val SECTION_BELOW = 8
    const val GROUP_GAP = 16

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

    /** 3.0.4: Swift's own font (AppFont), or the phone's if it couldn't be read. */
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
     * smallest labels. The layout files use the same sizes (res/values/dimens.xml, text_*). 3.0.5:
     * each size draws with the cut of the font made for it ([typeface], see AppFont).
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

    /**
     * 3.0.5: [type]'s weight in the cut of Swift's font made for its size (small text more open, big
     * titles tighter, see AppFont), or in the phone's own font.
     */
    fun typeface(type: Type): Typeface = AppFont.cut(type.sp, type.weight) ?: phoneFont(type.weight)

    /** 3.0.5: the medium weight at [type]'s size, for text in a Type's size that wants medium by hand. */
    fun medium(type: Type): Typeface = AppFont.cut(type.sp, Weight.MEDIUM) ?: MEDIUM

    private fun phoneFont(weight: Weight): Typeface = when (weight) {
        Weight.REGULAR -> Typeface.DEFAULT
        Weight.MEDIUM -> MEDIUM
        Weight.BOLD -> BOLD
    }

    /** 3.0.4: only the size and weight of [type], for text that keeps a colour (or a font) of its own. */
    fun size(tv: TextView, type: Type, keepFont: Boolean = false): TextView = tv.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
        if (!keepFont) {
            typeface = typeface(type)
        } else if (typeface == null || typeface === Typeface.DEFAULT) {
            // 3.0.5: no font of its own to keep (yet): small text still gets the cut for its size.
            AppFont.plainCut(type.sp)?.let { typeface = it }
        }
        noHyphens(this)
    }

    /**
     * 3.0.5: figures all of a width (Google Sans Flex's own are not: a "1" is much narrower than a
     * "0"), for numbers that change while they are watched, so they don't wobble as they count. Only
     * on those (a time, a percentage), not on words: letters are unchanged, but figures read better
     * in their own widths in a sentence. Harmless on the phone's font, whose figures don't vary.
     */
    fun steadyFigures(tv: TextView): TextView = tv.apply { fontFeatureSettings = "tnum" }

    /**
     * 3.0.4: no words split at the end of a line ("Proton VP-N"): Android 7 hyphenates labels by
     * default (Android 10 stopped), and a label reads better with the whole word on the next line.
     */
    fun noHyphens(tv: TextView) {
        if (android.os.Build.VERSION.SDK_INT >= 23) tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
    }

    fun style(tv: TextView, type: Type, color: Int? = null): TextView = tv.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
        typeface = typeface(type)
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
    fun ripple(ctx: Context, content: Drawable?, radiusDp: Int, color: Int? = null): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color ?: highlight(ctx)), content, shape(ctx, 0xFFFFFFFF.toInt(), radiusDp))

    /** Touch ripple for a round icon button (spreads in a circle without a box). */
    fun rippleBorderless(ctx: Context, color: Int? = null): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color ?: highlight(ctx)), null, null)

    fun icon(ctx: Context, res: Int, tint: Int): Drawable? =
        ContextCompat.getDrawable(ctx, res)?.mutate()?.also { it.setTint(tint) }

    /**
     * An icon in a soft circle: [ICON_BOX] in rows and tiles, bigger on empty screens and the welcome.
     */
    fun iconBadge(ctx: Context, res: Int, sizeDp: Int = ICON_BOX, tint: Int = onAccentContainer(ctx), fill: Int = accentContainer(ctx)): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(icon(ctx, res, tint))
            background = circle(fill)
            val size = dp(ctx, sizeDp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Big badges (empty screens, the welcome) get a big icon; a row's the one size of every row.
            val p = if (sizeDp >= 56) (size * 0.28f).toInt() else (size - dp(ctx, ICON_IN_BOX)) / 2
            setPadding(p, p, p, p)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    /**
     * 3.0.4: a row that scrolls sideways fades out softly at an end where more of it is hidden, so it's
     * clear there's more (Android's own fading edge, which only shows where there's something to
     * scroll to). The row is drawn into a layer of its own, so the fading is worked out only when the
     * row itself changes, not on every frame of the page scrolling past it (a slow graphics chip).
     * 3.0.5: the fade stops at the row's own padding, so side padding goes on the row's child (a row
     * with padding and clipToPadding off showed a sliver of the next chip past the fade).
     */
    fun fadeEdges(row: android.widget.HorizontalScrollView, lengthDp: Int = 28) {
        row.isHorizontalFadingEdgeEnabled = true
        row.setFadingEdgeLength(dp(row.context, lengthDp))
        row.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 3.0.5: one line of text too long for its box fades out softly at its end, instead of being cut
     * off mid-letter (the address bar), over whatever is behind it: Android's own fading edge for
     * text, which only works on the text's own pixels, so a website's colour behind it stays as it is.
     * Only while the text doesn't fit: most addresses do, and then nothing extra is drawn at all.
     * While it fades, the text is drawn into a layer of its own, as [fadeEdges] does, so the fading is
     * worked out when the text changes and not on every frame of the page moving under it.
     * [on] false (while typing): none of it. Asked again and again costs nothing while nothing changes.
     */
    fun fadeTextEnd(tv: TextView, on: Boolean, lengthDp: Int = 24) {
        val fade = on && textOverflows(tv)
        if (fade == tv.isHorizontalFadingEdgeEnabled) return
        tv.isHorizontalFadingEdgeEnabled = fade
        if (fade) tv.setFadingEdgeLength(dp(tv.context, lengthDp))
        tv.setLayerType(if (fade) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
    }

    /** 3.0.5: whether [tv]'s one line, running left to right (right-to-left text ends at the other side), is wider than its box. */
    private fun textOverflows(tv: TextView): Boolean {
        val layout = tv.layout ?: return false
        val room = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        return room > 0 && tv.text.isNotEmpty() && layout.getParagraphDirection(0) == android.text.Layout.DIR_LEFT_TO_RIGHT && layout.getLineWidth(0) > room
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
    fun pressable(v: View, scale: Float = Motion.PRESS_BUTTON, morph: Boolean = true) {
        // 3.0.4: and its shape changes a little as it is pressed (rounded corners squarer, a circle a
        // rounded square), springing back as it is let go: Android 16's buttons do that. Only its
        // background is redrawn, so it costs next to nothing.
        val down = AnimatorSet().apply {
            val parts = arrayListOf<Animator>(ObjectAnimator.ofFloat(v, View.SCALE_X, scale), ObjectAnimator.ofFloat(v, View.SCALE_Y, scale))
            if (morph) parts += ObjectAnimator.ofFloat(v, PRESS_SHAPE, 1f)
            playTogether(parts)
            duration = Motion.PRESS_DOWN
            interpolator = Motion.standard
        }
        val up = AnimatorSet().apply {
            val parts = arrayListOf<Animator>(ObjectAnimator.ofFloat(v, View.SCALE_X, 1f), ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f))
            if (morph) parts += ObjectAnimator.ofFloat(v, PRESS_SHAPE, 0f)
            playTogether(parts)
            duration = Motion.PRESS_UP
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

    /**
     * 3.0.5: one search box everywhere (the home page's big one and the address bar are their own
     * things): [SEARCH_HEIGHT] tall (its caller's layout says so), body text, the search icon and
     * grey hint, and the fill the rows and cards around it have: [fill] (the card colour unless the
     * screen says otherwise; incognito's tab search keeps its own colours through [ink] and [soft]).
     */
    fun searchBox(box: android.widget.EditText, fill: Int = color(box.context, R.color.card_bg), ink: Int = text(box.context), soft: Int = textSecondary(box.context)) {
        val ctx = box.context
        size(box, Type.BODY, keepFont = true)
        box.setTextColor(ink)
        box.setHintTextColor(soft)
        // Set on the view: a field whose default look was already painted keeps a tint of its own (the
        // accent-coloured underline, see Palette), which would colour this shape instead.
        box.background = ContextCompat.getDrawable(ctx, R.drawable.bg_url)?.mutate()
        box.backgroundTintList = android.content.res.ColorStateList.valueOf(fill)
        box.setPaddingRelative(dp(ctx, 14), 0, dp(ctx, 14), 0)
        box.compoundDrawablePadding = dp(ctx, 10)
        val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_search)?.mutate()?.also {
            it.setTint(soft)
            it.setBounds(0, 0, dp(ctx, 20), dp(ctx, 20))
        }
        box.setCompoundDrawablesRelative(icon, null, null, null)
    }

    /**
     * A centred "nothing here" screen: an icon in a soft circle (3.0.5: or, with [art], a small
     * drawing of its own), a title and a hint.
     */
    fun emptyState(ctx: Context, iconRes: Int, title: String, hint: String?, art: EmptyArt? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(ctx, 40), dp(ctx, 48), dp(ctx, 40), dp(ctx, 48))
        addView(art?.view(ctx) ?: iconBadge(ctx, iconRes, 64, tint = accent(ctx)))
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
    fun noteBox(ctx: Context, iconRes: Int, message: CharSequence, fill: Int = accentContainer(ctx), ink: Int = onAccentContainer(ctx)): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape(ctx, fill, R_MEDIUM)
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        addView(ImageView(ctx).apply {
            setImageDrawable(icon(ctx, iconRes, ink))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 12) })
        addView(text(ctx, Type.BODY_SECONDARY, message, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /**
     * A text field in a dialog or a sheet, filled like Swift's search boxes instead of underlined:
     * 48 dp tall at least, [R_SMALL] corners. Its box starts where the dialog's title does (24 dp in).
     */
    fun field(box: android.widget.EditText, fill: Int = color(box.context, R.color.surface_alt)) {
        val ctx = box.context
        size(box, Type.BODY, keepFont = true)
        box.background = shape(ctx, fill, R_SMALL)
        // On the view too: a field whose default look was already painted keeps a tint of its own.
        box.backgroundTintList = ColorStateList.valueOf(fill)
        box.minHeight = dp(ctx, 48)
        box.setPaddingRelative(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
    }
}

/** How things move: one set of durations, curves and press depths for every screen. */
object Motion {
    const val SHORT = 150L
    const val NORMAL = 220L
    const val LONG = 300L

    // By what is moving. The window animations in res/anim and the buttons' res/animator say the same.

    /** A press going down and coming back. */
    const val PRESS_DOWN = 100L
    const val PRESS_UP = 150L

    /** An icon changing. */
    const val ICON = 150L

    /** A small part of a screen changing (a list, a bar, a fade). */
    const val SMALL = 180L

    /** A dialog opening, a sheet opening and closing. */
    const val DIALOG = 200L
    const val SHEET = 250L
    const val SHEET_OUT = 200L

    /** A section opening or closing. */
    const val EXPAND = 220L

    /** How far a press shrinks what is pressed: an icon or small round thing, a button, a whole card. */
    const val PRESS_ICON = 0.90f
    const val PRESS_BUTTON = 0.95f
    const val PRESS_CARD = 0.98f

    /**
     * [v].animate() with its time, curve and delay all said: what a call leaves out stays from the
     * last animation on that view (a delay left over made a shortcut's lift start late).
     */
    fun anim(v: View, ms: Long, curve: TimeInterpolator = standard, delay: Long = 0L): ViewPropertyAnimator =
        v.animate().setDuration(ms).setInterpolator(curve).setStartDelay(delay)

    /** For a list whose rows come and go: only they and the rows closing up move; a row changing stays put. */
    fun listAnimator() = androidx.recyclerview.widget.DefaultItemAnimator().apply {
        removeDuration = SHORT
        moveDuration = SHORT
        addDuration = SHORT
        changeDuration = 0
        supportsChangeAnimations = false
    }

    /** A bar's value going to [value] smoothly (Android's own short slide; a value set often stays cheap). */
    fun progress(bar: android.widget.ProgressBar, value: Int) = bar.setProgress(value, true)

    /** A running [fadePicture], and how solid the picture is meant to be (a dimmed one, SoftPictures, stays dimmed). */
    private class PictureFade(val animator: ValueAnimator, val rest: Int)

    /** A picture that arrived after its row was shown fades in, up to the strength it has now: only the picture, with no layer. */
    fun fadePicture(v: ImageView) {
        stopPicture(v)
        if (!enabled(v.context)) return
        val rest = v.imageAlpha
        v.imageAlpha = 0
        v.setTag(R.id.motion_picture, PictureFade(ValueAnimator.ofInt(0, rest).apply {
            duration = ICON
            interpolator = standard
            addUpdateListener { v.imageAlpha = it.animatedValue as Int }
            start()
        }, rest))
    }

    /** [v] is showing something else now (a row used again): whatever was fading in stops, at the strength it was meant to end at. */
    fun stopPicture(v: ImageView) {
        val fade = v.getTag(R.id.motion_picture) as? PictureFade ?: return
        fade.animator.cancel()
        v.setTag(R.id.motion_picture, null)
        v.imageAlpha = fade.rest
    }

    /** Starts quickly and settles gently: things moving on screen. */
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Things coming in. */
    val enter = PathInterpolator(0f, 0f, 0f, 1f)
    /** Things going away. */
    val exit = PathInterpolator(0.3f, 0f, 1f, 1f)
    /** A small spring past the end and back (letting go of a pressed button, a count changing). */
    val overshoot = android.view.animation.OvershootInterpolator(2f)

    // 3.0.5: one motion for going forward and back, wherever it happens: new screens (their window
    // animations are res/anim's swift_open_enter, swift_close_exit and swift_hold, which say the same:
    // 8% of a phone's width is this distance), Settings' topics, Files' folders and network folders.
    // (Settings slid 56 dp in 220 ms, folders 28 dp in 220 ms and 24 dp in 150 ms, new screens 8% in 160 ms.)

    /** 3.0.5: how far new content comes from (dp). */
    const val PAGE_SLIDE_DP = 28

    /** 3.0.5: how long it takes. */
    const val PAGE_MS = 180L

    /** 3.0.5: how long what it replaces takes to fade away, sooner, so the two don't show through each other for long. */
    const val PAGE_OUT_MS = 100L

    /** 3.0.5: the side new content comes from: going forward from the right, going back from the left. */
    const val FORWARD = 1
    const val BACK = -1

    /** 3.0.5: how it eases (the window animations' @android:anim/decelerate_interpolator). */
    val page = android.view.animation.DecelerateInterpolator()

    /**
     * 3.0.5: [v], the new content, comes in with a fade from the side it is reached from: [FORWARD]
     * from the right, [BACK] from the left, the mirror of going forward. Drawn through one layer
     * while it moves (cheap on a slow graphics chip); with Android's animations off it is just there.
     */
    fun pageIn(v: View, direction: Int) {
        v.animate().cancel()
        v.translationY = 0f
        if (!enabled(v.context)) {
            v.alpha = 1f
            v.translationX = 0f
            return
        }
        v.alpha = 0f
        v.translationX = Ui.dpf(v.context, PAGE_SLIDE_DP.toFloat()) * direction
        v.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(PAGE_MS).setInterpolator(page).layerIfFits(v).start()
    }

    /** 3.0.5: what the new content replaces fades away where it is (it holds still, as the screen under a new one does); then [then], which takes it away. */
    fun pageOut(v: View, then: () -> Unit) {
        v.animate().cancel()
        if (!enabled(v.context)) {
            then()
            return
        }
        v.animate().alpha(0f).setStartDelay(0).setDuration(PAGE_OUT_MS).setInterpolator(exit).layerIfFits(v).withEndAction { then() }.start()
    }

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

    /** Changes [v]'s icon with a quick turn and settle (reload ↔ stop, 2.3); the same icon again does nothing. [restAlpha]: how solid it ends (shuffle when off is dimmer). */
    fun swapIcon(v: android.widget.ImageView, res: Int, restAlpha: Float = 1f) {
        val shown = v.getTag(R.id.motion_icon) as? Int
        if (shown == res) return
        v.setTag(R.id.motion_icon, res)
        if (shown == null || !v.isShown) {
            v.setImageResource(res)
            v.alpha = restAlpha
            return
        }
        v.animate().cancel()
        v.animate().rotation(-45f).scaleX(0.7f).scaleY(0.7f).alpha(0.3f).setStartDelay(0).setDuration(80).setInterpolator(exit).withEndAction {
            v.setImageResource(res)
            v.rotation = 45f
            v.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(restAlpha).setDuration(ICON).setInterpolator(enter).start()
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
    fun bump(v: View, scale: Float = 1.12f) {
        v.animate().cancel()
        anim(v, 70).scaleX(scale).scaleY(scale).withEndAction {
            anim(v, 110).scaleX(1f).scaleY(1f).start()
        }.start()
    }

    /**
     * Folds [v] away (fading and closing its height), then runs [then]. Lays the screen out on every
     * frame: for something small, once in a while (the ≡ menu's hint). A section the user opens and
     * closes uses [reveal].
     */
    fun collapse(v: View, then: () -> Unit) {
        val start = v.height
        if (start <= 0 || !v.isAttachedToWindow) {
            then()
            return
        }
        val lp = v.layoutParams
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = EXPAND
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

    /**
     * Opens or closes [v], a section in a column, as if its height changed, with no layout while it
     * moves: it is laid out once at full size and shown through a growing clip, and what is under it
     * moves by translation (in a sheet, the sheet's top edge too). Asked again on the way, it turns
     * back from where it is. [then] runs when it has got there; closed, it is GONE.
     */
    fun reveal(v: View, open: Boolean, then: (() -> Unit)? = null) {
        val r = v.getTag(R.id.motion_reveal) as? Reveal ?: Reveal(v).also { v.setTag(R.id.motion_reveal, it) }
        r.go(open, then)
    }

    private class Reveal(private val v: View) {
        private var animator: ValueAnimator? = null
        private var opening = false
        private var then: (() -> Unit)? = null
        /** 0 shut, 1 open. */
        private var f = 0f
        private var full = 0
        private var sheet: View? = null
        private var sheetShift = 0
        private var scroll: ViewGroup? = null
        /** In the same scrolling column, under it: they follow its edge. */
        private val below = ArrayList<View>()
        /** After the scrolling part, in the sheet (a button pinned at the bottom): they stay where they are on screen. */
        private val pinned = ArrayList<View>()
        private val clip = Rect()

        fun go(open: Boolean, done: (() -> Unit)?) {
            if (animator != null) {
                then = done
                if (open != opening) {
                    opening = open
                    run(if (open) 1f else 0f)
                }
                return
            }
            if (open == (v.visibility == View.VISIBLE)) {
                done?.invoke()
                return
            }
            then = done
            opening = open
            if (!enabled(v.context) || !v.isAttachedToWindow || (!open && v.height <= 0)) {
                v.visibility = if (open) View.VISIBLE else View.GONE
                finish()
                return
            }
            sheet = sheetOf(v)
            if (open) open() else close()
        }

        private fun open() {
            val top = sheet?.top ?: 0
            clip.set(0, 0, 0, 0)
            v.clipBounds = clip
            v.visibility = View.VISIBLE
            // After its one layout: how tall it is and how far the sheet's edge has gone are known then.
            val observer = v.viewTreeObserver
            observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    (if (observer.isAlive) observer else v.viewTreeObserver).removeOnPreDrawListener(this)
                    full = v.height
                    sheetShift = top - (sheet?.top ?: top)
                    findWhatMoves()
                    f = 0f
                    show()
                    run(1f)
                    return true
                }
            })
        }

        private fun close() {
            full = v.height
            findWhatMoves()
            // How much shorter the sheet will be: as much as the list under its top stops filling it.
            val content = scroll?.getChildAt(0)?.height
            sheetShift = when {
                sheet == null -> 0
                scroll == null || content == null -> full
                else -> (scroll!!.height - (content - full)).coerceIn(0, full)
            }
            f = 1f
            run(0f)
        }

        private fun sheetOf(view: View): View? {
            var p = view.parent as? View
            while (p != null) {
                if (p.id == com.google.android.material.R.id.design_bottom_sheet) return p
                p = p.parent as? View
            }
            return null
        }

        private fun findWhatMoves() {
            below.clear()
            pinned.clear()
            scroll = null
            var child: View = v
            var parent = v.parent as? ViewGroup
            while (parent != null && parent !== sheet && parent.id != android.R.id.content) {
                if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
                    val list = if (scroll == null) below else pinned
                    for (i in parent.indexOfChild(child) + 1 until parent.childCount) {
                        parent.getChildAt(i).takeIf { it.visibility == View.VISIBLE }?.let { list += it }
                    }
                }
                if (scroll == null && (parent is androidx.core.widget.NestedScrollView || parent is android.widget.ScrollView)) scroll = parent
                child = parent
                parent = parent.parent as? ViewGroup
            }
        }

        private fun run(to: Float) {
            animator?.cancel()
            val from = f
            animator = ValueAnimator.ofFloat(from, to).apply {
                duration = (EXPAND * kotlin.math.abs(to - from)).toLong().coerceAtLeast(60L)
                interpolator = standard
                addUpdateListener {
                    if (!v.isAttachedToWindow) {
                        cancel()
                        return@addUpdateListener
                    }
                    f = it.animatedValue as Float
                    show()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        animator = null
                        if (!opening) v.visibility = View.GONE
                        finish()
                    }
                })
                start()
            }
        }

        private fun show() {
            clip.set(0, 0, v.width, (full * f).toInt())
            v.clipBounds = clip
            val up = -full * (1f - f)
            for (b in below) b.translationY = up
            val shift = sheetShift * (1f - f)
            sheet?.translationY = shift
            for (b in pinned) b.translationY = -shift
        }

        private fun finish() {
            v.clipBounds = null
            for (b in below) b.translationY = 0f
            for (b in pinned) b.translationY = 0f
            sheet?.translationY = 0f
            below.clear()
            pinned.clear()
            sheet = null
            scroll = null
            val run = then
            then = null
            run?.invoke()
        }
    }

    /** The first children of [group] come in one after another (a sheet's rows), all done within the sheet's own opening. */
    fun stagger(group: ViewGroup, step: Long = 16, max: Int = 6) {
        var n = 0
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (n >= max) break
            fadeIn(c, fromDy = 8, delay = n * step, duration = SHORT)
            n++
        }
    }
}

/**
 * A choice of a few short options side by side (Settings' ad blocking level): one filled part slides to
 * the one chosen and the words change colour with it. Drawn here, so moving it lays nothing out.
 * Give it a fixed height (44 dp).
 */
class Segmented(context: Context, labels: List<String>, selected: Int, private val onPick: (Int) -> Unit) : LinearLayout(context) {
    private val inset = Ui.dpf(context, 3f)
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.accentContainer(context) }
    private val chosenInk = Ui.onAccentContainer(context)
    private val plainInk = Ui.text(context)
    private val names = labels
    private val views: List<TextView>
    private var position = selected.toFloat()
    private var animator: ValueAnimator? = null

    var selected = selected
        private set

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        background = Ui.shape(context, Ui.color(context, R.color.surface_alt), 22)
        views = labels.mapIndexed { i, label ->
            Ui.text(context, Ui.Type.LABEL, label, lines = 1).apply {
                gravity = Gravity.CENTER
                background = Ui.ripple(context, null, 19)
                setOnClickListener {
                    if (i == this@Segmented.selected) return@setOnClickListener
                    Haptics.tick(this)
                    select(i, animate = true)
                    onPick(i)
                }
                this@Segmented.addView(this, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }
        paintWords()
    }

    /** Shows [index] as chosen (from outside: the same choice made elsewhere). */
    fun select(index: Int, animate: Boolean) {
        if (index == selected && animator == null && position == index.toFloat()) return
        selected = index
        animator?.cancel()
        animator = null
        if (!animate || !isAttachedToWindow || !Motion.enabled(context)) {
            position = index.toFloat()
            paintWords()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, index.toFloat()).apply {
            duration = Motion.SHORT
            interpolator = Motion.standard
            addUpdateListener {
                position = it.animatedValue as Float
                paintWords()
                invalidate()
            }
            start()
        }
    }

    private fun paintWords() {
        views.forEachIndexed { i, tv ->
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            tv.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(plainInk, chosenInk, near))
            tv.contentDescription = names[i] + if (i == selected) ", selected" else ""
        }
    }

    override fun onDraw(canvas: Canvas) {
        val part = width.toFloat() / views.size
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val x = if (rtl) width - part * (position + 1) else part * position
        rect.set(x + inset, inset, x + part - inset, height - inset)
        val r = rect.height() / 2
        canvas.drawRoundRect(rect, r, r, fill)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        position = selected.toFloat()
        super.onDetachedFromWindow()
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
 * Grey shapes where the news is about to appear, gently pulsing, instead of a spinner: [cards] story
 * cards as they will be (a picture across the top, the site, two lines of headline), so the page
 * keeps its shape when the stories arrive.
 */
class SkeletonView(context: Context, private val cards: Int = 2) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.color(context, R.color.skeleton) }
    private val rect = RectF()
    private val d = resources.displayMetrics.density
    private var pulse: ObjectAnimator? = null

    // A flat shape's fading needs no picture of itself first (a slow graphics chip).
    override fun hasOverlappingRendering() = false

    private fun pictureHeight(width: Int) = width * 9f / 16f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (cards * (pictureHeight(w) + (TEXT_DP + GAP_DP) * d)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val picture = pictureHeight(width)
        val inset = Ui.ROW_INSET * d
        val small = Ui.R_XSMALL * d
        val big = Ui.R_MEDIUM * d
        for (i in 0 until cards) {
            val top = i * (picture + (TEXT_DP + GAP_DP) * d) + GAP_DP / 2 * d
            rect.set(0f, top, width.toFloat(), top + picture)
            canvas.drawRoundRect(rect, big, big, paint)
            var y = top + picture + 12 * d
            rect.set(inset, y, inset + 120 * d, y + 14 * d)
            canvas.drawRoundRect(rect, small, small, paint)
            y += 22 * d
            rect.set(inset, y, width - inset, y + 18 * d)
            canvas.drawRoundRect(rect, small, small, paint)
            y += 26 * d
            rect.set(inset, y, inset + (width - 2 * inset) * 0.7f, y + 18 * d)
            canvas.drawRoundRect(rect, small, small, paint)
        }
    }

    private companion object {
        /** The site and the headline under the picture, and the gap between two cards (dp). */
        const val TEXT_DP = 106
        const val GAP_DP = 12
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
