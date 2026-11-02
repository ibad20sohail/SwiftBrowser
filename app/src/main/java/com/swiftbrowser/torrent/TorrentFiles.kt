package com.swiftbrowser.torrent

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.ui.PlayerActivity
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.Locale

/**
 * 2.9.2: what Swift knows about the files inside a torrent: which are junk (left unticked when it is
 * added), which are Windows programs (a warning), which is the film (Play), and whether a video
 * looks like 10-bit HEVC, which this phone's chip can't decode.
 */
object TorrentFiles {
    /** A file of a torrent: [index] in the torrent, [path] inside the download folder. */
    class Entry(val index: Int, val path: String, val name: String, val size: Long, val junk: Boolean, val program: Boolean) {
        val video get() = PlayerActivity.kindOf(name) == PlayerActivity.Kind.VIDEO
    }

    /** Text files, links and programs: what torrent sites add for advertising, never the download itself. */
    private val JUNK = setOf("txt", "nfo", "url", "lnk", "website", "htm", "html")
    /** Windows programs: they can't run on a phone and in a film's torrent they are often malware. */
    private val PROGRAMS = setOf("exe", "bat", "cmd", "scr", "msi", "vbs", "pif")
    private val SAMPLE = Regex("(?i)(^|[^a-z])sample([^a-z]|$)")
    private val TEN_BIT = Regex("(?i)(10[ ._-]?bit|hi10p?|main ?10)")

    /** Public trackers that have been up for years (some may be down: a dead one is simply skipped). */
    val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://open.tracker.cl:1337/announce",
    )

    /** The files of [ti] as the add panel lists them (padding files, which hold nothing, left out). */
    fun list(ti: TorrentInfo): List<Entry> {
        val fs = ti.files()
        var biggestVideo = 0L
        for (i in 0 until fs.numFiles()) {
            if (!fs.padFileAt(i) && PlayerActivity.kindOf(fs.fileName(i)) == PlayerActivity.Kind.VIDEO) biggestVideo = maxOf(biggestVideo, fs.fileSize(i))
        }
        val out = ArrayList<Entry>(fs.numFiles())
        for (i in 0 until fs.numFiles()) {
            if (fs.padFileAt(i)) continue
            val name = fs.fileName(i)
            val size = fs.fileSize(i)
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val program = ext in PROGRAMS
            // "Film.Sample.mkv" next to the film: a short clip of it.
            val sample = PlayerActivity.kindOf(name) == PlayerActivity.Kind.VIDEO && SAMPLE.containsMatchIn(name) && size < biggestVideo / 5
            out += Entry(i, fs.filePath(i), name, size, junk = program || ext in JUNK || sample, program = program)
        }
        return out
    }

    /** Every file wanted but the junk (and the padding), for a torrent added without choosing. */
    fun defaultPriorities(ti: TorrentInfo): Array<Priority> {
        val wanted = list(ti).filter { !it.junk }.map { it.index }.toSet()
        return Array(ti.numFiles()) { if (it in wanted || wanted.isEmpty()) Priority.DEFAULT else Priority.IGNORE }
    }

    /** What Play opens: the biggest video of the files wanted, else the biggest file. */
    fun main(entries: List<Entry>, wanted: (Entry) -> Boolean): Entry? {
        val chosen = entries.filter(wanted)
        return chosen.filter { it.video }.maxByOrNull { it.size } ?: chosen.maxByOrNull { it.size }
    }

    fun looks10Bit(name: String) = TEN_BIT.containsMatchIn(name)

    /**
     * The phone's chip decodes 10-bit HEVC. Android's own decoders (OMX.google, c2.android) don't
     * count: in software it is far too slow for this phone.
     */
    val phonePlays10Bit: Boolean by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") &&
                    info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                    info.getCapabilitiesForType("video/hevc").profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The name a magnet link gives its download (dn=), if any. 3.0: as a file name (no folders, no
     * hidden characters that turn text round), the way the row shows it.
     */
    fun magnetName(magnet: String): String? = try {
        Uri.parse(magnet.replaceFirst("magnet:?", "magnet:/?")).getQueryParameter("dn")?.takeIf { it.isNotBlank() }?.let { UrlUtils.sanitizeFileName(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * 3.0: [name] (a link's dn=, or a torrent's own name) as one file or folder name inside the
     * torrent's folder: never a path up and out of it ("../../WhatsApp/…"), never empty or "..".
     */
    fun safeName(name: String, fallback: String): String =
        // Separators become "_", and dots at either end go (so ".." is never left).
        UrlUtils.sanitizeFileName(name.ifBlank { fallback })

    /** 3.0: [f] is [folder] itself or inside it, once ".." and links are resolved (before anything is deleted). */
    fun within(folder: File, f: File): Boolean {
        val root = runCatching { folder.canonicalPath }.getOrNull() ?: return false
        val path = runCatching { f.canonicalPath }.getOrNull() ?: return false
        return isWithin(root, path, File.separatorChar)
    }

    /** [path] is [root] or under it (both already resolved). */
    fun isWithin(root: String, path: String, separator: Char = '/'): Boolean {
        val r = root.trimEnd(separator)
        return r.isNotEmpty() && (path == r || path.startsWith(r + separator))
    }

    /**
     * A magnet link's info hash in lowercase hex (from its xt=urn:btih: part, hex or base32), without
     * starting libtorrent: the Downloads screen checks copied links with it.
     */
    fun magnetHash(magnet: String): String? {
        val m = Regex("(?i)xt=urn:btih:([a-z0-9]+)").find(magnet) ?: return null
        val v = m.groupValues[1]
        return when (v.length) {
            40 -> v.lowercase(Locale.ROOT).takeIf { s -> s.all { it in '0'..'9' || it in 'a'..'f' } }
            32 -> base32ToHex(v.uppercase(Locale.ROOT))
            else -> null
        }
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format((buffer shr bits) and 0xff))
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}
