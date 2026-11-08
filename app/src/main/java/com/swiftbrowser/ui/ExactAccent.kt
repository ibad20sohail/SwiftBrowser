package com.swiftbrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.AbsListView
import android.widget.EdgeEffect
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.swiftbrowser.core.L
import java.lang.reflect.Field

/**
 * 3.0.5: the parts of Android's own widgets that take their colour from the theme, in the accent
 * chosen. A theme can only hold fixed colours (the nearest of the six presets), so without this an
 * amber or your own accent had a salmon cursor and glow. Android 10 has methods for all of these;
 * Android 7 to 9, Swift's phones, only the widgets' hidden fields, read here. If one of them isn't
 * there the part simply keeps the theme's colour.
 */
internal object ExactAccent {
    /** The glow's see-through part, as Android's own EdgeEffect has it (its accent is only its colour). */
    private const val GLOW_ALPHA = 0x33000000

    /** Android's selection highlight: the accent at 40%. */
    private const val HIGHLIGHT_ALPHA = 0x66000000

    // Looked up once; a field that isn't there stays null. Below Android 10 only (the caller checks).
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "PrivateApi")
    private object Hidden {
        private fun field(owner: Class<*>, name: String): Field? = try {
            owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: Exception) {
            null
        }

        val editor = field(TextView::class.java, "mEditor")
        val cursorRes = field(TextView::class.java, "mCursorDrawableRes")
        val handleLeftRes = field(TextView::class.java, "mTextSelectHandleLeftRes")
        val handleRightRes = field(TextView::class.java, "mTextSelectHandleRightRes")
        val handleCentreRes = field(TextView::class.java, "mTextSelectHandleRes")

        private val editorClass = try {
            Class.forName("android.widget.Editor")
        } catch (_: Exception) {
            null
        }
        val cursors = editorClass?.let { field(it, "mCursorDrawable") } // Android 7 and 8: two, for text of two directions
        val cursor = editorClass?.let { field(it, "mDrawableForCursor") } // Android 9
        val handleLeft = editorClass?.let { field(it, "mSelectHandleLeft") }
        val handleRight = editorClass?.let { field(it, "mSelectHandleRight") }
        val handleCentre = editorClass?.let { field(it, "mSelectHandleCenter") }

        val scrollTop = field(ScrollView::class.java, "mEdgeGlowTop")
        val scrollBottom = field(ScrollView::class.java, "mEdgeGlowBottom")
        val sideLeft = field(HorizontalScrollView::class.java, "mEdgeGlowLeft")
        val sideRight = field(HorizontalScrollView::class.java, "mEdgeGlowRight")
        val listTop = field(AbsListView::class.java, "mEdgeGlowTop")
        val listBottom = field(AbsListView::class.java, "mEdgeGlowBottom")
    }

    private fun tinted(d: Drawable, color: Int): Drawable = d.mutate().also { it.setTint(color) }

    /** The text cursor and selection handles of [tv] (and what is selected) in [color]. Once, when made. */
    fun text(tv: TextView, color: Int) {
        tv.highlightColor = (color and 0x00FFFFFF) or HIGHLIGHT_ALPHA
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tv.textCursorDrawable?.let { tv.setTextCursorDrawable(tinted(it, color)) }
                tv.textSelectHandleLeft?.let { tv.setTextSelectHandleLeft(tinted(it, color)) }
                tv.textSelectHandleRight?.let { tv.setTextSelectHandleRight(tinted(it, color)) }
                tv.textSelectHandle?.let { tv.setTextSelectHandle(tinted(it, color)) }
                return
            }
            val editor = Hidden.editor?.get(tv) ?: return
            val ctx = tv.context
            val cursorRes = Hidden.cursorRes?.getInt(tv) ?: 0
            if (cursorRes != 0) {
                val cursors = Hidden.cursors?.get(editor) as? Array<*>
                if (cursors != null) {
                    @Suppress("UNCHECKED_CAST")
                    val slots = cursors as Array<Drawable?>
                    slots[0] = ctx.getDrawable(cursorRes)?.let { tinted(it, color) }
                    slots[1] = ctx.getDrawable(cursorRes)?.let { tinted(it, color) }
                } else {
                    Hidden.cursor?.set(editor, ctx.getDrawable(cursorRes)?.let { tinted(it, color) })
                }
            }
            setHandle(ctx, tv, editor, Hidden.handleLeftRes, Hidden.handleLeft, color)
            setHandle(ctx, tv, editor, Hidden.handleRightRes, Hidden.handleRight, color)
            setHandle(ctx, tv, editor, Hidden.handleCentreRes, Hidden.handleCentre, color)
        } catch (e: Exception) {
            L.d("Couldn't colour a text cursor: $e")
        }
    }

    private fun setHandle(ctx: Context, tv: TextView, editor: Any, res: Field?, slot: Field?, color: Int) {
        val drawable = res?.getInt(tv)?.takeIf { it != 0 }?.let { ctx.getDrawable(it) } ?: return
        slot?.set(editor, tinted(drawable, color))
    }

    /** RecyclerViews make their glows when needed, so every one can share this. */
    private val glowFactory = object : RecyclerView.EdgeEffectFactory() {
        override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect =
            EdgeEffect(view.context).apply { color = glowColour(Ui.accent(view.context)) }
    }

    private fun glowColour(color: Int): Int = (color and 0x00FFFFFF) or GLOW_ALPHA

    /** The glow at the ends of [v] (a list or scroll view) in [color]; none to colour when it doesn't have one. */
    fun glow(v: View, color: Int) {
        try {
            if (v is RecyclerView) {
                if (v.overScrollMode != View.OVER_SCROLL_NEVER) v.edgeEffectFactory = glowFactory
                return
            }
            val c = glowColour(color)
            if (Build.VERSION.SDK_INT >= 29) {
                when (v) {
                    is ScrollView -> v.setEdgeEffectColor(c)
                    is HorizontalScrollView -> v.setEdgeEffectColor(c)
                    is AbsListView -> v.setEdgeEffectColor(c)
                }
                return
            }
            when (v) {
                is ScrollView -> paintGlows(v, c, Hidden.scrollTop, Hidden.scrollBottom)
                is HorizontalScrollView -> paintGlows(v, c, Hidden.sideLeft, Hidden.sideRight)
                is AbsListView -> paintGlows(v, c, Hidden.listTop, Hidden.listBottom)
            }
        } catch (e: Exception) {
            L.d("Couldn't colour a list's glow: $e")
        }
    }

    private fun paintGlows(v: View, c: Int, one: Field?, other: Field?) {
        (one?.get(v) as? EdgeEffect)?.color = c
        (other?.get(v) as? EdgeEffect)?.color = c
    }
}
