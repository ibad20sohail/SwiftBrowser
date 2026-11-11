package com.swiftbrowser.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 3.0.8: the lock on Swift's backups (the Google backup, and passwords and sign-ins in backup files):
 * a key made from the backup password with PBKDF2-HMAC-SHA256 (done here: Android only has it built in
 * from 8.0) and AES-256-GCM, which also tells a wrong password from a right one. Plain, for tests.
 */
object BackupCrypto {
    /** Rounds of PBKDF2: about a second or two on a Moto E4, once when the password is set or typed. */
    const val ITERATIONS = 310_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    fun salt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /** The key made from [password] (PBKDF2-HMAC-SHA256, RFC 8018). Slow on purpose. */
    fun keyFrom(password: String, salt: ByteArray, iterations: Int = ITERATIONS, length: Int = KEY_BYTES): ByteArray {
        require(password.isNotEmpty() && iterations > 0 && length > 0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = ByteArray(length)
        var pos = 0
        var block = 1
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    /** [plain] locked with [key]: a fresh IV, then the encrypted bytes and their tag. [extra] is checked too, unencrypted. */
    fun seal(key: ByteArray, plain: ByteArray, extra: ByteArray? = null): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        if (extra != null) c.updateAAD(extra)
        return iv + c.doFinal(plain)
    }

    /** What [seal] locked, or null with a wrong key (a wrong password) or a changed file. */
    fun open(key: ByteArray, sealed: ByteArray, extra: ByteArray? = null): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            if (extra != null) c.updateAAD(extra)
            c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    /** Unzips [data], never past [max] bytes (a damaged or made-up file isn't read without end). */
    fun gunzip(data: ByteArray, max: Int): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            out.toByteArray()
        }
    } catch (_: java.io.IOException) {
        null
    }

    // ------------------------------------------------------------------ sign-ins of profiles

    /** The engine's name for a Swift profile's pages ("gvctx" + the hex of "profile-<id>", as GeckoView writes it). */
    fun contextOf(profileId: Long): String = "gvctx" + "profile-$profileId".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private val CONTEXT = Regex("geckoViewUserContextId=(gvctx[0-9a-f]+)")

    /**
     * A cookie's origin attributes ("^geckoViewUserContextId=gvctx…&partitionKey=…") with its profile
     * moved to the one it has on this phone ([ids]: the backup's profile id → this phone's); null when
     * its profile isn't known here (the cookie is left out).
     */
    fun moveProfile(originAttributes: String, ids: Map<Long, Long>): String? {
        val m = CONTEXT.find(originAttributes) ?: return originAttributes
        val old = ids.keys.firstOrNull { contextOf(it) == m.groupValues[1] } ?: return null
        return originAttributes.replaceRange(m.groups[1]!!.range, contextOf(ids.getValue(old)))
    }
}
