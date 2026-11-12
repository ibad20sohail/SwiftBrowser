package com.swiftbrowser.core

/**
 * 3.0.7: Lite mode's levels (≡ menu → Lite), for all sites and for one site, each adding to the one
 * before. They took over Lite mode's switch and "Show sites as designed": Off is sites exactly as
 * designed, Low what Swift did with both off, Medium the old Lite mode, so nobody's sites change
 * look with the update. Plain, for tests.
 */
enum class LiteLevel(val key: String, val label: String, val summary: String) {
    OFF("off", "Off", "Sites exactly as designed: frosted glass and all their motion."),
    LOW("low", "Low", "No frosted glass, sites asked for less motion, lighter YouTube and TheFlixer."),
    MEDIUM("medium", "Medium", "Also no animations, shadows, blur or filters, and no smooth scrolling."),
    HIGH("high", "High", "Also GIFs and GIF-type videos play once, other videos start only when you touch them, and pages use fewer graphics layers."),
    EXTREME("extreme", "Extreme", "Also GIFs, silent videos and moving drawings stay still until you touch them, and text shows at once while a site's font loads.");

    fun atLeast(other: LiteLevel): Boolean = ordinal >= other.ordinal

    companion object {
        fun fromKey(key: String?): LiteLevel? = entries.firstOrNull { it.key == key }

        /**
         * The level an install gets from what it had before 3.0.7: Lite mode on is Medium; else
         * sites as designed ([asDesigned], or [autoAsDesigned] for a fast phone that never chose)
         * is Off; else Low.
         */
        fun migrated(liteOn: Boolean, asDesigned: Boolean?, autoAsDesigned: Boolean): LiteLevel = when {
            liteOn -> MEDIUM
            asDesigned ?: autoAsDesigned -> OFF
            else -> LOW
        }

        /** What a page gets: its site's own level or [all]; saving battery makes it at least Medium. */
        fun effective(all: LiteLevel, site: LiteLevel?, saving: Boolean): LiteLevel {
            val level = site ?: all
            return if (saving && !level.atLeast(MEDIUM)) MEDIUM else level
        }
    }
}

/**
 * 3.0.7: where one of the helper extension's page styles applies: on every site but [exclude], or,
 * with [only], on those sites alone (or nowhere when [on] is false).
 */
class LiteReach(val on: Boolean, val exclude: List<String>, val only: List<String>?) {
    companion object {
        /** The style that starts at level [min], with [all] for all sites and [sites]' own levels. */
        fun of(min: LiteLevel, all: LiteLevel, sites: Map<String, LiteLevel>): LiteReach {
            if (all.atLeast(min)) return LiteReach(true, sites.filterValues { !it.atLeast(min) }.keys.sorted(), null)
            val only = sites.filterValues { it.atLeast(min) }.keys.sorted()
            return LiteReach(only.isNotEmpty(), emptyList(), only)
        }
    }
}
