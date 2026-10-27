package com.swiftbrowser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow

/**
 * Swift's colours while it runs (2.3): the accent (a preset or the user's own) and the backgrounds
 * of light and dark mode (presets or the user's own), worked out into every colour the screens use.
 *
 * The screens ask for colours by their resource ids as before (R.color.page_bg, R.color.accent…);
 * [color] answers with the chosen colour for the screen's mode, or the resource's own value for
 * colours that don't follow the choices. Views made from XML layouts are recoloured once with [paint];
 * dialogs and sheets as they open. The default choices (blue, white, grey) are the resources
 * themselves, so they look exactly as before.
 */
object Palette {
    /** A background to choose from: its key, name, and the main colour the others are worked out from. */
    class Background(val key: String, val label: String, val seed: Int)

    val lightBackgrounds = listOf(
        Background("white", "White", 0xFFFFFFFF.toInt()),
        Background("warm", "Warm paper", 0xFFFCF8F0.toInt()),
        Background("cool", "Cool grey", 0xFFF4F6F9.toInt()),
    )

    val darkBackgrounds = listOf(
        Background("grey", "Grey", 0xFF15171C.toInt()),
        Background("black", "Pure black", 0xFF000000.toInt()),
        Background("navy", "Navy", 0xFF0D1322.toInt()),
        Background("warm", "Warm dark", 0xFF1B1815.toInt()),
    )

    const val CUSTOM = "custom"

    /** The colours that follow the accent. */
    private val ACCENT_ROLES = intArrayOf(R.color.accent, R.color.on_accent, R.color.accent_container, R.color.on_accent_container, R.color.toggle_selected)

    /** The colours that follow the background. */
    private val BACKGROUND_ROLES = intArrayOf(
        R.color.page_bg, R.color.home_bg, R.color.toolbar_bg, R.color.sheet_bg, R.color.card_bg, R.color.tab_card_bg,
        R.color.ptr_bg, R.color.settings_bg, R.color.switcher_bg, R.color.surface_alt, R.color.home_search_bg,
        R.color.tile_bg, R.color.tab_thumb_bg, R.color.divider, R.color.outline, R.color.skeleton,
    )

    private var built = false
    private val light = HashMap<Int, Int>()
    private val dark = HashMap<Int, Int>()

    /** Resource value → chosen value, for recolouring views made from XML (per mode). */
    private val lightSwap = HashMap<Int, Int>()
    private val darkSwap = HashMap<Int, Int>()

    /** The same for the accent only: text and icons are never swapped for a background colour (white text isn't a white page). */
    private val lightAccentSwap = HashMap<Int, Int>()
    private val darkAccentSwap = HashMap<Int, Int>()

    /** Changes when any choice changes: open screens compare it to know they must be redrawn. */
    val signature: String
        get() = SwiftApp.prefs.let { listOf(it.accent, it.accentCustom, it.lightBg, it.lightBgCustom, it.darkBg, it.darkBgCustom).joinToString() }

    /** The choices changed: worked out again on the next colour asked for. */
    fun invalidate() {
        built = false
    }

    fun isNight(ctx: Context) =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** A colour by its resource id, as chosen, for [ctx]'s mode. */
    fun color(ctx: Context, res: Int): Int {
        ensure(ctx)
        return (if (isNight(ctx)) dark else light)[res] ?: ContextCompat.getColor(ctx, res)
    }

    /** A colour for one mode, whatever [ctx]'s mode is (previews of both modes side by side). */
    fun colorFor(ctx: Context, night: Boolean, res: Int): Int {
        ensure(ctx)
        return (if (night) dark else light)[res] ?: modeContext(ctx, night).getColor(res)
    }

    private fun modeContext(ctx: Context, night: Boolean): Context {
        val config = Configuration(ctx.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return ctx.createConfigurationContext(config)
    }

    private fun ensure(ctx: Context) {
        if (built) return
        built = true
        val prefs = SwiftApp.prefs
        val app = ctx.applicationContext ?: ctx
        val day = modeContext(app, false)
        val night = modeContext(app, true)
        light.clear()
        dark.clear()
        lightSwap.clear()
        darkSwap.clear()
        lightAccentSwap.clear()
        darkAccentSwap.clear()
        val accent = Accent.of(prefs.accent)
        fill(light, accentRoles(day, accent, false))
        fill(dark, accentRoles(night, accent, true))
        backgroundRoles(false)?.let { fill(light, it) }
        backgroundRoles(true)?.let { fill(dark, it) }
        // What XML layouts and the theme's own colours become.
        for ((map, swap, c) in listOf(Triple(light, lightSwap, day), Triple(dark, darkSwap, night))) {
            val accentSwap = if (swap === lightSwap) lightAccentSwap else darkAccentSwap
            for ((res, value) in map) {
                val original = c.getColor(res)
                if (original == value) continue
                swap[original] = value
                if (res in ACCENT_ROLES) accentSwap[original] = value
            }
            // Material's own parts take the theme's accent (the nearest preset for other accents).
            val overlay = accent.nearestPreset
            for ((res, role) in listOf(
                overlay.primaryRes to R.color.accent, overlay.onPrimaryRes to R.color.on_accent,
                overlay.containerRes to R.color.accent_container, overlay.onContainerRes to R.color.on_accent_container,
            )) {
                val original = c.getColor(res)
                val value = map[role] ?: c.getColor(role)
                if (original != value) {
                    swap[original] = value
                    accentSwap[original] = value
                }
            }
        }
    }

    private fun fill(map: HashMap<Int, Int>, values: Map<Int, Int>) {
        map.putAll(values)
    }

    /** The accent's four colours for one mode: the preset's own, or worked out from its seed. */
    private fun accentRoles(c: Context, accent: Accent, night: Boolean): Map<Int, Int> {
        val (p, onP, cont, onCont) = accentColors(c, accent, night)
        return mapOf(
            R.color.accent to p, R.color.on_accent to onP, R.color.accent_container to cont,
            R.color.on_accent_container to onCont, R.color.toggle_selected to cont,
        )
    }

    /** [primary, on primary, container, on container] of [accent] in one mode. */
    fun accentColors(c: Context, accent: Accent, night: Boolean): List<Int> {
        if (accent.primaryRes != 0) {
            val m = if (isNight(c) == night) c else modeContext(c, night)
            return listOf(m.getColor(accent.primaryRes), m.getColor(accent.onPrimaryRes), m.getColor(accent.containerRes), m.getColor(accent.onContainerRes))
        }
        val seed = accent.seedColor
        return if (night) {
            listOf(Tones.tone(seed, 80.0), Tones.tone(seed, 20.0), Tones.tone(seed, 30.0), Tones.tone(seed, 90.0))
        } else {
            listOf(Tones.tone(seed, 40.0), Color.WHITE, Tones.tone(seed, 90.0), Tones.tone(seed, 10.0))
        }
    }

    /** The chosen background's colours for one mode, or null for the default (the resources as they are). */
    private fun backgroundRoles(night: Boolean): Map<Int, Int>? {
        val prefs = SwiftApp.prefs
        val key = if (night) prefs.darkBg else prefs.lightBg
        val seed = when {
            key == CUSTOM -> if (night) limitDark(prefs.darkBgCustom) else limitLight(prefs.lightBgCustom)
            night -> darkBackgrounds.firstOrNull { it.key == key }?.takeIf { it != darkBackgrounds[0] }?.seed ?: return null
            else -> lightBackgrounds.firstOrNull { it.key == key }?.takeIf { it != lightBackgrounds[0] }?.seed ?: return null
        }
        return if (night) darkRoles(seed) else lightRoles(seed)
    }

    /** Light mode from its main colour: pages, bars and cards in it, the rest a little darker. */
    fun lightRoles(s: Int): Map<Int, Int> {
        val shade = { t: Double -> Tones.mix(s, Color.BLACK, t) }
        return mapOf(
            R.color.page_bg to s, R.color.home_bg to s, R.color.toolbar_bg to s, R.color.sheet_bg to s,
            R.color.card_bg to s, R.color.tab_card_bg to s, R.color.ptr_bg to s,
            R.color.settings_bg to shade(0.045), R.color.switcher_bg to shade(0.045),
            R.color.surface_alt to shade(0.062), R.color.home_search_bg to shade(0.062), R.color.tile_bg to shade(0.062),
            R.color.tab_thumb_bg to shade(0.085), R.color.skeleton to shade(0.085),
            R.color.divider to shade(0.095), R.color.outline to shade(0.165),
        )
    }

    /** Dark mode from its main colour (the page behind everything): bars, sheets and cards a little lighter. */
    fun darkRoles(s: Int): Map<Int, Int> {
        val lift = { t: Double -> Tones.mix(s, Color.WHITE, t) }
        return mapOf(
            R.color.page_bg to s, R.color.home_bg to s,
            R.color.settings_bg to Tones.mix(s, Color.BLACK, 0.14), R.color.switcher_bg to Tones.mix(s, Color.BLACK, 0.14),
            R.color.toolbar_bg to lift(0.026),
            R.color.sheet_bg to lift(0.043), R.color.card_bg to lift(0.043), R.color.tab_card_bg to lift(0.043),
            R.color.surface_alt to lift(0.09), R.color.home_search_bg to lift(0.09), R.color.tile_bg to lift(0.09), R.color.ptr_bg to lift(0.09),
            R.color.tab_thumb_bg to lift(0.065), R.color.skeleton to lift(0.065),
            R.color.divider to lift(0.07), R.color.outline to lift(0.14),
        )
    }

    /** A light background stays light enough for dark text. */
    fun limitLight(c: Int): Int {
        var v = c or 0xFF000000.toInt()
        var i = 0
        while (Color.luminance(v) < 0.78f && i++ < 40) v = Tones.mix(v, Color.WHITE, 0.08)
        return v
    }

    /** A dark background stays dark enough for light text. */
    fun limitDark(c: Int): Int {
        var v = c or 0xFF000000.toInt()
        var i = 0
        while (Color.luminance(v) > 0.022f && i++ < 60) v = Tones.mix(v, Color.BLACK, 0.08)
        return v
    }

    // ------------------------------------------------------------------ recolouring views

    /**
     * Recolours [view] and everything in it, made from an XML layout or with the theme's colours:
     * backgrounds, progress bars, picture tints, Material buttons and list ticks. Once, when made.
     */
    fun <T : View> paint(view: T): T {
        ensure(view.context)
        val night = isNight(view.context)
        val swap = if (night) darkSwap else lightSwap
        if (swap.isEmpty()) return view
        paintTree(view, swap, if (night) darkAccentSwap else lightAccentSwap)
        return view
    }

    private fun paintTree(v: View, swap: Map<Int, Int>, accentSwap: Map<Int, Int>) {
        // Colour swatches and previews show exact colours (the blue swatch is the stock accent).
        if (v.getTag(R.id.palette_keep) == true) return
        v.background?.let { if (repaint(it, swap)) v.invalidate() }
        when (v) {
            is ProgressBar -> {
                v.progressDrawable?.let { repaint(it, swap) }
                v.progressTintList?.let { t -> accentSwap[t.defaultColor]?.let { v.progressTintList = ColorStateList.valueOf(it) } }
            }
            is ImageView -> v.imageTintList?.let { t -> accentSwap[t.defaultColor]?.let { v.imageTintList = ColorStateList.valueOf(it) } }
            is MaterialButton -> paintButton(v, accentSwap)
            is CheckedTextView -> {
                v.checkMarkTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(color(v.context, R.color.accent), color(v.context, R.color.text_secondary)),
                )
                accentSwap[v.currentTextColor]?.let { v.setTextColor(it) }
            }
            is TextView -> accentSwap[v.currentTextColor]?.let { v.setTextColor(it) }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) paintTree(v.getChildAt(i), swap, accentSwap)
    }

    private fun paintButton(b: MaterialButton, swap: Map<Int, Int>) {
        b.backgroundTintList?.let { t -> swap[t.defaultColor]?.let { b.backgroundTintList = ColorStateList.valueOf(it) } }
        swap[b.currentTextColor]?.let { b.setTextColor(it) }
        b.iconTint?.let { t -> swap[t.defaultColor]?.let { b.iconTint = ColorStateList.valueOf(it) } }
        b.rippleColor?.let { t -> swap[t.defaultColor]?.let { b.rippleColor = ColorStateList.valueOf(Tones.withAlpha(it, Color.alpha(t.defaultColor))) } }
    }

    /** True when a colour in [d] was swapped. */
    private fun repaint(d: Drawable, swap: Map<Int, Int>): Boolean = when (d) {
        is ColorDrawable -> swap[d.color]?.let { d.mutate(); d.color = it; true } ?: false
        is GradientDrawable -> d.color?.defaultColor?.let { old -> swap[old]?.let { d.mutate(); d.setColor(it); true } } ?: false
        is LayerDrawable -> (0 until d.numberOfLayers).map { repaint(d.getDrawable(it), swap) }.any { it }
        is InsetDrawable -> d.drawable?.let { repaint(it, swap) } ?: false
        is ClipDrawable -> d.drawable?.let { repaint(it, swap) } ?: false
        else -> false
    }

    /** A Material dialog's background in the chosen sheet colour (call on the builder, before create). */
    fun dialogBackground(ctx: Context): Drawable = GradientDrawable().apply {
        cornerRadius = 26 * ctx.resources.displayMetrics.density
        setColor(color(ctx, R.color.sheet_bg))
    }

    /** After a dialog showed: its buttons and list ticks in the chosen accent, the rest recoloured. */
    fun paintDialog(d: android.app.Dialog) {
        // Buttons in the theme's accent take the chosen one (a red "Delete" keeps its colour), list ticks too.
        if (d is androidx.appcompat.app.AlertDialog) {
            d.listView?.let { list ->
                // Rows come and go while scrolling: each new one is recoloured too.
                list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        child?.let { paint(it) }
                    }

                    override fun onChildViewRemoved(parent: View?, child: View?) {}
                })
            }
        }
        d.window?.decorView?.let { paint(it) }
    }

    /** A bottom sheet's background in the chosen sheet colour (after setContentView). */
    fun paintSheet(d: com.google.android.material.bottomsheet.BottomSheetDialog) {
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ColorStateList.valueOf(color(d.context, R.color.sheet_bg))
    }
}

/** Colour maths for [Palette]: mixing, and lighter or darker tones of one colour (CIELAB lightness). */
object Tones {
    fun mix(a: Int, b: Int, t: Double): Int {
        val u = 1 - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t + 0.5).toInt(),
            (Color.green(a) * u + Color.green(b) * t + 0.5).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t + 0.5).toInt(),
        )
    }

    fun withAlpha(c: Int, alpha: Int) = (c and 0x00FFFFFF) or (alpha shl 24)

    /** [seed] at lightness [tone] (0 black … 100 white), as colourful as the screen can show. */
    fun tone(seed: Int, tone: Double): Int {
        val (_, a, b) = lab(seed)
        // Less colour until it fits on screen (very light and very dark tones hold little colour).
        var lo = 0.0
        var hi = 1.0
        var best = fromLab(tone, 0.0, 0.0) ?: Color.GRAY
        if (fromLab(tone, a, b)?.also { best = it } != null) return best
        repeat(18) {
            val mid = (lo + hi) / 2
            val c = fromLab(tone, a * mid, b * mid)
            if (c != null) {
                best = c
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    /** How far apart two hues are on the colour wheel (degrees, 0–180). */
    fun hueDistance(a: Int, b: Int): Float {
        val ha = FloatArray(3).also { Color.colorToHSV(a, it) }[0]
        val hb = FloatArray(3).also { Color.colorToHSV(b, it) }[0]
        val d = abs(ha - hb) % 360f
        return min(d, 360f - d)
    }

    fun saturation(c: Int) = FloatArray(3).also { Color.colorToHSV(c, it) }[1]

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055

    private const val XN = 0.95047
    private const val ZN = 1.08883

    private fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116

    private fun finv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > 0.008856) t3 else (t - 16.0 / 116) / 7.787
    }

    fun lab(c: Int): Triple<Double, Double, Double> {
        val r = lin(Color.red(c))
        val g = lin(Color.green(c))
        val b = lin(Color.blue(c))
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / XN
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / ZN
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    /** The colour, or null when the screen can't show it. */
    private fun fromLab(l: Double, a: Double, b: Double): Int? {
        val fy = (l + 16) / 116
        val x = XN * finv(fy + a / 500)
        val y = finv(fy)
        val z = ZN * finv(fy - b / 200)
        val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
        val eps = 0.0005
        if (r < -eps || r > 1 + eps || g < -eps || g > 1 + eps || bl < -eps || bl > 1 + eps) return null
        fun ch(v: Double) = (gamma(v.coerceIn(0.0, 1.0)) * 255 + 0.5).toInt().coerceIn(0, 255)
        return Color.rgb(ch(r), ch(g), ch(bl))
    }
}
