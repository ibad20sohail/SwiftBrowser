package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun markdownBecomesPlainText() {
        val md = "Swift 3.0.9 for Android 7 and later.\r\n\r\n## Lite mode\r\n- **High**: silent videos play once.\r\n  - see [the README](https://example.com/readme)\r\n\r\n\r\n\r\nUse `Settings`."
        assertEquals(
            "Swift 3.0.9 for Android 7 and later.\n\nLite mode\n• High: silent videos play once.\n  • see the README\n\nUse Settings.",
            ReleaseNotes.plain(md),
        )
    }

    @Test
    fun plainTextStaysAsItIs() {
        assertEquals("A new version of Swift.", ReleaseNotes.plain("A new version of Swift.\n"))
    }
}
