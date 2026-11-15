package com.swiftbrowser.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.swiftbrowser.torrent.TorrentStream
import com.swiftbrowser.torrent.Torrents
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * 3.0.11: a torrent's file watched while it downloads, as the player knows it:
 * swift-torrent://<info hash>/<file index>/file.<ending>. The ending lets ExoPlayer try the right
 * reader first; the address names no file or folder.
 */
object TorrentUri {
    const val SCHEME = "swift-torrent"

    fun of(hash: String, index: Int, ending: String): Uri =
        Uri.Builder().scheme(SCHEME).authority(hash).appendPath(index.toString()).appendPath("file.$ending").build()

    fun isTorrent(uri: Uri?): Boolean = uri?.scheme == SCHEME

    /** The info hash and file index of [uri], or null. */
    fun place(uri: Uri): Pair<String, Int>? {
        if (!isTorrent(uri)) return null
        val hash = uri.authority ?: return null
        val index = uri.pathSegments.firstOrNull()?.toIntOrNull() ?: return null
        return hash to index
    }
}

/**
 * 3.0.11: Swift's player reads a torrent's file being watched (Watch now) straight from where
 * libtorrent saves it, but only the pieces that are in: a read that needs a missing piece waits
 * for it (torrent.PieceWaiter) while libtorrent is asked for it first, so the player shows that it
 * is loading, as for a slow website video. A seek opens the file again somewhere else, and the
 * pieces from there are asked for at once. It waits as long as it takes: the player stops a read
 * it no longer needs (a seek, closing), and when the torrent is removed a waiting read fails as for
 * a file that isn't there any more.
 */
@OptIn(UnstableApi::class)
class TorrentDataSource private constructor() : BaseDataSource(false) {
    /** Torrent files being watched through [TorrentDataSource], everything else through [other]. */
    class Factory(private val other: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Either(TorrentDataSource(), other.createDataSource())
    }

    /** Hands each opening to the source for its address (as files.net.NetDataSource does). */
    private class Either(private val torrent: TorrentDataSource, private val other: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            torrent.addTransferListener(transferListener)
            other.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val ds = if (TorrentUri.isTorrent(dataSpec.uri)) torrent else other
            current = ds
            return ds.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: throw IllegalStateException("Not opened")

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            val c = current
            current = null
            c?.close()
        }
    }

    private var stream: TorrentStream? = null
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null

    /** Where the next read starts in the file, and how many bytes are left to read. */
    private var position = 0L
    private var left = 0L

    /** Where the open file's own pointer is (-1: not known), so a read in a row needs no seek. */
    private var filePointer = -1L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val place = TorrentUri.place(dataSpec.uri) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val s = Torrents.stream(place.first, place.second)
        if (s == null || s.gone) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val size = s.map.fileSize
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        val rest = size - dataSpec.position
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, rest) else rest
        position = dataSpec.position
        filePointer = -1
        stream = s
        // A seek: the pieces from there are asked for now, before the first read waits for them.
        if (left > 0) s.readingAt(position)
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: return C.RESULT_END_OF_INPUT
        val want = minOf(left, length.toLong())
        var ready: Long
        while (true) {
            ready = try {
                s.waiter.await(position, want, WAIT_MS)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                // Removed while the player waited.
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            }
            if (ready != 0L) break
        }
        if (ready < 0) return C.RESULT_END_OF_INPUT
        val n = try {
            val f = file ?: RandomAccessFile(s.file, "r").also { file = it }
            if (filePointer != position) f.seek(position)
            f.read(buffer, offset, ready.toInt())
        } catch (e: IOException) {
            filePointer = -1
            throw DataSourceException(e, if (s.file.exists()) PlaybackException.ERROR_CODE_IO_UNSPECIFIED else PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        // A piece that is in is in the file: shorter means the file was taken away meanwhile.
        if (n <= 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        position += n
        filePointer = position
        left -= n
        s.position = position
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        stream = null
        val f = file
        file = null
        filePointer = -1
        try {
            f?.close()
        } catch (_: IOException) {
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        /** A read waits up to this long at a time, and then on, for as long as the player keeps it. */
        const val WAIT_MS = 1_000L
    }
}
