package com.swiftbrowser.ui

/**
 * 3.0.5: corners that nest. A rounded thing inside another, a few dp in from its edge, looks even
 * all the way round when its corner is the outer corner less the gap between them (a picture with
 * the same corners as its card looks pinched at the corners). The answer is one of the app's four
 * corner sizes (Ui.R_…): the nearest, the smaller when two are as near, and R_XSMALL at the
 * smallest. Plain numbers, so it is tested on the PC (CornersTest).
 */
object Corners {
    private val SIZES = intArrayOf(Ui.R_XSMALL, Ui.R_SMALL, Ui.R_MEDIUM, Ui.R_LARGE)

    /** The corner (dp) of something [gap] dp inside a shape with corners of [outer] dp. */
    fun nested(outer: Int, gap: Int): Int {
        val wanted = (outer - gap).coerceAtLeast(Ui.R_XSMALL)
        return SIZES.minBy { kotlin.math.abs(it - wanted) }
    }
}
