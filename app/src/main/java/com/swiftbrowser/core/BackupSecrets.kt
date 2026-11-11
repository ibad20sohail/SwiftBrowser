package com.swiftbrowser.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.swiftbrowser.SwiftApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 3.0.8: the backup password. Swift never keeps the password itself: only the key made from it
 * (BackupCrypto), locked with Android's keystore like the saved passwords, so backups made later
 * don't ask for it again. The salt and the hint go with every locked backup, for opening it on
 * another phone. None of this is in backups (BackupSettings only takes what it lists).
 */
object BackupLock {
    private const val KEY = "backup_key"
    private const val SALT = "backup_salt"
    private const val ROUNDS = "backup_rounds"
    private const val HINT = "backup_hint"

    /** A locked backup is never bigger than this once opened (history, passwords and sign-ins). */
    const val MAX_OPEN_BYTES = 48 * 1024 * 1024

    private val sp get() = SwiftApp.prefs.sp

    val isSet: Boolean get() = sp.contains(KEY)

    val hint: String get() = sp.getString(HINT, "").orEmpty()

    val salt: ByteArray? get() = sp.getString(SALT, null)?.let { b64(it) }

    val rounds: Int get() = sp.getInt(ROUNDS, BackupCrypto.ITERATIONS)

    /** Blocking and slow (a second or two): sets the backup password. */
    fun set(password: String, hint: String) {
        val salt = BackupCrypto.salt()
        remember(BackupCrypto.keyFrom(password, salt), salt, BackupCrypto.ITERATIONS, hint)
    }

    /** Keeps [key] (made from the password with [salt] and [rounds]), locked by the keystore. Blocking. */
    fun remember(key: ByteArray, salt: ByteArray, rounds: Int, hint: String) {
        sp.edit()
            .putString(KEY, Passwords.lock(b64(key)))
            .putString(SALT, b64(salt))
            .putInt(ROUNDS, rounds)
            .putString(HINT, hint.trim().take(MAX_HINT))
            .apply()
    }

    /** Blocking: the key, or null when no password is set or the keystore lost its own key. */
    fun key(): ByteArray? = sp.getString(KEY, null)?.let { Passwords.unlock(it) }?.let { b64(it) }

    fun clear() {
        sp.edit().remove(KEY).remove(SALT).remove(ROUNDS).remove(HINT).apply()
    }

    const val MAX_HINT = 80

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun b64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    // ------------------------------------------------------------------ locked part of a backup file

    /**
     * Blocking: [inside] locked with the backup password's key, for a backup file:
     * {"salt", "rounds", "hint", "data"}; null when no password is set (or its key can't be read).
     */
    fun lockBox(inside: JSONObject): JSONObject? {
        val key = key() ?: return null
        val salt = salt ?: return null
        val data = BackupCrypto.seal(key, BackupCrypto.gzip(inside.toString().toByteArray(Charsets.UTF_8)))
        return JSONObject().put("salt", b64(salt)).put("rounds", rounds).put("hint", hint).put("data", b64(data))
    }

    /** A locked part made with this phone's backup password as it is now: it opens without asking. */
    fun opensWithoutPassword(box: JSONObject): Boolean {
        val mine = salt ?: return false
        return box.optString("salt") == b64(mine) && box.optInt("rounds") == rounds && key() != null
    }

    /**
     * Blocking, slow when [password] is given: what [box] holds, or null with a wrong password (or a
     * damaged box). Without [password], this phone's key, if the box was locked with it. [key] gets
     * the key that opened it.
     */
    fun openBox(box: JSONObject, password: String?, key: ((ByteArray) -> Unit)? = null): JSONObject? = try {
        val salt = b64(box.getString("salt"))
        val rounds = box.getInt("rounds").takeIf { it in 1..10_000_000 } ?: return null
        val k = if (password != null) BackupCrypto.keyFrom(password, salt, rounds) else (if (opensWithoutPassword(box)) key() else null) ?: return null
        val plain = BackupCrypto.open(k, b64(box.getString("data")))?.let { BackupCrypto.gunzip(it, MAX_OPEN_BYTES) } ?: return null
        key?.invoke(k)
        JSONObject(String(plain, Charsets.UTF_8))
    } catch (e: Exception) {
        L.w("A locked backup couldn't be opened (${e.javaClass.simpleName})")
        null
    }
}

/**
 * 3.0.8: the saved passwords and the sign-ins to sites (the engine's cookies), for locked backups
 * only: out of the phone unlocked, inside a backup locked with the backup password; back in, the
 * passwords locked again with this phone's keystore, the cookies at Swift's next start (the engine
 * reads them once, as it starts).
 */
object BackupSecrets {
    private const val PENDING = "google-backup/sign-ins-to-restore"

    /** Blocking: every profile's saved passwords, unlocked. Those that can't be read any more are left out. */
    fun logins(): JSONArray {
        val out = JSONArray()
        for (p in Profiles.all()) {
            for (r in SwiftApp.db.logins(p.id)) {
                val password = Passwords.unlock(r.password) ?: continue
                out.put(
                    JSONObject().put("p", r.profileId).put("guid", r.guid).put("origin", r.origin).put("action", r.formAction ?: JSONObject.NULL)
                        .put("realm", r.realm ?: JSONObject.NULL).put("user", r.username).put("pass", password)
                        .put("created", r.created).put("used", r.used).put("times", r.times),
                )
            }
        }
        return out
    }

    /** The engine's cookie file (one profile folder holds Swift's profiles, apart by their context). */
    private fun cookieFile(ctx: Context): File? =
        File(ctx.filesDir, "mozilla").listFiles()?.map { File(it, "cookies.sqlite") }?.firstOrNull { it.isFile }

    /**
     * Blocking: the engine's cookies (the sign-ins to sites): its file's version, the columns and the
     * rows; null when they can't be read. The engine keeps the file open; reading it alongside is safe.
     */
    fun cookies(ctx: Context): JSONObject? {
        val file = cookieFile(ctx) ?: return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.rawQuery("SELECT * FROM moz_cookies", null).use { c ->
                    val columns = c.columnNames.filter { it != "id" }
                    val index = columns.map { c.getColumnIndexOrThrow(it) }
                    val rows = JSONArray()
                    while (c.moveToNext()) rows.put(JSONArray().apply { for (i in index) put(value(c, i)) })
                    JSONObject().put("schema", db.version).put("columns", JSONArray(columns)).put("rows", rows)
                }
            }
        } catch (e: Exception) {
            L.w("The engine's sign-ins couldn't be read for the backup", e)
            null
        }
    }

    private fun value(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_STRING -> c.getString(i)
        else -> JSONObject.NULL
    }

    /** Blocking: what a backup file's locked part holds. */
    fun forFile(ctx: Context): JSONObject = JSONObject().put("logins", logins()).apply { cookies(ctx)?.let { put("cookies", it) } }

    /**
     * Blocking: puts back [from]'s saved passwords (into the profiles they have here, [ids]: the
     * backup's profile id → this phone's), each locked again with this phone's key; one already here
     * (same site and name) stays as it is. Its cookies wait for Swift's next start ([importAtStart]).
     * Returns the passwords added and the sign-ins waiting.
     */
    fun restore(ctx: Context, from: JSONObject, ids: Map<Long, Long>): Pair<Int, Int> {
        var passwords = 0
        from.optJSONArray("logins")?.let { arr ->
            val db = SwiftApp.db
            val known = HashMap<Long, List<LoginRow>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = ids[o.optLong("p")] ?: continue
                val origin = o.optString("origin").takeIf { it.isNotBlank() && it.length <= 2048 } ?: continue
                val user = o.optString("user").take(1024)
                val pass = o.optString("pass")
                if (pass.isEmpty() || pass.length > 4096) continue
                val here = known.getOrPut(pid) { db.logins(pid) }
                if (here.any { it.origin == origin && it.username == user }) continue
                val guid = o.optString("guid").takeIf { g -> g.isNotBlank() && g.length <= 64 && here.none { it.guid == g } } ?: UUID.randomUUID().toString()
                db.saveLogin(
                    LoginRow(
                        guid, pid, origin, o.optString("action").takeIf { !o.isNull("action") }, o.optString("realm").takeIf { !o.isNull("realm") },
                        user, Passwords.lock(pass), o.optLong("created", System.currentTimeMillis()), o.optLong("used"), o.optInt("times"),
                    ),
                )
                known[pid] = here + LoginRow(guid, pid, origin, null, null, user, "", 0, 0, 0)
                passwords++
            }
        }
        var signIns = 0
        from.optJSONObject("cookies")?.let { cookies ->
            val rows = cookies.optJSONArray("rows") ?: JSONArray()
            val columns = cookies.optJSONArray("columns") ?: JSONArray()
            val at = (0 until columns.length()).firstOrNull { columns.optString(it) == "originAttributes" }
            val moved = JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (at != null) {
                    val oa = BackupCrypto.moveProfile(row.optString(at), ids) ?: continue
                    row.put(at, oa)
                }
                moved.put(row)
            }
            if (moved.length() > 0) {
                cookies.put("rows", moved)
                val file = File(ctx.filesDir, PENDING)
                file.parentFile?.mkdirs()
                // Locked while it waits (they are sign-ins), with the keystore like the passwords.
                file.writeText(Passwords.lock(cookies.toString()))
                signIns = moved.length()
            }
        }
        L.d("Backup: $passwords saved passwords back, $signIns sign-ins waiting for the next start")
        return passwords to signIns
    }

    /**
     * At Swift's start, before the engine: the sign-ins a restore left ([restore]) go into the
     * engine's cookie file, when its version is the one they were read from (another version may
     * mean another meaning); otherwise they are dropped and the user signs in again.
     */
    fun importAtStart(ctx: Context) {
        val pending = File(ctx.filesDir, PENDING)
        if (!pending.isFile) return
        val file = cookieFile(ctx)
        if (file == null) {
            // The engine hasn't made its file yet: it has after this start.
            L.d("Backup: sign-ins wait for the engine's first start")
            return
        }
        try {
            val cookies = Passwords.unlock(pending.readText())?.let { JSONObject(it) }
            if (cookies == null) {
                L.w("Backup: the waiting sign-ins couldn't be unlocked")
            } else {
                val n = SQLiteDatabase.openDatabase(
                    file.path, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                ).use { db -> insert(db, cookies) }
                L.d("Backup: $n sign-ins put back")
            }
        } catch (e: Exception) {
            L.w("Backup: the sign-ins couldn't be put back", e)
        }
        pending.delete()
    }

    private fun insert(db: SQLiteDatabase, cookies: JSONObject): Int {
        if (cookies.optInt("schema", -1) != db.version) {
            L.w("Backup: sign-ins from another engine version (${cookies.optInt("schema")} vs ${db.version}) are left out")
            return 0
        }
        val have = db.rawQuery("PRAGMA table_info(moz_cookies)", null).use { c ->
            val names = HashSet<String>()
            while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
            names
        }
        val columns = cookies.getJSONArray("columns")
        val use = (0 until columns.length()).filter { columns.optString(it).let { n -> n != "id" && n in have } }
        val rows = cookies.getJSONArray("rows")
        var n = 0
        db.beginTransaction()
        try {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val cv = ContentValues()
                for (j in use) {
                    val name = columns.getString(j)
                    when (val v = row.opt(j)) {
                        is Int -> cv.put(name, v.toLong())
                        is Long -> cv.put(name, v)
                        is Double -> cv.put(name, v)
                        is String -> cv.put(name, v)
                        else -> cv.putNull(name)
                    }
                }
                if (db.insertWithOnConflict("moz_cookies", null, cv, SQLiteDatabase.CONFLICT_REPLACE) >= 0) n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }
}
