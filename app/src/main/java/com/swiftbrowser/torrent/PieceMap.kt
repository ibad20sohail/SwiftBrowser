package com.swiftbrowser.torrent

/**
 * 3.0.11: where one file's bytes are in a torrent's pieces, for Watch now (a video played while it
 * downloads). A torrent is all its files one after another, cut into pieces of [pieceLength] bytes
 * (the last one shorter); a file starts [fileOffset] bytes in, so its first and last pieces may be
 * shared with the files next to it.
 */
class PieceMap(val fileOffset: Long, val fileSize: Long, val pieceLength: Int, val totalSize: Long) {
    init {
        require(pieceLength > 0 && fileOffset >= 0 && fileSize >= 0 && fileOffset + fileSize <= totalSize)
    }

    /** How many pieces the whole torrent has. */
    val numPieces: Int = ((totalSize + pieceLength - 1) / pieceLength).toInt()

    /** The piece holding byte [pos] of the file. */
    fun pieceOf(pos: Long): Int = ((fileOffset + pos) / pieceLength).toInt()

    val firstPiece: Int = pieceOf(0)

    /** An empty file has one piece all the same: the one it would start in. */
    val lastPiece: Int = if (fileSize == 0L) firstPiece else pieceOf(fileSize - 1)

    /** How long piece [piece] is: [pieceLength], but the torrent's last one only holds what is left. */
    fun pieceSize(piece: Int): Long = minOf(pieceLength.toLong(), totalSize - piece.toLong() * pieceLength)

    /** Where piece [piece] ends in the file (the byte after it), never past the file's end. */
    fun pieceEnd(piece: Int): Long = minOf(fileSize, piece.toLong() * pieceLength + pieceSize(piece) - fileOffset)

    /**
     * How many bytes from [pos] can be read now: up to the first piece not [have], at most [max] and
     * never past the file's end. 0: the piece at [pos] is missing (or [pos] is the end).
     */
    fun readable(pos: Long, have: (Int) -> Boolean, max: Long = Long.MAX_VALUE): Long {
        if (pos < 0 || pos >= fileSize || max <= 0) return 0
        val limit = if (max >= fileSize - pos) fileSize else pos + max
        var piece = pieceOf(pos)
        var end = pos
        while (end < limit && have(piece)) {
            end = minOf(limit, pieceEnd(piece))
            piece++
        }
        return end - pos
    }

    /**
     * The pieces fetched before anything else when watching starts: those of the file's first [head]
     * bytes, then those of its last [tail] bytes (at least one piece each, none twice). A film's index
     * is often at its end (an MP4's moov when it isn't made for streaming, an MKV's cues, an AVI's
     * idx1), and the player reads it before the first picture.
     */
    fun edgePieces(head: Long, tail: Long): List<Int> {
        if (fileSize == 0L) return listOf(firstPiece)
        val out = LinkedHashSet<Int>()
        for (p in firstPiece..pieceOf(head.coerceIn(1, fileSize) - 1)) out += p
        for (p in pieceOf(fileSize - tail.coerceIn(1, fileSize))..lastPiece) out += p
        return out.toList()
    }

    /** The next [count] pieces of the file not [have] yet, from the one holding [pos], in order. */
    fun missingFrom(pos: Long, count: Int, have: (Int) -> Boolean): List<Int> {
        val out = ArrayList<Int>(count.coerceIn(0, 64))
        if (count <= 0 || fileSize == 0L) return out
        var p = pieceOf(pos.coerceIn(0, fileSize - 1))
        while (p <= lastPiece && out.size < count) {
            if (!have(p)) out += p
            p++
        }
        return out
    }

    /**
     * The pieces to ask for first, most urgent first: [pinned] ones still missing (the file's start and
     * end, see [edgePieces]), then the next [window] missing from where it is read ([pos]). Each
     * piece once.
     */
    fun firstWanted(pos: Long, pinned: List<Int>, window: Int, have: (Int) -> Boolean): List<Int> {
        val out = LinkedHashSet<Int>()
        for (p in pinned) if (!have(p)) out += p
        out += missingFrom(pos, window, have)
        return out.toList()
    }

    companion object {
        /** What [edgePieces] takes from the start of a file. */
        const val HEAD_BYTES = 1L shl 20

        /** How many pieces are asked for ahead of where the file is read: about 16 MB of it. */
        private const val WINDOW_BYTES = 16L shl 20
        private const val MIN_WINDOW = 2
        private const val MAX_WINDOW = 64

        /** What [edgePieces] takes from the end of a file: 1% of it (as qBittorrent does), from 1 to 8 MB. */
        fun tailBytes(fileSize: Long): Long = (fileSize / 100).coerceIn(1L shl 20, 8L shl 20)

        /**
         * How many pieces are asked for ahead of the reading ([firstWanted]'s window). While some
         * are, libtorrent fetches only those, so there must be enough for all the people sharing.
         */
        fun windowFor(pieceLength: Int): Int = (WINDOW_BYTES / pieceLength.coerceAtLeast(1)).toInt().coerceIn(MIN_WINDOW, MAX_WINDOW)

        /** The pieces holding [size] bytes from [offset] in a torrent of [pieceLength] pieces (another file, such as its subtitles). */
        fun piecesOf(offset: Long, size: Long, pieceLength: Int): IntRange =
            if (size <= 0) IntRange.EMPTY else (offset / pieceLength).toInt()..((offset + size - 1) / pieceLength).toInt()
    }
}
