package com.swiftbrowser.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetAddressTest {
    private fun parse(s: String) = NetAddress.parse(s)

    @Test
    fun aPlainAddressOrNameIsTheHostOnSmbsOwnPort() {
        assertEquals(NetAddress("192.168.1.5"), parse("192.168.1.5"))
        assertEquals(NetAddress("nas"), parse("  nas  "))
        assertEquals(NetAddress("DESKTOP-4F2K9"), parse("DESKTOP-4F2K9"))
        assertEquals(NetAddress("nas.local"), parse("nas.local."))
        assertEquals(445, parse("10.0.2.2")!!.port)
    }

    @Test
    fun aPortAfterAColonIsKept() {
        assertEquals(NetAddress("10.0.2.2", 4445), parse("10.0.2.2:4445"))
        assertEquals(NetAddress("nas", 1445), parse("smb://nas:1445"))
        // Not a port, or out of range: not an address at all.
        assertNull(parse("nas:"))
        assertNull(parse("nas:0"))
        assertNull(parse("nas:65536"))
        assertNull(parse("nas:44a"))
        assertNull(parse("nas:+44"))
    }

    @Test
    fun ipv4AddressesAreCheckedAndTidied() {
        assertEquals("192.168.1.5", parse("192.168.001.005")!!.host)
        assertNull(parse("192.168.1.256"))
        assertNull(parse("192.168.1"))
        assertNull(parse("1.2.3.4.5"))
        assertTrue(NetAddress.isIpv4("0.0.0.0"))
        assertFalse(NetAddress.isIpv4("nas"))
    }

    @Test
    fun ipv6AddressesWorkWithAndWithoutBrackets() {
        assertEquals(NetAddress("fe80::1"), parse("fe80::1"))
        assertEquals(NetAddress("fe80::1", 4445), parse("[FE80::1]:4445"))
        assertEquals(NetAddress("fe80::1%wlan0"), parse("[fe80::1%wlan0]"))
        assertNull(parse("[fe80::1"))
        assertNull(parse("fe80::1::2"))
    }

    @Test
    fun smbAddressesNameAShareAndAFolderInIt() {
        val a = parse("smb://nas/Films/2024/Summer")!!
        assertEquals("nas", a.host)
        assertEquals("Films", a.share)
        assertEquals("2024/Summer", a.path)
        // Spaces as such addresses write them, and the same as Windows writes it.
        assertEquals("My Films", parse("smb://nas/My%20Films/")!!.share)
        assertEquals(NetAddress("nas", share = "My Films", path = "Holiday"), parse("\\\\nas\\My Films\\Holiday"))
        assertEquals(NetAddress("nas", share = "Music"), parse("//nas/Music"))
        assertEquals(NetAddress("nas", share = "Music"), parse("CIFS://nas/Music"))
        // A plain "+" stays one (it isn't a space in these addresses).
        assertEquals("C++", parse("smb://nas/C++")!!.share)
    }

    @Test
    fun anAddressMayNameWhoSignsIn() {
        val a = parse("smb://anna@nas/Photos")!!
        assertEquals("anna", a.user)
        assertEquals("", a.domain)
        val b = parse("smb://WORKGROUP;anna:se%40cret@192.168.1.5:4445/Photos")!!
        assertEquals("WORKGROUP", b.domain)
        assertEquals("anna", b.user)
        assertEquals("se@cret", b.password)
        assertEquals(4445, b.port)
        // The label and a log line never show them.
        assertEquals("192.168.1.5:4445/Photos", b.label())
        assertFalse(b.toString().contains("anna") || b.toString().contains("cret"))
    }

    @Test
    fun whatIsNotAnAddressIsRefused() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("https://example.com/files"))
        assertNull(parse("my nas"))
        assertNull(parse("nas!"))
        assertNull(parse("-nas"))
        assertNull(parse("smb:///Films"))
        // Never above the share, and no broken %-sequences.
        assertNull(parse("smb://nas/Films/../../etc"))
        assertNull(parse("smb://nas/Films%2"))
        assertNull(parse("smb://nas/Films%zz"))
    }

    @Test
    fun labelsShowThePortOnlyWhenItIsNotTheUsualOne() {
        assertEquals("nas", NetAddress("nas").label())
        assertEquals("nas:4445", NetAddress("nas", 4445).label())
        assertEquals("[fe80::1]:4445", NetAddress("fe80::1", 4445).label())
        assertEquals("fe80::1", NetAddress("fe80::1").label())
        assertEquals("nas/Films/2024", NetAddress("nas", share = "Films", path = "2024").label())
    }

    @Test
    fun aWindowsDomainUserIsSplit() {
        assertEquals("WORKGROUP" to "anna", NetAddress.splitUser("WORKGROUP\\anna"))
        assertEquals("" to "anna", NetAddress.splitUser(" anna "))
        // An e-mail address (a Microsoft account) stays whole.
        assertEquals("" to "anna@outlook.com", NetAddress.splitUser("anna@outlook.com"))
        assertEquals("" to "\\anna", NetAddress.splitUser("\\anna"))
    }

    @Test
    fun pathsJoinAndSplitWithSlashes() {
        assertEquals("Films", NetPath.join("", "Films"))
        assertEquals("Films/2024", NetPath.join("Films", "2024"))
        assertEquals("Films/2024", NetPath.join("/Films/", "/2024/"))
        assertEquals("Films/2024", NetPath.clean("\\Films\\\\2024\\."))
        assertEquals("Films", NetPath.parent("Films/2024"))
        assertEquals("", NetPath.parent("Films"))
        assertEquals("", NetPath.parent(""))
        assertEquals("2024", NetPath.name("Films/2024"))
        assertEquals(listOf("Films", "2024"), NetPath.segments("Films/2024/"))
        assertEquals(emptyList<String>(), NetPath.segments(""))
    }

    @Test
    fun aServersNamesCantClimbOutOfTheFolderTheyAreCopiedInto() {
        assertFalse(NetPath.isEntry("."))
        assertFalse(NetPath.isEntry(".."))
        assertTrue(NetPath.isEntry(".hidden"))
        assertEquals("_", NetPath.localName(".."))
        assertEquals("_", NetPath.localName("..."))
        assertEquals("a_b", NetPath.localName("a/b"))
        assertEquals("a_b", NetPath.localName("a\\b"))
        assertEquals("Report_ draft_.txt", NetPath.localName("Report: draft?.txt"))
        assertEquals("Holiday 2024", NetPath.localName(" Holiday 2024. "))
    }
}
