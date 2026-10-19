package com.swiftbrowser.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.ui.DownloadsActivity

/** Keeps the app alive while downloads run and shows their progress in the notification shade. */
class DownloadService : Service() {
    private val listener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_ID, buildOngoing())
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Downloads.removeListener(listener)
        super.onDestroy()
    }

    private fun update() {
        if (Downloads.activeCount() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(ONGOING_ID, buildOngoing())
        } catch (_: SecurityException) {
        }
    }

    private fun buildOngoing(): Notification {
        val active = Downloads.all().filter {
            it.status == Downloads.RUNNING || it.status == Downloads.QUEUED || it.status == Downloads.CONVERTING
        }
        val first = active.firstOrNull()
        val title = when {
            first == null -> "Downloads"
            active.size == 1 -> first.fileName
            else -> "Downloading ${active.size} files"
        }
        val text = when {
            first == null -> ""
            first.status == Downloads.CONVERTING -> "Converting to MP4…"
            first.status == Downloads.QUEUED -> "Waiting…"
            else -> buildString {
                append(UrlUtils.formatBytes(first.done))
                if (first.total > 0) append(" / ").append(UrlUtils.formatBytes(first.total))
                if (first.bytesPerSecond > 0) append(" • ").append(UrlUtils.formatBytes(first.bytesPerSecond)).append("/s")
            }
        }
        val percent = first?.percent ?: -1
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0 || first?.status == Downloads.CONVERTING)
            .setContentIntent(openDownloads(this))
            .build()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads_done"
        private const val ONGOING_ID = 1

        fun ensureChannels(ctx: Context) {
            val nm = NotificationManagerCompat.from(ctx)
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Download progress").build(),
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Finished downloads").build(),
            )
        }

        private fun openDownloads(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun notifyFinished(ctx: Context, item: Downloads.Item) {
            ensureChannels(ctx)
            val open = DownloadsActivity.openFileIntent(ctx, item)
            val pi = if (open != null) {
                PendingIntent.getActivity(ctx, item.id.toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else {
                openDownloads(ctx)
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(item.fileName)
                .setContentText("Download complete • ${UrlUtils.formatBytes(item.done)}")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(1000 + item.id.toInt(), n)
            } catch (_: SecurityException) {
            }
        }
    }
}
