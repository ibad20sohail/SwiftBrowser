package com.swiftbrowser.files

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swiftbrowser.R
import com.swiftbrowser.core.L
import com.swiftbrowser.core.UrlUtils
import com.swiftbrowser.ui.WifiTransferActivity

/**
 * The Wi-Fi transfer running now (3.0), whoever keeps it: its screen while that is open, or
 * [WifiTransferService] once the screen is left in the middle of a transfer. With it, full Wi-Fi
 * speed while it runs, and (in the background) the phone kept awake.
 */
object WifiTransfer {
    @Volatile
    var server: WifiServer? = null
        private set

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** [s] runs now (its screen started it). */
    fun started(ctx: Context, s: WifiServer) {
        server = s
        holdWifi(ctx, true)
    }

    /**
     * Stops the server (Stop, the screen closed with nothing moving, two quiet minutes in the
     * background); [fromService] when the service itself stops it (it then stops itself too).
     */
    fun stop(ctx: Context, fromService: Boolean = false) {
        server?.stop()
        server = null
        holdWifi(ctx, false)
        holdAwake(ctx, false)
        if (!fromService) WifiTransferService.takeBack(ctx)
    }

    /** Full Wi-Fi speed (Android may slow Wi-Fi down to save the battery otherwise). */
    @Suppress("DEPRECATION")
    private fun holdWifi(ctx: Context, on: Boolean) {
        try {
            if (on) {
                if (wifiLock == null) {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swift:wifi-transfer").apply { setReferenceCounted(false) }
                }
                wifiLock?.acquire()
            } else {
                wifiLock?.takeIf { it.isHeld }?.release()
            }
        } catch (e: Exception) {
            L.w("Wi-Fi transfer: no Wi-Fi lock (${e.javaClass.simpleName})")
        }
    }

    /** The phone stays awake while the transfer goes on in the background (the screen may be off). */
    internal fun holdAwake(ctx: Context, on: Boolean) {
        try {
            if (on) {
                if (wakeLock == null) {
                    val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swift:wifi-transfer").apply { setReferenceCounted(false) }
                }
                // Never for ever: the service renews it while files move.
                wakeLock?.acquire(WifiTransferService.AWAKE_MS)
            } else {
                wakeLock?.takeIf { it.isHeld }?.release()
            }
        } catch (e: Exception) {
            L.w("Wi-Fi transfer: no wake lock (${e.javaClass.simpleName})")
        }
    }
}

/**
 * Wi-Fi transfer going on with its screen left (3.0): Home, the screen turned off, or a call in the
 * middle of a 2 GB upload used to stop the server and delete the half file. While something is being
 * sent or received, leaving the screen hands the server to this service: a notification ("Wi-Fi
 * transfer: receiving photo.jpg…", with Stop), the phone kept awake and Wi-Fi at full speed. It stops
 * by itself two minutes after the last transfer, or when the screen opens again (which takes the
 * server back and stops it when closed with nothing moving).
 */
class WifiTransferService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        started = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = WifiTransfer.server
        ensureChannel(this)
        // The notification first, whatever comes next (Android 8+ insists on it for a service started so).
        try {
            startForeground(ID, build(s))
        } catch (e: Exception) {
            // Android 12+ may refuse a start from the background: the transfer still goes on while Swift runs.
            L.w("Wi-Fi transfer: no background notification", e)
        }
        when {
            intent?.action == ACTION_STOP || s == null || !s.running -> {
                if (intent?.action == ACTION_STOP) L.d("Wi-Fi transfer: stopped from its notification")
                wanted = false
                if (s != null) WifiTransfer.stop(this, fromService = true)
                stopSelf()
                return START_NOT_STICKY
            }
            // Its screen took it back (opened again) meanwhile.
            intent?.action == ACTION_RELEASE || !wanted -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        WifiTransfer.holdAwake(this, true)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        L.d("Wi-Fi transfer: goes on in the background")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        handler.removeCallbacks(tick)
        WifiTransfer.holdAwake(this, false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 3.0.6: Android 15 and later allow 6 hours a day of transfers in the background, then call this:
     * the service must stop within seconds or Android closes Swift. The transfer stops as after two
     * quiet minutes; opening Wi-Fi transfer starts it again.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        L.w("Wi-Fi transfer: Android's 6 hours a day in the background are used up, stopped")
        wanted = false
        handler.removeCallbacks(tick)
        WifiTransfer.stop(this, fromService = true)
        stopSelf()
    }

    /** Two quiet minutes: the server stops; otherwise the notification says what moves. */
    private fun update() {
        val s = WifiTransfer.server
        if (s == null || !s.running) {
            stopSelf()
            return
        }
        if (!s.busyWithin(IDLE_MS)) {
            L.d("Wi-Fi transfer: two quiet minutes in the background, stopped")
            wanted = false
            WifiTransfer.stop(this, fromService = true)
            stopSelf()
            return
        }
        if (s.busy) WifiTransfer.holdAwake(this, true)
        try {
            NotificationManagerCompat.from(this).notify(ID, build(s))
        } catch (_: SecurityException) {
        }
    }

    private fun build(s: WifiServer?): Notification {
        val name = s?.receivingName
        val text = when {
            s == null -> "Stopping…"
            name != null -> "Receiving “$name”…"
            s.receiving.get() > 0 -> "Receiving from your computer…"
            s.sending.get() > 0 -> "Sending to your computer…"
            else -> "Nothing moving now. It stops by itself in a couple of minutes."
        }
        val moved = if (s == null) "" else listOfNotNull(
            s.bytesReceived.get().takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) + " in" },
            s.bytesSent.get().takeIf { it > 0 }?.let { UrlUtils.formatBytes(it) + " out" },
        ).joinToString(" · ")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WifiTransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WifiTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_files)
            .setContentTitle("Wi-Fi transfer")
            .setContentText(text)
            .setSubText(moved.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, s?.busy == true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "wifi_transfer"
        private const val ID = 7110
        private const val ACTION_STOP = "com.swiftbrowser.files.WIFI_STOP"
        private const val ACTION_RELEASE = "com.swiftbrowser.files.WIFI_RELEASE"
        private const val TICK_MS = 2000L

        /** Stops this long after the last transfer. */
        private const val IDLE_MS = 2 * 60_000L

        /** The wake lock lasts at most this long unless renewed (a transfer still moving renews it). */
        internal const val AWAKE_MS = 10 * 60_000L

        /** The service should keep the server (the screen was left mid-transfer and isn't back). */
        @Volatile
        private var wanted = false

        /** The service is running. */
        @Volatile
        private var started = false

        /** Keeps the running server going in the background (its screen is being left mid-transfer). */
        fun keep(ctx: Context) {
            wanted = true
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WifiTransferService::class.java))
            } catch (e: Exception) {
                wanted = false
                L.w("Wi-Fi transfer: couldn't go on in the background", e)
            }
        }

        /**
         * The screen is back (or the transfer stopped): the service lets go, and its notification goes.
         * Told through the service itself, never by stopping it from outside before it is up.
         */
        fun takeBack(ctx: Context) {
            wanted = false
            if (!started) return
            runCatching { ctx.startService(Intent(ctx, WifiTransferService::class.java).setAction(ACTION_RELEASE)) }
        }

        private fun ensureChannel(ctx: Context) {
            NotificationManagerCompat.from(ctx).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Wi-Fi transfer").build(),
            )
        }
    }
}
