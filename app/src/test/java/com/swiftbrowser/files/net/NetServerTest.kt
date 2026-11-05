package com.swiftbrowser.files.net

import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetServerTest {
    private val sealed = "q83vEjRWeJA=:3q2+7w8/AA==" // what the keystore's lock gives: base64 "iv:data"

    @Test
    fun aServerComesBackAsItWasSaved() {
        val s = NetServer(
            id = "5d0c", name = "Anna's PC & NAS = 100% fun", host = "192.168.1.5", port = 4445, share = "Films",
            path = "2024/Summer", user = "anna", domain = "WORKGROUP", sealed = sealed, added = 1_730_000_000_000L,
        )
        val back = NetServer.decode(s.encode())
        assertEquals(s, back)
        // The locked password is kept exactly as the keystore gave it.
        assertEquals(sealed, back!!.sealed)
        assertTrue(back.remembers)
        assertFalse(back.guest)
    }

    @Test
    fun aGuestWithoutAPasswordComesBackToo() {
        val s = NetServer(id = "g1", name = "", host = "nas")
        val back = NetServer.decode(s.encode())!!
        assertEquals(s, back)
        assertTrue(back.guest)
        assertFalse(back.remembers)
        assertEquals("nas", back.title)
        assertEquals("nas", back.address())
        // Unicode names survive.
        assertEquals("Ünïcødé ☃", NetServer.decode(s.copy(name = "Ünïcødé ☃").encode())!!.name)
    }

    @Test
    fun damagedOrNewerRecordsAreNotHalfRead() {
        assertNull(NetServer.decode(""))
        assertNull(NetServer.decode("garbage"))
        assertNull(NetServer.decode("v=2&id=a&host=nas"))
        assertNull(NetServer.decode("v=1&id=a&host="))
        assertNull(NetServer.decode("v=1&id=&host=nas"))
        assertNull(NetServer.decode("v=1&id=a&host=nas&port=70000"))
        assertNull(NetServer.decode("v=1&id=a&host=nas&name=%zz"))
    }

    @Test
    fun logLinesNeverShowWhereItIsOrWhoSignsIn() {
        val s = NetServer(id = "x1", name = "Home", host = "192.168.1.5", user = "anna", sealed = sealed)
        assertEquals("NetServer(x1)", s.toString())
        assertEquals("Network folder: logon", NetFailure(NetProblem.LOGON).message)
    }

    @Test
    fun signingInAgainIsNeededWhenWhoOrWhereChanges() {
        val s = NetServer(id = "x1", name = "Home", host = "NAS", user = "anna")
        assertEquals(s.signIn, s.copy(name = "Other name", sealed = sealed).signIn)
        assertFalse(s.signIn == s.copy(user = "ben").signIn)
        assertFalse(s.signIn == s.copy(port = 4445).signIn)
        assertEquals(s.signIn, s.copy(host = "nas").signIn)
    }

    private fun api(status: Long) = SMBApiException(status, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "Session setup failed for anna@\\\\192.168.1.5", null)

    @Test
    fun signInAnswersBecomePlainWords() {
        assertEquals(NetProblem.LOGON, NetErrors.of(api(NetErrors.LOGON_FAILURE), guest = false))
        assertEquals(NetProblem.LOGON, NetErrors.of(api(NetErrors.WRONG_PASSWORD), guest = false))
        // Without a user name, a refusal means guests aren't let in.
        assertEquals(NetProblem.NO_GUESTS, NetErrors.of(api(NetErrors.LOGON_FAILURE), guest = true))
        assertEquals(NetProblem.NO_GUESTS, NetErrors.of(SMB2GuestSigningRequiredException(), guest = true))
        assertEquals(NetProblem.ACCOUNT, NetErrors.of(api(NetErrors.ACCOUNT_LOCKED_OUT), guest = false))
        assertEquals(NetProblem.ACCESS, NetErrors.of(api(NetErrors.ACCESS_DENIED), guest = false))
        assertEquals(NetProblem.NO_SHARE, NetErrors.of(api(NetErrors.BAD_NETWORK_NAME), guest = false))
        assertEquals(NetProblem.NOT_FOUND, NetErrors.of(api(NetErrors.OBJECT_NAME_NOT_FOUND), guest = false))
        assertEquals(NetProblem.BUSY, NetErrors.of(api(NetErrors.SHARING_VIOLATION), guest = false))
        assertEquals(NetProblem.OTHER, NetErrors.of(api(0xC0000001L), guest = false))
    }

    @Test
    fun networkProblemsAreFoundThroughWrappedCauses() {
        assertEquals(NetProblem.UNREACHABLE, NetErrors.of(ConnectException("failed to connect to /192.168.1.5"), false))
        assertEquals(NetProblem.UNREACHABLE, NetErrors.of(SocketTimeoutException(), false))
        assertEquals(NetProblem.UNKNOWN_HOST, NetErrors.of(UnknownHostException("nas"), false))
        assertEquals(NetProblem.SMB1, NetErrors.of(SMBRuntimeException(TransportException(SMB1NotSupportedException())), false))
        assertEquals(NetProblem.ACCESS, NetErrors.of(SMBRuntimeException(IOException(api(NetErrors.ACCESS_DENIED))), false))
        // A connection that dropped half-way.
        assertEquals(NetProblem.UNREACHABLE, NetErrors.of(TransportException(EOFException()), false))
        assertEquals(NetProblem.OTHER, NetErrors.of(IllegalStateException("odd"), false))
        assertEquals(NetProblem.SMB1, NetErrors.of(NetFailure(NetProblem.SMB1), false))
    }

    @Test
    fun onlyAGoneSessionIsTriedAgain() {
        assertTrue(NetErrors.isStale(api(NetErrors.NETWORK_SESSION_EXPIRED)))
        assertTrue(NetErrors.isStale(SMBRuntimeException(TransportException(EOFException()))))
        assertFalse(NetErrors.isStale(api(NetErrors.LOGON_FAILURE)))
        assertFalse(NetErrors.isStale(TransportException(SMB1NotSupportedException())))
        assertFalse(NetErrors.isStale(ConnectException()))
        assertFalse(NetErrors.isStale(NetFailure(NetProblem.UNREACHABLE)))
    }

    @Test
    fun onlyAComputerThatAnsweredButHungUpIsAskedTheOldWay() {
        // Reached, but it hung up on SMB 2 (an SMB1-only server, say): asked the old way to tell.
        assertTrue(NetErrors.negotiationFailed(TransportException(EOFException())))
        assertTrue(NetErrors.negotiationFailed(SMBRuntimeException(TransportException("closed"))))
        // Not reached, a clear answer, or SMB1 known already: nothing more to ask.
        assertFalse(NetErrors.negotiationFailed(ConnectException()))
        assertFalse(NetErrors.negotiationFailed(UnknownHostException("nas")))
        assertFalse(NetErrors.negotiationFailed(TransportException(SMB1NotSupportedException())))
        assertFalse(NetErrors.negotiationFailed(api(NetErrors.ACCESS_DENIED)))
        assertFalse(NetErrors.negotiationFailed(IllegalStateException()))
    }

    @Test
    fun theMessagesSayWhatToDo() {
        assertEquals("Can't reach 192.168.1.5. Is it on the same Wi-Fi?", NetErrors.text(NetProblem.UNREACHABLE, "192.168.1.5"))
        assertEquals("Wrong user name or password", NetErrors.text(NetProblem.LOGON, "nas"))
        assertEquals("Swift isn't allowed to open this share", NetErrors.text(NetProblem.ACCESS, "nas"))
        assertEquals("This server only speaks the old SMB1, which isn't safe.", NetErrors.text(NetProblem.SMB1, "nas"))
        assertEquals("There's no share called “Films” on Home.", NetErrors.text(NetProblem.NO_SHARE, "nas", "Home", "Films"))
        assertEquals("Home doesn't let guests in. Edit it and add a user name and password.", NetErrors.text(NetProblem.NO_GUESTS, "nas", "Home"))
        // Every problem has words of its own.
        for (p in NetProblem.entries) assertTrue(NetErrors.text(p, "nas").isNotBlank())
    }
}
