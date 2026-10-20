package com.swiftbrowser.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.ui.MainActivity

/**
 * Background play: while a page plays a video or music, a notification with play/pause keeps
 * Swift running when it is in the background or the screen is off (the engine itself keeps the
 * phone awake while sound plays). Without it Android would soon close the app and stop the sound.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tab = Playback.tab
        when (intent?.action) {
            ACTION_PLAY -> tab?.mediaSession?.play()
            ACTION_PAUSE -> tab?.mediaSession?.pause()
            ACTION_CLOSE -> {
                tab?.mediaSession?.pause()
                Playback.dismiss()
                stop()
                return START_NOT_STICKY
            }
        }
        if (tab == null || tab.mediaSession == null) {
            stop()
            return START_NOT_STICKY
        }
        val n = notification(tab)
        if (tab.playing) {
            startForeground(NOTIFICATION_ID, n)
        } else {
            // Paused: the notification stays (to resume), but Android may close the app again.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        // A paused tab's notification isn't tied to the service; it goes with it all the same.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun notification(tab: Tab): android.app.Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB_ID, tab.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = Playback.title?.takeIf { it.isNotBlank() } ?: tab.displayTitle
        val site = UrlUtils.host(tab.url)?.removePrefix("www.").orEmpty()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(listOfNotNull(Playback.artist?.takeIf { it.isNotBlank() }, site).joinToString(" • "))
            .setContentIntent(open)
            .setOngoing(tab.playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                if (tab.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (tab.playing) "Pause" else "Play",
                action(if (tab.playing) ACTION_PAUSE else ACTION_PLAY, 1),
            )
            .addAction(R.drawable.ic_close, "Close", action(ACTION_CLOSE, 2))
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 4100
        private const val ACTION_SYNC = "sync"
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_CLOSE = "close"

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing videos and music", NotificationManager.IMPORTANCE_LOW))
        }

        /** Shows, updates or removes the notification for the current state of [Playback]. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, PlaybackService::class.java).setAction(ACTION_SYNC)
            try {
                if (Playback.wanted()) ctx.startService(intent) else ctx.stopService(intent)
            } catch (e: Exception) {
                L.w("Playback notification failed", e)
            }
        }
    }
}

/** Which tab's media the notification is about, and what it is playing. */
object Playback {
    var tab: Tab? = null
        private set
    var title: String? = null
        private set
    var artist: String? = null
        private set

    /** The user closed the notification: it comes back when something plays again. */
    private var dismissed = false

    fun wanted(): Boolean {
        val t = tab ?: return false
        return SwiftApp.prefs.backgroundPlay && !dismissed && t.mediaSession != null && t in TabManager.tabs
    }

    fun onPlayingChanged(ctx: Context, t: Tab) {
        if (t.playing) {
            if (tab !== t) {
                title = null
                artist = null
            }
            tab = t
            dismissed = false
        }
        if (tab === t) PlaybackService.sync(ctx)
    }

    fun onMetadata(ctx: Context, t: Tab, title: String?, artist: String?) {
        if (tab !== t) return
        this.title = title
        this.artist = artist
        PlaybackService.sync(ctx)
    }

    /** The tab's media went away (page changed, tab closed or put to sleep). */
    fun onMediaGone(ctx: Context, t: Tab) {
        if (tab !== t) return
        tab = null
        title = null
        artist = null
        PlaybackService.sync(ctx)
    }

    fun dismiss() {
        dismissed = true
    }
}
