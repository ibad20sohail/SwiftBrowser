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
import java.util.concurrent.Executors

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
    private val io = Executors.newSingleThreadExecutor()
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
            if (wasOnScreen && !File(dir, "last_crash.txt").exists()) {
                // The app's own crashes have their own report (Crash); this is everything else.
                val crash = engineCrashes(ctx).lastOrNull { it.time >= oldStart && it.fatal }
                if (crash != null) {
                    problem = Problem.ENGINE_CRASH
                    problemDetail = crash.reason
                } else {
                    problem = Problem.CLOSED_BY_ANDROID
                }
            }
            val log = File(dir, LOG)
            if (log.exists()) log.renameTo(File(dir, PREVIOUS_LOG))
        } catch (e: Exception) {
            L.w("Could not read the last run's state", e)
        }
        writeState(onScreen = false)
        if (problem != null) L.w("Previous run ended unexpectedly: $problem ${problemDetail.orEmpty()}")
    }

    fun clearProblem() {
        problem = null
        problemDetail = null
    }

    /** The browser screen is (no longer) on screen. */
    fun setOnScreen(onScreen: Boolean) {
        io.execute { writeState(onScreen) }
        if (!onScreen) flushLog()
    }

    /** Before the app closes itself (e.g. to restart): that isn't a problem. */
    fun markCleanExit() {
        writeState(onScreen = false)
        flushLogNow()
    }

    private fun writeState(onScreen: Boolean) {
        try {
            File(app.filesDir, STATE).writeText("$runStart\n${if (onScreen) "fg" else "bg"}\n")
        } catch (_: Exception) {
        }
    }

    /** Keeps this run's log on disk, so a report after Android closed the app still has it. */
    fun flushLog() {
        if (!::app.isInitialized) return
        io.execute { flushLogNow() }
    }

    private fun flushLogNow() {
        try {
            File(app.filesDir, LOG).writeText("Memory: ${memoryText()}\n${L.dump()}\n")
        } catch (_: Exception) {
        }
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

    /** Everything useful for finding a problem; the user decides where to send it. */
    fun report(extra: String): String = buildString {
        appendLine("Swift ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) problem report")
        appendLine("Time: ${time.format(Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Memory: ${memoryText()}")
        appendLine(extra)
        problem?.let { appendLine("Last run ended: $it ${problemDetail.orEmpty()}") }
        appendLine()
        appendLine("--- Engine crashes ---")
        val crashes = engineCrashes(app)
        if (crashes.isEmpty()) appendLine("none")
        crashes.forEach { appendLine("${time.format(Date(it.time))} ${if (it.fatal) "app" else "page/graphics"} ${it.reason}") }
        File(app.filesDir, "last_crash.txt").takeIf { it.exists() }?.let {
            appendLine()
            appendLine("--- App crash ---")
            appendLine(it.readText().take(6000))
        }
        appendLine()
        appendLine("--- Log of the previous run ---")
        appendLine(File(app.filesDir, PREVIOUS_LOG).takeIf { it.exists() }?.readText()?.takeLast(20000) ?: "none")
        appendLine()
        appendLine("--- Log of this run ---")
        appendLine(L.dump().takeLast(20000))
    }

    /** The report with the app's settings and tabs, for the share sheet. */
    fun shareReport(activity: Activity) {
        val prefs = SwiftApp.prefs
        val tabs = TabManager.tabs
        val extra = "Settings: tabs kept in memory ${prefs.maxLiveTabs}, pages drawn with ${if (prefs.softwareRendering) "CPU" else "GPU"}, " +
            "ad blocking ${AdBlock.global.label}, Lite mode ${prefs.liteMode}, sites as designed ${prefs.sitesAsDesigned}, full screen ${prefs.fullScreen}, " +
            "keep playing ${prefs.keepPlaying}, text size ${(prefs.textScale * 100).toInt()}%\n" +
            "Tabs: ${tabs.size} open, ${tabs.count { it.session != null }} loaded; current: ${TabManager.current?.url?.take(200)}"
        share(activity, extra)
    }

    /** Opens Android's share sheet with the report as a text file (for WhatsApp, Gmail, ...). */
    fun share(activity: Activity, extra: String) {
        val text = report(extra)
        val dir = File(activity.filesDir, "reports").apply { mkdirs() }
        val file = File(dir, "swift-report.txt")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Swift ${BuildConfig.VERSION_NAME} problem report")
            try {
                file.writeText(text)
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(activity, activity.packageName + ".files", file))
                putExtra(Intent.EXTRA_TEXT, "Swift ${BuildConfig.VERSION_NAME} problem report (attached)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                putExtra(Intent.EXTRA_TEXT, text.take(60000))
            }
        }
        try {
            activity.startActivity(Intent.createChooser(intent, "Send problem report"))
        } catch (_: Exception) {
        }
    }
}
