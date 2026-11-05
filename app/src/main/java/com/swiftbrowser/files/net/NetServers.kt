package com.swiftbrowser.files.net

import android.content.Context
import com.swiftbrowser.core.Passwords
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 3.0.4: the computers and storages added to Network folders, in a preferences file of their own
 * ("network_folders"). Swift's backups read only its main settings file (and Android's own backup
 * is off), so these addresses and user names never go into one. A password is kept only when the
 * user chose to remember it, locked with the keystore key of saved sign-ins (Passwords.lock); one
 * typed without that is held in memory while Swift runs, never on disk.
 */
object NetServers {
    private const val FILE = "network_folders"
    private const val PREFIX = "server."

    @Volatile
    private var cache: List<NetServer>? = null

    /** Passwords typed (or unlocked) for each server, by its id: in memory only, while Swift runs. */
    private val typed = ConcurrentHashMap<String, String>()

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** All of them, by name. A small file, read once (later calls are in memory). */
    fun all(ctx: Context): List<NetServer> = cache ?: synchronized(this) {
        cache ?: sp(ctx).all.mapNotNull { (k, v) -> if (k.startsWith(PREFIX)) (v as? String)?.let { NetServer.decode(it) } else null }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .also { cache = it }
    }

    fun get(ctx: Context, id: String): NetServer? = all(ctx).firstOrNull { it.id == id }

    fun count(ctx: Context): Int = all(ctx).size

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Saves [server] (new, or changed). [password]: what was typed, or null to keep the one it has;
     * locked and kept when [remember], otherwise held in memory only. Blocking (the keystore).
     */
    fun save(ctx: Context, server: NetServer, password: String?, remember: Boolean): NetServer {
        val before = get(ctx, server.id)
        val known = password ?: typed[server.id]
        val sealed = when {
            server.guest || !remember -> ""
            password != null -> Passwords.lock(password)
            before?.remembers == true -> before.sealed
            known != null -> Passwords.lock(known)
            else -> ""
        }
        val s = server.copy(sealed = sealed)
        if (server.guest) typed.remove(s.id) else if (password != null) typed[s.id] = password
        // Signing in another way (or with another password): the session open now goes.
        if (before != null && (before.signIn != s.signIn || password != null)) NetClient.forget(s.id)
        synchronized(this) {
            sp(ctx).edit().putString(PREFIX + s.id, s.encode()).apply()
            cache = null
        }
        return s
    }

    fun remove(ctx: Context, id: String) {
        typed.remove(id)
        NetClient.forget(id)
        synchronized(this) {
            sp(ctx).edit().remove(PREFIX + id).apply()
            cache = null
        }
    }

    /** The password to sign in with: typed this time, or the one kept (unlocked). Null: ask for it. Blocking. */
    fun password(server: NetServer): String? {
        if (server.guest) return ""
        typed[server.id]?.let { return it }
        if (!server.remembers) return null
        return Passwords.unlock(server.sealed)?.also { typed[server.id] = it }
    }

    /** A password typed for this time only (not remembered). */
    fun useOnce(id: String, password: String) {
        typed[id] = password
    }

    /** The password didn't work: it is asked for again (the one kept stays until another is saved). */
    fun forgetTyped(id: String) {
        typed.remove(id)
    }
}
