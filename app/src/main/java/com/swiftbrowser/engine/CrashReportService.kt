package com.swiftbrowser.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.Diagnostics
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import java.io.File

/**
 * Started by the engine when one of its processes crashes (it runs in a process of its own, see
 * the manifest, so it also hears about crashes of the app's main process). It notes what crashed
 * and why for the problem report, then deletes the crash dump: nothing is sent anywhere.
 */
class CrashReportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) foreground()
        if (intent?.action != GeckoRuntime.ACTION_CRASHED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread {
            try {
                record(intent)
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun record(intent: Intent) {
        val visibility = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY).orEmpty()
        val processType = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE).orEmpty()
        val remoteType = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE).orEmpty()
        val dump = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH)
        val extras = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH)
        val notes = extras?.let { readAnnotations(File(it)) }.orEmpty()
        // Where it crashed, from the crash file (2.7): what went wrong and in which library.
        val where = dump?.let { com.swiftbrowser.core.Minidump.summary(File(it)) }
        val reason = listOfNotNull(
            notes["MozCrashReason"],
            notes["OOMAllocationSize"]?.let { "out of memory (wanted $it bytes)" },
            notes["JSOutOfMemory"]?.takeIf { it.isNotBlank() }?.let { "page scripts ran out of memory ($it)" },
            notes["JSLargeAllocationFailure"]?.takeIf { it.isNotBlank() }?.let { "a page script's big memory request failed" },
            notes["ipc_channel_error"]?.let { "IPC: $it" },
            where,
        ).joinToString("; ").ifBlank { "no reason given" }
        val process = listOf(processType, remoteType).filter { it.isNotBlank() }.joinToString("/").ifBlank { visibility }
        Diagnostics.recordEngineCrash(this, visibility == GeckoRuntime.CRASHED_PROCESS_VISIBILITY_MAIN, process, reason)
        // Everything the engine noted that helps, into the day's log (nothing that names pages).
        val kept = notes.filterKeys { it in KEPT_NOTES }.entries.joinToString("; ") { "${it.key}=${it.value.take(300)}" }
        com.swiftbrowser.core.L.appendFromOtherProcess(
            this, "E Engine crash ($process, ${visibility.lowercase()}): $reason${if (kept.isNotEmpty()) " | $kept" else ""}",
        )
        dump?.let { File(it).delete() }
        extras?.let { File(it).delete() }
    }

    private companion object {
        /** The engine's crash notes worth keeping: memory, graphics and what kind of crash. */
        val KEPT_NOTES = setOf(
            "MozCrashReason", "OOMAllocationSize", "JSOutOfMemory", "JSLargeAllocationFailure", "ipc_channel_error",
            "AvailablePhysicalMemory", "TotalPhysicalMemory", "AvailableVirtualMemory", "SystemMemoryUsePercentage",
            "GraphicsCriticalError", "AdapterDescription", "AppNotes", "ProcessType", "RemoteType", "StartupCrash",
            "UptimeTS", "LastInteractionDuration", "ShutdownProgress", "IPCMessageName", "IPCFatalErrorMsg",
        )
    }

    /** The crash notes: JSON in current engines, "key=value" lines in older ones. */
    private fun readAnnotations(file: File): Map<String, String> {
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = HashMap<String, String>()
        try {
            val json = JSONObject(text)
            json.keys().forEach { k -> out[k] = json.optString(k) }
        } catch (_: Exception) {
            text.lineSequence().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        return out
    }

    /** Android 8+ requires engine-started services to show a notification right away. */
    private fun foreground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("crash", "Crash notes", NotificationManager.IMPORTANCE_MIN))
            val n = NotificationCompat.Builder(this, "crash")
                .setSmallIcon(R.drawable.ic_notify_logo)
                .setContentTitle("Noting a crash")
                .build()
            startForeground(77, n)
        } catch (_: Exception) {
        }
    }
}
