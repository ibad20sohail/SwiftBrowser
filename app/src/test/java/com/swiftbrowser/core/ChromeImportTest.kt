package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeImportTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun visitsFoldIntoOnePageEach() {
        val t = ChromeImport.HistoryTally(now)
        t.add("https://a.test/", "Old title", (now - 5 * day) * 1000)
        t.add("https://a.test/", "New title", (now - day) * 1000)
        t.add("https://a.test/", "", (now - 2 * day) * 1000)
        t.add("https://b.test/", "B", (now - 3 * day) * 1000)
        val pages = t.pages()
        assertEquals(listOf("https://a.test/", "https://b.test/"), pages.map { it.url })
        assertEquals(3, pages[0].visits)
        assertEquals(now - day, pages[0].last)
        assertEquals("New title", pages[0].title)
    }

    @Test
    fun onlyWebPagesOfTheLastYearComeIn() {
        val t = ChromeImport.HistoryTally(now)
        t.add("chrome://settings/", "Settings", now * 1000)
        t.add("https://old.test/", "Old", (now - 400 * day) * 1000)
        t.add("https://future.test/", "Future", (now + 30 * day) * 1000)
        t.add("https://ok.test/", "Ok", (now - 10 * day) * 1000)
        t.add("https://zero.test/", "Zero", 0)
        assertEquals(listOf("https://ok.test/"), t.pages().map { it.url })
    }
}
