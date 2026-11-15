package com.swiftbrowser.media

import android.os.SystemClock
import androidx.media3.common.Player
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.torrent.TorrentStream
import com.swiftbrowser.torrent.Torrents
import com.swiftbrowser.torrent.WatchNotes
import java.util.Locale

/**
 * 3.0.11: Watch now in Swift's player: a torrent's video played while it downloads. Its entry is
 * read through [TorrentDataSource]; it is a file on the phone otherwise (where it was left is kept,
 * subtitles beside it are found). The torrent goes back to its usual order as soon as the player no
 * longer has the entry (closed, or something else opened), and while the player waits for the
 * torrent, [note] says why when there is something to know: a rule paused it, or nothing comes in.
 * Main thread only.
 */
object TorrentPlayback : LocalPlayback.Watcher {
    private var stream: TorrentStream? = null
    private var entry: LocalPlayback.Entry? = null
    private val notes = WatchNotes.Repeat(SystemClock::uptimeMillis)

    /** The player's entry for file [index] of torrent download [id] (-1: its film), watched from now on; null if it can't be. */
    fun entry(id: Long, index: Int): LocalPlayback.Entry? {
        val item = Downloads.get(id) ?: return null
        // Another file watched before is let go of there.
        val s = Torrents.watch(item, index) ?: return null
        val f = s.file
        val e = LocalPlayback.Entry(TorrentUri.of(s.hash, s.index, f.extension.lowercase(Locale.ROOT)), f.nameWithoutExtension, true, file = f)
        stream = s
        entry = e
        LocalPlayback.watch(this)
        return e
    }

    override fun onInfoChanged() = check()

    override fun onReleased() = check()

    /** The player's list no longer has the entry: watching ends. */
    private fun check() {
        val e = entry ?: return
        if (LocalPlayback.entries.any { it === e }) return
        stream?.let { Torrents.unwatch(it) }
        stream = null
        entry = null
        LocalPlayback.unwatch(this)
    }

    /**
     * What the player should say now (each note once, and again every half minute while it lasts):
     * while it shows that it is loading the torrent's video, why, when a rule paused the torrent or
     * nothing has come in for a while. Null otherwise.
     */
    fun note(): String? {
        val s = stream
        val loading = LocalPlayback.player?.playbackState == Player.STATE_BUFFERING
        val item = if (s != null && loading && LocalPlayback.current === entry) Downloads.get(s.id) else null
        val note = if (s != null && item != null) WatchNotes.of(s.waitingMs(), s.quietMs(), item.status, item.error, item.peers) else null
        return notes.next(note)
    }
}
