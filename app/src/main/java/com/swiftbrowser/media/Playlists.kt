package com.swiftbrowser.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * 3.0.11: the user's own playlists: songs on the phone in an order of their own (until now a song's
 * folder was its only playlist). Made and filled in Files (Music, and a song's Add to playlist), and
 * played in that order on the player's music screen (PlayerActivity.openList).
 *
 * One small JSON file in Swift's own storage, written aside and then swapped in, so a phone that dies
 * meanwhile keeps the last one whole. Only the songs' paths are kept: the songs themselves are never
 * touched, and one deleted or moved since shows as missing. Read once (off the main thread), then kept
 * in memory; each change is written on a thread of its own. Not in the database (nothing here needs
 * one, and its version belongs to other parts of Swift).
 */
object Playlists {
    /** A playlist: its [songs] (paths) in the user's order. Never changed: a change makes a new one. */
    class Playlist(val id: String, val name: String, val songs: List<String>, val changed: Long)

    private const val FILE = "playlists.json"
    private const val FORMAT = 1
    private const val HINT = "playlist_hint_shown"

    /** A restore waits this long at most for its playlists to be written. */
    private const val WRITE_WAIT_S = 5L

    private val io = Background.single("playlists")
    private val main = Handler(Looper.getMainLooper())

    /** The playlists in the order they were made, null until read. */
    @Volatile
    private var lists: List<Playlist>? = null

    /** What waits to be written: the latest only (a write that comes later finds nothing left to do). */
    private var unsaved: List<Playlist>? = null

    /** Changes with every change, for a screen to tell that what it shows is old. */
    @Volatile
    var version = 0
        private set

    /** Blocking the first time (call it off the main thread then): the playlists, read from their file once. */
    @Synchronized
    fun load(ctx: Context): List<Playlist> {
        lists?.let { return it }
        val f = File(ctx.filesDir, FILE)
        val read = try {
            if (f.isFile) fromJson(JSONObject(f.readText()).optJSONArray("lists"), null) else emptyList()
        } catch (e: Exception) {
            // Kept aside rather than written over, should it ever be wanted back.
            L.w("Playlists couldn't be read (${e.javaClass.simpleName}); the file is kept aside")
            f.renameTo(File(ctx.filesDir, "$FILE.bad"))
            emptyList()
        }
        lists = read
        return read
    }

    /** The playlists if they were read already (null: not yet, see [load] and [whenLoaded]). */
    fun cached(): List<Playlist>? = lists

    /** [then] on the main thread with the playlists, read first (off the main thread) if they weren't yet. */
    fun whenLoaded(ctx: Context, then: (List<Playlist>) -> Unit) {
        lists?.let {
            then(it)
            return
        }
        val app = ctx.applicationContext
        io.execute {
            val all = load(app)
            main.post { then(all) }
        }
    }

    fun get(id: String): Playlist? = lists?.firstOrNull { it.id == id }

    /** A new playlist called [name] (another's name gets a number) holding [songs]. */
    @Synchronized
    fun create(ctx: Context, name: String, songs: List<String>): Playlist {
        val all = load(ctx)
        val names = all.map { it.name }
        val wanted = PlaylistRules.cleanName(name).ifEmpty { PlaylistRules.freshName(names) }
        val p = Playlist(UUID.randomUUID().toString(), PlaylistRules.uniqueName(wanted, names), PlaylistRules.add(emptyList(), kept(ctx, songs)).first, now())
        commit(ctx, all + p)
        L.d("Playlists: one made, ${p.songs.size} songs")
        return p
    }

    /** [songs] at the end of playlist [id], leaving out those it has already: how many went in (-1: no such playlist). */
    @Synchronized
    fun add(ctx: Context, id: String, songs: List<String>): Int {
        val all = load(ctx)
        val p = all.firstOrNull { it.id == id } ?: return -1
        val (next, added) = PlaylistRules.add(p.songs, kept(ctx, songs))
        if (added > 0) commit(ctx, all.map { if (it.id == id) Playlist(id, it.name, next, now()) else it })
        return added
    }

    /** Playlist [id] called [name] now (another's name gets a number); the playlist as it is then, or null. */
    @Synchronized
    fun rename(ctx: Context, id: String, name: String): Playlist? {
        val all = load(ctx)
        val p = all.firstOrNull { it.id == id } ?: return null
        val clean = PlaylistRules.cleanName(name)
        if (clean.isEmpty()) return p
        val unique = PlaylistRules.uniqueName(clean, all.filter { it.id != id }.map { it.name })
        if (unique == p.name) return p
        val renamed = Playlist(id, unique, p.songs, now())
        commit(ctx, all.map { if (it.id == id) renamed else it })
        return renamed
    }

    /** Playlist [id]'s songs are [songs] now (moved, taken out, or put back). */
    @Synchronized
    fun setSongs(ctx: Context, id: String, songs: List<String>) {
        val all = load(ctx)
        val p = all.firstOrNull { it.id == id } ?: return
        if (p.songs == songs) return
        commit(ctx, all.map { if (it.id == id) Playlist(id, it.name, songs.take(PlaylistRules.MAX_SONGS), now()) else it })
    }

    /** Deletes playlist [id] (never its songs): it and where it was, for Undo ([putBack]), or null. */
    @Synchronized
    fun delete(ctx: Context, id: String): Pair<Playlist, Int>? {
        val all = load(ctx)
        val at = all.indexOfFirst { it.id == id }
        if (at < 0) return null
        commit(ctx, all.filterIndexed { i, _ -> i != at })
        return all[at] to at
    }

    /** Undo of [delete]: [p] back where it was. */
    @Synchronized
    fun putBack(ctx: Context, p: Playlist, index: Int) {
        val all = load(ctx)
        if (all.any { it.id == p.id }) return
        val next = all.toMutableList()
        next.add(index.coerceIn(0, next.size), p)
        commit(ctx, next)
    }

    /** The one-time hint on a playlist's screen (holding a song moves it, a swipe takes it out) was shown. */
    var hintShown: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(HINT, false)
        set(v) {
            SwiftApp.prefs.sp.edit().putBoolean(HINT, v).apply()
        }

    // ------------------------------------------------------------------ backups

    /** Blocking: the playlists for a backup (Backup.contents): their names and their songs' paths. */
    fun forBackup(ctx: Context): JSONArray = toJson(load(ctx))

    /**
     * Blocking: a backup's playlists added (Backup.restore): one with a name not here comes in whole, one
     * with a name here already gets the songs it lacks. A backup may come from anyone: only paths on the
     * phone's storage, never into Swift's own. How many playlists came in or grew.
     */
    fun restore(ctx: Context, arr: JSONArray?): Int {
        val incoming = fromJson(arr, ctx)
        if (incoming.isEmpty()) return 0
        val count = synchronized(this) {
            val all = load(ctx).toMutableList()
            var n = 0
            for (b in incoming) {
                val i = all.indexOfFirst { it.name.equals(b.name, ignoreCase = true) }
                if (i < 0) {
                    all += Playlist(UUID.randomUUID().toString(), b.name, b.songs, now())
                    n++
                } else {
                    val (next, added) = PlaylistRules.add(all[i].songs, b.songs)
                    if (added > 0) {
                        all[i] = Playlist(all[i].id, all[i].name, next, now())
                        n++
                    }
                }
            }
            if (n > 0) commit(ctx, all)
            n
        }
        // Written before this returns (Swift restarts straight after a restore), waited for without the
        // lock: the playlists' thread may need it meanwhile.
        if (count > 0) runCatching { io.submit(Runnable {}).get(WRITE_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) }
        return count
    }

    // ------------------------------------------------------------------ the file

    private fun now() = System.currentTimeMillis()

    /** [songs] as a playlist may hold them: paths on the phone's storage, none into Swift's own. */
    private fun kept(ctx: Context, songs: List<String>): List<String> {
        val own = ownFolders(ctx)
        return songs.filter { PlaylistRules.keeps(it, own) }
    }

    /** Swift's own storage under each of the names it goes by. */
    private fun ownFolders(ctx: Context): List<String> =
        listOfNotNull(ctx.applicationInfo.dataDir, "/data/data/${ctx.packageName}", "/data/user/0/${ctx.packageName}").distinct()

    /** The playlists are [all] now: kept in memory at once, written to the file on the playlists' thread. */
    private fun commit(ctx: Context, all: List<Playlist>) {
        lists = all
        version++
        unsaved = all
        val app = ctx.applicationContext
        io.execute { write(app) }
    }

    /** Written aside, then swapped in: a phone that dies meanwhile keeps the old file whole. */
    private fun write(ctx: Context) {
        val all = synchronized(this) { unsaved.also { unsaved = null } } ?: return
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        try {
            val text = JSONObject().put("format", FORMAT).put("lists", toJson(all)).toString()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) throw IOException("the new file couldn't take the old one's place")
        } catch (e: Exception) {
            tmp.delete()
            L.w("Playlists couldn't be saved (${e.javaClass.simpleName})")
        }
    }

    private fun toJson(all: List<Playlist>): JSONArray = JSONArray().apply {
        for (p in all) put(JSONObject().put("id", p.id).put("name", p.name).put("changed", p.changed).put("songs", JSONArray(p.songs)))
    }

    /**
     * Playlists read from [arr]: each checked, a broken one passed by. [from] (a backup's): only paths
     * Swift may keep (see [kept]); Swift's own file is trusted.
     */
    private fun fromJson(arr: JSONArray?, from: Context?): List<Playlist> {
        arr ?: return emptyList()
        val own = from?.let { ownFolders(it) }
        val out = ArrayList<Playlist>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = PlaylistRules.cleanName((o.opt("name") as? String).orEmpty())
            if (name.isEmpty()) continue
            val songs = ArrayList<String>()
            o.optJSONArray("songs")?.let { s ->
                for (j in 0 until s.length()) {
                    val path = s.opt(j) as? String ?: continue
                    if (own == null || PlaylistRules.keeps(path, own)) songs += path
                }
            }
            val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: UUID.randomUUID().toString()
            // Its songs once each, as Add to playlist keeps them.
            out += Playlist(id, name, PlaylistRules.add(emptyList(), songs).first, o.optLong("changed"))
        }
        return out
    }
}

/** 3.0.11: the playlists' plain rules (tested on the PC, PlaylistRulesTest). */
internal object PlaylistRules {
    /** A playlist's name is at most this long, as a bookmark folder's. */
    const val MAX_NAME = 60

    /** A playlist holds at most this many songs: its file stays small (under half a megabyte). */
    const val MAX_SONGS = 5000

    /** A path longer than this isn't one. */
    private const val MAX_PATH = 4096

    /** [raw] as a playlist's name: one line, no spaces round it, at most [MAX_NAME] long. */
    fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()

    /** "Playlist 1", or the next number no playlist ([taken]) has. */
    fun freshName(taken: Collection<String>): String {
        val names = taken.map { it.lowercase(Locale.ROOT) }.toHashSet()
        var n = 1
        while ("playlist $n" in names) n++
        return "Playlist $n"
    }

    /** [name], or "[name] (2)", "(3)"… when another playlist ([taken]) has it, in any case. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        val names = taken.map { it.lowercase(Locale.ROOT) }.toHashSet()
        if (name.lowercase(Locale.ROOT) !in names) return name
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = name.take(MAX_NAME - suffix.length).trimEnd() + suffix
            if (candidate.lowercase(Locale.ROOT) !in names) return candidate
            n++
        }
    }

    /** [songs] with those of [adding] they haven't got yet at the end (once each, up to [MAX_SONGS]), and how many that was. */
    fun add(songs: List<String>, adding: List<String>): Pair<List<String>, Int> {
        val have = songs.toHashSet()
        val out = ArrayList(songs)
        var added = 0
        for (s in adding) {
            if (out.size >= MAX_SONGS) break
            if (s.isBlank() || !have.add(s)) continue
            out += s
            added++
        }
        return out to added
    }

    /** [songs] with the one at [from] moved to [to]; the others make room. */
    fun move(songs: List<String>, from: Int, to: Int): List<String> {
        if (from !in songs.indices || to !in songs.indices || from == to) return songs
        val out = songs.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /** Undo of a song taken out: [songs] with [song] back at [index], unless it is there again already. */
    fun putBack(songs: List<String>, song: String, index: Int): List<String> {
        if (song in songs) return songs
        val out = songs.toMutableList()
        out.add(index.coerceIn(0, out.size), song)
        return out
    }

    /**
     * Whether a playlist may keep [path]: a whole path on the phone's storage, not into Swift's own
     * storage ([own], each name it goes by), with no ".." that could lead there.
     */
    fun keeps(path: String, own: Collection<String>): Boolean {
        if (!path.startsWith("/") || path.length > MAX_PATH || path.endsWith("/")) return false
        if (path.split('/').any { it == ".." || it == "." }) return false
        return own.none { root -> root.isNotEmpty() && (path == root || path.startsWith(root.trimEnd('/') + "/")) }
    }

    /**
     * What Play plays: the songs still on the phone ([there]), and where among them to start: the song at
     * [start] in [songs], or the first one there after it, or the very first.
     */
    fun playable(songs: List<String>, there: (String) -> Boolean, start: Int = 0): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        var at = -1
        for ((i, s) in songs.withIndex()) {
            if (!there(s)) continue
            if (at < 0 && i >= start) at = out.size
            out += s
        }
        return out to if (at < 0) 0 else at
    }

    /** An order of [count] songs (their places) that starts with [first], the others in a random order after it (Shuffle). */
    fun shuffleOrder(count: Int, first: Int, random: java.util.Random): IntArray {
        if (count <= 0) return IntArray(0)
        val start = first.coerceIn(0, count - 1)
        val rest = (0 until count).filter { it != start }.toMutableList()
        java.util.Collections.shuffle(rest, random)
        return (listOf(start) + rest).toIntArray()
    }

    /** "1 song", "12 songs"; with some gone, "12 songs · 2 missing". */
    fun songsText(count: Int, missing: Int = 0): String {
        val songs = if (count == 1) "1 song" else "$count songs"
        return if (missing > 0) "$songs · $missing missing" else songs
    }

    /** What adding [count] songs to the playlist [name] says, [added] of them new to it. */
    fun addedText(name: String, count: Int, added: Int): String {
        val songs = if (added == 1) "1 song" else "$added songs"
        return when {
            added <= 0 -> if (count == 1) "It's in “$name” already" else "They're all in “$name” already"
            count == 1 -> "Added to “$name”"
            added == count -> "Added $songs to “$name”"
            else -> "Added $songs to “$name”: the others were in it already"
        }
    }

    /** What making the playlist [name] with [count] songs says. */
    fun madeText(name: String, count: Int): String = when (count) {
        0 -> "Made “$name”"
        1 -> "Made “$name” with 1 song"
        else -> "Made “$name” with $count songs"
    }
}
