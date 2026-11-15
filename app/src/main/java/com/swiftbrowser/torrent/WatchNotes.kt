package com.swiftbrowser.torrent

import com.swiftbrowser.download.Downloads

/**
 * 3.0.11: what Swift's player says while a torrent being watched (Watch now) keeps it waiting: the
 * rule that paused the torrent, in plain words, or that nothing has come in for a while. Nothing
 * while the pieces are simply on their way: the player's loading circle says that.
 */
object WatchNotes {
    /** A shorter wait says nothing: a seek in a film still downloading takes a moment. */
    const val SAY_AFTER_MS = 3_000L

    /** No piece of the torrent for this long while the player waits: said too. */
    const val QUIET_MS = 30_000L

    /** The same note is said again this often while it lasts (a message goes by itself after a few seconds). */
    const val AGAIN_MS = 30_000L

    /**
     * The note for a player waiting [waitingMs] for a torrent whose last piece came [quietMs] ago,
     * its row's [status] and [reason] (Torrents' words), with [peers] sharing it; or null.
     */
    fun of(waitingMs: Long, quietMs: Long, status: Int, reason: String?, peers: Int): String? {
        if (waitingMs < SAY_AFTER_MS) return null
        return when (status) {
            Downloads.DONE -> null
            Downloads.PAUSED -> "The torrent is paused. Resume it in Downloads to go on watching."
            Downloads.FAILED -> "The torrent stopped" + (reason?.trimEnd('.')?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + "."
            else -> when (reason) {
                Torrents.PAUSED_VPN -> "The torrent is paused while Swift's VPN is on. Turn off the VPN to go on watching."
                Torrents.WAITING_WIFI -> "The torrent waits for Wi-Fi. Connect to Wi-Fi to go on watching."
                Torrents.PAUSED_BATTERY -> "The torrent is paused because the battery is low. It goes on when the phone charges."
                Torrents.PAUSED_STORAGE -> "The torrent is paused: the phone doesn't have enough free space for it."
                Torrents.WAITING_TURN -> "The torrent waits for another one to finish."
                Torrents.NOBODY -> "No one is sharing this torrent right now. Swift tries again later."
                Torrents.CHECKING -> Torrents.CHECKING
                else -> when {
                    quietMs < QUIET_MS -> null
                    peers == 0 -> "No one sharing this torrent is online right now. Swift keeps looking."
                    else -> "Nothing has come in for a while. Swift keeps trying."
                }
            }
        }
    }

    /** Says each note once, and again every [AGAIN_MS] while it lasts. [clock]: uptime in ms. */
    class Repeat(private val clock: () -> Long) {
        private var said: String? = null
        private var saidAt = 0L

        /** What to say now for [note] (the note of this moment, or null): it, or null when said lately. */
        fun next(note: String?): String? {
            if (note == null) {
                said = null
                return null
            }
            val now = clock()
            if (note == said && now - saidAt < AGAIN_MS) return null
            said = note
            saidAt = now
            return note
        }
    }
}
