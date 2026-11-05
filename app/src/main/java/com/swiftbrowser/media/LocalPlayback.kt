package com.swiftbrowser.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.DefaultExtractorsFactory
import com.swiftbrowser.core.Background
import com.swiftbrowser.core.L
import com.swiftbrowser.engine.Net
import java.io.File
import java.util.Locale

/**
 * 2.9: Swift's own player for videos and music on the phone, and for website videos opened in it:
 * one ExoPlayer (AndroidX Media3, decoding with the phone's hardware) shared by the player screen
 * (ui.PlayerActivity) and the notification service ([MusicService]). Music goes on with the screen
 * off or Swift left, and the screen, opened again, finds it where it is.
 *
 * Everything here runs on the main thread; reading pictures and folders goes to [io].
 */
@OptIn(UnstableApi::class)
object LocalPlayback {
    private const val MAX_BOOST_MB = 1000

    /** One video or song of the list. */
    class Entry(
        val uri: Uri,
        /** Its name (a file's name without the ending, or the page's title); a shared file's name comes a moment later. */
        var title: String,
        /** A video (or only sound): guessed from its name or type until its tracks are known. */
        var video: Boolean,
        /** A file on the phone: its place is remembered, and subtitles next to it are found. */
        val file: File? = null,
        /** A website video, played from the network with the page's [headers]. */
        val stream: Boolean = false,
        val hls: Boolean = false,
        /** 3.0.2: a DASH stream (.mpd). */
        val dash: Boolean = false,
        val headers: Map<String, String> = emptyMap(),
        /** From an incognito tab: nothing about it is remembered, logged or shown on the lock screen. */
        val incognito: Boolean = false,
        /**
         * 3.0: a file of Private (Files): like [incognito], nothing about it is remembered or shown on
         * the lock screen, and its screen is always kept out of screenshots.
         */
        val secure: Boolean = false,
    ) {
        /** 3.0: nothing about it is kept or shown outside the player (incognito, or Private's). */
        val private: Boolean get() = incognito || secure

        /** 3.0.4: a file of a network folder, read from the share (files.net.NetDataSource). */
        val network: Boolean get() = com.swiftbrowser.files.net.NetUri.isNet(uri)

        /** What a log line may say about it: the kind of file, never its name, path or address. */
        val logName: String
            get() = when {
                stream -> if (hls) "a website stream" else "a website video"
                file != null -> "a ${file.extension.lowercase(Locale.ROOT).take(5)} file"
                network -> "a network folder's file"
                else -> "a shared file"
            }
    }

    /** The player screen hears about what the player itself doesn't tell. */
    interface Watcher {
        /** The title, artist, cover, list or sleep timer changed. */
        fun onInfoChanged() {}

        /** The player was closed (the notification's Close): the screen closes too. */
        fun onReleased() {}

        /** 3.0.3: a video of the playlist paused at its end, for the screen's "Up next" (see [upNextWanted]). */
        fun onUpNext() {}
    }

    /** Reading covers, folders and subtitle files. */
    val io = Background.single("player")

    private val main = Handler(Looper.getMainLooper())
    private val watchers = ArrayList<Watcher>()
    private var app: Context? = null

    var player: ExoPlayer? = null
        private set

    /** The list, in the player's order (shuffling only changes the order they play in). */
    var entries: List<Entry> = emptyList()
        private set

    val index: Int get() = player?.currentMediaItemIndex ?: 0
    val current: Entry? get() = entries.getOrNull(index)

    /** The entry playing before the last change of item (to forget it once it played to its end). */
    private var playingIndex = 0

    /** Each list loaded gets a number: folder contents read for an older one are dropped. */
    private var loadToken = 0
    private var sources: MediaSource.Factory? = null

    /** A video's sound goes on with the screen off or Swift left (its menu's "Play in background"). */
    var background: Boolean
        get() = inBackground
        set(v) {
            inBackground = v
            app?.let { MusicService.sync(it) }
        }
    private var inBackground = false

    /** The player screen can be seen (between its onStart and onStop). */
    var screenVisible = false
        set(v) {
            field = v
            syncPauseAtEnd()
        }

    /** The paused notification was swiped away while the screen was open: it comes back with playing. */
    private var dismissed = false

    // What plays, from its tags (or its name), and its cover: big for the screen, small for Android.
    var tagTitle: String? = null
        private set
    var tagArtist: String? = null
        private set
    var art: Bitmap? = null
        private set
    var smallArt: Bitmap? = null
        private set
    private var artFrom: Any? = null
    private var artToken = 0

    /**
     * Playing, or about to (buffering, or held up by a seek-bar drag): what buttons and the
     * notification show. 3.0: not during a phone call (Android has taken the sound away for a while:
     * the player waits with "play" still on, and goes on by itself after the call).
     */
    val playing: Boolean
        get() {
            val p = player ?: return false
            return p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE &&
                p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        }

    fun watch(w: Watcher) {
        if (w !in watchers) watchers += w
    }

    fun unwatch(w: Watcher) {
        watchers -= w
    }

    private fun changed() {
        for (w in watchers.toList()) w.onInfoChanged()
    }

    /** An entry's title changed (a shared file's name arrived): the screen and notification follow. */
    fun refreshInfo() {
        changed()
        app?.let { MusicService.sync(it) }
    }

    // ------------------------------------------------------------------ the player

    private fun obtain(ctx: Context): ExoPlayer = player ?: build(ctx).also { player = it }

    /** 3.0.4: night sound in the player's sound output (one for each player made); its switch is [setNightSound]. */
    private var night: NightSound? = null

    private fun build(ctx: Context): ExoPlayer {
        // The phone's hardware decoders first (smooth and light on the battery); another decoder
        // when one can't start, rather than an error. FFmpeg (2.9.2) only for sound none of the phone's
        // decoders can play (Dolby and DTS in films): "on" puts it after them, "prefer" would before.
        // 3.0.3: with the subtitles' and the sound's timing (SyncedRenderers). 3.0.4: and night sound.
        val nightSound = NightSound().apply { enabled = PlayerSettings.nightSound }
        night = nightSound
        val renderers = SyncedRenderers(ctx, nightSound)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // Smaller buffers than ExoPlayer's (up to 128 MB for video): the phone has 2 GB of memory
        // shared with the engine. 32 MB is still a minute of a typical film ahead.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(load)
            // Pauses for calls and other apps' sound (audio focus), and when headphones are unplugged.
            .setAudioAttributes(attributes(video = true), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(listener)
        // 3.0.3: the equalizer, when it is on, on the player's sound.
        SoundEffects.attach(p.audioSessionId)
        return p
    }

    private fun attributes(video: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /**
     * Files, shared files and website videos (with the page's headers: cookies, referrer). 3.0:
     * website videos through the engine ([GeckoDataSource]: Android 7's own HTTPS fails for many
     * sites), an incognito tab's in its private session ([private]).
     */
    private fun sourcesFor(ctx: Context, headers: Map<String, String>, private: Boolean): MediaSource.Factory {
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: Net.USER_AGENT
        val http = GeckoDataSource.Factory(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) } + ("User-Agent" to agent), private)
        // MP3, AAC and AMR files without a table of contents can still be skipped through.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        // 3.0.4: network folders' files from their share, the rest as before.
        return DefaultMediaSourceFactory(com.swiftbrowser.files.net.NetDataSource.Factory(ctx, DefaultDataSource.Factory(ctx, http)), extractors)
    }

    // No title of Swift's own in the item: it would win over the song's tags.
    private fun mediaItem(e: Entry): MediaItem = MediaItem.Builder()
        .setUri(e.uri)
        .apply {
            if (e.hls) setMimeType(MimeTypes.APPLICATION_M3U8)
            if (e.dash) setMimeType(MimeTypes.APPLICATION_MPD)
        }
        .build()

    /**
     * Plays [list] from item [start], at [positionMs] (C.TIME_UNSET: its start); what played before
     * stops. Returns the list's number, for [addAround].
     */
    fun load(ctx: Context, list: List<Entry>, start: Int, positionMs: Long): Int {
        val a = ctx.applicationContext
        app = a
        // Where the one playing before was left.
        saveNow()
        val p = obtain(a)
        cancelSleep(notify = false)
        clearLoop(notify = false)
        entries = list
        playingIndex = start
        clearInfo()
        dismissed = false
        inBackground = false
        val first = list[start]
        // 3.0.3: its timing and tracks as chosen last time.
        loadTimings(first)
        // 3.0: the volume boost belongs to what it was set for, not to the next file opened.
        if (boost > 0f) setBoost(0f)
        val factory = sourcesFor(a, first.headers, first.incognito).also { sources = it }
        p.setMediaSources(list.map { factory.createMediaSource(mediaItem(it)) }, start, positionMs)
        // 3.0.4: a network folder's file needs the Wi-Fi awake too.
        p.setWakeMode(if (first.stream || first.network) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
        p.setAudioAttributes(attributes(first.video), true)
        p.setPlaybackSpeed(PlayerSettings.defaultSpeed)
        // Repeating and shuffling carry on from song to song, not into a video.
        if (first.video) {
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.shuffleModeEnabled = false
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            // 3.0.1: the video's own subtitles in the chosen language show by themselves (null: as before).
            .setPreferredTextLanguage(PlayerSettings.subtitleLanguage.ifEmpty { null })
            .build()
        p.prepare()
        p.playWhenReady = true
        syncPauseAtEnd()
        pauseWebMedia()
        L.d("Player: playing ${first.logName}" + if (positionMs > 0) ", going on where it was left" else "")
        changed()
        MusicService.sync(a)
        return ++loadToken
    }

    /**
     * The rest of the folder, read after playing started: [before] goes ahead of what plays and
     * [after] behind it, without interrupting it. Dropped if another list was loaded meanwhile.
     */
    fun addAround(token: Int, before: List<Entry>, after: List<Entry>) {
        val p = player ?: return
        val f = sources ?: return
        if (token != loadToken || (before.isEmpty() && after.isEmpty())) return
        if (after.isNotEmpty()) p.addMediaSources(after.map { f.createMediaSource(mediaItem(it)) })
        if (before.isNotEmpty()) p.addMediaSources(0, before.map { f.createMediaSource(mediaItem(it)) })
        entries = before + entries + after
        playingIndex += before.size
        changed()
        app?.let { MusicService.sync(it) }
    }

    // ------------------------------------------------------------------ what the buttons do

    fun play() {
        val p = player ?: return
        when (p.playbackState) {
            // After an error: try again (3.0.4: a part repeated is watched for again, as the error dropped it).
            Player.STATE_IDLE -> {
                p.prepare()
                watchLoopEnd()
            }
            // At the end: from the start.
            Player.STATE_ENDED -> p.seekTo(p.currentMediaItemIndex, 0)
        }
        p.play()
        pauseWebMedia()
    }

    fun pause() {
        player?.pause()
    }

    fun toggle() = if (playing) pause() else play()

    fun seekTo(ms: Long) {
        val p = player ?: return
        val d = p.duration
        p.seekTo(if (d != C.TIME_UNSET && d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        seekTo(p.currentPosition + ms)
    }

    /** The next one in the list (in shuffled order when shuffling). */
    fun next() {
        val p = player ?: return
        if (!p.hasNextMediaItem()) return
        val i = p.nextMediaItemIndex
        // 3.0.3: one video or song repeating all: next is it again, from its start.
        if (i == p.currentMediaItemIndex) {
            seekTo(0)
            if (!p.playWhenReady) play()
        } else {
            jumpTo(i)
        }
    }

    /** Back to the start, or the one before when already at its start, as music players do. */
    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3_000 || !p.hasPreviousMediaItem()) seekTo(0) else jumpTo(p.previousMediaItemIndex)
    }

    /** Plays item [i] of the list, where it was left off. */
    fun jumpTo(i: Int) {
        val p = player ?: return
        val e = entries.getOrNull(i) ?: return
        saveNow()
        p.seekTo(i, resumeAt(e) ?: 0L)
        if (!p.playWhenReady || p.playbackState == Player.STATE_IDLE) play()
    }

    private fun resumeAt(e: Entry): Long? {
        val a = app ?: return null
        val f = e.file ?: return null
        return if (e.private) null else LocalMedia.position(a, f)
    }

    /**
     * Turns the picture's decoding off while a video only plays its sound in the background (it
     * saves the battery), and on again when it is seen.
     */
    fun setPictureOff(off: Boolean) {
        val p = player ?: return
        val params = p.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == off) return
        p.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, off).build()
    }

    /**
     * 2.9.2: sound above the phone's loudest, from 0 (none) to 1 (about twice as loud), for quiet
     * films on a quiet speaker. Android's loudness enhancer on the player's sound; it ends with the player.
     * 3.0: it starts at none with each file opened (the next one in the list keeps it, and the player
     * shows it while it is on), and with headphones it stops at [HEADPHONE_BOOST]: +10 dB in the
     * ears is a risk for hearing.
     */
    var boost = 0f
        private set
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var enhancerSession = 0

    /** 3.0: +4 dB (about 140%) at most with headphones. */
    const val HEADPHONE_BOOST = 0.4f

    /** 3.0: how far the boost may go now (less with headphones in). */
    fun maxBoost(): Float = if (headphones()) HEADPHONE_BOOST else 1f

    /** 3.0: the sound goes to headphones (wired, Bluetooth or USB). */
    fun headphones(): Boolean {
        val a = app ?: return false
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }
        } catch (_: Exception) {
            false
        }
    }

    private val headphoneTypes: Set<Int> by lazy {
        val t = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) t += AudioDeviceInfo.TYPE_USB_HEADSET
        t
    }

    /** 3.0: headphones plugged in while boosted: the boost comes down to what is safe for them. */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (boost > HEADPHONE_BOOST && addedDevices?.any { it.type in headphoneTypes } == true) {
                L.d("Player: headphones in, the volume boost comes down")
                setBoost(HEADPHONE_BOOST)
                changed()
            }
        }
    }
    private var watchingDevices = false

    fun setBoost(amount: Float) {
        val was = boost
        boost = amount.coerceIn(0f, maxBoost())
        watchDevices(boost > 0f)
        if ((was > 0f) != (boost > 0f)) changed()
        val p = player ?: return
        try {
            val session = p.audioSessionId
            if (enhancer != null && enhancerSession != session) {
                enhancer?.release()
                enhancer = null
            }
            if (boost <= 0f) {
                enhancer?.enabled = false
                return
            }
            val e = enhancer ?: android.media.audiofx.LoudnessEnhancer(session).also {
                enhancer = it
                enhancerSession = session
            }
            e.setTargetGain((boost * MAX_BOOST_MB).toInt())
            e.enabled = true
        } catch (t: Throwable) {
            L.w("Player: no volume boost on this phone", t)
        }
    }

    private fun watchDevices(on: Boolean) {
        if (on == watchingDevices) return
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (on) am.registerAudioDeviceCallback(devices, main) else am.unregisterAudioDeviceCallback(devices)
            watchingDevices = on
        } catch (e: Exception) {
            L.w("Player: can't watch for headphones", e)
        }
    }

    /** 3.0.4: night sound (NightSound) on everything the player plays, kept with the player's settings. */
    val nightSoundOn: Boolean get() = PlayerSettings.nightSound

    /** 3.0.4: night sound on or off, at once: it is always in the sound's way, so nothing starts again. */
    fun setNightSound(on: Boolean) {
        PlayerSettings.nightSound = on
        night?.enabled = on
        L.d("Player: night sound " + if (on) "on" else "off")
        changed()
    }

    /** Stops for good: the place is remembered, the notification goes, and the screen closes. */
    fun release() {
        val p = player ?: return
        runCatching { enhancer?.release() }
        enhancer = null
        boost = 0f
        watchDevices(false)
        SoundEffects.release()
        saveNow()
        loadTimings(null)
        cancelSleep(notify = false)
        clearLoop(notify = false)
        main.removeCallbacks(saver)
        p.removeListener(listener)
        p.release()
        player = null
        night = null
        entries = emptyList()
        sources = null
        clearInfo()
        inBackground = false
        app?.let { MusicService.sync(it) }
        L.d("Player: closed")
        for (w in watchers.toList()) w.onReleased()
    }

    /** The paused notification was swiped away: without the screen, that closes the player. */
    fun dismiss() {
        if (playing) return
        if (screenVisible) {
            dismissed = true
            app?.let { MusicService.sync(it) }
        } else {
            release()
        }
    }

    /** Whether the notification (and lock screen controls) should be there. */
    fun wantsService(): Boolean {
        val p = player ?: return false
        val e = current ?: return false
        // Not after an error (only before playing starts: a new song replacing one keeps its notification).
        if (dismissed || p.playerError != null || p.mediaItemCount == 0) return false
        return !e.video || background
    }

    /** A page playing in a tab stops when the player starts: two sounds at once help nobody. */
    private fun pauseWebMedia() {
        val tab = Playback.tab ?: return
        if (tab.playing) tab.mediaSession?.pause()
    }

    // ------------------------------------------------------------------ titles and covers

    /** What plays, for the notification: a song's own title, a video's file name. */
    fun displayTitle(): String {
        val e = current ?: return "Swift"
        return if (e.video) e.title else tagTitle?.takeIf { it.isNotBlank() } ?: e.title
    }

    /** The line under it: the artist, the folder, or the site. */
    fun displaySubtitle(): String {
        val e = current ?: return ""
        if (!e.video) tagArtist?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            e.file != null -> e.file.parentFile?.name.orEmpty()
            e.stream -> com.swiftbrowser.core.UrlUtils.host(e.uri.toString())?.removePrefix("www.").orEmpty()
            else -> ""
        }
    }

    private fun clearInfo() {
        tagTitle = null
        tagArtist = null
        art = null
        smallArt = null
        artFrom = null
        artToken++
    }

    private fun onMetadata(md: MediaMetadata) {
        val e = current ?: return
        tagTitle = md.title?.toString()?.trim()
        tagArtist = (md.artist ?: md.albumArtist)?.toString()?.trim()
        val data = md.artworkData
        val key: Any = data ?: e
        if (key === artFrom) {
            changed()
            return
        }
        artFrom = key
        val token = ++artToken
        // A song without a picture of its own: the folder's (cover.jpg, folder.jpg).
        val folder = if (data == null && !e.video) e.file?.parentFile else null
        if (data == null && folder == null) {
            art = null
            smallArt = null
            changed()
            app?.let { MusicService.sync(it) }
            return
        }
        io.execute {
            val big = try {
                if (data != null) decode(data) else folderCover(folder!!)
            } catch (_: OutOfMemoryError) {
                null
            } catch (e2: Exception) {
                L.w("Couldn't read a cover", e2)
                null
            }
            // Android's media session carries its picture between apps: kept small.
            val small = big?.let { b ->
                val side = maxOf(b.width, b.height)
                if (side <= 320) b else Bitmap.createScaledBitmap(b, b.width * 320 / side, b.height * 320 / side, true)
            }
            main.post {
                if (token != artToken) return@post
                art = big
                smallArt = small
                changed()
                app?.let { MusicService.sync(it) }
            }
        }
        changed()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds) })
    }

    private fun folderCover(dir: File): Bitmap? {
        val f = COVERS.map { File(dir, it) }.firstOrNull { it.isFile } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds) })
    }

    private val COVERS = listOf("cover.jpg", "Cover.jpg", "folder.jpg", "Folder.jpg", "AlbumArt.jpg", "front.jpg", "cover.png", "folder.png")

    /** About 640 pixels across: as big as the screen shows it. */
    private fun sampleFor(bounds: BitmapFactory.Options): Int {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640 || bounds.outHeight / (sample * 2) >= 640) sample *= 2
        return sample
    }

    // ------------------------------------------------------------------ where it was left off

    /** Remembers where the file playing is (nothing for website videos, shared files, incognito and Private's). */
    fun saveNow() {
        val p = player ?: return
        val a = app ?: return
        val e = current ?: return
        val f = e.file ?: return
        if (e.private) return
        val d = p.duration
        if (d == C.TIME_UNSET || d <= 0 || p.playbackState == Player.STATE_IDLE) return
        LocalMedia.save(a, f, displayTitle(), e.video, p.currentPosition, d)
    }

    /** Every half a minute while playing, in case Swift is closed without a goodbye. */
    private val saver = object : Runnable {
        override fun run() {
            if (!playing) return
            saveNow()
            main.postDelayed(this, 30_000)
        }
    }

    // ------------------------------------------------------------------ up next (3.0.3)

    /**
     * A video of a playlist pauses at its end while the screen is seen (repeat off, one after it),
     * so the screen can say what comes next before it starts. Music, and anything playing without
     * the screen, goes straight on.
     */
    var upNextWanted = false
        private set

    private fun syncPauseAtEnd() {
        val p = player ?: return
        val e = current
        upNextWanted = screenVisible && e != null && e.video && p.repeatMode == Player.REPEAT_MODE_OFF && p.hasNextMediaItem()
        val pause = sleepAtEnd || upNextWanted
        if (p.pauseAtEndOfMediaItems != pause) p.pauseAtEndOfMediaItems = pause
    }

    // ------------------------------------------------------------------ repeating a part (3.0.4)

    /**
     * A-B repeat (PartRepeat), as VLC has it: where the part of what plays ([loopFor]) starts and
     * ends, C.TIME_UNSET when not set. Not kept: another video or song, or another list, ends it.
     */
    var loopStartMs = C.TIME_UNSET
        private set
    var loopEndMs = C.TIME_UNSET
        private set
    private var loopFor: Entry? = null

    /**
     * The player's message at the end of the part: sent on the playback thread as playback goes
     * past it, again each time (it isn't deleted once sent), so its seek back needs nothing more.
     */
    private var loopMessage: PlayerMessage? = null

    /** The part to repeat starts at [ms] (a part set before goes); its end comes next. */
    fun setLoopStart(ms: Long) {
        clearLoop(notify = false)
        val e = current ?: return
        loopFor = e
        loopStartMs = ms.coerceAtLeast(0)
        L.d("Player: the start of a part to repeat")
        changed()
    }

    /** The part ends at [ms]: false (and nothing changes) unless that is a second after its start, at least. */
    fun setLoopEnd(ms: Long): Boolean {
        val p = player ?: return false
        if (loopStartMs == C.TIME_UNSET || current !== loopFor || !PartRepeat.endFits(loopStartMs, ms)) return false
        loopEndMs = ms
        watchLoopEnd()
        L.d("Player: repeating a part")
        changed()
        // Set where playback is (the chip tapped at the end): round it goes, at once.
        if (PartRepeat.backTo(loopStartMs, ms, p.currentPosition) != null) loopBack.run()
        return true
    }

    /** The player's message at the end of the part (made again after an error, which the player forgets it with). */
    private fun watchLoopEnd() {
        loopMessage?.cancel()
        loopMessage = null
        val p = player ?: return
        if (loopEndMs == C.TIME_UNSET || current !== loopFor) return
        // Delivered on the playback thread; the seek is the main thread's.
        loopMessage = p.createMessage { _, _ -> main.post(loopBack) }
            .setPosition(p.currentMediaItemIndex, PartRepeat.watchAt(loopEndMs, p.duration))
            .setDeleteAfterDelivery(false)
            .send()
    }

    /** No part repeats any more. */
    fun clearLoop(notify: Boolean = true) {
        loopMessage?.cancel()
        loopMessage = null
        main.removeCallbacks(loopBack)
        val was = loopStartMs != C.TIME_UNSET
        loopStartMs = C.TIME_UNSET
        loopEndMs = C.TIME_UNSET
        loopFor = null
        if (was && notify) changed()
    }

    /** Playback got to the end of the part (or past it, by a seek): back to its start. */
    private val loopBack = Runnable {
        val p = player ?: return@Runnable
        if (loopEndMs == C.TIME_UNSET || current !== loopFor) return@Runnable
        p.seekTo(loopStartMs)
    }

    /** A seek past the end of the part goes back to its start, as in VLC (not while the seek bar is dragged). */
    private fun loopAfterSeek(newPosition: Player.PositionInfo) {
        val p = player ?: return
        if (loopEndMs == C.TIME_UNSET || current !== loopFor || p.isScrubbingModeEnabled) return
        if (newPosition.mediaItemIndex != p.currentMediaItemIndex) return
        if (PartRepeat.backTo(loopStartMs, PartRepeat.watchAt(loopEndMs, p.duration), newPosition.positionMs) != null) main.post(loopBack)
    }

    // ------------------------------------------------------------------ timing and tracks (3.0.3)

    /**
     * How much later (> 0) or earlier the subtitles show and the sound plays, in µs: read on the
     * playback thread by the renderers (SyncedRenderers). One subtitle timing for the video's own
     * subtitles and subtitle files (the screen shows those itself, with [subtitleDelayMs]).
     */
    @Volatile
    private var subtitleDelayUs = 0L

    @Volatile
    private var soundDelayUs = 0L

    val subtitleDelayMs: Long get() = subtitleDelayUs / 1000
    val soundDelayMs: Long get() = soundDelayUs / 1000

    /** What the timing (and [trackChoice]) is for: kept with that file. */
    private var timingFor: Entry? = null

    /** The subtitles' timing when the changes being made began (see [timingSettled]). */
    private var rereadFrom = 0L
    private var timingChanging = false

    /** The sound and subtitles chosen last time for what plays (a file on the phone, not incognito's or Private's), or null. */
    var trackChoice: TrackChoice? = null
        private set

    /** The entry whose sound track was chosen again already. */
    private var soundRestoredFor: Entry? = null

    fun setSubtitleDelay(ms: Long) {
        val v = Delays.clamp(ms)
        if (v == subtitleDelayMs) return
        beginTimingChange()
        subtitleDelayUs = v * 1000
        timingChanged()
    }

    fun setSoundDelay(ms: Long) {
        val v = Delays.clamp(ms)
        if (v == soundDelayMs) return
        beginTimingChange()
        soundDelayUs = v * 1000
        timingChanged()
    }

    /** A change is about to be made: the first of a round notes where the subtitles' timing began. */
    private fun beginTimingChange() {
        if (!timingChanging) rereadFrom = subtitleDelayMs
    }

    private fun timingChanged() {
        timingChanging = true
        main.removeCallbacks(timingSettled)
        main.postDelayed(timingSettled, 600)
        changed()
    }

    /**
     * 0.6 s after the last change: kept with the file, and, when earlier subtitles were made later
     * again, the video's own subtitles read again (those passed were dropped) by a quick seek to
     * where the picture is.
     */
    private val timingSettled = Runnable {
        timingChanging = false
        saveTimings()
        val p = player ?: return@Runnable
        if (Delays.rereadSubtitles(rereadFrom, subtitleDelayMs) && p.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)) {
            // The position said runs ahead by the sound's timing: back by that, so the picture stays.
            p.seekTo((p.currentPosition - soundDelayMs).coerceAtLeast(0))
        }
    }

    private fun saveTimings() {
        val a = app ?: return
        val e = timingFor ?: return
        val f = e.file ?: return
        if (e.private) return
        LocalMedia.saveDelays(a, f, subtitleDelayMs, soundDelayMs)
    }

    /** [e] plays now: the timing and tracks chosen for it last time (a change not kept yet goes to the one before). */
    private fun loadTimings(e: Entry?) {
        if (timingChanging) {
            main.removeCallbacks(timingSettled)
            timingChanging = false
            saveTimings()
        }
        timingFor = e
        soundRestoredFor = null
        val a = app
        val f = e?.file
        if (a == null || f == null || e.private) {
            subtitleDelayUs = 0
            soundDelayUs = 0
            trackChoice = null
            return
        }
        subtitleDelayUs = Delays.clamp(LocalMedia.subtitleDelay(a, f)) * 1000
        soundDelayUs = Delays.clamp(LocalMedia.soundDelay(a, f)) * 1000
        trackChoice = LocalMedia.trackChoice(a, f)
    }

    /** Keeps a choice of sound or subtitles for the file playing (not incognito's or Private's, nor a website's). */
    fun rememberTracks(change: (TrackChoice) -> TrackChoice) {
        val a = app ?: return
        val e = current ?: return
        val f = e.file ?: return
        if (e.private) return
        val next = change(trackChoice ?: TrackChoice())
        trackChoice = next
        soundRestoredFor = e
        LocalMedia.saveTrackChoice(a, f, next)
    }

    /** The sound track chosen last time for [e], chosen again once its tracks are known. */
    private fun restoreSound(e: Entry, tracks: Tracks) {
        if (soundRestoredFor === e) return
        val want = trackChoice?.takeIf { it.hasSound }
        if (want == null) {
            soundRestoredFor = e
            return
        }
        val options = ArrayList<Pair<Tracks.Group, Int>>()
        for (g in tracks.groups) if (g.type == C.TRACK_TYPE_AUDIO) for (t in 0 until g.length) options += g to t
        soundRestoredFor = e
        val i = TrackChoice.match(want.soundLanguage, want.soundLabel, options.map { (g, t) -> g.getTrackFormat(t).let { it.language to it.label } })
        if (i < 0) return
        val (g, t) = options[i]
        if (g.isTrackSelected(t)) return
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
            .build()
        L.d("Player: the sound track chosen last time is back")
    }

    // ------------------------------------------------------------------ sleep timer

    const val SLEEP_END = -1
    val SLEEP_CHOICES = intArrayOf(15, 30, 45, 60)

    /** When the sleep timer runs out (uptime), or 0. */
    var sleepEndsAt = 0L
        private set

    /** Stops at the end of the video or song playing. */
    var sleepAtEnd = false
        private set

    private var fadeStep = 0

    private val sleepFire = Runnable {
        sleepEndsAt = 0
        L.d("Player: sleep timer ran out, fading out")
        main.post(fade)
    }

    /** The sound fades out over six seconds instead of stopping at once. */
    private val fade = object : Runnable {
        override fun run() {
            val p = player ?: return
            fadeStep++
            if (fadeStep >= 30) {
                p.pause()
                p.volume = 1f
                fadeStep = 0
                changed()
                app?.let { MusicService.sync(it) }
                return
            }
            p.volume = 1f - fadeStep / 30f
            main.postDelayed(this, 200)
        }
    }

    /** Once a minute while the timer runs: the minutes left in the notification and the menu. */
    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepEndsAt <= 0) return
            changed()
            app?.let { MusicService.sync(it) }
            main.postDelayed(this, 60_000)
        }
    }

    /** Stops after [minutes], or at the end of what plays ([SLEEP_END]). */
    fun setSleep(minutes: Int) {
        cancelSleep(notify = false)
        val p = player ?: return
        if (minutes == SLEEP_END) {
            sleepAtEnd = true
            syncPauseAtEnd()
        } else {
            sleepEndsAt = SystemClock.uptimeMillis() + minutes * 60_000L
            main.postDelayed(sleepFire, minutes * 60_000L)
            main.postDelayed(sleepTick, 60_000)
        }
        changed()
        app?.let { MusicService.sync(it) }
    }

    fun cancelSleep(notify: Boolean = true) {
        main.removeCallbacks(sleepFire)
        main.removeCallbacks(sleepTick)
        if (fadeStep > 0) {
            main.removeCallbacks(fade)
            fadeStep = 0
            player?.volume = 1f
        }
        sleepEndsAt = 0
        sleepAtEnd = false
        syncPauseAtEnd()
        if (notify) {
            changed()
            app?.let { MusicService.sync(it) }
        }
    }

    /** "23 min left", "at the end", or null when off. */
    fun sleepLabel(): String? = when {
        sleepAtEnd -> "at the end"
        sleepEndsAt > 0 -> "${((sleepEndsAt - SystemClock.uptimeMillis()) / 60_000L + 1).coerceAtLeast(1)} min left"
        else -> null
    }

    // ------------------------------------------------------------------ what the player says

    /** Played to its end: nothing to go back to, and (3.0.3) a video gets its tick in Files (not incognito's or Private's). */
    private fun finished(e: Entry?) {
        val a = app ?: return
        val f = e?.file ?: return
        LocalMedia.forget(a, f.path)
        if (e.video && !e.private) Watched.markFinished(a, f)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val before = entries.getOrNull(playingIndex)
            // Played to its end (and on to the next, or round again): nothing to go back to.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) finished(before)
            playingIndex = p.currentMediaItemIndex
            clearInfo()
            // 3.0.3: another file, its own timing and tracks (the same one round again keeps them).
            if (current !== timingFor) loadTimings(current)
            // 3.0.4: and no part of the one before repeating.
            if (current !== loopFor) clearLoop(notify = false)
            // The next episode of a series goes on where it was left off too.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) current?.let { e -> resumeAt(e)?.let { p.seekTo(it) } }
            syncPauseAtEnd()
            changed()
            app?.let { MusicService.sync(it) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = onMetadata(mediaMetadata)

        override fun onTracksChanged(tracks: Tracks) {
            val e = current ?: return
            if (tracks.isEmpty) return
            // An .mp4 that is only sound shows as music (and plays on in the background).
            val video = tracks.containsType(C.TRACK_TYPE_VIDEO)
            if (video != e.video) {
                e.video = video
                syncPauseAtEnd()
                changed()
                app?.let { MusicService.sync(it) }
            }
            restoreSound(e, tracks)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                dismissed = false
                main.removeCallbacks(saver)
                main.postDelayed(saver, 30_000)
            } else {
                saveNow()
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    // 3.0.3: paused at its end (the sleep timer's "at the end", or Up next): played to
                    // its end, as if it had gone on (going on is a seek then, which isn't counted).
                    finished(current)
                    if (sleepAtEnd) {
                        // "At the end" of the sleep timer: it has paused there, so it's done.
                        L.d("Player: sleep timer stopped at the end")
                        cancelSleep()
                    } else if (upNextWanted) {
                        for (w in watchers.toList()) w.onUpNext()
                    }
                }
            }
            app?.let { MusicService.sync(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finished(current)
            app?.let { MusicService.sync(it) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = syncPauseAtEnd()

        override fun onRepeatModeChanged(repeatMode: Int) = syncPauseAtEnd()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = syncPauseAtEnd()

        // 3.0.3: the effects go with the sound to its new session.
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            SoundEffects.attach(audioSessionId)
            if (boost > 0f) setBoost(boost)
        }

        // 3.0: a call took the sound away (or gave it back): the buttons and the notification follow.
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            changed()
            app?.let { MusicService.sync(it) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            MusicService.positionChanged()
            // 3.0.4: a seek outside the part repeated keeps it.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) loopAfterSeek(newPosition)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            MusicService.positionChanged()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            L.w("Player: ${current?.logName ?: "media"} couldn't play (${error.errorCodeName})")
            app?.let { MusicService.sync(it) }
        }
    }

    // ------------------------------------------------------------------ the timing in the renderers (3.0.3)

    /**
     * ExoPlayer's usual renderers, with the subtitles and the sound moved by their timing: the
     * subtitle renderer is told a position moved by the subtitles' ([DelayedText]), and the sound's
     * clock, which the picture follows, is moved by the sound's ([DelayedSound]). FFmpeg's sound
     * renderer gets the same sound output as the phone's own, so the same clock. 3.0.4: and the
     * same [night] sound.
     */
    @OptIn(UnstableApi::class)
    private class SyncedRenderers(ctx: Context, private val night: NightSound) : DefaultRenderersFactory(ctx) {
        override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
            val made = ArrayList<Renderer>()
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, made)
            for (r in made) out += if (r is TextRenderer) DelayedText(r) else r
        }

        // The sink ExoPlayer's own factory makes (DefaultRenderersFactory.buildAudioSink, Media3
        // 1.11), with night sound in its chain: the sink puts the speed's (Sonic) after it.
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            DelayedSound(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(night))
                    .build(),
            )
    }

    /**
     * The video's own subtitles, rendered and reset at the position less the subtitles' timing (so
     * later subtitles show later). It knows where its stream ends (from the timeline), so near the end
     * it gets the true position (see Delays.subtitlePositionUs), and it tells the renderer where its
     * last stream ends: ExoPlayer only does that for a TextRenderer it sees, not through a wrapper.
     */
    @OptIn(UnstableApi::class)
    private class DelayedText(private val text: TextRenderer) : ForwardingRenderer(text) {
        private var timeline: Timeline = Timeline.EMPTY
        private var periodId: MediaSource.MediaPeriodId? = null
        private var offsetUs = 0L
        private val period = Timeline.Period()

        /** Where the stream being read ends, in the renderers' time; C.TIME_UNSET when not known. */
        private fun streamEndUs(): Long {
            val id = periodId ?: return C.TIME_UNSET
            val i = timeline.getIndexOfPeriod(id.periodUid)
            if (i == C.INDEX_UNSET) return C.TIME_UNSET
            val d = timeline.getPeriod(i, period).durationUs
            return if (d == C.TIME_UNSET) C.TIME_UNSET else offsetUs + d
        }

        private fun at(positionUs: Long) = Delays.subtitlePositionUs(positionUs, subtitleDelayUs, streamEndUs())

        override fun enable(
            configuration: RendererConfiguration,
            formats: Array<out Format>,
            stream: SampleStream,
            positionUs: Long,
            joining: Boolean,
            mayRenderStartOfStream: Boolean,
            startPositionUs: Long,
            offsetUs: Long,
            mediaPeriodId: MediaSource.MediaPeriodId,
        ) {
            this.offsetUs = offsetUs
            periodId = mediaPeriodId
            super.enable(configuration, formats, stream, at(positionUs), joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
        }

        override fun replaceStream(formats: Array<out Format>, stream: SampleStream, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
            this.offsetUs = offsetUs
            periodId = mediaPeriodId
            super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
        }

        override fun setTimeline(timeline: Timeline) {
            this.timeline = timeline
            super.setTimeline(timeline)
        }

        override fun setCurrentStreamFinal() {
            super.setCurrentStreamFinal()
            val end = streamEndUs()
            if (end != C.TIME_UNSET) runCatching { text.setFinalStreamEndPositionUs(end) }
        }

        override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) =
            super.resetPosition(at(positionUs), sampleStreamIsResetToKeyFrame)

        override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean =
            super.supportsResetPositionWithoutKeyFrameReset(at(positionUs))

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = super.render(at(positionUs), elapsedRealtimeUs)

        override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
            super.getDurationToProgressUs(at(positionUs), elapsedRealtimeUs)
    }

    /** The sound's output, its clock moved by the sound's timing (see Delays.soundClockUs for which way). */
    @OptIn(UnstableApi::class)
    private class DelayedSound(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = Delays.soundClockUs(super.getCurrentPositionUs(sourceEnded), soundDelayUs)
    }
}
