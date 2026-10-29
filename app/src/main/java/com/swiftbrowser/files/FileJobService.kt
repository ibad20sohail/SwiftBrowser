package com.swiftbrowser.files

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.ui.FilesActivity

/**
 * Keeps Swift running while files are copied, moved, deleted, extracted or compressed (2.7), with
 * the progress in the notification shade and a Cancel button. Stops by itself when nothing is left.
 */
class FileJobService : Service() {
    private val listener: () -> Unit = { update() }
    private val handler = Handler(Looper.getMainLooper())
    private val updateLater = Runnable { update() }
    private var shown: Shown? = null
    private var shownAt = 0L

    private data class Shown(val title: String, val text: String, val percent: Int, val indeterminate: Boolean, val asking: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        FileJobs.addService(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileJobs.running?.cancel()
        }
        val now = state()
        try {
            startForeground(ONGOING_ID, build(now))
        } catch (e: Exception) {
            // Android 12+ refuses a start from the background: the job still runs while Swift does.
            L.w("Couldn't show the file job notification", e)
        }
        shown = now
        shownAt = SystemClock.uptimeMillis()
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateLater)
        FileJobs.removeService(listener)
        super.onDestroy()
    }

    /** At most once a second, and only when what it shows changed. */
    private fun update() {
        handler.removeCallbacks(updateLater)
        if (FileJobs.active.none { it.kind != FileJobs.Kind.OPEN }) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val now = state()
        if (now == shown) return
        val wait = shownAt + MIN_UPDATE_MS - SystemClock.uptimeMillis()
        if (wait > 0 && now.asking == shown?.asking) {
            handler.postDelayed(updateLater, wait)
            return
        }
        shown = now
        shownAt = SystemClock.uptimeMillis()
        try {
            NotificationManagerCompat.from(this).notify(ONGOING_ID, build(now))
        } catch (_: SecurityException) {
        }
    }

    private fun state(): Shown {
        val jobs = FileJobs.active.filter { it.kind != FileJobs.Kind.OPEN }
        val job = jobs.firstOrNull { it.running } ?: jobs.firstOrNull()
        if (job == null) return Shown("Files", "", 0, true, false)
        val q = job.question
        val more = jobs.size - 1
        val text = when {
            q?.password == true -> "Waiting for you: ${q.name} needs its password"
            q != null -> "Waiting for you: “${q.name}” is already there"
            else -> listOfNotNull(job.current.ifBlank { null }, FileJobs.detail(job).ifBlank { null }).joinToString(" · ")
        } + if (more > 0) " (${more} more waiting)" else ""
        val percent = if (job.fraction >= 0) (job.fraction * 100).toInt() else 0
        return Shown(job.title, text, percent, job.fraction < 0 || job.counting, q != null)
    }

    private fun build(s: Shown): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, FilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, FileJobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify_files)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.text))
            .setOngoing(true)
            .setOnlyAlertOnce(!s.asking)
            .setPriority(if (s.asking) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setProgress(100, s.percent, s.indeterminate)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "file_jobs"
        private const val CHANNEL_DONE = "file_jobs_done"
        private const val ONGOING_ID = 7100
        private const val DONE_ID = 7101
        private const val MIN_UPDATE_MS = 1000L
        private const val ACTION_CANCEL = "com.swiftbrowser.files.CANCEL"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FileJobService::class.java))
            } catch (e: Exception) {
                L.w("Couldn't start the file job service", e)
            }
        }

        private fun ensureChannels(ctx: Context) {
            val nm = NotificationManagerCompat.from(ctx)
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Copying and moving files").build(),
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Finished file jobs").build(),
            )
        }

        /** The outcome, when no Files screen was on show to say it. */
        fun notifyDone(ctx: Context, job: FileJobs.Job) {
            if (job.message == "Cancelled") return
            ensureChannels(ctx)
            val folder = job.result?.let { if (it.isDirectory) it else it.parentFile } ?: job.touched.firstOrNull()?.let { java.io.File(it) }
            val open = PendingIntent.getActivity(
                ctx, 2, FilesActivity.intent(ctx, folder).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notify_files)
                .setContentTitle(if (job.failed) "${job.title}: stopped" else job.title)
                .setContentText(job.message)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(DONE_ID, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
