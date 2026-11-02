package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0: what the address bar takes for an address, and how it shows a site's name. */
class AddressesTest {
    @Test
    fun typedTextKeepsItsSchemes() {
        assertEquals("javascript:alert(1)", UrlUtils.addressOf("javascript:alert(1)"))
        assertEquals("about:config", UrlUtils.addressOf("about:config"))
        assertEquals("https://example.com", UrlUtils.addressOf("example.com"))
        assertEquals("http://192.168.1.1", UrlUtils.addressOf("192.168.1.1"))
        assertEquals("https://example.com:8080/a", UrlUtils.addressOf("example.com:8080/a"))
        assertNull(UrlUtils.addressOf("how to cook rice"))
    }

    @Test
    fun scannedPastedOrSpokenTextOnlyOpensWebPages() {
        for (t in listOf(
            "javascript:alert(document.cookie)", "data:text/html,<h1>hi</h1>", "file:///data/data/com.swiftbrowser.app/x",
            "view-source:https://example.com", "resource://android/assets/", "moz-extension://abc/page.html",
            "about:config", "blob:https://example.com/1", "tel:123456", "intent://x#Intent;end",
        )) {
            assertNull(t, UrlUtils.addressOf(t, trusted = false))
        }
        assertEquals("https://example.com/a", UrlUtils.addressOf("https://example.com/a", trusted = false))
        assertEquals("http://example.com", UrlUtils.addressOf("http://example.com", trusted = false))
        assertEquals("https://example.com", UrlUtils.addressOf("example.com", trusted = false))
        assertEquals("http://localhost:8080", UrlUtils.addressOf("localhost:8080", trusted = false))
    }

    @Test
    fun wordsThatReadLikeAnAddress() {
        for (t in listOf(
            "accounts.google.com", "paypal.com/signin", "https://evil.example", "javascript:void(0)", "192.168.0.1",
            "localhost", "user@bank.com", "paypal.com.", " paypal.com ", "pay​pal.com", "paypal。com",
            "аpple.com", "пример.рф", "xn--pple-43d.com", "see https://x.com", "paypal.com ",
        )) {
            assertTrue(t, UrlUtils.looksLikeAddress(t))
        }
        for (t in listOf("how to cook rice", "3.14", "e.g.", "U.S.A.", "c++", "", "   ", "paypal.com login", "pay pal")) {
            assertFalse(t, UrlUtils.looksLikeAddress(t))
        }
    }

    @Test
    fun sitesOfSharedHostingAreTheirOwn() {
        assertEquals("alice.github.io", UrlUtils.siteOfHost("alice.github.io"))
        assertEquals("alice.github.io", UrlUtils.siteOfHost("www.alice.github.io"))
        assertEquals("github.io", UrlUtils.siteOfHost("github.io"))
        assertEquals("github.io", UrlUtils.siteOfHost("www.github.io"))
        assertEquals("shop.netlify.app", UrlUtils.siteOfHost("a.b.shop.netlify.app"))
        assertEquals("bucket.s3.amazonaws.com", UrlUtils.siteOfHost("bucket.s3.amazonaws.com"))
        assertEquals("amazonaws.com", UrlUtils.siteOfHost("s3.amazonaws.com"))
        assertEquals("d111.cloudfront.net", UrlUtils.siteOfHost("D111.CloudFront.net."))
        assertEquals("tumblr.com", UrlUtils.siteOfHost("www.tumblr.com"))
        assertEquals("blog.wordpress.com", UrlUtils.siteOfHost("blog.wordpress.com"))
    }

    @Test
    fun sitesOfOtherHosts() {
        assertEquals("google.com", UrlUtils.siteOfHost("accounts.google.com"))
        assertEquals("bbc.co.uk", UrlUtils.siteOfHost("www.bbc.co.uk"))
        assertEquals("daraz.com.pk", UrlUtils.siteOfHost("www.daraz.com.pk"))
        assertEquals("example.net", UrlUtils.siteOfHost("accounts.google.com.verify-session.example.net"))
        assertEquals("192.168.1.1", UrlUtils.siteOfHost("192.168.1.1"))
        assertEquals("localhost", UrlUtils.siteOfHost("localhost"))
        // Not a shared platform, only a name ending like one.
        assertEquals("evilgithub.io", UrlUtils.siteOfHost("evilgithub.io"))
    }

    @Test
    fun plainHostAndPath() {
        assertEquals("www.example.com", UrlUtils.plainHost("https://user:pw@WWW.Example.com.:8443/a/b?c=d#e"))
        assertEquals("/a/b", UrlUtils.plainPath("https://user:pw@WWW.Example.com.:8443/a/b?c=d#e"))
        assertEquals("/", UrlUtils.plainPath("https://example.com"))
        assertEquals("/", UrlUtils.plainPath("https://example.com?q=/x"))
        assertEquals("[::1]", UrlUtils.plainHost("http://[::1]:8080/"))
        assertNull(UrlUtils.plainHost("about:blank"))
        assertNull(UrlUtils.plainHost(null))
    }

    @Test
    fun longSiteNamesShowTheirEnd() {
        val fits20 = { s: String -> s.length <= 20 }
        assertEquals("example.com", UrlUtils.hostEnd("example.com", fits20))
        val shown = UrlUtils.hostEnd("accounts.google.com.verify-session.example.net", fits20)
        assertTrue(shown, shown.startsWith("…") && shown.endsWith("example.net") && shown.length <= 20)
        // A scrap of a part ("…om.") is left out.
        assertEquals("…verify-session.net", UrlUtils.hostEnd("accounts.google.com.verify-session.net", fits20))
        assertEquals("…example.net", UrlUtils.hostEnd("aaaaaaaaaa.bbbbb.example.net") { it.length <= 14 })
        // A whole short part stays.
        assertEquals("…cd.example.net", UrlUtils.hostEnd("aaaaaaaaaa.bbbbb.cd.example.net") { it.length <= 16 })
        // The port, at the end, stays too.
        assertTrue(UrlUtils.hostEnd("login.bank.example.verify.example.org:8080", fits20).endsWith("example.org:8080"))
    }

    @Test
    fun linksToFilesOnThePhone() {
        assertTrue(UrlUtils.isLocalFile("content"))
        assertTrue(UrlUtils.isLocalFile("FILE"))
        assertFalse(UrlUtils.isLocalFile("https"))
        assertFalse(UrlUtils.isLocalFile(null))
    }
}
