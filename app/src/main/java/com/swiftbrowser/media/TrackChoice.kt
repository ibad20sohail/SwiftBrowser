package com.swiftbrowser.media

import org.json.JSONObject
import java.util.Locale

/**
 * 3.0.3: the sound and subtitles chosen for a file on the phone, kept with its timing (LocalMedia)
 * and chosen again the next time it plays: a track by its language and name, subtitles off, or a
 * subtitle file (its path, or the content:// address another app gave it by).
 */
data class TrackChoice(
    val soundLanguage: String? = null,
    val soundLabel: String? = null,
    val subtitlesOff: Boolean = false,
    val subtitleLanguage: String? = null,
    val subtitleLabel: String? = null,
    val subtitleFile: String? = null,
) {
    val hasSound: Boolean get() = soundLanguage != null || soundLabel != null

    /** The video's own subtitles by language or name (not off, not a file). */
    val hasOwnSubtitles: Boolean get() = !subtitlesOff && subtitleFile == null && (subtitleLanguage != null || subtitleLabel != null)

    val hasSubtitles: Boolean get() = subtitlesOff || subtitleFile != null || hasOwnSubtitles

    val isEmpty: Boolean get() = !hasSound && !hasSubtitles

    fun withSound(language: String?, label: String?) = copy(soundLanguage = clean(language), soundLabel = clean(label))

    fun withSubtitles(language: String?, label: String?) =
        copy(subtitlesOff = false, subtitleLanguage = clean(language), subtitleLabel = clean(label), subtitleFile = null)

    fun withSubtitleFile(path: String) = copy(subtitlesOff = false, subtitleLanguage = null, subtitleLabel = null, subtitleFile = path)

    fun withSubtitlesOff() = copy(subtitlesOff = true, subtitleLanguage = null, subtitleLabel = null, subtitleFile = null)

    fun toJson(): JSONObject = JSONObject().apply {
        soundLanguage?.let { put("sl", it) }
        soundLabel?.let { put("sn", it) }
        if (subtitlesOff) put("off", true)
        subtitleLanguage?.let { put("tl", it) }
        subtitleLabel?.let { put("tn", it) }
        subtitleFile?.let { put("tf", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): TrackChoice = TrackChoice(
            soundLanguage = clean(o.optString("sl")),
            soundLabel = clean(o.optString("sn")),
            subtitlesOff = o.optBoolean("off"),
            subtitleLanguage = clean(o.optString("tl")),
            subtitleLabel = clean(o.optString("tn")),
            subtitleFile = o.optString("tf").takeIf { it.isNotBlank() },
        )

        /** A language or name as kept: blank and "und" (not known) are none. */
        fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }

        /**
         * Which of [tracks] (each a language and a name, as ExoPlayer gives them) is the one kept: the
         * same language and name, else the first in that language (a file whose track names changed),
         * else, with no language kept, the first of that name. -1 for none.
         */
        fun match(language: String?, label: String?, tracks: List<Pair<String?, String?>>): Int {
            val lang = clean(language)?.lowercase(Locale.ROOT)
            val name = clean(label)
            if (lang == null && name == null) return -1
            fun langOf(i: Int) = clean(tracks[i].first)?.lowercase(Locale.ROOT)
            fun nameOf(i: Int) = clean(tracks[i].second)
            tracks.indices.firstOrNull { langOf(it) == lang && nameOf(it) == name }?.let { return it }
            val fallback = if (lang != null) tracks.indices.firstOrNull { langOf(it) == lang } else tracks.indices.firstOrNull { nameOf(it) == name }
            return fallback ?: -1
        }
    }
}
