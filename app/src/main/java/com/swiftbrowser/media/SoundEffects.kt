package com.swiftbrowser.media

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.swiftbrowser.core.L
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 3.0.3: the player's equalizer and bass boost (⋮ → Equalizer): Android's own effects on the
 * player's sound (its audio session), as the volume boost is. The choices are kept in
 * PlayerSettings and apply to everything the player plays. The effects exist only while the
 * equalizer is on; a phone whose sound can't take them doesn't show the option.
 *
 * Everything here runs on the main thread.
 */
object SoundEffects {
    /** What the phone's equalizer offers: its bands (levels in millibels, middles in milliHertz) and presets. */
    class Info(
        val bands: Int,
        val minLevel: Int,
        val maxLevel: Int,
        val centres: IntArray,
        val presets: List<String>,
        /** Each preset's band levels, so they show without the equalizer on. */
        val presetLevels: List<IntArray>,
        val bass: Boolean,
    )

    private var session = 0
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var info: Info? = null
    private var tried = false

    /** The player's sound session: the effects go onto it (again, when it changed). */
    fun attach(sessionId: Int) {
        if (sessionId == session) return
        drop()
        session = sessionId
        apply()
    }

    /** The player is gone. */
    fun release() {
        drop()
        session = 0
    }

    /** What the phone's equalizer offers, or null when it has none (asked once). */
    fun info(): Info? {
        if (tried || session == 0) return info
        tried = true
        val own = eq
        val e = own ?: try {
            Equalizer(0, session)
        } catch (t: Throwable) {
            L.w("Player: no equalizer on the phone", t)
            return null
        }
        info = try {
            val range = e.bandLevelRange
            val n = e.numberOfBands.toInt()
            val presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
            // Each preset's levels, read by trying it (put back as it was after).
            val before = IntArray(n) { e.getBandLevel(it.toShort()).toInt() }
            val presetLevels = presets.indices.map { p ->
                e.usePreset(p.toShort())
                IntArray(n) { e.getBandLevel(it.toShort()).toInt() }
            }
            before.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            Info(n, range[0].toInt(), range[1].toInt(), IntArray(n) { e.getCenterFreq(it.toShort()) }, presets, presetLevels, bassSupported())
        } catch (t: Throwable) {
            L.w("Player: the equalizer didn't say what it offers", t)
            null
        } finally {
            if (own == null) runCatching { e.release() }
        }
        return info
    }

    private fun bassSupported(): Boolean {
        val b = bass ?: return try {
            BassBoost(0, session).let { probe ->
                try {
                    probe.strengthSupported
                } finally {
                    probe.release()
                }
            }
        } catch (_: Throwable) {
            false
        }
        return runCatching { b.strengthSupported }.getOrDefault(false)
    }

    /** The band levels in use now (the preset's, or the user's own), within the phone's range. */
    fun levels(): IntArray {
        val i = info ?: return IntArray(0)
        val preset = PlayerSettings.equalizerPreset
        return i.presetLevels.getOrNull(preset)?.copyOf() ?: EqualizerRules.levels(PlayerSettings.equalizerLevels, i.bands, i.minLevel, i.maxLevel)
    }

    fun setOn(on: Boolean) {
        PlayerSettings.equalizerOn = on
        apply()
    }

    /** One of the phone's presets ([index]), or -1 for the user's own levels. */
    fun usePreset(index: Int) {
        PlayerSettings.equalizerPreset = index
        PlayerSettings.equalizerOn = true
        apply()
    }

    /**
     * A band moved: the user's own levels from now on. Kept when the finger lets go ([save]), and at
     * once when it leaves a preset or turns the equalizer on.
     */
    fun setBand(band: Int, level: Int, save: Boolean) {
        val i = info ?: return
        val levels = levels()
        if (band !in levels.indices) return
        levels[band] = level.coerceIn(i.minLevel, i.maxLevel)
        val wasOn = PlayerSettings.equalizerOn
        if (save || PlayerSettings.equalizerPreset >= 0 || !wasOn) {
            PlayerSettings.equalizerLevels = EqualizerRules.format(levels)
            PlayerSettings.equalizerPreset = -1
            PlayerSettings.equalizerOn = true
        }
        val e = eq
        if (e == null || !wasOn) {
            apply()
            return
        }
        try {
            e.setBandLevel(band.toShort(), levels[band].toShort())
        } catch (t: Throwable) {
            L.w("Player: equalizer band", t)
        }
    }

    /** The bass boost, 0 (none) to 1000; kept when the finger lets go ([save]). */
    fun setBass(strength: Int, save: Boolean) {
        val s = strength.coerceIn(0, 1000)
        val b = bass
        if (!save && b != null && PlayerSettings.equalizerOn) {
            try {
                b.setStrength(s.toShort())
            } catch (t: Throwable) {
                L.w("Player: bass boost", t)
            }
            return
        }
        PlayerSettings.bassBoost = s
        if (s > 0) PlayerSettings.equalizerOn = true
        apply()
    }

    /** The choices onto the effects: made, changed or let go. */
    fun apply() {
        if (session == 0) return
        if (!PlayerSettings.equalizerOn) {
            drop()
            return
        }
        try {
            val e = eq ?: Equalizer(0, session).also { eq = it }
            val i = info() ?: run {
                drop()
                return
            }
            val preset = PlayerSettings.equalizerPreset
            if (preset in i.presets.indices) {
                e.usePreset(preset.toShort())
            } else {
                EqualizerRules.levels(PlayerSettings.equalizerLevels, i.bands, i.minLevel, i.maxLevel).forEachIndexed { band, level ->
                    e.setBandLevel(band.toShort(), level.toShort())
                }
            }
            e.enabled = true
        } catch (t: Throwable) {
            L.w("Player: equalizer", t)
            runCatching { eq?.release() }
            eq = null
        }
        val strength = PlayerSettings.bassBoost
        try {
            if (strength > 0 && info?.bass == true) {
                val b = bass ?: BassBoost(0, session).also { bass = it }
                b.setStrength(strength.toShort())
                b.enabled = true
            } else {
                bass?.release()
                bass = null
            }
        } catch (t: Throwable) {
            L.w("Player: bass boost", t)
            runCatching { bass?.release() }
            bass = null
        }
    }

    private fun drop() {
        runCatching { eq?.release() }
        eq = null
        runCatching { bass?.release() }
        bass = null
    }
}

/** 3.0.3: the equalizer's labels and kept levels, as plain numbers (tested on the PC, EqualizerRulesTest). */
object EqualizerRules {
    /** "60 Hz", "230 Hz", "3.6 kHz", "14 kHz": a band's middle, given in milliHertz as Android's equalizer gives it. */
    fun frequency(milliHz: Int): String {
        val hz = milliHz / 1000.0
        return if (hz >= 1000) "${oneDecimal(hz / 1000)} kHz" else "${hz.roundToInt()} Hz"
    }

    /** "+3 dB", "−4.5 dB", "0 dB" for a level in millibels. */
    fun level(millibels: Int): String {
        val db = oneDecimal(abs(millibels) / 100.0)
        return when {
            millibels > 0 -> "+$db dB"
            millibels < 0 -> "−$db dB"
            else -> "0 dB"
        }
    }

    private fun oneDecimal(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    /** The levels kept ("300,0,-200"), one for each of [bands], each within [min]..[max]; missing ones are 0. */
    fun levels(saved: String?, bands: Int, min: Int, max: Int): IntArray {
        val parts = saved?.split(',').orEmpty()
        return IntArray(bands.coerceAtLeast(0)) { i -> (parts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0).coerceIn(min, max) }
    }

    fun format(levels: IntArray): String = levels.joinToString(",")

    /** The bass boost's strength (0 to 1000) as a share: "0%" to "100%". */
    fun bassLabel(strength: Int): String = "${(strength.coerceIn(0, 1000) / 10.0).roundToInt()}%"
}
