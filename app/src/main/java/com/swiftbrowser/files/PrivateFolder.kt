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
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Private (2.9): files moved into Swift's own storage (its files folder, "private"), where no other
 * app, the Gallery or a computer can see them. Opening it asks for the phone's screen lock, or for
 * a PIN of Swift's own on a phone without one (kept only as a salted hash). It locks again when Files
 * is left, or after a minute out of sight (a file opened from it, Android's own lock question).
 * The files aren't encrypted: they are as safe as Swift's own data, and they go when Swift is
 * uninstalled or its data cleared, which the screen says.
 */
object PrivateFolder {
    private const val DIR = "private"
    private const val PREF_PIN = "files_private_pin"
    private const val PREF_TRIES = "files_private_tries"
    private const val PREF_WAIT = "files_private_wait_until"
    private const val PREF_ORIGINS = "files_private_origins"

    /** The first time Private opens, what it is is explained once. */
    const val PREF_INTRO = "files_private_intro"

    /** Out of sight this long (a file opened from Private, another app), Private locks. */
    private const val AWAY_MS = 60_000L

    /** Wrong PINs in a row before a wait, and the wait. */
    private const val TRIES = 5
    private const val WAIT_MS = 30_000L

    private const val ITERATIONS = 4000

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

    // ------------------------------------------------------------------ the PIN (phones without a screen lock)

    fun hasPin(): Boolean = !sp.getString(PREF_PIN, null).isNullOrEmpty()

    /** A PIN is 4 to 8 digits. */
    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** Keeps [pin] as a salted hash; [done] on the main thread. */
    fun setPin(pin: String, done: () -> Unit) {
        worker.execute {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = hash(pin, salt)
            sp.edit().putString(PREF_PIN, "1:" + hex(salt) + ":" + hex(hash)).putInt(PREF_TRIES, 0).remove(PREF_WAIT).apply()
            main.post(done)
        }
    }

    /** The PIN is no longer needed (the phone has a screen lock now, which opens Private instead). */
    fun clearPin() {
        sp.edit().remove(PREF_PIN).putInt(PREF_TRIES, 0).remove(PREF_WAIT).apply()
    }

    /** How long before another PIN may be tried (0: now). */
    fun waitLeft(): Long = (sp.getLong(PREF_WAIT, 0L) - System.currentTimeMillis()).coerceIn(0L, WAIT_MS)

    /**
     * Checks [pin] off the main thread (the hash takes a moment, on purpose); [done] on the main
     * thread. After [TRIES] wrong ones in a row the next has to wait half a minute.
     */
    fun checkPin(pin: String, done: (Boolean) -> Unit) {
        worker.execute {
            val stored = sp.getString(PREF_PIN, null).orEmpty().split(':')
            val ok = stored.size == 3 && stored[0] == "1" && runCatching {
                MessageDigest.isEqual(hash(pin, unhex(stored[1])), unhex(stored[2]))
            }.getOrDefault(false)
            if (ok) {
                sp.edit().putInt(PREF_TRIES, 0).remove(PREF_WAIT).apply()
            } else {
                val tries = sp.getInt(PREF_TRIES, 0) + 1
                val e = sp.edit().putInt(PREF_TRIES, if (tries >= TRIES) 0 else tries)
                if (tries >= TRIES) e.putLong(PREF_WAIT, System.currentTimeMillis() + WAIT_MS)
                e.apply()
            }
            main.post { done(ok) }
        }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

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
     */
    fun moveIn(w: FileJobs.Work, files: List<File>) {
        val ctx = w.ctx
        val root = root(ctx)
        if (!root.isDirectory && !root.mkdirs()) throw FileProblem("Swift couldn't make its Private folder.")
        w.counting()
        val totals = FileOps.measure(files, { w.job.cancelled })
        w.check()
        w.totals(totals.files, totals.bytes)
        val free = runCatching { StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        // Some room is left for Swift itself to run.
        val margin = 50L * 1_048_576
        if (totals.bytes > free - margin) {
            throw FileProblem("There isn't enough free space: this needs ${UrlUtils.formatBytes(totals.bytes)}, and ${UrlUtils.formatBytes((free - margin).coerceAtLeast(0))} is free.")
        }
        var done = 0
        var kept = 0
        val origins = HashMap<String, String>()
        for (src in files) {
            w.check()
            if (isInside(ctx, src)) continue
            val access = Volumes.accessFor(ctx, src) ?: throw FileProblem(Transfers.CARD_TEXT)
            val target = FileOps.freeName(File(root, src.name))
            w.touched(src.parentFile)
            var copied = false
            try {
                copyIn(w, src, target)
                copied = true
            } finally {
                if (!copied) target.deleteRecursively()
            }
            // Only what was copied whole leaves its folder; one that can't leave stays where it was, uncopied.
            if (!runCatching { access.delete(src) }.getOrDefault(false) && src.exists()) {
                target.deleteRecursively()
                kept++
                continue
            }
            FileOps.forget(ctx, src)
            src.parentFile?.let { origins[target.name] = it.path }
            done++
        }
        rememberOrigins(origins)
        w.result(root)
        L.d("Moved into Private: $done, $kept couldn't leave their folders")
        if (done == 0) throw FileProblem("Nothing was moved: Android didn't let Swift take them from their folder.")
        w.message("Moved " + (if (files.size == 1 && done == 1) "“${files[0].name}”" else FileJobs.items(done)) + " to Private" +
            if (kept > 0) ". $kept couldn't leave their folder." else "")
    }

    private fun copyIn(w: FileJobs.Work, src: File, target: File) {
        w.check()
        if (src.isDirectory) {
            if (!target.mkdir() && !target.isDirectory) throw FileProblem("Swift couldn't make a folder in Private.")
            for (c in src.listFiles().orEmpty()) copyIn(w, c, File(target, c.name))
            target.setLastModified(src.lastModified())
            return
        }
        w.current(src.name)
        FileInputStream(src).use { input -> FileOutputStream(target).use { out -> w.copyCountingInput(input, out) } }
        target.setLastModified(src.lastModified())
        w.fileDone()
    }
}
