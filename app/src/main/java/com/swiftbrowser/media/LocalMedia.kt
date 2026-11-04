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

    /**
     * 3.0.3: how much of [file] was watched (0..1), for the line on its picture in Files; null when
     * nothing is kept for it (never played, finished, or remembering is off). A map lookup.
     */
    fun progress(ctx: Context, file: File): Float? = progress(ctx, file.path, file.length())

    /** [progress] for a file whose [size] is known already (a list row): no disk access. */
    fun progress(ctx: Context, path: String, size: Long): Float? {
        val saved = read(sp(ctx).getString(key(path, size), null)) ?: return null
        val d = saved.item.duration
        return if (d > 0) (saved.item.position.toFloat() / d).coerceIn(0f, 1f) else null
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
        val d = delays(ctx)
        val timings = d.all.keys.filter { it.startsWith(prefix) }
        if (timings.isNotEmpty()) d.edit { timings.forEach { remove(it) } }
    }

    fun clear(ctx: Context) {
        sp(ctx).edit { clear() }
        delays(ctx).edit { clear() }
        Watched.clear(ctx)
    }

    // ------------------------------------------------------------------ timing (3.0.1) and tracks (3.0.3)

    /**
     * Each file's subtitle timing (3.0.3: and its sound timing and the tracks chosen), kept apart from
     * the places: {"ms": subtitles, "snd": sound, "tr": tracks, "u": when}. 3.0.1's entries (only
     * "ms") read as they are.
     */
    private const val DELAYS = "local_media_subtitle_delay"

    private fun delays(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(DELAYS, Context.MODE_PRIVATE)

    private fun timing(ctx: Context, file: File): JSONObject? = try {
        delays(ctx).getString(key(file.path, file.length()), null)?.let { JSONObject(it) }
    } catch (_: Exception) {
        null
    }

    /** The subtitle timing chosen for [file], in ms (later > 0, earlier < 0); 0 when none. */
    fun subtitleDelay(ctx: Context, file: File): Long = timing(ctx, file)?.optLong("ms") ?: 0L

    /** 3.0.3: the sound timing chosen for [file], in ms (the sound later > 0, earlier < 0); 0 when none. */
    fun soundDelay(ctx: Context, file: File): Long = timing(ctx, file)?.optLong("snd") ?: 0L

    /** 3.0.3: the sound and subtitles chosen for [file] last time, or null. */
    fun trackChoice(ctx: Context, file: File): TrackChoice? =
        timing(ctx, file)?.optJSONObject("tr")?.let { TrackChoice.fromJson(it) }?.takeIf { !it.isEmpty }

    /** Keeps [ms] as [file]'s subtitle timing for the next time it plays (0 forgets it). */
    fun saveSubtitleDelay(ctx: Context, file: File, ms: Long) = changeTiming(ctx, file) { if (ms == 0L) remove("ms") else put("ms", ms) }

    /** 3.0.3: both timings at once (0 forgets one). */
    fun saveDelays(ctx: Context, file: File, subtitleMs: Long, soundMs: Long) = changeTiming(ctx, file) {
        if (subtitleMs == 0L) remove("ms") else put("ms", subtitleMs)
        if (soundMs == 0L) remove("snd") else put("snd", soundMs)
    }

    /** 3.0.3: the tracks chosen for [file] (null or nothing forgets them). */
    fun saveTrackChoice(ctx: Context, file: File, choice: TrackChoice?) = changeTiming(ctx, file) {
        if (choice == null || choice.isEmpty) remove("tr") else put("tr", choice.toJson())
    }

    /** Changes [file]'s entry; one with nothing left in it goes. Beyond the last KEEP files, the oldest go. */
    private fun changeTiming(ctx: Context, file: File, change: JSONObject.() -> Unit) {
        val sp = delays(ctx)
        val k = key(file.path, file.length())
        val o = (try {
            sp.getString(k, null)?.let { JSONObject(it) }
        } catch (_: Exception) {
            null
        }) ?: JSONObject()
        o.change()
        o.remove("u")
        if (o.length() == 0) {
            if (sp.contains(k)) sp.edit { remove(k) }
            return
        }
        o.put("u", System.currentTimeMillis())
        val all = sp.all
        sp.edit {
            putString(k, o.toString())
            if (all.size > KEEP + 20) {
                all.entries.mapNotNull { e -> (e.value as? String)?.let { runCatching { e.key to JSONObject(it).optLong("u") }.getOrNull() } }
                    .sortedByDescending { it.second }.drop(KEEP).forEach { remove(it.first) }
            }
        }
    }

    /**
     * 3.0: forgets the places left off from [from] to [to] (Clear browsing data → Where you left off);
     * with [onlyCount], only says how many there are. Blocking.
     */
    fun clearSince(ctx: Context, from: Long, to: Long, onlyCount: Boolean = false): Int {
        val sp = sp(ctx)
        val gone = sp.all.entries.filter { e -> (e.value as? String)?.let { read(it) }?.item?.updated?.let { it in from..to } == true }.map { it.key }
        if (!onlyCount && gone.isNotEmpty()) sp.edit { gone.forEach { remove(it) } }
        // 3.0.1: subtitle timings chosen then go too (they name the files played).
        if (!onlyCount) {
            val d = delays(ctx)
            val timings = d.all.entries.filter { e ->
                (e.value as? String)?.let { runCatching { JSONObject(it).optLong("u") }.getOrNull() }?.let { it in from..to } == true
            }.map { it.key }
            if (timings.isNotEmpty()) d.edit { timings.forEach { remove(it) } }
            // 3.0.3: and the ticks of videos watched to their end then.
            Watched.clearSince(ctx, from, to)
        }
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
