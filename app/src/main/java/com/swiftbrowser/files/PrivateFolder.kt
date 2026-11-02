package com.swiftbrowser.files

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom

/**
 * Private (2.9): files moved into Swift's own storage (its files folder, "private"), where no other
 * app, the Gallery or a computer can see them. Opening it asks for the phone's screen lock, or for
 * a PIN of Swift's own on a phone without one (kept only as a salted hash). It locks again when Files
 * is left, or after a minute out of sight (a file opened from it, Android's own lock question).
 * The files aren't encrypted: they are as safe as Swift's own data, and they go when Swift is
 * uninstalled or its data cleared, which the screen says.
 *
 * 3.0: once Private has a PIN, it keeps it: a screen lock added to the phone later no longer opens
 * Private nor removes the PIN (phones that had a screen lock when Private was first set up go on using
 * it, with no PIN). A forgotten PIN can be reset after a day's wait ("Forgot PIN"), with a new one or
 * the screen lock; opening Private with the right PIN meanwhile shows that a reset was asked for. Wrong
 * PINs wait longer and longer (PinWait), and the PIN is kept with a slower hash (PinHash).
 */
object PrivateFolder {
    private const val DIR = "private"
    private const val PREF_PIN = "files_private_pin"
    private const val PREF_TRIES = "files_private_tries"

    /** 2.9's wait (a time on the clock, which could be put forward): no longer used. */
    private const val PREF_WAIT_OLD = "files_private_wait_until"

    /** 3.0: the wait after wrong PINs (a Wait). */
    private const val PREF_WAIT = "files_private_wait"

    /** 3.0: a PIN reset asked for: when (on the clock), then its Wait ("<time>|<wait>"). */
    private const val PREF_RESET = "files_private_reset"
    private const val PREF_ORIGINS = "files_private_origins"

    /** The first time Private opens, what it is is explained once. */
    const val PREF_INTRO = "files_private_intro"

    /** Out of sight this long (a file opened from Private, another app), Private locks. */
    private const val AWAY_MS = 60_000L

    private val sp get() = SwiftApp.prefs.sp
    private val worker = Background.single("private-folder")
    private val main = Handler(Looper.getMainLooper())

    fun root(ctx: Context): File = File(ctx.filesDir, DIR)

    /** Whether [f] is Private's folder or inside it. */
    fun isInside(ctx: Context, f: File): Boolean {
        val r = root(ctx).path
        return f.path == r || f.path.startsWith("$r/")
    }

    /** How many things are in Private's top folder. */
    fun count(ctx: Context): Int = root(ctx).list()?.size ?: 0

    // ------------------------------------------------------------------ locked or not (while Swift runs)

    @Volatile
    private var open = false
    private var awaySince = 0L

    val isUnlocked: Boolean get() = open

    fun unlock() {
        open = true
        awaySince = 0L
        L.d("Private opened")
    }

    /** Locks now; the small pictures made of its files go from memory too. */
    fun lock() {
        if (open) L.d("Private locked")
        open = false
        awaySince = 0L
        FileThumbs.trim()
        // 3.0: a document from Private open in the viewer closes too, and can't be opened again by its id.
        com.swiftbrowser.docs.Docs.forgetPrivate()
    }

    /** Files went out of sight while Private stays open for a minute (a file opened from it). */
    fun away() {
        if (open && awaySince == 0L) awaySince = SystemClock.elapsedRealtime()
    }

    /** Files is back: Private locks if it was out of sight over a minute. */
    fun back() {
        if (open && awaySince > 0L && SystemClock.elapsedRealtime() - awaySince > AWAY_MS) lock()
        awaySince = 0L
    }

    // ------------------------------------------------------------------ the PIN

    fun hasPin(): Boolean = !sp.getString(PREF_PIN, null).isNullOrEmpty()

    /** A PIN is 4 to 8 digits. */
    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** Keeps [pin] as a salted hash (a reset asked for is over then); [done] on the main thread. */
    fun setPin(pin: String, done: () -> Unit) {
        worker.execute {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            sp.edit().putString(PREF_PIN, PinHash.make(pin, salt)).putInt(PREF_TRIES, 0)
                .remove(PREF_WAIT).remove(PREF_WAIT_OLD).remove(PREF_RESET).apply()
            main.post(done)
        }
    }

    /** 3.0: Private opens with the phone's screen lock from now on (a forgotten PIN reset that way). */
    fun clearPin() {
        sp.edit().remove(PREF_PIN).putInt(PREF_TRIES, 0).remove(PREF_WAIT).remove(PREF_WAIT_OLD).remove(PREF_RESET).apply()
    }

    /** Now, as the phone tells it (which start of the phone, the time since, the clock). */
    private fun now(): Moment {
        val boot = try {
            SwiftApp.instance?.contentResolver?.let { android.provider.Settings.Global.getInt(it, android.provider.Settings.Global.BOOT_COUNT, -1) } ?: -1
        } catch (_: Exception) {
            -1
        }
        return Moment(boot, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    /** [key]'s wait as it stands now (kept so), or null when it is over. */
    private fun waitNow(key: String, prefix: String = ""): Wait? {
        val raw = sp.getString(key, null) ?: return null
        val w = Wait.decode(raw.removePrefix(prefix)) ?: return null
        val now = now()
        val rolled = w.at(now)
        sp.edit().putString(key, prefix + rolled.encode()).apply()
        return rolled
    }

    /** How long before another PIN may be tried (0: now). */
    fun waitLeft(): Long {
        val left = waitNow(PREF_WAIT)?.left ?: 0L
        if (left == 0L && sp.contains(PREF_WAIT)) sp.edit().remove(PREF_WAIT).apply()
        return left
    }

    /** 3.0: how many wrong PINs there have been since the right one was last typed. */
    val wrongTries: Int get() = sp.getInt(PREF_TRIES, 0)

    /**
     * Checks [pin] off the main thread (the hash takes a moment, on purpose); [done] on the main
     * thread. 3.0: after [PinWait.FREE_TRIES] wrong ones, each one more brings a longer wait, and
     * none is even tried while one lasts. A PIN kept the 2.9 way is kept the new way once right.
     */
    fun checkPin(pin: String, done: (Boolean) -> Unit) {
        worker.execute {
            val stored = sp.getString(PREF_PIN, null)
            val waiting = waitLeft() > 0
            val ok = !waiting && PinHash.matches(pin, stored)
            if (ok) {
                val e = sp.edit().putInt(PREF_TRIES, 0).remove(PREF_WAIT).remove(PREF_WAIT_OLD)
                if (PinHash.outdated(stored)) {
                    e.putString(PREF_PIN, PinHash.make(pin, ByteArray(16).also { SecureRandom().nextBytes(it) }))
                    L.d("Private: the PIN is kept the 3.0 way now")
                }
                e.apply()
            } else if (!waiting) {
                val tries = sp.getInt(PREF_TRIES, 0) + 1
                val wait = PinWait.after(tries)
                val e = sp.edit().putInt(PREF_TRIES, tries)
                if (wait > 0) e.putString(PREF_WAIT, Wait(wait, now()).encode())
                e.apply()
                if (wait > 0) L.w("Private: $tries wrong PINs, a wait of ${wait / 1000} s")
            }
            main.post { done(ok) }
        }
    }

    // ------------------------------------------------------------------ a forgotten PIN (3.0)

    /** When a PIN reset was asked for (on the clock), or null when none was. */
    fun resetAskedAt(): Long? = sp.getString(PREF_RESET, null)?.substringBefore('|')?.toLongOrNull()

    /** How long before the PIN can be reset (0: it can be now), or null when no reset was asked for. */
    fun resetLeft(): Long? {
        val asked = resetAskedAt() ?: return null
        waitNow(PREF_RESET, "$asked|")?.let { return it.left }
        // Its wait can't be read (never by Swift's own writing): a whole day from now, never none.
        val now = now()
        sp.edit().putString(PREF_RESET, "$asked|" + Wait(PinWait.RESET_MS, now).encode()).apply()
        return PinWait.RESET_MS
    }

    /** "Forgot PIN": the PIN can be reset once a day has passed (the PIN still opens Private meanwhile). */
    fun askReset() {
        if (resetAskedAt() != null) return
        val now = now()
        sp.edit().putString(PREF_RESET, "${now.wall}|" + Wait(PinWait.RESET_MS, now).encode()).apply()
        L.d("Private: a PIN reset was asked for")
    }

    /** The reset asked for is called off (the right PIN was typed meanwhile). */
    fun cancelReset() {
        if (sp.contains(PREF_RESET)) L.d("Private: the PIN reset was called off")
        sp.edit().remove(PREF_RESET).apply()
    }

    // ------------------------------------------------------------------ where things came from

    private fun origins(): JSONObject = runCatching { JSONObject(sp.getString(PREF_ORIGINS, null) ?: "{}") }.getOrDefault(JSONObject())

    /** The folder [f] (in Private's top folder) was moved in from, if known and it is a folder on the phone's storage or a card. */
    fun origin(ctx: Context, f: File): File? {
        if (f.parentFile?.path != root(ctx).path) return null
        val path = origins().optString(f.name).takeIf { it.isNotEmpty() } ?: return null
        return File(path).takeIf { Volumes.of(ctx, it) != null }
    }

    private fun rememberOrigins(add: Map<String, String>) {
        if (add.isEmpty()) return
        val o = origins()
        for ((k, v) in add) o.put(k, v)
        sp.edit().putString(PREF_ORIGINS, o.toString()).apply()
    }

    /** A thing in Private's top folder was renamed: where it came from goes with it. */
    fun renamed(ctx: Context, from: File, to: File) {
        if (from.parentFile?.path != root(ctx).path) return
        val o = origins()
        val was = o.optString(from.name).takeIf { it.isNotEmpty() } ?: return
        o.remove(from.name)
        o.put(to.name, was)
        sp.edit().putString(PREF_ORIGINS, o.toString()).apply()
    }

    /** Forgets where things came from that are no longer in Private. */
    fun tidyOrigins(ctx: Context) {
        val o = origins()
        val names = root(ctx).list()?.toSet().orEmpty()
        val gone = o.keys().asSequence().filter { it !in names }.toList()
        if (gone.isEmpty()) return
        gone.forEach { o.remove(it) }
        sp.edit().putString(PREF_ORIGINS, o.toString()).apply()
    }

    // ------------------------------------------------------------------ moving in (a file job)

    /**
     * Moves [files] into Private's top folder (a copy, then the original goes: Swift's storage is
     * another place than the shared one), a free name for each ("photo (1).jpg"). The originals
     * leave the media index, and where each came from is remembered for "Move out".
     *
     * 3.0: an original leaves its folder file by file, once all of it is in Private; a file that came
     * into its folder meanwhile (a download finishing) stays there with the folder. One that can't
     * leave (the card refused) stays where it was and its copy in Private goes again (only when the
     * original is there whole), so each file is in one place: Private's copy of what did leave is
     * never deleted. Where each came from is kept as soon as it is in.
     */
    fun moveIn(w: FileJobs.Work, files: List<File>) {
        val ctx = w.ctx
        val root = root(ctx)
        if (!root.isDirectory && !root.mkdirs()) throw FileProblem("Swift couldn't make its Private folder.")
        w.destination(root)
        w.counting()
        val totals = FileOps.measure(files, { w.job.cancelled })
        w.check()
        w.totals(totals.files, totals.bytes)
        val free = runCatching { StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        // Some room is left for Swift itself to run.
        val margin = FileJobs.MARGIN
        if (totals.bytes > free - margin) {
            throw FileProblem("There isn't enough free space: this needs ${UrlUtils.formatBytes(totals.bytes)}, and ${UrlUtils.formatBytes((free - margin).coerceAtLeast(0))} is free.")
        }
        var done = 0
        var kept = 0
        var stayed = 0
        for (src in files) {
            w.check()
            if (isInside(ctx, src)) continue
            val access = Volumes.accessFor(ctx, src) ?: throw FileProblem(Transfers.CARD_TEXT)
            val target = FileOps.freeName(File(root, src.name))
            w.touched(src.parentFile)
            val copied = Originals()
            var whole = false
            try {
                copyIn(w, src, target, copied)
                whole = true
            } finally {
                // Cut off (Cancel, no room, a file that can't be read): its copy goes; the original wasn't touched.
                if (!whole) target.deleteRecursively()
            }
            val left = copied.remove(access)
            // The ones that couldn't leave: their copies go from Private again, so each file is in one place.
            for (orig in left.stayed) {
                val copy = copied.copyOf[orig] ?: continue
                if (orig.isFile && copy.isFile && orig.length() == copy.length()) copy.delete()
            }
            for (orig in copied.folders) {
                val copy = copied.copyOf[orig] ?: continue
                if (orig.exists() && copy.list()?.isEmpty() == true) copy.delete()
            }
            FileOps.forgetPaths(ctx, left.gone.map { it.path })
            stayed += left.stayed.size
            if (!target.exists()) {
                kept++
                continue
            }
            // 3.0: kept at once, for "Move out" (a job cut off later doesn't lose it).
            src.parentFile?.let { rememberOrigins(mapOf(target.name to it.path)) }
            done++
        }
        w.result(root)
        L.d("Moved into Private: $done, $kept couldn't leave their folders, $stayed files stayed")
        if (done == 0) throw FileProblem("Nothing was moved: Android didn't let Swift take them from their folder.")
        w.message("Moved " + (if (files.size == 1 && done == 1) "“${files[0].name}”" else FileJobs.items(done)) + " to Private" +
            when {
                kept > 0 -> ". $kept couldn't leave their folder."
                stayed > 0 -> ". " + (if (stayed == 1) "1 file" else "$stayed files") + " couldn't leave " + (if (stayed == 1) "its" else "their") + " folder and stayed there."
                else -> ""
            })
    }

    private fun copyIn(w: FileJobs.Work, src: File, target: File, copied: Originals) {
        w.check()
        if (src.isDirectory) {
            // 3.0: a folder that can't be read (a failing card) stops the move, rather than counting as empty.
            val children = src.listFiles() ?: throw FileProblem("Swift couldn't read the folder “${src.name}”. The memory card may be failing.")
            if (!target.mkdir() && !target.isDirectory) throw FileProblem("Swift couldn't make a folder in Private.")
            for (c in children) copyIn(w, c, File(target, c.name), copied)
            target.setLastModified(src.lastModified())
            copied.folder(src, target)
            return
        }
        w.current(src.name)
        val length = src.length()
        val time = src.lastModified()
        // 3.0: written under a hidden name until it is whole, as every copy.
        w.write(target, PlainAccess, time) { out -> FileInputStream(src).use { input -> w.copy(input, out) } }
        w.fileDone()
        copied.file(src, target, length, time)
    }
}
