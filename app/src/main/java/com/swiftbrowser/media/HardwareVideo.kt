package com.swiftbrowser.media

import android.media.MediaCodecList

/**
 * 3.0.1: whether the phone's chip decodes a video, not just whether it can be decoded at all.
 * Android's own decoders (OMX.google, c2.android) work in software: on a phone like this one big or
 * fast videos stutter there, and the player falls back to them without saying.
 */
object HardwareVideo {
    enum class Fit { CHIP, SOFTWARE_ONLY, TOO_MUCH, UNKNOWN }

    /** [answers]: for each chip decoder of the video's type, whether it takes its size and frame rate (null: it didn't say). */
    fun fit(answers: List<Boolean?>): Fit = when {
        answers.isEmpty() -> Fit.SOFTWARE_ONLY
        answers.any { it == true } -> Fit.CHIP
        answers.all { it == false } -> Fit.TOO_MUCH
        else -> Fit.UNKNOWN
    }

    /** What the viewer is told, or null when nothing needs saying. */
    fun message(fit: Fit, width: Int, height: Int): String? = when (fit) {
        Fit.SOFTWARE_ONLY -> "Your phone can't play this kind of video on its own chip, so it may stutter. Another version of it may play smoothly."
        Fit.TOO_MUCH -> "This video ($width × $height) is more than your phone is made for, so it may stutter. A smaller version would play smoothly."
        Fit.CHIP, Fit.UNKNOWN -> null
    }

    private fun software(name: String) = name.startsWith("OMX.google.") || name.startsWith("c2.android.")

    /** Blocking (off the main thread): the hint for a video of [mime], [width] × [height] at [fps] (0 or less: not known). */
    fun hint(mime: String?, width: Int, height: Int, fps: Float): String? {
        if (mime == null || !mime.startsWith("video/") || width <= 0 || height <= 0) return null
        val answers = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && !software(it.name) && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .map { info ->
                    try {
                        val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@map null
                        // Upright videos too: a decoder may give its sizes the other way round.
                        fun takes(w: Int, h: Int) = if (fps > 0) caps.areSizeAndRateSupported(w, h, fps.toDouble()) else caps.isSizeSupported(w, h)
                        takes(width, height) || takes(height, width)
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (_: Exception) {
            return null
        }
        return message(fit(answers), width, height)
    }
}
