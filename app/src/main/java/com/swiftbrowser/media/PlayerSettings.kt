package com.swiftbrowser.media

import android.content.Context
import androidx.core.content.edit
import com.swiftbrowser.SwiftApp

/**
 * 2.9: the player's options, kept with Swift's other settings (so a backup carries them). The player
 * reads them each time a video or song opens: a change made in Settings shows from the next one.
 * The player's own menu changes some of them too (subtitle size, turning the screen).
 */
object PlayerSettings {
    const val GESTURES = "player_gestures"
    const val RESUME = "player_resume"
    const val BACKGROUND_AUDIO = "player_background_audio"
    const val SPEED = "player_speed"
    const val SUBTITLE_SIZE = "player_subtitle_size"
    const val SUBTITLE_COLOUR = "player_subtitle_colour"
    const val SUBTITLE_BACKGROUND = "player_subtitle_background"
    const val SUBTITLE_LANGUAGE = "player_subtitle_language"

    /**
     * 3.0.1: subtitle languages that can come first ("" is as before: a file named like the video,
     * else an English one), with their names in Settings.
     */
    val SUBTITLE_LANGUAGES = listOf(
        "" to "Automatic", "en" to "English", "ur" to "Urdu", "hi" to "Hindi", "ar" to "Arabic", "fr" to "French",
        "es" to "Spanish", "de" to "German", "tr" to "Turkish", "pt" to "Portuguese", "id" to "Indonesian",
    )

    /** 2.9.2: the subtitles' colour (white or yellow) and what is behind them. */
    val SUBTITLE_COLOURS = listOf("White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFE34D.toInt())
    val SUBTITLE_BACKGROUNDS = listOf("See-through box", "Dark box", "None")
    const val ROTATION = "player_rotation"
    const val DOUBLE_TAP_SECONDS = "player_double_tap_seconds"

    // 3.0.3: the equalizer (SoundEffects), where the subtitles were dragged to, and hints said once.
    const val EQUALIZER = "player_eq_on"
    const val EQUALIZER_PRESET = "player_eq_preset"
    const val EQUALIZER_LEVELS = "player_eq_levels"
    const val BASS_BOOST = "player_eq_bass"
    const val SUBTITLE_LIFT = "player_subtitle_lift"
    const val HINT_PINCH = "player_hint_pinch"
    const val HINT_TAPS = "player_hint_taps"

    // 3.0.4: night sound (NightSound).
    const val NIGHT_SOUND = "player_night_sound"

    /** As the video is: wide videos in landscape, tall ones upright (either way round). */
    const val ROTATE_AUTO = "auto"
    /** Turns with the phone, even when its auto-rotate is off. */
    const val ROTATE_SENSOR = "sensor"
    const val ROTATE_LANDSCAPE = "landscape"
    const val ROTATE_PORTRAIT = "portrait"

    val ROTATIONS = listOf(ROTATE_AUTO, ROTATE_SENSOR, ROTATE_LANDSCAPE, ROTATE_PORTRAIT)
    val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
    val SUBTITLE_SIZES = intArrayOf(75, 100, 125, 150, 200)
    val DOUBLE_TAP_CHOICES = intArrayOf(5, 10, 15, 30)

    private val sp get() = SwiftApp.prefs.sp

    /** Swipes for brightness, volume and seeking, double taps to skip, holding for 2× speed. */
    var gestures: Boolean
        get() = sp.getBoolean(GESTURES, true)
        set(v) = sp.edit { putBoolean(GESTURES, v) }

    /** Videos (and long recordings) go on where they were left off. Off: nothing new is remembered. */
    var resume: Boolean
        get() = sp.getBoolean(RESUME, true)
        set(v) = sp.edit { putBoolean(RESUME, v) }

    /** Music keeps playing with the screen off or Swift left (off: it pauses, as videos do). */
    var backgroundAudio: Boolean
        get() = sp.getBoolean(BACKGROUND_AUDIO, true)
        set(v) = sp.edit { putBoolean(BACKGROUND_AUDIO, v) }

    /** The speed every video and song starts at (one of [SPEEDS]). */
    var defaultSpeed: Float
        get() = sp.getFloat(SPEED, 1f).coerceIn(0.25f, 4f)
        set(v) = sp.edit { putFloat(SPEED, v.coerceIn(0.25f, 4f)) }

    /** Subtitle text size in percent of the normal size (one of [SUBTITLE_SIZES]). */
    var subtitleSize: Int
        get() = sp.getInt(SUBTITLE_SIZE, 100).coerceIn(50, 300)
        set(v) = sp.edit { putInt(SUBTITLE_SIZE, v.coerceIn(50, 300)) }

    /** 3.0.1: the subtitles shown first, in the video and in files beside it (a code of [SUBTITLE_LANGUAGES]). */
    var subtitleLanguage: String
        get() = sp.getString(SUBTITLE_LANGUAGE, "")?.takeIf { l -> SUBTITLE_LANGUAGES.any { it.first == l } } ?: ""
        set(v) = sp.edit { putString(SUBTITLE_LANGUAGE, v) }

    /** An index of [SUBTITLE_COLOURS]. */
    var subtitleColour: Int
        get() = sp.getInt(SUBTITLE_COLOUR, 0).coerceIn(0, SUBTITLE_COLOURS.size - 1)
        set(v) = sp.edit { putInt(SUBTITLE_COLOUR, v.coerceIn(0, SUBTITLE_COLOURS.size - 1)) }

    /** An index of [SUBTITLE_BACKGROUNDS]. */
    var subtitleBackground: Int
        get() = sp.getInt(SUBTITLE_BACKGROUND, 0).coerceIn(0, SUBTITLE_BACKGROUNDS.size - 1)
        set(v) = sp.edit { putInt(SUBTITLE_BACKGROUND, v.coerceIn(0, SUBTITLE_BACKGROUNDS.size - 1)) }

    /** How the screen turns for videos: [ROTATE_AUTO], [ROTATE_SENSOR], [ROTATE_LANDSCAPE] or [ROTATE_PORTRAIT]. */
    var rotation: String
        get() = sp.getString(ROTATION, ROTATE_AUTO)?.takeIf { it in ROTATIONS } ?: ROTATE_AUTO
        set(v) = sp.edit { putString(ROTATION, if (v in ROTATIONS) v else ROTATE_AUTO) }

    /** How far a double tap on the left or right skips (seconds). */
    var doubleTapSeconds: Int
        get() = sp.getInt(DOUBLE_TAP_SECONDS, 10).coerceIn(5, 60)
        set(v) = sp.edit { putInt(DOUBLE_TAP_SECONDS, v.coerceIn(5, 60)) }

    /** 3.0.3: the equalizer on everything the player plays. */
    var equalizerOn: Boolean
        get() = sp.getBoolean(EQUALIZER, false)
        set(v) = sp.edit { putBoolean(EQUALIZER, v) }

    /** 3.0.3: one of the phone's equalizer presets, or -1 for the user's own levels ([equalizerLevels]). */
    var equalizerPreset: Int
        get() = sp.getInt(EQUALIZER_PRESET, -1).coerceAtLeast(-1)
        set(v) = sp.edit { putInt(EQUALIZER_PRESET, v.coerceAtLeast(-1)) }

    /** 3.0.3: the user's own band levels in millibels, "300,0,-200" (see EqualizerRules). */
    var equalizerLevels: String
        get() = sp.getString(EQUALIZER_LEVELS, "").orEmpty()
        set(v) = sp.edit { putString(EQUALIZER_LEVELS, v) }

    /** 3.0.3: the bass boost, 0 (none) to 1000. */
    var bassBoost: Int
        get() = sp.getInt(BASS_BOOST, 0).coerceIn(0, 1000)
        set(v) = sp.edit { putInt(BASS_BOOST, v.coerceIn(0, 1000)) }

    /** 3.0.3: how far up the subtitles were dragged, as a share of the screen's height (0: their usual place). */
    var subtitleLift: Float
        get() = sp.getFloat(SUBTITLE_LIFT, 0f).let { if (it.isNaN()) 0f else it.coerceIn(-0.1f, 0.9f) }
        set(v) = sp.edit { putFloat(SUBTITLE_LIFT, if (v.isNaN()) 0f else v.coerceIn(-0.1f, 0.9f)) }

    /** 3.0.3: "Pinch to fill the screen" was said. */
    var pinchHintShown: Boolean
        get() = sp.getBoolean(HINT_PINCH, false)
        set(v) = sp.edit { putBoolean(HINT_PINCH, v) }

    /** 3.0.3: "Tap again to skip more" was said. */
    var tapsHintShown: Boolean
        get() = sp.getBoolean(HINT_TAPS, false)
        set(v) = sp.edit { putBoolean(HINT_TAPS, v) }

    /** 3.0.4: night sound on everything the player plays: loud parts quieter, quiet voices louder. */
    var nightSound: Boolean
        get() = sp.getBoolean(NIGHT_SOUND, false)
        set(v) = sp.edit { putBoolean(NIGHT_SOUND, v) }

    fun rotationLabel(key: String) = when (key) {
        ROTATE_SENSOR -> "Turn with your phone"
        ROTATE_LANDSCAPE -> "Always sideways"
        ROTATE_PORTRAIT -> "Always upright"
        else -> "Automatic (as the video is)"
    }

    /** "1×", "1.25×", "0.5×". */
    fun speedLabel(speed: Float): String {
        val s = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')
        return "$s×"
    }

    /** Forgets every place videos and songs were left off (the home page's "Continue" loses them too). */
    fun clearPositions(ctx: Context) = LocalMedia.clear(ctx)
}
