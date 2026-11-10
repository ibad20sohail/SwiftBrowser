package com.swiftbrowser.core

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.swiftbrowser.BuildConfig
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.adblock.AdBlock
import com.swiftbrowser.tabs.TabManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app knows about how its runs end, for a problem report the user can send:
 *  - crashes of the engine (recorded by [com.swiftbrowser.engine.CrashReportService] in a process
 *    of its own, so they are caught even when the app's main process goes down),
 *  - Android closing the app while it was on screen, which on a 2 GB phone almost always means
 *    it ran out of memory (Android 7 doesn't say why an app was closed),
 *  - the app's log of the previous run.
 */
object Diagnostics {
    private const val STATE = "run_state.txt"
    private const val LOG = "run_log.txt"
    private const val PREVIOUS_LOG = "previous_run_log.txt"
    private const val CRASHES = "engine_crashes.txt"
    private const val MAX_CRASH_LINES = 30
    private const val MB = 1024L * 1024L

    /** How the previous run ended, if it ended badly; shown once on the next start. */
    enum class Problem { CLOSED_BY_ANDROID, ENGINE_CRASH }

    var problem: Problem? = null
        private set
    var problemDetail: String? = null
        private set

    private lateinit var app: Context
    private val io = Background.single("diagnostics")
    private var runStart = 0L
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Main process only, early in Application.onCreate. */
    fun onAppStart(ctx: Context) {
        app = ctx.applicationContext
        runStart = System.currentTimeMillis()
        val dir = ctx.filesDir
        try {
            val old = File(dir, STATE).takeIf { it.exists() }?.readLines().orEmpty()
            val oldStart = old.getOrNull(0)?.toLongOrNull() ?: 0L
            val wasOnScreen = old.getOrNull(1) == "fg"
            // The phone was switched off (or its battery ran out), or Swift was updated or installed
            // again: Android didn't close it for lack of memory, so there is nothing to tell.
            val sameBoot = old.getOrNull(2)?.let { it == bootId() } ?: true
            val sameVersion = old.getOrNull(3)?.let { it == BuildConfig.VERSION_CODE.toString() } ?: true
            val reinstalled = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime > oldStart
            } catch (_: Exception) {
                false
            }
            if (wasOnScreen && sameBoot && sameVersion && !reinstalled && !File(dir, "last_crash.txt").exists()) {
                // The app's own crashes have their own report (Crash); this is everything else.
                val crash = engineCrashes(ctx).lastOrNull { it.time >= oldStart && it.fatal }
                if (crash != null) {
                    problem = Problem.ENGINE_CRASH
                    problemDetail = crash.reason
                } else {
                    problem = Problem.CLOSED_BY_ANDROID
                }
            }
            // The run logs before 2.7; the day files (L) replace them.
            File(dir, LOG).delete()
            File(dir, PREVIOUS_LOG).delete()
        } catch (e: Exception) {
            L.w("Could not read the last run's state", e)
        }
        writeState(onScreen = false)
        if (problem != null) L.w("Previous run ended unexpectedly: $problem ${problemDetail.orEmpty()}")
    }

    /** What a crash's notes mean, in plain words (2.7); null when they don't say. */
    fun plainReason(reason: String?): String? {
        val r = reason?.lowercase() ?: return null
        return when {
            "out of memory" in r -> "It ran out of memory."
            listOf("libgles", "libegl", "adreno", "libgsl", "graphics").any { it in r } -> "It crashed in the phone's graphics driver."
            "ipc:" in r -> "Its page process stopped answering."
            "libxul" in r -> "It crashed inside the engine."
            "libc.so" in r || "libart" in r -> "It crashed in Android's own code."
            else -> null
        }
    }

    fun clearProblem() {
        problem = null
        problemDetail = null
    }

    /** The browser screen is (no longer) on screen. */
    fun setOnScreen(onScreen: Boolean) {
        io.execute { writeState(onScreen) }
        com.swiftbrowser.core.Health.onScreen(onScreen)
        com.swiftbrowser.tabs.TabManager.onScreen(onScreen)
        L.d(if (onScreen) "On screen" else "Out of sight")
    }

    /** Before the app closes itself (e.g. to restart): that isn't a problem. */
    fun markCleanExit() {
        writeState(onScreen = false)
        L.d("Swift closes itself")
        L.flushNow()
    }

    private fun writeState(onScreen: Boolean) {
        try {
            File(app.filesDir, STATE).writeText(
                "$runStart\n${if (onScreen) "fg" else "bg"}\n${bootId()}\n${BuildConfig.VERSION_CODE}\n",
            )
        } catch (_: Exception) {
        }
    }

    private var cachedBootId: String? = null

    /** Changes when the phone restarts. */
    private fun bootId(): String = cachedBootId ?: (
        try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (_: Exception) {
            null
        } ?: ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 600_000L).toString()
        ).also { cachedBootId = it }

    /**
     * Clearing all history (2.6): the logs, which name the sites of blocked pop-ups, start over.
     * 3.0: so do the notes of the last crash (they hold the log before it) and a report made earlier.
     */
    fun clearLogs() {
        if (!::app.isInitialized) return
        L.clear()
        Crash.clear(app)
        File(app.filesDir, REPORTS).deleteRecursively()
    }

    /** 3.0: clearing history for a time: the log lines of that time, and crash notes or a report made then. */
    fun clearLogsBetween(from: Long, to: Long) {
        if (!::app.isInitialized) return
        L.clearBetween(from, to)
        File(app.filesDir, "last_crash.txt").takeIf { it.lastModified() in from..to }?.delete()
        File(app.filesDir, REPORTS).listFiles()?.filter { it.lastModified() in from..to }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ memory

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    fun memoryText(): String {
        if (!::app.isInitialized) return "unknown"
        val mi = memoryInfo()
        return "${mi.availMem / MB} MB free of ${mi.totalMem / MB} MB" +
            " (Android starts closing apps below ${mi.threshold / MB} MB${if (mi.lowMemory) ", low now" else ""})"
    }

    /** The phone is close to the point where Android closes apps, possibly this one. */
    fun memoryLow(): Boolean {
        if (!::app.isInitialized) return false
        val mi = memoryInfo()
        return mi.lowMemory || mi.availMem < mi.threshold * 2
    }

    // ------------------------------------------------------------------ engine crashes

    class EngineCrash(val time: Long, val fatal: Boolean, val reason: String, val line: String)

    /** Called by the crash service, in its own process. */
    fun recordEngineCrash(ctx: Context, fatal: Boolean, process: String, reason: String) {
        try {
            val file = File(ctx.filesDir, CRASHES)
            val lines = (file.takeIf { it.exists() }?.readLines().orEmpty() +
                "${System.currentTimeMillis()}\t${if (fatal) "fatal" else "page"}\t$process\t${reason.replace('\n', ' ').replace('\t', ' ')}")
                .takeLast(MAX_CRASH_LINES)
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    fun engineCrashes(ctx: Context): List<EngineCrash> = try {
        File(ctx.filesDir, CRASHES).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split('\t')
            val t = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            EngineCrash(t, parts.getOrNull(1) == "fatal", "${parts.getOrNull(2).orEmpty()}: ${parts.getOrNull(3).orEmpty()}", line)
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------ report

    /** The folder of the report made for sending. */
    private const val REPORTS = "reports"

    /** 3.0: the report's first and last lines, to show before it is sent, and how many lines it has. */
    class Preview(val head: List<String>, val tail: List<String>, val lines: Int)

    /**
     * Everything useful for finding a problem, line by line; the user decides where to send it.
     * 3.0: read as it goes (three days of logs can be several MB, too much to hold at once), off
     * the main thread (it ran when the row was tapped).
     */
    private fun reportLines(extra: String, each: (String) -> Unit) {
        each("Swift ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) problem report")
        each("Time: ${time.format(Date())}")
        each("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
        each("Memory: ${memoryText()}")
        extra.lines().forEach(each)
        problem?.let { each("Last run ended: $it ${problemDetail.orEmpty()}") }
        each("")
        each("--- Engine crashes ---")
        val crashes = engineCrashes(app)
        if (crashes.isEmpty()) each("none")
        crashes.forEach { each("${time.format(Date(it.time))} ${if (it.fatal) "app" else "page/graphics"} ${it.reason}") }
        File(app.filesDir, "last_crash.txt").takeIf { it.exists() }?.let {
            each("")
            each("--- App crash ---")
            it.readText().take(6000).lines().forEach(each)
        }
        // The last 3 days' logs (2.7), oldest first.
        L.flushNow()
        val days = L.files()
        if (days.isEmpty()) {
            each("")
            each("--- Log ---")
            L.dump().lines().forEach(each)
        }
        for (f in days) {
            each("")
            each("--- Log of ${f.name.removePrefix("swift-").removeSuffix(".txt")} ---")
            try {
                f.bufferedReader().useLines { lines -> lines.forEach(each) }
            } catch (_: Exception) {
                each("(this day's log couldn't be read)")
            }
        }
    }

    /** Blocking: the start and the end of the report as it is now, to show before sending (3.0). */
    fun preview(extra: String): Preview {
        val head = ArrayList<String>()
        val tail = ArrayDeque<String>()
        var n = 0
        reportLines(extra) { line ->
            n++
            if (head.size < PREVIEW_HEAD) {
                head += line
            } else {
                tail.addLast(line)
                if (tail.size > PREVIEW_TAIL) tail.removeFirst()
            }
        }
        return Preview(head, tail.toList(), n)
    }

    private const val PREVIEW_HEAD = 40
    private const val PREVIEW_TAIL = 120

    /**
     * A line as the report sends it. Addresses are always cut to their site (where, not what was
     * read); 3.0: without [withSites] (the default), sites' names become "a site" too.
     */
    fun clean(line: String, withSites: Boolean): String = if (withSites) LogLines.trimAddress(line) else LogLines.withoutSites(line)

    /** Blocking (3.0): writes the report to send, line by line, and gives its file. */
    fun writeReport(ctx: Context, extra: String, withSites: Boolean): File {
        val dir = File(ctx.filesDir, REPORTS).apply { mkdirs() }
        val file = File(dir, "swift-report.txt")
        file.bufferedWriter().use { out ->
            reportLines(extra) { line ->
                out.write(clean(line, withSites))
                out.write("\n")
            }
        }
        return file
    }

    /** The app's settings and tabs at the start of the report. On the main thread (it reads the tabs). */
    fun reportExtra(activity: Activity): String {
        val prefs = SwiftApp.prefs
        val tabs = TabManager.tabs
        return "Settings: tabs kept in memory ${prefs.maxLiveTabs}, graphics chip ${com.swiftbrowser.engine.GpuInfo.renderer(activity).ifBlank { "unknown" }}, " +
            "ad blocking ${AdBlock.global.label}, Lite mode ${prefs.liteLevel.label}, saving battery ${com.swiftbrowser.engine.Power.saving}, cache ${prefs.cacheLimitMb} MB, full screen ${prefs.fullScreen}, " +
            "keep playing ${prefs.keepPlaying}, text size ${(prefs.textScale * 100).toInt()}%\n" +
            "Tabs: ${tabs.size} open, ${tabs.count { it.session != null }} loaded; current site: " +
            // An incognito tab's site stays private, even in a problem report (2.6).
            (TabManager.current?.let { if (it.incognito) "(incognito)" else UrlUtils.siteName(it.url) } ?: "")
    }

    /**
     * The report, for the share sheet. 3.0: first shown in a sheet with what it holds, sites' names
     * left out unless the user lets them in (ReportUi).
     */
    fun shareReport(activity: Activity) = com.swiftbrowser.ui.ReportUi.show(activity)

    /** Opens Android's share sheet with the report's [file] (for WhatsApp, Gmail, ...). */
    fun shareFile(activity: Activity, file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Swift ${BuildConfig.VERSION_NAME} problem report")
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(activity, activity.packageName + ".files", file))
            putExtra(Intent.EXTRA_TEXT, "Swift ${BuildConfig.VERSION_NAME} problem report (attached)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(Intent.createChooser(intent, "Send problem report"))
        } catch (_: Exception) {
        }
    }
}
