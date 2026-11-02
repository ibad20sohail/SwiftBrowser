package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun fileNamesLoseHiddenDirectionCharacters() {
        // "report<RLO>fdp.apk" would show as "reportkpa.pdf".
        assertEquals("reportfdp.apk", UrlUtils.sanitizeFileName("report‮fdp.apk"))
        assertEquals("a b.txt", UrlUtils.sanitizeFileName("a​ b﻿.txt"))
    }

    @Test
    fun fileNamesCantLeaveTheirFolder() {
        assertEquals("_.._etc_passwd", UrlUtils.sanitizeFileName("/../etc/passwd"))
        assertEquals("download", UrlUtils.sanitizeFileName(" .. "))
    }
}
