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

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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

    private fun decrypt(stored: String): String? = try {
        val (iv, data) = stored.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        L.w("Couldn't read a saved password", e)
        null
    }

    private fun hostOf(origin: String) = UrlUtils.host(origin) ?: origin

    /** Blocking. The engine's request: the sign-ins for [domain] and its subdomains (null: all). */
    fun entries(domain: String?): Array<Autocomplete.LoginEntry> {
        val rows = SwiftApp.db.logins(Profiles.currentId).filter { r ->
            domain == null || hostOf(r.origin).let { it == domain || it.endsWith(".$domain") }
        }
        return rows.mapNotNull { r ->
            val password = decrypt(r.password) ?: return@mapNotNull null
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
    fun all(profileId: Long): List<Login> = SwiftApp.db.logins(profileId).mapNotNull { r ->
        decrypt(r.password)?.let { Login(r.guid, r.origin, r.username, it, r.created, r.used) }
    }

    /** Blocking. Deletes a sign-in and gives back what was stored, for Undo. */
    fun remove(guid: String): LoginRow? {
        val db = SwiftApp.db
        val row = db.logins(Profiles.currentId).firstOrNull { it.guid == guid }
        db.deleteLogin(guid)
        return row
    }

    fun putBack(row: LoginRow) = SwiftApp.db.saveLogin(row)

    /** Clearing browsing data: sign-ins saved since [since] (0: all of them). */
    fun clearSince(profileId: Long, since: Long) = SwiftApp.db.deleteLoginsSince(profileId, since)

    /** Sites the user said never to save for. */
    fun never(origin: String): Boolean = hostOf(origin) in (SwiftApp.prefs.sp.getStringSet(NEVER, emptySet()) ?: emptySet())

    fun setNever(origin: String, on: Boolean) {
        val set = (SwiftApp.prefs.sp.getStringSet(NEVER, emptySet()) ?: emptySet()).toMutableSet()
        if (on) set += hostOf(origin) else set -= hostOf(origin)
        SwiftApp.prefs.sp.edit().putStringSet(NEVER, set).apply()
    }

    fun neverSites(): List<String> = (SwiftApp.prefs.sp.getStringSet(NEVER, emptySet()) ?: emptySet()).sorted()
}
