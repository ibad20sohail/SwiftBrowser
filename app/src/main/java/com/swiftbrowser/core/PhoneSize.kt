package com.swiftbrowser.core

import android.app.ActivityManager
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 3.0.6: how big the phone is (its memory and its screen), for the few things that must follow it.
 * Swift was tuned on a 2 GB phone with a 720p screen (the Moto E4): there everything stays exactly as
 * it was, and bigger phones get only what they need. The rules are plain, for tests; [totalMemory] and
 * [screenPixels] read the phone.
 */
object PhoneSize {
    /** More memory than this isn't a 2 GB phone (a 2 GB phone tells Android about 1.9 GB). */
    const val BIG_MEMORY = 2_500_000_000L

    /** The screen Swift's picture memory was sized on: 720 × 1280. */
    private const val BASE_SCREEN = 720L * 1280

    /** 48 MB of decoded pictures: plenty for a 720p screen. */
    const val BASE_PICTURE_KB = 48 * 1024

    /**
     * How many KB of decoded pictures the engine may hold. The page on screen keeps all of its
     * pictures ready, and a sharper screen draws each one with more dots, so 48 MB that is plenty at
     * 720p runs out at 1080 × 2400: the engine then gives up on the next pictures for good, and
     * picture-heavy sites (apple.com) showed empty boxes or the pictures' descriptions. So the room
     * grows with the screen: never less than the Moto E4's 48 MB, never more than a sixteenth of
     * the memory.
     */
    fun pictureMemoryKb(totalMemory: Long, screenPixels: Long): Int {
        val scaled = BASE_PICTURE_KB * (screenPixels.coerceAtLeast(1).toDouble() / BASE_SCREEN)
        val cap = (totalMemory / 16 / 1024).coerceAtLeast(BASE_PICTURE_KB.toLong()).toDouble()
        return scaled.coerceIn(BASE_PICTURE_KB.toDouble(), cap).toInt()
    }

    /**
     * Whether this phone should get the 64-bit Swift: it runs it already, or it can run it and has
     * more memory than a 2 GB phone. A 2 GB phone keeps the 32-bit Swift it was tuned with, even
     * when its Android is 64-bit.
     */
    fun wants64Bit(runs64Bit: Boolean, can64Bit: Boolean, totalMemory: Long): Boolean =
        runs64Bit || (can64Bit && totalMemory > BIG_MEMORY)

    /** "Swift-64bit-3.0.6.apk": the 64-bit app file of a release ("Swift-3.0.6.apk" is the 32-bit one). */
    fun is64BitApk(name: String): Boolean = name.contains("64bit", ignoreCase = true)

    /**
     * The release's app file for this phone, from the names of its files. Null when none fits (only a
     * 64-bit file, for a phone that can't run it).
     */
    fun pickApk(names: List<String>, want64: Boolean, can64Bit: Boolean): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        val (wide, narrow) = apks.partition { is64BitApk(it) }
        return if (want64) {
            wide.firstOrNull() ?: narrow.firstOrNull()
        } else {
            narrow.firstOrNull() ?: wide.firstOrNull()?.takeIf { can64Bit }
        }
    }

    /** The phone's memory, as Android tells it (bytes). */
    fun totalMemory(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    /** The whole screen's dots (the system bars included), whichever way the phone is held. */
    fun screenPixels(ctx: Context): Long {
        val metrics = DisplayMetrics()
        return try {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.toLong() * metrics.heightPixels
        } catch (e: Exception) {
            val m = ctx.resources.displayMetrics
            m.widthPixels.toLong() * m.heightPixels
        }
    }
}
