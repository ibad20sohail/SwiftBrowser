package com.swiftbrowser.tabs

/**
 * Small decisions about tabs (3.0), kept free of Android so that they can be tested on the computer
 * (app/src/test).
 */
object TabRules {
    /** How long a page the user just asked for waits for uBlock Origin's filters at app start. */
    const val USER_WAIT_MS = 3000L

    /**
     * How long a tab's page waits for uBlock Origin at app start (ms, see AdBlock.whenReady): what
     * the user just asked for briefly, a page restored by Swift until it is ready (0). Before 3.0 a
     * restored page gave up after 8 s, and the first page after a cold start loaded unfiltered.
     */
    fun adBlockWait(userNavAt: Long, now: Long): Long = if (now - userNavAt < 2000) USER_WAIT_MS else 0L

    /**
     * A page restore that waited (for the ad blocker, or for the tab's history to be read) still runs:
     * nothing newer was asked of its tab since. [token] is the restore's, [tabToken] the tab's now
     * (Tab.restoreToken: 0 once the user navigated, another number for a newer restore).
     */
    fun restoreStillWanted(token: Int, tabToken: Int): Boolean = token != 0 && token == tabToken

    /** What happens to the open tab when its page process ended (see [revive]). */
    enum class Revive {
        /** It ended again soon after Swift loaded it again: the crash page, with its Reload button. */
        CRASH_PAGE,

        /** Swift is out of sight: loaded again when it is back. */
        WHEN_BACK,

        /** Loaded again at once. */
        NOW,
    }

    /**
     * [autoRevivedAt]: when Swift last loaded the tab's page again by itself (elapsed ms, 0: never).
     * Once a minute at most, for crashes and (3.0) for pages Android ended too; and (3.0) not while
     * Swift is out of sight.
     */
    fun revive(now: Long, autoRevivedAt: Long, onScreen: Boolean): Revive = when {
        autoRevivedAt > 0 && now - autoRevivedAt < 60_000 -> Revive.CRASH_PAGE
        !onScreen -> Revive.WHEN_BACK
        else -> Revive.NOW
    }

    // ------------------------------------------------------------------ what is saved

    /**
     * The longest address saved for a tab. Android 7 can't read a database row over 2 MB: a tab
     * saved with a picture's data: address ("Open picture") stopped Swift at every start.
     */
    const val MAX_STORED_URL = 64 * 1024

    /** The longest saved history of a tab, in characters (under 2 MB however it is written). */
    const val MAX_STORED_STATE = 512 * 1024

    /** Titles are saved up to this many characters (a page can make its title as long as it likes). */
    const val MAX_STORED_TITLE = 1000

    /** [url] can be saved as a tab's address. */
    fun storable(url: String): Boolean = url.length <= MAX_STORED_URL

    /**
     * What is saved as a tab's address: a blank tab for one too long to keep. The page itself stays
     * open until Swift closes.
     */
    fun storedUrl(url: String): String = if (storable(url)) url else "about:blank"

    fun storedTitle(title: String): String = if (title.length <= MAX_STORED_TITLE) title else title.take(MAX_STORED_TITLE)

    /** A tab's history as saved; none when it is too long to read back. */
    fun storedState(state: String?): String? = state?.takeIf { it.length <= MAX_STORED_STATE }

    // ------------------------------------------------------------------ the log

    /**
     * What the log calls an address that isn't a web page: only its kind, never the address (a magnet
     * link carries the torrent's name and trackers, a file its name, a data: address its data). Null
     * for http and https addresses, which are logged by their site; "" for a blank page.
     */
    fun logKind(url: String?): String? {
        if (url.isNullOrBlank() || url == "about:blank") return ""
        val scheme = url.substringBefore(':', "").lowercase()
        return when (scheme) {
            "http", "https" -> null
            "about" -> "about:"
            "magnet" -> "a magnet link"
            "file", "content" -> "a file"
            "data" -> "a data address"
            "blob" -> "a blob address"
            "moz-extension" -> "an extension page"
            "view-source" -> "a page's source"
            "javascript" -> "a script"
            "resource" -> "a built-in page"
            else -> if (scheme.isNotEmpty() && scheme.length <= 20 && scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) {
                (if (scheme.first() in "aeiou") "an " else "a ") + "$scheme: link"
            } else {
                "an address"
            }
        }
    }

    // ------------------------------------------------------------------ links from other apps

    /** A tab showing [tabUrl] shows exactly [url] (a link another app sent): the same, or with the slash a site's address gets. */
    fun sameAddress(tabUrl: String, url: String): Boolean = tabUrl == url || tabUrl == "$url/" || "$tabUrl/" == url
}
