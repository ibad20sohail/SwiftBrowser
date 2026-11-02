package com.swiftbrowser.adblock

/**
 * The per-site part of uBlock Origin's setup, worked out from the global level and the sites' own
 * levels: its dynamic rules and its trusted sites (3.0.1: plain functions, so they can be tested,
 * and worked out twice by [AdBlock.push]: with and without the levels chosen from incognito tabs).
 */
object AdRules {
    /** Pages on these sites keep third-party scripts in Extreme mode, or signing in would break. */
    val EXTREME_EXEMPT = listOf("google.com", "youtube.com", "gmail.com", "accounts.google.com")

    private val BEHIND_THE_SCENE = listOf(
        "behind-the-scene * * noop",
        "behind-the-scene * image noop",
        "behind-the-scene * 3p noop",
        "behind-the-scene * inline-script noop",
        "behind-the-scene * 1p-script noop",
        "behind-the-scene * 3p-script noop",
        "behind-the-scene * 3p-frame noop",
    )

    /** uBO's dynamic rules: Extreme blocks third-party scripts and frames (on every site, or on one). */
    fun dynamicRules(g: AdLevel, overrides: Map<String, AdLevel>): String {
        val rules = ArrayList(BEHIND_THE_SCENE)
        if (g == AdLevel.EXTREME) {
            rules += "* * 3p-script block"
            rules += "* * 3p-frame block"
            val relaxed = EXTREME_EXEMPT + overrides.filterValues { it != AdLevel.EXTREME }.keys
            for (site in relaxed.distinct()) {
                rules += "$site * 3p-script noop"
                rules += "$site * 3p-frame noop"
            }
        } else {
            for (site in overrides.filterValues { it == AdLevel.EXTREME }.keys) {
                rules += "$site * 3p-script block"
                rules += "$site * 3p-frame block"
            }
        }
        return rules.joinToString("\n")
    }

    /** uBO "trusted sites": filtering is fully off for matching pages. */
    fun trusted(g: AdLevel, overrides: Map<String, AdLevel>): List<String> {
        if (g == AdLevel.OFF) {
            val enabled = overrides.filterValues { it != AdLevel.OFF }.keys
            if (enabled.isEmpty()) return listOf("/^/")
            // Sites are plain hostnames ([a-z0-9.-]), so only the dots need escaping for JS RegExp.
            val alt = enabled.joinToString("|") { it.replace(".", "\\.") }
            // Everything is trusted (unfiltered) except pages on the sites the user turned blocking on for.
            return listOf("/^(?!https?:\\/\\/([^\\/:]*\\.)?($alt)(:\\d+)?\\/)/")
        }
        return overrides.filterValues { it == AdLevel.OFF }.keys.toList()
    }
}
