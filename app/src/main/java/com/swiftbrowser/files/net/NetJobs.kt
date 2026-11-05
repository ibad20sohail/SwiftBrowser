package com.swiftbrowser.files.net

import android.content.Context
import android.os.StatFs
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.docs.Docs
import com.swiftbrowser.download.Downloads
import com.swiftbrowser.files.FileAccess
import com.swiftbrowser.files.FileErrors
import com.swiftbrowser.files.FileIndex
import com.swiftbrowser.files.FileJobs
import com.swiftbrowser.files.FileProblem
import com.swiftbrowser.files.PlainAccess
import com.swiftbrowser.files.Volumes
import java.io.File

/**
 * 3.0.4: network folders' files onto the phone, as Files' own copies go (FileJobs: in the background,
 * with their progress on the screen and in the notification shade, and Cancel): into Swift's
 * download folder (Download/Swift) in a folder named after the server, folders with all they hold;
 * and one file into Swift's cache, to open it in Swift's own viewers.
 */
object NetJobs {
    /** What is copied: a file or folder of a share, as listed. */
    class Item(val name: String, val path: String, val isDir: Boolean, val size: Long, val time: Long)

    /** Files bigger than this aren't copied into the cache to be opened: they are copied to the phone instead. */
    const val OPEN_MAX = 300L * 1_048_576

    /** A folder and what it holds, read through before copying (to count it, and to say early when it won't fit). */
    private class Node(val item: Item, val children: List<Node>) {
        val bytes: Long = if (item.isDir) children.sumOf { it.bytes } else item.size.coerceAtLeast(0)
        val files: Int = if (item.isDir) children.sumOf { it.files } else 1
        val biggest: Long = if (item.isDir) children.maxOfOrNull { it.biggest } ?: 0L else item.size
    }

    private const val MAX_FILES = 50_000
    private const val MAX_DEPTH = 40

    /** Copies [items] of [share] to the phone: into Download/Swift/<server's name>/. */
    fun copyToPhone(ctx: Context, server: NetServer, share: String, items: List<Item>, onDone: (FileJobs.Job) -> Unit): FileJobs.Job =
        FileJobs.start(ctx, FileJobs.Kind.COPY, "Copying from ${server.title}", onDone) { w -> copy(w, server, share, items) }

    private fun copy(w: FileJobs.Work, server: NetServer, share: String, items: List<Item>) {
        val ctx = w.ctx
        val base = Downloads.directory()
        val dest = File(base, NetPath.localName(server.title))
        w.destination(dest)
        w.counting()
        var count = 0
        fun read(item: Item, depth: Int): Node {
            w.check()
            if (!item.isDir) {
                count++
                return Node(item, emptyList())
            }
            if (depth > MAX_DEPTH || count > MAX_FILES) throw FileProblem("“${item.name}” holds too much to copy at once. Copy the folders in it one by one.")
            w.current(item.name)
            val inside = NetClient.list(ctx, server, share, item.path).map { e -> read(Item(e.name, e.path, e.isDir, e.size, e.time), depth + 1) }
            return Node(item, inside)
        }
        val tree = try {
            items.map { read(it, 0) }
        } catch (e: NetFailure) {
            throw FileProblem(problemText(server, e))
        }
        w.totals(tree.sumOf { it.files }, tree.sumOf { it.bytes })
        val needed = tree.sumOf { it.bytes }
        val free = runCatching { StatFs(base.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val room = (free - FileJobs.MARGIN).coerceAtLeast(0)
        if (needed > room) {
            throw FileProblem("There isn't enough free space: this needs ${UrlUtils.formatBytes(needed)}, and ${UrlUtils.formatBytes(room)} can be used.")
        }
        val volume = Volumes.of(ctx, base)
        if (volume != null && Volumes.isFat32(volume) && tree.any { it.biggest > FileErrors.FAT32_MAX }) throw FileProblem(FileErrors.FAT32)
        val access = Volumes.accessFor(ctx, dest)
            ?: throw FileProblem("Swift may not write to the SD card yet. Open Files, go to the card and allow it, then copy again.")
        if (!access.mkdirs(dest)) throw FileProblem("Swift couldn't make the folder “${dest.name}” in ${FileIndex.placeOf(ctx, base)}.")
        if (free != Long.MAX_VALUE) w.limit = w.written + room
        w.touched(base)
        w.touched(dest)
        for (n in tree) put(w, server, share, n, dest, access)
        val what = if (items.size == 1) "“${items[0].name}”" else FileJobs.items(items.size)
        w.message(buildString {
            append("Copied ").append(what).append(" to ").append(FileIndex.placeOf(ctx, dest))
            if (w.job.skipped > 0) append(", skipped ").append(w.job.skipped)
        })
        w.result(dest)
    }

    /** Puts [n] into [into]: a folder (with all it holds) or a file, its name asked about when taken. */
    private fun put(w: FileJobs.Work, server: NetServer, share: String, n: Node, into: File, access: FileAccess) {
        w.check()
        val target = w.resolve(File(into, NetPath.localName(n.item.name)), n.item.isDir) ?: run {
            // Skipped: its bytes count as done, so the bar still ends full.
            w.progress(n.bytes)
            return
        }
        if (n.item.isDir) {
            if (!access.mkdirs(target)) throw FileProblem("Swift couldn't make the folder “${target.name}”.")
            w.touched(target)
            for (c in n.children) put(w, server, share, c, target, access)
            return
        }
        w.current(n.item.name)
        fetch(w, server, share, n.item, target, access)
        w.fileDone()
    }

    /** Copies one file of the share into [target] (written aside first, then given its name: see FileJobs.write). */
    private fun fetch(w: FileJobs.Work, server: NetServer, share: String, item: Item, target: File, access: FileAccess) {
        val f = try {
            NetClient.open(w.ctx, server, share, item.path)
        } catch (e: NetFailure) {
            throw FileProblem(problemText(server, e))
        }
        try {
            w.write(target, access, item.time.takeIf { it > 0 } ?: System.currentTimeMillis()) { out ->
                f.inputStream.use { input -> w.copy(input, out) }
            }
        } catch (e: FileProblem) {
            throw e
        } catch (e: com.swiftbrowser.files.Cancelled) {
            throw e
        } catch (e: java.io.IOException) {
            // The phone's own problems (a full card) are said by FileJobs; the computer's here.
            if (NetErrors.of(e, server.guest) != NetProblem.OTHER) throw FileProblem(problemText(server, e))
            throw e
        } catch (e: RuntimeException) {
            // smbj's own errors while reading (a share gone, a file locked).
            throw FileProblem(problemText(server, e))
        } finally {
            runCatching { f.closeNoWait() }
        }
    }

    private fun problemText(server: NetServer, e: Throwable): String =
        NetErrors.text((e as? NetFailure)?.problem ?: NetErrors.of(e, server.guest), server.host, server.title)

    /**
     * Copies [item] into Swift's cache to open it there (unless the same is there already, from an
     * earlier opening). The cache keeps what was opened lately for a week (Docs).
     */
    fun openCopy(ctx: Context, server: NetServer, share: String, item: Item, onDone: (FileJobs.Job) -> Unit): FileJobs.Job =
        FileJobs.start(ctx, FileJobs.Kind.OPEN, "Opening ${item.name}", onDone) { w ->
            val folder = File(ctx.cacheDir, "docs/network/" + Docs.idFor(server.id + "|" + share + "|" + item.path + "|" + item.size + "|" + item.time).take(16))
            val target = File(folder, NetPath.localName(item.name))
            if (!(target.isFile && (item.size < 0 || target.length() == item.size))) {
                val free = runCatching { StatFs(ctx.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
                val room = (free - FileJobs.MARGIN).coerceAtLeast(0)
                if (item.size > room) throw FileProblem("There isn't enough free space on the phone to open this.")
                if (!PlainAccess.mkdirs(folder)) throw FileProblem("Swift couldn't open “${item.name}”.")
                w.replaceAll()
                w.totals(1, item.size.coerceAtLeast(0))
                w.current(item.name)
                if (free != Long.MAX_VALUE) w.limit = w.written + room
                fetch(w, server, share, item, target, PlainAccess)
            }
            // Dated today: Swift's cache keeps what was opened lately for a week.
            target.setLastModified(System.currentTimeMillis())
            w.result(target)
        }
}
