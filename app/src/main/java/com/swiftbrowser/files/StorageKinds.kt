package com.swiftbrowser.files

import android.content.Context
import android.provider.MediaStore
import com.swiftbrowser.core.L
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 3.0.4: what fills a storage, for the bar on Files' start page: pictures, videos, music and
 * documents (from Android's media index, which knows every file), the rest of what is used, and
 * what is free. The splitting maths is plain and tested on the PC (StorageKindsTest); [measure]
 * reads the index.
 */
object StorageKinds {
    /** What a storage holds by kind, in bytes (the same sizes are equal: nothing to show anew). */
    data class Sizes(val pictures: Long, val videos: Long, val music: Long, val documents: Long)

    /** A storage's bar, in bytes: each kind, the rest of what is used ([other]), and what is [free]. */
    class Split(val pictures: Long, val videos: Long, val music: Long, val documents: Long, val other: Long, val free: Long) {
        /** In the bar's order: pictures, videos, music, documents, other, free. */
        fun parts(): LongArray = longArrayOf(pictures, videos, music, documents, other, free)
    }

    /**
     * [total] and [free] as the storage says, split by [kinds]: what is used and none of them is
     * Other. Null when the storage's size isn't known. The index can count more than is used (files
     * gone since it last looked): the kinds are then made smaller in proportion to fit, and nothing
     * is left for Other.
     */
    fun split(total: Long, free: Long, kinds: Sizes): Split? {
        if (total <= 0 || free < 0) return null
        val f = free.coerceAtMost(total)
        val used = total - f
        val k = longArrayOf(kinds.pictures, kinds.videos, kinds.music, kinds.documents)
        for (i in k.indices) k[i] = k[i].coerceAtLeast(0)
        val known = k.sum()
        if (known <= used) return Split(k[0], k[1], k[2], k[3], used - known, f)
        val scaled = LongArray(k.size) { (k[it].toDouble() * used / known).toLong() }
        // What rounding left over (a few bytes) goes to the biggest, so they add up to what is used.
        val biggest = scaled.indices.maxByOrNull { scaled[it] } ?: 0
        scaled[biggest] += used - scaled.sum()
        return Split(scaled[0], scaled[1], scaled[2], scaled[3], 0, f)
    }

    /**
     * The bar's parts' widths in pixels: [parts] (bytes) across [width], with [gap] pixels between
     * the parts that show. None is narrower than [min] (a part too small to see still shows, as a
     * dot), which the wider ones make room for. A part of 0 gets no width and no gap. The widths and
     * the gaps add up to [width] exactly.
     */
    fun widths(parts: LongArray, width: Int, gap: Int, min: Int): IntArray {
        val out = IntArray(parts.size)
        val shown = parts.count { it > 0 }
        if (shown == 0 || width <= 0) return out
        val room = width - gap * (shown - 1)
        if (room < shown) return out
        // Too narrow for every part at its smallest: each the same.
        if (min * shown >= room) {
            var left = room
            var n = shown
            for (i in parts.indices) {
                if (parts[i] <= 0) continue
                out[i] = left / n
                left -= out[i]
                n--
            }
            return out
        }
        // Parts whose share would be under [min] get [min]; the rest share what is left by their
        // size, again until none falls under it.
        val atMin = BooleanArray(parts.size)
        var free = room.toDouble()
        var bytes = parts.sumOf { if (it > 0) it.toDouble() else 0.0 }
        var changed = true
        while (changed) {
            changed = false
            for (i in parts.indices) {
                if (parts[i] <= 0 || atMin[i]) continue
                if (parts[i] / bytes * free < min) {
                    atMin[i] = true
                    free -= min
                    bytes -= parts[i]
                    changed = true
                }
            }
        }
        // Whole pixels, the ones left over to the parts that lost the most in rounding.
        val exact = DoubleArray(parts.size) { i ->
            when {
                parts[i] <= 0 -> 0.0
                atMin[i] -> min.toDouble()
                else -> parts[i] / bytes * free
            }
        }
        var sum = 0
        for (i in parts.indices) {
            out[i] = exact[i].toInt()
            sum += out[i]
        }
        var left = room - sum
        while (left > 0) {
            var best = -1
            for (i in parts.indices) {
                if (parts[i] <= 0) continue
                if (best < 0 || exact[i] - out[i] > exact[best] - out[best]) best = i
            }
            out[best]++
            exact[best] = out[best].toDouble()
            left--
        }
        return out
    }

    // ------------------------------------------------------------------ Android's media index

    /** Each storage's sizes as last measured while Swift runs, by its top folder's path. */
    private val measured = ConcurrentHashMap<String, Sizes>()

    /** [root]'s storage as last measured, or null when it wasn't yet. */
    fun cached(root: File): Sizes? = measured[root.path]

    /**
     * What [root]'s storage holds by kind, from Android's media index: pictures, videos and music
     * by the kind the index found them to be, documents by their endings as Files' Documents finds
     * them. Kept for [cached]. Not on the main thread (one pass through the index); null when the
     * index can't be read.
     */
    fun measure(ctx: Context, root: File): Sizes? {
        val sizes = sums(ctx, root) ?: walk(ctx, root) ?: return null
        measured[root.path] = sizes
        return sizes
    }

    private const val DATA = "_data"
    private const val SIZE = MediaStore.Files.FileColumns.SIZE
    private const val MEDIA_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
    private const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private const val AUDIO = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
    private const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

    /** The index's question for "under [root]". */
    private fun under(root: File): Array<String> =
        arrayOf(root.path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%")

    /** A document by its ending (the endings are Swift's own, written into the question as they are). */
    private fun documents(): String = FileIndex.DOCUMENT_EXTS.joinToString(" OR ", "(", ")") { "$DATA LIKE '%.$it'" } +
        " AND $MEDIA_TYPE NOT IN ($IMAGE, $AUDIO, $VIDEO)"

    /** The four sums in one question (Android 7 to 10 answer it; newer ones refuse sums: then [walk]). */
    private fun sums(ctx: Context, root: File): Sizes? = try {
        val projection = arrayOf(
            "SUM(CASE WHEN $MEDIA_TYPE = $IMAGE THEN $SIZE ELSE 0 END)",
            "SUM(CASE WHEN $MEDIA_TYPE = $VIDEO THEN $SIZE ELSE 0 END)",
            "SUM(CASE WHEN $MEDIA_TYPE = $AUDIO THEN $SIZE ELSE 0 END)",
            "SUM(CASE WHEN ${documents()} THEN $SIZE ELSE 0 END)",
        )
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, "$DATA LIKE ? ESCAPE '\\'", under(root), null)?.use { c ->
            if (c.moveToFirst()) Sizes(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else null
        }
    } catch (e: Exception) {
        L.d("Files: the media index won't add up sizes (${e.javaClass.simpleName}); counting them one by one")
        null
    }

    /** The same, file by file (only pictures, videos, music and documents are read). */
    private fun walk(ctx: Context, root: File): Sizes? = try {
        val t = LongArray(4)
        val selection = "$DATA LIKE ? ESCAPE '\\' AND ($MEDIA_TYPE IN ($IMAGE, $AUDIO, $VIDEO) OR (${documents()}))"
        ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(SIZE, MEDIA_TYPE), selection, under(root), null)?.use { c ->
            while (c.moveToNext()) {
                val size = c.getLong(0).coerceAtLeast(0)
                when (c.getInt(1)) {
                    IMAGE -> t[0] += size
                    VIDEO -> t[1] += size
                    AUDIO -> t[2] += size
                    else -> t[3] += size
                }
            }
            Sizes(t[0], t[1], t[2], t[3])
        }
    } catch (e: Exception) {
        L.w("Files: couldn't read the media index for ${root.path}", e)
        null
    }
}
