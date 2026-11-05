package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialFoldersTest {
    private val phone = "/storage/emulated/0"
    private val card = "/storage/1A2B-3C4D"
    private val roots = listOf(phone, card)

    private fun of(path: String) = SpecialFolders.of(path, roots)

    @Test
    fun theStandardFoldersAtTheTopOfTheStorage() {
        assertEquals(SpecialFolders.Mark.CAMERA, of("$phone/DCIM"))
        assertEquals(SpecialFolders.Mark.DOWNLOAD, of("$phone/Download"))
        assertEquals(SpecialFolders.Mark.PICTURES, of("$phone/Pictures"))
        assertEquals(SpecialFolders.Mark.MOVIES, of("$phone/Movies"))
        assertEquals(SpecialFolders.Mark.MUSIC, of("$phone/Music"))
        assertEquals(SpecialFolders.Mark.DOCUMENTS, of("$phone/Documents"))
    }

    @Test
    fun theCameraAndScreenshotsFolders() {
        assertEquals(SpecialFolders.Mark.CAMERA, of("$phone/DCIM/Camera"))
        assertEquals(SpecialFolders.Mark.SCREENSHOTS, of("$phone/Pictures/Screenshots"))
        assertEquals(SpecialFolders.Mark.SCREENSHOTS, of("$phone/DCIM/Screenshots"))
    }

    @Test
    fun onAMemoryCardToo() {
        assertEquals(SpecialFolders.Mark.CAMERA, of("$card/DCIM"))
        assertEquals(SpecialFolders.Mark.MUSIC, of("$card/Music"))
        assertEquals(SpecialFolders.Mark.CAMERA, of("$card/DCIM/Camera"))
    }

    @Test
    fun capitalsDontMatter() {
        // The phone's storage doesn't tell them apart: it is the same folder.
        assertEquals(SpecialFolders.Mark.DOWNLOAD, of("$phone/download"))
        assertEquals(SpecialFolders.Mark.CAMERA, of("$phone/dcim/camera"))
        assertEquals(SpecialFolders.Mark.SCREENSHOTS, of("$phone/PICTURES/screenshots"))
    }

    @Test
    fun otherFoldersAreJustFolders() {
        // Deeper, or only like one, or not at the top of a storage.
        assertNull(of("$phone/DCIM/Camera/2026"))
        assertNull(of("$phone/Download/Swift"))
        assertNull(of("$phone/Music/Pictures"))
        assertNull(of("$phone/DCIMx"))
        assertNull(of("$phone/Downloads"))
        assertNull(of("$phone/My Documents"))
        assertNull(of("$phone/Android/data/Download"))
        assertNull(of("/data/user/0/com.swiftbrowser.app/files/private/Download"))
        // The storage itself, and a storage that only starts like one.
        assertNull(of(phone))
        assertNull(of("/storage/emulated/01/Download"))
        assertNull(of("$card-2/Download"))
    }

    @Test
    fun aTrailingSlashIsTheSameFolder() {
        assertEquals(SpecialFolders.Mark.MOVIES, of("$phone/Movies/"))
        assertEquals(SpecialFolders.Mark.MOVIES, SpecialFolders.of("$phone/Movies", listOf("$phone/")))
    }

    @Test
    fun noStoragesNoMarks() {
        assertNull(SpecialFolders.of("$phone/DCIM", emptyList()))
    }
}
