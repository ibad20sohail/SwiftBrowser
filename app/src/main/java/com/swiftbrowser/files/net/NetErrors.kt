package com.swiftbrowser.files.net

import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** 3.0.4: what went wrong with a network folder, as the user is told it (NetErrors.text). */
enum class NetProblem {
    UNREACHABLE, UNKNOWN_HOST, PASSWORD, LOGON, ACCOUNT, NO_GUESTS, ACCESS, NO_SHARE, NOT_FOUND, BUSY, SMB1, NO_SHARE_LIST, OTHER,
}

/**
 * 3.0.4: a network folder's problem, passed on. Its message names only the kind of problem, never an
 * address, a user, a share or a path (smbj's own messages name them), so logging it shows none.
 */
class NetFailure(val problem: NetProblem) : IOException("Network folder: " + problem.name.lowercase())

/**
 * 3.0.4: network folders' problems in plain words, from smbj's and Java's exceptions and SMB's status
 * codes ([MS-ERREF]). Pure, tested on the PC.
 */
object NetErrors {
    const val LOGON_FAILURE = 0xC000006DL
    const val WRONG_PASSWORD = 0xC000006AL
    const val NO_SUCH_USER = 0xC0000064L
    const val ACCOUNT_RESTRICTION = 0xC000006EL
    const val INVALID_LOGON_HOURS = 0xC000006FL
    const val INVALID_WORKSTATION = 0xC0000070L
    const val PASSWORD_EXPIRED = 0xC0000071L
    const val ACCOUNT_DISABLED = 0xC0000072L
    const val ACCOUNT_EXPIRED = 0xC0000193L
    const val PASSWORD_MUST_CHANGE = 0xC0000224L
    const val ACCOUNT_LOCKED_OUT = 0xC0000234L
    const val LOGON_TYPE_NOT_GRANTED = 0xC000015BL
    const val ACCESS_DENIED = 0xC0000022L
    const val NETWORK_ACCESS_DENIED = 0xC00000CAL
    const val BAD_NETWORK_NAME = 0xC00000CCL
    const val NO_SUCH_FILE = 0xC000000FL
    const val OBJECT_NAME_INVALID = 0xC0000033L
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034L
    const val OBJECT_PATH_NOT_FOUND = 0xC000003AL
    const val DELETE_PENDING = 0xC0000056L
    const val NOT_FOUND = 0xC0000225L
    const val SHARING_VIOLATION = 0xC0000043L
    const val FILE_LOCK_CONFLICT = 0xC0000054L
    const val LOCK_NOT_GRANTED = 0xC0000055L
    const val NETWORK_NAME_DELETED = 0xC00000C9L
    const val USER_SESSION_DELETED = 0xC0000203L
    const val NETWORK_SESSION_EXPIRED = 0xC000035CL

    /** Windows' own "access denied", as the list of shares answers it. */
    const val WIN_ACCESS_DENIED = 5

    /**
     * The computer was reached but didn't take up SMB 2 or 3 (it hung up, or answered otherwise):
     * worth asking the old way, which tells a server that only speaks SMB1.
     */
    fun negotiationFailed(t: Throwable): Boolean {
        val chain = generateSequence(t) { it.cause }.take(12).toList()
        if (chain.any {
                it is ConnectException || it is NoRouteToHostException || it is UnknownHostException || it is PortUnreachableException ||
                    it is SMBApiException || it is NetFailure || it is SMB1NotSupportedException
            }
        ) {
            return false
        }
        return chain.any { it is com.hierynomus.protocol.transport.TransportException || it is EOFException || it is SocketException }
    }

    /** What went wrong, from [t] and its causes. [guest]: signing in without a user name. */
    fun of(t: Throwable, guest: Boolean): NetProblem {
        var e: Throwable? = t
        var depth = 0
        var dropped = false
        while (e != null && depth++ < 12) {
            when (e) {
                is NetFailure -> return e.problem
                is SMB1NotSupportedException -> return NetProblem.SMB1
                is SMB2GuestSigningRequiredException -> return NetProblem.NO_GUESTS
                is SMBApiException -> return ofStatus(e.statusCode, guest)
                // smbj-rpc's own (opening the list of shares, and the list's answer).
                is com.rapid7.helper.smbj.io.SMB2Exception -> return ofStatus(e.statusCode, guest)
                is com.rapid7.client.dcerpc.RPCException -> return if (e.returnValue == WIN_ACCESS_DENIED) NetProblem.ACCESS else NetProblem.NO_SHARE_LIST
                is UnknownHostException -> return NetProblem.UNKNOWN_HOST
                is ConnectException, is NoRouteToHostException, is PortUnreachableException,
                is SocketTimeoutException, is TimeoutException,
                -> return NetProblem.UNREACHABLE
                // A connection that dropped: the computer went to sleep, or the Wi-Fi changed.
                is SocketException, is EOFException -> dropped = true
            }
            e = e.cause
        }
        return if (dropped) NetProblem.UNREACHABLE else NetProblem.OTHER
    }

    /** What an SMB status code means for the user. */
    fun ofStatus(status: Long, guest: Boolean): NetProblem = when (status) {
        LOGON_FAILURE, WRONG_PASSWORD, NO_SUCH_USER -> if (guest) NetProblem.NO_GUESTS else NetProblem.LOGON
        ACCOUNT_RESTRICTION, INVALID_LOGON_HOURS, INVALID_WORKSTATION, PASSWORD_EXPIRED, ACCOUNT_DISABLED,
        ACCOUNT_EXPIRED, PASSWORD_MUST_CHANGE, ACCOUNT_LOCKED_OUT, LOGON_TYPE_NOT_GRANTED,
        -> if (guest) NetProblem.NO_GUESTS else NetProblem.ACCOUNT
        ACCESS_DENIED, NETWORK_ACCESS_DENIED -> NetProblem.ACCESS
        BAD_NETWORK_NAME -> NetProblem.NO_SHARE
        NO_SUCH_FILE, OBJECT_NAME_INVALID, OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND, DELETE_PENDING, NOT_FOUND -> NetProblem.NOT_FOUND
        SHARING_VIOLATION, FILE_LOCK_CONFLICT, LOCK_NOT_GRANTED -> NetProblem.BUSY
        NETWORK_NAME_DELETED, USER_SESSION_DELETED, NETWORK_SESSION_EXPIRED -> NetProblem.UNREACHABLE
        else -> NetProblem.OTHER
    }

    /**
     * The session or connection behind [t] is gone (the computer restarted or slept, the Wi-Fi
     * changed): signing in again may well work, so it is tried once before saying anything.
     */
    fun isStale(t: Throwable): Boolean {
        val chain = generateSequence(t) { it.cause }.take(12).toList()
        // Another try gets the same answer (SMB1's is a transport problem too: looked for first).
        if (chain.any { it is NetFailure || it is SMB1NotSupportedException || it is UnknownHostException || it is ConnectException }) return false
        (chain.firstOrNull { it is SMBApiException } as SMBApiException?)?.let { e ->
            return e.statusCode == NETWORK_NAME_DELETED || e.statusCode == USER_SESSION_DELETED || e.statusCode == NETWORK_SESSION_EXPIRED
        }
        return chain.any { it is com.hierynomus.protocol.transport.TransportException || it is SocketException || it is EOFException }
    }

    /**
     * The words for [p]. [address]: where the computer is, as typed ("192.168.1.5"); [name]: what it
     * is called in the list; [share]: the share it was about, if any.
     */
    fun text(p: NetProblem, address: String, name: String = address, share: String = ""): String = when (p) {
        NetProblem.UNREACHABLE -> "Can't reach $address. Is it on the same Wi-Fi?"
        NetProblem.UNKNOWN_HOST -> "Can't find “$address” on this network. Try its IP address instead (like 192.168.1.5)."
        NetProblem.PASSWORD -> "Type the password for $name"
        NetProblem.LOGON -> "Wrong user name or password"
        NetProblem.ACCOUNT -> "$name refused this account. It may be locked or turned off, or its password may have expired."
        NetProblem.NO_GUESTS -> "$name doesn't let guests in. Edit it and add a user name and password."
        NetProblem.ACCESS -> "Swift isn't allowed to open this share"
        NetProblem.NO_SHARE -> if (share.isNotEmpty()) "There's no share called “$share” on $name." else "That share isn't on $name any more."
        NetProblem.NOT_FOUND -> "This isn't there any more. It may have been moved or deleted on $name."
        NetProblem.BUSY -> "Another program on $name is using this file. Try again once it's closed."
        NetProblem.SMB1 -> "This server only speaks the old SMB1, which isn't safe."
        NetProblem.NO_SHARE_LIST -> "$name didn't say which folders it shares. You can open one by its name."
        NetProblem.OTHER -> "Something went wrong with $name. Try again in a moment."
    }
}
