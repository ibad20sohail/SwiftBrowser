package com.swiftbrowser.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.swiftbrowser.core.L
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Read aloud in reader view: the phone's own text-to-speech reads the article paragraph by
 * paragraph. The reader page sends the paragraphs and asks how far it got (to mark the paragraph);
 * nothing runs while nothing is being read.
 */
object ReadAloud : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed: String? = null
    private var parts: List<String> = emptyList()
    private var lang: String? = null

    @Volatile private var index = 0
    @Volatile private var speaking = false

    /** Told (on the main thread) which paragraph is being read, and when reading stops. */
    var listener: ((JSONObject) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun state(): JSONObject = JSONObject().put("type", "tts-state").put("speaking", speaking).put("index", index)
        .put("total", parts.size).also { o -> failed?.let { o.put("error", it) } }

    private fun report() {
        main.post { listener?.invoke(state()) }
    }

    /** Handles a message from the reader page; the answer is the reading state. */
    fun onMessage(ctx: Context, msg: JSONObject): JSONObject {
        when (msg.optString("type")) {
            "tts-start" -> {
                val texts = msg.optJSONArray("texts") ?: JSONArray()
                start(ctx, (0 until texts.length()).map { texts.optString(it) }, msg.optInt("from", 0), msg.optString("lang"))
            }
            "tts-pause" -> pause()
            "tts-resume" -> resume()
            "tts-stop" -> stop()
        }
        report()
        return state()
    }

    private fun start(ctx: Context, texts: List<String>, from: Int, language: String?) {
        parts = texts
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
        engine.stop()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        for (i in from until parts.size) {
            // Long paragraphs are said in pieces, split where sentences end.
            chunks(parts[i], max).forEachIndexed { k, piece ->
                engine.speak(piece, TextToSpeech.QUEUE_ADD, null, "$i.$k")
            }
        }
        engine.speak(" ", TextToSpeech.QUEUE_ADD, null, "end")
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
        override fun onStart(utteranceId: String?) {
            utteranceId?.substringBefore('.')?.toIntOrNull()?.let {
                if (it != index) {
                    index = it
                    report()
                } else if (utteranceId.endsWith(".0")) {
                    report()
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == "end") {
                speaking = false
                index = 0
                report()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            L.w("Read aloud failed at $utteranceId")
        }
    }

    private fun pause() {
        speaking = false
        tts?.stop()
    }

    private fun resume() {
        if (parts.isEmpty()) return
        speaking = true
        if (ready) speakFrom(index)
    }

    fun stop() {
        speaking = false
        index = 0
        tts?.stop()
    }
}
