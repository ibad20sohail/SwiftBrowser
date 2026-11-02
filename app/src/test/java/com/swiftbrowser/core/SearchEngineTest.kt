package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.0: only the engines' own results pages put search words (and a logo) in the address bar. */
class SearchEngineTest {
    @Test
    fun googleIsOnlyGoogle() {
        for (h in listOf(
            "google.com", "www.google.com", "google.de", "www.google.co.uk", "google.com.pk", "www.google.com.pk",
            "WWW.GOOGLE.COM", "www.google.com.", "google.cat",
        )) {
            assertEquals(h, SearchEngine.GOOGLE, SearchEngine.ofHost(h))
        }
        for (h in listOf(
            "google.github.io", "google.evil.com", "accounts.google.com.evil.net", "mygoogle.com", "google.com.evil",
            "evil.google.co", "images.google.com", "google.co.evil.com", "googlexcom", null,
        )) {
            assertNull(h, SearchEngine.ofHost(h))
        }
    }

    @Test
    fun otherEnginesAndTheirSubdomains() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.ofHost("duckduckgo.com"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.ofHost("html.duckduckgo.com"))
        assertEquals(SearchEngine.BING, SearchEngine.ofHost("www.bing.com"))
        assertEquals(SearchEngine.STARTPAGE, SearchEngine.ofHost("eu.startpage.com"))
        assertNull(SearchEngine.ofHost("notstartpage.com"))
        assertNull(SearchEngine.ofHost("duckduckgo.com.evil.net"))
        assertNull(SearchEngine.ofHost("fakebing.com"))
    }

    @Test
    fun resultsPagesOnly() {
        assertEquals(listOf("q"), SearchEngine.wordsParams("www.google.co.uk", "/search"))
        assertNull(SearchEngine.wordsParams("www.google.com", "/maps"))
        // The address that showed "accounts.google.com" with Google's logo (2.9).
        assertNull(SearchEngine.wordsParams("google.github.io", "/search"))
        assertEquals(listOf("q"), SearchEngine.wordsParams("duckduckgo.com", "/"))
        assertNull(SearchEngine.wordsParams("duckduckgo.com", "/about"))
        assertEquals(listOf("q"), SearchEngine.wordsParams("www.bing.com", "/search"))
        assertEquals(listOf("query", "q"), SearchEngine.wordsParams("www.startpage.com", "/sp/search"))
        assertNull(SearchEngine.wordsParams("evil-startpage.com", "/sp/search"))
    }
}
