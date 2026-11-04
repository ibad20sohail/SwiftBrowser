package com.swiftbrowser.files

/**
 * 3.0.3: sliding to select in Files. Once a hold has selected a file (or unselected it), sliding the
 * finger on does the same to every file from that one to the one under the finger; files it goes back
 * over return to how they were. Near the top or bottom of the list, the list scrolls on by itself.
 * The plain parts, apart from Android so they are tested on the PC.
 */
object DragSelect {
    /** What a slide's move changes: [reached] take the slide's choice, [left] go back to how they were. */
    class Step(val reached: List<Int>, val left: List<Int>)

    /**
     * The slide that began at [start] went from [from] to [to] (positions in the list): those now
     * between [start] and [to] that weren't before, and those it left behind. [start] itself stays as
     * the hold left it.
     */
    fun step(start: Int, from: Int, to: Int): Step {
        val before = span(start, from)
        val now = span(start, to)
        return Step(now.filter { it !in before }, before.filter { it !in now })
    }

    private fun span(a: Int, b: Int) = if (a <= b) a..b else b..a

    /**
     * How far the list scrolls by itself in one frame (px, up when below 0) with the finger at [y]
     * over a list on show from [top] to [bottom]: not at all away from its edges, then faster the
     * nearer the finger is to an edge (or past it), up to [max]. [zone]: how near counts as near (at
     * most a third of the list each, so a short list keeps a middle to slide in).
     */
    fun edgeStep(y: Float, top: Int, bottom: Int, zone: Int, max: Int): Int {
        val room = bottom - top
        if (room <= 0 || zone <= 0 || max <= 0) return 0
        val near = minOf(zone, room / 3).coerceAtLeast(1)
        val share = when {
            y < top + near -> -(top + near - y) / near
            y > bottom - near -> (y - (bottom - near)) / near
            else -> return 0
        }.coerceIn(-1f, 1f)
        val px = (share * max).toInt()
        return if (px != 0) px else if (share < 0) -1 else 1
    }
}
