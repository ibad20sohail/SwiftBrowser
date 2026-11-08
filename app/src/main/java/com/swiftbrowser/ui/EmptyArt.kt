package com.swiftbrowser.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.swiftbrowser.R

/**
 * 3.0.5: the drawings of empty screens (nothing in History, Bookmarks, Downloads, a folder, the tab
 * grid), each its own, instead of one icon in a circle. Flat shapes in two tones, each tone a vector
 * drawable of its own (res/drawable, art_…_soft and art_…_ink) tinted at runtime, so a drawing
 * follows any accent, light or dark. Drawn once and held still: nothing moves, nothing is redrawn.
 */
enum class EmptyArt(private val soft: Int, private val ink: Int) {
    HISTORY(R.drawable.art_history_soft, R.drawable.art_history_ink),
    BOOKMARKS(R.drawable.art_bookmarks_soft, R.drawable.art_bookmarks_ink),
    DOWNLOADS(R.drawable.art_downloads_soft, R.drawable.art_downloads_ink),
    FOLDER(R.drawable.art_folder_soft, R.drawable.art_folder_ink),
    TABS(R.drawable.art_tabs_soft, R.drawable.art_tabs_ink);

    /** The drawing in the two tones given: the accent's soft colour and the colour on it, unless other tones are needed (incognito). */
    fun drawable(ctx: Context, softTone: Int = Ui.accentContainer(ctx), inkTone: Int = Ui.onAccentContainer(ctx)): Drawable =
        LayerDrawable(arrayOf(Ui.icon(ctx, soft, softTone)!!, Ui.icon(ctx, ink, inkTone)!!))

    /** The drawing as a view, ready for an empty screen. */
    fun view(ctx: Context): ImageView = ImageView(ctx).apply {
        setImageDrawable(drawable(ctx))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = Ui.dp(ctx, sizeDp(ctx))
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    companion object {
        /** About 120 dp; a turned phone (little height) keeps the room for the text under it. */
        fun sizeDp(ctx: Context) = if (ctx.resources.configuration.screenHeightDp < 480) 88 else 120
    }
}
