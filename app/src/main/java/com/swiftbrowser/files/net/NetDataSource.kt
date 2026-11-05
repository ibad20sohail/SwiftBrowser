package com.swiftbrowser.files.net

import android.content.Context
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
import java.io.InputStream

/**
 * 3.0.4: a file of a network folder as the player knows it: swift-smb://<server's id>/<share>/<path>.
 * The address says which saved server, never where it is or who signs in.
 */
object NetUri {
    const val SCHEME = "swift-smb"

    fun of(serverId: String, share: String, path: String): Uri = Uri.Builder().scheme(SCHEME).authority(serverId).appendPath(share)
        .apply { NetPath.segments(path).forEach { appendPath(it) } }.build()

    fun isNet(uri: Uri?): Boolean = uri?.scheme == SCHEME

    class Place(val serverId: String, val share: String, val path: String)

    fun place(uri: Uri): Place? {
        if (!isNet(uri)) return null
        val id = uri.authority ?: return null
        val parts = uri.pathSegments
        if (parts.isEmpty()) return null
        return Place(id, parts[0], parts.drop(1).joinToString("/"))
    }
}

/**
 * 3.0.4: Swift's player reads a network folder's videos and songs straight from the share, where it
 * needs them (seeking opens the file again at the new place), so nothing is copied first. smbj reads
 * a megabyte at a time, the next one already on its way. Its errors are Media3's usual kinds, so the
 * player says them as for other files.
 */
@OptIn(UnstableApi::class)
class NetDataSource private constructor(private val app: Context) : BaseDataSource(true) {
    /**
     * Files of network folders through [NetDataSource], everything else through [other] (Media3's own
     * sources: files on the phone, other apps' links, website videos).
     */
    class Factory(ctx: Context, private val other: DataSource.Factory) : DataSource.Factory {
        private val app = ctx.applicationContext

        override fun createDataSource(): DataSource = Either(NetDataSource(app), other.createDataSource())
    }

    /** Hands each opening to the source for its address. */
    private class Either(private val net: NetDataSource, private val other: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            net.addTransferListener(transferListener)
            other.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val ds = if (NetUri.isNet(dataSpec.uri)) net else other
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

    private var file: com.hierynomus.smbj.share.File? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var guest = false

    /** Bytes left to read. */
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val place = NetUri.place(dataSpec.uri) ?: throw problem(NetFailure(NetProblem.NOT_FOUND))
        val server = NetServers.get(app, place.serverId) ?: throw problem(NetFailure(NetProblem.NOT_FOUND))
        guest = server.guest
        val f = try {
            NetClient.open(app, server, place.share, place.path)
        } catch (e: Exception) {
            throw problem(e)
        }
        file = f
        val size = try {
            NetClient.sizeOf(f)
        } catch (e: Exception) {
            throw problem(e)
        }
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        if (dataSpec.position > size || left < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        val stream = f.inputStream
        // smbj's stream starts its reads at the new place (nothing before it is read).
        if (dataSpec.position > 0) stream.skip(dataSpec.position)
        input = stream
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = try {
            (input ?: return C.RESULT_END_OF_INPUT).read(buffer, offset, minOf(left, length.toLong()).toInt())
        } catch (e: Exception) {
            throw problem(e)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { input?.close() }
        input = null
        val f = file
        file = null
        // Without waiting for the computer's answer: a seek opens the file again at once.
        if (f != null) runCatching { f.closeNoWait() }
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Media3's kind of error for what went wrong; the cause names no address or path (they may be logged). */
    private fun problem(e: Throwable): DataSourceException {
        val p = (e as? NetFailure)?.problem ?: NetErrors.of(e, guest)
        val reason = when (p) {
            NetProblem.NOT_FOUND, NetProblem.NO_SHARE -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            NetProblem.ACCESS, NetProblem.LOGON, NetProblem.ACCOUNT, NetProblem.NO_GUESTS, NetProblem.PASSWORD -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            NetProblem.UNREACHABLE, NetProblem.UNKNOWN_HOST -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        }
        return DataSourceException(NetFailure(p), reason)
    }
}
