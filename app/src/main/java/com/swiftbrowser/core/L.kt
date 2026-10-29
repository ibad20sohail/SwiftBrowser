package com.swiftbrowser.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.swiftbrowser.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logging that also keeps the last few hundred lines in memory for crash reports. */
object L {
    private const val TAG = "Swift"
    private const val MAX = 300
    private val ring = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(msg: String) {
        Log.d(TAG, msg)
        add("D", msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        add("W", if (t != null) "$msg: $t" else msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        add("E", if (t != null) "$msg: $t" else msg)
    }

    @Synchronized
    private fun add(level: String, msg: String) {
        if (ring.size >= MAX) ring.removeFirst()
        ring.addLast("${fmt.format(Date())} $level $msg")
    }

    @Synchronized
    fun dump(): String = ring.joinToString("\n")

    @Synchronized
    fun clear() = ring.clear()
}

/** Writes uncaught exceptions to a file so the next launch can show them. */
object Crash {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val file = File(ctx.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText(report(thread.name, error))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(threadName: String, error: Throwable?): String {
        val trace = StringWriter().also { sw -> error?.printStackTrace(PrintWriter(sw)) }.toString()
        return buildString {
            appendLine("Swift ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Time: ${Date()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread: $threadName")
            appendLine()
            appendLine(trace)
            appendLine("--- recent log ---")
            appendLine(L.dump())
        }
    }

    fun pending(ctx: Context): String? =
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
