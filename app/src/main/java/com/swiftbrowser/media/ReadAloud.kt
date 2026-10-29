package com.swiftbrowser.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.swiftbrowser.R
import com.swiftbrowser.SwiftApp
import com.swiftbrowser.core.L
import com.swiftbrowser.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Read aloud in reader view: the phone's own text-to-speech reads the article paragraph by
 * paragraph, at the chosen speed, and tells the reader page which paragraph it is on (to mark it).
 * It goes on with Swift in the background or the screen off, with pause and stop in a
 * notification ([ReadAloudService]). Nothing runs while nothing is being read.
 */
object ReadAloud : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed: String? = null
    private var parts: List<String> = emptyList()
    private var lang: String? = null
    private var rate = 1f

    /** The article's title, for the notification. */
    var title: String = ""
        private set

    /** The tab being read (its notification opens it) and whether it is incognito (no title shown, 2.6). */
    @Volatile var tabId: Long = Long.MIN_VALUE
    @Volatile var incognito = false

    @Volatile private var index = 0

    /**
     * Which run of speech is current: pausing, stopping or skipping starts a new one. Some speech
     * engines still report the pieces a stop threw away (even the last one, "finished"); reports
     * from an older run are ignored.
     */
    @Volatile private var run = 0
    @Volatile var speaking = false
        private set

    /** Paused part-way: the notification offers to go on. */
    val paused: Boolean get() = !speaking && parts.isNotEmpty()

    /** Told (on the main thread) which paragraph is being read, and when reading stops. */
    var listener: ((JSONObject) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Reading reached the end by itself (not stopped): a document goes on with its next pages (2.5). */
    @Volatile private var finished = false

    private fun state(): JSONObject = JSONObject().put("type", "tts-state").put("speaking", speaking).put("paused", paused)
        .put("index", index).put("total", parts.size).put("finished", finished).also { o -> failed?.let { o.put("error", it) } }

    private fun report() {
        // The state as it is now: an error is cleared right after being reported (2.6).
        val s = state()
        main.post {
            listener?.invoke(s)
            SwiftApp.instance?.let { ReadAloudService.sync(it) }
        }
    }

    /** Handles a message from the reader page; the answer is the reading state. */
    fun onMessage(ctx: Context, msg: JSONObject): JSONObject {
        when (msg.optString("type")) {
            "tts-start" -> {
                val texts = msg.optJSONArray("texts") ?: JSONArray()
                title = msg.optString("title")
                rate = msg.optDouble("rate", 1.0).toFloat().coerceIn(0.5f, 3f)
                start(ctx, (0 until texts.length()).map { texts.optString(it) }, msg.optInt("from", 0), msg.optString("lang"))
            }
            "tts-pause" -> pause()
            "tts-resume" -> resume()
            "tts-stop" -> stop()
            "tts-skip" -> skip(msg.optInt("delta", 1))
            "tts-rate" -> setRate(msg.optDouble("rate", 1.0).toFloat())
        }
        report()
        return state()
    }

    /** Reads text selected on a page (2.6), with the notification's controls. */
    fun speak(ctx: Context, text: String, title: String, tabId: Long, incognito: Boolean) {
        this.title = title
        this.tabId = tabId
        this.incognito = incognito
        // At the speed reader view last read at.
        start(ctx, text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }, 0, null)
        report()
    }

    private fun start(ctx: Context, texts: List<String>, from: Int, language: String?) {
        parts = texts
        finished = false
        lang = language?.takeIf { it.isNotBlank() }
        index = from.coerceIn(0, (texts.size - 1).coerceAtLeast(0))
        speaking = true
        failed = null
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(ctx.applicationContext, this).also { it.setOnUtteranceProgressListener(progress) }
        } else if (ready) {
            speakFrom(index)
        }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            failed = "This phone has no text-to-speech voice. Install one (for example Google Text-to-speech) and try again."
            speaking = false
            parts = emptyList()
            tts = null
            report()
            failed = null
            return
        }
        if (speaking) speakFrom(index)
    }

    private fun speakFrom(from: Int) {
        val engine = tts ?: return
        lang?.let { l ->
            val locale = Locale.forLanguageTag(l)
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        }
        engine.setSpeechRate(rate)
        val r = ++run
        engine.stop()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        for (i in from until parts.size) {
            // Long paragraphs are said in pieces, split where sentences end.
            chunks(parts[i], max).forEachIndexed { k, piece ->
                engine.speak(piece, TextToSpeech.QUEUE_ADD, null, "$r:$i.$k")
            }
        }
        engine.speak(" ", TextToSpeech.QUEUE_ADD, null, "$r:end")
    }

    private fun chunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            val cut = rest.lastIndexOf(". ", max).takeIf { it > max / 2 }?.plus(1) ?: rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            out += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private val progress = object : UtteranceProgressListener() {
        /** The part of [id] after the run number, or null if it belongs to an older run. */
        private fun current(id: String?): String? {
            val r = id?.substringBefore(':', "")?.toIntOrNull() ?: return null
            return if (r == run && speaking) id.substringAfter(':') else null
        }

        override fun onStart(utteranceId: String?) {
            val id = current(utteranceId) ?: return
            id.substringBefore('.').toIntOrNull()?.let {
                if (it != index) {
                    index = it
                    report()
                } else if (id.endsWith(".0")) {
                    report()
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            if (current(utteranceId) == "end") {
                speaking = false
                parts = emptyList()
                index = 0
                finished = true
                report()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            L.w("Read aloud failed at $utteranceId")
        }
    }

    fun pause() {
        speaking = false
        run++
        tts?.stop()
        report()
    }

    fun resume() {
        if (parts.isEmpty()) return
        speaking = true
        if (ready) speakFrom(index)
        report()
    }

    /** The next or previous paragraph (also while paused: it starts reading there). */
    private fun skip(delta: Int) {
        if (parts.isEmpty()) return
        index = (index + delta).coerceIn(0, parts.size - 1)
        speaking = true
        if (ready) speakFrom(index)
    }

    private fun setRate(r: Float) {
        rate = r.coerceIn(0.5f, 3f)
        // The paragraph being read starts again at the new speed.
        if (speaking && ready) speakFrom(index)
    }

    fun stop() {
        speaking = false
        finished = false
        run++
        parts = emptyList()
        index = 0
        tts?.stop()
        report()
    }
}

/**
 * Keeps Swift running while an article is read aloud with Swift in the background or the screen
 * off, with pause/go on and stop in a notification.
 */
class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> ReadAloud.pause()
            ACTION_RESUME -> ReadAloud.resume()
            ACTION_STOP -> ReadAloud.stop()
        }
        if (!ReadAloud.speaking && !ReadAloud.paused) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification()
        if (ReadAloud.speaking) {
            startForeground(NOTIFICATION_ID, n)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW))
        }
        // Its own request code (the media notification has 0), so the two don't swap their tabs (2.6).
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ReadAloud.tabId != Long.MIN_VALUE) intent.putExtra(MainActivity.EXTRA_TAB_ID, ReadAloud.tabId)
        val open = PendingIntent.getActivity(this, REQUEST_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val speaking = ReadAloud.speaking
        // An incognito article's title never shows, on the lock screen least of all.
        val private = ReadAloud.incognito
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_logo)
            .setContentTitle(if (private) "Incognito tab" else ReadAloud.title.ifBlank { "Reading aloud" })
            .setContentText(if (speaking) "Reading aloud" else "Paused")
            .setContentIntent(open)
            .setOngoing(speaking)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (speaking) R.drawable.ic_pause else R.drawable.ic_play, if (speaking) "Pause" else "Go on",
                action(if (speaking) ACTION_PAUSE else ACTION_RESUME, 1),
            )
            .addAction(R.drawable.ic_close, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ReadAloudService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 4200
        private const val REQUEST_OPEN = 4201
        private const val ACTION_SYNC = "sync"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"

        /** Shows, updates or removes the notification for what ReadAloud is doing. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, ReadAloudService::class.java).setAction(ACTION_SYNC)
            try {
                if (ReadAloud.speaking || ReadAloud.paused) ctx.startService(intent) else ctx.stopService(intent)
            } catch (e: Exception) {
                L.w("Read aloud notification failed", e)
            }
        }
    }
}
