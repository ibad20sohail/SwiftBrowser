package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    @Test
    fun onlyFormatsThisSwiftKnowsAreRestored() {
        // Backups made before 3.0.1 have no format and are read as 1.
        assertTrue(Backup.readable(1))
        assertTrue(Backup.readable(Backup.FORMAT))
        // A newer Swift's backup isn't half understood, and nonsense isn't read at all.
        assertFalse(Backup.readable(Backup.FORMAT + 1))
        assertFalse(Backup.readable(0))
        assertFalse(Backup.readable(-3))
    }

    @Test
    fun theMessageSaysExactlyWhatWentIn() {
        assertEquals(
            "Added 1 profile, 12 bookmarks, 300 history entries and 4 tabs. 25 settings restored.",
            Backup.restoredText(Backup.Restored(profiles = 1, bookmarks = 12, history = 300, tabs = 4, siteLevels = 0, settings = 25)),
        )
        assertEquals(
            "Added 1 tab. 1 setting restored.",
            Backup.restoredText(Backup.Restored(profiles = 0, bookmarks = 0, history = 0, tabs = 1, siteLevels = 0, settings = 1)),
        )
        assertEquals(
            "Added 2 bookmarks and 3 sites' ad blocking levels. It had no settings to restore.",
            Backup.restoredText(Backup.Restored(profiles = 0, bookmarks = 2, history = 0, tabs = 0, siteLevels = 3, settings = 0)),
        )
    }

    @Test
    fun aRestoreThatAddsNothingNewSaysSo() {
        assertEquals(
            "Nothing in it was new for this phone. 40 settings restored.",
            Backup.restoredText(Backup.Restored(profiles = 0, bookmarks = 0, history = 0, tabs = 0, siteLevels = 0, settings = 40)),
        )
    }

    @Test
    fun settingsThatCouldNotGoInAreNotHidden() {
        // Before 3.0.1 this case said "Nothing on this phone was changed" although the data was in.
        assertEquals(
            "Added 5 bookmarks. Its settings couldn't be put in, so yours stay as they were.",
            Backup.restoredText(Backup.Restored(profiles = 0, bookmarks = 5, history = 0, tabs = 0, siteLevels = 0, settings = -1)),
        )
    }
}
