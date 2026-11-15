package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 3.0: only the engines' own results pages put search words (and a logo) in the address bar. */
class SearchEngineTest {
    /** 3.0.11: a held picture's "Search this picture": Bing's picture search with Bing, Google Lens otherwise. */
    @Test
    fun pictureSearch() {
        val pic = "https://example.com/a b/cat.jpg?x=1&y=2"
        val encoded = "https%3A%2F%2Fexample.com%2Fa+b%2Fcat.jpg%3Fx%3D1%26y%3D2"
        assertEquals("https://lens.google.com/uploadbyurl?url=$encoded", SearchEngine.GOOGLE.pictureSearchUrl(pic))
        assertEquals("https://lens.google.com/uploadbyurl?url=$encoded", SearchEngine.DUCKDUCKGO.pictureSearchUrl(pic))
        assertEquals("https://lens.google.com/uploadbyurl?url=$encoded", SearchEngine.STARTPAGE.pictureSearchUrl(pic))
        assertEquals("https://lens.google.com/uploadbyurl?url=$encoded", SearchEngine.CUSTOM.pictureSearchUrl(pic))
        assertEquals("https://www.bing.com/images/search?view=detailv2&iss=sbi&q=imgurl:$encoded", SearchEngine.BING.pictureSearchUrl(pic))
    }

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
