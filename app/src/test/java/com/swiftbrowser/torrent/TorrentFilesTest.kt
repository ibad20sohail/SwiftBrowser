package com.swiftbrowser.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TorrentFilesTest {
    @Test
    fun aMagnetsNameNeverLeadsOutOfItsFolder() {
        val name = TorrentFiles.safeName("../../../WhatsApp/Databases/msgstore.db.crypt14", "abc")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.startsWith("."))
        assertNotEquals("..", TorrentFiles.safeName("..", "abc"))
        assertFalse(TorrentFiles.safeName("..", "abc").contains('/'))
        assertTrue(TorrentFiles.safeName("", "0123abcd") == "0123abcd")
    }

    @Test
    fun pathsAreCheckedAgainstTheTorrentsFolder() {
        val root = "/storage/emulated/0/Download/Swift/Torrents"
        assertTrue(TorrentFiles.isWithin(root, "$root/Film (2024)/Film.mkv"))
        assertTrue(TorrentFiles.isWithin(root, root))
        assertTrue(TorrentFiles.isWithin("$root/", "$root/a"))
        assertFalse(TorrentFiles.isWithin(root, "/storage/emulated/0/WhatsApp/Databases/msgstore.db.crypt14"))
        // A folder whose name only starts the same isn't inside.
        assertFalse(TorrentFiles.isWithin(root, "${root}Other/file"))
        assertFalse(TorrentFiles.isWithin("", "/anything"))
    }

    @Test
    fun dotDotIsResolvedBeforeDeleting() {
        val dir = Files.createTempDirectory("torrents").toFile()
        try {
            val folder = File(dir, "Torrents").apply { mkdirs() }
            assertTrue(TorrentFiles.within(folder, File(folder, "Show/Episode 1.mkv")))
            assertFalse(TorrentFiles.within(folder, File(folder, "../outside.txt")))
            assertFalse(TorrentFiles.within(folder, File(folder, "Show/../../outside.txt")))
            // The name a magnet link gives, cleaned, stays inside.
            assertTrue(TorrentFiles.within(folder, File(folder, TorrentFiles.safeName("../../outside.txt", "x"))))
        } finally {
            dir.deleteRecursively()
        }
    }
}
