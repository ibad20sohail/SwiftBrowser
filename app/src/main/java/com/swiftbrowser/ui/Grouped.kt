package com.swiftbrowser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.swiftbrowser.R

/*
 * 3.0.4: grouped rows, as Android 16's own settings and lists have them. The rows of a group are
 * separate rounded pieces in the card colour, 2 dp apart, with no lines between them: the group's
 * first has large top corners, its last large bottom corners, the ones between small corners, and
 * a row alone is large all round. [RowCorners] says which corners a row gets (plain maths, tested
 * on the PC), [Piece] is a row's background with them (its touch ripple kept inside them),
 * [GroupLayout] keeps them right in Settings as rows show and hide, and [GroupedRows] does it for a
 * list's rows as they are bound (History, Bookmarks, Downloads).
 */

/** Which corners a row of a group gets, and the gap above it, by where it is (3.0.4). */
object RowCorners {
    /** Alone in its group: large corners all round. */
    const val SINGLE = 0

    /** The group's first: large top corners, small bottom ones. */
    const val FIRST = 1

    /** Between two others: small corners all round. */
    const val MIDDLE = 2

    /** The group's last: small top corners, large bottom ones. */
    const val LAST = 3

    /** No gap above: the list's first row, or the first under a heading. */
    const val GAP_NONE = 0

    /** The small gap between two rows of a group. */
    const val GAP_ROW = 1

    /** A group straight after another one, with no heading between: a wider gap. */
    const val GAP_GROUP = 2

    fun of(first: Boolean, last: Boolean): Int = when {
        first && last -> SINGLE
        first -> FIRST
        last -> LAST
        else -> MIDDLE
    }

    /**
     * The corners of item [i] of [count] in a list where [group] says which group each item is in:
     * rows of the same group next to each other go together, and null (a heading, not a row) ends
     * a group.
     */
    fun at(i: Int, count: Int, group: (Int) -> Any?): Int {
        val g = group(i)
        val first = i == 0 || group(i - 1) != g
        val last = i == count - 1 || group(i + 1) != g
        return of(first, last)
    }

    /** The gap above item [i] (one of GAP_…): the same rule as [at]. A heading (no row) has none of its own. */
    fun gapAt(i: Int, group: (Int) -> Any?): Int {
        if (i == 0) return GAP_NONE
        val g = group(i) ?: return GAP_NONE
        val before = group(i - 1) ?: return GAP_NONE
        return if (before == g) GAP_ROW else GAP_GROUP
    }

    /**
     * The corner radii of [kind] as a GradientDrawable takes them (top-left, top-right,
     * bottom-right, bottom-left; x and y of each): [large] on the group's outside, [small] inside it.
     */
    fun radii(kind: Int, large: Float, small: Float): FloatArray {
        val top = if (kind == SINGLE || kind == FIRST) large else small
        val bottom = if (kind == SINGLE || kind == LAST) large else small
        return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
    }
}

/**
 * 3.0.4: one row of a group drawn as a rounded piece of its own: the card colour and, for a row
 * that can be tapped, the touch ripple inside the same corners. Which corners is set by where the
 * row is ([set]); it costs nothing when they stay the same, so lists set it on every bind.
 */
class Piece private constructor(private val fill: GradientDrawable, private val mask: GradientDrawable?, private val sizes: Sizes) {
    /** Its corners as drawn now (RowCorners), -1 before they were first set. */
    var kind = -1
        private set

    /** The radii of its corners as drawn now (for what is drawn behind it: a row being swiped away). */
    val radii: FloatArray get() = sizes.arrays[kind.coerceAtLeast(0)]

    fun set(kind: Int) {
        if (kind == this.kind) return
        this.kind = kind
        shape(fill, kind)
        mask?.let { shape(it, kind) }
    }

    private fun shape(d: GradientDrawable, kind: Int) {
        // Corners all the same (a row between others, one alone) are one radius, which Android draws
        // the quickest way; only a group's first and last have each corner its own.
        when (kind) {
            RowCorners.MIDDLE -> d.cornerRadius = sizes.small
            RowCorners.SINGLE -> d.cornerRadius = sizes.large
            else -> d.cornerRadii = sizes.arrays[kind]
        }
    }

    /** The two radii in pixels, and each kind's corners made from them once (shared: never changed). */
    private class Sizes(val large: Float, val small: Float) {
        val arrays = Array(4) { RowCorners.radii(it, large, small) }
    }

    companion object {
        private var sizes: Sizes? = null
        private var sizesDensity = 0f

        private fun sizes(ctx: Context): Sizes {
            val density = ctx.resources.displayMetrics.density
            sizes?.takeIf { sizesDensity == density }?.let { return it }
            return Sizes(Ui.dpf(ctx, Ui.R_LARGE.toFloat()), Ui.dpf(ctx, Ui.R_XSMALL.toFloat())).also {
                sizes = it
                sizesDensity = density
            }
        }

        /**
         * Makes [v]'s background a piece, in [color] (the card colour unless said), with a ripple when
         * [tappable]; alone in its group until told otherwise.
         */
        fun attach(v: View, tappable: Boolean, color: Int = Palette.color(v.context, R.color.card_bg)): Piece {
            val ctx = v.context
            val fill = GradientDrawable().apply { setColor(color) }
            var mask: GradientDrawable? = null
            v.background = if (tappable) {
                // Ui's ripple, with its mask shaped like the piece from now on.
                Ui.ripple(ctx, fill, Ui.R_XSMALL).also { mask = it.findDrawableByLayerId(android.R.id.mask) as? GradientDrawable }
            } else {
                fill
            }
            val p = Piece(fill, mask, sizes(ctx))
            p.set(RowCorners.SINGLE)
            v.setTag(R.id.group_piece, p)
            return p
        }

        /** [v]'s piece, if it has one. */
        fun of(v: View): Piece? = v.getTag(R.id.group_piece) as? Piece
    }
}

/**
 * 3.0.4: a group of rows (Settings), each a piece of its own, one under another 2 dp apart. Their
 * corners and gaps are worked out again whenever it is measured, so a row that shows or hides (the
 * Advanced rows, a row shown only while something is on) leaves its neighbours with the right ones.
 */
class GroupLayout(ctx: Context) : LinearLayout(ctx) {
    private val gap = Ui.dp(ctx, 2)
    private val pieces = ArrayList<View>()

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        pieces.clear()
        collect(this)
        for (i in pieces.indices) {
            val v = pieces[i]
            Piece.of(v)?.set(RowCorners.of(i == 0, i == pieces.lastIndex))
            val lp = v.layoutParams as? MarginLayoutParams ?: continue
            val top = if (i == 0) 0 else gap
            if (lp.topMargin != top) {
                lp.topMargin = top
                // A row of a run: the run measures again too (it might keep its last measure otherwise).
                if (v.parent !== this) (v.parent as? View)?.forceLayout()
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The rows on show, in order, those of runs among them. */
    private fun collect(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (c is Run) collect(c) else pieces += c
        }
    }

    /**
     * Rows made and made again together (the VPN's locations) inside a group: each of them is a piece
     * of the group around it, as if it were there itself; an empty run is nothing.
     */
    class Run(ctx: Context) : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
        }
    }
}

/** 3.0.4: grouped rows in a RecyclerView: each row's piece is made once, its corners and gap set as it is bound. */
object GroupedRows {
    /** A new row of a grouped list: its background is a piece from now on (it keeps it when recycled). */
    fun prepare(v: View): Piece = Piece.attach(v, tappable = true)

    /**
     * The row [v] bound at [position] of [count]: its corners and the gap above it, by [group] (see
     * RowCorners.at). Nothing changes when they are the same as the row had.
     */
    fun place(v: View, position: Int, count: Int, group: (Int) -> Any?) {
        Piece.of(v)?.set(RowCorners.at(position, count, group))
        val ctx = v.context
        val gap = when (RowCorners.gapAt(position, group)) {
            RowCorners.GAP_ROW -> Ui.dp(ctx, 2)
            RowCorners.GAP_GROUP -> Ui.dp(ctx, Ui.L)
            else -> 0
        }
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != gap) {
            lp.topMargin = gap
            v.layoutParams = lp
        }
    }
}
