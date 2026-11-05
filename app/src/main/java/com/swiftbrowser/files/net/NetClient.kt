package com.swiftbrowser.files.net

import android.content.Context
import android.os.SystemClock
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.Interface
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransport
import com.rapid7.helper.smbj.share.NamedPipe
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.files.FileOps
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 3.0.4: Swift's SMB client for network folders, through smbj (SMB 2 and 3): it connects and signs in
 * once for each server and keeps that session while Swift runs (until it drops, or the server is
 * changed), and lists a server's shares (smbj-rpc), a folder, and opens files to read. Everything
 * here blocks: it runs off the main thread. Its log lines say what kind of thing went wrong, never an
 * address, a user, a share or a path.
 */
object NetClient {
    /** A share in a server's list: its name and the words the server gives it. */
    class Share(val name: String, val remark: String)

    /** A file or folder in a share. [time]: last changed (0 when not known); [size]: -1 for a folder. */
    class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long, val time: Long, val hidden: Boolean)

    /** A request the computer doesn't answer in this long fails (a file's reads are a megabyte at most). */
    private const val TIMEOUT_S = 20L

    /** A sign-in refused isn't tried again with the same password this soon (accounts lock after a few). */
    private const val REFUSED_MS = 60_000L

    @Volatile
    private var client: SMBClient? = null

    /** Asks the old way first (an SMB1 request offering SMB 2): only to tell a server that speaks only SMB1. */
    @Volatile
    private var probe: SMBClient? = null

    private class Live(val signIn: String, val password: String, val connection: Connection, val session: Session)

    private val live = ConcurrentHashMap<String, Live>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** The last refused sign-in of each server: what it was, and when. */
    private val refused = ConcurrentHashMap<String, Pair<String, Long>>()

    /** Signing out and hanging up, which talk to the computer, off the main thread. */
    private val closer = Background.single("net-close")

    private fun config(ctx: Context, oldWay: Boolean): SmbConfig = SmbConfig.builder()
        .withTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .withSocketFactory(LocalSockets(ctx))
        .withMultiProtocolNegotiate(oldWay)
        .build()

    private fun client(ctx: Context): SMBClient = client ?: synchronized(this) {
        client ?: SMBClient(config(ctx, false)).also { client = it }
    }

    private fun probe(ctx: Context): SMBClient = probe ?: synchronized(this) {
        probe ?: SMBClient(config(ctx, true)).also { probe = it }
    }

    /** A signed-in session with [server]: the one open, or a new one. Throws [NetFailure]. */
    fun session(ctx: Context, server: NetServer): Session {
        val lock = locks.getOrPut(server.id) { Any() }
        synchronized(lock) {
            val password = NetServers.password(server) ?: throw NetFailure(NetProblem.PASSWORD)
            live[server.id]?.let { l ->
                if (l.signIn == server.signIn && l.password == password && l.connection.isConnected) return l.session
                drop(server.id)
            }
            val attempt = server.signIn + "|" + password.hashCode()
            refused[server.id]?.let { (what, at) ->
                if (what == attempt && SystemClock.elapsedRealtime() - at < REFUSED_MS) throw NetFailure(if (server.guest) NetProblem.NO_GUESTS else NetProblem.LOGON)
            }
            val connection = connect(ctx, server)
            val session = try {
                signIn(connection, server, password)
            } catch (e: Exception) {
                closeQuietly(connection)
                val f = failure(e, server, "signing in")
                if (f.problem == NetProblem.LOGON || f.problem == NetProblem.NO_GUESTS || f.problem == NetProblem.ACCOUNT) {
                    refused[server.id] = attempt to SystemClock.elapsedRealtime()
                }
                throw f
            }
            refused.remove(server.id)
            live[server.id] = Live(server.signIn, password, connection, session)
            L.d("Network folders: signed in (${if (server.guest) "as a guest" else "with a user name"}, ${connection.negotiatedProtocol.dialect})")
            return session
        }
    }

    private fun connect(ctx: Context, server: NetServer): Connection {
        try {
            return client(ctx).connect(server.host, server.port)
        } catch (e: Exception) {
            if (NetErrors.of(e, server.guest) == NetProblem.SMB1) throw failure(e, server, "connecting")
            if (NetErrors.negotiationFailed(e)) {
                // It answered, but not in SMB 2 or 3 when asked straight away: asked the old way.
                try {
                    return probe(ctx).connect(server.host, server.port)
                } catch (e2: Exception) {
                    if (NetErrors.of(e2, server.guest) == NetProblem.SMB1) throw failure(e2, server, "connecting")
                }
            }
            throw failure(e, server, "connecting")
        }
    }

    private fun signIn(c: Connection, server: NetServer, password: String): Session {
        if (!server.guest) return c.authenticate(AuthenticationContext(server.user, password.toCharArray(), server.domain.ifEmpty { null }))
        // A guest: as "Guest" (Samba's guest shares, Windows with its guest account on), then with no name at all.
        return try {
            c.authenticate(AuthenticationContext.guest())
        } catch (first: Exception) {
            try {
                c.authenticate(AuthenticationContext.anonymous())
            } catch (_: Exception) {
                throw first
            }
        }
    }

    /**
     * Runs [work] with [server]'s session; once more with a new one when the old one turns out to be
     * gone (the computer restarted or slept, the Wi-Fi changed). [what]: for the log, in a few words.
     */
    private fun <T> withSession(ctx: Context, server: NetServer, what: String, work: (Session) -> T): T {
        try {
            return work(session(ctx, server))
        } catch (e: Exception) {
            if (e is NetFailure || !NetErrors.isStale(e)) throw failure(e, server, what)
            drop(server.id)
        } catch (e: LinkageError) {
            // smbj-rpc names Java classes Android 7 lacks for an answer it can't read (java.rmi) and
            // for one that doesn't come (java.nio's timeout, Android 8).
            throw failure(e, server, what)
        }
        try {
            return work(session(ctx, server))
        } catch (e: Exception) {
            throw failure(e, server, what)
        } catch (e: LinkageError) {
            throw failure(e, server, what)
        }
    }

    private fun failure(e: Throwable, server: NetServer, what: String): NetFailure {
        if (e is NetFailure) return e
        val p = when {
            // smbj-rpc's answer to a timeout is an Android 8 class (InterruptedByTimeoutException): no answer.
            e is LinkageError && e.message.orEmpty().contains("Timeout") -> NetProblem.UNREACHABLE
            e is LinkageError -> NetProblem.NO_SHARE_LIST
            else -> NetErrors.of(e, server.guest)
        }
        L.w("Network folders: $what failed (${p.name.lowercase()}, ${e.javaClass.simpleName})")
        return NetFailure(p)
    }

    /** The folders [server] shares, by name; never the hidden ones (C$, ADMIN$, IPC$, print$). */
    fun shares(ctx: Context, server: NetServer): List<Share> = withSession(ctx, server, "listing the shares") { s ->
        val ipc = s.connectShare("IPC$") as? PipeShare ?: throw NetFailure(NetProblem.NO_SHARE_LIST)
        NamedPipe(s, ipc, "srvsvc").use { pipe ->
            val transport = SMBTransport(pipe)
            transport.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2)
            ServerService(transport).shares1
                .filter { info ->
                    val name = info.netName.orEmpty()
                    // Disk shares only (not printers or pipes), and not the special or hidden ones.
                    (info.type and 0xFFFF) == STYPE_DISKTREE && (info.type and STYPE_SPECIAL) == 0 && name.isNotBlank() && !name.endsWith("$")
                }
                .map { Share(it.netName, it.remark.orEmpty().trim()) }
                .sortedWith { a, b -> FileOps.naturalCompare(a.name, b.name) }
        }
    }

    private const val STYPE_DISKTREE = 0
    private const val STYPE_SPECIAL = 0x80000000.toInt()

    private fun disk(s: Session, share: String): DiskShare = s.connectShare(share) as? DiskShare ?: throw NetFailure(NetProblem.NO_SHARE)

    /** Checks that [share] can be opened (a share typed by name). */
    fun check(ctx: Context, server: NetServer, share: String) {
        withSession(ctx, server, "opening a share") { s -> disk(s, share) }
    }

    /** What is in [path] of [share]: folders first, then files, by name as people count (2 before 10). */
    fun list(ctx: Context, server: NetServer, share: String, path: String): List<Entry> =
        withSession(ctx, server, "listing a folder") { s ->
            val folder = NetPath.clean(path)
            disk(s, share).list(folder).filter { NetPath.isEntry(it.fileName.orEmpty()) }.map { i ->
                val attrs = i.fileAttributes
                val dir = EnumWithValue.EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                val hidden = EnumWithValue.EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_HIDDEN) ||
                    EnumWithValue.EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_SYSTEM) || i.fileName.startsWith(".")
                val time = runCatching { i.lastWriteTime.toEpochMillis() }.getOrDefault(0L).coerceAtLeast(0L)
                Entry(i.fileName, NetPath.join(folder, i.fileName), dir, if (dir) -1L else i.endOfFile, time, hidden)
            }.sortedWith(order)
        }

    private val order = Comparator<Entry> { a, b ->
        if (a.isDir != b.isDir) (if (a.isDir) -1 else 1) else FileOps.naturalCompare(a.name, b.name)
    }

    /** [path] of [share] opened to read (others may still read and change it). Close it when done. */
    fun open(ctx: Context, server: NetServer, share: String, path: String): File = withSession(ctx, server, "opening a file") { s ->
        disk(s, share).openFile(
            NetPath.clean(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
    }

    /** How long an open file is. */
    fun sizeOf(f: File): Long = f.getFileInformation(FileStandardInformation::class.java).endOfFile

    /** Whether a session with [id]'s server is open now (a place of it then opens without signing in). */
    fun hasSession(id: String): Boolean = live[id]?.connection?.isConnected == true

    /** Signs out of [id]'s server and hangs up (the server was changed or removed). */
    fun forget(id: String) {
        drop(id)
        refused.remove(id)
    }

    private fun drop(id: String) {
        val l = live.remove(id) ?: return
        closer.execute {
            runCatching { l.session.close() }
            closeQuietly(l.connection)
        }
    }

    private fun closeQuietly(c: Connection) {
        // Shared by servers at the same address: it ends with the last one using it.
        runCatching { c.close() }
    }
}
