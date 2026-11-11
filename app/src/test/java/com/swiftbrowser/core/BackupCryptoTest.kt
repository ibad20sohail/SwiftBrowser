package com.swiftbrowser.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupCryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theKeyIsStandardPbkdf2HmacSha256() {
        // RFC 7914, section 11.
        assertArrayEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
            BackupCrypto.keyFrom("passwd", "salt".toByteArray(), 1, 64),
        )
        assertArrayEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
            BackupCrypto.keyFrom("Password", "NaCl".toByteArray(), 80_000, 64),
        )
    }

    @Test
    fun onlyTheRightPasswordOpensIt() {
        val salt = BackupCrypto.salt()
        val key = BackupCrypto.keyFrom("my backup", salt, 1000)
        val header = "{\"device\":\"Moto E4\"}".toByteArray()
        val sealed = BackupCrypto.seal(key, "bookmarks and passwords".toByteArray(), header)
        assertEquals("bookmarks and passwords", String(BackupCrypto.open(key, sealed, header)!!))
        // A wrong password, or a header changed (another hint, another phone's name): nothing.
        assertNull(BackupCrypto.open(BackupCrypto.keyFrom("my backuP", salt, 1000), sealed, header))
        assertNull(BackupCrypto.open(key, sealed, "{\"device\":\"other\"}".toByteArray()))
        assertNull(BackupCrypto.open(key, sealed.copyOf(5), header))
    }

    @Test
    fun zippedAndNeverReadPastItsLimit() {
        val text = "history ".repeat(10_000).toByteArray()
        val zipped = BackupCrypto.gzip(text)
        assertArrayEquals(text, BackupCrypto.gunzip(zipped, text.size))
        assertNull(BackupCrypto.gunzip(zipped, text.size - 1))
        assertNull(BackupCrypto.gunzip("not zipped".toByteArray(), 1000))
    }

    @Test
    fun signInsMoveWithTheirProfile() {
        // As GeckoView names Swift's profile 1 (seen in the engine's storage folders).
        assertEquals("gvctx70726f66696c652d31", BackupCrypto.contextOf(1))
        val oa = "^geckoViewUserContextId=gvctx70726f66696c652d31&partitionKey=%28https%2Cexample.com%29"
        // Profile 1 of the old phone is profile 3 here.
        assertEquals(
            "^geckoViewUserContextId=gvctx70726f66696c652d33&partitionKey=%28https%2Cexample.com%29",
            BackupCrypto.moveProfile(oa, mapOf(1L to 3L)),
        )
        // A profile the backup didn't bring: left out. No profile at all: as it is.
        assertNull(BackupCrypto.moveProfile(oa, mapOf(2L to 2L)))
        assertEquals("", BackupCrypto.moveProfile("", mapOf(1L to 3L)))
    }
}
