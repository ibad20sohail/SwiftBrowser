package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivePathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun namesFromAnArchiveBecomeSafeRelativePaths() {
        assertEquals("a/b.txt", Archives.clean("a/b.txt"))
        assertEquals("a/b.txt", Archives.clean("a\\b.txt"))
        assertEquals("a/b.txt", Archives.clean("./a//b.txt"))
        // A leading slash or drive goes: the entry stays inside the chosen folder.
        assertEquals("etc/passwd", Archives.clean("/etc/passwd"))
        assertEquals("Windows/x.dll", Archives.clean("C:\\Windows\\x.dll"))
        assertEquals("", Archives.clean("/"))
    }

    @Test
    fun namesThatClimbOutAreRefused() {
        assertNull(Archives.clean("../evil.sh"))
        assertNull(Archives.clean("a/../../evil.sh"))
        assertNull(Archives.clean("..\\..\\evil.sh"))
        assertNull(Archives.clean("a/b/.."))
    }

    @Test
    fun entriesLandInsideTheFolderOrNowhere() {
        val dest = tmp.newFolder("out")
        val destPath = dest.canonicalPath
        val inside = Archives.targetIn(dest, destPath, "a/b.txt")
        assertNotNull(inside)
        assertTrue(inside!!.canonicalPath.startsWith(destPath))
        // Even if a name got past clean(), the canonical check stops it.
        assertNull(Archives.targetIn(dest, destPath, "a/../../evil.sh"))
        assertNull(Archives.targetIn(dest, destPath, ".."))
        // Characters a memory card can't hold become "_", still inside.
        assertEquals("a_b.txt", Archives.targetIn(dest, destPath, "a:b.txt")!!.name)
        // A folder next to it whose name starts the same isn't inside it.
        assertNull(Archives.targetIn(dest, destPath, "../out2/x.txt"))
    }
}
