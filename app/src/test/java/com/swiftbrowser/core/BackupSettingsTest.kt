package com.swiftbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSettingsTest {
    @Test
    fun secretsAndPhoneStateNeverGoIntoABackup() {
        // The Private folder's PIN check, its tries and the files moved into it.
        assertNull(BackupSettings.forBackup("files_private_pin", "1:abcd:ef01"))
        assertNull(BackupSettings.forBackup("files_private_tries", 3))
        assertNull(BackupSettings.forBackup("files_private_wait_until", 1_700_000_000_000L))
        assertNull(BackupSettings.forBackup("files_private_origins", "{\"a\":\"b\"}"))
        // Sign-ins, the VPN's locations (private keys), and what only this phone knows.
        assertNull(BackupSettings.forBackup("login_never", setOf("bank.example")))
        assertNull(BackupSettings.forBackup("login_offer", true))
        assertNull(BackupSettings.forBackup("vpn_locations", "[]"))
        assertNull(BackupSettings.forBackup("vpn_wanted", true))
        assertNull(BackupSettings.forBackup("storage_asked", true))
        assertNull(BackupSettings.forBackup("menu_move_hint_seen", true))
        assertNull(BackupSettings.forBackup("last_update_check", 5L))
        assertNull(BackupSettings.forBackup("ad_stats", "2026-11-01:12"))
        assertNull(BackupSettings.forBackup("data_usage", "{}"))
        assertNull(BackupSettings.forBackup("files_last_folder", "/storage/emulated/0/Movies"))
        assertNull(BackupSettings.forBackup("current_profile", 1L))
        // Anything not known stays on the phone (a setting added later must be listed to travel).
        assertNull(BackupSettings.forBackup("something_new", true))
    }

    @Test
    fun theUsersChoicesGoInWithTheirKind() {
        assertEquals(BackupSettings.BOOLEAN to true, BackupSettings.forBackup("full_screen", true))
        assertEquals(BackupSettings.INT to 2, BackupSettings.forBackup("theme_mode", 2))
        assertEquals(BackupSettings.FLOAT to 1.25f, BackupSettings.forBackup("text_scale", 1.25f))
        assertEquals(BackupSettings.STRING to "duckduckgo", BackupSettings.forBackup("search_engine", "duckduckgo"))
        // Sets as sorted lists of texts.
        assertEquals(BackupSettings.SET to listOf("a.com", "b.com"), BackupSettings.forBackup("site_desktop", setOf("b.com", "a.com")))
        // A profile's pinned tiles, by its id.
        assertEquals(BackupSettings.STRING to "[]", BackupSettings.forBackup("pinned_tiles_3", "[]"))
        assertNull(BackupSettings.forBackup("pinned_tiles_", "[]"))
        assertNull(BackupSettings.forBackup("pinned_tiles_x", "[]"))
    }

    @Test
    fun aValueOfTheWrongKindIsLeftOut() {
        // Written: a phone's value of another kind than Swift reads.
        assertNull(BackupSettings.forBackup("dark_mode", "true"))
        assertNull(BackupSettings.forBackup("theme_mode", 2L))
        // Read: "dark_mode" as a text made reading it fail at every start.
        assertNull(BackupSettings.fromBackup("dark_mode", BackupSettings.STRING, "true"))
        assertNull(BackupSettings.fromBackup("dark_mode", BackupSettings.BOOLEAN, "true"))
        assertNull(BackupSettings.fromBackup("theme_mode", BackupSettings.INT, "2"))
        assertNull(BackupSettings.fromBackup("theme_mode", BackupSettings.INT, 2.5))
        assertNull(BackupSettings.fromBackup("theme_mode", BackupSettings.INT, 5_000_000_000L))
        assertNull(BackupSettings.fromBackup("text_scale", BackupSettings.FLOAT, Double.NaN))
        assertNull(BackupSettings.fromBackup("search_engine", BackupSettings.STRING, null))
        assertNull(BackupSettings.fromBackup("site_desktop", BackupSettings.SET, "a.com"))
    }

    @Test
    fun theRightKindIsReadBack() {
        assertEquals(true, BackupSettings.fromBackup("dark_mode", BackupSettings.BOOLEAN, true))
        // JSON gives numbers as Integer, Long or Double.
        assertEquals(2, BackupSettings.fromBackup("theme_mode", BackupSettings.INT, 2))
        assertEquals(2, BackupSettings.fromBackup("theme_mode", BackupSettings.INT, 2L))
        assertEquals(2, BackupSettings.fromBackup("theme_mode", BackupSettings.INT, 2.0))
        assertEquals(1.25f, BackupSettings.fromBackup("text_scale", BackupSettings.FLOAT, 1.25))
        assertEquals(1.0f, BackupSettings.fromBackup("text_scale", BackupSettings.FLOAT, 1))
        assertEquals("[]", BackupSettings.fromBackup("pinned_tiles_7", BackupSettings.STRING, "[]"))
        // Only texts stay in a set.
        assertEquals(setOf("a.com", "b.com"), BackupSettings.fromBackup("site_dark", BackupSettings.SET, listOf("a.com", 3, null, "b.com")))
    }

    @Test
    fun secretsAreRefusedFromABackupToo() {
        assertNull(BackupSettings.fromBackup("files_private_pin", BackupSettings.STRING, "1:00:00"))
        assertNull(BackupSettings.fromBackup("vpn_locations", BackupSettings.STRING, "[]"))
        assertNull(BackupSettings.fromBackup("login_never", BackupSettings.SET, listOf("a.com")))
        assertNull(BackupSettings.fromBackup("storage_asked", BackupSettings.BOOLEAN, true))
    }

    @Test
    fun perProfileKeysGiveTheirProfile() {
        assertEquals("pinned_tiles_", BackupSettings.perProfilePrefix("pinned_tiles_12"))
        assertEquals(12L, BackupSettings.profileOf("pinned_tiles_12", "pinned_tiles_"))
        assertNull(BackupSettings.perProfilePrefix("full_screen"))
        assertNull(BackupSettings.profileOf("pinned_tiles_1a", "pinned_tiles_"))
    }
}
