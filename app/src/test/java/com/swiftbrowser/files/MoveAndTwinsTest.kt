package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MoveAndTwinsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A folder as a move found it: a.jpg, b.jpg and sub/c.jpg, copied (all but b.jpg, skipped). */
    private fun copiedTree(): Triple<File, Originals, File> {
        val src = tmp.newFolder("src")
        val dest = tmp.newFolder("dest")
        val a = File(src, "a.jpg").apply { writeText("a") }
        File(src, "b.jpg").writeText("b")
        val sub = File(src, "sub").apply { mkdir() }
        val c = File(sub, "c.jpg").apply { writeText("c") }
        val copied = Originals()
        copied.file(a, File(dest, "a.jpg"))
        copied.file(c, File(dest, "sub/c.jpg"))
        copied.folder(sub, File(dest, "sub"))
        copied.folder(src, dest)
        return Triple(src, copied, sub)
    }

    @Test
    fun aMoveTakesAwayOnlyWhatItCopied() {
        val (src, copied, sub) = copiedTree()
        // A download finished in the folder meanwhile.
        val arrived = File(src, "arrived.mp4").apply { writeText("new") }
        val removed = copied.remove(PlainAccess)
        assertFalse(File(src, "a.jpg").exists())
        assertFalse(File(sub, "c.jpg").exists())
        // The folder it emptied goes; the one still holding things stays, with them.
        assertFalse(sub.exists())
        assertTrue(src.isDirectory)
        assertTrue(File(src, "b.jpg").exists())
        assertTrue(arrived.exists())
        assertEquals(3, removed.gone.size)
        assertTrue(removed.stayed.isEmpty())
    }

    @Test
    fun aFileChangedSinceItWasCopiedStays() {
        val src = tmp.newFolder("s")
        val growing = File(src, "video.mp4").apply { writeText("part") }
        val copied = Originals()
        copied.file(growing, File(tmp.root, "copy.mp4"), length = 4, time = growing.lastModified())
        // Still being written: longer now.
        growing.appendText(" and more")
        val removed = copied.remove(PlainAccess)
        assertTrue(growing.exists())
        assertEquals(listOf(growing), removed.stayed)
    }

    @Test
    fun aFileThatCantBeDeletedStaysAndIsSaid() {
        val (_, copied, _) = copiedTree()
        val removed = copied.remove({ f -> f.name != "a.jpg" && f.delete() }, { d -> d.delete() })
        assertEquals(listOf("a.jpg"), removed.stayed.map { it.name })
        // Its folder isn't empty, so it stays.
        assertTrue(removed.gone.any { it.name == "c.jpg" })
    }

    private fun found(path: String, group: Int, ticked: Boolean, time: Long, keeper: Boolean = false) =
        Cleaner.Found(File(path), 1000, time, false, null, group, ticked, keeper)

    @Test
    fun theCameraOrPicturesCopyIsKept() {
        assertEquals(1, Cleaner.keeperOf(listOf("/sdcard/Download/a.jpg", "/sdcard/DCIM/Camera/a.jpg", "/sdcard/Pictures/a.jpg")))
        assertEquals(1, Cleaner.keeperOf(listOf("/sdcard/Download/a.jpg", "/sdcard/Pictures/a.jpg")))
        // Otherwise the newest (the first).
        assertEquals(0, Cleaner.keeperOf(listOf("/sdcard/Music/a.mp3", "/sdcard/Music/old/a.mp3")))
        assertEquals(1, Cleaner.keeperOf(listOf("/sdcard/Download/x.zip", "/sdcard/Documents/x.zip")))
    }

    @Test
    fun oneCopyAlwaysStays() {
        val keep = found("/sdcard/DCIM/Camera/a.jpg", 0, false, 1, keeper = true)
        val other = found("/sdcard/Download/a.jpg", 0, true, 2)
        val all = listOf(keep, other)
        // The last unticked copy can't be ticked.
        assertFalse(Cleaner.canTick(all, keep))
        other.ticked = false
        assertTrue(Cleaner.canTick(all, keep))
        // Tick all leaves the kept copy.
        Cleaner.tickAllTwins(all)
        assertFalse(keep.ticked)
        assertTrue(other.ticked)
        // Every copy ticked somehow: the kept one is unticked again.
        keep.ticked = true
        Cleaner.tidyTwins(all)
        assertFalse(keep.ticked)
        assertEquals(1 to 1000L, Cleaner.tickableTwins(all))
    }

    @Test
    fun aSetDownToOneCopyIsNoSet() {
        val keep = found("/sdcard/Pictures/b.jpg", 1, false, 1, keeper = true)
        val gone = found("/sdcard/Download/b.jpg", 1, true, 2).apply { this.gone = true }
        val last = found("/sdcard/Download/b (1).jpg", 1, true, 3)
        keep.gone = true
        val all = listOf(keep, gone, last)
        Cleaner.tidyTwins(all)
        // The last copy left isn't offered for deleting.
        assertFalse(last.ticked)
        Cleaner.tickAllTwins(all)
        assertFalse(last.ticked)
        assertEquals(0 to 0L, Cleaner.tickableTwins(all))
    }
}
