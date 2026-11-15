package com.swiftbrowser.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PlaylistRulesTest {
    private val a = "/storage/emulated/0/Music/a.mp3"
    private val b = "/storage/emulated/0/Music/b.mp3"
    private val c = "/storage/emulated/0/Music/c.mp3"
    private val d = "/storage/emulated/0/Music/d.mp3"

    @Test
    fun songsGoInOnceEachAtTheEnd() {
        val (first, added) = PlaylistRules.add(listOf(a, b), listOf(c, a, c, d, " "))
        assertEquals(listOf(a, b, c, d), first)
        // a was there already, c came twice, and a blank isn't a song.
        assertEquals(2, added)
        val (same, none) = PlaylistRules.add(first, listOf(b, d))
        assertEquals(first, same)
        assertEquals(0, none)
    }

    @Test
    fun aPlaylistStopsAtItsLargest() {
        val full = (1..PlaylistRules.MAX_SONGS).map { "/storage/emulated/0/Music/$it.mp3" }
        val (next, added) = PlaylistRules.add(full, listOf(a, b))
        assertEquals(PlaylistRules.MAX_SONGS, next.size)
        assertEquals(0, added)
    }

    @Test
    fun aMovedSongTakesItsNewPlaceAndTheOthersMakeRoom() {
        assertEquals(listOf(b, c, a, d), PlaylistRules.move(listOf(a, b, c, d), 0, 2))
        assertEquals(listOf(d, a, b, c), PlaylistRules.move(listOf(a, b, c, d), 3, 0))
        // Nowhere to go: unchanged.
        assertEquals(listOf(a, b), PlaylistRules.move(listOf(a, b), 0, 5))
        assertEquals(listOf(a, b), PlaylistRules.move(listOf(a, b), 1, 1))
    }

    @Test
    fun undoPutsASongBackWhereItWas() {
        assertEquals(listOf(a, b, c), PlaylistRules.putBack(listOf(a, c), b, 1))
        // The list got shorter meanwhile: at its end.
        assertEquals(listOf(a, b), PlaylistRules.putBack(listOf(a), b, 4))
        // Back already (added again meanwhile): not twice.
        assertEquals(listOf(a, b), PlaylistRules.putBack(listOf(a, b), b, 0))
    }

    @Test
    fun namesAreOneTidyLineOfAFewWords() {
        assertEquals("Road trip", PlaylistRules.cleanName("  Road \n  trip\t"))
        assertEquals(PlaylistRules.MAX_NAME, PlaylistRules.cleanName("x".repeat(200)).length)
        assertEquals("", PlaylistRules.cleanName("   "))
    }

    @Test
    fun aNewPlaylistGetsTheNextFreeNumber() {
        assertEquals("Playlist 1", PlaylistRules.freshName(emptyList()))
        assertEquals("Playlist 3", PlaylistRules.freshName(listOf("playlist 1", "Playlist 2", "Gym")))
    }

    @Test
    fun aNameTakenGetsANumber() {
        assertEquals("Gym", PlaylistRules.uniqueName("Gym", listOf("Road trip")))
        assertEquals("Gym (2)", PlaylistRules.uniqueName("Gym", listOf("gym")))
        assertEquals("Gym (3)", PlaylistRules.uniqueName("Gym", listOf("Gym", "Gym (2)")))
        // A long name is cut to make room for its number.
        val long = "y".repeat(PlaylistRules.MAX_NAME)
        val unique = PlaylistRules.uniqueName(long, listOf(long))
        assertEquals(PlaylistRules.MAX_NAME, unique.length)
        assertTrue(unique.endsWith(" (2)"))
    }

    @Test
    fun onlyPathsOnThePhonesStorageAreKept() {
        val own = listOf("/data/user/0/com.swiftbrowser.app", "/data/data/com.swiftbrowser.app")
        assertTrue(PlaylistRules.keeps(a, own))
        assertTrue(PlaylistRules.keeps("/storage/1A2B-3C4D/Music/song.flac", own))
        // Swift's own storage (the Private folder is in it), by any of its names.
        assertFalse(PlaylistRules.keeps("/data/user/0/com.swiftbrowser.app/files/private/x.mp3", own))
        assertFalse(PlaylistRules.keeps("/data/data/com.swiftbrowser.app", own))
        // Nothing that could find its way there, and nothing that isn't a whole path.
        assertFalse(PlaylistRules.keeps("/storage/emulated/0/../../data/data/com.swiftbrowser.app/x.mp3", own))
        assertFalse(PlaylistRules.keeps("Music/a.mp3", own))
        assertFalse(PlaylistRules.keeps("/storage/emulated/0/Music/", own))
        assertFalse(PlaylistRules.keeps("/" + "a".repeat(5000), own))
        // Only the folder itself and what is in it: a neighbour with a longer name is someone else's.
        assertTrue(PlaylistRules.keeps("/data/data/com.swiftbrowser.app2/x.mp3", own))
    }

    @Test
    fun playLeavesOutSongsThatAreGone() {
        val there = setOf(a, c, d)
        val songs = listOf(a, b, c, d)
        assertEquals(listOf(a, c, d) to 0, PlaylistRules.playable(songs, { it in there }))
        // From c: c is the second of those there.
        assertEquals(listOf(a, c, d) to 1, PlaylistRules.playable(songs, { it in there }, start = 2))
        // From b, which is gone: the next one there.
        assertEquals(listOf(a, c, d) to 1, PlaylistRules.playable(songs, { it in there }, start = 1))
        // Nothing there after it: from the first.
        assertEquals(listOf(a) to 0, PlaylistRules.playable(listOf(a, b), { it == a }, start = 1))
        assertEquals(emptyList<String>() to 0, PlaylistRules.playable(songs, { false }))
    }

    @Test
    fun shuffleStartsWithTheSongChosenAndPlaysEveryOneOnce() {
        val order = PlaylistRules.shuffleOrder(6, 3, Random(7))
        assertEquals(3, order[0])
        assertEquals((0 until 6).toList(), order.sorted())
        assertArrayEquals(intArrayOf(0), PlaylistRules.shuffleOrder(1, 0, Random(1)))
        assertEquals(0, PlaylistRules.shuffleOrder(0, 0, Random(1)).size)
        // A place past the end is the last song.
        assertEquals(4, PlaylistRules.shuffleOrder(5, 9, Random(2))[0])
    }

    @Test
    fun theWordsSayHowManyAndWhatHappened() {
        assertEquals("1 song", PlaylistRules.songsText(1))
        assertEquals("12 songs", PlaylistRules.songsText(12))
        assertEquals("12 songs · 2 missing", PlaylistRules.songsText(12, 2))
        assertEquals("Added to “Gym”", PlaylistRules.addedText("Gym", 1, 1))
        assertEquals("It's in “Gym” already", PlaylistRules.addedText("Gym", 1, 0))
        assertEquals("Added 3 songs to “Gym”", PlaylistRules.addedText("Gym", 3, 3))
        assertEquals("Added 1 song to “Gym”: the others were in it already", PlaylistRules.addedText("Gym", 3, 1))
        assertEquals("They're all in “Gym” already", PlaylistRules.addedText("Gym", 3, 0))
        assertEquals("Made “Gym”", PlaylistRules.madeText("Gym", 0))
        assertEquals("Made “Gym” with 1 song", PlaylistRules.madeText("Gym", 1))
        assertEquals("Made “Gym” with 4 songs", PlaylistRules.madeText("Gym", 4))
    }
}
