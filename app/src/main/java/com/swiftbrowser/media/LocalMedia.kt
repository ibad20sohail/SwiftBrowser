package com.swiftbrowser.media

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File

/**
 * 2.9: where videos and songs played in Swift's player were left off, per file (its path and size:
 * a file replaced by another of the same name starts over), so they go on from there next time, and
 * for the home page's "Continue". Finished ones are forgotten. Songs start over as music players do;
 * only long recordings (audiobooks, talks, mixes) are remembered.
 */
object LocalMedia {
    class Item(val path: String, val title: String, val video: Boolean, val position: Long, val duration: Long, val updated: Long)

    private const val FILE = "local_media"
    private const val KEEP = 200

    /** Before this it is just the start: nothing to go back to. */
    private const val MIN_POSITION_MS = 5_000L

    /** Sound shorter than this (a song) isn't remembered. */
    private const val MIN_AUDIO_MS = 10 * 60_000L

    private class Saved(val item: Item, val size: Long)

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(path: String, size: Long) = "$path|$size"

    /** Close enough to the end to count as watched (the credits): the last 2%, at least 15 seconds. */
    fun nearEnd(position: Long, duration: Long): Boolean = duration > 0 && duration - position < maxOf(15_000L, duration / 50)

    /** Where [file] was left off, or null (never played, finished, at its start, or remembering is off). */
    fun position(ctx: Context, file: File): Long? {
        if (!PlayerSettings.resume) return null
        val saved = read(sp(ctx).getString(key(file.path, file.length()), null)) ?: return null
        val pos = saved.item.position
        return pos.takeIf { it >= MIN_POSITION_MS && !nearEnd(it, saved.item.duration) }
    }

    /** Remembers where [file] is ([position] of [duration], in ms); at its start or end it is forgotten. */
    fun save(ctx: Context, file: File, title: String, video: Boolean, position: Long, duration: Long) {
        if (duration <= 0) return
        val sp = sp(ctx)
        val size = file.length()
        val k = key(file.path, size)
        val worth = PlayerSettings.resume && (video || duration >= MIN_AUDIO_MS)
        if (!worth || position < MIN_POSITION_MS || nearEnd(position, duration)) {
            if (sp.contains(k)) sp.edit { remove(k) }
            return
        }
        val json = JSONObject()
            .put("p", file.path)
            .put("t", title)
            .put("v", video)
            .put("pos", position)
            .put("dur", duration)
            .put("s", size)
            .put("u", System.currentTimeMillis())
        val all = sp.all
        sp.edit {
            putString(k, json.toString())
            // The oldest go beyond the last KEEP files (checked when there are some too many).
            if (all.size > KEEP + 20) {
                all.entries.mapNotNull { e -> (e.value as? String)?.let { read(it) }?.let { e.key to it.item.updated } }
                    .sortedByDescending { it.second }.drop(KEEP).forEach { remove(it.first) }
            }
        }
    }

    /**
     * The files left off most recently first, only those still there as they were. Reads the phone's
     * storage: call it off the main thread.
     */
    fun recent(ctx: Context, limit: Int = 10): List<Item> {
        val all = sp(ctx).all.values.mapNotNull { (it as? String)?.let { s -> read(s) } }.sortedByDescending { it.item.updated }
        val out = ArrayList<Item>()
        for (s in all) {
            if (out.size >= limit) break
            val f = File(s.item.path)
            if (f.isFile && f.length() == s.size) out += s.item
        }
        return out
    }

    /** Forgets [path] (every size it had). */
    fun forget(ctx: Context, path: String) {
        val sp = sp(ctx)
        val prefix = "$path|"
        val gone = sp.all.keys.filter { it.startsWith(prefix) }
        if (gone.isNotEmpty()) sp.edit { gone.forEach { remove(it) } }
    }

    fun clear(ctx: Context) = sp(ctx).edit { clear() }

    /**
     * 3.0: forgets the places left off from [from] to [to] (Clear browsing data → Where you left off);
     * with [onlyCount], only says how many there are. Blocking.
     */
    fun clearSince(ctx: Context, from: Long, to: Long, onlyCount: Boolean = false): Int {
        val sp = sp(ctx)
        val gone = sp.all.entries.filter { e -> (e.value as? String)?.let { read(it) }?.item?.updated?.let { it in from..to } == true }.map { it.key }
        if (!onlyCount && gone.isNotEmpty()) sp.edit { gone.forEach { remove(it) } }
        return gone.size
    }

    private fun read(s: String?): Saved? = try {
        if (s == null) null else JSONObject(s).let { o ->
            Saved(Item(o.getString("p"), o.optString("t"), o.optBoolean("v", true), o.optLong("pos"), o.optLong("dur"), o.optLong("u")), o.optLong("s", -1))
        }
    } catch (_: Exception) {
        null
    }
}
