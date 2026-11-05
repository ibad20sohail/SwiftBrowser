package com.swiftbrowser.ui

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.hypot

/**
 * 3.0.4: the music screen in the colours of the song's cover, as Android's own media player takes
 * them: from the cover's main colour (PictureColours), a soft background (light: tone 95 with a
 * little of the colour; dark: tone 10), and a strong tone of it for the play button and the seek bar.
 * Checked for contrast with Contrast: the controls 3:1 on the background (WCAG's measure for parts
 * you work), the play button's icon and all text 4.5:1. A grey cover, or none, keeps Swift's colours.
 *
 * Worked out off the main thread ([of]) and kept per song ([remember]), so a song played again, or
 * the screen turned, shows its colours at once. [scheme] is plain maths, tested on the PC
 * (CoverColoursTest).
 */
internal object CoverColours {
    /** The music screen's colours for one cover. */
    class Scheme(
        val background: Int,
        /** The play button, the part played, the handle, shuffle and repeat while on. */
        val accent: Int,
        /** The play button's icon. */
        val onAccent: Int,
        /** The rest of the seek bar. */
        val track: Int,
        val text: Int,
        val secondary: Int,
    )

    /** What the controls need against the background (WCAG 2.1, non-text contrast). */
    const val CONTROLS = 3.0

    /** The tones (CIELAB lightness) and amounts of colour (chroma) used, light and dark. */
    private const val LIGHT_BG = 95.0
    private const val DARK_BG = 10.0
    private const val LIGHT_BG_CHROMA = 7.0
    private const val DARK_BG_CHROMA = 10.0
    private const val LIGHT_ACCENT = 40.0
    private const val DARK_ACCENT = 80.0

    /** An accent at least this colourful, so a dull cover still gives a clear one (Material's own minimum is about as much). */
    private const val ACCENT_CHROMA = 40.0
    private const val LIGHT_TRACK = 87.0
    private const val DARK_TRACK = 30.0

    /**
     * The colours for a cover whose main colour is [seed], in light or dark ([night]); [text] and
     * [secondary] are the screen's own text colours, kept unless they would be hard to read.
     */
    fun scheme(seed: Int, night: Boolean, text: Int, secondary: Int): Scheme {
        val (_, a, b) = Tones.lab(seed)
        val chroma = hypot(a, b)
        val toward = if (night) Color.WHITE else Color.BLACK
        val background = Tones.tone(seed, if (night) DARK_BG else LIGHT_BG, minOf(chroma, if (night) DARK_BG_CHROMA else LIGHT_BG_CHROMA))
        val accent = Contrast.readable(Tones.tone(seed, if (night) DARK_ACCENT else LIGHT_ACCENT, maxOf(chroma, ACCENT_CHROMA)), listOf(background), CONTROLS, toward)
        // On the accent: white on light's deep tone, a deep tone of the colour on dark's light one.
        val on = if (night) Tones.tone(seed, 20.0, maxOf(chroma, ACCENT_CHROMA)) else Color.WHITE
        val onAccent = Contrast.readable(on, listOf(accent), Contrast.TEXT, if (night) Color.BLACK else Color.WHITE)
        val track = Tones.tone(seed, if (night) DARK_TRACK else LIGHT_TRACK, chroma.coerceIn(16.0, 24.0))
        return Scheme(
            background, accent, onAccent, track,
            Contrast.readable(text, listOf(background), Contrast.TEXT, toward),
            Contrast.readable(secondary, listOf(background), Contrast.TEXT, toward),
        )
    }

    /**
     * The scheme for [cover] (null: a grey picture, keep Swift's colours). Reads the picture's
     * pixels: not on the main thread.
     */
    fun of(cover: Bitmap, night: Boolean, text: Int, secondary: Int): Scheme? =
        PictureColours.seeds(cover, 1).firstOrNull()?.let { scheme(it, night, text, secondary) }

    /** Kept for a grey cover (no colours of its own), so it isn't looked at again. */
    val NONE = Scheme(0, 0, 0, 0, 0, 0)

    private val kept = object : LinkedHashMap<String, Scheme>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Scheme>?): Boolean = size > 64
    }

    /** What was worked out for [key] (a song and light or dark): its scheme, [NONE], or null when not yet. */
    @Synchronized
    fun kept(key: String): Scheme? = kept[key]

    @Synchronized
    fun remember(key: String, scheme: Scheme?) {
        kept[key] = scheme ?: NONE
    }
}
