package com.swiftbrowser.core

/**
 * 3.0.2: the order of the home page's parts under the search box (Settings → Home & news → Order of
 * the home page). Nothing is left out, only placed. By default Continue comes first: what you were
 * watching or reading is one tap away without scrolling, and when there is nothing to go on with the
 * row hides itself, so the shortcuts sit right under the search box as before.
 */
object HomeOrder {
    const val CONTINUE = "continue"
    const val SHORTCUTS = "shortcuts"
    const val NEWS = "news"

    val DEFAULT = listOf(CONTINUE, SHORTCUTS, NEWS)

    fun label(part: String): String = when (part) {
        CONTINUE -> "Continue watching and reading"
        SHORTCUTS -> "Shortcuts"
        NEWS -> "News"
        else -> part
    }

    /** The saved order: parts Swift knows, each once, and any missing ones where the default has them. */
    fun parse(saved: String?): List<String> {
        val known = saved.orEmpty().split(',').map { it.trim() }.filter { it in DEFAULT }.distinct()
        return known + DEFAULT.filter { it !in known }
    }

    fun encode(order: List<String>): String = order.joinToString(",")

    /** [order] with [part] moved [by] places (-1 up, 1 down), kept within the list. */
    fun move(order: List<String>, part: String, by: Int): List<String> {
        val from = order.indexOf(part)
        if (from < 0) return order
        val to = (from + by).coerceIn(0, order.size - 1)
        if (to == from) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }
}
