package com.swiftbrowser.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.engine.Engine
import com.swiftbrowser.engine.Net
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Swift's VPN (2.1): WireGuard (the official library) with setup files from Proton VPN's free plan,
 * one file per server. Only Swift's own traffic goes through it; other apps stay as they are.
 *
 * The files hold a private key: they are kept encrypted with a key in Android's keystore, and are
 * not in backups. While the VPN should be on but isn't (connecting, or it dropped), pages can be
 * paused, so sites never see the real connection by accident (Engine.setNetworkBlocked).
 */
object Vpn {
    enum class State { OFF, CONNECTING, ON, FAILED }

    /** A server: its name in the file ("NL-FREE#133"), its country, and the encrypted file's id. */
    class Location(val id: String, val server: String, val country: String) {
        /** "Netherlands" from "NL" (the name in the phone's language). */
        val countryName: String
            get() = if (country.length == 2) Locale("", country).getDisplayCountry(Locale.getDefault()).ifBlank { country } else server

        /** The country's flag, made of the two "regional indicator" letters. */
        val flag: String
            get() = if (country.length == 2 && country.all { it in 'A'..'Z' }) {
                String(Character.toChars(0x1F1E6 + (country[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (country[1] - 'A')))
            } else {
                "🌐"
            }
    }

    @Volatile var state = State.OFF
        private set

    /** Why the last connection failed (shown to the user). */
    @Volatile var error: String? = null
        private set

    /** What sites see once connected: the IP address and its country and city, when known. */
    @Volatile var seenAs: String? = null
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var backend: GoBackend? = null

    /** The one tunnel Swift has; the library calls back when Android takes it down. */
    private val tunnel = object : Tunnel {
        override fun getName() = "swift"

        override fun onStateChange(newState: Tunnel.State) {
            main.post { onTunnelState(newState) }
        }
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    // ------------------------------------------------------------------ locations

    private fun dir(ctx: Context) = File(ctx.filesDir, "vpn").apply { mkdirs() }

    fun locations(): List<Location> {
        val arr = try {
            JSONArray(SwiftApp.prefs.sp.getString(KEY_LOCATIONS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Location(o.getString("id"), o.optString("server"), o.optString("country"))
        }
    }

    private fun saveLocations(list: List<Location>) {
        val arr = JSONArray()
        for (l in list) arr.put(JSONObject().put("id", l.id).put("server", l.server).put("country", l.country))
        SwiftApp.prefs.sp.edit().putString(KEY_LOCATIONS, arr.toString()).apply()
    }

    /** The chosen location (the first one when none was chosen). */
    fun current(): Location? {
        val list = locations()
        val id = SwiftApp.prefs.sp.getString(KEY_CURRENT, null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /** Whether the user wants the VPN on (kept across restarts for "Connect when Swift starts"). */
    var wanted: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(KEY_WANTED, false)
        private set(v) = SwiftApp.prefs.sp.edit().putBoolean(KEY_WANTED, v).apply()

    var connectAtStart: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(KEY_AT_START, false)
        set(v) = SwiftApp.prefs.sp.edit().putBoolean(KEY_AT_START, v).apply()

    var pauseWhenDown: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(KEY_PAUSE, true)
        set(v) {
            SwiftApp.prefs.sp.edit().putBoolean(KEY_PAUSE, v).apply()
            applyBlock()
        }

    /**
     * Adds a WireGuard setup file's text. Returns the new location, or throws with a message
     * people understand when it isn't one.
     */
    fun add(ctx: Context, text: String, fileName: String?): Location {
        val config = try {
            Config.parse(text.byteInputStream())
        } catch (e: Exception) {
            throw IllegalArgumentException("That isn't a WireGuard setup file. Download one from Proton VPN (WireGuard configuration).")
        }
        if (config.peers.isEmpty()) throw IllegalArgumentException("That setup file has no server in it.")
        // "# NL-FREE#133" under [Peer] names the server; else the file name ("Swift-NL-FREE-133.conf").
        val server = Regex("^#\\s*([A-Z]{2}[-A-Z0-9#]*#\\d+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?: fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: config.peers[0].endpoint.map { it.host }.orElse("Server")
        // The country is the server name's first part: "NL" in "NL-FREE#133".
        val country = server.substringBefore('-').substringBefore('#').uppercase(Locale.ROOT).takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: ""
        val existing = locations()
        if (existing.any { it.server == server }) throw IllegalArgumentException("$server is already there.")
        val id = "l" + System.currentTimeMillis().toString(36)
        File(dir(ctx), "$id.bin").writeBytes(Secret.seal(text.toByteArray()))
        val loc = Location(id, server, country)
        saveLocations(existing + loc)
        if (existing.isEmpty()) choose(loc)
        changed()
        return loc
    }

    fun remove(ctx: Context, loc: Location) {
        if (current()?.id == loc.id && wanted) turnOff()
        File(dir(ctx), "${loc.id}.bin").delete()
        saveLocations(locations().filter { it.id != loc.id })
        changed()
    }

    /** Undo of [remove]: the file is kept by the caller until the Undo is gone. */
    fun restore(ctx: Context, loc: Location, sealed: ByteArray) {
        File(dir(ctx), "${loc.id}.bin").writeBytes(sealed)
        saveLocations(locations() + loc)
        changed()
    }

    fun sealedFile(ctx: Context, loc: Location): ByteArray? = File(dir(ctx), "${loc.id}.bin").takeIf { it.exists() }?.readBytes()

    fun choose(loc: Location) {
        SwiftApp.prefs.sp.edit().putString(KEY_CURRENT, loc.id).apply()
    }

    // ------------------------------------------------------------------ connecting

    /**
     * Connects to [loc] (or the chosen location). Android's permission (VpnService.prepare) must
     * have been given: the screens ask for it first.
     */
    fun turnOn(ctx: Context, loc: Location? = current()) {
        val target = loc ?: return
        choose(target)
        wanted = true
        state = State.CONNECTING
        error = null
        seenAs = null
        applyBlock()
        changed()
        val app = ctx.applicationContext
        val attempt = ++attempts
        io.execute {
            try {
                val sealed = File(dir(app), "${target.id}.bin").readBytes()
                val text = String(Secret.open(sealed))
                val config = Config.parse(onlySwift(text, app.packageName).byteInputStream())
                val b = backend ?: GoBackend(app).also { backend = it }
                b.setState(tunnel, Tunnel.State.UP, config)
                // WireGuard counts as up straight away: the server has only answered once a handshake is done.
                if (!waitForHandshake(b, config, attempt)) {
                    if (attempt != attempts) return@execute
                    b.setState(tunnel, Tunnel.State.DOWN, null)
                    throw NoAnswer(target.server)
                }
                main.post {
                    if (attempt != attempts) return@post
                    state = State.ON
                    applyBlock()
                    changed()
                    checkCountry()
                }
            } catch (e: Exception) {
                L.w("VPN didn't connect", e)
                main.post {
                    if (attempt != attempts) return@post
                    state = State.FAILED
                    error = describe(e)
                    applyBlock()
                    changed()
                }
            }
        }
    }

    /** Turning on, off or to another location counts up: an older attempt still running gives way. */
    @Volatile private var attempts = 0

    private class NoAnswer(val server: String) : Exception("No handshake with $server")

    /**
     * Blocking: true once the server answered (a WireGuard handshake), false after [HANDSHAKE_WAIT_MS]
     * (the server is full, switched off or blocked) or when a newer attempt took over.
     */
    private fun waitForHandshake(b: GoBackend, config: Config, attempt: Int): Boolean {
        val peer = config.peers.firstOrNull()?.publicKey ?: return true
        val until = android.os.SystemClock.elapsedRealtime() + HANDSHAKE_WAIT_MS
        while (android.os.SystemClock.elapsedRealtime() < until && attempt == attempts) {
            val last = try {
                b.getStatistics(tunnel).peer(peer)?.latestHandshakeEpochMillis() ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (last > 0L) return true
            Thread.sleep(250)
        }
        return false
    }

    fun turnOff() {
        attempts++
        wanted = false
        state = State.OFF
        seenAs = null
        error = null
        applyBlock()
        changed()
        val b = backend ?: return
        io.execute {
            try {
                b.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                L.w("VPN didn't stop", e)
            }
        }
    }

    /** At start-up: on again if it was on and "Connect when Swift starts" is set. */
    fun onAppStart(ctx: Context) {
        Engine.resetNetworkBlock()
        if (wanted && connectAtStart && current() != null) turnOn(ctx) else if (wanted) {
            wanted = false
        }
        applyBlock()
    }

    private fun onTunnelState(s: Tunnel.State) {
        if (s == Tunnel.State.DOWN && state == State.ON) {
            // Android took it down (another VPN app started, or it was turned off in Android's settings).
            state = if (wanted) State.FAILED else State.OFF
            error = if (wanted) "The VPN was stopped by Android or another VPN app." else null
            seenAs = null
            applyBlock()
            changed()
        }
    }

    /** Pages wait while the VPN should be on but isn't (and "Pause pages if the VPN drops" is set). */
    private fun applyBlock() {
        val block = wanted && pauseWhenDown && state != State.ON
        try {
            Engine.setNetworkBlocked(block)
        } catch (_: UninitializedPropertyAccessException) {
        }
    }

    /** What sites see now: asked of a public "what's my IP" service, through the VPN. */
    fun checkCountry() {
        io.execute {
            val r = Net.get("https://ipinfo.io/json", 8192, "application/json", 10_000)
            val text = r?.let { String(it.bytes) }
            val seen = try {
                val o = JSONObject(text ?: return@execute)
                val country = o.optString("country").let { c -> if (c.length == 2) Locale("", c).displayCountry else c }
                listOf(o.optString("ip"), listOf(o.optString("city"), country).filter { it.isNotBlank() }.joinToString(", "))
                    .filter { it.isNotBlank() }.joinToString(" · ")
            } catch (_: Exception) {
                null
            }
            main.post {
                if (state == State.ON) {
                    seenAs = seen
                    changed()
                }
            }
        }
    }

    /** Only Swift's traffic goes through the tunnel: other apps keep their own connection. */
    private fun onlySwift(text: String, pkg: String): String {
        val lines = text.lines().filter {
            val key = it.substringBefore('=').trim().lowercase(Locale.ROOT)
            key != "includedapplications" && key != "excludedapplications"
        }.toMutableList()
        val at = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (at >= 0) lines.add(at + 1, "IncludedApplications = $pkg")
        return lines.joinToString("\n")
    }

    private fun describe(e: Exception): String {
        val msg = (e.message ?: "").lowercase(Locale.ROOT)
        return when {
            e is NoAnswer -> "${e.server} didn't answer. Free servers are sometimes full: try another location, or again in a while."
            e is com.wireguard.android.backend.BackendException && e.reason == com.wireguard.android.backend.BackendException.Reason.VPN_NOT_AUTHORIZED ->
                "Android didn't allow the VPN. Turn it on again and tap OK when Android asks."
            msg.contains("permission") || msg.contains("authorized") -> "Android didn't allow the VPN. Turn it on again and tap OK when Android asks."
            else -> "Couldn't connect. Check your internet connection, or try another location."
        }
    }

    /** WireGuard tries the handshake every 5 seconds: three tries. */
    private const val HANDSHAKE_WAIT_MS = 15_000L

    private const val KEY_LOCATIONS = "vpn_locations"
    private const val KEY_CURRENT = "vpn_current"
    private const val KEY_WANTED = "vpn_wanted"
    private const val KEY_AT_START = "vpn_at_start"
    private const val KEY_PAUSE = "vpn_pause_when_down"

    /** Settings that stay on this phone (Backup leaves them out; the files themselves never go). */
    val PREF_KEYS = setOf(KEY_LOCATIONS, KEY_CURRENT, KEY_WANTED, KEY_AT_START, KEY_PAUSE)

    /** AES-GCM with a key that never leaves Android's keystore. */
    private object Secret {
        private const val ALIAS = "swift_vpn"

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return gen.generateKey()
        }

        fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
        }

        fun open(sealed: ByteArray): ByteArray {
            val ivLen = sealed[0].toInt()
            val iv = sealed.copyOfRange(1, 1 + ivLen)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return c.doFinal(sealed, 1 + ivLen, sealed.size - 1 - ivLen)
        }
    }
}
