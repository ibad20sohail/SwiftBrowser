package com.swiftbrowser.media

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File

/**
 * 3.0.3: the videos on the phone watched to their end in Swift's player, for the tick on their
 * pictures in Files (where one was left part-way is LocalMedia's). Kept by path and size as
 * LocalMedia does, with the time, at most [KEEP]; the oldest go first. Cleared with "Where you left
 * off" (Clear browsing data).
 */
object Watched {
    private const val FILE = "local_media_watched"
    private const val KEEP = 500

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(path: String, size: Long) = "$path|$size"

    /** [file] was played to its end. */
    fun markFinished(ctx: Context, file: File) {
        if (!file.isFile) return
        val sp = sp(ctx)
        val all = sp.all
        sp.edit {
            putLong(key(file.path, file.length()), System.currentTimeMillis())
            if (all.size > KEEP + 20) {
                all.entries.mapNotNull { e -> (e.value as? Long)?.let { e.key to it } }
                    .sortedByDescending { it.second }.drop(KEEP).forEach { remove(it.first) }
            }
        }
    }

    /** Whether [file], as it is now, was watched to its end. A map lookup (the store is in memory once read). */
    fun isFinished(ctx: Context, file: File): Boolean = isFinished(ctx, file.path, file.length())

    /** [isFinished] for a file whose [size] is known already (a list row): no disk access. */
    fun isFinished(ctx: Context, path: String, size: Long): Boolean = sp(ctx).contains(key(path, size))

    /** Started again from its start: no longer counted as watched. */
    fun forget(ctx: Context, path: String) {
        val sp = sp(ctx)
        val prefix = "$path|"
        val gone = sp.all.keys.filter { it.startsWith(prefix) }
        if (gone.isNotEmpty()) sp.edit { gone.forEach { remove(it) } }
    }

    fun clear(ctx: Context) = sp(ctx).edit { clear() }

    /** Forgets those finished from [from] to [to]. */
    fun clearSince(ctx: Context, from: Long, to: Long) {
        val sp = sp(ctx)
        val gone = sp.all.entries.filter { e -> (e.value as? Long)?.let { it in from..to } == true }.map { it.key }
        if (gone.isNotEmpty()) sp.edit { gone.forEach { remove(it) } }
    }
}
