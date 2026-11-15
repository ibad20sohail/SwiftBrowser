package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkFileTest {
    @Test
    fun chromesExportKeepsEachBookmarksFolder() {
        val html = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <H1>Bookmarks</H1>
            <DL><p>
                <DT><H3 ADD_DATE="1700000000" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
                <DL><p>
                    <DT><A HREF="https://news.example/" ADD_DATE="1700000100">News</A>
                    <DT><H3>Work</H3>
                    <DL><p>
                        <DT><A HREF="https://github.example/" ADD_DATE="1700000300">GitHub &amp; friends</A>
                    </DL><p>
                </DL><p>
                <DT><A HREF="chrome://settings/">Chrome settings</A>
                <DT><A HREF="https://top.example/">Top</A>
            </DL><p>
        """.trimIndent()
        val marks = BookmarkFile.parse(html)
        assertEquals(listOf("https://news.example/", "https://github.example/", "https://top.example/"), marks.map { it.url })
        assertEquals(listOf("Bookmarks bar", "Work", ""), marks.map { it.folder })
        assertEquals("GitHub & friends", marks[1].title)
        assertEquals(1_700_000_100_000L, marks[0].added)
    }

    /** 3.0.11: "Export bookmarks" gives a file Swift's own import reads back as it was. */
    @Test
    fun anExportedFileComesBackExactly() {
        val marks = listOf(
            BookmarkFile.Mark("https://news.example/today?a=1&b=2", "News <today> & \"more\"", "", 1_700_000_100_000L),
            BookmarkFile.Mark("https://ex.example/x\"y", "Quote in the address", "Work", 1_700_000_200_000L),
            BookmarkFile.Mark("https://urdu.example/", "خبریں — اردو", "Pakistan & more", 1_700_000_300_000L),
            BookmarkFile.Mark("http://plain.example/", "", "Work", 1_700_000_400_000L),
            BookmarkFile.Mark("https://amp.example/", "&amp; written out", "", 1_700_000_500_000L),
        )
        val html = BookmarkFile.write(marks)
        // The format browsers know it by, each folder as a heading of its own.
        assertTrue(html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        assertTrue("<DT><H3>Work</H3>" in html)
        val back = BookmarkFile.parse(html)
        fun key(m: BookmarkFile.Mark) = listOf(m.url, m.title, m.folder, m.added.toString())
        assertEquals(marks.map(::key).sortedBy { it[0] }, back.map(::key).sortedBy { it[0] })
    }

    @Test
    fun datesGoInWholeSecondsAsBrowsersWriteThem() {
        val back = BookmarkFile.parse(BookmarkFile.write(listOf(BookmarkFile.Mark("https://a.example/", "A", "", 1_700_000_123_456L))))
        assertEquals(1_700_000_123_000L, back.single().added)
    }
}
