package com.swiftbrowser.core

/**
 * What incognito tabs chose for sites (3.0.1): kept in memory only, so incognito leaves nothing
 * behind, and forgotten when the last incognito tab closes. A choice can be "nothing" (null: a
 * setting back to the one for all sites), which still hides the saved one. Used on the main thread.
 */
class IncognitoChoices<V : Any> {
    private val choices = HashMap<String, V?>()

    fun isEmpty() = choices.isEmpty()

    /** The incognito choice for [site] if there is one, else [saved]. */
    fun get(site: String, saved: V?): V? = if (choices.containsKey(site)) choices[site] else saved

    fun set(site: String, value: V?) {
        choices[site] = value
    }

    /** A choice made in a normal tab counts in incognito too, so incognito's own goes. */
    fun drop(site: String) {
        choices.remove(site)
    }

    /** Forgets everything; true if there was anything. */
    fun clear(): Boolean {
        val had = choices.isNotEmpty()
        choices.clear()
        return had
    }

    /** [saved] with the choices on top (a null choice takes the site out). */
    fun applyTo(saved: Map<String, V>): Map<String, V> {
        if (choices.isEmpty()) return saved
        val out = LinkedHashMap(saved)
        for ((site, value) in choices) if (value == null) out.remove(site) else out[site] = value
        return out
    }

    /** For on/off choices: the sites that are on, with the choices on top. */
    fun sitesOn(saved: Set<String>): Set<String> {
        if (choices.isEmpty()) return saved
        val out = LinkedHashSet(saved)
        for ((site, value) in choices) if (value == true) out += site else out -= site
        return out
    }
}
