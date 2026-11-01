package com.swiftbrowser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.ui.Palette
import com.swiftbrowser.ui.PlayerActivity

/**
 * 2.9: music from Swift's player (and a video's sound with "Play in background") going on with the
 * screen off or Swift left. Its notification has the cover, the title, previous / play-pause / next
 * and Close, also on the lock screen; Android's media session makes headset and Bluetooth buttons
 * control it. The player itself pauses for calls and other apps' sound, and when headphones are
 * unplugged (LocalPlayback). Like the pages' PlaybackService, which it sits next to.
 */
class MusicService : Service() {
    private var session: MediaSession? = null
    private var foreground = false

    /** What the notification and the media session show now: unchanged, neither is sent again. */
    private var shownLook: Look? = null
    private var shownMeta: Meta? = null

    /** The notification's buttons and its way back to the player, made once. */
    private val buttons = HashMap<String, PendingIntent>()
    private var screenIntent: PendingIntent? = null

    private data class Look(val title: String, val text: String, val art: Bitmap?, val playing: Boolean, val incognito: Boolean)

    private data class Meta(val title: String, val artist: String, val art: Bitmap?, val duration: Long)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started to be in the foreground: Android wants its notification within seconds, whatever
        // happens next.
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true && !foreground) goForeground(notification(look()))
        when (intent?.action) {
            ACTION_PLAY -> LocalPlayback.play()
            ACTION_PAUSE -> LocalPlayback.pause()
            ACTION_NEXT -> LocalPlayback.next()
            ACTION_PREVIOUS -> LocalPlayback.previous()
            ACTION_CLOSE -> {
                LocalPlayback.release()
                stop()
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> LocalPlayback.dismiss()
        }
        if (!LocalPlayback.wantsService()) {
            stop()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    /** The media session, the notification and foreground or not, as the player is now. */
    private fun refresh() {
        updateSession()
        val look = look()
        if (look.playing) {
            if (!foreground || look != shownLook) {
                goForeground(notification(look))
                shownLook = look
            }
        } else if (foreground || look != shownLook) {
            // Paused: the notification stays (to go on, or be swiped away), but Android may close Swift again.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            foreground = false
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(look))
            } catch (e: SecurityException) {
                L.w("Player notification not allowed", e)
            }
            shownLook = look
        }
    }

    private fun goForeground(n: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            foreground = true
        } catch (e: Exception) {
            L.w("Player notification couldn't stay in front", e)
        }
    }

    private fun look(): Look {
        val e = LocalPlayback.current
        val incognito = e?.incognito == true
        val timer = LocalPlayback.sleepLabel()?.let { "Sleep: $it" }
        // An incognito tab's video says nothing about itself (the notification is on the lock screen too).
        val title = if (incognito) "Playing a video from an incognito tab" else LocalPlayback.displayTitle()
        val sub = if (incognito) "Swift" else LocalPlayback.displaySubtitle().ifBlank { "Swift" }
        return Look(title, listOfNotNull(sub, timer).joinToString(" · "), if (incognito) null else LocalPlayback.smallArt, LocalPlayback.playing, incognito)
    }

    // ------------------------------------------------------------------ Android's media session

    private fun mediaSession(): MediaSession = session ?: MediaSession(this, "Swift player").also { s ->
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = LocalPlayback.play()

            override fun onPause() = LocalPlayback.pause()

            override fun onStop() = LocalPlayback.pause()

            override fun onSkipToNext() = LocalPlayback.next()

            override fun onSkipToPrevious() = LocalPlayback.previous()

            override fun onFastForward() = LocalPlayback.seekBy(10_000)

            override fun onRewind() = LocalPlayback.seekBy(-10_000)

            override fun onSeekTo(pos: Long) = LocalPlayback.seekTo(pos)
        })
        s.setSessionActivity(screenIntent())
        session = s
    }

    /** Where it is goes to the session every time; the title and picture only when they changed. */
    private fun updateSession() {
        val p = LocalPlayback.player ?: return
        val s = mediaSession()
        val playing = LocalPlayback.playing
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    p.currentPosition,
                    if (playing) p.playbackParameters.speed else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        val e = LocalPlayback.current
        val incognito = e?.incognito == true
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val meta = Meta(
            if (incognito) "Playing a video from an incognito tab" else LocalPlayback.displayTitle(),
            if (incognito) "" else LocalPlayback.displaySubtitle(),
            if (incognito) null else LocalPlayback.smallArt,
            duration,
        )
        if (meta != shownMeta) {
            val b = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist)
            if (duration > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            // The lock screen of Android 7 shows the cover behind the clock.
            meta.art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            s.setMetadata(b.build())
            shownMeta = meta
        }
        s.isActive = true
    }

    private fun stop() {
        // Stopping: playing again from now on starts a fresh one.
        if (running === this) running = null
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        shownLook = null
        shownMeta = null
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        if (running === this) running = null
        session?.release()
        session = null
        // A paused notification isn't tied to the service; it goes with it all the same.
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the notification

    private fun screenIntent(): PendingIntent = screenIntent ?: PendingIntent.getActivity(
        this, 0, PlayerActivity.showIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ).also { screenIntent = it }

    private fun notification(look: Look): Notification {
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_notify_logo)
            .setContentTitle(look.title)
            .setContentText(look.text)
            .setContentIntent(screenIntent())
            .setDeleteIntent(action(ACTION_DISMISS, 15))
            .setOngoing(look.playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setColor(Palette.color(this, R.color.accent))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(button(R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS, 11))
            .addAction(
                if (look.playing) button(R.drawable.ic_pause, "Pause", ACTION_PAUSE, 12) else button(R.drawable.ic_play, "Play", ACTION_PLAY, 13),
            )
            .addAction(button(R.drawable.ic_media_next, "Next", ACTION_NEXT, 14))
            .addAction(button(R.drawable.ic_close, "Close", ACTION_CLOSE, 16))
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession().sessionToken).setShowActionsInCompactView(0, 1, 2))
        look.art?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun button(icon: Int, title: String, name: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, action(name, code)).build()

    private fun action(name: String, code: Int): PendingIntent = buttons.getOrPut(name) {
        PendingIntent.getService(
            this, code, Intent(this, MusicService::class.java).setAction(name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** The pages' playback notifications' channel: one "Playing videos and music" in Android's settings. */
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 4200
        private const val EXTRA_FOREGROUND = "foreground"
        private const val ACTION_PLAY = "com.swiftbrowser.player.play"
        private const val ACTION_PAUSE = "com.swiftbrowser.player.pause"
        private const val ACTION_NEXT = "com.swiftbrowser.player.next"
        private const val ACTION_PREVIOUS = "com.swiftbrowser.player.previous"
        private const val ACTION_CLOSE = "com.swiftbrowser.player.close"
        private const val ACTION_DISMISS = "com.swiftbrowser.player.dismiss"

        /** The service while it runs. It, and everything that tells it about the player, runs on the main thread. */
        private var running: MusicService? = null

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing videos and music", NotificationManager.IMPORTANCE_LOW))
        }

        /** Shows, updates or takes away the notification for the player as it is now. */
        fun sync(ctx: Context) {
            val s = running
            if (LocalPlayback.wantsService()) {
                if (s != null) s.refresh() else start(ctx)
            } else {
                s?.stop()
            }
        }

        /** Where it is changed (a seek, another speed): only the media session needs to know. */
        fun positionChanged() {
            running?.takeIf { it.session != null }?.updateSession()
        }

        private fun start(ctx: Context) {
            // Playing, it goes to the foreground at once (Android 8 and later want it said beforehand).
            val front = LocalPlayback.playing
            val i = Intent(ctx, MusicService::class.java).putExtra(EXTRA_FOREGROUND, front)
            try {
                if (front) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
            } catch (e: Exception) {
                L.w("Player notification failed", e)
            }
        }
    }
}
