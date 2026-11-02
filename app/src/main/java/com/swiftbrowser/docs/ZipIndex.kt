package com.swiftbrowser.docs

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A ZIP file's contents read from its central directory, the list at its end (3.0), as file
 * managers do. Before, the whole ZIP was unpacked from start to end just to list it: a big one took
 * so long that the viewer gave up and called it damaged, and a ZIP locked with a password failed
 * outright. Now it lists at once, files locked with a password are known as such, and one file is
 * taken out by reading only its own bytes. Plain logic (tested on the PC); Docs gives it the file.
 */
object ZipIndex {
    class Entry(
        val name: String,
        /** Unpacked size. */
        val size: Long,
        /** Size in the ZIP. */
        val packed: Long,
        val method: Int,
        val flags: Int,
        /** Where its local header starts. */
        val offset: Long,
    ) {
        /** Locked with a password (ZipCrypto or AES): only Files, with the password, can take it out. */
        val encrypted: Boolean get() = flags and 1 != 0
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    /** [total]: how many entries the ZIP holds; [entries] the first ones of them. */
    class Listing(val entries: List<Entry>, val total: Long) {
        val truncated: Boolean get() = total > entries.size
    }

    /** Not a ZIP file, or its list at the end is damaged (another way may still read it). */
    class NotZip(why: String) : IOException(why)

    /** The entry is locked with a password. */
    class Locked : IOException("This file in the ZIP is locked with a password")

    /** Packed in a way Swift doesn't unpack (only stored and deflated are). */
    class Unsupported(method: Int) : IOException("ZIP compression method $method")

    /** Reads at a position: a file on the phone, a byte array in tests. */
    interface Source {
        val size: Long

        /** Up to [len] bytes at [pos] into [buf]; fewer only at the end. */
        fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int
    }

    class ChannelSource(private val ch: FileChannel) : Source {
        override val size: Long get() = ch.size()

        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            val bb = ByteBuffer.wrap(buf, off, len)
            var p = pos
            while (bb.hasRemaining()) {
                val n = ch.read(bb, p)
                if (n <= 0) break
                p += n
            }
            return bb.position() - off
        }
    }

    class BytesSource(private val b: ByteArray) : Source {
        override val size: Long get() = b.size.toLong()

        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= b.size) return 0
            val n = minOf(len.toLong(), b.size - pos).toInt()
            System.arraycopy(b, pos.toInt(), buf, off, n)
            return n
        }
    }

    private const val EOCD = 0x06054b50
    private const val EOCD64 = 0x06064b50
    private const val EOCD64_LOCATOR = 0x07064b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50

    /**
     * The most of the central directory read: about 150,000 entries' worth, far more than the viewer
     * lists. It is read a piece at a time, only as far as the entries listed need.
     */
    private const val MAX_DIRECTORY = 16 * 1_048_576
    private const val FIRST_READ = 256 * 1024

    private val CP437: Charset = Charset.forName("CP437")

    /** The first [max] entries of the ZIP in [src] (the rest are only counted). */
    fun list(src: Source, max: Int): Listing {
        val end = findEnd(src)
        val entries = ArrayList<Entry>(minOf(max, end.count.coerceAtMost(4096L).toInt()))
        val wanted = minOf(end.dirSize, MAX_DIRECTORY.toLong()).toInt()
        var asked = minOf(wanted, FIRST_READ)
        var dir = readDirectory(src, end.dirOffset, asked)
        var at = 0
        var seen = 0L
        while (seen < end.count && entries.size < max) {
            if (at + 46 > dir.size || at + 46 + short(dir, at + 28) + short(dir, at + 30) + short(dir, at + 32) > dir.size) {
                // The next entry goes past what was read: read more of the list, if there is more.
                if (dir.size < asked || asked >= wanted) break
                asked = minOf(wanted, asked * 4)
                dir = readDirectory(src, end.dirOffset, asked)
                continue
            }
            if (int(dir, at) != CENTRAL) throw NotZip("Bad central directory entry")
            val flags = short(dir, at + 8)
            val method = short(dir, at + 10)
            var packed = uint(dir, at + 20)
            var size = uint(dir, at + 24)
            val nameLength = short(dir, at + 28)
            val extraLength = short(dir, at + 30)
            val commentLength = short(dir, at + 32)
            var offset = uint(dir, at + 42)
            val nameAt = at + 46
            val next = nameAt + nameLength + extraLength + commentLength
            // ZIP64: sizes and offsets too big for 4 bytes are in an extra field, in this order.
            if (size == 0xFFFFFFFFL || packed == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
                var e = nameAt + nameLength
                val extraEnd = e + extraLength
                while (e + 4 <= extraEnd) {
                    val id = short(dir, e)
                    val len = short(dir, e + 2)
                    if (id == 0x0001) {
                        var p = e + 4
                        if (size == 0xFFFFFFFFL && p + 8 <= e + 4 + len) size = long(dir, p).also { p += 8 }
                        if (packed == 0xFFFFFFFFL && p + 8 <= e + 4 + len) packed = long(dir, p).also { p += 8 }
                        if (offset == 0xFFFFFFFFL && p + 8 <= e + 4 + len) offset = long(dir, p)
                        break
                    }
                    e += 4 + len
                }
            }
            seen++
            entries += Entry(name(dir, nameAt, nameLength, flags), size, packed, method, flags, offset + end.prefix)
            at = next
        }
        return Listing(entries, maxOf(end.count, seen))
    }

    private fun readDirectory(src: Source, offset: Long, length: Int): ByteArray {
        val b = ByteArray(length)
        val n = src.read(offset, b)
        return if (n == length) b else b.copyOf(n)
    }

    /** The unpacked bytes of [e] (close the stream when done). */
    fun open(src: Source, e: Entry): InputStream {
        if (e.encrypted) throw Locked()
        if (e.method != 0 && e.method != 8) throw Unsupported(e.method)
        val head = ByteArray(30)
        if (src.read(e.offset, head) < 30 || int(head, 0) != LOCAL) throw NotZip("Bad local header")
        val data = e.offset + 30 + short(head, 26) + short(head, 28)
        if (e.packed < 0 || data + e.packed > src.size) throw NotZip("Entry runs past the end")
        val raw = Slice(src, data, e.packed, padding = e.method == 8)
        if (e.method == 0) return raw
        val inflater = Inflater(true)
        // The inflater's memory is outside Java's: let go of it with the stream.
        return object : InflaterInputStream(raw, inflater, 65536) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    inflater.end()
                }
            }
        }
    }

    private class End(val count: Long, val dirSize: Long, val dirOffset: Long, val prefix: Long)

    /** The end of central directory record (and ZIP64's), searched for from the end of the file. */
    private fun findEnd(src: Source): End {
        val size = src.size
        if (size < 22) throw NotZip("Too short")
        val tailLength = minOf(size, 22L + 65535).toInt()
        val tail = ByteArray(tailLength)
        val tailStart = size - tailLength
        if (src.read(tailStart, tail) < tailLength) throw NotZip("Short read")
        // The record whose comment ends the file (a comment may itself hold the record's mark); failing
        // that, the last mark found (some programs add bytes after the comment).
        var at = tailLength - 22
        while (at >= 0 && !(int(tail, at) == EOCD && tailStart + at + 22 + short(tail, at + 20) == size)) at--
        if (at < 0) {
            at = tailLength - 22
            while (at >= 0 && int(tail, at) != EOCD) at--
        }
        if (at < 0) throw NotZip("No end of central directory")
        val eocdPos = tailStart + at
        var count = short(tail, at + 10).toLong()
        var dirSize = uint(tail, at + 12)
        var dirOffset = uint(tail, at + 16)
        if (count == 0xFFFFL || dirSize == 0xFFFFFFFFL || dirOffset == 0xFFFFFFFFL) {
            // ZIP64: its own end record, found through the locator just before.
            val loc = ByteArray(20)
            if (eocdPos >= 20 && src.read(eocdPos - 20, loc) == 20 && int(loc, 0) == EOCD64_LOCATOR) {
                val rec = ByteArray(56)
                val recPos = long(loc, 8)
                if (recPos in 0..(size - 56) && src.read(recPos, rec) == 56 && int(rec, 0) == EOCD64) {
                    count = long(rec, 32).coerceAtLeast(0)
                    dirSize = long(rec, 40)
                    dirOffset = long(rec, 48)
                    val prefix = recPos - dirSize - dirOffset
                    if (dirSize < 0 || dirOffset < 0 || prefix < 0) throw NotZip("Central directory out of place")
                    return End(count, dirSize, dirOffset + prefix, prefix)
                }
            }
        }
        // A ZIP joined to something before it (a self-extracting one): its offsets count from its own start.
        val prefix = eocdPos - dirSize - dirOffset
        if (prefix < 0 || dirSize > eocdPos) throw NotZip("Central directory out of place")
        return End(count, dirSize, dirOffset + prefix, prefix)
    }

    /** Names in UTF-8 when the ZIP says so or they read as UTF-8, else in the DOS code page of old Windows ZIPs. */
    private fun name(b: ByteArray, at: Int, length: Int, flags: Int): String {
        if (flags and 0x800 != 0) return String(b, at, length, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, at, length)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(b, at, length, CP437)
        }
    }

    /** [length] bytes of [src] from [start]; for deflated data one more zero byte, which Inflater wants at the end. */
    private class Slice(private val src: Source, private var pos: Long, length: Long, private val padding: Boolean) : InputStream() {
        private var left = length
        private var padded = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) {
                if (padding && !padded) {
                    padded = true
                    b[off] = 0
                    return 1
                }
                return -1
            }
            val n = src.read(pos, b, off, minOf(len.toLong(), left).toInt())
            if (n <= 0) {
                left = 0
                return read(b, off, len)
            }
            pos += n
            left -= n
            return n
        }
    }

    private fun short(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun int(b: ByteArray, at: Int) = short(b, at) or (short(b, at + 2) shl 16)

    private fun uint(b: ByteArray, at: Int) = int(b, at).toLong() and 0xFFFFFFFFL

    private fun long(b: ByteArray, at: Int) = uint(b, at) or (uint(b, at + 4) shl 32)
}
