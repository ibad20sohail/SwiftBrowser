package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAccountsTest {
    @Test
    fun googleHostsAreGooglesOwn() {
        assertTrue(GoogleAccounts.isGoogleHost("google.com"))
        assertTrue(GoogleAccounts.isGoogleHost("mail.google.com"))
        assertTrue(GoogleAccounts.isGoogleHost("myaccount.google.com"))
        assertTrue(GoogleAccounts.isGoogleHost("www.google.com.pk"))
        assertTrue(GoogleAccounts.isGoogleHost("www.google.co.uk"))
        assertTrue(GoogleAccounts.isGoogleHost("WWW.GOOGLE.DE"))
        assertFalse(GoogleAccounts.isGoogleHost("evilgoogle.com"))
        assertFalse(GoogleAccounts.isGoogleHost("google.com.evil.net"))
        assertFalse(GoogleAccounts.isGoogleHost("google.github.io"))
        assertFalse(GoogleAccounts.isGoogleHost("accounts.google.com.verify-session.example.ru"))
        assertFalse(GoogleAccounts.isGoogleHost(""))
        assertFalse(GoogleAccounts.isGoogleHost(null))
    }

    @Test
    fun picturesComeOnlyFromGoogleUserContent() {
        assertTrue(GoogleAccounts.isPictureUrl("https://lh3.googleusercontent.com/a/ACg8ocK=s32-c"))
        assertFalse(GoogleAccounts.isPictureUrl("http://lh3.googleusercontent.com/a/ACg8ocK=s32-c"))
        assertFalse(GoogleAccounts.isPictureUrl("https://googleusercontent.com/a/x"))
        assertFalse(GoogleAccounts.isPictureUrl("https://lh3.googleusercontent.com.evil.net/a/x"))
        assertFalse(GoogleAccounts.isPictureUrl("https://evil.net/lh3.googleusercontent.com/a/x"))
        assertFalse(GoogleAccounts.isPictureUrl("https://lh3.googleusercontent.com@evil.net/a/x"))
        assertFalse(GoogleAccounts.isPictureUrl("https://lh3.googleusercontent.com/a/" + "x".repeat(2000)))
        assertFalse(GoogleAccounts.isPictureUrl("not a url"))
    }

    @Test
    fun biggerPictureAsksFor128Pixels() {
        assertEquals("https://lh3.googleusercontent.com/a/x=s128-c", GoogleAccounts.biggerPicture("https://lh3.googleusercontent.com/a/x=s32-c"))
        assertEquals("https://lh3.googleusercontent.com/a/x=s128", GoogleAccounts.biggerPicture("https://lh3.googleusercontent.com/a/x=s64"))
        assertEquals("https://lh3.googleusercontent.com/a/x", GoogleAccounts.biggerPicture("https://lh3.googleusercontent.com/a/x"))
    }
}
