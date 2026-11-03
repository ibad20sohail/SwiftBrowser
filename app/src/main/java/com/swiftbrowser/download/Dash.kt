package com.swiftbrowser.download

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import com.swiftbrowser.download.DownloadRules.Why
import java.util.Locale

/**
 * 3.0.2: DASH streams (.mpd), saved the way HLS ones are: the best video (H.264 first, at most
 * 1080p) and its sound become the same parts an HLS stream has (Hls.Media), read with Media3's own
 * MPD parser. MP4 parts only (WebM can't go into an MP4 file); live and copy-protected streams, and
 * streams in several periods (ad breaks), are refused.
 */
@OptIn(UnstableApi::class)
object Dash {
    /** [soundShare]: what the sound is of all the bytes, from the two bit rates (for the progress). */
    class Tracks(val video: Hls.Media, val audio: Hls.Media?, val summary: String, val soundShare: Double = DownloadRules.SOUND_SHARE)

    /**
     * What choosing looks at: a representation's height, bit rate and codecs, and for sound its
     * language and whether the stream marks it as the main one (pure, tested).
     */
    class Choice(val height: Int, val bitrate: Int, val codecs: String?, val language: String? = null, val main: Boolean = false)

    const val SEVERAL_PERIODS = "This stream comes in several parts (with breaks) and can't be saved."
    const val NO_MP4 = "This stream's video is in a kind Swift can't save as MP4."

    /** The video codecs an MP4 file (Android's MediaMuxer) takes, the most widely playable first. */
    private fun videoRank(codecs: String?): Int {
        val c = codecs?.lowercase(Locale.ROOT).orEmpty()
        return when {
            c.startsWith("avc") -> 0
            c.startsWith("hvc") || c.startsWith("hev") -> 1
            else -> -1
        }
    }

    /** The video to save: H.264 before HEVC, the highest up to [maxHeight] (or the smallest above it). */
    fun bestVideo(list: List<Choice>, maxHeight: Int = 1080): Int? {
        val usable = list.indices.filter { videoRank(list[it].codecs) >= 0 }
        if (usable.isEmpty()) return null
        val rank = usable.minOf { videoRank(list[it].codecs) }
        val same = usable.filter { videoRank(list[it].codecs) == rank }
        val fits = same.filter { list[it].height in 1..maxHeight }
        return if (fits.isNotEmpty()) {
            fits.maxWithOrNull(compareBy<Int>({ list[it].height }, { list[it].bitrate }))
        } else {
            same.minWithOrNull(compareBy<Int>({ if (list[it].height > 0) list[it].height else Int.MAX_VALUE }, { list[it].bitrate }))
        }
    }

    /**
     * The sound to save with it: AAC, in the phone's [language] if the stream has it, else the one
     * the stream marks as main, then the best bit rate (a film in five languages keeps yours).
     */
    fun bestAudio(list: List<Choice>, language: String? = null): Int? {
        val want = language?.substringBefore('-')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        fun mine(c: Choice) = want != null && c.language?.substringBefore('-')?.lowercase(Locale.ROOT) == want
        return list.indices.filter { list[it].codecs?.lowercase(Locale.ROOT)?.startsWith("mp4a") == true }
            .maxWithOrNull(compareBy<Int>({ mine(list[it]) }, { list[it].main }, { list[it].bitrate }))
    }

    /** Whether [text] is a DASH manifest. */
    fun isManifest(text: String): Boolean = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t').let { it.startsWith("<?xml") || it.startsWith("<MPD") } && text.contains("<MPD")

    /** Reads [text] (from [url]) as a DASH stream to save. Throws DownloadRules.Failure when it can't be. */
    fun forDownload(text: String, url: String): Tracks {
        val manifest = try {
            DashManifestParser().parse(Uri.parse(url), text.byteInputStream())
        } catch (e: Exception) {
            throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOT_A_VIDEO)
        }
        if (manifest.dynamic) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.LIVE)
        if (manifest.periodCount != 1) throw DownloadRules.Failure(Why.PERMANENT, SEVERAL_PERIODS)
        val period = manifest.getPeriod(0)
        val periodUs = manifest.getPeriodDurationUs(0)
        val videos = period.adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { it.representations }
            .filter { it.format.containerMimeType?.contains("webm") != true }
        val audios = period.adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { it.representations }
            .filter { it.format.containerMimeType?.contains("webm") != true }
        if (videos.isEmpty() && audios.isEmpty()) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOTHING)
        val v = bestVideo(videos.map { Choice(it.format.height, it.format.bitrate, it.format.codecs) })?.let { videos[it] }
            ?: throw DownloadRules.Failure(Why.PERMANENT, NO_MP4)
        val a = bestAudio(
            audios.map { Choice(0, it.format.bitrate, it.format.codecs, it.format.language, it.format.roleFlags and C.ROLE_FLAG_MAIN != 0) },
            Locale.getDefault().language,
        )?.let { audios[it] }
        if (v.format.drmInitData != null || a?.format?.drmInitData != null) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.PROTECTED)
        val video = media(v, periodUs)
        val summary = listOfNotNull(
            if (v.format.height > 0) "${v.format.height}p" else null,
            if (periodUs != C.TIME_UNSET && periodUs > 0) Hls.formatDuration(periodUs / 1e6) else null,
        ).joinToString(", ").ifEmpty { "stream" }
        val share = if (a != null && a.format.bitrate > 0 && v.format.bitrate > 0) {
            a.format.bitrate.toDouble() / (a.format.bitrate + v.format.bitrate)
        } else {
            DownloadRules.SOUND_SHARE
        }
        return Tracks(video, a?.let { media(it, periodUs) }, summary, share)
    }

    /** One representation's parts: its init part and media parts, or its one whole file. */
    private fun media(r: Representation, periodUs: Long): Hls.Media {
        val base = r.baseUrls.firstOrNull()?.url ?: throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOTHING)
        val seconds = if (periodUs != C.TIME_UNSET && periodUs > 0) periodUs / 1e6 else 0.0
        if (r is Representation.SingleSegmentRepresentation) {
            // One file holds it all (its header, index and every part): saved whole, an MP4 already.
            return Hls.Media(listOf(Hls.Segment(base, seconds, null, 0, -1, 0)), true, null, seconds, mp4Parts = true)
        }
        val index = r.index ?: throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.NOTHING)
        val count = index.getSegmentCount(periodUs)
        if (count <= 0) throw DownloadRules.Failure(Why.PERMANENT, DownloadRules.LIVE)
        val first = index.firstSegmentNum
        val parts = (first until first + count).map { n ->
            val u = index.getSegmentUrl(n)
            Hls.Segment(u.resolveUriString(base), index.getDurationUs(n, periodUs) / 1e6, null, u.start, u.length, n)
        }
        val init = r.initializationUri?.let { Hls.Segment(it.resolveUriString(base), 0.0, null, it.start, it.length, -1) }
        return Hls.Media(parts, true, init, seconds, mp4Parts = true)
    }
}
