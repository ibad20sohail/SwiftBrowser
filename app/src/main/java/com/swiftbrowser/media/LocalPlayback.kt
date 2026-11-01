package com.swiftbrowser.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
        val headers: Map<String, String> = emptyMap(),
        /** From an incognito tab: nothing about it is remembered, logged or shown on the lock screen. */
        val incognito: Boolean = false,
    ) {
        /** What a log line may say about it: the kind of file, never its name, path or address. */
        val logName: String
            get() = when {
                stream -> if (hls) "a website stream" else "a website video"
                file != null -> "a ${file.extension.lowercase(Locale.ROOT).take(5)} file"
                else -> "a shared file"
            }
    }

    /** The player screen hears about what the player itself doesn't tell. */
    interface Watcher {
        /** The title, artist, cover, list or sleep timer changed. */
        fun onInfoChanged() {}

        /** The player was closed (the notification's Close): the screen closes too. */
        fun onReleased() {}
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

    /** Playing, or about to (buffering, or held up by a seek-bar drag): what buttons and the notification show. */
    val playing: Boolean
        get() {
            val p = player ?: return false
            return p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
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

    private fun build(ctx: Context): ExoPlayer {
        // The phone's hardware decoders first (smooth and light on the battery); another decoder
        // when one can't start, rather than an error. FFmpeg (2.9.2) only for sound none of the phone's
        // decoders can play (Dolby and DTS in films): "on" puts it after them, "prefer" would before.
        val renderers = DefaultRenderersFactory(ctx)
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
        return p
    }

    private fun attributes(video: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** Files, shared files and website videos (with the page's headers: cookies, referrer). */
    private fun sourcesFor(ctx: Context, headers: Map<String, String>): MediaSource.Factory {
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: Net.USER_AGENT
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(agent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        // MP3, AAC and AMR files without a table of contents can still be skipped through.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors)
    }

    // No title of Swift's own in the item: it would win over the song's tags.
    private fun mediaItem(e: Entry): MediaItem = MediaItem.Builder()
        .setUri(e.uri)
        .apply { if (e.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
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
        entries = list
        playingIndex = start
        clearInfo()
        dismissed = false
        inBackground = false
        val first = list[start]
        val factory = sourcesFor(a, first.headers).also { sources = it }
        p.setMediaSources(list.map { factory.createMediaSource(mediaItem(it)) }, start, positionMs)
        p.setWakeMode(if (first.stream) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
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
            .build()
        p.prepare()
        p.playWhenReady = true
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
            // After an error: try again.
            Player.STATE_IDLE -> p.prepare()
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
        if (p.hasNextMediaItem()) jumpTo(p.nextMediaItemIndex)
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
        return if (e.incognito) null else LocalMedia.position(a, f)
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

    /** Stops for good: the place is remembered, the notification goes, and the screen closes. */
    /**
     * 2.9.2: sound above the phone's loudest, from 0 (none) to 1 (about twice as loud), for quiet
     * films on a quiet speaker. Android's loudness enhancer on the player's sound; it ends with the player.
     */
    var boost = 0f
        private set
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var enhancerSession = 0

    fun setBoost(amount: Float) {
        boost = amount.coerceIn(0f, 1f)
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

    fun release() {
        val p = player ?: return
        runCatching { enhancer?.release() }
        enhancer = null
        boost = 0f
        saveNow()
        cancelSleep(notify = false)
        main.removeCallbacks(saver)
        p.removeListener(listener)
        p.release()
        player = null
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

    /** Remembers where the file playing is (nothing for website videos, shared files and incognito). */
    fun saveNow() {
        val p = player ?: return
        val a = app ?: return
        val e = current ?: return
        val f = e.file ?: return
        if (e.incognito) return
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
            p.pauseAtEndOfMediaItems = true
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
        if (sleepAtEnd) player?.pauseAtEndOfMediaItems = false
        sleepAtEnd = false
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

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val before = entries.getOrNull(playingIndex)
            // Played to its end (and on to the next, or round again): nothing to go back to.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                before?.file?.let { f -> app?.let { LocalMedia.forget(it, f.path) } }
            }
            playingIndex = p.currentMediaItemIndex
            clearInfo()
            // The next episode of a series goes on where it was left off too.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) current?.let { e -> resumeAt(e)?.let { p.seekTo(it) } }
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
                changed()
                app?.let { MusicService.sync(it) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                dismissed = false
                main.removeCallbacks(saver)
                main.postDelayed(saver, 30_000)
            } else {
                saveNow()
                // "At the end" of the sleep timer: it has paused there, so it's done.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) {
                    L.d("Player: sleep timer stopped at the end")
                    cancelSleep()
                }
            }
            app?.let { MusicService.sync(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                current?.file?.let { f -> app?.let { LocalMedia.forget(it, f.path) } }
            }
            app?.let { MusicService.sync(it) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            MusicService.positionChanged()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            MusicService.positionChanged()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            L.w("Player: ${current?.logName ?: "media"} couldn't play (${error.errorCodeName})")
            app?.let { MusicService.sync(it) }
        }
    }
}
