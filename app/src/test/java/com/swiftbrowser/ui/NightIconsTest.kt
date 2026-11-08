package com.swiftbrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3.0.5: each low-grade icon (res/drawable-night) is its light twin with only the glyph's outline changed. */
class NightIconsTest {
    private val night = File("src/main/res/drawable-night")
    private val day = File("src/main/res/drawable")

    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val path = Regex("android:pathData=\"[^\"]*\"")

    /** The file's text without its comments and with its outlines left out: its name, size, tint and fill stay. */
    private fun frame(f: File): String =
        f.readText().replace("\r\n", "\n").replace(comment, "").replace(path, "android:pathData").trim()

    @Test
    fun everyNightIconHasTheSameFrameAsItsDayTwin() {
        val icons = night.listFiles().orEmpty().filter { it.name.endsWith(".xml") }
        assertTrue("found ${icons.size}", icons.size > 100)
        for (f in icons) {
            val twin = File(day, f.name)
            assertTrue("${f.name} has no day version", twin.isFile)
            assertEquals(f.name, frame(twin), frame(f))
        }
    }

    @Test
    fun theGlyphsActuallyChanged() {
        for (f in night.listFiles().orEmpty().filter { it.name.endsWith(".xml") }) {
            val twin = File(day, f.name)
            val a = path.findAll(f.readText()).map { it.value }.toList()
            val b = path.findAll(twin.readText()).map { it.value }.toList()
            assertEquals(f.name, b.size, a.size)
            // The last outline is the glyph (a shortcut's first is its round background).
            assertTrue("${f.name}: the same outline as the day icon", a.last() != b.last())
        }
    }
}
