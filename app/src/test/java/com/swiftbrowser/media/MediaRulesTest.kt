package com.swiftbrowser.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRulesTest {
    @Test
    fun webAddressesOfVideosAreKept() {
        assertTrue(MediaRules.acceptable("https://cdn.example.com/v/movie.mp4"))
        assertTrue(MediaRules.acceptable("http://example.com:8080/live/master.m3u8?token=abc"))
        assertTrue(MediaRules.acceptable("HTTPS://Example.com/a.mp4"))
    }

    @Test
    fun otherSchemesFromAPageAreDropped() {
        for (url in listOf(
            "javascript:alert(1)", "data:video/mp4;base64,AAAA", "blob:https://example.com/123",
            "file:///sdcard/a.mp4", "content://media/1", "intent://x#Intent;end", "ftp://example.com/a.mp4",
        )) assertFalse(url, MediaRules.acceptable(url))
    }

    @Test
    fun brokenOrHugeAddressesAreDropped() {
        assertFalse(MediaRules.acceptable(""))
        assertFalse(MediaRules.acceptable("https:///no-host.mp4"))
        assertFalse(MediaRules.acceptable("https://:443/no-host.mp4"))
        assertFalse(MediaRules.acceptable("https://example.com/a b.mp4"))
        assertFalse(MediaRules.acceptable("https://example.com/a.mp4\nX-Header: 1"))
        assertFalse(MediaRules.acceptable("https://example.com/" + "a".repeat(MediaRules.MAX_URL)))
    }
}
