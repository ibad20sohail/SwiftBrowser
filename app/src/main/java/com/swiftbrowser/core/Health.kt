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

    /**
     * 3.0.11: which watchdog is the live one. Off and on again within its 1 s sleep used to leave the
     * old thread running next to the new one (two memory notes a minute, seen in a problem report).
     */
    @Volatile private var generation = 0
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
            generation++
            return
        }
        if (watching) return
        watching = true
        val mine = ++generation
        Thread({ watch(mine) }, "watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun watch(mine: Int) {
        var stuckSince = 0L
        var told = false
        var nextMemory = SystemClock.uptimeMillis() + 5000
        var nextGuard = SystemClock.uptimeMillis() + MemoryGuard.CHECK_MS
        answered = SystemClock.uptimeMillis()
        while (watching && mine == generation) {
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
            // 3.0.13: free memory, every 2 seconds (see MemoryGuard).
            if (now >= nextGuard) {
                nextGuard = now + MemoryGuard.CHECK_MS
                guard()
            }
        }
    }

    // ------------------------------------------------------------------ 3.0.13: the memory guard

    /** When the last clean-up ran (uptime), see [MemoryGuard.decide]. */
    @Volatile private var lastRelief = MemoryGuard.NEVER

    /** Off the main thread: the phone's free memory now, and what to do about it. */
    private fun guard() {
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val now = SystemClock.uptimeMillis()
            when (MemoryGuard.decide(mi.availMem, mi.threshold, mi.lowMemory, now, lastRelief)) {
                MemoryGuard.Action.NONE -> Unit
                MemoryGuard.Action.UNLOAD -> main.post { TabManager.trimLiveSessions() }
                MemoryGuard.Action.RELIEVE -> {
                    lastRelief = now
                    val text = "${mi.availMem / MB} MB free of ${mi.totalMem / MB} MB"
                    main.post { relieve(text) }
                }
            }
        } catch (e: Exception) {
            L.w("Memory guard failed", e)
        }
    }

    /**
     * Main thread: memory is about to run out. Other pages go, Swift lets go of the pictures it keeps
     * in memory (they load again when shown), the engine runs its own low-memory clean-up (the one
     * Android's "critical" warning starts: its caches, pictures and scripts' memory, in every process
     * of it), and Android's drawing lets go of its caches, before its next picture finds no graphics
     * memory left.
     */
    private fun relieve(text: String) {
        L.w("Memory very low ($text): freeing memory now")
        TabManager.trimLiveSessions()
        com.swiftbrowser.tabs.Thumbnails.trim()
        com.swiftbrowser.docs.DocThumbs.trim()
        com.swiftbrowser.files.FileThumbs.trim()
        Favicons.trim()
        com.swiftbrowser.ui.HomeBackground.trim()
        try {
            org.mozilla.gecko.GeckoAppShell.notifyObservers("memory-pressure", "low-memory")
        } catch (e: Throwable) {
            L.w("The engine's clean-up couldn't be asked for", e)
        }
        trimDrawingCaches()
    }

    /**
     * Android 7 and 8: the caches of Android's own drawing (pictures already sent to the graphics chip,
     * spare layers) go, as when an app leaves the screen; what shows is sent again with the next frame.
     * Android 9 and later don't let apps ask for this.
     */
    private fun trimDrawingCaches() {
        if (android.os.Build.VERSION.SDK_INT >= 28) return
        try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            global.getMethod("trimMemory", Int::class.javaPrimitiveType).invoke(instance, android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        } catch (e: Throwable) {
            L.w("Android's drawing caches couldn't be freed (${e.javaClass.simpleName})")
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
                val info = used.getOrNull(i)
                fun stat(key: String) = info?.getMemoryStat(key)?.toLongOrNull()?.div(1024) ?: 0L
                // 3.0.8: and the graphics memory it holds (as Android counts it, where the phone tells),
                // to see which process fills it before Android's drawing runs out.
                val graphics = stat("summary.graphics")
                // 3.0.13: what Swift's own process is made of, and how much of its address space is taken
                // (a 32-bit process has 3-4 GB of it): it grew by hundreds of MB during long videos on a
                // phone, which the emulator didn't show.
                val inside = if (mine[i].pid == Process.myPid()) {
                    "; java ${stat("summary.java-heap")}, native ${stat("summary.native-heap")}, other ${stat("summary.private-other")}" +
                        (addressSpaceMb()?.let { ", address space $it" } ?: "")
                } else {
                    ""
                }
                val extra = (if (graphics > 0) "graphics $graphics" else "") + inside
                "$name ${info?.totalPss?.div(1024) ?: 0} MB" + if (extra.isNotEmpty()) " (${extra.removePrefix("; ")})" else ""
            }
            val text = "Memory: ${mi.availMem / MB} MB free of ${mi.totalMem / MB} MB${if (mi.lowMemory) " (low)" else ""}; Swift: $parts"
            // 3.0.11: memory that stays low (as Diagnostics.memoryLow counts it) lets background pages go
            // now, not at the next tab switch, warning or 5-minute check: in a problem report it stayed
            // under the line for minutes, with no warning from Android, until Android closed Swift.
            val low = mi.lowMemory || mi.availMem < mi.threshold * 2
            main.post {
                val loaded = TabManager.tabs.count { it.session != null }
                L.d("$text; tabs loaded $loaded of ${TabManager.tabs.size}")
                if (low) TabManager.trimLiveSessions()
            }
        } catch (e: Exception) {
            L.w("Memory note failed", e)
        }
    }

    /** 3.0.13: the address space Swift's own process has taken (VmSize), in MB; null if unknown. */
    private fun addressSpaceMb(): Long? = try {
        java.io.File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmSize:") }?.filter { it.isDigit() }?.toLongOrNull()?.div(1024)
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ the engine's messages

    private val URL = Regex("(https?://[^/\\s\"'<>]+)[^\\s\"'<>]*")

    private val NOISY_TAGS = setOf("Swift", "chatty", "linker", "eglCodecCommon", "GeckoLibLoad", "ResourceType", "InputMethodManager")
    /**
     * 3.0.13: Android's own lines that come by the thousand while videos play and say nothing new
     * (5,700 of a report's 9,700 lines): the codec list read again, each new sound output, the
     * graphics driver unable to read its clock speed.
     */
    private val VIDEO_NOISE = listOf("Unrecognized profile", "Configuration error: unknown key", "Leaving AndroidConfiguration::GetConfiguration", "get_gpu_clk")

    private val NOISY_TEXT = listOf(
        "JavaScript Warning", "JavaScript Error", "[JavaScript", "unused DT entry", "avc: denied", "No listener for GeckoView",
        "No history entries found", "No editable parent", "Unexpected CPU variant", "zerdatime",
    ) + VIDEO_NOISE

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

    /** 3.0.8: the previous run ended with Android's drawing out of graphics memory (see [logEndOfPreviousRun]). */
    @Volatile var endedOutOfGraphicsMemory = false
        private set

    private val endOfRunRead = java.util.concurrent.CountDownLatch(1)

    /** 3.0.8: blocking, at most [ms]: until [logEndOfPreviousRun] has read Android's log. */
    fun awaitEndOfPreviousRun(ms: Long) {
        try {
            endOfRunRead.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
    }

    /**
     * After a run that ended badly: the engine's errors from the end of that run, which Android's
     * log still has (until the phone restarts), go into today's file. 3.0.8: only that run's lines
     * (Android keeps its crash log for days: a crash fixed long ago came back after every bad end),
     * and whether its drawing ran out of graphics memory, for the message about it.
     */
    fun logEndOfPreviousRun() {
        val since = Diagnostics.previousRunStart
        val until = Diagnostics.thisRunStart
        Thread({
            try {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "*:E").redirectErrorStream(true).start()
                val all = p.inputStream.bufferedReader().readLines()
                    // 3.0.11: page scripts' errors stay out (they can quote pages), but not those of Swift's
                    // own add-ons (ad blocker, helper), which were missing from a problem report.
                    .filter { l ->
                        (!l.contains("JavaScript") || l.contains("moz-extension://")) && !l.contains("eglCodecCommon") && !l.contains("/Swift(") &&
                            // 3.0.13: nor the thousands of lines that say nothing (they hid the crash's own lines).
                            VIDEO_NOISE.none { l.contains(it) }
                    }
                val lines = (if (since > 0) LogLines.between(all, since, until, System.currentTimeMillis()) else all).takeLast(120)
                if (lines.isEmpty()) return@Thread
                endedOutOfGraphicsMemory = LogLines.graphicsOutOfMemory(lines)
                L.w("Engine errors before the last run ended (${lines.size} lines):")
                lines.forEach { L.engine("E", URL.replace(it, "$1/…")) }
                if (endedOutOfGraphicsMemory) L.w("The last run ended with Android's drawing out of graphics memory")
            } catch (e: Exception) {
                L.w("The engine's errors of the last run can't be read", e)
            } finally {
                endOfRunRead.countDown()
            }
        }, "engine-log-previous").apply { isDaemon = true }.start()
    }
}
