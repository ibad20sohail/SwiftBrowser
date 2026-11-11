package com.swiftbrowser.core

import android.content.Context
import android.os.Build
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.SwiftApp
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/**
 * 3.0.8: the backup Android keeps in the user's Google account (Settings → Storage & data → Back up
 * to your Google account). Swift writes one file, the only one Android may take (res/xml backup and
 * data extraction rules): what a backup file holds, with the history, the saved passwords and the
 * sign-ins to sites, all of it locked with the backup password (Google can read Android 7's backups;
 * it can't read this). Android sends it about once a day while the phone charges on Wi-Fi, and puts
 * it back when Swift is installed again or on a new phone; Swift then offers to restore it.
 */
object GoogleBackup {
    private const val DIR = "google-backup"

    /** The file the backup rules name (res/xml/backup_rules.xml and data_extraction_rules.xml). */
    const val FILE = "swift-backup.bin"

    private val MAGIC = "SWIFTGB1".toByteArray(Charsets.US_ASCII)
    private const val FORMAT = 1
    private const val MAX_HEADER = 64 * 1024

    /** Android's limit is 25 MB an app; Swift's own stays well under it. */
    private const val MAX_FILE = 20L * 1024 * 1024

    private const val ON = "google_backup"
    private const val WRITTEN = "google_backup_written"
    private const val INSTALL = "install_id"
    private const val OFFERED = "google_backup_offered"
    private const val REMINDED = "google_backup_reminded"

    /** Swift's copy is made anew when Swift leaves the screen, at most this often. */
    private const val EVERY = 12L * 3600 * 1000

    private val io = Background.single("google-backup")
    private val sp get() = SwiftApp.prefs.sp

    /** The user turned it on (off until then). */
    var on: Boolean
        get() = sp.getBoolean(ON, false)
        set(v) = sp.edit().putBoolean(ON, v).apply()

    /** When Swift last wrote its copy (0: never). */
    val written: Long get() = sp.getLong(WRITTEN, 0L)

    /** This install of Swift (it isn't backed up: a copy from another install is someone else's to restore). */
    fun installId(): String = sp.getString(INSTALL, null) ?: UUID.randomUUID().toString().also { sp.edit().putString(INSTALL, it).apply() }

    fun file(ctx: Context) = File(File(ctx.filesDir, DIR), FILE)

    /**
     * The phone's own backup to Google: on, off, or null when Android doesn't say (never set: Android
     * may keep it elsewhere, as the Moto E4 emulator does; from Android 12 apps may not read it).
     */
    fun phoneBackupOn(ctx: Context): Boolean? = try {
        when (android.provider.Settings.Secure.getInt(ctx.contentResolver, "backup_enabled", -1)) {
            1 -> true
            0 -> false
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** "motorola Moto E4" (an emulator's maker is "unknown"). */
    private fun device(): String = Build.MODEL.let { model ->
        val maker = Build.MANUFACTURER.orEmpty()
        if (maker.isBlank() || maker.equals("unknown", ignoreCase = true) || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }

    /** Whether the reminder that the phone's own backup is off was shown (once). */
    var reminded: Boolean
        get() = sp.getBoolean(REMINDED, false)
        set(v) = sp.edit().putBoolean(REMINDED, v).apply()

    /** A copy found on the phone: from this install, or put back by Android. */
    class Found(
        val created: Long, val device: String, val version: String, val install: String, val hint: String,
        val salt: ByteArray, val rounds: Int, val header: ByteArray, val sealed: ByteArray,
    ) {
        val fromHere get() = install == installId()
    }

    /**
     * Blocking: the copy on the phone, or null (none, or not readable). Without [withData], only what
     * it says about itself (quick, for Settings).
     */
    fun found(ctx: Context, withData: Boolean = true): Found? {
        val f = file(ctx)
        if (!f.isFile || f.length() > MAX_FILE) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
                if (!magic.contentEquals(MAGIC)) return null
                val n = input.readInt()
                if (n !in 1..MAX_HEADER) return null
                val header = ByteArray(n).also { input.readFully(it) }
                val sealed = if (withData) input.readBytes() else ByteArray(0)
                val h = JSONObject(String(header, Charsets.UTF_8))
                if (h.optInt("format", 0) !in 1..FORMAT) return null
                Found(
                    h.optLong("created"), h.optString("device").take(80), h.optString("version").take(20), h.optString("install"),
                    h.optString("hint").take(BackupLock.MAX_HINT), BackupLock.b64(h.getString("salt")), h.getInt("rounds"), header, sealed,
                )
            }
        } catch (e: Exception) {
            L.w("Google backup: the copy on the phone can't be read (${e.javaClass.simpleName})")
            null
        }
    }

    /** A copy Android put back (Swift installed again, or a new phone), not offered yet at start. */
    fun toOffer(ctx: Context): Found? {
        val f = found(ctx, withData = false) ?: return null
        if (f.fromHere || sp.getLong(OFFERED, 0L) == f.created) return null
        return f
    }

    /** It was offered (restored, or "Not now"): not again at start; Settings still has it. */
    fun offered(f: Found) = sp.edit().putLong(OFFERED, f.created).apply()

    /** Blocking and slow (the password's key): what the copy holds, and its key; null with a wrong password. */
    fun open(f: Found, password: String): Pair<JSONObject, ByteArray>? {
        if (f.rounds !in 1..10_000_000) return null
        val key = BackupCrypto.keyFrom(password, f.salt, f.rounds)
        val plain = BackupCrypto.open(key, f.sealed, f.header)?.let { BackupCrypto.gunzip(it, BackupLock.MAX_OPEN_BYTES) } ?: return null
        return try {
            JSONObject(String(plain, Charsets.UTF_8)) to key
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Blocking: writes Swift's copy now (the backup password must be set). Written beside it first,
     * so a copy Android takes is never half written. True when written.
     */
    fun write(ctx: Context): Boolean {
        val key = BackupLock.key() ?: return false.also { L.w("Google backup: no key (the backup password needs setting again)") }
        val salt = BackupLock.salt ?: return false
        return try {
            val root = Backup.contents(ctx, history = true)
                .put("logins", BackupSecrets.logins())
            BackupSecrets.cookies(ctx)?.let { root.put("cookies", it) }
            val header = JSONObject()
                .put("app", "Swift").put("format", FORMAT).put("created", System.currentTimeMillis())
                .put("device", device()).put("version", BuildConfig.VERSION_NAME)
                .put("install", installId()).put("hint", BackupLock.hint)
                .put("salt", BackupLock.b64(salt)).put("rounds", BackupLock.rounds)
                .toString().toByteArray(Charsets.UTF_8)
            val sealed = BackupCrypto.seal(key, BackupCrypto.gzip(root.toString().toByteArray(Charsets.UTF_8)), header)
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.write(MAGIC)
                out.writeInt(header.size)
                out.write(header)
                out.write(sealed)
            }
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return false
            }
            sp.edit().putLong(WRITTEN, System.currentTimeMillis()).apply()
            android.app.backup.BackupManager(ctx).dataChanged()
            L.d("Google backup: Swift's copy written (${f.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            L.w("Google backup: Swift's copy couldn't be written", e)
            false
        }
    }

    /** Swift left the screen: its copy is made anew if it's on and the last one is old enough. */
    fun refreshIfDue(ctx: Context) {
        if (!on || System.currentTimeMillis() - written < EVERY) return
        val app = ctx.applicationContext
        io.execute { write(app) }
    }

    /** In the background: Swift's copy now (turned on, a new password). [done] on the main thread. */
    fun writeSoon(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        io.execute {
            val ok = write(app)
            if (done != null) android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }
    }

    /** Turned off: Swift's copy goes (Google's goes at Android's next backup, which finds none). */
    fun turnOff(ctx: Context) {
        on = false
        sp.edit().remove(WRITTEN).apply()
        val app = ctx.applicationContext
        io.execute {
            file(app).delete()
            try {
                android.app.backup.BackupManager(app).dataChanged()
            } catch (_: Exception) {
            }
            L.d("Google backup: turned off, Swift's copy deleted")
        }
    }
}
