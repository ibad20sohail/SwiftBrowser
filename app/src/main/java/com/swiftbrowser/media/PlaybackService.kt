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
 * plays out of sight, the processor and Wi-Fi stay awake so streams don't stall with the screen off.
 */
class PlaybackService : Service() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var session: MediaSession? = null
    private var noisyRegistered = false

    /** What the media session and the notification show now: an update that changes neither sends neither again (2.6). */
    private var shownMeta: Meta? = null
    private var shownLook: Look? = null
    private var foreground = false

    /** The notification's buttons and its way back to the tab, made once rather than with every update (2.6). */
    private val buttons = HashMap<String, PendingIntent>()
    private var tabIntent: PendingIntent? = null
    private var tabIntentFor: Long? = null

    /** What the media session shows besides where the media is. */
    private data class Meta(val title: String, val subtitle: String, val art: Bitmap?, val seconds: Long)

    /** What the notification shows. */
    private data class Look(val tab: Long, val title: String, val subtitle: String, val art: Bitmap?, val playing: Boolean, val timer: String?)

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

    override fun onCreate() {
        super.onCreate()
        running = this
        ensureChannel(this)
    }

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
        refresh(tab)
        return START_NOT_STICKY
    }

    /**
     * Brings the media session, the notification and the locks up to date with [tab]. Where the media
     * is goes to the session every time; its title and picture, and the notification, only when they
     * changed (2.6: both used to be made again, picture and all, with every position update).
     */
    private fun refresh(tab: Tab) {
        val title = Playback.displayTitle(tab)
        val subtitle = Playback.subtitle(tab)
        val art = Playback.artwork(tab)
        updateSession(tab, title, subtitle, art)
        val look = Look(tab.id, title, subtitle, art, tab.playing, SleepTimer.label())
        if (tab.playing) {
            if (!foreground || look != shownLook) {
                startForeground(NOTIFICATION_ID, notification(look))
                foreground = true
                shownLook = look
            }
        } else if (foreground || look != shownLook) {
            // Paused: the notification stays (to go on), but Android may close the app again.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(look))
            shownLook = look
        }
        updateLocks()
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
        s.setSessionActivity(openIntent(Playback.tab?.id))
        session = s
    }

    private fun updateSession(tab: Tab, title: String, subtitle: String, art: Bitmap?) {
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
        // The picture travels to Android with the metadata: only sent again when something in it changed.
        val duration = position?.duration?.takeIf { it > 0 }
        val now = Meta(title, subtitle, art, duration?.toLong() ?: 0L)
        if (now != shownMeta) {
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            duration?.let { meta.putLong(MediaMetadata.METADATA_KEY_DURATION, (it * 1000).toLong()) }
            art?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
            s.setMetadata(meta.build())
            shownMeta = now
        }
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

    /**
     * Held while something plays and Swift is out of sight (2.6): on screen, the phone is awake anyway,
     * and the Wi-Fi lock would keep Wi-Fi from saving power for nothing.
     */
    private fun updateLocks() = holdLocks(session != null && Playback.tab?.playing == true && !onScreen)

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
        shownMeta = null
        shownLook = null
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        holdLocks(false)
        setNoisyWatch(false)
        session?.release()
        session = null
        // A paused tab's notification isn't tied to the service; it goes with it all the same.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the notification

    /** Opens Swift on the playing tab; made again only for another tab. */
    private fun openIntent(tabId: Long?): PendingIntent {
        tabIntent?.let { if (tabIntentFor == tabId) return it }
        return PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).also { i -> tabId?.let { i.putExtra(MainActivity.EXTRA_TAB_ID, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).also {
            tabIntent = it
            tabIntentFor = tabId
        }
    }

    private fun notification(look: Look): Notification {
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val timer = look.timer
        b.setSmallIcon(R.drawable.ic_notify_logo)
            .setContentTitle(look.title)
            .setContentText(listOfNotNull(look.subtitle, timer?.let { "Sleep: $it" }).joinToString(" · "))
            .setContentIntent(openIntent(look.tab))
            .setOngoing(look.playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setDeleteIntent(action(ACTION_CLOSE, 9))
            .addAction(button(R.drawable.ic_fast_rewind, "Previous", ACTION_PREVIOUS, 4))
            .addAction(
                if (look.playing) button(R.drawable.ic_pause, "Pause", ACTION_PAUSE, 1) else button(R.drawable.ic_play, "Play", ACTION_PLAY, 1),
            )
            .addAction(button(R.drawable.ic_fast_forward, "Next", ACTION_NEXT, 5))
            .addAction(button(R.drawable.ic_timer, timer ?: "Sleep timer", ACTION_TIMER, 3))
            .addAction(button(R.drawable.ic_close, "Close", ACTION_CLOSE, 2))
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession().sessionToken).setShowActionsInCompactView(0, 1, 2))
        look.art?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun button(icon: Int, title: String, name: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, action(name, code)).build()

    private fun action(name: String, code: Int): PendingIntent = buttons.getOrPut("$name/$code") {
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

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

        /** The service while it runs. It, and everything that tells it about playback, runs on the main thread. */
        private var running: PlaybackService? = null

        /** Whether Swift is on screen; until MainActivity first says, taken as not (the locks are held as before 2.6). */
        private var onScreen = false

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

        /**
         * Where the media is (or its speed) changed, which pages say often while they play (2.6): the
         * running service hears it directly, without a round trip through Android, and only the
         * media session is told unless something shown changed too.
         */
        fun positionChanged(ctx: Context) {
            val service = running
            val tab = Playback.tab
            if (service != null && service.session != null && tab != null && tab.mediaSession != null && Playback.wanted()) {
                service.refresh(tab)
            } else {
                sync(ctx)
            }
        }

        /**
         * Swift came on screen or went out of sight (MainActivity's onStart and onStop, 2.6): the
         * processor and Wi-Fi are only kept awake for playback while it is out of sight.
         */
        fun setOnScreen(visible: Boolean) {
            onScreen = visible
            running?.updateLocks()
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

    /**
     * The tab's picture or site icon last used when the page has no artwork of its own (2.6): kept,
     * as Swift lets go of the pictures in memory when it goes out of sight.
     */
    private var lastArt: Bitmap? = null

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

    // An incognito tab shows nothing about its page (the notification is also on the lock screen).
    fun displayTitle(t: Tab): String = if (t.incognito) "Playing in an incognito tab" else title?.takeIf { it.isNotBlank() } ?: t.displayTitle

    fun subtitle(t: Tab): String = if (t.incognito) "Swift" else
        listOfNotNull(artist?.takeIf { it.isNotBlank() }, UrlUtils.host(t.url)?.removePrefix("www.")).joinToString(" · ")

    /** The page's own artwork, or else the tab's picture or the site's icon (none in incognito). */
    fun artwork(t: Tab): Bitmap? {
        if (t.incognito) return null
        art?.let { return it }
        val found = com.swiftbrowser.tabs.Thumbnails.cached(t) ?: Favicons.cached(t.url)
        if (t !== tab) return found
        if (found != null) lastArt = found
        return lastArt
    }

    fun onPlayingChanged(ctx: Context, t: Tab) {
        if (t.playing) {
            if (tab !== t) {
                title = null
                artist = null
                art = null
                lastArt = null
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
        PlaybackService.positionChanged(ctx)
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
        lastArt = null
        features = 0
        position = null
        PlaybackService.sync(ctx)
    }

    fun dismiss() {
        dismissed = true
    }
}
