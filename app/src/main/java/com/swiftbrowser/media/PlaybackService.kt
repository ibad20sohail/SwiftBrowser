package com.swiftbrowser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.Favicons
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.tabs.Tab
import com.swiftbrowser.tabs.TabManager
import com.swiftbrowser.ui.MainActivity

/**
 * Keep playing in the background: while a page plays a video or music, a media notification (the
 * site's picture, the title, previous / play-pause / next, the sleep timer) keeps Swift running in
 * the background or with the screen off, also on the lock screen. Android's media session makes
 * headset and Bluetooth buttons control it, and unplugging headphones pauses it. While something
 * plays, the processor and Wi-Fi stay awake so streams don't stall with the screen off.
 */
class PlaybackService : Service() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var session: MediaSession? = null
    private var noisyRegistered = false

    /** Headphones unplugged (or a Bluetooth headset gone): pause, as music apps do. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                L.d("Headphones unplugged: pausing")
                Playback.tab?.mediaSession?.pause()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tab = Playback.tab
        when (intent?.action) {
            ACTION_PLAY -> tab?.mediaSession?.play()
            ACTION_PAUSE -> tab?.mediaSession?.pause()
            ACTION_NEXT -> Playback.next()
            ACTION_PREVIOUS -> Playback.previous()
            ACTION_TIMER -> SleepTimer.cycle(this)
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
        updateSession(tab)
        val n = notification(tab)
        if (tab.playing) {
            startForeground(NOTIFICATION_ID, n)
            holdLocks(true)
        } else {
            // Paused: the notification stays (to go on), but Android may close the app again.
            holdLocks(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ Android's media session

    private fun mediaSession(): MediaSession = session ?: MediaSession(this, "Swift").also { s ->
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                Playback.tab?.mediaSession?.play()
            }

            override fun onPause() {
                Playback.tab?.mediaSession?.pause()
            }

            override fun onStop() {
                Playback.tab?.mediaSession?.pause()
            }

            override fun onSkipToNext() = Playback.next()

            override fun onSkipToPrevious() = Playback.previous()

            override fun onFastForward() {
                Playback.tab?.mediaSession?.seekForward()
            }

            override fun onRewind() {
                Playback.tab?.mediaSession?.seekBackward()
            }

            override fun onSeekTo(pos: Long) {
                Playback.tab?.mediaSession?.seekTo(pos / 1000.0, false)
            }
        })
        s.setSessionActivity(openIntent(Playback.tab))
        session = s
    }

    private fun updateSession(tab: Tab) {
        val s = mediaSession()
        val position = Playback.position
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
            )
            .setState(
                if (tab.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                ((position?.position ?: 0.0) * 1000).toLong(),
                if (tab.playing) (position?.playbackRate ?: 1.0).toFloat() else 0f,
                SystemClock.elapsedRealtime(),
            )
            .build()
        s.setPlaybackState(state)
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, Playback.displayTitle(tab))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, Playback.subtitle(tab))
        position?.duration?.takeIf { it > 0 }?.let { meta.putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong()) }
        Playback.artwork(tab)?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        s.setMetadata(meta.build())
        s.isActive = true
        setNoisyWatch(tab.playing)
    }

    private fun setNoisyWatch(on: Boolean) {
        if (on == noisyRegistered) return
        noisyRegistered = on
        try {
            if (on) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) else unregisterReceiver(noisy)
        } catch (e: Exception) {
            L.w("Headphone watch", e)
        }
    }

    // ------------------------------------------------------------------ keeping the phone awake

    @Suppress("DEPRECATION")
    private fun holdLocks(on: Boolean) {
        try {
            if (on) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "swift:playback").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swift:playback").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } else {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                wifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
            }
        } catch (e: Exception) {
            L.w("Playback locks", e)
        }
    }

    private fun stop() {
        holdLocks(false)
        setNoisyWatch(false)
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        holdLocks(false)
        setNoisyWatch(false)
        session?.release()
        session = null
        // A paused tab's notification isn't tied to the service; it goes with it all the same.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the notification

    private fun openIntent(tab: Tab?): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).also { i -> tab?.let { i.putExtra(MainActivity.EXTRA_TAB_ID, it.id) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(tab: Tab): Notification {
        ensureChannel(this)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val timer = SleepTimer.label()
        b.setSmallIcon(R.drawable.ic_notify_logo)
            .setContentTitle(Playback.displayTitle(tab))
            .setContentText(listOfNotNull(Playback.subtitle(tab), timer?.let { "Sleep: $it" }).joinToString(" · "))
            .setContentIntent(openIntent(tab))
            .setOngoing(tab.playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setDeleteIntent(action(ACTION_CLOSE, 9))
            .addAction(button(R.drawable.ic_fast_rewind, "Previous", ACTION_PREVIOUS, 4))
            .addAction(
                if (tab.playing) button(R.drawable.ic_pause, "Pause", ACTION_PAUSE, 1) else button(R.drawable.ic_play, "Play", ACTION_PLAY, 1),
            )
            .addAction(button(R.drawable.ic_fast_forward, "Next", ACTION_NEXT, 5))
            .addAction(button(R.drawable.ic_timer, timer ?: "Sleep timer", ACTION_TIMER, 3))
            .addAction(button(R.drawable.ic_close, "Close", ACTION_CLOSE, 2))
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession().sessionToken).setShowActionsInCompactView(0, 1, 2))
        Playback.artwork(tab)?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun button(icon: Int, title: String, name: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, action(name, code)).build()

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
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREVIOUS = "previous"
        private const val ACTION_CLOSE = "close"
        private const val ACTION_TIMER = "timer"

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
    private var art: Bitmap? = null

    /** What the page can do (MediaSession.Feature flags), e.g. go to the next track. */
    var features = 0L
        private set
    var position: org.mozilla.geckoview.MediaSession.PositionState? = null
        private set

    /** The user closed the notification: it comes back when something plays again. */
    private var dismissed = false

    /**
     * The controls are there whenever something plays (headset buttons, unplugging headphones),
     * whether or not it may keep playing in the background.
     */
    fun wanted(): Boolean {
        val t = tab ?: return false
        return !dismissed && t.mediaSession != null && t in TabManager.tabs
    }

    fun displayTitle(t: Tab): String = title?.takeIf { it.isNotBlank() } ?: t.displayTitle

    fun subtitle(t: Tab): String =
        listOfNotNull(artist?.takeIf { it.isNotBlank() }, UrlUtils.host(t.url)?.removePrefix("www.")).joinToString(" • ")

    /** The page's own artwork, or else the tab's picture or the site's icon. */
    fun artwork(t: Tab): Bitmap? = art ?: com.swiftbrowser.tabs.Thumbnails.cached(t) ?: Favicons.cached(t.url)

    fun onPlayingChanged(ctx: Context, t: Tab) {
        if (t.playing) {
            if (tab !== t) {
                title = null
                artist = null
                art = null
                features = 0
                position = null
            }
            tab = t
            dismissed = false
        }
        if (tab === t) PlaybackService.sync(ctx)
    }

    fun onMetadata(ctx: Context, t: Tab, meta: org.mozilla.geckoview.MediaSession.Metadata) {
        if (tab !== t) return
        title = meta.title
        artist = meta.artist
        PlaybackService.sync(ctx)
        meta.artwork?.getBitmap(256)?.accept({ bmp ->
            if (tab === t && bmp != null) {
                art = bmp
                PlaybackService.sync(ctx)
            }
        }, { })
    }

    fun onFeatures(t: Tab, f: Long) {
        if (tab === t) features = f
    }

    fun onPosition(ctx: Context, t: Tab, p: org.mozilla.geckoview.MediaSession.PositionState) {
        if (tab !== t) return
        position = p
        PlaybackService.sync(ctx)
    }

    /** The next track if the page has one (a playlist), otherwise 10 seconds on. */
    fun next() {
        val t = tab ?: return
        val s = t.mediaSession ?: return
        if (features and org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK != 0L) s.nextTrack() else Helper.seekPlaying(t, 10.0)
    }

    fun previous() {
        val t = tab ?: return
        val s = t.mediaSession ?: return
        if (features and org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK != 0L) s.previousTrack() else Helper.seekPlaying(t, -10.0)
    }

    /** The tab's media went away (page changed, tab closed or put to sleep). */
    fun onMediaGone(ctx: Context, t: Tab) {
        if (tab !== t) return
        tab = null
        title = null
        artist = null
        art = null
        features = 0
        position = null
        PlaybackService.sync(ctx)
    }

    fun dismiss() {
        dismissed = true
    }
}
