package com.swiftbrowser.core

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.swiftbrowser.tabs.TabManager

/**
 * What the logs say about how Swift is doing (2.7), for finding crashes and freezes:
 *  - a watchdog notes when Swift's screen is stuck for 2 seconds or more, and where;
 *  - memory every minute while Swift is on screen (the phone's, and each of Swift's processes);
 *  - the engine's own warnings and errors, read from Android's log (an app can read its own).
 * The watchdog and memory notes only run while Swift is on screen, so they cost nothing otherwise.
 */
object Health {
    private const val MB = 1024L * 1024L
    private const val STUCK_MS = 2000L
    private const val MEMORY_EVERY_MS = 60_000L

    /** Engine lines per minute at most: a page spamming warnings doesn't fill the day's file. */
    private const val ENGINE_LINES_PER_MINUTE = 120

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var watching = false
    @Volatile private var answered = 0L
    private val pong = Runnable { answered = SystemClock.uptimeMillis() }

    /**
     * An incognito tab is open: the engine's messages could name the sites it shows, so they aren't
     * kept meanwhile (crash lines still are; they don't name sites).
     */
    @Volatile var quiet = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        startEngineLog()
    }

    /** Swift's screen is on screen: the watchdog and the memory notes run. */
    fun onScreen(visible: Boolean) {
        if (!::app.isInitialized) return
        if (!visible) {
            watching = false
            return
        }
        if (watching) return
        watching = true
        Thread({ watch() }, "watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun watch() {
        var stuckSince = 0L
        var told = false
        var nextMemory = SystemClock.uptimeMillis() + 5000
        answered = SystemClock.uptimeMillis()
        while (watching) {
            val asked = SystemClock.uptimeMillis()
            main.post(pong)
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
            val now = SystemClock.uptimeMillis()
            if (answered < asked) {
                if (stuckSince == 0L) stuckSince = asked
                if (!told && now - stuckSince >= STUCK_MS) {
                    told = true
                    L.w("Swift's screen is stuck (${(now - stuckSince) / 100 / 10.0} s), busy in: ${mainStack()}")
                }
            } else if (stuckSince != 0L) {
                if (told) L.w("Swift's screen moves again, after ${(answered - stuckSince) / 100 / 10.0} s")
                stuckSince = 0L
                told = false
            }
            if (now >= nextMemory) {
                nextMemory = now + MEMORY_EVERY_MS
                logMemory()
            }
        }
    }

    /** Where Swift's main thread is (its top calls, Swift's and Android's). */
    private fun mainStack(): String = try {
        Looper.getMainLooper().thread.stackTrace.take(12).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    } catch (_: Exception) {
        "unknown"
    }

    /** The phone's free memory and what each of Swift's processes uses. Off the main thread. */
    private fun logMemory() {
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val mine = am.runningAppProcesses.orEmpty().filter { it.uid == Process.myUid() }
            val used = if (mine.isEmpty()) emptyArray() else am.getProcessMemoryInfo(mine.map { it.pid }.toIntArray())
            val parts = mine.indices.joinToString(", ") { i ->
                val name = mine[i].processName.substringAfter(':', "main")
                "$name ${used.getOrNull(i)?.totalPss?.div(1024) ?: 0} MB"
            }
            val text = "Memory: ${mi.availMem / MB} MB free of ${mi.totalMem / MB} MB${if (mi.lowMemory) " (low)" else ""}; Swift: $parts"
            main.post {
                val loaded = TabManager.tabs.count { it.session != null }
                L.d("$text; tabs loaded $loaded of ${TabManager.tabs.size}")
            }
        } catch (e: Exception) {
            L.w("Memory note failed", e)
        }
    }

    // ------------------------------------------------------------------ the engine's messages

    private val URL = Regex("(https?://[^/\\s\"'<>]+)[^\\s\"'<>]*")

    private val NOISY_TAGS = setOf("Swift", "chatty", "linker", "eglCodecCommon", "GeckoLibLoad", "ResourceType", "InputMethodManager")
    private val NOISY_TEXT = listOf(
        "JavaScript Warning", "JavaScript Error", "[JavaScript", "unused DT entry", "avc: denied", "No listener for GeckoView",
        "No history entries found", "No editable parent", "Unexpected CPU variant", "zerdatime",
    )

    /** A line of a JavaScript stack ("name@file:12:3"), which follows the error it belongs to. */
    private val STACK_LINE = Regex("^\\S*@\\S+:\\d+:\\d+$")

    /** Lines of no use for finding problems (page script messages name pages and are many). */
    private fun noise(tag: String, msg: String): Boolean =
        tag in NOISY_TAGS || NOISY_TEXT.any { msg.contains(it) } || msg.startsWith("\" {file:") || STACK_LINE.matches(msg.trim())

    private fun startEngineLog() {
        Thread({
            try {
                // Only Swift's own processes' lines are visible to Swift; warnings and worse, from now on.
                val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "*:W").redirectErrorStream(true).start()
                var minute = 0L
                var count = 0
                var last = ""
                var repeats = 0
                p.inputStream.bufferedReader().forEachLine { line ->
                    val parsed = parse(line) ?: return@forEachLine
                    val (level, tag, msg) = parsed
                    if (noise(tag, msg)) return@forEachLine
                    if (quiet && level != "F") return@forEachLine
                    val text = "$tag: ${URL.replace(msg, "$1/…")}"
                    if (text == last) {
                        repeats++
                        return@forEachLine
                    }
                    if (repeats > 0) L.engine(level, "(the line before, $repeats more times)")
                    repeats = 0
                    last = text
                    val now = SystemClock.elapsedRealtime() / 60_000
                    if (now != minute) {
                        minute = now
                        count = 0
                    }
                    if (++count > ENGINE_LINES_PER_MINUTE && level != "F") return@forEachLine
                    L.engine(level, text)
                }
            } catch (e: Exception) {
                L.w("The engine's messages can't be read", e)
            }
        }, "engine-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** "W/GeckoSession( 1234): message" → level, tag, message. */
    private fun parse(line: String): Triple<String, String, String>? {
        if (line.length < 3 || line[1] != '/') return null
        val level = line.substring(0, 1)
        if (level !in setOf("W", "E", "F")) return null
        val open = line.indexOf('(', 2)
        val colon = line.indexOf("): ", 2)
        if (open < 0 || colon < 0) return null
        return Triple(level, line.substring(2, open).trim(), line.substring(colon + 3))
    }

    /**
     * After a run that ended badly: the engine's errors from the end of that run, which Android's
     * log still has (until the phone restarts), go into today's file.
     */
    fun logEndOfPreviousRun() {
        Thread({
            try {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "*:E").redirectErrorStream(true).start()
                val lines = p.inputStream.bufferedReader().readLines()
                    .filter { l -> !l.contains("JavaScript") && !l.contains("eglCodecCommon") && !l.contains("/Swift(") }
                    .takeLast(120)
                if (lines.isEmpty()) return@Thread
                L.w("Engine errors before the last run ended (${lines.size} lines):")
                lines.forEach { L.engine("E", URL.replace(it, "$1/…")) }
            } catch (e: Exception) {
                L.w("The engine's errors of the last run can't be read", e)
            }
        }, "engine-log-previous").apply { isDaemon = true }.start()
    }
}
