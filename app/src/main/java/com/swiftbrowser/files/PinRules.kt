package com.swiftbrowser.files

import java.security.MessageDigest
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * Private's PIN, kept only as a salted hash (3.0: a slower one, with its version in front). "1:salt:hash"
 * is 2.9's: 4,000 rounds of PBKDF2 with SHA-1. "2:salt:hash" has 60,000 rounds, about half a second on
 * a slow phone, so the 10,000 PINs of 4 digits can't be tried quickly even by someone who copied Swift's
 * data. Its hash is SHA-1's own 160 bits: a longer one would cost the phone twice the time and a guesser
 * nothing. (PBKDF2 with SHA-256 isn't on Android 7.) A PIN kept the 2.9 way is kept the new way the
 * next time it is typed right.
 */
object PinHash {
    private const val V1_ROUNDS = 4000
    private const val V1_BITS = 256
    private const val V2_ROUNDS = 60_000
    private const val V2_BITS = 160

    /** [pin] as it is kept, with [salt] (16 random bytes). */
    fun make(pin: String, salt: ByteArray): String = "2:" + hex(salt) + ":" + hex(pbkdf2(pin, salt, V2_ROUNDS, V2_BITS))

    /** Whether [pin] is the one kept as [stored] (either version). */
    fun matches(pin: String, stored: String?): Boolean {
        val p = stored?.split(':') ?: return false
        if (p.size != 3) return false
        return try {
            val salt = unhex(p[1])
            val want = unhex(p[2])
            val got = when (p[0]) {
                "1" -> pbkdf2(pin, salt, V1_ROUNDS, V1_BITS)
                "2" -> pbkdf2(pin, salt, V2_ROUNDS, V2_BITS)
                else -> return false
            }
            MessageDigest.isEqual(got, want)
        } catch (_: Exception) {
            false
        }
    }

    /** Kept the older, quicker way: kept again the new way once typed right. */
    fun outdated(stored: String?): Boolean = stored != null && !stored.startsWith("2:")

    private fun pbkdf2(pin: String, salt: ByteArray, rounds: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, bits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * How long Private makes someone wait after wrong PINs (3.0). Five can be wrong; then each one more
 * waits longer than the last: 30 seconds, 5 minutes, 30 minutes, 2 hours, 8 hours, then a day each
 * time. Waiting doesn't start the count again (2.9's did, so ten guesses a minute went on for ever);
 * only the right PIN does. "Forgot PIN" waits a day before the PIN can be reset.
 */
object PinWait {
    const val FREE_TRIES = 5

    const val RESET_MS = 24 * HOUR

    private val STEPS = longArrayOf(30_000L, 5 * MINUTE, 30 * MINUTE, 2 * HOUR, 8 * HOUR, 24 * HOUR)

    /** The wait after [wrong] wrong PINs in a row (0: none yet). */
    fun after(wrong: Int): Long = if (wrong < FREE_TRIES) 0L else STEPS[(wrong - FREE_TRIES).coerceAtMost(STEPS.size - 1)]
}

/**
 * A moment as the phone tells it (3.0): which start of the phone it is in ([boot], -1 when not known),
 * how long the phone has been on since ([elapsed], which no setting of the clock changes) and the
 * clock ([wall], only for saying when something happened).
 */
class Moment(val boot: Int, val elapsed: Long, val wall: Long)

/**
 * A wait of [left] ms as it stood at [at] (3.0). Only time the phone was surely on counts, so putting
 * the clock forward never shortens it; after the phone restarts, only the time since then counts
 * (what came before isn't known), which may make it a little longer, never shorter.
 */
class Wait(val left: Long, val at: Moment) {
    /** What is left of it at [now]. */
    fun leftAt(now: Moment): Long {
        val sameStart = now.boot == at.boot && now.elapsed >= at.elapsed
        val passed = if (sameStart) now.elapsed - at.elapsed else now.elapsed
        return (left - passed).coerceIn(0L, left)
    }

    /** The same wait as it stands at [now], kept from then on (a restart then loses less of it). */
    fun at(now: Moment) = Wait(leftAt(now), now)

    fun encode(): String = "$left:${at.boot}:${at.elapsed}:${at.wall}"

    companion object {
        fun decode(s: String?): Wait? {
            val p = s?.split(':') ?: return null
            if (p.size != 4) return null
            val left = p[0].toLongOrNull() ?: return null
            val boot = p[1].toIntOrNull() ?: return null
            val elapsed = p[2].toLongOrNull() ?: return null
            val wall = p[3].toLongOrNull() ?: return null
            if (left < 0) return null
            return Wait(left, Moment(boot, elapsed, wall))
        }
    }
}
