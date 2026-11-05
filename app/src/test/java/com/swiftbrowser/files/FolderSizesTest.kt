package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FolderSizesTest {
    /** A disk in memory: folders, files with their bytes, and links. */
    private class Disk : FolderSizes.Disk {
        private val names = LinkedHashMap<String, MutableList<String>>()
        private val nodes = HashMap<String, FolderSizes.Node>()
        val unreadable = HashSet<String>()

        private fun add(path: String, node: FolderSizes.Node) {
            nodes[path] = node
            val parent = path.substringBeforeLast('/')
            names.getOrPut(parent) { mutableListOf() } += path.substringAfterLast('/')
        }

        fun dir(path: String) = apply {
            add(path, FolderSizes.Node(dir = true, file = false, bytes = 4096))
            names.getOrPut(path) { mutableListOf() }
        }

        fun file(path: String, bytes: Long) = apply { add(path, FolderSizes.Node(dir = false, file = true, bytes = bytes)) }

        /** A link (lstat sees neither a folder nor a plain file), here to a big file or a folder above. */
        fun link(path: String) = apply { add(path, FolderSizes.Node(dir = false, file = false, bytes = 1_000_000)) }

        override fun names(dir: String): Array<String>? = if (dir in unreadable) null else names[dir]?.toTypedArray()

        override fun node(path: String): FolderSizes.Node? = nodes[path]
    }

    private val noStop = { false }
    private var now = 0L
    private val clock = { now }

    @Before
    fun clean() {
        FolderSizes.clear()
        now = 0L
    }

    private fun sample() = Disk().dir("/r")
        .file("/r/a.txt", 100)
        .dir("/r/sub").file("/r/sub/b.jpg", 200)
        .dir("/r/sub/deep").file("/r/sub/deep/c.mp4", 300)
        .dir("/r/empty")

    @Test
    fun aFolderHoldsEverythingBelowIt() {
        val s = FolderSizes.walk("/r", sample(), clock, noStop)!!
        assertEquals(600L, s.bytes)
        assertEquals(3, s.files)
        assertTrue(s.complete)
    }

    @Test
    fun linksAreLeftOut() {
        val d = sample().link("/r/to-big-file").link("/r/sub/back-up")
        val s = FolderSizes.walk("/r", d, clock, noStop)!!
        assertEquals(600L, s.bytes)
        assertEquals(3, s.files)
        assertTrue(s.complete)
    }

    @Test
    fun theFoldersRightInsideAreToldOnceWhole() {
        val found = HashMap<String, Long>()
        FolderSizes.walk("/r", sample(), clock, noStop) { path, size -> found[path] = size.bytes }
        // The next ones opened are known at once; deeper ones are measured when they show.
        assertEquals(mapOf("/r/sub" to 500L, "/r/empty" to 0L), found)
    }

    @Test
    fun tooManyThingsStopsShortAsAtLeast() {
        val d = Disk().dir("/r")
        repeat(50) { d.file("/r/f$it", 10) }
        val s = FolderSizes.walk("/r", d, clock, noStop, maxEntries = 20)!!
        assertFalse(s.complete)
        assertEquals(200L, s.bytes)
        assertEquals("over 200 B", FolderSizes.text(s))
        // Exactly as many as allowed is still whole.
        assertTrue(FolderSizes.walk("/r", d, clock, noStop, maxEntries = 50)!!.complete)
    }

    @Test
    fun tooLongStopsShortAsAtLeast() {
        val d = Disk().dir("/r")
        repeat(100) { d.file("/r/f$it", 1) }
        // Every look at the clock takes 50 ms here: two seconds go by after 40 or so.
        val slow = { now += 50; now }
        val s = FolderSizes.walk("/r", d, slow, noStop, maxMs = 2_000)!!
        assertFalse(s.complete)
        assertTrue(s.bytes in 30L..45L)
    }

    @Test
    fun aFolderCutShortInsideIsntToldAsWhole() {
        val d = Disk().dir("/r").dir("/r/a").file("/r/a/1", 5).dir("/r/b")
        repeat(30) { d.file("/r/b/f$it", 1) }
        val found = HashMap<String, Long>()
        val s = FolderSizes.walk("/r", d, clock, noStop, maxEntries = 10) { path, size -> found[path] = size.bytes }!!
        assertFalse(s.complete)
        assertEquals(mapOf("/r/a" to 5L), found)
    }

    @Test
    fun stoppingKeepsNothing() {
        var looks = 0
        val stop = { ++looks > 3 }
        assertNull(FolderSizes.walk("/r", sample(), clock, stop))
    }

    @Test
    fun whatCantBeReadIsAtLeast() {
        // The folder itself: nothing to say.
        val d = sample().apply { unreadable += "/r" }
        val none = FolderSizes.walk("/r", d, clock, noStop)!!
        assertFalse(none.complete)
        assertNull(FolderSizes.text(none))
        // A folder inside it: the rest is counted, "at least".
        val part = FolderSizes.walk("/r", sample().apply { unreadable += "/r/sub/deep" }, clock, noStop)!!
        assertFalse(part.complete)
        assertEquals(300L, part.bytes)
    }

    @Test
    fun sizesReadAsFilesShowThem() {
        assertEquals("0 B", FolderSizes.text(FolderSizes.Size(0, 0, true)))
        assertEquals("1.50 GB", FolderSizes.text(FolderSizes.Size(1_610_612_736, 9, true)))
        assertEquals("over 1.50 GB", FolderSizes.text(FolderSizes.Size(1_610_612_736, 9, false)))
        assertEquals("over 12.0 MB", FolderSizes.text(FolderSizes.Size(12_582_912, 9, false)))
    }

    @Test
    fun aChangeReachesTheFolderTheOnesAboveAndTheOnesInside() {
        assertTrue(FolderSizes.affects("/s/a/b", "/s/a/b"))
        assertTrue(FolderSizes.affects("/s/a/b", "/s/a"))
        assertTrue(FolderSizes.affects("/s/a/b", "/s"))
        assertTrue(FolderSizes.affects("/s/a/b", "/s/a/b/c"))
        // Not a folder whose name only begins the same, nor one beside it.
        assertFalse(FolderSizes.affects("/s/a/b", "/s/a/bc"))
        assertFalse(FolderSizes.affects("/s/a/b", "/s/a/c"))
        assertFalse(FolderSizes.affects("/s/a/bc", "/s/a/b"))
    }

    @Test
    fun changedFoldersAreMeasuredAgain() {
        val size = FolderSizes.Size(1, 1, true)
        for (p in listOf("/t", "/t/a", "/t/a/b", "/t/a/b/c", "/t/x")) FolderSizes.keep(p, size, 0L)
        FolderSizes.changed(listOf("/t/a/b/"))
        assertNull(FolderSizes.cached("/t"))
        assertNull(FolderSizes.cached("/t/a"))
        assertNull(FolderSizes.cached("/t/a/b"))
        assertNull(FolderSizes.cached("/t/a/b/c"))
        assertNotNull(FolderSizes.cached("/t/x"))
        // Nothing named: nothing goes.
        FolderSizes.changed(emptyList())
        assertNotNull(FolderSizes.cached("/t/x"))
    }

    @Test
    fun aWalkIsntKeptWhenItsFolderChangedMeanwhile() {
        val size = FolderSizes.Size(5, 1, true)
        // A change inside the folder while it was walked: what the walk found may be wrong.
        var since = FolderSizes.mark()
        FolderSizes.changed(listOf("/u/photos"))
        assertFalse(FolderSizes.keep("/u", size, 0L, since))
        assertNull(FolderSizes.cached("/u"))
        // One elsewhere (a download, the recycle bin on another card) doesn't matter.
        since = FolderSizes.mark()
        FolderSizes.changed(listOf("/elsewhere", "/storage/1234/.SwiftBin"))
        assertTrue(FolderSizes.keep("/u", size, 0L, since))
        assertEquals(5L, FolderSizes.cached("/u")?.bytes)
        // More changes since than are remembered: it can't be told, so it isn't kept.
        since = FolderSizes.mark()
        repeat(100) { FolderSizes.changed(listOf("/other/$it")) }
        assertFalse(FolderSizes.keep("/w", size, 0L, since))
        // Begun after them all: kept.
        assertTrue(FolderSizes.keep("/w", size, 0L, FolderSizes.mark()))
    }

    @Test
    fun aSortTakesTheSizesAsTheyAreNow() {
        FolderSizes.keep("/x/a", FolderSizes.Size(10, 1, true), 0L)
        FolderSizes.keep("/x/b", FolderSizes.Size(20, 2, false), 0L)
        val now = FolderSizes.bytesFor(listOf("/x/a", "/x/b", "/x/c"))
        assertEquals(mapOf("/x/a" to 10L, "/x/b" to 20L), now)
        // Measured again meanwhile: the copy stays as it was.
        FolderSizes.keep("/x/a", FolderSizes.Size(99, 1, true), 0L)
        assertEquals(10L, now["/x/a"])
    }

    @Test
    fun sizesAreFreshForTenMinutesAndShowAfterThat() {
        FolderSizes.keep("/v", FolderSizes.Size(7, 1, true), 1_000L)
        assertTrue(FolderSizes.fresh("/v", 1_000L))
        assertTrue(FolderSizes.fresh("/v", 1_000L + FolderSizes.FRESH_MS - 1))
        assertFalse(FolderSizes.fresh("/v", 1_000L + FolderSizes.FRESH_MS))
        // Still shown meanwhile, until it is measured again.
        assertEquals(7L, FolderSizes.cached("/v")?.bytes)
        assertFalse(FolderSizes.fresh("/w", 1_000L))
    }

    @Test
    fun bySizeFoldersGoByWhatTheyHold() {
        fun folder(name: String) = FileItem(File("/s/$name"), name, true, 0L, 0L, 3, FileKind.FOLDER)
        fun file(name: String, size: Long) = FileItem(File("/s/$name"), name, false, size, 0L, -1, FileKind.OTHER)
        val items = listOf(folder("Apps"), folder("Books"), folder("Camera"), folder("Docs"), file("small.txt", 10), file("big.mp4", 900))
        val bytes = mapOf(File("/s/Books").path to 100L, File("/s/Camera").path to 5_000L, File("/s/Docs").path to 100L)
        val sorted = FileOps.sorted(items, FileOps.SORT_SIZE) { bytes[it] }.map { it.name }
        // Measured ones first, biggest at the top (the same size: by name), then the one not measured yet; files after.
        assertEquals(listOf("Camera", "Books", "Docs", "Apps", "big.mp4", "small.txt"), sorted)
        // Without sizes, folders keep their names' order, as before.
        assertEquals(listOf("Apps", "Books", "Camera", "Docs", "big.mp4", "small.txt"), FileOps.sorted(items, FileOps.SORT_SIZE).map { it.name })
        // Other orders don't use them.
        assertEquals(listOf("Apps", "Books", "Camera", "Docs", "big.mp4", "small.txt"), FileOps.sorted(items, FileOps.SORT_NAME) { bytes[it] }.map { it.name })
    }
}
