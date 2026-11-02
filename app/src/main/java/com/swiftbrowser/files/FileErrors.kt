package com.swiftbrowser.files

/**
 * The usual reasons a file can't be written, in words (3.0), for file jobs and Wi-Fi transfer: the
 * system's own messages ("write failed: ENOSPC (No space left on device)") mean nothing to most people.
 */
object FileErrors {
    const val FULL = "The phone is full."
    const val CARD_FULL = "The memory card is full."
    const val FAT32 = "Memory cards formatted as FAT32 can't hold files over 4 GB."
    const val NOT_ALLOWED = "Swift can't write there."

    /** The biggest file a FAT32 card holds: 4 GB less a byte. */
    const val FAT32_MAX = 4L * 1024 * 1024 * 1024 - 1

    /** A file this close to 4 GB when writing it failed was stopped by the card's format. */
    private const val NEAR_FAT32 = 4L * 1_048_576

    /**
     * [e] in words when it is one of the usual reasons, otherwise null. [size]: how far the file had
     * got when it failed (-1: not known); [onCard]: it was being written to a memory card.
     */
    fun text(e: Throwable, size: Long = -1, onCard: Boolean = false): String? {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        val said = chain.mapNotNull { it.message }.joinToString(" ")
        return when {
            "ENOSPC" in said || said.contains("No space left", ignoreCase = true) -> if (onCard) CARD_FULL else FULL
            "EFBIG" in said || said.contains("File too large", ignoreCase = true) -> FAT32
            // Some cards refuse the write that crosses 4 GB with "invalid argument" instead.
            ("EINVAL" in said || said.contains("Invalid argument", ignoreCase = true)) && size >= FAT32_MAX - NEAR_FAT32 -> FAT32
            "EACCES" in said || "EROFS" in said || "EPERM" in said ||
                said.contains("Permission denied", ignoreCase = true) || said.contains("Read-only file system", ignoreCase = true) ||
                chain.any { it is SecurityException } -> NOT_ALLOWED
            else -> null
        }
    }
}
