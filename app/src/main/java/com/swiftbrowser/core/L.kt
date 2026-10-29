package com.swiftbrowser.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.swiftbrowser.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logging that also keeps the last few hundred lines in memory for crash reports, and (2.7) writes
 * every line to a file of the day: logs/swift-2026-10-30.txt. Lines are written within 2 seconds
 * (warnings and errors at once), so a crash keeps what came just before it. The newest 3 days are
 * kept: when a 4th day starts, the oldest file goes.
 */
object L {
    private const val TAG = "Swift"
    private const val MAX = 300
    private const val KEEP_DAYS = 3

    /** A day's file stops taking ordinary lines past this size; warnings and errors still go in. */
    private const val DAY_LIMIT = 4L * 1024 * 1024
    private const val WRITE_DELAY_MS = 2000L

    private val ring = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var dir: File? = null
    private val pending = StringBuilder()
    private var scheduled = false
    private var fullNoted: String? = null
    private val writer = Background.single("log")

    /** Main process, first thing at start: lines go to the day's file from now on. */
    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        writer.execute { prune() }
    }

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

    /** A message of the engine (from Android's log, see Health), level W, E or F. */
    fun engine(level: String, msg: String) = add(level, "engine $msg")

    @Synchronized
    private fun add(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level $msg"
        if (ring.size >= MAX) ring.removeFirst()
        ring.addLast(line)
        if (dir == null) return
        pending.append(line).append('\n')
        if (level != "D") {
            writer.execute { writePending() }
        } else if (!scheduled) {
            scheduled = true
            writer.execute {
                try {
                    Thread.sleep(WRITE_DELAY_MS)
                } catch (_: InterruptedException) {
                }
                writePending()
            }
        }
    }

    /** Writes what is waiting now, on the calling thread (a crash, Swift closing itself). */
    fun flushNow() = writePending()

    private fun writePending() {
        val text: String
        synchronized(this) {
            text = pending.toString()
            pending.setLength(0)
            scheduled = false
        }
        val d = dir ?: return
        if (text.isEmpty()) return
        try {
            val day = dayFmt.format(Date())
            val file = File(d, "swift-$day.txt")
            val fresh = !file.exists()
            var out = text
            if (!fresh && file.length() > DAY_LIMIT) {
                // A very busy day: only warnings and errors from here on.
                out = text.lineSequence().filter { it.length > 13 && it[13] != 'D' }.joinToString("") { it + "\n" }
                if (fullNoted != day) {
                    fullNoted = day
                    out = "${fmt.format(Date())} W Today's log is full: only warnings and errors are kept now\n$out"
                }
                if (out.isBlank()) return
            }
            FileOutputStream(file, true).use { it.write(out.toByteArray()) }
            if (fresh) prune()
        } catch (_: Exception) {
        }
    }

    /** The newest [KEEP_DAYS] days stay. */
    private fun prune() {
        val d = dir ?: return
        val files = d.listFiles { f -> f.name.startsWith("swift-") && f.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: return
        files.drop(KEEP_DAYS).forEach { it.delete() }
    }

    /** The day files, oldest first. */
    fun files(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("swift-") && f.name.endsWith(".txt") }?.sortedBy { it.name }.orEmpty()

    /** From another process of Swift (the crash notes): straight into the day's file. */
    fun appendFromOtherProcess(ctx: Context, text: String) {
        try {
            val d = File(ctx.filesDir, "logs").apply { mkdirs() }
            val line = "${fmt.format(Date())} $text\n"
            FileOutputStream(File(d, "swift-${dayFmt.format(Date())}.txt"), true).use { it.write(line.toByteArray()) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun dump(): String = ring.joinToString("\n")

    /** Clearing all history: the logs (they name sites) start over. */
    fun clear() {
        synchronized(this) {
            ring.clear()
            pending.setLength(0)
        }
        writer.execute { files().forEach { it.delete() } }
    }
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
                L.e("Swift crashed on ${thread.name}", error)
                L.flushNow()
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
