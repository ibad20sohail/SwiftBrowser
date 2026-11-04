package com.swiftbrowser.media

import org.junit.Assert.assertEquals
import org.junit.Test

class UnfinishedTest {
    private fun item(url: String, updated: Long) = Unfinished.Item(url, url.substringAfterLast('/'), 0.5f, "5 min left", true, updated, null)

    @Test
    fun theNewestComeFirstAndEightAtMost() {
        val items = (1..12).map { item("https://example.com/watch/$it", it * 1000L) }.shuffled(java.util.Random(3))
        val shown = Unfinished.newest(items, emptySet())
        assertEquals((12 downTo 5).map { "https://example.com/watch/$it" }, shown.map { it.url })
    }

    @Test
    fun oneTakenOffStaysOutOfSightUntilItsUndoIsGone() {
        val items = listOf(item("file:///sdcard/Movies/a.mp4", 3), item("file:///sdcard/Movies/b.mp4", 2), item("file:///sdcard/Music/talk.mp3", 1))
        val shown = Unfinished.newest(items, setOf("file:///sdcard/Movies/a.mp4"))
        assertEquals(listOf("file:///sdcard/Movies/b.mp4", "file:///sdcard/Music/talk.mp3"), shown.map { it.url })
    }

    @Test
    fun nothingLeftHalfWayShowsNothing() {
        assertEquals(0, Unfinished.newest(emptyList(), emptySet()).size)
    }
}
