package com.swiftbrowser.files

import android.content.Context
import com.swiftbrowser.core.L
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

/**
 * Unfinished copies (3.0). Every file Files writes (a copy, a file taken out of an archive, a new
 * archive, a file moved into Private, an upload from Wi-Fi transfer) is written under a hidden name
 * beside where it goes (".swift-<number>.part") and takes its own name only once it is whole: a copy
 * cut off half-way (Android closed Swift, the battery ran out) never shows as the file itself.
 *
 * Each part is noted in a small list in Swift's own storage as it begins, and crossed off once it has
 * its name or is deleted. The next time Files opens, the parts a closed Swift left behind are known
 * for certain (never a file of the user's, never one being written now) and go.
 */
object Parts {
    const val PREFIX = ".swift-"
    const val SUFFIX = ".part"
    private const val JOURNAL = "file-parts.txt"

    /** Past this size the list is written again with only what is still open. */
    private const val COMPACT_BYTES = 256L * 1024

    private val live: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val lock = Any()
    private var out: FileOutputStream? = null

    /** Whether [name] is the name of one of Swift's unfinished copies. */
    fun isPart(name: String) = name.length > PREFIX.length + SUFFIX.length && name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** A new part's file in [dir] ([tag]: "up-" for Wi-Fi uploads). */
    fun newIn(dir: File?, tag: String = ""): File = File(dir, PREFIX + tag + System.nanoTime() + SUFFIX)

    /** Being written now, while Swift runs. */
    fun isLive(f: File) = f.path in live

    /** Writing [f] begins. */
    fun begin(ctx: Context, f: File) {
        live += f.path
        note(ctx, "+", f)
    }

    /** [f] has its own name now, or was deleted. */
    fun end(ctx: Context, f: File) {
        if (live.remove(f.path)) note(ctx, "-", f)
    }

    private fun journal(ctx: Context) = File(ctx.applicationContext.filesDir, JOURNAL)

    private fun note(ctx: Context, sign: String, f: File) {
        synchronized(lock) {
            try {
                val o = out ?: FileOutputStream(journal(ctx), true).also { out = it }
                // One write each, straight to the file: kept even if Swift is closed the next moment.
                o.write((sign + f.path + "\n").toByteArray())
            } catch (e: Exception) {
                L.w("Parts: the list couldn't be written (${e.javaClass.simpleName})")
            }
        }
    }

    /** Parts begun and never finished, in the order they began. */
    private fun open(file: File): LinkedHashSet<String> {
        val open = LinkedHashSet<String>()
        file.forEachLine { l ->
            when {
                l.startsWith("+") -> open += l.substring(1)
                l.startsWith("-") -> open -= l.substring(1)
            }
        }
        return open
    }

    /**
     * Deletes the parts a closed Swift left (begun, never finished, not being written now) and says
     * how many went. Not on the main thread.
     */
    fun clearLeftovers(ctx: Context): Int {
        synchronized(lock) {
            val file = journal(ctx)
            if (!file.isFile) return 0
            val open = try {
                open(file)
            } catch (e: Exception) {
                L.w("Parts: the list couldn't be read (${e.javaClass.simpleName})")
                return 0
            }
            val keep = ArrayList<String>()
            var cleared = 0
            for (path in open) {
                val f = File(path)
                when {
                    path in live -> keep += path
                    // Never anything but a part, and nothing that went already.
                    !isPart(f.name) || !f.exists() -> {}
                    else -> {
                        val access = Volumes.accessFor(ctx, f)
                        val gone = access != null && (runCatching { access.delete(f) }.getOrDefault(false) || !f.exists())
                        if (gone) cleared++ else keep += path
                    }
                }
            }
            rewrite(file, keep)
            if (cleared > 0) L.d("Parts: $cleared left by a closed Swift deleted")
            return cleared
        }
    }

    /** The list written again with only what is still open, once it has grown (after a big job). */
    fun compactIfBig(ctx: Context) {
        synchronized(lock) {
            val file = journal(ctx)
            if (!file.isFile || file.length() < COMPACT_BYTES) return
            try {
                rewrite(file, open(file).toList())
            } catch (e: Exception) {
                L.w("Parts: the list couldn't be tidied (${e.javaClass.simpleName})")
            }
        }
    }

    private fun rewrite(file: File, paths: List<String>) {
        runCatching { out?.close() }
        out = null
        try {
            if (paths.isEmpty()) file.delete() else file.writeText(paths.joinToString("") { "+$it\n" })
        } catch (e: Exception) {
            L.w("Parts: the list couldn't be written again (${e.javaClass.simpleName})")
        }
    }
}
