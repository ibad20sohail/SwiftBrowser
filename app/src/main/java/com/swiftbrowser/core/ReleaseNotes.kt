package com.swiftbrowser.core

/**
 * 3.0.9: a release's notes, written on GitHub in Markdown, as plain text for the update window
 * (it showed "##" and "**" as they are). Plain, for tests.
 */
object ReleaseNotes {
    private val heading = Regex("^#{1,6}\\s*")
    private val bullet = Regex("^(\\s*)[-*]\\s+")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val gaps = Regex("\n{3,}")

    fun plain(markdown: String): String = markdown.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        line.trimEnd()
            .replace(heading, "")
            .replace(bullet, "$1• ")
            .replace(link, "$1")
            .replace("**", "")
            .replace("`", "")
    }.replace(gaps, "\n\n").trim()
}
