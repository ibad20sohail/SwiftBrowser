package com.swiftbrowser.files

/**
 * 3.0.4: the standard folders at the top of each storage (DCIM, Download, Pictures, Movies, Music,
 * Documents) and the camera's and screenshots' folders in them, which Files shows with a small mark
 * of what they hold inside the folder icon. Plain maths on paths, tested on the PC
 * (SpecialFoldersTest); the marks' icons are Files' (FilesActivity).
 */
object SpecialFolders {
    enum class Mark { CAMERA, DOWNLOAD, PICTURES, MOVIES, MUSIC, DOCUMENTS, SCREENSHOTS }

    /** Each one by its path under a storage's top folder. */
    private val NAMES = arrayOf(
        "DCIM", "DCIM/Camera", "DCIM/Screenshots", "Download", "Pictures", "Pictures/Screenshots", "Movies", "Music", "Documents",
    )
    private val MARKS = arrayOf(
        Mark.CAMERA, Mark.CAMERA, Mark.SCREENSHOTS, Mark.DOWNLOAD, Mark.PICTURES, Mark.SCREENSHOTS, Mark.MOVIES, Mark.MUSIC, Mark.DOCUMENTS,
    )

    /**
     * The mark of the folder at [path], or null for any other folder: one of the standard folders
     * of a storage whose top folder is in [roots] (the phone's storage, a memory card). The phone's
     * storage doesn't tell capitals apart ("download" is the same folder), so neither does this.
     * Nothing is made while asking: it is asked for every folder row as it is shown.
     */
    fun of(path: String, roots: List<String>): Mark? {
        for (r in roots.indices) {
            val root = roots[r]
            val rootLength = if (root.length > 1 && root.endsWith('/')) root.length - 1 else root.length
            val start = rootLength + 1
            if (path.length <= start || path[rootLength] != '/' || !path.regionMatches(0, root, 0, rootLength)) continue
            val length = path.length - start - if (path.endsWith('/')) 1 else 0
            for (i in NAMES.indices) {
                val name = NAMES[i]
                if (length == name.length && path.regionMatches(start, name, 0, name.length, ignoreCase = true)) return MARKS[i]
            }
        }
        return null
    }
}
