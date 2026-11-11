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

    /** 3.0: …up to this size; past it nothing more goes in that day (a page warning without end filled the phone). */
    private const val WARNINGS_LIMIT = 5L * 1024 * 1024
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
            // 3.0: warnings and errors have their limit too.
            if (!fresh && file.length() > WARNINGS_LIMIT) return
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

    /**
     * 3.0: clearing history for a time: the log lines of [from]..[to] go too (they name the sites
     * visited then). A day file is rewritten without them, or deleted when it was all in that time;
     * the lines kept in memory for crash reports go the same way.
     */
    fun clearBetween(from: Long, to: Long) {
        val now = System.currentTimeMillis()
        val today = LogLines.startOfDay(now)
        synchronized(this) {
            // Lines in memory have no date: today's, or yesterday's when their time is still to come.
            val kept = ring.filter { line ->
                val t = LogLines.timeOfDay(line)?.let { today + it }?.let { if (it > now + 60_000) it - 24L * 3600 * 1000 else it }
                t == null || t !in from..to
            }
            ring.clear()
            ring.addAll(kept)
        }
        writer.execute {
            writePending()
            for (f in files()) {
                try {
                    val day = dayFmt.parse(f.name.removePrefix("swift-").removeSuffix(".txt"))?.time ?: continue
                    val next = LogLines.startOfDay(day + 36L * 3600 * 1000)
                    if (next <= from || day > to) continue
                    if (from <= day && to >= next - 1) {
                        f.delete()
                        continue
                    }
                    val kept = LogLines.keepOutside(f.readLines(), day, from, to)
                    if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n", postfix = "\n"))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Couldn't clear a log file", e)
                }
            }
        }
    }
}

/**
 * 3.0: plain work on the logs' lines ("HH:mm:ss.SSS L message"), kept apart so the PC's tests can
 * check it: which lines belong to a time, and a problem report without the sites visited.
 */
object LogLines {
    private val TIME = Regex("^(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) ")

    /** Milliseconds after midnight a line was written, or null for a line without a time (a message's next line). */
    fun timeOfDay(line: String): Long? {
        val m = TIME.find(line) ?: return null
        val (h, min, s, ms) = m.destructured
        return h.toLong() * 3_600_000 + min.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
    }

    /** 00:00 of the day of [ms], local time. */
    fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().run {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /**
     * The lines of a day's file (the day starting at [dayStart]) written outside [from]..[to]. A line
     * without a time goes with the line before it.
     */
    fun keepOutside(lines: List<String>, dayStart: Long, from: Long, to: Long): List<String> {
        var time: Long? = null
        return lines.filter { line ->
            timeOfDay(line)?.let { time = dayStart + it }
            val t = time
            t == null || t !in from..to
        }
    }

    /** Android's log as `logcat -v time` writes it: "11-10 04:26:44.525 E/Tag( 123): message". */
    private val LOGCAT_TIME = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) ")

    /**
     * 3.0.8: when a line of Android's log was written ([now]'s year; a date ahead of [now] was last
     * year's), or null for a line without one ("--------- beginning of crash").
     */
    fun logcatTime(line: String, now: Long): Long? {
        val m = LOGCAT_TIME.find(line) ?: return null
        val (mo, d, h, min, s, ms) = m.destructured
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(get(java.util.Calendar.YEAR), mo.toInt() - 1, d.toInt(), h.toInt(), min.toInt(), s.toInt())
            set(java.util.Calendar.MILLISECOND, ms.toInt())
        }
        if (c.timeInMillis > now + 86_400_000L) c.add(java.util.Calendar.YEAR, -1)
        return c.timeInMillis
    }

    /**
     * 3.0.8: the lines of Android's log written from [start] (the run that ended badly began then)
     * until [end] (this run began; its own lines aren't about that end). Android keeps its crash log
     * for days, so a crash fixed long ago came back in every report.
     */
    fun between(lines: List<String>, start: Long, end: Long, now: Long): List<String> =
        lines.filter { line -> logcatTime(line, now)?.let { it in start until end } ?: false }

    /**
     * 3.0.8: Android's drawing ran out of graphics memory (HWUI stops the app on purpose then): the
     * driver couldn't give memory ("kgsl_sharedmem_alloc ioctl failed") or "GL error: Out of memory".
     */
    fun graphicsOutOfMemory(lines: List<String>): Boolean = lines.any { line ->
        line.contains("kgsl_sharedmem_alloc") ||
            (line.contains("OpenGLRenderer") && (line.contains("Out of memory", ignoreCase = true) || line.contains("GL errors!")))
    }

    /** Web addresses: the whole of each, for [withoutSites]; [trimAddress] keeps their site. */
    private val ADDRESS = Regex("\\b(?:https?|wss?)://[^\\s\"'<>()]+", RegexOption.IGNORE_CASE)
    private val ADDRESS_PATH = Regex("((?:https?|wss?)://[^/\\s\"'<>]+)[^\\s\"'<>]*", RegexOption.IGNORE_CASE)

    /** A name like a site's: lowercase words with dots, ending in letters ("m.youtube.com", "example.co.uk:8080"). */
    private val HOST = Regex("(?<![A-Za-z0-9._-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]*[a-z](?::\\d{1,5})?(?![A-Za-z0-9_-])")

    /** Names in the logs that look like sites but are code (packages, classes) or files Swift notes. */
    private val NOT_SITES = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "dalvik.", "libcore.", "sun.", "org.json",
        "com.android.", "com.google.", "com.swiftbrowser", "org.mozilla", "mozilla.org", "com.wireguard", "org.libtorrent4j",
        "org.apache", "net.lingala", "org.tukaani", "com.github", "org.jellyfin", "com.journeyapps",
    )
    private val FILE_ENDINGS = listOf(
        ".so", ".apk", ".odex", ".oat", ".dex", ".jar", ".js", ".mjs", ".css", ".html", ".txt", ".json", ".conf", ".bin",
        ".db", ".kt", ".java", ".png", ".jpg", ".webp", ".mp4", ".mkv", ".m3u8", ".ts", ".xpi", ".sqlite", ".tmp",
    )

    /** An address cut to its site (as reports before 3.0 did: where, not what was read). */
    fun trimAddress(line: String): String = ADDRESS_PATH.replace(line, "$1/…")

    /** The line with every site's name and web address replaced by "a site" (the report when sites are left out). */
    fun withoutSites(line: String): String {
        val noAddresses = ADDRESS.replace(line, "a site")
        return HOST.replace(noAddresses) { m ->
            val name = m.value
            if (NOT_SITES.any { name.startsWith(it) } || FILE_ENDINGS.any { name.endsWith(it) }) name else "a site"
        }
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
