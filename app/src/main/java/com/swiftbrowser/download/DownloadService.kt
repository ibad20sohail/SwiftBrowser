package com.swiftbrowser.download

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
import com.swiftbrowser.R
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.ui.DownloadsActivity
import com.swiftbrowser.ui.PlayerActivity

/** Keeps the app alive while downloads run and shows their progress in the notification shade. */
class DownloadService : Service() {
    private val listener: () -> Unit = { update() }
    private val handler = Handler(Looper.getMainLooper())
    private val updateLater = Runnable { update() }

    /** What the notification shows now, and when it was last posted (uptime). */
    private var shown: Progress? = null
    private var shownAt = 0L

    /** Opens the Downloads screen; made once rather than with every update (2.6). */
    private val openIntent by lazy { openDownloads(this) }

    /** The notification's Pause all (2.9.2). */
    private val pauseAllIntent by lazy {
        PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 2.9.2: while something downloads, the phone stays awake and on Wi-Fi with the screen off, so a
     * long download (a torrent overnight) doesn't stall when it sleeps. Let go the moment nothing does.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** What the notification shows. */
    private data class Progress(val title: String, val text: String, val percent: Int, val indeterminate: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) Downloads.pauseAll()
        val now = progress()
        startForeground(ONGOING_ID, buildOngoing(now))
        shown = now
        shownAt = SystemClock.uptimeMillis()
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateLater)
        Downloads.removeListener(listener)
        keepAwake(false)
        super.onDestroy()
    }

    private fun keepAwake(on: Boolean) {
        if (on == (wakeLock != null)) return
        if (on) {
            try {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "swift:downloads").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swift:downloads").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                com.swiftbrowser.core.L.w("Couldn't keep the phone awake for downloads", e)
            }
        } else {
            runCatching { wakeLock?.release() }
            runCatching { wifiLock?.release() }
            wakeLock = null
            wifiLock = null
        }
    }

    /**
     * Downloads report progress a few times a second. The notification follows at most once a second
     * and only when what it shows changed (2.6): the system redraws it in the shade every time.
     */
    private fun update() {
        handler.removeCallbacks(updateLater)
        if (Downloads.activeCount() == 0) {
            keepAwake(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        keepAwake(Downloads.all().any { it.status == Downloads.RUNNING || it.status == Downloads.CONVERTING })
        val now = progress()
        if (now == shown) return
        val wait = shownAt + MIN_UPDATE_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            // The latest progress shows once the second is up, even if no other report comes.
            handler.postDelayed(updateLater, wait)
            return
        }
        shown = now
        shownAt = SystemClock.uptimeMillis()
        try {
            NotificationManagerCompat.from(this).notify(ONGOING_ID, buildOngoing(now))
        } catch (_: SecurityException) {
        }
    }

    private fun progress(): Progress {
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
            // 2.9.2: why it waits (the VPN, Wi-Fi, the battery…), when it says.
            first.status == Downloads.QUEUED -> first.error ?: "Waiting…"
            first.kind == Downloads.KIND_TORRENT && first.error != null -> first.error ?: ""
            else -> buildString {
                append(UrlUtils.formatBytes(first.done))
                if (first.total > 0) append(" / ").append(UrlUtils.formatBytes(first.total))
                if (first.bytesPerSecond > 0) append(" · ").append(UrlUtils.formatBytes(first.bytesPerSecond)).append("/s")
                if (first.kind == Downloads.KIND_TORRENT && first.peers > 0) append(" · ").append(first.peers).append(if (first.peers == 1) " peer" else " peers")
            }
        }
        val percent = first?.percent ?: -1
        return Progress(title, text, percent.coerceAtLeast(0), percent < 0 || first?.status == Downloads.CONVERTING)
    }

    private fun buildOngoing(p: Progress): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(p.title)
            .setContentText(p.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, p.percent, p.indeterminate)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_pause, "Pause all", pauseAllIntent)
            .build()

    companion object {
        private const val MIN_UPDATE_MS = 1000L
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads_done"
        private const val ONGOING_ID = 1
        private const val ACTION_PAUSE_ALL = "com.swiftbrowser.PAUSE_ALL_DOWNLOADS"

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
            // 2.9.2: a torrent's film plays in Swift's player (Play); other torrents show in Downloads.
            val video = if (item.kind == Downloads.KIND_TORRENT) {
                com.swiftbrowser.torrent.Torrents.mainFile(item)?.takeIf { PlayerActivity.kindOf(it.name) == PlayerActivity.Kind.VIDEO }
            } else {
                null
            }
            val play = video?.let {
                PendingIntent.getActivity(
                    ctx, 5000 + item.id.toInt(), PlayerActivity.intentFor(ctx, it),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val open = if (item.kind == Downloads.KIND_TORRENT) null else DownloadsActivity.openFileIntent(ctx, item)
            val pi = when {
                play != null -> play
                open != null -> PendingIntent.getActivity(ctx, item.id.toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else -> openDownloads(ctx)
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(item.fileName)
                .setContentText("Downloaded · ${UrlUtils.formatBytes(item.done)}")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .apply { if (play != null) addAction(R.drawable.ic_play, "Play", play) }
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(1000 + item.id.toInt(), n)
            } catch (_: SecurityException) {
            }
        }
    }
}
