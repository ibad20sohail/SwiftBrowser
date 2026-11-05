package com.swiftbrowser.files.net

/**
 * 3.0.4: paths inside a share of a network folder, written with "/" ("Films/2024"; "" is the top of
 * the share), as smbj takes them. Pure, tested on the PC.
 */
object NetPath {
    /** Without empty parts, "." or stray slashes; backslashes (Windows) read as slashes. */
    fun clean(path: String): String = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** [name] inside [parent] ("" being the top of the share). */
    fun join(parent: String, name: String): String = clean(if (parent.isEmpty()) name else "$parent/$name")

    /** The folder [path] is in ("" for something at the top of the share). */
    fun parent(path: String): String = clean(path).substringBeforeLast('/', "")

    fun name(path: String): String = clean(path).substringAfterLast('/')

    fun segments(path: String): List<String> = clean(path).split('/').filter { it.isNotEmpty() }

    /** A name in a folder's listing that is a file or folder of its own ("." and ".." are the folder itself and the one above). */
    fun isEntry(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".."

    /**
     * A name from the server as a file or folder name on the phone: nothing that could climb out of
     * the folder it is copied into ("..", slashes) or that a memory card refuses (: * ? " < > |).
     */
    fun localName(name: String): String {
        val s = name.replace(BAD, "_").trim().trimEnd('.').trim()
        return if (s.isEmpty() || s == "." || s == "..") "_" else s
    }

    private val BAD = Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]")
}
