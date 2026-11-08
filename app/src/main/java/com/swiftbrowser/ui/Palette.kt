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
import android.widget.AbsListView
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
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

    /**
     * 3.0: text colours worked out for the background, so grey text stays readable on your own
     * (its stock value is used for nothing else, so text in it can be recoloured safely).
     */
    private val TEXT_ROLES = intArrayOf(R.color.text_secondary)

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

    /**
     * The same for the accent (and, 3.0, grey text) only: text and icons are never swapped for a
     * background colour (white text isn't a white page).
     */
    private val lightAccentSwap = HashMap<Int, Int>()
    private val darkAccentSwap = HashMap<Int, Int>()

    /**
     * 3.0.5: whether Android's own parts (radio buttons, check boxes, text cursors and handles, the
     * glow at a list's end), which the theme colours with the nearest preset, already show the accent
     * chosen. Not for an accent without a preset of its own (amber's radios were salmon) or your own colours.
     */
    private var lightExact = true
    private var darkExact = true

    // 3.0.5: tints shared by every radio, check box and underline of one mode (one object each, made once).
    private var lightChoice: ColorStateList? = null
    private var darkChoice: ColorStateList? = null
    private var lightUnderline: ColorStateList? = null
    private var darkUnderline: ColorStateList? = null

    /** Changes when any choice changes: open screens compare it to know they must be redrawn. */
    val signature: String
        get() = SwiftApp.prefs.let { listOf(it.accent, it.accentCustom, it.lightBg, it.lightBgCustom, it.darkBg, it.darkBgCustom).joinToString() }

    /** The choices changed: worked out again on the next colour asked for. */
    @Synchronized
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
        return (if (night) dark else light)[res] ?: resourcesFor(ctx, night).getColor(res)
    }

    /** A colour's own value (as in the resources) in one mode, whatever was chosen. */
    fun stockColor(ctx: Context, night: Boolean, res: Int): Int = resourcesFor(ctx, night).getColor(res)

    private fun modeContext(ctx: Context, night: Boolean): Context {
        val config = Configuration(ctx.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return ctx.createConfigurationContext(config)
    }

    // 3.0: the app's resources in light and in dark mode, made once. Colours asked for one mode were
    // read through a new configuration context each time (20 of them for every finger move in the
    // colour maker's preview).
    @Volatile private var dayResources: Context? = null
    @Volatile private var nightResources: Context? = null

    private fun resourcesFor(ctx: Context, night: Boolean): Context =
        (if (night) nightResources else dayResources) ?: modeContext(ctx.applicationContext ?: ctx, night).also {
            if (night) nightResources = it else dayResources = it
        }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (built) return
        built = true
        val prefs = SwiftApp.prefs
        val day = resourcesFor(ctx, false)
        val night = resourcesFor(ctx, true)
        light.clear()
        dark.clear()
        lightSwap.clear()
        darkSwap.clear()
        lightAccentSwap.clear()
        darkAccentSwap.clear()
        lightChoice = null
        darkChoice = null
        lightUnderline = null
        darkUnderline = null
        val accent = Accent.of(prefs.accent)
        val lightBg = backgroundRoles(day, false)
        val darkBg = backgroundRoles(night, true)
        // 3.0: accent text and switches readable on the chosen background (your own colours; presets as made).
        fill(light, accentRoles(day, accent, false, textBackgrounds(day, lightBg).takeIf { accent == Accent.CUSTOM || prefs.lightBg == CUSTOM }))
        fill(dark, accentRoles(night, accent, true, textBackgrounds(night, darkBg).takeIf { accent == Accent.CUSTOM || prefs.darkBg == CUSTOM }))
        lightBg?.let { fill(light, it) }
        darkBg?.let { fill(dark, it) }
        // What XML layouts and the theme's own colours become.
        for ((map, swap, c) in listOf(Triple(light, lightSwap, day), Triple(dark, darkSwap, night))) {
            val accentSwap = if (swap === lightSwap) lightAccentSwap else darkAccentSwap
            for ((res, value) in map) {
                val original = c.getColor(res)
                if (original == value) continue
                swap[original] = value
                if (res in ACCENT_ROLES || res in TEXT_ROLES) accentSwap[original] = value
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
            // 3.0.5: what the theme's accent is against the one chosen (see lightExact).
            val exact = c.getColor(overlay.primaryRes) == (map[R.color.accent] ?: c.getColor(R.color.accent))
            if (swap === lightSwap) lightExact = exact else darkExact = exact
        }
    }

    private fun fill(map: HashMap<Int, Int>, values: Map<Int, Int>) {
        map.putAll(values)
    }

    /**
     * The accent's four colours for one mode: the preset's own, or worked out from its seed. [bgs]:
     * the backgrounds its text has to read on (3.0), or null to leave it as it is.
     */
    private fun accentRoles(c: Context, accent: Accent, night: Boolean, bgs: List<Int>?): Map<Int, Int> {
        val (p, onP, cont, onCont) = accentColors(c, accent, night, bgs)
        return mapOf(
            R.color.accent to p, R.color.on_accent to onP, R.color.accent_container to cont,
            R.color.on_accent_container to onCont, R.color.toggle_selected to cont,
        )
    }

    /**
     * [primary, on primary, container, on container] of [accent] in one mode. 3.0: with [bgs], the
     * primary is deepened (light mode) or lightened (dark mode) until it reads as text on all of them.
     */
    fun accentColors(c: Context, accent: Accent, night: Boolean, bgs: List<Int>? = null): List<Int> {
        val toward = if (night) Color.WHITE else Color.BLACK
        if (accent.primaryRes != 0) {
            val m = if (isNight(c) == night) c else resourcesFor(c, night)
            val primary = m.getColor(accent.primaryRes).let { if (bgs != null) Contrast.readable(it, bgs, Contrast.TEXT, toward) else it }
            return listOf(primary, m.getColor(accent.onPrimaryRes), m.getColor(accent.containerRes), m.getColor(accent.onContainerRes))
        }
        val seed = accent.seedColor
        if (accent == Accent.CUSTOM) {
            // Your own colour stays as you made it (2.5); only nudged when text on it would be hard to read.
            val (primary, on) = if (bgs != null) customAccent(seed, night, bgs) else customAccent(seed, night)
            return if (night) listOf(primary, on, Tones.tone(seed, 30.0), Tones.tone(seed, 90.0))
            else listOf(primary, on, Tones.tone(seed, 90.0), Tones.tone(seed, 10.0))
        }
        val primary = Tones.tone(seed, if (night) 80.0 else 40.0).let { if (bgs != null) Contrast.readable(it, bgs, Contrast.TEXT, toward) else it }
        return if (night) {
            listOf(primary, Tones.tone(seed, 20.0), Tones.tone(seed, 30.0), Tones.tone(seed, 90.0))
        } else {
            listOf(primary, Color.WHITE, Tones.tone(seed, 90.0), Tones.tone(seed, 10.0))
        }
    }

    /**
     * The colours text sits on in one mode (3.0): the page and the most tinted surface (chips, the
     * address box), as chosen (or the stock ones). Accent and grey text must read on both.
     */
    fun textBackgrounds(ctx: Context, night: Boolean): List<Int> {
        val c = resourcesFor(ctx, night)
        return textBackgrounds(c, backgroundRoles(c, night))
    }

    private fun textBackgrounds(c: Context, roles: Map<Int, Int>?): List<Int> =
        listOf(roles?.get(R.color.page_bg) ?: c.getColor(R.color.page_bg), roles?.get(R.color.surface_alt) ?: c.getColor(R.color.surface_alt))

    /** The chosen background's colours for one mode ([c] in that mode), or null for the default (the resources as they are). */
    private fun backgroundRoles(c: Context, night: Boolean): Map<Int, Int>? {
        val prefs = SwiftApp.prefs
        val key = if (night) prefs.darkBg else prefs.lightBg
        val seed = when {
            key == CUSTOM -> if (night) limitDark(prefs.darkBgCustom) else limitLight(prefs.lightBgCustom)
            night -> darkBackgrounds.firstOrNull { it.key == key }?.takeIf { it != darkBackgrounds[0] }?.seed ?: return null
            else -> lightBackgrounds.firstOrNull { it.key == key }?.takeIf { it != lightBackgrounds[0] }?.seed ?: return null
        }
        return if (night) darkRoles(seed, c.getColor(R.color.text_secondary)) else lightRoles(seed, c.getColor(R.color.text_secondary))
    }

    /**
     * Light mode from its main colour: pages, bars and cards in it, the rest a little darker. 3.0:
     * and the grey text ([secondary], the stock one) deepened where it would be hard to read on them.
     */
    fun lightRoles(s: Int, secondary: Int): Map<Int, Int> {
        val shade = { t: Double -> Tones.mix(s, Color.BLACK, t) }
        return mapOf(
            R.color.page_bg to s, R.color.home_bg to s, R.color.toolbar_bg to s, R.color.sheet_bg to s,
            R.color.card_bg to s, R.color.tab_card_bg to s, R.color.ptr_bg to s,
            R.color.settings_bg to shade(0.045), R.color.switcher_bg to shade(0.045),
            R.color.surface_alt to shade(0.062), R.color.home_search_bg to shade(0.062), R.color.tile_bg to shade(0.062),
            R.color.tab_thumb_bg to shade(0.085), R.color.skeleton to shade(0.085),
            R.color.divider to shade(0.095), R.color.outline to shade(0.165),
            R.color.text_secondary to Contrast.readable(secondary, listOf(s, shade(0.062)), Contrast.TEXT, Color.BLACK),
        )
    }

    /**
     * Dark mode from its main colour (the page behind everything): bars a little lighter, sheets and
     * cards a clear step above the page, tiles and chips a step above them. 3.0: and the grey text
     * ([secondary]) lightened where it would be hard to read on them. 3.0.5: the steps are contrast
     * ([DarkSteps]), not a share of white (cards were 1.07:1 on pure black and 1.12:1 on the grey):
     * the same on every dark background, ready-made or your own, the stock grey included (its colours
     * in values-night/colors.xml are these).
     */
    fun darkRoles(s: Int, secondary: Int): Map<Int, Int> {
        val lift = { t: Double -> Tones.mix(s, Color.WHITE, t) }
        val card = Contrast.above(s, DarkSteps.CARD)
        val thumb = Contrast.above(s, DarkSteps.THUMB)
        val surface = Contrast.above(s, DarkSteps.SURFACE)
        return mapOf(
            R.color.page_bg to s, R.color.home_bg to s,
            R.color.settings_bg to Tones.mix(s, Color.BLACK, 0.14), R.color.switcher_bg to Tones.mix(s, Color.BLACK, 0.14),
            R.color.toolbar_bg to lift(0.026),
            R.color.sheet_bg to card, R.color.card_bg to card, R.color.tab_card_bg to card,
            R.color.surface_alt to surface, R.color.home_search_bg to surface, R.color.tile_bg to surface, R.color.ptr_bg to surface,
            R.color.tab_thumb_bg to thumb, R.color.skeleton to thumb,
            R.color.divider to Contrast.above(s, DarkSteps.DIVIDER), R.color.outline to Contrast.above(s, DarkSteps.OUTLINE),
            R.color.text_secondary to Contrast.readable(secondary, listOf(s, surface), Contrast.TEXT, Color.WHITE),
        )
    }

    /** A light background stays light enough for dark text. */
    fun limitLight(c: Int): Int {
        var v = c or 0xFF000000.toInt()
        var i = 0
        while (Contrast.luminance(v) < 0.6 && i++ < 60) v = Tones.mix(v, Color.WHITE, 0.05)
        return v
    }

    /**
     * Your own accent and the colour of text on it: the colour exactly as made, unless it would be
     * hard to read on the page (light mode: darkened just enough; dark mode: lightened just enough).
     * 3.0: checked against the background chosen ([bgs]: page and surface) and to 4.5:1, as text
     * needs; it was 3:1 against the stock page, so accent text could drop to 2:1 on your own colour.
     */
    fun customAccent(color: Int, night: Boolean, bgs: List<Int> = listOf(if (night) 0xFF15171C.toInt() else Color.WHITE)): Pair<Int, Int> {
        val c = Contrast.readable(color, bgs, Contrast.TEXT, if (night) Color.WHITE else Color.BLACK)
        // Text on it: white in light mode (it has 4.5:1 against a light background, and white is
        // lighter still, so white reads on it too) and a deep shade of it in dark mode. Never another
        // fixed colour: Palette.paint swaps colours by value, and the stock text-on-accent white is also
        // the white of cards (2.5: a light accent with dark text turned every card black).
        val on = if (night) Tones.tone(c, 10.0) else Color.WHITE
        return c to on
    }

    /** A dark background stays dark enough for light text. */
    fun limitDark(c: Int): Int {
        var v = c or 0xFF000000.toInt()
        var i = 0
        while (Contrast.luminance(v) > 0.07 && i++ < 60) v = Tones.mix(v, Color.BLACK, 0.05)
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
        paintTree(view, swap, if (night) darkAccentSwap else lightAccentSwap, if (night) darkExact else lightExact)
        return view
    }

    /** 3.0.5: whether the theme's accent, which Android's own parts use, is the one chosen in [c]'s mode. */
    private fun themeHasAccent(c: Context): Boolean {
        ensure(c)
        return if (isNight(c)) darkExact else lightExact
    }

    /**
     * 3.0.5: the glow at the ends of [v], a list or scroll view that isn't made with [paint], in the
     * accent chosen.
     */
    fun glow(v: View) {
        if (!themeHasAccent(v.context)) ExactAccent.glow(v, color(v.context, R.color.accent))
    }

    /** 3.0.5: a field not made with [paint]: its cursor and handles in the accent chosen. */
    fun cursor(tv: TextView) {
        if (!themeHasAccent(tv.context)) ExactAccent.text(tv, color(tv.context, R.color.accent))
    }

    /**
     * Radio buttons and check boxes: the accent ticked, grey not. 3.0.5: in the accent chosen, for
     * Material's dialog lists too, whose radio is a compound drawable; only a checked-text's own
     * check mark was tinted before. One list per mode, made once.
     */
    private fun choiceTint(c: Context): ColorStateList {
        val night = isNight(c)
        (if (night) darkChoice else lightChoice)?.let { return it }
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color(c, R.color.accent), color(c, R.color.text_secondary)),
        ).also { if (night) darkChoice = it else lightChoice = it }
    }

    /** 3.0.5: a text field's underline in the accent chosen while it's focused or held. */
    private fun underlineTint(c: Context): ColorStateList {
        val night = isNight(c)
        (if (night) darkUnderline else lightUnderline)?.let { return it }
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(color(c, R.color.accent), color(c, R.color.accent), color(c, R.color.text_secondary)),
        ).also { if (night) darkUnderline = it else lightUnderline = it }
    }

    private fun paintTree(v: View, swap: Map<Int, Int>, accentSwap: Map<Int, Int>, exact: Boolean) {
        // Colour swatches and previews show exact colours (the blue swatch is the stock accent).
        if (v.getTag(R.id.palette_keep) == true) return
        v.background?.let { if (repaint(it, swap)) v.invalidate() }
        when (v) {
            is ProgressBar -> {
                v.progressDrawable?.let { repaint(it, swap) }
                v.progressTintList?.let { t -> accentSwap[t.defaultColor]?.let { v.progressTintList = ColorStateList.valueOf(it) } }
                // 3.0: spinners too (their XML tint kept the nearest preset's colour).
                v.indeterminateTintList?.let { t -> accentSwap[t.defaultColor]?.let { v.indeterminateTintList = ColorStateList.valueOf(it) } }
            }
            is ImageView -> v.imageTintList?.let { t -> accentSwap[t.defaultColor]?.let { v.imageTintList = ColorStateList.valueOf(it) } }
            is MaterialButton -> paintButton(v, accentSwap)
            is CheckedTextView -> {
                v.checkMarkTintList = choiceTint(v.context)
                // 3.0.5: Material's dialog lists have their radio or tick as a compound drawable, not a check mark.
                if (!exact) v.compoundDrawableTintList = choiceTint(v.context)
                accentSwap[v.currentTextColor]?.let { v.setTextColor(it) }
            }
            // 3.0.5: check boxes (radio buttons too, should Swift get any) in the accent chosen,
            // unless they were given their colour already (the torrent sheet's).
            is CompoundButton -> {
                if (!exact && v.buttonTintList == null) v.buttonTintList = choiceTint(v.context)
                accentSwap[v.currentTextColor]?.let { v.setTextColor(it) }
            }
            is TextView -> {
                accentSwap[v.currentTextColor]?.let { v.setTextColor(it) }
                // 3.0.5: a field's cursor and handles (text that can be selected too) in the accent chosen.
                if (!exact && (v is EditText || v.isTextSelectable)) {
                    ExactAccent.text(v, color(v.context, R.color.accent))
                    if (v is EditText && v.background is InsetDrawable) v.backgroundTintList = underlineTint(v.context)
                }
            }
            is RecyclerView, is ScrollView, is HorizontalScrollView, is AbsListView -> if (!exact) ExactAccent.glow(v, color(v.context, R.color.accent))
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) paintTree(v.getChildAt(i), swap, accentSwap, exact)
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
        cornerRadius = Ui.R_LARGE * ctx.resources.displayMetrics.density
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

/**
 * 3.0.5: how far each surface of the dark look stands above the page, as contrast against it (Contrast.above),
 * so every dark background has the same steps: cards clearly apart from the page, the rest above them in
 * order. Tested for every background on the PC (DarkStepsTest).
 */
internal object DarkSteps {
    /** Sheets and cards: 1.2:1 or a little more against the page, and more against settings' darker page. */
    const val CARD = 1.22

    /** A tab's empty picture and the grey shapes while content loads: just above a card. */
    const val THUMB = 1.30

    /** Chips, tiles, the address and search boxes, and the switches' tracks: a clear step above a card. */
    const val SURFACE = 1.40

    /** Lines between rows: as clear as a chip on a card. */
    const val DIVIDER = 1.42

    /** Outlines: the strongest. */
    const val OUTLINE = 1.80
}

/**
 * How readable one colour is on another (3.0), as WCAG measures it, on plain ARGB numbers: no
 * Android classes, so the maths is tested on the PC (PaletteContrastTest).
 */
internal object Contrast {
    /** What text needs (WCAG AA for body text). */
    const val TEXT = 4.5

    /** Relative luminance, 0 (black) to 1 (white); the same as Android's Color.luminance. */
    fun luminance(c: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s < 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)
    }

    /** The contrast ratio of two colours, 1 (the same) to 21 (black on white). */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return if (la > lb) la / lb else lb / la
    }

    /** [a] with [t] (0..1) of [b] mixed in; always opaque. */
    fun mix(a: Int, b: Int, t: Double): Int {
        val u = 1 - t
        fun channel(shift: Int) = ((((a shr shift) and 0xFF) * u + ((b shr shift) and 0xFF) * t) + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * 3.0.5: [base] moved toward white just far enough to stand [target]:1 off it: an even step on
     * every background (one share of white gives a dark page's cards a much smaller step than a
     * lighter page's). 8-bit colours step unevenly in the dark, so it can come out a little over.
     */
    fun above(base: Int, target: Double): Int {
        var lo = 0.0
        var hi = 1.0
        repeat(24) {
            val t = (lo + hi) / 2
            if (ratio(mix(base, 0xFFFFFFFF.toInt(), t), base) >= target) hi = t else lo = t
        }
        return mix(base, 0xFFFFFFFF.toInt(), hi)
    }

    /**
     * [fg], moved toward [toward] (black on light backgrounds, white on dark ones) a little at a time
     * until it has [min]:1 against every one of [bgs]; unchanged when it already has.
     */
    fun readable(fg: Int, bgs: List<Int>, min: Double, toward: Int): Int {
        var c = fg or 0xFF000000.toInt()
        var i = 0
        while (bgs.any { ratio(c, it) < min } && i++ < 60) c = mix(c, toward, 0.05)
        return c
    }

    /**
     * A colour with hardly any hue (grey, white, black, 3.0): its hue says nothing, so it can't
     * pick the nearest preset by hue.
     */
    fun greyish(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val max = maxOf(r, g, b)
        val spread = max - minOf(r, g, b)
        return spread < 10 || spread < max * 0.12
    }
}

/** Colour maths for [Palette]: mixing, and lighter or darker tones of one colour (CIELAB lightness). */
object Tones {
    fun mix(a: Int, b: Int, t: Double): Int = Contrast.mix(a, b, t)

    fun withAlpha(c: Int, alpha: Int) = (c and 0x00FFFFFF) or (alpha shl 24)

    /**
     * [seed] at lightness [tone] (0 black … 100 white), as colourful as the screen can show. 3.0.4:
     * [chroma] asks for that much colour instead of the seed's own, its hue kept (a cover's soft
     * background, a clear accent from a dull cover).
     */
    fun tone(seed: Int, tone: Double, chroma: Double = Double.NaN): Int {
        var (_, a, b) = lab(seed)
        val own = hypot(a, b)
        if (!chroma.isNaN() && own > 0) {
            a *= chroma / own
            b *= chroma / own
        }
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

    // 3.0.4: the channels read from the colour's number (not with Color.red…), so tones are worked
    // out the same on the PC, where the music screen's colours are tested (CoverColoursTest).
    fun lab(c: Int): Triple<Double, Double, Double> {
        val r = lin((c shr 16) and 0xFF)
        val g = lin((c shr 8) and 0xFF)
        val b = lin(c and 0xFF)
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
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(bl)
    }
}
