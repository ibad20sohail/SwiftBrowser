package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.L

/**
 * 3.0.4: Swift's own font, Google Sans Flex: Google's 2025 font, free under the SIL Open Font
 * License since November 2025, for all of Swift's own text (web pages keep their own fonts).
 *
 * Android 7 can't use a variable font's axes, so Swift carries fixed cuts of it (made with fontTools,
 * with the Latin and Vietnamese letters, punctuation and common symbols; letters of other scripts
 * come from the phone's fonts, as Android falls back for anything a font doesn't have). 3.0.5: each
 * cut made for the size it is shown at (optical sizes, see [TextCuts]): the everyday regular, medium
 * and bold (optical size 14), a more open regular and medium for small text (12) and a tighter bold
 * for big titles (30). The smaller sizes are drawn wider: those cuts are narrowed to the width of the
 * one cut before (optical size 18), so labels don't get longer.
 *
 * Most of Swift's text gets its look from [Ui.Type], which asks here. Android's and Material's own
 * parts (dialog buttons, chips, menus, text without a style) ask the system for "sans-serif" and
 * "sans-serif-medium", or for its default: at start Swift puts its font in their place in
 * Android's table of system fonts, for this app only. That needs Android's own hidden fields,
 * which Android 7 allows; on a newer Android, or if it fails, only Swift's text styles change.
 * 3.0.5: always on (it was a setting).
 */
object AppFont {
    /** Swift's font with its bold; null only if it couldn't be read (the phone's font then). */
    var regular: Typeface? = null
        private set

    var medium: Typeface? = null
        private set

    var bold: Typeface? = null
        private set

    // 3.0.5: the cuts for small text and for big titles (see cut()).
    private var smallRegular: Typeface? = null
    private var smallMedium: Typeface? = null
    private var displayBold: Typeface? = null

    // 3.0.5: whether Android's own default fonts were replaced (see replaceSystemFonts).
    private var defaultsReplaced = false

    /** 3.0.5: whether Swift's own font is in use (its web pages follow this, as its screens do). */
    val active: Boolean get() = regular != null

    /** Called once at start, before any screen is made. */
    fun install(ctx: Context) {
        try {
            val family = ResourcesCompat.getFont(ctx, R.font.swift_sans) ?: return
            val mid = ResourcesCompat.getFont(ctx, R.font.swift_sans_medium) ?: return
            val heavy = Typeface.create(family, Typeface.BOLD)
            regular = family
            medium = mid
            bold = heavy
            // 3.0.5: if one of these can't be read, its text stays in the everyday cut (see cut()).
            fun optional(id: Int) = runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull()
            smallRegular = optional(R.font.swift_sans_small_regular)
            smallMedium = optional(R.font.swift_sans_small_medium)
            displayBold = optional(R.font.swift_sans_display_bold)
            if (Build.VERSION.SDK_INT < 28) replaceSystemFonts(family, mid, heavy)
        } catch (e: Exception) {
            L.w("Couldn't use Swift's font", e)
        } catch (e: OutOfMemoryError) {
            L.w("Couldn't use Swift's font", e)
        }
    }

    // Android's hidden fields, used only below Android 9 (the caller checks), where apps may use them.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi")
    @Suppress("UNCHECKED_CAST")
    private fun replaceSystemFonts(family: Typeface, mid: Typeface, heavy: Typeface) {
        val c = Typeface::class.java
        try {
            // The named fonts: what "sans-serif" (and its bold) and "sans-serif-medium" mean in this app.
            val mapField = c.getDeclaredField("sSystemFontMap").apply { isAccessible = true }
            val map = HashMap(mapField.get(null) as Map<String, Typeface>)
            map["sans-serif"] = family
            map["sans-serif-medium"] = mid
            // 3.0.5: a name of Swift's own for the small cut, so small text in the layout files can
            // ask for it ("swift-small"); if Swift's font couldn't be read the name isn't here, and that text
            // asks the system for a font it doesn't know: the phone's font.
            smallRegular?.let { map[SMALL_REGULAR_NAME] = it }
            mapField.set(null, map)
            // The default fonts: Typeface.DEFAULT and the like, and what text without a font uses.
            setStatic(c, "DEFAULT", family)
            setStatic(c, "SANS_SERIF", family)
            setStatic(c, "DEFAULT_BOLD", heavy)
            (c.getDeclaredField("sDefaults").apply { isAccessible = true }.get(null) as? Array<Typeface?>)?.let { d ->
                if (d.size >= 4) {
                    d[0] = family
                    d[1] = heavy
                    d[2] = Typeface.create(family, Typeface.ITALIC)
                    d[3] = Typeface.create(family, Typeface.BOLD_ITALIC)
                }
            }
            c.getDeclaredMethod("setDefault", Typeface::class.java).apply { isAccessible = true }.invoke(null, family)
            defaultsReplaced = true
        } catch (e: Exception) {
            // Swift's own text styles still use the font; Android's parts keep the phone's font.
            L.w("Couldn't make Swift's font the app's default", e)
        }
    }

    private fun setStatic(c: Class<*>, name: String, value: Typeface) {
        c.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    /**
     * 3.0.5: the cut of Swift's font for text of [sp] in [weight] (see [TextCuts]), or null when the
     * phone's own font is used. A small or big cut that couldn't be read gives the everyday one.
     */
    fun cut(sp: Float, weight: Ui.Weight): Typeface? = when (TextCuts.of(sp, weight)) {
        TextCuts.Cut.SMALL -> (if (weight == Ui.Weight.MEDIUM) smallMedium else smallRegular) ?: everyday(weight)
        TextCuts.Cut.DISPLAY -> displayBold ?: everyday(weight)
        TextCuts.Cut.EVERYDAY -> everyday(weight)
    }

    private fun everyday(weight: Ui.Weight): Typeface? = when (weight) {
        Ui.Weight.REGULAR -> regular
        Ui.Weight.MEDIUM -> medium
        Ui.Weight.BOLD -> bold
    }

    /**
     * 3.0.5: the cut for text of [sp] with no font of its own (Ui.size's keepFont): only small text
     * differs from what Android gives it, and only where Swift's font is the default of all text.
     */
    fun plainCut(sp: Float): Typeface? =
        if (defaultsReplaced && TextCuts.of(sp, Ui.Weight.REGULAR) == TextCuts.Cut.SMALL) smallRegular else null

    private const val SMALL_REGULAR_NAME = "swift-small"
}

/**
 * 3.0.5: which cut of Swift's font text is drawn with, by its size: letters cut for their size, as
 * the font is drawn to be used (its optical size axis). Small text gets the more open cut (captions
 * and the smallest labels, up to 12.5 sp, in regular or medium: there is no small bold), big bold
 * text (display and headline, from 23 sp) the tighter one; everything between, and bold text below
 * them, the everyday one. Plain Kotlin, so it's tested on the PC (TextCutsTest).
 */
internal object TextCuts {
    enum class Cut { SMALL, EVERYDAY, DISPLAY }

    const val SMALL_UP_TO = 12.5f
    const val DISPLAY_FROM = 23f

    fun of(sp: Float, weight: Ui.Weight): Cut = when {
        sp <= SMALL_UP_TO && weight != Ui.Weight.BOLD -> Cut.SMALL
        sp >= DISPLAY_FROM && weight == Ui.Weight.BOLD -> Cut.DISPLAY
        else -> Cut.EVERYDAY
    }
}
