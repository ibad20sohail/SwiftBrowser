package com.swiftbrowser.download

import java.io.IOException
import java.util.Locale

/**
 * 3.0: the plain rules of downloads, kept apart from Android so they are tested on the PC: what went
 * wrong in words people use, whether trying again can help, what a site's answer to a resumed
 * download means, and when a saved stream may become an MP4.
 */
object DownloadRules {
    /** 3.0.1: a download asked for again from the same address (see [again]). */
    enum class Again { NEW, BUSY, DONE }

    /**
     * What to do when an address is downloaded again in the same profile (and incognito or not):
     * [status] of the earlier download (null: none), [fileThere]: its file still exists. One still
     * going is shown instead of a second copy; a finished one is offered to open; else a new one.
     */
    fun again(status: Int?, fileThere: Boolean): Again = when (status) {
        null -> Again.NEW
        Downloads.QUEUED, Downloads.RUNNING, Downloads.PAUSED, Downloads.CONVERTING -> Again.BUSY
        Downloads.DONE -> if (fileThere) Again.DONE else Again.NEW
        else -> Again.NEW
    }
    /** Why a download stopped, as far as trying again goes. */
    enum class Why {
        /** The connection (or a busy site): worth trying again by itself, a few times. */
        NETWORK,

        /** The site said no (an expired link, a file gone): only its page can start it again. */
        REFUSED,

        /** The phone's storage is full. */
        FULL,

        /** It can never be saved: a live stream, copy protection, not a video. */
        PERMANENT,

        OTHER,
    }

    /** A failure already put in words for the Downloads list. */
    class Failure(val why: Why, message: String) : IOException(message)

    class Problem(val why: Why, val text: String) {
        /** Worth a ↻ (what the site refused needs its page, and some things can never work). */
        val retryable: Boolean get() = why != Why.PERMANENT && why != Why.REFUSED
    }

    const val LIVE = "This is a live stream: it can't be saved."
    const val PROTECTED = "This video is copy-protected and can't be saved."
    const val NOT_A_VIDEO = "This isn't a video Swift can save."
    const val NOTHING = "The video has nothing Swift can save."
    const val LOST = "The connection was lost."
    const val FULL = "The phone is full."

    /** Automatic tries after the connection failed. */
    const val TRIES = 3

    /** "Retrying (2 of 3)". */
    fun retrying(attempt: Int) = "Retrying ($attempt of $TRIES)"

    /** What [e] means, in plain words. */
    fun explain(e: Throwable): Problem {
        if (e is Failure) return Problem(e.why, e.message ?: "Something went wrong.")
        val chain = generateSequence(e) { it.cause }.take(5).toList()
        if (chain.any { it is java.net.SocketException || it is java.net.SocketTimeoutException || it is java.net.UnknownHostException || it is javax.net.ssl.SSLException || it is java.io.EOFException }) {
            // A full disk shows as a write error, never as one of these.
            if (chain.none { it.message?.contains("ENOSPC") == true }) return Problem(Why.NETWORK, LOST)
        }
        return reword(chain.mapNotNull { it.message }.joinToString(" "))
    }

    /** The words of an error ([explain]'s, or a row's from before 3.0, which kept raw ones). */
    fun reword(error: String?): Problem {
        val e = error?.lowercase(Locale.ROOT).orEmpty()
        val code = HTTP_CODE.find(e)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            "enospc" in e || "no space left" in e -> Problem(Why.FULL, FULL)
            "eacces" in e || "permission denied" in e || "erofs" in e || "read-only file system" in e ->
                Problem(Why.OTHER, "Swift can't write to the download folder.")
            "live stream" in e -> Problem(Why.PERMANENT, LIVE)
            "copy-protected" in e || "drm" in e -> Problem(Why.PERMANENT, PROTECTED)
            "not a video" in e || "isn't a video" in e -> Problem(Why.PERMANENT, NOT_A_VIDEO)
            "empty playlist" in e || "no streams" in e || "nothing swift can save" in e -> Problem(Why.PERMANENT, NOTHING)
            code != null -> http(code)
            "invalid key" in e || "missing key" in e || "pad block" in e || "badpadding" in e || "bad padding" in e || "decrypt" in e ->
                Problem(Why.REFUSED, "The video's key didn't work. Start it again from its page.")
            "too large" in e -> Problem(Why.OTHER, "A part of it was too big to save.")
            NETWORK_WORDS.any { it in e } -> Problem(Why.NETWORK, LOST)
            // Already in plain words (a 3.0 row read again).
            e.endsWith(".") && error != null && error.first().isUpperCase() -> Problem(Why.OTHER, error)
            else -> Problem(Why.OTHER, "Something went wrong.")
        }
    }

    private val HTTP_CODE = Regex("(?:server error|segment error|expired \\(|error \\()\\s*(\\d{3})")
    private val NETWORK_WORDS = listOf(
        "timed out", "timeout", "network error", "no response", "no answer", "connection", "reset", "unreachable",
        "closed early", "unexpected end", "failed to connect", "unknown host", "socket", "ssl", "eof",
    )

    /** What a site's answer [code] means for a download. */
    fun http(code: Int): Problem = when (code) {
        401, 403 -> Problem(Why.REFUSED, "The site refused — the link may have expired. Start it again from its page.")
        404, 410 -> Problem(Why.REFUSED, "The file isn't on the site any more.")
        408, 429 -> Problem(Why.NETWORK, "The site is busy.")
        in 500..599 -> Problem(Why.NETWORK, "The site had a problem.")
        else -> Problem(Why.REFUSED, "The site answered with an error ($code).")
    }

    fun failure(code: Int): Failure = http(code).let { Failure(it.why, it.text) }

    /** Before 3.0 a row kept only the error's words: whether those were about the connection. */
    fun legacyNetwork(error: String?): Boolean = error == null || reword(error).why == Why.NETWORK

    // ------------------------------------------------------------------ going on with a download

    /** What the site's answer to a resumed download means. */
    enum class Resume {
        /** The rest of the file, from where it stopped. */
        APPEND,

        /** Not the rest of it (the file changed, or the site can't send parts): the whole file again. */
        RESTART,

        /** There was nothing left to send: it is all there. */
        DONE,

        FAIL,
    }

    /**
     * The answer [status] (with its Content-Range [contentRange]) to asking for the file from byte
     * [offset] on, with If-Range: a part that doesn't start where it stopped is never mixed in.
     */
    fun resume(offset: Long, status: Int, contentRange: String?, knownTotal: Long): Resume = when {
        status == 206 -> if (offset > 0 && rangeStart(contentRange) == offset) Resume.APPEND else Resume.RESTART
        status in 200..299 -> Resume.RESTART
        status == 416 -> {
            val total = rangeTotal(contentRange) ?: knownTotal.takeIf { it > 0 }
            if (total != null && offset == total) Resume.DONE else Resume.RESTART
        }
        else -> Resume.FAIL
    }

    private val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

    /** "bytes 100-199/1000" → 100 (null when it only gives the size, or there is none). */
    fun rangeStart(contentRange: String?): Long? = contentRange?.trim()?.let { CONTENT_RANGE.find(it) }?.groupValues?.get(1)?.toLongOrNull()

    /** The whole file's size from a Content-Range ("…/1000"), when it says. */
    fun rangeTotal(contentRange: String?): Long? = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    /** If-Range's value: a strong ETag (weak ones don't count there), else the date it last changed. */
    fun ifRange(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: lastModified?.trim()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ waiting for Wi-Fi

    /** "Big downloads wait for Wi-Fi" counts files over this. */
    const val BIG = 50L * 1024 * 1024

    /** Whether a download waits for Wi-Fi: a stream or a video, or a file over 50 MB (known or not yet). */
    fun mustWait(wifiOnly: Boolean, onMobileData: Boolean, hls: Boolean, total: Long, done: Long, mime: String?): Boolean =
        wifiOnly && onMobileData && (hls || total > BIG || (total <= 0 && (done > BIG || mime?.startsWith("video/") == true)))

    // ------------------------------------------------------------------ streams (HLS) into MP4

    enum class Convert { OK, TOO_BIG, NO_ROOM }

    /** Android 7's MP4 writer can't go past 4 GB (its offsets are 32 bits): those stay .ts. */
    const val MP4_LIMIT = 4_000_000_000L

    /** Room kept free beyond the copy while converting. */
    const val CONVERT_SPARE = 50L * 1024 * 1024

    /** Whether [bytes] of stream may be converted with [free] bytes free (-1: unknown) where it is. */
    fun convert(bytes: Long, free: Long): Convert = when {
        bytes > MP4_LIMIT -> Convert.TOO_BIG
        free >= 0 && free < bytes + CONVERT_SPARE -> Convert.NO_ROOM
        else -> Convert.OK
    }

    /**
     * The MP4 is whole: about as long as [expectedUs] (what was written into it, or the playlist
     * said), a few seconds or 3% short at most. A disk that filled up while writing leaves an MP4
     * without its index (no length at all) or a short one.
     */
    fun lengthOk(expectedUs: Long, actualUs: Long): Boolean =
        actualUs > 0 && (expectedUs <= 0 || actualUs >= expectedUs - maxOf(2_000_000L, expectedUs / 33))

    /** Every part of a stream is on disk ([saved] bytes of the [fileBytes] the file has): nothing to fetch any more. */
    fun streamSaved(seg: Int, segTotal: Int, audioSeg: Int, audioTotal: Int, fileBytes: Long, saved: Long): Boolean =
        segTotal > 0 && seg >= segTotal && audioSeg >= audioTotal && saved > 0 && fileBytes >= saved
}
