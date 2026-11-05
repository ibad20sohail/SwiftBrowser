package com.swiftbrowser.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L

/**
 * 3.0.4: Swift's own font, Google Sans Flex: Google's 2025 font, free under the SIL Open Font
 * License since November 2025, for all of Swift's own text (web pages keep their own fonts).
 *
 * Android 7 can't use a variable font's axes, so Swift carries three fixed weights of it, regular,
 * medium and bold (made with fontTools at Google Fonts' default optical size, with the Latin and
 * Vietnamese letters, punctuation and common symbols; letters of other scripts come from the
 * phone's fonts, as Android falls back for anything a font doesn't have).
 *
 * Most of Swift's text gets its look from [Ui.Type], which asks here. Android's and Material's own
 * parts (dialog buttons, chips, menus, text without a style) ask the system for "sans-serif" and
 * "sans-serif-medium", or for its default: at start Swift puts its font in their place in
 * Android's table of system fonts, for this app only. That needs Android's own hidden fields,
 * which Android 7 allows; on a newer Android, or if it fails, only Swift's text styles change.
 * Settings → Look & screen → Swift's font off: the phone's own font (after a restart).
 */
object AppFont {
    /** Swift's font with its bold, or null when the phone's own font is used. */
    var regular: Typeface? = null
        private set

    var medium: Typeface? = null
        private set

    var bold: Typeface? = null
        private set

    /** Called once at start, before any screen is made. */
    fun install(ctx: Context) {
        if (!SwiftApp.prefs.swiftFont) return
        try {
            val family = ResourcesCompat.getFont(ctx, R.font.swift_sans) ?: return
            val mid = ResourcesCompat.getFont(ctx, R.font.swift_sans_medium) ?: return
            val heavy = Typeface.create(family, Typeface.BOLD)
            regular = family
            medium = mid
            bold = heavy
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
        } catch (e: Exception) {
            // Swift's own text styles still use the font; Android's parts keep the phone's font.
            L.w("Couldn't make Swift's font the app's default", e)
        }
    }

    private fun setStatic(c: Class<*>, name: String, value: Typeface) {
        c.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
}
