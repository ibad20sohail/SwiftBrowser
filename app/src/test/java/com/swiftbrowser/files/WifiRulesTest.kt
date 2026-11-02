package com.swiftbrowser.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRulesTest {
    @Test
    fun tenWrongCodesInAllLockEveryoneOut() {
        val lock = CodeLock(10)
        assertEquals(CodeLock.Answer.IN, lock.attempt("123456", "123456"))
        repeat(9) { assertEquals(CodeLock.Answer.WRONG, lock.attempt("000000", "123456")) }
        // A right one in between doesn't start the count again: ten in all.
        assertEquals(CodeLock.Answer.IN, lock.attempt("123456", "123456"))
        assertEquals(CodeLock.Answer.LOCKED_NOW, lock.attempt("111111", "123456"))
        assertTrue(lock.locked)
        // Locked: even the right code is refused, until New code.
        assertEquals(CodeLock.Answer.LOCKED, lock.attempt("123456", "123456"))
        lock.reset()
        assertFalse(lock.locked)
        assertEquals(CodeLock.Answer.IN, lock.attempt("654321", "654321"))
    }

    @Test
    fun anEmptyCodeIsWrong() {
        val lock = CodeLock(10)
        assertEquals(CodeLock.Answer.WRONG, lock.attempt("", ""))
    }

    @Test
    fun swiftsOwnFolderIsHiddenInAnyCapitals() {
        val pkg = "com.swiftbrowser.app"
        assertTrue(WifiPaths.isOwnFolder(listOf("Android", "data", pkg), pkg))
        assertTrue(WifiPaths.isOwnFolder(listOf("android", "DATA", "COM.swiftbrowser.app", "files"), pkg))
        assertFalse(WifiPaths.isOwnFolder(listOf("Android", "data", "com.other.app"), pkg))
        assertFalse(WifiPaths.isOwnFolder(listOf("Android", "data"), pkg))
        assertFalse(WifiPaths.isOwnFolder(listOf("Download", "data", pkg), pkg))
    }

    @Test
    fun withinIgnoresCapitalsButNotNames() {
        val dl = "/storage/1A2B-3C4D/Android/data/com.swiftbrowser.app/files/Download"
        assertTrue(WifiPaths.within(dl, dl))
        assertTrue(WifiPaths.within("$dl/film.mp4", dl))
        assertTrue(WifiPaths.within("/storage/1A2B-3C4D/android/DATA/com.swiftbrowser.app/files/download/a", dl))
        assertFalse(WifiPaths.within("/storage/1A2B-3C4D/Android/data/com.swiftbrowser.app/files/Downloads", dl))
        assertFalse(WifiPaths.within("/storage/1A2B-3C4D/Android/data/com.swiftbrowser.app/cache", dl))
    }
}
