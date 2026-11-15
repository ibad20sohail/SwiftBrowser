package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordsCsvTest {
    @Test
    fun googlesExportComesInWithItsQuotesAndLineBreaks() {
        val text = "﻿name,url,username,password,note\r\n" +
            "example.com,https://example.com/login,alice,s3cret,\r\n" +
            "accounts.test,https://accounts.test/signin?x=1,\"bob, the builder\",\"pa\"\"ss,word\",\"line one\nline two\"\r\n" +
            "sub,https://m.example.org:8443/a/b,carol,c4r0l!,note\r\n"
        val r = PasswordsCsv.parse(text)!!
        assertEquals(3, r.rows.size)
        assertEquals(0, r.skipped)
        assertEquals("https://example.com", r.rows[0].origin)
        assertEquals("alice", r.rows[0].username)
        assertEquals("s3cret", r.rows[0].password)
        assertEquals("bob, the builder", r.rows[1].username)
        assertEquals("pa\"ss,word", r.rows[1].password)
        assertEquals("https://m.example.org:8443", r.rows[2].origin)
    }

    @Test
    fun appPasswordsAndEmptyOnesAreLeftOut() {
        val text = "name,url,username,password,note\n" +
            "app,android://AbC123@com.whatsapp/,+92300,app-pass,\n" +
            "empty,https://empty.test/,nobody,,\n" +
            "\n" +
            "ok,http://plain.test:80/x,dave,pw,\n"
        val r = PasswordsCsv.parse(text)!!
        assertEquals(1, r.rows.size)
        assertEquals(2, r.skipped)
        assertEquals("http://plain.test", r.rows[0].origin)
    }

    @Test
    fun firefoxsColumnsInTheirOwnOrderWork() {
        val text = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\"\n" +
            "\"https://www.site.test\",\"erin\",\"pass\",,\"https://www.site.test\",\"{1}\"\n"
        val r = PasswordsCsv.parse(text)!!
        assertEquals(1, r.rows.size)
        assertEquals("https://www.site.test", r.rows[0].origin)
        assertEquals("erin", r.rows[0].username)
    }

    /** 3.0.11: "Export passwords" writes Google's columns, and what it writes comes back exactly. */
    @Test
    fun anExportedFileComesBackExactly() {
        val rows = listOf(
            PasswordsCsv.Row("https://example.com", "alice", "s3cret"),
            PasswordsCsv.Row("https://accounts.test", "bob, the builder", "pa\"ss,word"),
            PasswordsCsv.Row("https://m.example.org:8443", "carol", "line one\nline two"),
            PasswordsCsv.Row("http://plain.test", "", "\"starts with a quote"),
            PasswordsCsv.Row("https://www.urdu.test", "علی", " spaces around "),
        )
        val text = PasswordsCsv.write(rows)
        assertEquals("name,url,username,password,note", text.lineSequence().first())
        assertTrue(text.contains("\nexample.com,https://example.com/,alice,s3cret,\n"))
        // The site's name without "www.", as Google writes it.
        assertTrue(text.contains("\nurdu.test,https://www.urdu.test/,"))
        val back = PasswordsCsv.parse(text)!!
        assertEquals(0, back.skipped)
        assertEquals(rows.map { Triple(it.origin, it.username, it.password) }, back.rows.map { Triple(it.origin, it.username, it.password) })
    }

    @Test
    fun aFileThatIsntPasswordsIsTurnedAway() {
        assertNull(PasswordsCsv.parse("name,price\napple,3\n"))
        assertNull(PasswordsCsv.parse(""))
    }

    @Test
    fun originsAreWrittenAsSwiftKeepsThem() {
        assertEquals("https://example.com", PasswordsCsv.originOf("HTTPS://Example.COM/path?q=1"))
        assertEquals("https://example.com", PasswordsCsv.originOf("https://example.com:443/"))
        assertEquals("http://example.com:8080", PasswordsCsv.originOf("http://example.com:8080"))
        assertNull(PasswordsCsv.originOf("android://hash@com.app/"))
        assertNull(PasswordsCsv.originOf("not a url"))
    }
}
