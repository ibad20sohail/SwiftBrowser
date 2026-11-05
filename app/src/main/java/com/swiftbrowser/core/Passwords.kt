package com.swiftbrowser.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.swiftbrowser.SwiftApp
import org.mozilla.geckoview.Autocomplete
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved sign-ins (2.6): the engine asks Swift for a site's logins to fill in, and gives it the ones
 * to save once the user agreed (Prompts). Passwords are encrypted with a key that never leaves
 * Android's keystore; they stay out of backups. Each profile has its own.
 */
object Passwords {
    private const val KEY_ALIAS = "swift_logins"
    private const val NEVER = "login_never"

    class Login(val guid: String, val origin: String, val username: String, val password: String, val created: Long, val used: Long)

    /**
     * 3.0: the key the sign-ins were locked with, or null when Android's keystore no longer has it
     * (a reset of its secure storage). A new key is only made to save new ones: it can't open the
     * old ones, which then count as unreadable (they showed in the count, but not in the list).
     */
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The keystore itself failing throws instead: a new key must never take a working one's place. */
    private fun existingKey(ks: KeyStore = keyStore()): SecretKey? {
        if (!ks.containsAlias(KEY_ALIAS)) return null
        return try {
            ks.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (_: java.security.UnrecoverableKeyException) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = keyStore()
        existingKey(ks)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** [key]: the keystore's (null when it lost it: then nothing can be read). */
    private fun decrypt(stored: String, key: SecretKey?): String? {
        if (key == null) return null
        return try {
            val (iv, data) = stored.split(':', limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** 3.0.4: another secret (a network folder's password) locked with the same keystore key. Blocking. */
    fun lock(text: String): String = encrypt(text)

    /** 3.0.4: what [lock] gave back, unlocked; null when the keystore lost its key or doesn't answer. Blocking. */
    fun unlock(stored: String): String? = try {
        decrypt(stored, existingKey())
    } catch (e: Exception) {
        L.w("The keystore can't be read (${e.javaClass.simpleName})")
        null
    }

    /** 3.0: how many of a profile's sign-ins can be read, and how many can't any more. */
    class Count(val readable: Int, val unreadable: Int)

    /** The last count and the rows it was made of: Settings asks at every visit, and the keystore is slow. */
    @Volatile private var lastCount: Pair<Int, Count>? = null

    /** Blocking (3.0): for Settings' row; a sign-in that can't be read any more isn't counted as one. */
    fun count(profileId: Long): Count {
        val rows = SwiftApp.db.logins(profileId)
        val sign = rows.fold(profileId.hashCode()) { h, r -> 31 * h + r.guid.hashCode() * 17 + r.password.hashCode() }
        lastCount?.takeIf { it.first == sign }?.let { return it.second }
        val key = try {
            existingKey()
        } catch (e: Exception) {
            // The keystore didn't answer this time: nothing is said to be lost.
            L.w("The keystore can't be read", e)
            return Count(rows.size, 0)
        }
        val bad = if (key == null) rows.size else rows.count { decrypt(it.password, key) == null }
        if (bad > 0) L.w("$bad saved sign-ins can't be read any more (the keystore lost their key)")
        return Count(rows.size - bad, bad).also { lastCount = sign to it }
    }

    /**
     * Blocking (3.0): this profile's sign-ins: those that can be read, and the rows of those that
     * can't any more. Throws when the keystore doesn't answer (they may be fine: nothing is said).
     */
    fun read(profileId: Long): Pair<List<Login>, List<LoginRow>> {
        val key = existingKey()
        val good = ArrayList<Login>()
        val bad = ArrayList<LoginRow>()
        for (r in SwiftApp.db.logins(profileId)) {
            val password = decrypt(r.password, key)
            if (password != null) good += Login(r.guid, r.origin, r.username, password, r.created, r.used) else bad += r
        }
        return good to bad
    }

    /** Blocking (3.0): deletes sign-ins that can't be read any more. */
    fun removeRows(rows: List<LoginRow>) {
        for (r in rows) SwiftApp.db.deleteLogin(r.guid)
        lastCount = null
    }

    private fun hostOf(origin: String) = UrlUtils.host(origin) ?: origin

    /** Blocking. The engine's request: the sign-ins for [domain] and its subdomains (null: all). */
    fun entries(domain: String?): Array<Autocomplete.LoginEntry> {
        val rows = SwiftApp.db.logins(Profiles.currentId).filter { r ->
            domain == null || hostOf(r.origin).let { it == domain || it.endsWith(".$domain") }
        }
        if (rows.isEmpty()) return emptyArray()
        val key = try {
            existingKey()
        } catch (e: Exception) {
            L.w("The keystore can't be read", e)
            return emptyArray()
        }
        return rows.mapNotNull { r ->
            val password = decrypt(r.password, key) ?: return@mapNotNull null
            Autocomplete.LoginEntry.Builder()
                .guid(r.guid).origin(r.origin).formActionOrigin(r.formAction).httpRealm(r.realm)
                .username(r.username).password(password)
                .build()
        }.toTypedArray()
    }

    /** Blocking. A sign-in the user chose to save (new, or a changed password of one already saved). */
    fun save(entry: Autocomplete.LoginEntry) {
        val profile = Profiles.currentId
        val now = System.currentTimeMillis()
        val db = SwiftApp.db
        val same = db.logins(profile).firstOrNull { r ->
            (entry.guid != null && r.guid == entry.guid) || (r.origin == entry.origin && r.username == (entry.username ?: ""))
        }
        db.saveLogin(
            LoginRow(
                same?.guid ?: entry.guid ?: UUID.randomUUID().toString(), profile, entry.origin, entry.formActionOrigin,
                entry.httpRealm, entry.username ?: "", encrypt(entry.password ?: ""), same?.created ?: now, now, same?.times ?: 0,
            ),
        )
    }

    fun used(entry: Autocomplete.LoginEntry) {
        val guid = entry.guid ?: return
        SwiftApp.db.loginUsed(guid, System.currentTimeMillis())
    }

    /** Blocking: this profile's sign-ins, for Settings → Passwords. */
    fun all(profileId: Long): List<Login> = read(profileId).first

    /** Blocking. Deletes a sign-in and gives back what was stored, for Undo. */
    fun remove(guid: String): LoginRow? {
        val db = SwiftApp.db
        val row = db.logins(Profiles.currentId).firstOrNull { it.guid == guid }
        db.deleteLogin(guid)
        return row
    }

    fun putBack(row: LoginRow) = SwiftApp.db.saveLogin(row)

    /** Clearing browsing data: sign-ins saved since [since] (0: all of them); 2.8: until [until] for chosen dates. */
    fun clearSince(profileId: Long, since: Long, until: Long = Long.MAX_VALUE) = SwiftApp.db.deleteLoginsSince(profileId, since, until)

    /** Sites the user said never to save for. */
    fun never(origin: String): Boolean = hostOf(origin) in (SwiftApp.prefs.sp.getStringSet(NEVER, emptySet()) ?: emptySet())

    fun setNever(origin: String, on: Boolean) {
        val set = (SwiftApp.prefs.sp.getStringSet(NEVER, emptySet()) ?: emptySet()).toMutableSet()
        if (on) set += hostOf(origin) else set -= hostOf(origin)
        SwiftApp.prefs.sp.edit().putStringSet(NEVER, set).apply()
    }

    fun neverSites(): List<String> = (SwiftApp.prefs.sp.getStringSet(NEVER, emptySet()) ?: emptySet()).sorted()
}
