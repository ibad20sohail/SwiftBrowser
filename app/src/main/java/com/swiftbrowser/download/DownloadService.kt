package com.swiftbrowser.download

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
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
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.ui.DownloadsActivity
import com.swiftbrowser.ui.PlayerActivity
import java.io.File

/**
 * Keeps the app alive while downloads run and shows their progress in the notification shade.
 *
 * 3.0: only while something really downloads, converts or is about to. Downloads that just wait
 * (for Wi-Fi, the night, the charger, the VPN to go off, someone to share a torrent) neither keep the
 * phone awake nor hold a notification in front all day: a quiet one says what waits, and an alarm
 * wakes Swift for the waits that end at a time (the night, the next try of a torrent).
 */
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
     * 3.0: the wake lock has a time limit, renewed while downloads run, so it can't outlive them.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var renewedAt = 0L

    /** What the notification shows. */
    private data class Progress(val title: String, val text: String, val percent: Int, val indeterminate: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        ensureChannels(this)
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> Downloads.pauseAll()
            // 3.0: the alarm for downloads waiting for a time: a minute awake to start them.
            ACTION_WAKE -> {
                awakeBriefly(this)
                Downloads.wake()
            }
        }
        // Started to be in the foreground (Android 8 and later want it within seconds).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || Downloads.needsService()) {
            val now = progress()
            startForeground(ONGOING_ID, buildOngoing(now))
            shown = now
            shownAt = SystemClock.uptimeMillis()
        }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        handler.removeCallbacks(updateLater)
        Downloads.removeListener(listener)
        keepAwake(false)
        super.onDestroy()
    }

    /**
     * 3.0.6: Android 15 and later let an app download in the background for 6 hours a day. When they
     * are up, Android calls this and the service must stop within seconds, or Android closes Swift.
     * The downloads pause and a notification says why; they go on by themselves the next time Swift is
     * opened (Downloads.resumeAfterAndroidLimit), when Android starts counting again.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val paused = Downloads.pauseForAndroidLimit()
        L.w("Android's 6 hours a day of background downloading are used up: $paused paused until Swift is opened")
        keepAwake(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (paused > 0) notifyPausedByAndroid(this, paused)
    }

    private fun keepAwake(on: Boolean) {
        if (!on) {
            // Each let go of on its own: one failing never keeps the other.
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                L.w("Letting go of the wake lock", e)
            } finally {
                wakeLock = null
            }
            try {
                wifiLock?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                L.w("Letting go of the Wi-Fi lock", e)
            } finally {
                wifiLock = null
            }
            return
        }
        try {
            val now = SystemClock.uptimeMillis()
            val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "swift:downloads").apply { setReferenceCounted(false) }
                .also {
                    wakeLock = it
                    renewedAt = 0L
                }
            // Taken again before it runs out (with a limit, it goes by itself if Swift is stopped).
            if (!lock.isHeld || now - renewedAt > RENEW_MS) {
                lock.acquire(WAKE_MS)
                renewedAt = now
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swift:downloads").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            L.w("Couldn't keep the phone awake for downloads", e)
        }
    }

    /**
     * Downloads report progress a few times a second. The notification follows at most once a second
     * and only when what it shows changed (2.6): the system redraws it in the shade every time.
     */
    private fun update() {
        handler.removeCallbacks(updateLater)
        if (!Downloads.needsService()) {
            // 3.0: nothing to do now; what only waits gets a quiet notification instead.
            keepAwake(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            showWaiting(this)
            stopSelf()
            return
        }
        cancelWaiting(this)
        keepAwake(Downloads.busy())
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
        // Those waiting for Wi-Fi or the night come last: what the notification is about is what runs.
        val active = Downloads.all().filter {
            it.status == Downloads.RUNNING || it.status == Downloads.QUEUED || it.status == Downloads.CONVERTING
        }.sortedBy { if (Downloads.waiting(it)) 1 else 0 }
        val first = active.firstOrNull()
        val title = when {
            first == null -> "Downloads"
            // 3.0: an incognito download's name never shows (the notification is on the lock screen too).
            active.size == 1 -> if (first.incognito) "Downloading a file" else first.fileName
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

        /** 3.0: the quiet notification of downloads that only wait. */
        private const val WAITING_ID = 2

        /** 3.0.6: the notification of downloads Android's background limit paused. */
        private const val PAUSED_ID = 3

        /** 3.0: finished downloads' notifications have this tag, their download's number as id (no clash with others). */
        private const val TAG_DONE = "download"
        private const val ACTION_PAUSE_ALL = "com.swiftbrowser.PAUSE_ALL_DOWNLOADS"
        private const val ACTION_WAKE = "com.swiftbrowser.WAKE_DOWNLOADS"

        /** 3.0: the wake lock's limit, and how often it is taken again while downloads run. */
        private const val WAKE_MS = 10 * 60_000L
        private const val RENEW_MS = 60_000L

        /** The service while it runs (on the main thread). */
        private var running: DownloadService? = null
        private val mainThread = Handler(Looper.getMainLooper())

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

        /**
         * 3.0: the service as the downloads are now: started while something downloads (or is about
         * to); otherwise the quiet notification of those that wait, or none.
         */
        fun sync(ctx: Context) {
            // Torrents report from their own thread: the service is only ever touched on the main one.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainThread.post { sync(ctx) }
                return
            }
            val s = running
            if (Downloads.needsService()) {
                if (s != null) {
                    s.update()
                    return
                }
                try {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
                } catch (e: Exception) {
                    L.w("Could not start download service", e)
                }
            } else if (s != null) {
                s.update()
            } else {
                showWaiting(ctx)
            }
        }

        /** 3.0: "2 downloads are waiting", quietly (not in front, and it can be swiped away). */
        private fun showWaiting(ctx: Context) {
            val waiting = Downloads.all().filter { Downloads.waiting(it) }
            if (waiting.isEmpty()) {
                cancelWaiting(ctx)
                return
            }
            ensureChannels(ctx)
            val first = waiting.first()
            val title = if (waiting.size == 1) {
                if (first.incognito) "A download is waiting" else first.fileName
            } else {
                "${waiting.size} downloads are waiting"
            }
            val text = waiting.mapNotNull { it.error }.distinct().let { reasons ->
                if (reasons.size == 1) reasons[0] else "For Wi-Fi, the night or the charger, as you chose"
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(openDownloads(ctx))
                .build()
            post(ctx, null, WAITING_ID, n)
        }

        private fun cancelWaiting(ctx: Context) {
            runCatching { NotificationManagerCompat.from(ctx).cancel(WAITING_ID) }
        }

        /** 3.0.6: Android's 6 hours a day of background downloading are up (DownloadService.onTimeout). */
        private fun notifyPausedByAndroid(ctx: Context, count: Int) {
            ensureChannels(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (count == 1) "A download is paused" else "$count downloads are paused")
                .setContentText("Open Swift to go on")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Android lets apps download in the background for 6 hours a day. Open Swift and they go on by themselves.",
                    ),
                )
                .setAutoCancel(true)
                .setContentIntent(openDownloads(ctx))
                .build()
            post(ctx, null, PAUSED_ID, n)
        }

        /** 3.0.6: Swift is open again and those downloads go on: the notification about them goes. */
        fun cancelPausedByAndroid(ctx: Context) {
            runCatching { NotificationManagerCompat.from(ctx).cancel(PAUSED_ID) }
        }

        /**
         * 3.0: Swift is woken at [at] (wall-clock ms) for downloads waiting for a time; null: not.
         * One alarm at a time, the earliest wanted.
         */
        fun wakeAt(ctx: Context, at: Long?) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val i = Intent(ctx, DownloadService::class.java).setAction(ACTION_WAKE)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(ctx, 2, i, flags) else PendingIntent.getService(ctx, 2, i, flags)
            try {
                if (at == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: Exception) {
                L.w("Couldn't set the downloads' alarm", e)
            }
        }

        /** The alarm's wake lock: a minute for waiting downloads to start (then the service's own takes over). */
        private fun awakeBriefly(ctx: Context) {
            try {
                (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "swift:downloads-alarm")
                    .acquire(60_000L)
            } catch (e: Exception) {
                L.w("Couldn't wake for downloads", e)
            }
        }

        private fun openDownloads(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun post(ctx: Context, tag: String?, id: Int, n: Notification) {
            val nm = NotificationManagerCompat.from(ctx)
            // 3.0: Android 13 and later ask before showing notifications.
            if (!nm.areNotificationsEnabled()) return
            try {
                nm.notify(tag, id, n)
            } catch (_: SecurityException) {
            }
        }

        fun notifyFinished(ctx: Context, item: Downloads.Item) {
            ensureChannels(ctx)
            // 2.9.2: a torrent's film plays in Swift's player (Play); 3.0: a downloaded video or song too.
            val media = if (item.kind == Downloads.KIND_TORRENT) com.swiftbrowser.torrent.Torrents.mainFile(item) else File(item.filePath)
            val playable = media?.takeIf { PlayerActivity.kindOf(it.name) != null && it.isFile }
            // Their own request codes (2 apart), so one download's Play never opens another's file.
            val play = playable?.let {
                PendingIntent.getActivity(
                    ctx, (item.id * 2).toInt(), PlayerActivity.intentFor(ctx, it, item.incognito),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val open = if (item.kind == Downloads.KIND_TORRENT) null else DownloadsActivity.openIntent(ctx, item)
            val pi = when {
                play != null -> play
                open != null -> PendingIntent.getActivity(ctx, (item.id * 2 + 1).toInt(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                else -> openDownloads(ctx)
            }
            val size = UrlUtils.formatBytes(item.done)
            val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notify)
                // 3.0: an incognito download's name never shows.
                .setContentTitle(if (item.incognito) "Download finished" else item.fileName)
                .setContentText(if (item.incognito) "From an incognito tab · $size" else "Downloaded · $size")
                .setVisibility(if (item.incognito) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .apply { if (play != null) addAction(R.drawable.ic_play, "Play", play) }
                .build()
            post(ctx, TAG_DONE, item.id.toInt(), n)
        }
    }
}
