package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun namesKeepALeadingDot() {
        assertEquals(".nomedia", FileOps.cleanName(".nomedia"))
        assertEquals(".hidden folder", FileOps.cleanName("  .hidden folder  "))
        // Dots and spaces at the end go (memory cards drop them).
        assertEquals("photo", FileOps.cleanName("photo. . "))
        assertEquals("", FileOps.cleanName("."))
        assertEquals("", FileOps.cleanName(".."))
        assertEquals("", FileOps.cleanName("   "))
    }

    @Test
    fun namesLoseWhatCardsCantHave() {
        assertEquals("a_b_c.txt", FileOps.cleanName("a/b:c.txt"))
        assertEquals("_.._etc", FileOps.cleanName("/../etc"))
        // "report<RLO>fdp.apk" would show as "reportkpa.pdf".
        assertEquals("reportfdp.apk", FileOps.cleanName("report‮fdp.apk"))
    }

    @Test
    fun longNamesKeepTheirEnding() {
        val long = "x".repeat(200) + ".jpeg"
        val clean = FileOps.cleanName(long)
        assertEquals(120, clean.length)
        assertTrue(clean.endsWith(".jpeg"))
        // A name that is only a dot and an ending isn't cut at that dot.
        assertEquals(120, FileOps.cleanName("." + "y".repeat(200)).length)
    }

    @Test
    fun freeNamesDontClash() {
        val dir = tmp.newFolder("d")
        val photo = File(dir, "photo.jpg").apply { writeText("1") }
        assertEquals("photo (1).jpg", FileOps.freeName(photo).name)
        File(dir, "photo (1).jpg").writeText("2")
        assertEquals("photo (2).jpg", FileOps.freeName(photo).name)
        // "photo (2).jpg" taken as well: the number goes on, not "photo (2) (1).jpg".
        File(dir, "photo (2).jpg").writeText("3")
        assertEquals("photo (3).jpg", FileOps.freeName(File(dir, "photo (2).jpg")).name)
        // A name not taken is itself.
        assertEquals("new.txt", FileOps.freeName(File(dir, "new.txt")).name)
        // A folder "archive.zip" in the way of a file: the file keeps its ending.
        File(dir, "archive.zip").mkdir()
        assertEquals("archive (1).zip", FileOps.freeName(File(dir, "archive.zip"), isDir = false).name)
        assertEquals("archive.zip (1)", FileOps.freeName(File(dir, "archive.zip")).name)
    }

    @Test
    fun insideMeansTheFolderItself() {
        val root = tmp.newFolder("root")
        val pics = File(root, "My_Pics").apply { mkdirs() }
        val other = File(root, "My_Pics2").apply { mkdirs() }
        assertTrue(FileOps.isInside(File(pics, "a.jpg"), pics))
        assertTrue(FileOps.isInside(pics, pics))
        assertFalse(FileOps.isInside(other, pics))
        assertFalse(FileOps.isInside(root, pics))
        // A way out with ".." is seen through.
        assertFalse(FileOps.isInside(File(pics, "../My_Pics2/b.jpg"), pics))
    }

    @Test
    fun sameFileOnlyWhenTheFolderHasOneOfThem() {
        // A storage that ignores capitals lists one name.
        assertTrue(FileOps.sameByNames("photo.jpg", "Photo.jpg", listOf("photo.jpg", "notes.txt")))
        // Swift's own storage (Private) can hold both: two files, never one written over the other.
        assertFalse(FileOps.sameByNames("photo.jpg", "Photo.jpg", listOf("photo.jpg", "Photo.jpg")))
        assertFalse(FileOps.sameByNames("photo.jpg", "other.jpg", listOf("photo.jpg", "other.jpg")))
        assertTrue(FileOps.sameByNames("a", "a", listOf("a")))
    }

    @Test
    fun likeQuestionsTakeUnderscoresAsThemselves() {
        assertEquals("/sdcard/My\\_Pics", FileOps.likeEscaped("/sdcard/My_Pics"))
        assertEquals("100\\%\\\\x", FileOps.likeEscaped("100%\\x"))
    }

    @Test
    fun renamingNeverWritesOverAnotherFile() {
        val dir = tmp.newFolder("r")
        val a = File(dir, "a.txt").apply { writeText("A") }
        val b = File(dir, "b.txt").apply { writeText("B") }
        assertFalse(PlainAccess.rename(a, "b.txt"))
        assertEquals("A", a.readText())
        assertEquals("B", b.readText())
        // Moving into a folder that has another file of the name: refused, nothing written over.
        val other = tmp.newFolder("other")
        File(other, "a.txt").writeText("other A")
        assertFalse(PlainAccess.moveInto(a, other))
        assertEquals("other A", File(other, "a.txt").readText())
        assertTrue(PlainAccess.rename(a, "c.txt"))
        assertEquals("A", File(dir, "c.txt").readText())
    }

    @Test
    fun partsHaveTheirOwnNames() {
        assertTrue(Parts.isPart(".swift-123456.part"))
        assertTrue(Parts.isPart(".swift-up-123456.part"))
        assertFalse(Parts.isPart(".swift-.part"))
        assertFalse(Parts.isPart("swift-1.part"))
        assertFalse(Parts.isPart(".swift-rename-1"))
        val p = Parts.newIn(File("/x"))
        assertTrue(Parts.isPart(p.name))
        assertEquals("/x", p.parentFile!!.path.replace('\\', '/'))
    }
}
