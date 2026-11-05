package com.swiftbrowser.files

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.ui.Dialogs
import com.swiftbrowser.ui.Messages
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The recycle bin (2.9): what is deleted in Files goes to a hidden folder on its own storage
 * (".SwiftBin" at the top of the phone's storage or the SD card, with a .nomedia file so the
 * Gallery and music apps leave it alone). Moving it there is a rename, so it is instant however big
 * it is, and bringing it back is one too. Each thing gets a folder of its own in the bin, named by
 * when it was deleted, so two "photo.jpg" never meet; the bin's index.json remembers where each was
 * and when it went (and travels with an SD card). After [FilesSettings.binDays] days (30 unless
 * chosen otherwise) things go for good: checked when Files starts and once a day.
 *
 * Swift's own folders (and Private, which must never land on shared storage) have no bin: what is
 * deleted there is asked about and erased.
 *
 * 3.0: each thing's folder in the bin also holds a small file of its own details (where it was, when
 * it went), used when the index lacks it (Swift closed before writing it), so it still goes back
 * where it was. Moving in is only the rename: sizes are counted afterwards, when the bin is looked at,
 * so Undo shows at once. The clean-up doesn't trust a clock that was reset (a date before 2020, or
 * one still to come, counts as now), so it never erases everything at once.
 */
object RecycleBin {
    const val FOLDER = ".SwiftBin"
    private const val INDEX = "index.json"
    private const val NOMEDIA = ".nomedia"

    /** 3.0: a thing's own details, in its folder in the bin. */
    private const val META = ".swift-bin.json"
    private const val DAY = 24L * 3600 * 1000
    private const val PREF_CHECKED = "files_bin_checked"

    /** One thing in the bin: [file] is where it is now, [home] where it goes back to. */
    class Item internal constructor(
        val id: String,
        val volume: Volumes.Volume,
        val name: String,
        /** Its folder, from the top of its storage ("Download/Swift"; "" for the top); null when not known. */
        val from: String?,
        val deletedAt: Long,
        val size: Long,
        val isDir: Boolean,
        /** How many files it is (a folder's, counted when it was deleted). */
        val files: Int,
    ) {
        val holder: File get() = File(File(volume.root, FOLDER), id)
        val file: File get() = File(holder, name)
        val home: File get() = if (from.isNullOrEmpty()) volume.root else File(volume.root, from)

        /** When it goes for good. */
        fun goesAt(days: Int = FilesSettings.binDays) = deletedAt + days * DAY

        /** Whole days left before it goes (0: today). */
        fun daysLeft(now: Long = System.currentTimeMillis(), days: Int = FilesSettings.binDays): Int =
            ((goesAt(days) - now + DAY - 1) / DAY).toInt().coerceAtLeast(0)

        /** 3.0: the same thing with other details (its size once counted, its date made sane). */
        internal fun with(size: Long = this.size, files: Int = this.files, deletedAt: Long = this.deletedAt) =
            Item(id, volume, name, from, deletedAt, size, isDir, files)
    }

    /** What one deletion did: [items] went to the bin (for Undo), [failed] couldn't. */
    class Batch(val items: List<Item>, val failed: List<File>)

    class Summary(val count: Int, val bytes: Long)

    private val worker = Background.single("recycle-bin")
    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()
    private val lock = Any()

    // ------------------------------------------------------------------ what may go in

    private fun binOf(v: Volumes.Volume) = File(v.root, FOLDER)

    private fun inBin(v: Volumes.Volume, f: File) = binOf(v).let { b -> f.path == b.path || f.path.startsWith(b.path + "/") }

    /** Whether [f] can go to the bin: on the phone's storage or an SD card, but not a storage's top or the bin itself. */
    fun canHold(ctx: Context, f: File): Boolean {
        val v = Volumes.of(ctx, f) ?: return false
        return f.path != v.root.path && !inBin(v, f)
    }

    /** Whether [f] is in the bin (or is the bin). */
    fun isInBin(ctx: Context, f: File): Boolean = Volumes.of(ctx, f)?.let { inBin(it, f) } == true

    /**
     * 3.0: the bins [files] would go to, to ask for a card's access before deleting: a file in Swift's
     * own folder on the card needs none itself, but the bin at the card's top does.
     */
    fun binsFor(ctx: Context, files: Collection<File>): List<File> =
        files.mapNotNull { f -> Volumes.of(ctx, f)?.takeIf { canHold(ctx, f) }?.let { binOf(it) } }.distinctBy { it.path }

    // ------------------------------------------------------------------ for other screens

    /** Moves [files] to the bin (off the main thread); [done] on the main thread with how many went. */
    fun put(ctx: Context, files: List<File>, done: (moved: Int, failed: Int) -> Unit) =
        move(ctx, files) { b -> done(b.items.size, b.failed.size) }

    /** As [put], with what went (for Undo through [restore]). */
    fun move(ctx: Context, files: List<File>, done: (Batch) -> Unit) {
        val app = ctx.applicationContext
        val list = files.toList()
        worker.execute {
            val b = try {
                moveNow(app, list)
            } catch (e: Exception) {
                L.w("Recycle bin: moving failed (${e.javaClass.simpleName})")
                Batch(emptyList(), list)
            }
            // 3.0.4: the folders they left, and the bin, are measured again (FolderSizes).
            FolderSizes.changed(list.mapNotNull { it.parentFile?.path } + b.items.map { binOf(it.volume).path })
            main.post { done(b) }
        }
    }

    /**
     * Deleting from another screen in one call: to the bin with "Moved … to the recycle bin · Undo"
     * (Undo brings it back), or, when the bin is off or can't take them, asked about and erased.
     * [changed] runs on the main thread after each change, to show the list again.
     */
    fun delete(a: Activity, files: List<File>, bottomOffset: Int = 0, changed: () -> Unit) {
        if (files.isEmpty()) return
        val what = if (files.size == 1) "“${files[0].name}”" else "${files.size} items"
        if (!FilesSettings.useRecycleBin || files.any { !canHold(a, it) }) {
            Dialogs.confirm(a, "Delete $what?", "Swift can't bring " + (if (files.size == 1) "it" else "them") + " back.", "Delete", danger = true) {
                eraseFiles(a, files) { failed ->
                    if (failed > 0 && !a.isFinishing) Messages.show(a, "Swift couldn't delete $failed of them", bottomOffset = bottomOffset)
                    changed()
                }
            }
            return
        }
        move(a, files) { b ->
            changed()
            if (a.isFinishing) return@move
            if (b.items.isNotEmpty()) {
                Messages.show(a, "Moved " + movedText(b.items) + " to the recycle bin", "Undo", bottomOffset = bottomOffset) {
                    restore(a, b.items) { back, _ ->
                        changed()
                        // 3.0: put back under another name (the old one was taken meanwhile): said.
                        renamedText(b.items, back)?.let { if (!a.isFinishing) Messages.show(a, it, bottomOffset = bottomOffset) }
                    }
                }
            }
            if (b.failed.isNotEmpty()) {
                Dialogs.confirm(a, "Delete for good?", "${couldntText(b.failed)} Swift can't bring " + (if (b.failed.size == 1) "it" else "them") + " back once deleted.", "Delete", danger = true) {
                    eraseFiles(a, b.failed) { changed() }
                }
            }
        }
    }

    /** "“photo.jpg”" or "3 items". */
    fun movedText(items: List<Item>) = if (items.size == 1) "“${items[0].name}”" else "${items.size} items"

    /**
     * 3.0: when some of [items] came back ([back]) under new names, because the old ones were taken
     * meanwhile: "Restored as “photo (1).jpg”", or how many got new names. Null when none did.
     */
    fun renamedText(items: List<Item>, back: List<File>): String? {
        val homes = items.map { File(it.home, it.name).path }.toHashSet()
        val renamed = back.filter { it.path !in homes }
        return when {
            renamed.isEmpty() -> null
            back.size == 1 -> "Restored as “${renamed[0].name}”: the old name was taken"
            renamed.size == 1 -> "1 of them came back as “${renamed[0].name}”: the old name was taken"
            else -> "${renamed.size} of them came back with new names: the old ones were taken"
        }
    }

    /** Why some didn't go to the bin, in a sentence. */
    fun couldntText(failed: List<File>) =
        (if (failed.size == 1) "“${failed[0].name}” couldn't go to the recycle bin" else "${failed.size} items couldn't go to the recycle bin") +
            " (there may be no room for it there, or Android didn't allow it)."

    /** Deletes [files] for good, not through the bin; [done] on the main thread with how many couldn't be. */
    fun eraseFiles(ctx: Context, files: List<File>, done: (failed: Int) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            var failed = 0
            for (f in files) {
                val access = Volumes.accessFor(app, f)
                val ok = access != null && runCatching { access.delete(f) }.getOrDefault(false)
                if (ok) FileOps.forget(app, f) else failed++
            }
            FolderSizes.changed(files.mapNotNull { it.parentFile?.path })
            main.post { done(failed) }
        }
    }

    // ------------------------------------------------------------------ the bin's own actions

    /** Puts [items] back where they were (with a new name when that one is taken); [done] with the files as they are now. */
    fun restore(ctx: Context, items: List<Item>, done: (restored: List<File>, failed: Int) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val r = try {
                restoreNow(app, items)
            } catch (e: Exception) {
                L.w("Recycle bin: restoring failed (${e.javaClass.simpleName})")
                emptyList<File>() to items.size
            }
            FolderSizes.changed(items.map { it.home.path } + items.map { binOf(it.volume).path })
            main.post { done(r.first, r.second) }
        }
    }

    /** Deletes [items] for good; [done] with how many went. */
    fun erase(ctx: Context, items: List<Item>, done: (Int) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val n = runCatching { eraseNow(app, items) }.getOrDefault(0)
            FolderSizes.changed(items.map { binOf(it.volume).path })
            main.post { done(n) }
        }
    }

    /** Deletes everything in the bin, on every storage; [done] with how many things went. */
    fun empty(ctx: Context, done: (Int) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val n = runCatching { synchronized(lock) { Volumes.refresh(app).sumOf { v -> emptyVolume(app, v) } } }.getOrDefault(0)
            FolderSizes.changed(Volumes.all(app).map { binOf(it).path })
            main.post { done(n) }
        }
    }

    /**
     * What is in the bin on every storage that is in, newest first. Reads files: not on the main
     * thread. 3.0: things whose size wasn't counted yet are counted now, and written down.
     */
    fun list(ctx: Context): List<Item> = synchronized(lock) {
        Volumes.all(ctx).flatMap { v ->
            val (items, stale) = listVolume(v, measure = true)
            // What was counted or found out just now is written down.
            if (stale) writeIndex(ctx, v, items)
            items
        }.sortedByDescending { it.deletedAt }
    }

    /** 3.0: what [days] days in the bin (rather than the days chosen now) would delete for good now; [done] on the main thread. */
    fun dueSummary(ctx: Context, days: Int, done: (Summary) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val now = System.currentTimeMillis()
            val due = runCatching { list(app) }.getOrDefault(emptyList()).filter { BinClock.due(it.deletedAt, now, days) }
            val s = Summary(due.size, due.sumOf { it.size.coerceAtLeast(0) })
            main.post { done(s) }
        }
    }

    /** How many things the bin holds and their size; [done] on the main thread. */
    fun summary(ctx: Context, done: (Summary) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val s = summaryNow(app)
            main.post { done(s) }
        }
    }

    /** As [summary], answered here (not on the main thread). */
    fun summaryNow(ctx: Context): Summary = runCatching { list(ctx) }.getOrDefault(emptyList()).let { all ->
        Summary(all.size, all.sumOf { it.size.coerceAtLeast(0) })
    }

    /** Things past their time go for good: at once when [force], otherwise at most once a day. */
    fun purgeSoon(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val sp = SwiftApp.prefs.sp
        val last = sp.getLong(PREF_CHECKED, 0L)
        if (!force && now - last in 0 until DAY) return
        sp.edit().putLong(PREF_CHECKED, now).apply()
        val app = ctx.applicationContext
        worker.execute {
            try {
                purgeNow(app)
            } catch (e: Exception) {
                L.w("Recycle bin: clean-up failed (${e.javaClass.simpleName})")
            }
            FolderSizes.changed(Volumes.all(app).map { binOf(it).path })
        }
    }

    // ------------------------------------------------------------------ the work (on the bin's thread)

    private fun moveNow(ctx: Context, files: List<File>): Batch {
        val now = System.currentTimeMillis()
        val moved = ArrayList<Item>()
        val failed = ArrayList<File>()
        for (f in files) {
            // Gone already (deleted elsewhere meanwhile): nothing to keep, and nothing failed.
            if (!f.exists()) continue
            val item = try {
                moveOne(ctx, f, now)
            } catch (e: Exception) {
                L.w("Recycle bin: one item couldn't move (${e.javaClass.simpleName})")
                null
            }
            if (item == null) failed += f else moved += item
        }
        if (moved.isNotEmpty()) {
            synchronized(lock) {
                for ((root, added) in moved.groupBy { it.volume.root.path }) {
                    val v = added[0].volume
                    // The new ones show as unknown folders until written down: their own details replace those.
                    val all = LinkedHashMap<String, Item>()
                    listVolume(v, added.map { it.id }.toSet()).first.forEach { all[it.id] = it }
                    added.forEach { all[it.id] = it }
                    if (!writeIndex(ctx, v, all.values)) L.w("Recycle bin: couldn't write the index on ${if (v.removable) "the card" else "the phone"} ($root)")
                }
            }
        }
        L.d("Recycle bin: ${moved.size} moved in, ${failed.size} couldn't")
        return Batch(moved, failed)
    }

    private fun moveOne(ctx: Context, f: File, now: Long): Item? {
        val v = Volumes.of(ctx, f) ?: return null
        if (f.path == v.root.path || inBin(v, f)) return null
        val bin = binOf(v)
        // One way to change the storage for both ends of the move (a card's document access covers all of it).
        val access = Volumes.accessFor(ctx, bin) ?: return null
        if (!ready(access, bin)) return null
        val isDir = f.isDirectory
        // 3.0: a file's size is at hand; a folder's is counted later (the move is a rename, done at once).
        val size = if (isDir) -1L else f.length()
        val id = "${now}_${seq.incrementAndGet()}"
        val holder = File(bin, id)
        if (!access.mkdir(holder)) return null
        if (!access.moveInto(f, holder) || !File(holder, f.name).exists()) {
            if (holder.list().isNullOrEmpty()) runCatching { access.delete(holder) }
            return null
        }
        FileOps.forget(ctx, f)
        val from = f.parentFile?.path?.removePrefix(v.root.path)?.trim('/').orEmpty()
        val item = Item(id, v, f.name, from, now, size, isDir, if (isDir) -1 else 1)
        writeMeta(access, item)
        return item
    }

    /**
     * 3.0: [item]'s details in its own folder in the bin, for when the index lacks it (Swift closed
     * before the index was written, or the index was lost). Not a problem if it can't be written.
     */
    private fun writeMeta(access: FileAccess, item: Item) {
        val bytes = JSONObject().apply {
            put("name", item.name)
            put("from", item.from ?: JSONObject.NULL)
            put("at", item.deletedAt)
            put("dir", item.isDir)
        }.toString().toByteArray()
        try {
            val meta = File(item.holder, META)
            if (access is PlainAccess) meta.writeBytes(bytes) else access.output(meta).use { it.write(bytes) }
        } catch (e: Exception) {
            L.w("Recycle bin: a thing's own details couldn't be written (${e.javaClass.simpleName})")
        }
    }

    /** 3.0: [one], the thing in [holder], with the details [writeMeta] kept beside it (null when there are none). */
    private fun readMeta(v: Volumes.Volume, holder: File, one: File): Item? = try {
        val meta = File(holder, META)
        if (!meta.isFile || meta.length() > 64 * 1024) {
            null
        } else {
            val o = JSONObject(meta.readText())
            val from = if (o.isNull("from")) null else safeFrom(o.optString("from"))
            Item(holder.name, v, one.name, from, o.optLong("at", holder.lastModified()), -1, one.isDirectory, if (one.isDirectory) -1 else 1)
        }
    } catch (_: Exception) {
        null
    }

    /** The bin's folder is there, with its .nomedia file. */
    private fun ready(access: FileAccess, bin: File): Boolean {
        if (!bin.isDirectory && !access.mkdir(bin)) return false
        val nomedia = File(bin, NOMEDIA)
        if (!nomedia.exists()) {
            try {
                access.output(nomedia).close()
            } catch (_: Exception) {
            }
        }
        return true
    }

    private fun restoreNow(ctx: Context, items: List<Item>): Pair<List<File>, Int> {
        val restored = ArrayList<File>()
        var failed = 0
        val gone = HashSet<String>()
        for (item in items) {
            val ok = try {
                restoreOne(ctx, item)
            } catch (e: Exception) {
                L.w("Recycle bin: one item couldn't go back (${e.javaClass.simpleName})")
                null
            }
            if (ok == null) failed++ else {
                restored += ok
                gone += item.id
            }
        }
        if (gone.isNotEmpty()) synchronized(lock) { dropFromIndex(ctx, items.filter { it.id in gone }) }
        // Back in the Gallery and music apps (3.0: every file of a folder, not the first few hundred).
        FileOps.scan(ctx, restored.filter { !it.isDirectory }.map { it.path })
        restored.filter { it.isDirectory }.forEach { FileOps.scanTree(ctx, it) }
        L.d("Recycle bin: ${restored.size} put back, $failed couldn't")
        return restored to failed
    }

    private fun restoreOne(ctx: Context, item: Item): File? {
        val src = item.file
        if (!src.exists()) return null
        val access = Volumes.accessFor(ctx, binOf(item.volume)) ?: return null
        val dir = item.home
        if (!dir.isDirectory && !access.mkdirs(dir)) return null
        var target = File(dir, item.name)
        var moving = src
        if (target.exists()) {
            // The name is taken there now: "photo (1).jpg", given in the bin, as a move keeps the name.
            target = FileOps.freeName(target)
            if (!access.rename(src, target.name)) return null
            moving = File(item.holder, target.name)
        }
        if (!access.moveInto(moving, dir) || !target.exists()) return null
        runCatching { access.delete(item.holder) }
        return target
    }

    private fun eraseNow(ctx: Context, items: List<Item>): Int {
        val gone = ArrayList<Item>()
        for (item in items) {
            val access = Volumes.accessFor(ctx, binOf(item.volume)) ?: continue
            if (runCatching { access.delete(item.holder) }.getOrDefault(false) || !item.holder.exists()) gone += item
        }
        if (gone.isNotEmpty()) synchronized(lock) { dropFromIndex(ctx, gone) }
        L.d("Recycle bin: ${gone.size} deleted for good")
        return gone.size
    }

    private fun emptyVolume(ctx: Context, v: Volumes.Volume): Int {
        val bin = binOf(v)
        if (!bin.isDirectory) return 0
        val access = Volumes.accessFor(ctx, bin) ?: return 0
        var n = 0
        for (c in bin.listFiles().orEmpty()) {
            if (!c.isDirectory) continue
            if (runCatching { access.delete(c) }.getOrDefault(false) || !c.exists()) n++
        }
        writeIndex(ctx, v, emptyList())
        L.d("Recycle bin emptied ($n)")
        return n
    }

    private fun purgeNow(ctx: Context) {
        val now = System.currentTimeMillis()
        // 3.0: a clock before 2020 is wrong (a phone that lost its time): nothing goes until it is right.
        if (!BinClock.sane(now)) {
            L.w("Recycle bin: the phone's clock looks wrong, nothing deleted")
            return
        }
        val days = FilesSettings.binDays
        synchronized(lock) {
            for (v in Volumes.refresh(ctx)) {
                val bin = binOf(v)
                if (!bin.isDirectory) continue
                val (listed, changed) = listVolume(v)
                // 3.0: a date before 2020 or still to come (written while the clock was wrong) counts from now.
                val items = listed.map { if (BinClock.fixed(it.deletedAt, now) != it.deletedAt) it.with(deletedAt = now) else it }
                val redated = items.indices.any { items[it] !== listed[it] }
                val due = items.filter { BinClock.due(it.deletedAt, now, days) }
                if (due.isEmpty() && !changed && !redated) continue
                val access = Volumes.accessFor(ctx, bin) ?: continue
                val gone = due.filter { runCatching { access.delete(it.holder) }.getOrDefault(false) || !it.holder.exists() }.map { it.id }.toSet()
                writeIndex(ctx, v, items.filter { it.id !in gone })
                if (gone.isNotEmpty()) L.d("Recycle bin: ${gone.size} past their time deleted")
                if (redated) L.w("Recycle bin: dates from a wrong clock counted from now")
            }
        }
    }

    // ------------------------------------------------------------------ the index

    private val ID = Regex("[0-9]+_[0-9]+")

    /** A name read from the index: one plain name, never a way out of its folder. */
    private fun safeName(s: String?): String? = s?.takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.contains('/') && !it.contains('\u0000') }

    /** A folder read from the index, from the top of the storage; null when it isn't one. */
    private fun safeFrom(s: String?): String? = s?.trim('/')?.takeIf { p -> p.split('/').none { it == ".." || it == "." || it.contains('\u0000') } }

    /**
     * The bin on [v] as it is: what the index says, for things still there; and things in the bin
     * the index doesn't know (it was lost, or written before them): 3.0: with the details kept in
     * their own folder, otherwise dated by their folder. With [measure], sizes not counted yet are
     * counted. The second value tells that the index is out of date.
     */
    private fun listVolume(v: Volumes.Volume, except: Set<String> = emptySet(), measure: Boolean = false): Pair<List<Item>, Boolean> {
        val bin = binOf(v)
        val dirs = bin.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (dirs.isEmpty()) return emptyList<Item>() to File(bin, INDEX).let { it.exists() && it.length() > 2 }
        val known = HashMap<String, Item>()
        var stale = false
        try {
            val text = File(bin, INDEX).takeIf { it.isFile }?.readText()
            if (text != null) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { ID.matches(it) } ?: continue
                    val name = safeName(o.optString("name")) ?: continue
                    val from = if (o.isNull("from")) null else safeFrom(o.optString("from"))
                    known[id] = Item(id, v, name, from, o.optLong("at"), o.optLong("size"), o.optBoolean("dir"), o.optInt("files", 1))
                }
            }
        } catch (e: Exception) {
            L.w("Recycle bin: the index couldn't be read (${e.javaClass.simpleName})")
            stale = true
        }
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (d in dirs) {
            // Just moved in, with their details at hand: not measured again.
            if (d.name in except) continue
            val k = known[d.name]
            if (k != null && k.file.exists()) {
                out += k
                seen += k.id
                continue
            }
            // Not in the index: what its folder holds (not its own details file).
            val inside = d.listFiles().orEmpty().filter { it.name != META }
            val one = inside.firstOrNull() ?: continue
            if (!ID.matches(d.name)) continue
            stale = true
            // 3.0: where it was and when, from its own details; otherwise not known, dated by its folder.
            out += readMeta(v, d, one) ?: Item(d.name, v, one.name, null, d.lastModified(), -1, one.isDirectory, if (one.isDirectory) -1 else 1)
            seen += d.name
        }
        if (known.keys.any { it !in seen }) stale = true
        if (measure) {
            for (i in out.indices) {
                val item = out[i]
                if (item.size >= 0 && (!item.isDir || item.files >= 0)) continue
                val t = FileOps.measure(listOf(item.file))
                out[i] = item.with(size = t.bytes, files = if (item.isDir) t.files else 1)
                stale = true
            }
        }
        return out to stale
    }

    /** Takes [items] out of their storages' indexes. */
    private fun dropFromIndex(ctx: Context, items: List<Item>) {
        for ((_, group) in items.groupBy { it.volume.root.path }) {
            val v = group[0].volume
            val ids = group.map { it.id }.toSet()
            writeIndex(ctx, v, listVolume(v).first.filter { it.id !in ids })
        }
    }

    private fun writeIndex(ctx: Context, v: Volumes.Volume, items: Collection<Item>): Boolean {
        val bin = binOf(v)
        if (!bin.isDirectory) return items.isEmpty()
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("from", it.from ?: JSONObject.NULL)
                put("at", it.deletedAt)
                put("size", it.size)
                put("dir", it.isDir)
                put("files", it.files)
            })
        }
        val bytes = arr.toString().toByteArray()
        val index = File(bin, INDEX)
        return try {
            val access = Volumes.accessFor(ctx, bin) ?: return false
            if (access is PlainAccess) {
                // Written aside, then swapped in: a phone that dies meanwhile keeps the old index.
                val tmp = File(bin, "$INDEX.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(index)) {
                    index.writeBytes(bytes)
                    tmp.delete()
                }
            } else {
                access.output(index).use { it.write(bytes) }
            }
            true
        } catch (e: Exception) {
            L.w("Recycle bin: the index couldn't be written (${e.javaClass.simpleName})")
            false
        }
    }
}

/**
 * The recycle bin's sense of time (3.0), which doesn't trust a clock that was reset: a phone that lost
 * its time (a dead battery) says 1970 or 2000, and the bin mustn't erase everything then, nor keep
 * for ever what it was given while the clock was wrong.
 */
object BinClock {
    /** 1 January 2020: nothing was deleted in Swift before. */
    const val EARLIEST = 1_577_836_800_000L
    private const val DAY = 24L * 3600 * 1000

    /** Whether [now] can be the true time. */
    fun sane(now: Long) = now >= EARLIEST

    /** [deletedAt] as the bin counts it at [now]: a date before 2020 or still to come counts as now. */
    fun fixed(deletedAt: Long, now: Long): Long = if (deletedAt < EARLIEST || deletedAt > now) now else deletedAt

    /** Whether a thing deleted at [deletedAt] goes for good at [now], when things are kept [days] days. */
    fun due(deletedAt: Long, now: Long, days: Int): Boolean = sane(now) && now > fixed(deletedAt, now) + days * DAY
}
