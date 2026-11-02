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

    /**
     * A server: its name in the file ("NL-FREE#133"), its country, the encrypted file's id, and when it
     * was added (or last extended at Proton): free files stop working a year later.
     */
    class Location(val id: String, val server: String, val country: String, val added: Long) {
        /** When the file stops working unless extended at Proton. */
        val renewBy: Long get() = added + YEAR_MS

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

    /**
     * 3.0: Swift is turning the VPN back on by itself at start (it was on when Swift closed: an
     * update, a crash, Android closing it; or "Turn on the VPN every time Swift starts"). Until it is
     * connected, or the user chooses to go on without it, pages wait even if "Pause pages if the VPN
     * drops" is off: nothing goes over the real connection.
     */
    @Volatile var resuming = false
        private set

    /** 3.0: why the VPN didn't come back on at start, for the VPN sheet (the message says what to do). */
    @Volatile var resumeReason: String? = null
        private set

    /** 3.0: locations whose file can't be opened any more (Android's keystore lost Swift's key): added again from their .conf. */
    private val unreadable = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isUnreadable(loc: Location): Boolean = loc.id in unreadable

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
            val id = o.getString("id")
            // Added before 2.2: the day its encrypted file was written.
            val added = o.optLong("added", 0L).takeIf { it > 0 }
                ?: SwiftApp.instance?.let { File(File(it.filesDir, "vpn"), "$id.bin").lastModified() }?.takeIf { it > 0 }
                ?: System.currentTimeMillis()
            Location(id, o.optString("server"), o.optString("country"), added)
        }
    }

    private fun saveLocations(list: List<Location>) {
        val arr = JSONArray()
        for (l in list) arr.put(JSONObject().put("id", l.id).put("server", l.server).put("country", l.country).put("added", l.added))
        SwiftApp.prefs.sp.edit().putString(KEY_LOCATIONS, arr.toString()).apply()
    }

    /** The chosen location (the first one when none was chosen). */
    fun current(): Location? {
        val list = locations()
        val id = SwiftApp.prefs.sp.getString(KEY_CURRENT, null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /** Whether the user wants the VPN on (kept across restarts: 3.0, Swift turns it back on at start). */
    var wanted: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(KEY_WANTED, false)
        private set(v) = SwiftApp.prefs.sp.edit().putBoolean(KEY_WANTED, v).apply()

    /**
     * 3.0: the VPN turns on every time Swift starts, even if it was off when Swift closed (if it was
     * on, it always comes back on). A key of its own: before 3.0 "Connect when Swift starts" meant
     * only "back on if it was on" (now always so), and those who had it on didn't ask for this.
     */
    var connectAtStart: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(KEY_EVERY_START, false)
        set(v) = SwiftApp.prefs.sp.edit().putBoolean(KEY_EVERY_START, v).apply()

    /** 2.2: when a server doesn't answer (or stops answering), move on to another location by itself. */
    var autoSwitch: Boolean
        get() = SwiftApp.prefs.sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(v) = SwiftApp.prefs.sp.edit().putBoolean(KEY_AUTO_SWITCH, v).apply()

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
        val loc = Location(id, server, country, System.currentTimeMillis())
        saveLocations(existing + loc)
        if (existing.isEmpty()) choose(loc)
        changed()
        return loc
    }

    /**
     * Removes [loc]. 3.0: the location in use, removed while the VPN is on, makes way for another
     * one (the same country first; pages wait meanwhile) instead of turning the VPN off; only with
     * none left does it go off. Returns the location it moved to, if any.
     */
    fun remove(ctx: Context, loc: Location): Location? {
        val inUse = current()?.id == loc.id && wanted
        val next = if (inUse) spareFor(loc) else null
        File(dir(ctx), "${loc.id}.bin").delete()
        unreadable -= loc.id
        saveLocations(locations().filter { it.id != loc.id })
        if (inUse) {
            if (next != null) turnOn(ctx, next) else turnOff()
        }
        changed()
        return next
    }

    /**
     * Undo of [remove]: the file is kept by the caller until the Undo is gone. [inUse]: it was the
     * one in use, so it is chosen again (the caller reconnects to it).
     */
    fun restore(ctx: Context, loc: Location, sealed: ByteArray, inUse: Boolean = false) {
        File(dir(ctx), "${loc.id}.bin").writeBytes(sealed)
        saveLocations(locations() + loc)
        if (inUse) choose(loc)
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
    fun turnOn(ctx: Context, loc: Location? = current(), auto: Boolean = false) {
        val target = loc ?: return
        // Chosen by the user: a fresh start; switched by itself: the ones that failed stay out.
        if (!auto) {
            failedInChain.clear()
            switchNote = null
        }
        stopWatching()
        testing = false
        testStep = null
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
                val text = String(Secret.open(readSealed(app, target)))
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
                    // 3.0: back on: from now on "Pause pages if the VPN drops" decides, as usual.
                    resuming = false
                    resumeReason = null
                    applyBlock()
                    changed()
                    checkCountry()
                    // Proton's files keep the tunnel alive (a handshake every 2 minutes), so a long
                    // silence means the server stopped answering.
                    config.peers.firstOrNull()?.takeIf { it.persistentKeepalive.isPresent }?.let { watch(app, b, it.publicKey, attempt) }
                }
            } catch (e: Exception) {
                L.w("VPN didn't connect", e)
                main.post {
                    if (attempt != attempts) return@post
                    if (e is Unreadable) unreadable += target.id
                    if (e is NoAnswer && switchAway(app, target)) return@post
                    state = State.FAILED
                    val reason = if (e is NoAnswer && failedInChain.size > 1) {
                        "None of your locations answered. Check your connection, or try again in a while."
                    } else {
                        describe(e, target)
                    }
                    // 3.0: coming back on at start, the message says what can be done (the VPN sheet
                    // offers Try again and Continue without VPN, and gives the reason).
                    if (resuming) {
                        resumeReason = reason
                        error = RESUME_FAILED
                    } else {
                        error = reason
                    }
                    failedInChain.clear()
                    applyBlock()
                    changed()
                }
            }
        }
    }

    /**
     * Blocking: a location's sealed file. 3.0: a missing one is unreadable, like one the keystore
     * can't open (it said "check your internet connection").
     */
    private fun readSealed(ctx: Context, loc: Location): ByteArray = try {
        File(dir(ctx), "${loc.id}.bin").readBytes()
    } catch (_: java.io.IOException) {
        throw Unreadable()
    }

    /** 3.0: a location's file that can't be opened: its key went with a reset of Android's secure storage. */
    private class Unreadable : Exception("The location's file can't be opened")

    /** Turning on, off or to another location counts up: an older attempt still running gives way. */
    @Volatile private var attempts = 0

    private class NoAnswer(val server: String) : Exception("No handshake with $server")

    /**
     * Blocking: true once the server answered (a WireGuard handshake), false after [HANDSHAKE_WAIT_MS]
     * (the server is full, switched off or blocked) or when a newer attempt took over.
     */
    private fun waitForHandshake(b: GoBackend, config: Config, attempt: Int, pollMs: Long = 250): Boolean {
        val peer = config.peers.firstOrNull()?.publicKey ?: return true
        val until = android.os.SystemClock.elapsedRealtime() + HANDSHAKE_WAIT_MS
        while (android.os.SystemClock.elapsedRealtime() < until && attempt == attempts) {
            val last = try {
                b.getStatistics(tunnel).peer(peer)?.latestHandshakeEpochMillis() ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (last > 0L) return true
            Thread.sleep(pollMs)
        }
        return false
    }

    fun turnOff() {
        attempts++
        stopWatching()
        failedInChain.clear()
        switchNote = null
        testing = false
        testStep = null
        wanted = false
        resuming = false
        resumeReason = null
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

    /**
     * At start-up (3.0): the VPN comes back on by itself when it was on as Swift closed (an update, a
     * crash, Android closing it), and always with "Turn on the VPN every time Swift starts"; pages
     * wait until it is connected ([resuming]). Before, it stayed off unless "Connect when Swift
     * starts" (off at first) was set, and the tabs loaded over the real connection.
     */
    fun onAppStart(ctx: Context) {
        Engine.resetNetworkBlock()
        // Before 3.0's own key: what it meant is always so now.
        if (SwiftApp.prefs.sp.contains(KEY_AT_START)) SwiftApp.prefs.sp.edit().remove(KEY_AT_START).apply()
        val loc = current()
        if ((wanted || connectAtStart) && loc != null) {
            resuming = true
            L.d("VPN: turning it back on at start" + if (wanted) "" else " (on every start)")
            turnOn(ctx, loc)
        } else if (wanted) {
            wanted = false
        }
        applyBlock()
    }

    private fun onTunnelState(s: Tunnel.State) {
        if (s == Tunnel.State.DOWN && state == State.ON && !testing) {
            // Android took it down (another VPN app started, or it was turned off in Android's settings).
            state = if (wanted) State.FAILED else State.OFF
            error = if (wanted) "The VPN was stopped by Android or another VPN app." else null
            seenAs = null
            applyBlock()
            changed()
        }
    }

    // ------------------------------------------------------------------ expiry and spares (2.2)

    /** The location ends its year again: the user extended it on Proton's page. */
    fun markExtended(loc: Location) {
        saveLocations(locations().map { if (it.id == loc.id) Location(it.id, it.server, it.country, System.currentTimeMillis()) else it })
        changed()
    }

    /** The location that stops working soonest, if within two weeks; once a day at most. */
    fun expiringToRemind(): Location? {
        val now = System.currentTimeMillis()
        val soonest = locations().filter { it.renewBy - now < REMIND_BEFORE_MS }.minByOrNull { it.renewBy } ?: return null
        val day = now / DAY_MS
        val sp = SwiftApp.prefs.sp
        if (sp.getLong(KEY_REMINDED, -1L) == day) return null
        sp.edit().putLong(KEY_REMINDED, day).apply()
        return soonest
    }

    /**
     * Another location to try when [loc] doesn't answer: the same country first, then any, the
     * fastest in the last test first (ones that didn't answer then last); none in [exclude].
     */
    fun spareFor(loc: Location?, exclude: Set<String> = emptySet()): Location? {
        val others = ranked(locations().filter { it.id != loc?.id && it.id !in exclude })
        return others.firstOrNull { it.country == loc?.country } ?: others.firstOrNull()
    }

    /** Fastest first by the last test: measured ones, then untested ones, then ones that didn't answer. */
    fun ranked(list: List<Location>): List<Location> = list.sortedBy {
        when (val ms = testResults[it.id]) {
            null -> Long.MAX_VALUE - 1
            NO_ANSWER -> Long.MAX_VALUE
            else -> ms
        }
    }

    /** Locations that didn't answer since the user last chose one (automatic switching skips them). */
    private val failedInChain = HashSet<String>()

    /** "NL-FREE#133 stopped answering": why Swift switched by itself; shown once it's connected. */
    var switchNote: String? = null

    /** [from] didn't answer: on to the next location by itself (Settings: "Switch to another location"). True when it did. */
    private fun switchAway(ctx: Context, from: Location, stopped: Boolean = false): Boolean {
        if (!autoSwitch || !wanted) return false
        failedInChain += from.id
        val next = spareFor(from, failedInChain) ?: return false
        switchNote = "${from.server} " + (if (stopped) "stopped answering" else "didn't answer") + ": switched to ${next.flag} ${next.countryName} (${next.server})"
        L.d("VPN: ${from.server} not answering, trying ${next.server}")
        turnOn(ctx, next, auto = true)
        return true
    }

    private var watcher: Runnable? = null

    /**
     * While connected: every 30 seconds, whether the server still answers. WireGuard renews the
     * handshake every 2 minutes on a kept-alive tunnel; none for over 3 minutes means it stopped.
     */
    private fun watch(ctx: Context, b: GoBackend, peer: com.wireguard.crypto.Key, attempt: Int) {
        stopWatching()
        val check = object : Runnable {
            override fun run() {
                if (attempt != attempts || state != State.ON) return
                val self = this
                io.execute {
                    val last = try {
                        b.getStatistics(tunnel).peer(peer)?.latestHandshakeEpochMillis() ?: 0L
                    } catch (_: Exception) {
                        -1L
                    }
                    main.post {
                        if (attempt != attempts || state != State.ON) return@post
                        if (last > 0L && System.currentTimeMillis() - last > SILENT_MS) {
                            val loc = current() ?: return@post
                            L.w("VPN: no handshake with ${loc.server} for ${(System.currentTimeMillis() - last) / 1000} s")
                            if (!switchAway(ctx, loc, stopped = true)) {
                                state = State.FAILED
                                error = "${loc.server} stopped answering. Try another location, or again in a while."
                                seenAs = null
                                applyBlock()
                                changed()
                            }
                        } else {
                            main.postDelayed(self, WATCH_MS)
                        }
                    }
                }
            }
        }
        watcher = check
        main.postDelayed(check, WATCH_MS)
    }

    private fun stopWatching() {
        watcher?.let { main.removeCallbacks(it) }
        watcher = null
    }

    // ------------------------------------------------------------------ testing the locations (2.2)

    /** Locations being tried one after another (the VPN is off meanwhile; pages wait if it should be on). */
    @Volatile var testing = false
        private set

    /** While testing: which one (1-based) of how many. */
    var testStep: Pair<Int, Int>? = null
        private set

    /** Location id → how long its server took to answer (ms), or [NO_ANSWER]. Kept until Swift closes. */
    val testResults = HashMap<String, Long>()

    const val NO_ANSWER = -1L

    /**
     * Connects to each location in turn and times its server's answer (up to 15 seconds each),
     * then reconnects to the chosen location if the VPN was on. Turning the VPN on or off meanwhile
     * stops the test. Android's permission must have been given (VpnUi.test asks first).
     */
    fun testAll(ctx: Context) {
        val list = locations()
        if (testing || list.isEmpty()) return
        val resume = wanted
        val attempt = ++attempts
        testing = true
        testStep = 1 to list.size
        state = State.CONNECTING
        error = null
        seenAs = null
        applyBlock()
        changed()
        val app = ctx.applicationContext
        io.execute {
            val b = backend ?: GoBackend(app).also { backend = it }
            for ((n, loc) in list.withIndex()) {
                if (attempt != attempts) break
                main.post {
                    testStep = n + 1 to list.size
                    changed()
                }
                val ms = try {
                    val text = try {
                        String(Secret.open(readSealed(app, loc)))
                    } catch (e: Unreadable) {
                        unreadable += loc.id
                        throw e
                    }
                    val config = Config.parse(onlySwift(text, app.packageName).byteInputStream())
                    val start = android.os.SystemClock.elapsedRealtime()
                    b.setState(tunnel, Tunnel.State.UP, config)
                    if (waitForHandshake(b, config, attempt, pollMs = 100)) android.os.SystemClock.elapsedRealtime() - start else NO_ANSWER
                } catch (e: Exception) {
                    L.w("VPN test of ${loc.server} failed", e)
                    NO_ANSWER
                }
                if (attempt != attempts) break
                main.post {
                    testResults[loc.id] = ms
                    changed()
                }
            }
            val stillOurs = attempt == attempts
            if (stillOurs) {
                try {
                    b.setState(tunnel, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
            }
            main.post {
                testing = false
                testStep = null
                // Turned on or off meanwhile: that took over.
                if (!stillOurs) return@post
                if (resume) {
                    turnOn(app)
                } else {
                    state = State.OFF
                    applyBlock()
                    changed()
                }
            }
        }
    }

    /**
     * Pages wait while the VPN should be on but isn't (and "Pause pages if the VPN drops" is set;
     * 3.0: or while it comes back on at start, whatever that says).
     */
    private fun applyBlock() {
        val block = wanted && (pauseWhenDown || resuming) && state != State.ON
        try {
            Engine.setNetworkBlocked(block)
        } catch (_: UninitializedPropertyAccessException) {
        }
    }

    /** What sites see now: asked of a public "what's my IP" service, through the VPN. */
    fun checkCountry() {
        val attempt = attempts
        io.execute {
            val r = Net.get("https://ipinfo.io/json", 8192, "application/json", 10_000)
            val text = r?.let { String(it.bytes) }
            val seen = try {
                val o = JSONObject(text ?: throw IllegalStateException("No answer"))
                val country = o.optString("country").let { c -> if (c.length == 2) Locale("", c).displayCountry else c }
                listOf(o.optString("ip"), listOf(o.optString("city"), country).filter { it.isNotBlank() }.joinToString(", "))
                    .filter { it.isNotBlank() }.joinToString(" · ")
            } catch (_: Exception) {
                null
            }
            // Nothing from that service: a second one tells a server that passes nothing (a revoked or
            // expired file still completes the handshake) from that one service being down.
            val passesNothing = seen == null && Net.get("https://www.cloudflare.com/cdn-cgi/trace", 4096, "text/plain", 10_000) == null
            main.post {
                if (state != State.ON || attempt != attempts) return@post
                if (passesNothing) {
                    val loc = current()
                    val app = SwiftApp.instance
                    if (loc != null && app != null && switchAway(app, loc, stopped = true)) return@post
                    state = State.FAILED
                    error = if (failedInChain.size > 1) {
                        "None of your locations answered. Check your connection, or try again in a while."
                    } else {
                        "${loc?.server ?: "The server"} connects but passes nothing (its file may have expired or been revoked at Proton). Try another location."
                    }
                    failedInChain.clear()
                    seenAs = null
                    applyBlock()
                    changed()
                    return@post
                }
                // It works: earlier failures no longer matter for automatic switching.
                failedInChain.clear()
                seenAs = seen ?: CHECK_FAILED
                changed()
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

    private fun describe(e: Exception, loc: Location): String {
        val msg = (e.message ?: "").lowercase(Locale.ROOT)
        return when {
            e is NoAnswer -> "${e.server} didn't answer. Free servers are sometimes full: try another location, or again in a while."
            // 3.0: it said "Check your internet connection" when the keystore had lost the key.
            e is Unreadable -> "${loc.server} can't be opened any more: Android's secure storage was reset. " +
                "Remove it and add it again from its .conf file (from Proton's page if you don't have it)."
            e is com.wireguard.android.backend.BackendException && e.reason == com.wireguard.android.backend.BackendException.Reason.VPN_NOT_AUTHORIZED ->
                "Android didn't allow the VPN. Turn it on again and tap OK when Android asks."
            msg.contains("permission") || msg.contains("authorized") -> "Android didn't allow the VPN. Turn it on again and tap OK when Android asks."
            else -> "Couldn't connect. Check your internet connection, or try another location."
        }
    }

    /** [seenAs] when nothing came back through the VPN (the server took the key but passes nothing). */
    const val CHECK_FAILED = "not known (the address service didn't answer)"

    /** 3.0: the message when the VPN didn't come back on at start ([resumeReason] says why). */
    const val RESUME_FAILED = "The VPN didn't come back on, so pages are waiting. Tap VPN to try again or continue without it."

    /** Proton's free WireGuard files work for a year unless extended on its downloads page. */
    const val PROTON_DOWNLOADS = "https://account.protonvpn.com/downloads"
    private const val DAY_MS = 24L * 3600 * 1000
    private const val YEAR_MS = 365 * DAY_MS
    private const val REMIND_BEFORE_MS = 14 * DAY_MS
    private const val KEY_REMINDED = "vpn_reminded_day"

    private const val WATCH_MS = 30_000L

    /** WireGuard's handshake is renewed every 2 minutes and a session ends after 3: past that, nothing gets through. */
    private const val SILENT_MS = 200_000L

    /** WireGuard tries the handshake every 5 seconds: three tries. */
    private const val HANDSHAKE_WAIT_MS = 15_000L

    private const val KEY_LOCATIONS = "vpn_locations"
    private const val KEY_CURRENT = "vpn_current"
    private const val KEY_WANTED = "vpn_wanted"
    private const val KEY_AT_START = "vpn_at_start"
    private const val KEY_EVERY_START = "vpn_every_start"
    private const val KEY_PAUSE = "vpn_pause_when_down"
    private const val KEY_AUTO_SWITCH = "vpn_auto_switch"

    /** Settings that stay on this phone (Backup leaves them out; the files themselves never go). */
    val PREF_KEYS = setOf(KEY_LOCATIONS, KEY_CURRENT, KEY_WANTED, KEY_AT_START, KEY_EVERY_START, KEY_PAUSE, KEY_REMINDED, KEY_AUTO_SWITCH)

    /**
     * AES-GCM with a key that never leaves Android's keystore. 3.0: Android can lose the key (a reset
     * of its secure storage); a new one is only made for new files, as it can't open the old ones,
     * which then count as unreadable instead of failing like a bad connection.
     */
    private object Secret {
        private const val ALIAS = "swift_vpn"

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        /**
         * The key the files were sealed with, or null when the keystore no longer has a usable one.
         * The keystore itself failing throws instead: a new key must never take a working one's place.
         */
        private fun existingKey(ks: KeyStore): SecretKey? {
            if (!ks.containsAlias(ALIAS)) return null
            return try {
                ks.getKey(ALIAS, null) as? SecretKey
            } catch (_: java.security.UnrecoverableKeyException) {
                null
            }
        }

        private fun key(): SecretKey {
            val ks = keyStore()
            existingKey(ks)?.let { return it }
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
            val key = existingKey(keyStore()) ?: throw Unreadable()
            return try {
                val ivLen = sealed[0].toInt()
                val iv = sealed.copyOfRange(1, 1 + ivLen)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                c.doFinal(sealed, 1 + ivLen, sealed.size - 1 - ivLen)
            } catch (e: java.security.GeneralSecurityException) {
                // Sealed with a key the keystore lost (a new one can't open it), or the file is damaged.
                L.w("VPN: a location's file can't be opened", e)
                throw Unreadable()
            } catch (e: IndexOutOfBoundsException) {
                throw Unreadable()
            }
        }
    }
}
