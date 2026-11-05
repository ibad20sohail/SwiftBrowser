package com.swiftbrowser.ui

/*
 * 3.0.4: the address bar in a website's own colour (the colour a page asks browsers for with
 * <meta name="theme-color">, as Vivaldi and Chrome use it), and the floating bar's shape. Plain maths
 * on ARGB numbers and pixel sizes, without Android's classes, so it is tested on the PC
 * (BarColoursTest). MainActivity and GroupStrip paint with it.
 */

/**
 * The colours of the address bar and what belongs to it (the address box, text, icons, the line to
 * the page, the accent's bits): Swift's own, incognito's, or worked out from a website's colour
 * ([forSite]). [surround]: what is around the floating bar's pill where the page can't show.
 * [site]: from a website's colour (its group colours are then checked for contrast too).
 */
class BarColours(
    val bar: Int,
    val pill: Int,
    val text: Int,
    val icon: Int,
    val line: Int,
    val accent: Int,
    val danger: Int,
    val surround: Int,
    val site: Boolean = false,
) {
    /** The floating pill's outline (dark look, incognito): a line a little lighter than the bar. */
    val outline: Int get() = Contrast.mix(bar, icon, 0.25)

    /** The bar is dark (light text on it). */
    val dark: Boolean get() = Contrast.luminance(text) > Contrast.luminance(bar)

    companion object {
        /**
         * The bar's colours for a website's [colour] (already fit for the bar, see BarTones.forBar):
         * text and icons light or dark, whichever reads; the address box a step off the bar; the
         * accent's bits ([accent], and [danger] for "not secure") in the bar's text colour where
         * they wouldn't stand out.
         */
        fun forSite(colour: Int, accent: Int, danger: Int, surround: Int): BarColours {
            val ink = BarTones.ink(colour)
            val pill = BarTones.pill(colour, ink)
            val icon = BarTones.soft(ink, colour, pill)
            return BarColours(
                bar = colour, pill = pill, text = ink, icon = icon, line = BarTones.line(colour, icon),
                accent = BarTones.onBar(accent, colour, ink), danger = BarTones.onBar(danger, pill, ink),
                surround = surround, site = true,
            )
        }

        /** [t] (0..1) of the way from [a] to [b]: the bar's colours while they change. */
        fun between(a: BarColours, b: BarColours, t: Float): BarColours {
            fun m(x: Int, y: Int) = BarTones.blend(x, y, t.toDouble())
            return BarColours(
                m(a.bar, b.bar), m(a.pill, b.pill), m(a.text, b.text), m(a.icon, b.icon), m(a.line, b.line),
                m(a.accent, b.accent), m(a.danger, b.danger), m(a.surround, b.surround), b.site,
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is BarColours && bar == other.bar && pill == other.pill &&
        text == other.text && icon == other.icon && line == other.line && accent == other.accent &&
        danger == other.danger && surround == other.surround && site == other.site

    override fun hashCode(): Int = listOf(bar, pill, text, icon, line, accent, danger, surround).hashCode() * 2 + if (site) 1 else 0
}

/** Which colours go on a website-coloured bar, so that everything on it stays readable. */
internal object BarTones {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    /** The text on a light bar: Swift's own dark text (text_primary in light mode). */
    const val DARK_INK = 0xFF1A1C21.toInt()

    /** The text on a dark bar. */
    const val LIGHT_INK = WHITE

    /** A see-through colour less opaque than this (of 255, about a tenth) counts as none. */
    const val MIN_ALPHA = 26

    /**
     * In the dark look a website's colour is only used when white text reads on it this well (7:1):
     * a colour dark enough for a dark bar. A light one would glare at night.
     */
    const val DARK_LOOK_CONTRAST = 7.0

    /** What a mark that isn't text needs on what is around it (WCAG's 3:1). */
    const val GRAPHIC = 3.0

    /**
     * The bar's colour for a website's [colour] (ARGB, as CssColour reads it), or null when Swift
     * keeps its own: a see-through colour is laid over [under] (the bar it would be on), so it is
     * opaque; an almost invisible one counts as none; in the dark look ([dark]) only a dark colour.
     */
    fun forBar(colour: Int, under: Int, dark: Boolean): Int? {
        val alpha = (colour ushr 24) and 0xFF
        if (alpha < MIN_ALPHA) return null
        val solid = colour or 0xFF000000.toInt()
        val opaque = if (alpha == 0xFF) solid else Contrast.mix(under, solid, alpha / 255.0)
        if (dark && Contrast.ratio(opaque, WHITE) < DARK_LOOK_CONTRAST) return null
        return opaque
    }

    /** The text on [bar]: white or dark, whichever reads better on it. */
    fun ink(bar: Int): Int = if (Contrast.ratio(LIGHT_INK, bar) >= Contrast.ratio(DARK_INK, bar)) LIGHT_INK else DARK_INK

    /**
     * The address box on [bar]: a step darker on a light bar, lighter on a dark one (as Swift's own
     * is), then moved away from the text's colour [ink] until the site's name reads on it (4.5:1):
     * a bar of a middle tone has little contrast to spare.
     */
    fun pill(bar: Int, ink: Int): Int {
        val light = ink != LIGHT_INK
        val step = if (light) Contrast.mix(bar, BLACK, 0.07) else Contrast.mix(bar, WHITE, 0.10)
        return Contrast.readable(step, listOf(ink), Contrast.TEXT, if (light) WHITE else BLACK)
    }

    /** Icons and hints: the text's colour softened with the bar's, still clear (3:1) on [bar] and the [pill]. */
    fun soft(ink: Int, bar: Int, pill: Int): Int =
        Contrast.readable(Contrast.mix(ink, bar, 0.28), listOf(bar, pill), GRAPHIC, if (ink == LIGHT_INK) WHITE else BLACK)

    /** The hairline between the bar and the page: the bar's colour with a little of its icons'. */
    fun line(bar: Int, icon: Int): Int = Contrast.mix(bar, icon, 0.18)

    /** A coloured mark ([colour]: the accent, a group's colour) on [bar]: itself when it stands out (3:1), else the text's colour [ink]. */
    fun onBar(colour: Int, bar: Int, ink: Int): Int = if (Contrast.ratio(colour, bar) >= GRAPHIC) colour else ink

    /** [t] (0..1) of the way from [a] to [b], alpha too. */
    fun blend(a: Int, b: Int, t: Double): Int {
        if (t <= 0.0) return a
        if (t >= 1.0) return b
        fun channel(shift: Int) = ((((a ushr shift) and 0xFF) * (1 - t) + ((b ushr shift) and 0xFF) * t) + 0.5).toInt().coerceIn(0, 255)
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/**
 * The floating address bar's shape (Settings → Look & screen → Address bar style): a fully rounded
 * pill in the bar's room, a little in from the screen's sides and edge. Sizes in pixels; margins as
 * left, top, right, bottom.
 */
internal object BarShape {
    /** The floating bar's room is this much taller (dp) than the full-width bar, so the pill keeps the buttons' size. */
    const val EXTRA_DP = 6

    /** From the screen's sides (dp). */
    const val SIDE_DP = 8

    /** From the screen's top or bottom edge (dp). */
    const val EDGE_DP = 6

    /** Between the pill and the page, or the group strip's pill (dp). */
    const val GAP_DP = 4

    /** The pill in the bar's room: [edge] from the screen's edge, [gap] from the page, [side] from the sides. */
    fun pillMargins(atBottom: Boolean, side: Int, edge: Int, gap: Int): IntArray =
        if (atBottom) intArrayOf(side, gap, side, edge) else intArrayOf(side, edge, side, gap)

    /** The group strip's pill in its room, on the page's side of the bar: [gap] from the page beyond it. */
    fun stripMargins(atBottom: Boolean, side: Int, gap: Int): IntArray =
        if (atBottom) intArrayOf(side, gap, side, 0) else intArrayOf(side, 0, side, gap)

    /** How tall a pill with [margins] is in a room [room] tall. */
    fun pillHeight(room: Int, margins: IntArray): Int = (room - margins[1] - margins[3]).coerceAtLeast(0)

    /**
     * The loading line along the pill's edge on the page's side, clear of its round ends (where it
     * would stick out of the pill): margins in the bar's room, the line at the room's page side.
     */
    fun progressMargins(atBottom: Boolean, room: Int, margins: IntArray): IntArray {
        val inset = pillHeight(room, margins) / 2
        return if (atBottom) intArrayOf(margins[0] + inset, margins[1], margins[2] + inset, 0)
        else intArrayOf(margins[0] + inset, 0, margins[2] + inset, margins[3])
    }
}

/** Reads a colour as CSS writes it: what a page's theme-color holds. */
internal object CssColour {
    /** Longer than this, it's no colour Swift reads. */
    const val MAX_LENGTH = 100

    private val NUMBER = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)(e[+-]?\\d+)?")
    private val SPACES = Regex("\\s+")
    private const val HEX = "0123456789abcdef"

    /**
     * [text] as ARGB: #rgb, #rgba, #rrggbb, #rrggbbaa, rgb()/rgba() and hsl()/hsla() (with commas, or
     * spaces and "/ alpha"), and CSS's colour names ("white", "rebeccapurple", "transparent"). Null
     * for anything else: other colour functions, "currentcolor", rubbish.
     */
    fun parse(text: String?): Int? {
        val s = text?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s.length > MAX_LENGTH) return null
        if (s[0] == '#') return hex(s.substring(1))
        NAMES[s]?.let { return it }
        val open = s.indexOf('(')
        if (open <= 0 || s.last() != ')') return null
        val args = arguments(s.substring(open + 1, s.length - 1)) ?: return null
        return when (s.substring(0, open).trim()) {
            "rgb", "rgba" -> rgb(args)
            "hsl", "hsla" -> hsl(args)
            else -> null
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun hex(h: String): Int? {
        if (h.any { it !in HEX }) return null
        fun d(i: Int) = HEX.indexOf(h[i])
        return when (h.length) {
            3, 4 -> argb(if (h.length == 4) d(3) * 17 else 255, d(0) * 17, d(1) * 17, d(2) * 17)
            6, 8 -> argb(if (h.length == 8) d(6) * 16 + d(7) else 255, d(0) * 16 + d(1), d(2) * 16 + d(3), d(4) * 16 + d(5))
            else -> null
        }
    }

    /** What is in the brackets: three parts and maybe an alpha, from "1, 2, 3, 0.5" or "1 2 3 / 0.5". */
    private fun arguments(inner: String): List<String>? {
        val parts = if (inner.contains(',')) {
            if (inner.contains('/')) return null
            inner.split(',').map { it.trim() }
        } else {
            val halves = inner.split('/')
            if (halves.size > 2) return null
            val main = halves[0].trim().split(SPACES).filter { it.isNotEmpty() }
            if (main.size != 3) return null
            if (halves.size == 2) main + halves[1].trim() else main
        }
        return parts.takeIf { (it.size == 3 || it.size == 4) && it.none { p -> p.isEmpty() } }
    }

    private fun number(p: String): Double? = if (NUMBER.matches(p)) p.toDouble() else null

    private fun round(v: Double) = (v + 0.5).toInt()

    /** A red, green or blue part: 0–255, or a percentage; "none" is 0 (CSS Color 4). */
    private fun channel(p: String): Int? {
        val v = when {
            p == "none" -> 0.0
            p.endsWith('%') -> number(p.dropLast(1))?.let { it * 255 / 100 }
            else -> number(p)
        } ?: return null
        return round(v.coerceIn(0.0, 255.0))
    }

    /** The alpha: 0–1 or a percentage, as 0–255. */
    private fun alpha(p: String): Int? {
        val v = when {
            p == "none" -> 0.0
            p.endsWith('%') -> number(p.dropLast(1))?.div(100)
            else -> number(p)
        } ?: return null
        return round(v.coerceIn(0.0, 1.0) * 255)
    }

    /** Saturation or lightness: a percentage (or, as CSS Color 4 allows, a plain number of percent), as 0–1. */
    private fun percent(p: String): Double? {
        val v = when {
            p == "none" -> 0.0
            p.endsWith('%') -> number(p.dropLast(1))
            else -> number(p)
        } ?: return null
        return (v / 100).coerceIn(0.0, 1.0)
    }

    /** A hue in degrees (0–360), from a number or an angle in deg, grad, rad or turn. */
    private fun hue(p: String): Double? {
        if (p == "none") return 0.0
        val (num, factor) = when {
            p.endsWith("deg") -> p.dropLast(3) to 1.0
            p.endsWith("grad") -> p.dropLast(4) to 0.9
            p.endsWith("rad") -> p.dropLast(3) to 180 / Math.PI
            p.endsWith("turn") -> p.dropLast(4) to 360.0
            else -> p to 1.0
        }
        val v = number(num)?.times(factor) ?: return null
        return ((v % 360) + 360) % 360
    }

    private fun rgb(a: List<String>): Int? {
        val r = channel(a[0]) ?: return null
        val g = channel(a[1]) ?: return null
        val b = channel(a[2]) ?: return null
        val alpha = if (a.size == 4) alpha(a[3]) ?: return null else 255
        return argb(alpha, r, g, b)
    }

    private fun hsl(a: List<String>): Int? {
        val h = hue(a[0]) ?: return null
        val s = percent(a[1]) ?: return null
        val l = percent(a[2]) ?: return null
        val alpha = if (a.size == 4) alpha(a[3]) ?: return null else 255
        // CSS's own conversion (CSS Color 4, hslToRgb).
        fun f(n: Int): Double {
            val k = (n + h / 30) % 12
            return l - s * minOf(l, 1 - l) * maxOf(-1.0, minOf(k - 3, 9 - k, 1.0))
        }
        return argb(alpha, round(f(0) * 255), round(f(8) * 255), round(f(4) * 255))
    }

    /** CSS's colour names (CSS Color 4), made the first time a name is asked for. */
    private val NAMES: Map<String, Int> by lazy {
        val list = ("aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc " +
            "bisque ffe4c4 black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a " +
            "burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed " +
            "cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b " +
            "darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b " +
            "darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a " +
            "darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 " +
            "darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff " +
            "firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff " +
            "gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 " +
            "hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa " +
            "lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 " +
            "lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 " +
            "lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 " +
            "lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 " +
            "magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 " +
            "mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a " +
            "mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 " +
            "moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 " +
            "orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee " +
            "palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd " +
            "powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 " +
            "saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d " +
            "silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa " +
            "springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 " +
            "turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32")
            .split(' ')
        val map = HashMap<String, Int>(list.size)
        for (i in 0 until list.size - 1 step 2) map[list[i]] = 0xFF000000.toInt() or list[i + 1].toInt(16)
        map["transparent"] = 0
        map
    }
}
