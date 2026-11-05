package com.swiftbrowser.files

/**
 * 3.0.4: two folders side by side in Files, on a wide screen turned sideways. The plain rules, apart
 * from Android so they are tested on the PC: when two show, how wide each side is, where each side's
 * place comes from when the screen is made again (turned, or Files opened again), and where copying
 * from one side goes.
 */
object TwoPanes {
    const val LEFT = 0
    const val RIGHT = 1

    /** The screen must be at least this wide (dp) for two folders; a phone turned sideways is about 640. */
    const val MIN_WIDTH_DP = 560

    /** Whether the button for two folders is offered: the screen turned sideways, and wide enough. */
    fun offered(landscape: Boolean, widthDp: Int) = landscape && widthDp >= MIN_WIDTH_DP

    /** Whether two folders show: turned on, and offered (held upright, one folder always shows). */
    fun shows(on: Boolean, landscape: Boolean, widthDp: Int) = on && offered(landscape, widthDp)

    /** The other side. */
    fun other(side: Int) = if (side == RIGHT) LEFT else RIGHT

    /**
     * Which way the other side is from [side] as the screen shows it, for "Copy to the right": the left
     * side's other side is on the right, the other way round in a right-to-left language ([rtl]),
     * where the row of sides is laid out from the right.
     */
    fun toward(side: Int, rtl: Boolean): String = if ((side == LEFT) != rtl) "right" else "left"

    /** A side read back (from a setting or a turned screen): anything but the right is the left. */
    fun side(saved: Int?) = if (saved == RIGHT) RIGHT else LEFT

    /** How wide one side's list is (dp): half the screen less the line between them, or the one list (at most [maxDp]). */
    fun paneWidthDp(screenDp: Int, two: Boolean, maxDp: Int): Int = if (two) (screenDp - 1) / 2 else minOf(screenDp, maxDp)

    /** Tiles about 112 dp wide (2.8), three to six across a side. */
    fun gridColumns(widthDp: Int) = (widthDp / 112).coerceIn(3, 6)

    /**
     * Where copying or moving from one side goes: the folder the other side shows ([otherPlace], a
     * path), but not its start page, a category or the recycle bin (places that aren't paths), nor
     * inside an archive. Null when the other side can't take files.
     */
    fun target(otherPlace: String?, otherInArchive: Boolean): String? =
        otherPlace?.takeIf { it.startsWith("/") && !otherInArchive }

    /**
     * What each side shows as the screen is made again: the side worked on last ([side]) shows its
     * [place]; with [two], the other side shows where it was left ([other]), or the first time the
     * same folder (a start page or a category: its own start page, null). With one folder only
     * [side]'s shows; the other's place is kept for when it comes back.
     */
    fun places(side: Int, place: String?, other: String?, two: Boolean): Array<String?> {
        val out = arrayOfNulls<String>(2)
        out[side] = place
        if (two) out[other(side)] = other ?: place?.takeIf { it.startsWith("/") }
        return out
    }
}
